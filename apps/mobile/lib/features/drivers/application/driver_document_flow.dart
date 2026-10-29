import 'package:flutter/foundation.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_document_models.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/vehicle_photo_models.dart';

/// Documents that apply to the confirmed account's own file, and the decisions about it. Like the
/// photos, an image is kept in memory only: a lost answer is retried with the same key while the app
/// is open; after a restart the person chooses it again and the server keeps the history.
class DriverDocumentFlow extends ChangeNotifier {
  DriverDocumentFlow(this.login, this.gateway) : _sessionId = login.confirmedNow ? login.session?.id : null {
    login.addListener(_loginChanged);
  }
  final LoginFlow login;
  final DriverDocumentGateway gateway;
  String? _sessionId;
  List<RequirementView>? _requirements;
  List<DecisionView>? _decisions;
  final Map<String, ({String key, List<int> bytes, String type, DateTime? validUntil, String applicationId})> _pending = {};
  String? busyCode;
  bool _closed = false;
  PhotoFailure? failure;

  List<RequirementView>? get requirements => _visible ? _requirements : null;
  List<DecisionView>? get decisions => _visible ? _decisions : null;
  bool get _visible => login.confirmedNow && login.application != null;
  bool get busy => busyCode != null;
  bool hasPending(String code) => _pending.containsKey(code);
  /// Documents the candidate still has to provide before sending (established requirements only).
  List<RequirementView> get missingForSubmission =>
      (requirements ?? const []).where((r) => r.established && r.needsUpload).toList();

  Future<void> refresh() => _run('', () async {
    final (session, client, applicationId) = _context();
    final requirements = await gateway.requirements(session, client, applicationId);
    final decisions = await gateway.decisions(session, client, applicationId);
    if (_stillFor(session, applicationId)) {
      _requirements = requirements;
      _decisions = decisions;
    }
  });

  /// A new choice always gets a new key; [validUntil] is the end date printed on the document, if any.
  Future<void> upload(String code, List<int> bytes, DateTime? validUntil) => _run(code, () async {
    final type = photoContentType(bytes);
    if (type == null) throw const PhotoProblem(PhotoFailure.unsupported);
    if (bytes.length > maxPhotoBytes) throw const PhotoProblem(PhotoFailure.tooLarge);
    final (_, _, applicationId) = _context();
    if (!(_requirements ?? const []).any((r) => r.code == code)) throw const PhotoProblem(PhotoFailure.notApplicable);
    _pending[code] = (key: randomLoginId(), bytes: List.unmodifiable(bytes), type: type, validUntil: validUntil,
        applicationId: applicationId);
    await _send(code);
  });

  Future<void> retry(String code) => _run(code, () async {
    if (!_pending.containsKey(code)) throw const PhotoProblem(PhotoFailure.invalid);
    await _send(code);
  });

  Future<void> _send(String code) async {
    final (session, client, applicationId) = _context();
    final pending = _pending[code]!;
    if (pending.applicationId != applicationId) {
      _pending.remove(code);
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    try {
      await gateway.uploadDocument(session, client, applicationId, code, pending.key, pending.bytes, pending.type,
          pending.validUntil);
    } on PhotoProblem catch (problem) {
      if (problem.failure != PhotoFailure.uncertain && problem.failure != PhotoFailure.unavailable) _pending.remove(code);
      rethrow;
    }
    _pending.remove(code);
    if (!_stillFor(session, applicationId)) return;
    final requirements = await gateway.requirements(session, client, applicationId);
    if (_stillFor(session, applicationId)) _requirements = requirements;
  }

  (LoginSession, String, String) _context() {
    final session = login.session, client = login.clientInstance, application = login.application;
    if (!login.confirmedNow || session == null || client == null || application == null) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return (session, client, application.applicationId);
  }

  bool _stillFor(LoginSession session, String applicationId) =>
      !_closed && login.confirmedNow && login.session?.id == session.id && login.application?.applicationId == applicationId;

  Future<void> _run(String code, Future<void> Function() operation) async {
    if (busyCode != null || _closed) return;
    busyCode = code;
    failure = null;
    _changed();
    try {
      await operation();
    } on PhotoProblem catch (problem) {
      failure = problem.failure;
      if (problem.failure == PhotoFailure.denied) await login.checkAccount();
    } finally {
      busyCode = null;
      _changed();
    }
  }

  void _loginChanged() {
    final current = login.confirmedNow ? login.session?.id : null;
    if (current == _sessionId) return;
    _sessionId = current;
    _requirements = null;
    _decisions = null;
    _pending.clear();
    _changed();
  }

  void _changed() {
    if (!_closed) notifyListeners();
  }

  @override
  void dispose() {
    _closed = true;
    login.removeListener(_loginChanged);
    _pending.clear();
    super.dispose();
  }
}
