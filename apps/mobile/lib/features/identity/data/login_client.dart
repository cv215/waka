import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../../../core/config/local_api_configuration.dart';
import 'driver_application_models.dart';
import 'login_models.dart';
import 'profile_models.dart';

abstract interface class LoginGateway {
  Future<LoginChallenge> request(LoginAttempt attempt, String client);
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code);
  Future<LoginAccount> me(LoginSession session, String client);
  Future<void> revoke(LoginSession session, String client, String key);
  void dispose();
}

abstract interface class ProfileGateway {
  Future<ProfileSnapshot> profile(LoginSession session, String client);
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command);
  void dispose();
}

/// Draft candidature routes (A26-A28 limited to the vehicle). No admission is ever read here.
abstract interface class DriverApplicationGateway {
  /// Null when the account has no open candidature (404).
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession session, String client);
  Future<DriverApplicationSnapshot> sendApplication(LoginSession session, String client, ApplicationCommand command);
  void dispose();
}

class LoginClient implements LoginGateway, ProfileGateway, DriverApplicationGateway {
  LoginClient(this.configuration);
  final LocalApiConfiguration configuration;
  final Set<HttpClient> _active = {};
  bool _closed = false;

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession session, String client) async {
    final (status, body) = await _applicationSend(session, client, 'GET', '/api/v1/me/driver-application');
    return status == 404 ? null : DriverApplicationSnapshot.parse(body, session);
  }

  @override
  Future<DriverApplicationSnapshot> sendApplication(
    LoginSession session,
    String client,
    ApplicationCommand command,
  ) async {
    final (status, body) = switch (command) {
      CreateApplicationCommand c => await _applicationSend(session, client, 'POST', '/api/v1/driver-applications',
          command: c, body: {'requestedCategory': c.requestedCategory}),
      VehicleApplicationCommand c => await _applicationSend(session, client, 'PATCH',
          '/api/v1/driver-applications/${c.applicationId}',
          command: c, body: {'expectedVersion': c.expectedVersion, 'vehicleFields': c.vehicle.toJson()}),
    };
    if (status != (command is CreateApplicationCommand ? 201 : 200)) {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    }
    return DriverApplicationSnapshot.parse(body, session, command: command);
  }

  /// Returns (status, decoded JSON) for 200/201, or (404, null); every other answer is a problem.
  Future<(int, Object?)> _applicationSend(
    LoginSession session,
    String client,
    String method,
    String path, {
    ApplicationCommand? command,
    Map<String, Object?>? body,
  }) async {
    if (_closed || !configuration.isConfigured) throw const ApplicationProblem(ApplicationFailure.unavailable);
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(method, configuration.baseUri!.resolve(path));
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        request.headers.set('Authorization', 'Bearer ${session.token}');
        request.headers.set('X-Waka-Client-Instance', client);
        if (command != null) {
          request.headers.set('Idempotency-Key', command.key);
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode(body)));
        }
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) throw const ApplicationProblem(ApplicationFailure.invalidResponse);
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) != 'no-store') {
          throw const ApplicationProblem(ApplicationFailure.invalidResponse);
        }
        final status = response.statusCode;
        if (status == 200 || status == 201) {
          if (response.headers.contentType?.mimeType != 'application/json') {
            throw const ApplicationProblem(ApplicationFailure.invalidResponse);
          }
          return (status, jsonDecode(utf8.decode(bytes)));
        }
        if (response.headers.contentType?.mimeType != 'application/problem+json') {
          throw const ApplicationProblem(ApplicationFailure.invalidResponse);
        }
        final problem = jsonDecode(utf8.decode(bytes));
        final code = problem is Map && problem['code'] is String ? problem['code'] as String : null;
        // For the read, 404 is the normal answer of an account without an open candidature.
        if (status == 404 && command == null) return (404, null);
        throw ApplicationProblem(switch ((status, code)) {
          (401 || 403, _) => ApplicationFailure.denied,
          (404, _) => ApplicationFailure.notFound,
          (400 || 413 || 415, _) => ApplicationFailure.rejected,
          (409, 'VERSION_CONFLICT') => ApplicationFailure.versionConflict,
          (409, 'IDEMPOTENCY_KEY_REUSED') => ApplicationFailure.keyReused,
          (409, 'APPLICATION_ALREADY_OPEN') => ApplicationFailure.alreadyOpen,
          (410, 'REPLAY_EXPIRED') => ApplicationFailure.replayExpired,
          (>= 500, _) => ApplicationFailure.unavailable,
          _ => ApplicationFailure.invalidResponse,
        });
      })().timeout(const Duration(seconds: 8));
    } on ApplicationProblem {
      rethrow;
    } on FormatException {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    } on Exception {
      throw const ApplicationProblem(ApplicationFailure.uncertain);
    } finally {
      http.close(force: true);
      _active.remove(http);
    }
  }

  @override
  Future<ProfileSnapshot> profile(LoginSession session, String client) async =>
      ProfileSnapshot.parse(await _profileSend(session, client), session);

  @override
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command) async =>
      ProfileSnapshot.parse(await _profileSend(session, client, command), session, expectedVersion: command.expectedVersion);

  Future<Object?> _profileSend(LoginSession session, String client, [ProfileCommand? command]) async {
    if (_closed || !configuration.isConfigured) throw const ProfileProblem(ProfileFailure.unavailable);
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(command == null ? 'GET' : 'PATCH', configuration.baseUri!.resolve(command == null ? '/api/v1/me/profile' : '/api/v1/me'));
        request.followRedirects = false;
        request.headers.set(HttpHeaders.acceptHeader, 'application/json, application/problem+json');
        request.headers.set('Authorization', 'Bearer ${session.token}');
        request.headers.set('X-Waka-Client-Instance', client);
        if (command != null) {
          request.headers.set('Idempotency-Key', command.key);
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode({'expectedVersion': command.expectedVersion, 'displayName': command.displayName})));
        }
        final response = await request.close();
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) throw const ProfileProblem(ProfileFailure.invalidResponse);
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) != 'no-store') throw const ProfileProblem(ProfileFailure.invalidResponse);
        if (response.statusCode != 200) {
          String? code;
          if (response.headers.contentType?.mimeType != 'application/problem+json') throw const ProfileProblem(ProfileFailure.invalidResponse);
          try { final body = jsonDecode(utf8.decode(bytes)); if (body is Map && body['code'] is String) code = body['code'] as String; } on Exception { throw const ProfileProblem(ProfileFailure.invalidResponse); }
          if (response.statusCode == 401 || response.statusCode == 403) throw const ProfileProblem(ProfileFailure.denied);
          if (response.statusCode == 400 || response.statusCode == 413 || response.statusCode == 415) throw const ProfileProblem(ProfileFailure.rejected);
          if (response.statusCode == 409) throw ProfileProblem(code == 'VERSION_CONFLICT' ? ProfileFailure.versionConflict : code == 'IDEMPOTENCY_KEY_REUSED' ? ProfileFailure.keyReused : ProfileFailure.invalidResponse);
          if (response.statusCode == 410 && code == 'REPLAY_EXPIRED') throw const ProfileProblem(ProfileFailure.replayExpired);
          if (response.statusCode >= 500) throw const ProfileProblem(ProfileFailure.unavailable);
          throw const ProfileProblem(ProfileFailure.invalidResponse);
        }
        if (response.headers.contentType?.mimeType != 'application/json') throw const ProfileProblem(ProfileFailure.invalidResponse);
        return jsonDecode(utf8.decode(bytes));
      })().timeout(const Duration(seconds: 8));
    } on ProfileProblem { rethrow; }
    on FormatException { throw const ProfileProblem(ProfileFailure.invalidResponse); }
    on Exception { throw const ProfileProblem(ProfileFailure.uncertain); }
    finally { http.close(force: true); _active.remove(http); }
  }

  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async =>
      LoginChallenge.parse(
        await _send('/api/v1/auth/phone-challenges', attempt.requestKey, 202, {
          'phoneNumber': attempt.phone,
          'purpose': 'login',
          'clientInstanceRef': client,
        }),
      );

  @override
  Future<LoginSession> verify(
    LoginAttempt attempt,
    String client,
    String code,
  ) async => LoginSession.parse(
    await _send(
      '/api/v1/auth/phone-challenges/${attempt.challenge!.id}/verifications',
      attempt.verificationKey(code),
      200,
      {'phoneNumber': attempt.phone, 'code': code, 'clientInstanceRef': client},
    ),
  );

  @override
  Future<void> revoke(LoginSession session, String client, String key) async {
    await _send(
      '/api/v1/auth/sessions/${session.id}/revocations',
      key,
      204,
      null,
      {
        'Authorization': 'Bearer ${session.token}',
        'X-Waka-Client-Instance': client,
      },
    );
  }

  @override
  Future<LoginAccount> me(LoginSession session, String client) async =>
      LoginAccount.parse(
        await _send('/api/v1/me', null, 200, null, {
          'Authorization': 'Bearer ${session.token}',
          'X-Waka-Client-Instance': client,
        }, 'GET'),
        session,
      );

  Future<Object?> _send(
    String path,
    String? key,
    int expected,
    Map<String, Object>? body, [
    Map<String, String> extra = const {},
    String method = 'POST',
  ]) async {
    if (_closed || !configuration.isConfigured) {
      throw const LoginProblem(LoginFailure.unavailable);
    }
    final http = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(http);
    try {
      return await (() async {
        final request = await http.openUrl(
          method,
          configuration.baseUri!.resolve(path),
        );
        request.followRedirects = false;
        if (key != null) request.headers.set('Idempotency-Key', key);
        request.headers.set(
          HttpHeaders.acceptHeader,
          'application/json, application/problem+json',
        );
        extra.forEach(request.headers.set);
        if (body != null) {
          request.headers.contentType = ContentType.json;
          request.add(utf8.encode(jsonEncode(body)));
        }
        final response = await request.close();
        if (response.statusCode != expected) {
          throw LoginProblem(switch (response.statusCode) {
            400 || 413 || 415 => LoginFailure.invalid,
            401 || 403 => LoginFailure.denied,
            409 => LoginFailure.conflict,
            429 => LoginFailure.limited,
            >= 500 => LoginFailure.unavailable,
            _ => LoginFailure.invalidResponse,
          });
        }
        final bytes = <int>[];
        await for (final chunk in response) {
          if (bytes.length + chunk.length > 8192) {
            throw const LoginProblem(LoginFailure.invalidResponse);
          }
          bytes.addAll(chunk);
        }
        if (response.headers.value(HttpHeaders.cacheControlHeader) !=
            'no-store') {
          throw const LoginProblem(LoginFailure.invalidResponse);
        }
        if (expected == 204) {
          if (bytes.isNotEmpty) {
            throw const LoginProblem(LoginFailure.invalidResponse);
          }
          return null;
        }
        if (response.headers.contentType?.mimeType != 'application/json') {
          throw const LoginProblem(LoginFailure.invalidResponse);
        }
        return jsonDecode(utf8.decode(bytes));
      })().timeout(const Duration(seconds: 8));
    } on LoginProblem {
      rethrow;
    } on FormatException {
      throw const LoginProblem(LoginFailure.invalidResponse);
    } on Exception {
      throw const LoginProblem(LoginFailure.uncertain);
    } finally {
      http.close(force: true);
      _active.remove(http);
    }
  }

  @override
  void dispose() {
    _closed = true;
    for (final client in _active) {
      client.close(force: true);
    }
    _active.clear();
  }
}
