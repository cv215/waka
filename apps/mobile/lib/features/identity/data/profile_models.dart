import 'dart:convert';

import 'login_models.dart';

class ProfileProblem implements Exception {
  const ProfileProblem(this.failure);
  final ProfileFailure failure;
  @override
  String toString() => 'ProfileProblem(${failure.name})';
}

enum ProfileFailure {
  denied,
  versionConflict,
  keyReused,
  replayExpired,
  unavailable,
  uncertain,
  invalidResponse,
  storage,
  invalid,
  /// The server refused the request itself (400, 413, 415): it was not applied.
  rejected,
}

/// Same local rules on input and on restore, so an accepted name can never make the
/// protected record unreadable. The server stays the authority (NFC, Unicode categories).
bool isAcceptableProfileName(String name) =>
    name.isNotEmpty &&
    name.trim() == name &&
    name.runes.length <= 80 &&
    utf8.encode(name).length <= 320 &&
    !name.runes.any(_unsafeNameCodePoint);

bool _unsafeNameCodePoint(int r) =>
    r < 32 ||
    (r >= 127 && r <= 159) ||
    r == 0x00ad ||
    r == 0x061c ||
    r == 0x180e ||
    (r >= 0x200b && r <= 0x200f) ||
    (r >= 0x2028 && r <= 0x202e) ||
    (r >= 0x2060 && r <= 0x206f) ||
    r == 0xfeff ||
    (r >= 0xfff9 && r <= 0xfffb) ||
    (r >= 0x1bca0 && r <= 0x1bca3) ||
    (r >= 0x1d173 && r <= 0x1d17a) ||
    (r >= 0xe0000 && r <= 0xe0fff);

class ProfileSnapshot {
  const ProfileSnapshot({
    required this.accountId,
    required this.version,
    required this.displayName,
    required this.updatedAt,
    required this.serverTime,
    required this.replay,
  });
  final String accountId;
  final int version;
  final String? displayName;
  final DateTime? updatedAt;
  final DateTime serverTime;
  final bool replay;

  factory ProfileSnapshot.parse(
    Object? value,
    LoginSession session, {
    int? expectedVersion,
  }) {
    try {
      final m = exactObject(value, {
        'accountId',
        'version',
        'displayName',
        'updatedAt',
        'serverTime',
        'idempotentReplay',
      });
      if (m['accountId'] != session.accountId ||
          m['version'] is! int ||
          (m['version'] as int) < 1 ||
          (m['version'] as int) > 9007199254740991 ||
          (m['displayName'] != null && m['displayName'] is! String) ||
          (m['updatedAt'] != null && m['updatedAt'] is! String) ||
          m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      final name = m['displayName'] as String?;
      if (name != null && !isAcceptableProfileName(name)) {
        throw const FormatException();
      }
      final updatedAt = m['updatedAt'] == null ? null : utcDate(m['updatedAt']);
      final serverTime = utcDate(m['serverTime']);
      if (updatedAt != null && updatedAt.isAfter(serverTime)) {
        throw const FormatException();
      }
      final version = m['version'] as int;
      final replay = m['idempotentReplay'] as bool;
      if ((version == 1 && (name != null || updatedAt != null || replay)) ||
          (version >= 2 && updatedAt == null) ||
          (expectedVersion == null && replay) ||
          (expectedVersion != null && version != expectedVersion + 1)) {
        throw const FormatException();
      }
      return ProfileSnapshot(
        accountId: session.accountId,
        version: version,
        displayName: name,
        updatedAt: updatedAt,
        serverTime: serverTime,
        replay: replay,
      );
    } on LoginProblem {
      throw const ProfileProblem(ProfileFailure.invalidResponse);
    } on FormatException {
      throw const ProfileProblem(ProfileFailure.invalidResponse);
    } on TypeError {
      throw const ProfileProblem(ProfileFailure.invalidResponse);
    }
  }

  @override
  String toString() => 'ProfileSnapshot[REDACTED]';
}

class ProfileCommand {
  const ProfileCommand({
    required this.key,
    required this.expectedVersion,
    required this.displayName,
    required this.accountId,
  });
  final String key, accountId;
  final int expectedVersion;
  final String? displayName;
  Map<String, Object?> toJson() => {
    'key': key,
    'expectedVersion': expectedVersion,
    'displayName': displayName,
    'accountId': accountId,
  };
  factory ProfileCommand.parse(Object? value) {
    try {
      final m = exactObject(value, {
        'key',
        'expectedVersion',
        'displayName',
        'accountId',
      });
      if (!isLoginUuid(m['key']) ||
          !isLoginUuid(m['accountId']) ||
          m['expectedVersion'] is! int ||
          (m['expectedVersion'] as int) < 1 ||
          (m['expectedVersion'] as int) > 9007199254740991 ||
          (m['displayName'] != null && m['displayName'] is! String)) {
        throw const FormatException();
      }
      final name = m['displayName'] as String?;
      if (name != null && !isAcceptableProfileName(name)) {
        throw const FormatException();
      }
      return ProfileCommand(
        key: m['key'] as String,
        expectedVersion: m['expectedVersion'] as int,
        displayName: name,
        accountId: m['accountId'] as String,
      );
    } on ProfileProblem {
      rethrow;
    } on FormatException {
      throw const ProfileProblem(ProfileFailure.storage);
    } on LoginProblem {
      throw const ProfileProblem(ProfileFailure.storage);
    } on TypeError {
      throw const ProfileProblem(ProfileFailure.storage);
    }
  }

  @override
  String toString() => 'ProfileCommand[REDACTED]';
}
