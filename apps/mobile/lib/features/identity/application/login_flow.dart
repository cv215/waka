import 'package:flutter/foundation.dart';

import '../data/driver_application_models.dart';
import '../data/login_client.dart';
import '../data/login_models.dart';
import '../data/login_store.dart';
import '../data/profile_models.dart';

/// Serializes user commands and persists their identity before any network effect.
class LoginFlow extends ChangeNotifier {
  LoginFlow(
    this.gateway,
    this.store, {
    ProfileGateway? profileGateway,
    DriverApplicationGateway? applicationGateway,
  }) : profileGateway = profileGateway ?? (gateway is ProfileGateway ? gateway as ProfileGateway : null),
       applicationGateway = applicationGateway ??
           (gateway is DriverApplicationGateway ? gateway as DriverApplicationGateway : null);
  final LoginGateway gateway;
  final ProfileGateway? profileGateway;
  final DriverApplicationGateway? applicationGateway;
  final LoginStore store;
  ApplicationFailure? applicationFailure;
  DriverApplicationSnapshot? _application;
  bool _applicationRead = false;
  bool applicationNeedsReview = false;

  /// The confirmed account's own draft; null while unread, absent or not confirmed.
  DriverApplicationSnapshot? get application => confirmedNow ? _application : null;
  /// True once a read established that the confirmed account has no open candidature.
  bool get noApplication => confirmedNow && _applicationRead && _application == null && !applicationNeedsReview;
  ApplicationCommand? get pendingApplication {
    final command = _snapshot?.applicationCommand;
    return command != null && command.accountId == session?.accountId ? command : null;
  }
  bool get canStartApplication =>
      noApplication && applicationGateway != null && pendingApplication == null;
  /// R16: an approved driver may change situation (taxi <-> private); his profile keeps serving until the decision.
  bool canChangeTo(String category) =>
      application?.state == 'approved' && application!.requestedCategory != category && applicationGateway != null &&
      !applicationNeedsReview && pendingApplication == null;
  bool get canEditVehicle =>
      application != null && application!.editable && applicationGateway != null && !applicationNeedsReview &&
      pendingApplication == null;
  LoginSnapshot? _snapshot;
  bool busy = false, ready = false, sessionSaved = true;
  bool _confirmedNow = false, _sessionRejected = false;
  LoginAccount? _account;
  Stopwatch? _serverElapsed;
  bool _closed = false;
  LoginFailure? failure;
  ProfileFailure? profileFailure;
  ProfileSnapshot? _profile;
  bool profileNeedsReview = false;
  String? notice;
  /// Identifier of this installation's context, sent with every authenticated request.
  String? get clientInstance => _snapshot?.client;
  LoginAttempt? get attempt => _snapshot?.attempt;
  LoginSession? get session => _snapshot?.session;
  bool get logoutPending => _snapshot?.logoutKey != null;
  /// Only shown while the current session is confirmed; never carried to another account.
  ProfileSnapshot? get profile => confirmedNow ? _profile : null;
  ProfileCommand? get pendingProfile {
    final command = _snapshot?.profileCommand;
    return command != null && command.accountId == session?.accountId ? command : null;
  }
  bool get canEditProfile =>
      confirmedNow && profile != null && profileGateway != null && !profileNeedsReview && pendingProfile == null;
  bool get cleanupPending => _sessionRejected && session != null;
  bool get confirmedNow =>
      _confirmedNow &&
      sessionSaved &&
      !logoutPending &&
      !cleanupPending &&
      !locallyExpired;
  LoginAccount? get account => confirmedNow ? _account : null;
  DateTime get _sessionTime => _account != null && _serverElapsed != null
      ? _account!.serverTime.add(_serverElapsed!.elapsed)
      : DateTime.now().toUtc();
  bool get locallyExpired =>
      session != null &&
      (!_sessionTime.isBefore(session!.absoluteExpiresAt) ||
          !_sessionTime.isBefore(session!.idleExpiresAt));

  Future<void> initialize() async {
    if (busy || ready || _closed) return;
    busy = true;
    failure = null;
    _changed();
    try {
      final existing = await store.read();
      final snapshot = existing ?? LoginSnapshot(randomLoginId());
      if (existing == null) await store.write(snapshot);
      _snapshot = snapshot;
      ready = true;
      _forgetConfirmation();
      if (logoutPending) {
        await _revoke();
      } else if (session != null) {
        await _checkAccount();
      }
    } on LoginProblem catch (problem) {
      failure = problem.failure;
    } on Exception {
      failure = LoginFailure.storage;
    } finally {
      busy = false;
      _changed();
    }
  }

  Future<void> request(String phone) => _run(() async {
    if (session != null) throw const LoginProblem(LoginFailure.invalid);
    final normalized = normalizeLoginPhone(phone);
    final command = attempt ?? LoginAttempt.start(normalized);
    if (command.phone != normalized) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    final pending = LoginSnapshot(_snapshot!.client, attempt: command);
    await store.write(pending);
    _snapshot = pending;
    final challenge = await gateway.request(command, pending.client);
    final received = LoginSnapshot(
      pending.client,
      attempt: command.withChallenge(challenge),
    );
    _snapshot = received;
    await store.write(received);
  });

  Future<void> verify(String code) => _run(() async {
    final command = attempt;
    if (command?.challenge == null || session != null) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    command!.verificationKey(code);
    if (!DateTime.now().toUtc().isBefore(command.challenge!.expiresAt)) {
      throw const LoginProblem(LoginFailure.denied);
    }
    await store.write(_snapshot!);
    final granted = await gateway.verify(command, _snapshot!.client, code);
    _snapshot = LoginSnapshot(_snapshot!.client, session: granted);
    sessionSaved = false;
    _forgetConfirmation();
    await store.write(_snapshot!);
    sessionSaved = true;
    _confirmedNow = true;
  });

  Future<void> retrySessionStorage() => _run(() async {
    if (session == null || logoutPending || cleanupPending) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    await store.write(_snapshot!);
    sessionSaved = true;
    _forgetConfirmation();
  });

  Future<void> checkAccount() => _run(_checkAccount);

  Future<void> _checkAccount() async {
    if (session == null || !sessionSaved || logoutPending || cleanupPending) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    _forgetConfirmation();
    final sent = session!;
    // Starting before the request conservatively includes network transit time.
    // A changed device clock must not extend a server-confirmed session.
    final elapsed = Stopwatch()..start();
    try {
      final received = await gateway.me(sent, _snapshot!.client);
      // A pending profile command must survive revalidation: its key alone can recover the outcome.
      final updated = _snapshot!.withSession(received.refresh(sent));
      _snapshot = updated;
      sessionSaved = false;
      await store.write(updated);
      sessionSaved = true;
      _account = received;
      _serverElapsed = elapsed;
      _confirmedNow = true;
      await _readProfile();
    } on LoginProblem catch (problem) {
      if (problem.failure != LoginFailure.denied) rethrow;
      _sessionRejected = true;
      await _clearRejectedSession();
    } finally {
      if (_serverElapsed != elapsed) elapsed.stop();
    }
  }

  Future<void> retrySessionCleanup() => _run(() async {
    if (!cleanupPending || logoutPending) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    await _clearRejectedSession();
  });

  Future<void> _clearRejectedSession() async {
    _forgetConfirmation();
    final cleared = LoginSnapshot(_snapshot!.client);
    await store.write(cleared);
    _snapshot = cleared;
    sessionSaved = true;
    _sessionRejected = false;
    notice = 'La session n’est plus acceptée. Les données de connexion locales ont été effacées. Reconnecte-toi pour continuer.';
  }

  Future<void> refreshProfile() => _run(_readProfile);

  Future<void> _readProfile() async {
    if (!confirmedNow || profileGateway == null || session == null || logoutPending) return;
    final sent = session!;
    profileNeedsReview = true;
    _profile = null;
    _changed();
    try {
      final received = await profileGateway!.profile(sent, _snapshot!.client);
      // The session may have been replaced or forgotten while waiting.
      if (_closed || session?.id != sent.id || !confirmedNow) return;
      _profile = received;
      profileNeedsReview = false;
      profileFailure = null;
      _changed();
    } on ProfileProblem catch (problem) {
      if (_closed || session?.id != sent.id) return;
      profileFailure = problem.failure;
      _profile = null;
      _changed();
      if (problem.failure == ProfileFailure.denied) {
        _sessionRejected = true;
        await _clearRejectedSession();
      }
      return;
    }
    if (pendingProfile != null) {
      notice = 'Une modification de profil reste à reprendre. Reprends-la pour connaître son résultat.';
    }
  }

  Future<void> saveDisplayName(String? value) => _run(() async {
    if (!canEditProfile) throw const ProfileProblem(ProfileFailure.invalid);
    final normalized = value?.trim();
    if (normalized != null && !isAcceptableProfileName(normalized)) {
      throw const ProfileProblem(ProfileFailure.invalid);
    }
    final command = ProfileCommand(key: randomLoginId(), expectedVersion: profile!.version, displayName: normalized, accountId: session!.accountId);
    final pending = _snapshot!.withProfileCommand(command);
    try {
      await store.write(pending);
    } on Exception {
      // Nothing was sent: no network effect without a persisted key.
      throw const ProfileProblem(ProfileFailure.storage);
    }
    _snapshot = pending;
    await _sendProfileCommand(command);
  });

  /// Resends the same key and body; a lost answer never gets a new key automatically.
  Future<void> resumeProfile() => _run(() async {
    final command = pendingProfile;
    if (command == null || !confirmedNow || profileGateway == null) {
      throw const ProfileProblem(ProfileFailure.invalid);
    }
    await _sendProfileCommand(command);
  });

  Future<void> _sendProfileCommand(ProfileCommand command) async {
    final sent = session!;
    try {
      await profileGateway!.updateProfile(sent, _snapshot!.client, command);
    } on ProfileProblem catch (problem) {
      if (_closed || session?.id != sent.id) rethrow;
      if (problem.failure == ProfileFailure.denied) {
        _sessionRejected = true;
        await _clearRejectedSession();
      } else if (_settles(problem.failure)) {
        // The server will never apply this command: forget it, then reread the current state.
        await _forgetProfileCommand();
        await _readProfile();
      }
      // Uncertain, unavailable or unverifiable answers keep the command for a resume.
      rethrow;
    }
    if (_closed || session?.id != sent.id) return;
    // The effect is established; a replay may contain an old profile, so only GET shows the current one.
    await _forgetProfileCommand();
    await _readProfile();
  }

  static bool _settles(ProfileFailure failure) => const {
    ProfileFailure.versionConflict,
    ProfileFailure.keyReused,
    ProfileFailure.replayExpired,
    ProfileFailure.rejected,
  }.contains(failure);

  Future<void> _forgetProfileCommand() async {
    profileNeedsReview = true;
    _profile = null;
    final cleared = _snapshot!.withProfileCommand(null);
    await store.write(cleared);
    _snapshot = cleared;
  }

  Future<void> refreshApplication() => _run(_readApplication);

  Future<void> _readApplication() async {
    if (!confirmedNow || applicationGateway == null || session == null || logoutPending) return;
    final sent = session!;
    applicationNeedsReview = true;
    _application = null;
    _applicationRead = false;
    _changed();
    try {
      final received = await applicationGateway!.currentApplication(sent, _snapshot!.client);
      if (_closed || session?.id != sent.id || !confirmedNow) return;
      _application = received;
      _applicationRead = true;
      applicationNeedsReview = false;
      applicationFailure = null;
      _changed();
    } on ApplicationProblem catch (problem) {
      if (_closed || session?.id != sent.id) return;
      applicationFailure = problem.failure;
      _changed();
      if (problem.failure == ApplicationFailure.denied) {
        _sessionRejected = true;
        await _clearRejectedSession();
      }
      return;
    }
    if (pendingApplication != null) {
      notice = 'Une demande de candidature reste à reprendre. Reprends-la pour connaître son résultat.';
    }
  }

  /// Creates a draft only: choosing a category gives no admission and no access. For an approved driver, the draft of
  /// the other category is a change of situation (R16).
  Future<void> startApplication(String category) => _run(() async {
    if (!(canStartApplication || canChangeTo(category)) || (category != 'taxi' && category != 'private')) {
      throw const ApplicationProblem(ApplicationFailure.invalid);
    }
    await _persistAndSend(CreateApplicationCommand(randomLoginId(), session!.accountId, category));
  });

  Future<void> saveVehicle(VehicleData input) => _run(() async {
    final vehicle = input.normalized();
    if (!canEditVehicle || !vehicle.acceptable) throw const ApplicationProblem(ApplicationFailure.invalid);
    await _persistAndSend(VehicleApplicationCommand(
      randomLoginId(),
      session!.accountId,
      application!.applicationId,
      application!.version,
      vehicle,
    ));
  });

  /// Sends the exact version shown to examination; the server checks fields, photos and documents.
  Future<void> submitApplication() => _run(() async {
    if (!canEditVehicle) throw const ApplicationProblem(ApplicationFailure.invalid);
    await _persistAndSend(SubmitApplicationCommand(
      randomLoginId(),
      session!.accountId,
      application!.applicationId,
      application!.version,
    ));
  });

  /// Resends the same key and body; a lost answer never gets a new key automatically.
  Future<void> resumeApplication() => _run(() async {
    final command = pendingApplication;
    if (command == null || !confirmedNow || applicationGateway == null) {
      throw const ApplicationProblem(ApplicationFailure.invalid);
    }
    await _sendApplicationCommand(command);
  });

  Future<void> _persistAndSend(ApplicationCommand command) async {
    final pending = _snapshot!.withApplicationCommand(command);
    try {
      await store.write(pending);
    } on Exception {
      // Nothing was sent: no network effect without a persisted key.
      throw const ApplicationProblem(ApplicationFailure.storage);
    }
    _snapshot = pending;
    await _sendApplicationCommand(command);
  }

  Future<void> _sendApplicationCommand(ApplicationCommand command) async {
    final sent = session!;
    try {
      await applicationGateway!.sendApplication(sent, _snapshot!.client, command);
    } on ApplicationProblem catch (problem) {
      if (_closed || session?.id != sent.id) rethrow;
      if (problem.failure == ApplicationFailure.denied) {
        _sessionRejected = true;
        await _clearRejectedSession();
      } else if (_applicationSettles(problem.failure)) {
        // The server will never apply this command: forget it, then reread the current state.
        await _forgetApplicationCommand();
        await _readApplication();
      }
      rethrow;
    }
    if (_closed || session?.id != sent.id) return;
    // The effect is established; a replay may hold an old state, so only a read shows the current one.
    await _forgetApplicationCommand();
    await _readApplication();
  }

  static bool _applicationSettles(ApplicationFailure failure) => const {
    ApplicationFailure.versionConflict,
    ApplicationFailure.keyReused,
    ApplicationFailure.replayExpired,
    ApplicationFailure.alreadyOpen,
    ApplicationFailure.notFound,
    ApplicationFailure.rejected,
    ApplicationFailure.incomplete,
    ApplicationFailure.notEditable,
  }.contains(failure);

  Future<void> _forgetApplicationCommand() async {
    applicationNeedsReview = true;
    _application = null;
    _applicationRead = false;
    final cleared = _snapshot!.withApplicationCommand(null);
    await store.write(cleared);
    _snapshot = cleared;
  }

  Future<void> revoke() => _run(_revoke);

  Future<void> _revoke() async {
    if (session == null) throw const LoginProblem(LoginFailure.invalid);
    final pending = LoginSnapshot(
      _snapshot!.client,
      session: session,
      logoutKey: _snapshot!.logoutKey ?? randomLoginId(),
    );
    _snapshot = pending;
    _forgetConfirmation();
    _changed();
    await store.write(pending);
    bool alreadyInvalid = false;
    try {
      await gateway.revoke(
        pending.session!,
        pending.client,
        pending.logoutKey!,
      );
    } on LoginProblem catch (problem) {
      if (problem.failure != LoginFailure.denied) rethrow;
      alreadyInvalid = true;
    }
    final cleared = LoginSnapshot(pending.client);
    await store.write(cleared);
    _snapshot = cleared;
    sessionSaved = true;
    _sessionRejected = false;
    notice = alreadyInvalid
        ? 'La session est refusée par le serveur. Les données de connexion locales ont été effacées.'
        : 'Déconnexion confirmée.';
  }

  Future<void> restart() => _run(() async {
    if (session != null) throw const LoginProblem(LoginFailure.invalid);
    final cleared = LoginSnapshot(_snapshot!.client);
    await store.write(cleared);
    _snapshot = cleared;
  });

  Future<void> _run(Future<void> Function() operation) async {
    if (busy || !ready || _closed) return;
    busy = true;
    failure = null;
    profileFailure = null;
    applicationFailure = null;
    notice = null;
    _changed();
    try {
      await operation();
    } on LoginProblem catch (problem) {
      failure = problem.failure;
    } on ProfileProblem catch (problem) {
      profileFailure = problem.failure;
    } on ApplicationProblem catch (problem) {
      applicationFailure = problem.failure;
    } on Exception {
      failure = LoginFailure.storage;
    } finally {
      busy = false;
      _changed();
    }
  }

  void _changed() {
    if (!_closed) notifyListeners();
  }

  void _forgetConfirmation() {
    _confirmedNow = false;
    _account = null;
    _profile = null;
    profileNeedsReview = false;
    _application = null;
    _applicationRead = false;
    applicationNeedsReview = false;
    _serverElapsed?.stop();
    _serverElapsed = null;
  }

  @override
  void dispose() {
    _closed = true;
    _forgetConfirmation();
    gateway.dispose();
    if (!identical(profileGateway, gateway)) profileGateway?.dispose();
    if (!identical(applicationGateway, gateway) && !identical(applicationGateway, profileGateway)) {
      applicationGateway?.dispose();
    }
    super.dispose();
  }
}
