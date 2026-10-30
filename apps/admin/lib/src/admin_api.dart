import 'dart:convert';
import 'dart:typed_data';

import 'package:http/http.dart' as http;

import 'models.dart';

/// Refusal or failure of one call; [code] is the server's safe code when one was given.
class ApiProblem implements Exception {
  const ApiProblem(this.status, this.code);
  final int status;
  final String? code;
  /// Network failure or unreadable answer: the outcome of a command is unknown.
  bool get uncertain => status == 0;
  @override
  String toString() => 'ApiProblem($status, $code)';
}

/// Calls the WAKA API from the browser. Proofs are sent in headers only; nothing is stored here.
class AdminApi {
  AdminApi(this.baseUri, {http.Client? client}) : _client = client ?? http.Client() {
    final local = baseUri.scheme == 'http' && {'localhost', '127.0.0.1'}.contains(baseUri.host);
    if (!(local || baseUri.scheme == 'https') || baseUri.path.isNotEmpty && baseUri.path != '/') {
      throw ArgumentError('ADMIN_API_BASE_REFUSED');
    }
  }
  final Uri baseUri;
  final http.Client _client;
  static const _timeout = Duration(seconds: 10);

  Future<Challenge> requestCode(String phone, String clientRef, String key) async => Challenge.parse(await _json(
      'POST', '/api/v1/auth/phone-challenges', 202,
      key: key, body: {'phoneNumber': phone, 'purpose': 'login', 'clientInstanceRef': clientRef}));

  Future<ClientSession> verifyCode(String challengeId, String phone, String code, String clientRef, String key) async =>
      ClientSession.parse(await _json('POST', '/api/v1/auth/phone-challenges/$challengeId/verifications', 200,
          key: key, body: {'phoneNumber': phone, 'code': code, 'clientInstanceRef': clientRef}));

  Future<Enrollment> enroll(ClientSession session, String clientRef) async => Enrollment.parse(
      await _json('POST', '/api/v1/admin/second-factor/enrollments', 201, session: session, clientRef: clientRef));

  Future<void> confirmFactor(ClientSession session, String clientRef, String code) =>
      _json('POST', '/api/v1/admin/second-factor/confirmations', 204,
          session: session, clientRef: clientRef, body: {'code': code});

  Future<AdminSession> openAdmin(ClientSession session, String clientRef, String code) async => AdminSession.parse(
      await _json('POST', '/api/v1/admin/sessions', 201, session: session, clientRef: clientRef, body: {'code': code}));

  Future<void> closeAdmin(ClientSession session, String clientRef, AdminSession admin) =>
      _json('POST', '/api/v1/admin/sessions/current/revocations', 204, session: session, clientRef: clientRef, admin: admin);

  Future<List<WorkItem>> workItems(ClientSession session, String clientRef, AdminSession admin) async =>
      WorkItem.parseList(await _json('GET', '/api/v1/admin/work-items?category=admission', 200,
          session: session, clientRef: clientRef, admin: admin));

  Future<Detail> detail(ClientSession session, String clientRef, AdminSession admin, String applicationId) async =>
      Detail.parse(await _json('GET', '/api/v1/admin/driver-applications/${uuid(applicationId)}', 200,
          session: session, clientRef: clientRef, admin: admin, maxBytes: 131072));

  /// A photo or document image of the file under examination; every read is audited by the server.
  Future<Uint8List> image(ClientSession session, String clientRef, AdminSession admin, {required bool photo,
      required String id}) async {
    final path = photo ? '/api/v1/admin/private-files/${uuid(id)}/content' : '/api/v1/admin/driver-documents/${uuid(id)}/content';
    final response = await _send('GET', path, session: session, clientRef: clientRef, admin: admin);
    if (response.statusCode != 200) throw _problem(response);
    final bytes = response.bodyBytes;
    if (response.headers['content-type'] != 'image/jpeg' || bytes.length < 3 || bytes[0] != 0xFF || bytes[1] != 0xD8) {
      throw const ApiProblem(0, null);
    }
    return bytes;
  }

  /// A38: [key] stays the same when the agent retries after a lost answer.
  Future<Map<String, dynamic>> decide(ClientSession session, String clientRef, AdminSession admin, String applicationId,
      String key, Map<String, Object?> decision) async {
    final value = await _json('POST', '/api/v1/admin/driver-applications/${uuid(applicationId)}/decisions', 201,
        session: session, clientRef: clientRef, admin: admin, key: key, body: decision);
    if (value is! Map<String, dynamic> || value['applicationId'] != applicationId) throw const ApiProblem(0, null);
    return value;
  }

  Future<List<GrantView>> grants(ClientSession session, String clientRef, AdminSession admin) async =>
      GrantView.parseList(await _json('GET', '/api/v1/admin/grants', 200,
          session: session, clientRef: clientRef, admin: admin, maxBytes: 131072));

  /// K19: an identical active grant comes back unchanged (200) instead of a second one (201).
  Future<GrantView> grant(ClientSession session, String clientRef, AdminSession admin, String accountId,
      String function, String reasonCode) async {
    if (!adminFunctions.contains(function) || !reasonPattern.hasMatch(reasonCode)) throw const ApiProblem(400, null);
    final response = await _send('POST', '/api/v1/admin/grants', session: session, clientRef: clientRef, admin: admin,
        body: {'accountId': uuid(accountId), 'function': function, 'reasonCode': reasonCode});
    return GrantView.parse(_accepted(response, {200, 201}));
  }

  Future<GrantView> revoke(ClientSession session, String clientRef, AdminSession admin, String grantId,
      String reasonCode) async {
    if (!reasonPattern.hasMatch(reasonCode)) throw const ApiProblem(400, null);
    return GrantView.parse(await _json('POST', '/api/v1/admin/grants/${uuid(grantId)}/revocations', 200,
        session: session, clientRef: clientRef, admin: admin, body: {'reasonCode': reasonCode}));
  }

  /// A41: newest first; [cursor] is the id of the last entry already shown.
  Future<List<AuditEntry>> audit(ClientSession session, String clientRef, AdminSession admin,
      {String? objectType, String? objectId, int? cursor, int limit = 50}) async {
    final safe = RegExp(r'^[A-Za-z0-9_-]{1,128}$');
    if (objectType != null && !safe.hasMatch(objectType) || objectId != null && !safe.hasMatch(objectId) ||
        cursor != null && cursor < 1 || limit < 1 || limit > 100) {
      throw const ApiProblem(400, null);
    }
    final query = [
      if (objectType != null) 'objectType=$objectType',
      if (objectId != null) 'objectId=$objectId',
      if (cursor != null) 'cursor=$cursor',
      'limit=$limit',
    ].join('&');
    return AuditEntry.parseList(await _json('GET', '/api/v1/admin/audit?$query', 200,
        session: session, clientRef: clientRef, admin: admin, maxBytes: 131072));
  }

  Future<List<PrivacyItem>> privacyRequests(ClientSession session, String clientRef, AdminSession admin) async =>
      PrivacyItem.parseList(await _json('GET', '/api/v1/admin/privacy-requests', 200,
          session: session, clientRef: clientRef, admin: admin, maxBytes: 262144));

  Future<PrivacyItem> closePrivacyRequest(ClientSession session, String clientRef, AdminSession admin, String requestId,
      String outcome, String reasonCode) async {
    if (!{'completed', 'refused'}.contains(outcome) || !reasonPattern.hasMatch(reasonCode)) throw const ApiProblem(400, null);
    return PrivacyItem.parse(await _json('POST', '/api/v1/admin/privacy-requests/${uuid(requestId)}/closures', 200,
        session: session, clientRef: clientRef, admin: admin, body: {'outcome': outcome, 'reasonCode': reasonCode}));
  }

  Future<List<LockedVoyage>> lockedVoyages(ClientSession session, String clientRef, AdminSession admin) async =>
      LockedVoyage.parseList(await _json('GET', '/api/v1/admin/locked-voyages', 200, session: session, clientRef: clientRef, admin: admin));

  Future<LockedVoyage> unlockVoyage(ClientSession session, String clientRef, AdminSession admin, String voyageId, String reasonCode) async {
    if (!reasonPattern.hasMatch(reasonCode)) throw const ApiProblem(400, null);
    return LockedVoyage.parse(await _json('POST', '/api/v1/admin/voyages/${uuid(voyageId)}/boarding-unlocks', 200,
        session: session, clientRef: clientRef, admin: admin, body: {'reasonCode': reasonCode}));
  }

  Object? _accepted(http.Response response, Set<int> expected) {
    if (!expected.contains(response.statusCode)) throw _problem(response);
    if (response.headers['cache-control'] != 'no-store' ||
        !(response.headers['content-type'] ?? '').startsWith('application/json') || response.bodyBytes.length > 32768) {
      throw const ApiProblem(0, null);
    }
    try {
      return jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw const ApiProblem(0, null);
    }
  }

  Future<Object?> _json(String method, String path, int expected, {ClientSession? session, String? clientRef,
      AdminSession? admin, String? key, Map<String, Object?>? body, int maxBytes = 32768}) async {
    final response = await _send(method, path, session: session, clientRef: clientRef, admin: admin, key: key, body: body);
    if (response.statusCode != expected) throw _problem(response);
    if (response.headers['cache-control'] != 'no-store') throw const ApiProblem(0, null);
    if (expected == 204) return null;
    if (response.bodyBytes.length > maxBytes || !(response.headers['content-type'] ?? '').startsWith('application/json')) {
      throw const ApiProblem(0, null);
    }
    try {
      return jsonDecode(utf8.decode(response.bodyBytes));
    } on FormatException {
      throw const ApiProblem(0, null);
    }
  }

  Future<http.Response> _send(String method, String path, {ClientSession? session, String? clientRef,
      AdminSession? admin, String? key, Map<String, Object?>? body}) async {
    final request = http.Request(method, baseUri.resolve(path))
      ..followRedirects = false
      ..headers['Accept'] = 'application/json, application/problem+json, image/jpeg';
    if (session != null) request.headers['Authorization'] = 'Bearer ${session.token}';
    if (clientRef != null) request.headers['X-Waka-Client-Instance'] = clientRef;
    if (admin != null) request.headers['X-Waka-Admin-Session'] = admin.token;
    if (key != null) request.headers['Idempotency-Key'] = key;
    if (body != null) {
      request.headers['Content-Type'] = 'application/json';
      request.bodyBytes = utf8.encode(jsonEncode(body));
    }
    try {
      return await http.Response.fromStream(await _client.send(request)).timeout(_timeout);
    } on Exception {
      throw const ApiProblem(0, null);
    }
  }

  static ApiProblem _problem(http.Response response) {
    String? code;
    try {
      final body = jsonDecode(utf8.decode(response.bodyBytes));
      if (body is Map && body['code'] is String && reasonPattern.hasMatch(body['code'] as String)) code = body['code'] as String;
    } on Exception {
      code = null;
    }
    return ApiProblem(response.statusCode, code);
  }

  void close() => _client.close();
}
