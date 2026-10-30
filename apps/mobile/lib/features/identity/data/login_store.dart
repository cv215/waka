import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../../../core/platform/device.dart';

import 'driver_application_models.dart';
import 'login_models.dart';
import 'profile_models.dart';

class LoginSnapshot {
  const LoginSnapshot(
    this.client, {
    this.attempt,
    this.session,
    this.logoutKey,
    this.profileCommand,
    this.applicationCommand,
  });
  final String client;
  final LoginAttempt? attempt;
  final LoginSession? session;
  final String? logoutKey;
  final ProfileCommand? profileCommand;
  final ApplicationCommand? applicationCommand;
  Map<String, Object?> toJson() => {
    'version': 3,
    'client': client,
    'attempt': attempt?.toJson(),
    'session': session?.toJson(),
    'logoutKey': logoutKey,
    'profileCommand': profileCommand?.toJson(),
    'applicationCommand': applicationCommand?.toJson(),
  };
  /// Same session with its pending commands; used when only the session is refreshed.
  LoginSnapshot withSession(LoginSession refreshed) => LoginSnapshot(
    client,
    session: refreshed,
    profileCommand: profileCommand,
    applicationCommand: applicationCommand,
  );
  /// Replaces only the profile command; the session and the other pending command are kept.
  LoginSnapshot withProfileCommand(ProfileCommand? command) => LoginSnapshot(
    client,
    session: session,
    profileCommand: command,
    applicationCommand: applicationCommand,
  );
  /// Replaces only the candidature command; the session and the other pending command are kept.
  LoginSnapshot withApplicationCommand(ApplicationCommand? command) => LoginSnapshot(
    client,
    session: session,
    profileCommand: profileCommand,
    applicationCommand: command,
  );
  /// Reads v1 (login only), v2 (+ profile command) and v3 (+ candidature command) without reset.
  factory LoginSnapshot.parse(String raw) {
    if (raw.length > 16384) throw const LoginProblem(LoginFailure.storage);
    final decoded = jsonDecode(raw);
    if (decoded is! Map<String, dynamic>) throw const LoginProblem(LoginFailure.storage);
    final version = decoded['version'];
    final fields = {
      'version', 'client', 'attempt', 'session', 'logoutKey',
      if (version == 2 || version == 3) 'profileCommand',
      if (version == 3) 'applicationCommand',
    };
    final map = exactObject(decoded, fields);
    if ((version != 1 && version != 2 && version != 3) ||
        !isLoginUuid(map['client']) ||
        (map['logoutKey'] != null && !isLoginUuid(map['logoutKey'])) ||
        (map['session'] != null && map['attempt'] != null) ||
        (map['logoutKey'] != null && map['session'] == null) ||
        (map['profileCommand'] != null && map['session'] == null) ||
        (map['applicationCommand'] != null && map['session'] == null)) {
      throw const LoginProblem(LoginFailure.storage);
    }
    return LoginSnapshot(
      map['client'] as String,
      attempt: map['attempt'] == null
          ? null
          : LoginAttempt.parse(map['attempt']),
      session: map['session'] == null
          ? null
          : LoginSession.parse(map['session']),
      logoutKey: map['logoutKey'] as String?,
      profileCommand: map['profileCommand'] == null
          ? null
          : ProfileCommand.parse(map['profileCommand']),
      applicationCommand: map['applicationCommand'] == null
          ? null
          : ApplicationCommand.parse(map['applicationCommand']),
    );
  }
  @override
  String toString() => 'LoginSnapshot[REDACTED]';
}

abstract interface class LoginStore {
  Future<LoginSnapshot?> read();
  Future<void> write(LoginSnapshot snapshot);
}

/// One protected record keeps context, pending commands and session consistent.
/// The Android backup is disabled in the manifest; no ordinary preferences fallback.
class AndroidLoginStore implements LoginStore {
  static const _key = 'waka.identity.v1';
  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(
      resetOnError: false,
      migrateOnAlgorithmChange: false,
      storageNamespace: 'waka_identity',
    ),
  );
  @override
  Future<LoginSnapshot?> read() async {
    try {
      if (!isAndroidDevice) throw const LoginProblem(LoginFailure.storage);
      final raw = await _storage.read(key: _key);
      return raw == null ? null : LoginSnapshot.parse(raw);
    } on Exception {
      throw const LoginProblem(LoginFailure.storage);
    }
  }

  @override
  Future<void> write(LoginSnapshot snapshot) async {
    try {
      if (!isAndroidDevice) throw const LoginProblem(LoginFailure.storage);
      await _storage.write(key: _key, value: jsonEncode(snapshot.toJson()));
    } on Exception {
      throw const LoginProblem(LoginFailure.storage);
    }
  }
}
