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
