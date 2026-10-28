import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

import 'phone_format_client.dart';

enum LoginFailure {
  invalid,
  denied,
  conflict,
  limited,
  unavailable,
  uncertain,
  invalidResponse,
  storage,
}

class LoginProblem implements Exception {
  const LoginProblem(this.failure);
  final LoginFailure failure;
  @override
  String toString() => 'LoginProblem(${failure.name})';
}

bool isLoginUuid(Object? value) =>
    value is String &&
    RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
        .hasMatch(value) &&
    value.length == 36;

String randomLoginId() {
  final random = Random.secure();
  return _uuid(List.generate(16, (_) => random.nextInt(256)));
}

String _uuid(List<int> bytes) {
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  final hex = bytes.map((v) => v.toRadixString(16).padLeft(2, '0')).join();
  return '${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}';
}

String normalizeLoginPhone(String input) {
  if (!isValidCameroonMobilePhoneFormat(input)) {
    throw const LoginProblem(LoginFailure.invalid);
  }
  final compact = input.replaceAll(RegExp(r'[ \u00a0\u202f\-()]'), '');
  return compact.startsWith('+237')
      ? compact
      : compact.startsWith('00237')
      ? '+237${compact.substring(5)}'
      : '+237$compact';
}

Map<String, dynamic> exactObject(Object? value, Set<String> fields) {
  if (value is! Map<String, dynamic> ||
      value.length != fields.length ||
      !value.keys.every(fields.contains)) {
    throw const LoginProblem(LoginFailure.invalidResponse);
  }
  return value;
}

DateTime utcDate(Object? value) {
  if (value is! String ||
      !value.endsWith('Z') ||
      !RegExp(r'^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$')
          .hasMatch(value)) {
    throw const LoginProblem(LoginFailure.invalidResponse);
  }
  try {
    final parsed = DateTime.parse(value).toUtc();
    // DateTime.parse normalizes impossible dates (for example 31 February).
    if (parsed.toIso8601String().substring(0, 19) != value.substring(0, 19)) {
      throw const FormatException();
    }
    return parsed;
  } on FormatException {
    throw const LoginProblem(LoginFailure.invalidResponse);
  }
}

class LoginChallenge {
  const LoginChallenge(
    this.id,
    this.submission,
    this.expiresAt,
    this.destinationHint,
  );
  final String id, submission, destinationHint;
  final DateTime expiresAt;
  factory LoginChallenge.parse(Object? value) {
    final map = exactObject(value, {
      'challengeId',
      'submission',
      'expiresAt',
      'destinationHint',
    });
    if (!isLoginUuid(map['challengeId']) ||
        !const {
          'SENDING',
          'ACCEPTED',
          'REJECTED',
          'UNKNOWN',
        }.contains(map['submission']) ||
        map['destinationHint'] is! String ||
        (map['destinationHint'] as String).length != 9 ||
        !RegExp(r'^•{7}[0-9]{2}$').hasMatch(map['destinationHint'] as String)) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    return LoginChallenge(
      map['challengeId'] as String,
      map['submission'] as String,
      utcDate(map['expiresAt']),
      map['destinationHint'] as String,
    );
  }
  Map<String, Object> toJson() => {
    'challengeId': id,
    'submission': submission,
    'expiresAt': expiresAt.toIso8601String(),
    'destinationHint': destinationHint,
  };
  @override
  String toString() => 'LoginChallenge[REDACTED]';
}

class LoginSession {
  const LoginSession(
    this.id,
    this.accountId,
    this.token,
    this.absoluteExpiresAt,
    this.idleExpiresAt,
  );
  final String id, accountId, token;
  final DateTime absoluteExpiresAt, idleExpiresAt;
  factory LoginSession.parse(Object? value) {
    final map = exactObject(value, {
      'sessionId',
      'accountId',
      'tokenType',
      'token',
      'absoluteExpiresAt',
      'idleExpiresAt',
    });
    if (!isLoginUuid(map['sessionId']) ||
        !isLoginUuid(map['accountId']) ||
        map['tokenType'] != 'Bearer' ||
        map['token'] is! String) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    final token = map['token'] as String;
    if (token.length != 43 || !RegExp(r'^[A-Za-z0-9_-]{43}$').hasMatch(token)) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    try {
      if (base64Url.encode(base64Url.decode('$token=')).replaceAll('=', '') !=
          token) {
        throw const FormatException();
      }
    } on FormatException {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    final absolute = utcDate(map['absoluteExpiresAt']),
        idle = utcDate(map['idleExpiresAt']);
    if (idle.isAfter(absolute)) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    return LoginSession(
      map['sessionId'] as String,
      map['accountId'] as String,
      token,
      absolute,
      idle,
    );
  }
  Map<String, Object> toJson() => {
    'sessionId': id,
    'accountId': accountId,
    'tokenType': 'Bearer',
    'token': token,
    'absoluteExpiresAt': absoluteExpiresAt.toIso8601String(),
    'idleExpiresAt': idleExpiresAt.toIso8601String(),
  };
  @override
  String toString() => 'LoginSession[REDACTED]';
}

/// The only account projection currently supported; it grants no driver rights.
/// It is deliberately never persisted as proof of a current authorization.
class LoginAccount {
  const LoginAccount._(
    this.accountId,
    this.sessionId,
    this.absoluteExpiresAt,
    this.idleExpiresAt,
    this.serverTime,
  );

  final String accountId, sessionId;
  final DateTime absoluteExpiresAt, idleExpiresAt, serverTime;

  factory LoginAccount.parse(Object? value, LoginSession sent) {
    final map = exactObject(value, {
      'accountId',
      'sessionId',
      'absoluteExpiresAt',
      'idleExpiresAt',
      'accountState',
      'roles',
      'serverTime',
    });
    final roles = map['roles'];
    if (map['accountId'] != sent.accountId ||
        map['sessionId'] != sent.id ||
        map['accountState'] != 'ACTIVE' ||
        roles is! List ||
        roles.length != 1 ||
        roles.single != 'CLIENT') {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    final absolute = utcDate(map['absoluteExpiresAt']);
    final idle = utcDate(map['idleExpiresAt']);
    final serverTime = utcDate(map['serverTime']);
    if (absolute != sent.absoluteExpiresAt ||
        !serverTime.isBefore(absolute) ||
        !serverTime.isBefore(idle) ||
        idle.isAfter(absolute)) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    return LoginAccount._(sent.accountId, sent.id, absolute, idle, serverTime);
  }

  LoginSession refresh(LoginSession sent) {
    if (accountId != sent.accountId ||
        sessionId != sent.id ||
        absoluteExpiresAt != sent.absoluteExpiresAt) {
      throw const LoginProblem(LoginFailure.invalidResponse);
    }
    return LoginSession(
      sent.id,
      sent.accountId,
      sent.token,
      absoluteExpiresAt,
      idleExpiresAt,
    );
  }

  @override
  String toString() => 'LoginAccount[REDACTED]';
}

class LoginAttempt {
  const LoginAttempt(this.phone, this.requestKey, this.seed, this.challenge);
  final String phone, requestKey, seed;
  final LoginChallenge? challenge;
  factory LoginAttempt.start(String phone) {
    final random = Random.secure();
    return LoginAttempt(
      normalizeLoginPhone(phone),
      randomLoginId(),
      base64Url.encode(List.generate(32, (_) => random.nextInt(256))),
      null,
    );
  }
  // Re-entering the same code after a lost response/process restart derives the same command.
  // The OTP itself is never written to storage. The seed belongs only in protected storage.
  String verificationKey(String code) {
    if (challenge == null ||
        code.length != 6 ||
        !RegExp(r'^[0-9]{6}$').hasMatch(code)) {
      throw const LoginProblem(LoginFailure.invalid);
    }
    final bytes = Hmac(
      sha256,
      base64Url.decode(seed),
    ).convert(utf8.encode('waka-login-v1:${challenge!.id}:$code')).bytes;
    return _uuid(bytes.take(16).toList());
  }

  LoginAttempt withChallenge(LoginChallenge value) =>
      LoginAttempt(phone, requestKey, seed, value);
  Map<String, Object?> toJson() => {
    'phone': phone,
    'requestKey': requestKey,
    'seed': seed,
    'challenge': challenge?.toJson(),
  };
  factory LoginAttempt.parse(Object? value) {
    final map = exactObject(value, {
      'phone',
      'requestKey',
      'seed',
      'challenge',
    });
    if (map['phone'] is! String ||
        !isLoginUuid(map['requestKey']) ||
        map['seed'] is! String) {
      throw const LoginProblem(LoginFailure.storage);
    }
    final seed = map['seed'] as String;
    try {
      if (seed.length != 44 || base64Url.decode(seed).length != 32) {
        throw const FormatException();
      }
    } on FormatException {
      throw const LoginProblem(LoginFailure.storage);
    }
    return LoginAttempt(
      normalizeLoginPhone(map['phone'] as String),
      map['requestKey'] as String,
      seed,
      map['challenge'] == null ? null : LoginChallenge.parse(map['challenge']),
    );
  }
  @override
  String toString() => 'LoginAttempt[REDACTED]';
}
