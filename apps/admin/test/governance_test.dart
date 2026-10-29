import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:waka_admin/src/admin_api.dart';
import 'package:waka_admin/src/agent_session.dart';
import 'package:waka_admin/src/governance_pages.dart';
import 'package:waka_admin/src/models.dart';

String now() => DateTime.now().toUtc().toIso8601String();
String id() => randomKey();
final token = 'T' * 43, adminToken = 'A' * 43;
const challengeId = '00000000-0000-4000-8000-000000000001';

http.Response json(int status, Object? body, {String type = 'application/json'}) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': type, 'cache-control': 'no-store'});
http.Response problem(int status, String code) => json(status, {'status': status, 'code': code}, type: 'application/problem+json');

Map<String, Object?> grantJson(String account, String function, {String? grantId, bool revoked = false, bool replay = false}) => {
  'grantId': grantId ?? id(), 'accountId': account, 'function': function, 'grantedAt': now(), 'grantedBy': 'FOUNDER_BOOTSTRAP',
  'reasonCode': 'FOUNDER_BOOTSTRAP', 'revokedAt': revoked ? now() : null, 'revokedBy': revoked ? id() : null,
  'revokeReason': revoked ? 'LEFT_TEAM' : null, 'idempotentReplay': replay,
};

Map<String, Object?> auditJson(int number) => {
  'id': number, 'occurredAt': now(), 'eventType': 'ADMIN_GRANT', 'actorRef': id(), 'actorScope': 'HABILITATIONS',
  'objectType': 'admin_grant', 'objectRef': id(), 'reasonCode': 'STAFFING', 'outcome': 'APPLIED',
};

class Server {
  final requests = <http.Request>[];
  final Map<String, List<http.Response Function(http.Request)>> routes = {};
  void on(String route, http.Response Function(http.Request) answer) => routes.putIfAbsent(route, () => []).add(answer);
  late final client = MockClient((request) async {
    requests.add(request);
    final answers = routes['${request.method} ${request.url.path}'];
    if (answers == null || answers.isEmpty) return problem(404, 'RESOURCE_NOT_FOUND');
    return (answers.length > 1 ? answers.removeAt(0) : answers.first)(request);
  });
  AdminApi api() => AdminApi(Uri.parse('http://localhost:8080'), client: client);
}

/// A session in the ready state, as after a successful login, with the given functions.
Future<(Server, AgentSession, String)> ready(List<String> functions) async {
  final server = Server(), account = id();
  final session = AgentSession(server.api());
  server.on('POST /api/v1/auth/phone-challenges', (_) => json(202,
      {'challengeId': challengeId, 'submission': 'ACCEPTED', 'expiresAt': now(), 'destinationHint': '•••••••01'}));
  server.on('POST /api/v1/auth/phone-challenges/$challengeId/verifications', (_) => json(200,
      {'sessionId': id(), 'accountId': account, 'tokenType': 'Bearer', 'token': token, 'absoluteExpiresAt': now(), 'idleExpiresAt': now()}));
  server.on('POST /api/v1/admin/sessions', (_) => json(201, {'adminSessionToken': adminToken, 'adminSessionId': id(),
    'functions': functions, 'idleExpiresAt': now(), 'absoluteExpiresAt': now(), 'serverTime': now()}));
  await session.requestCode('600000001');
  await session.verifySms('135790');
  await session.openWithAuthenticator('123456');
  return (server, session, account);
}

void main() {
  group('Client des habilitations et du journal', () {
    test('attribution : 201 nouvelle, 200 identique ; lecture stricte', () async {
      final (server, s, _) = await ready(['HABILITATIONS']);
      final other = id(), grantId = id();
      server.on('POST /api/v1/admin/grants', (_) => json(201, grantJson(other, 'ADMISSION', grantId: grantId)));
      server.on('POST /api/v1/admin/grants', (_) => json(200, grantJson(other, 'ADMISSION', grantId: grantId, replay: true)));
      final first = await s.api.grant(s.client!, s.clientRef, s.admin!, other, 'ADMISSION', 'STAFFING');
      final second = await s.api.grant(s.client!, s.clientRef, s.admin!, other, 'ADMISSION', 'STAFFING');
      expect(first.grantId, second.grantId);
      final post = server.requests.lastWhere((r) => r.url.path == '/api/v1/admin/grants');
      expect(jsonDecode(post.body), {'accountId': other, 'function': 'ADMISSION', 'reasonCode': 'STAFFING'});
      expect(post.headers['X-Waka-Admin-Session'], adminToken);
      await expectLater(s.api.grant(s.client!, s.clientRef, s.admin!, other, 'ROOT', 'STAFFING'),
          throwsA(isA<ApiProblem>().having((p) => p.status, 'status', 400)));
      server.on('GET /api/v1/admin/grants', (_) => json(200, [{...grantJson(other, 'AUDIT'), 'phone': '+237600000001'}]));
      await expectLater(s.api.grants(s.client!, s.clientRef, s.admin!), throwsA(isA<ResponseShapeError>()));
      final half = {...grantJson(other, 'AUDIT', revoked: true), 'revokeReason': null};
      expect(() => GrantView.parse(half), throwsA(isA<ResponseShapeError>()));
    });

    test('journal : filtres et curseur dans la requête, rien d’autre', () async {
      final (server, s, _) = await ready(['AUDIT']);
      final object = id();
      server.on('GET /api/v1/admin/audit', (_) => json(200, [auditJson(7)]));
      final entries = await s.api.audit(s.client!, s.clientRef, s.admin!, objectType: 'admin_grant', objectId: object, cursor: 8, limit: 10);
      expect(entries.single.id, 7);
      expect(server.requests.last.url.query, 'objectType=admin_grant&objectId=$object&cursor=8&limit=10');
      await expectLater(s.api.audit(s.client!, s.clientRef, s.admin!, objectId: 'a b'),
          throwsA(isA<ApiProblem>().having((p) => p.status, 'status', 400)));
      expect(() => AuditEntry.parse({...auditJson(1), 'detail': 'contenu'}), throwsA(isA<ResponseShapeError>()));
    });
  });

  group('Écrans', () {
    void large(WidgetTester tester) {
      tester.view.physicalSize = const Size(1200, 3000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
    }

    testWidgets('habilitations : pas de retrait de soi-même, refus expliqué, attribution envoyée', (tester) async {
      large(tester);
      final (server, s, own) = (await tester.runAsync(() => ready(['HABILITATIONS', 'AUDIT'])))!;
      final other = id();
      server.on('GET /api/v1/admin/grants', (_) => json(200, [grantJson(own, 'HABILITATIONS'), grantJson(other, 'ADMISSION')]));
      server.on('POST /api/v1/admin/grants', (_) => problem(403, 'SELF_GRANT_FORBIDDEN'));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: GrantsPage(session: s))));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      expect(find.textContaining('Ton identifiant de compte : $own'), findsOneWidget);
      expect(find.text('Retirer'), findsOneWidget, reason: 'seule l’habilitation d’un autre compte peut être retirée');
      await tester.enterText(find.widgetWithText(TextField, 'Identifiant du compte de l’agent'), own);
      await tester.tap(find.text('Attribuer'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      expect(find.textContaining('Tu ne peux ni t’attribuer'), findsOneWidget);
      final post = server.requests.lastWhere((r) => r.method == 'POST' && r.url.path == '/api/v1/admin/grants');
      expect(jsonDecode(post.body), {'accountId': own, 'function': 'ADMISSION', 'reasonCode': 'NOUVELLE_MISSION'});
    });

    testWidgets('retrait : motif demandé puis envoyé', (tester) async {
      large(tester);
      final (server, s, own) = (await tester.runAsync(() => ready(['HABILITATIONS'])))!;
      final other = id(), grantId = id();
      server.on('GET /api/v1/admin/grants', (_) => json(200, [grantJson(own, 'HABILITATIONS'), grantJson(other, 'FINANCE', grantId: grantId)]));
      server.on('POST /api/v1/admin/grants/$grantId/revocations',
          (_) => json(200, grantJson(other, 'FINANCE', grantId: grantId, revoked: true)));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: GrantsPage(session: s))));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Retirer'));
      await tester.pumpAndSettle();
      await tester.tap(find.widgetWithText(FilledButton, 'Retirer'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      final revoke = server.requests.lastWhere((r) => r.url.path.endsWith('/revocations'));
      expect(jsonDecode(revoke.body), {'reasonCode': 'FIN_DE_MISSION'});
      expect(find.text('Habilitation retirée.'), findsOneWidget);
    });

    testWidgets('journal : page suivante avec le curseur de la dernière entrée', (tester) async {
      large(tester);
      final (server, s, _) = (await tester.runAsync(() => ready(['AUDIT'])))!;
      server.on('GET /api/v1/admin/audit', (_) => json(200, [for (var i = 100; i > 50; i--) auditJson(i)]));
      server.on('GET /api/v1/admin/audit', (_) => json(200, [auditJson(50)]));
      await tester.pumpWidget(MaterialApp(home: Scaffold(body: AuditPage(session: s))));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      await tester.scrollUntilVisible(find.text('Entrées plus anciennes'), 500, scrollable: find.byType(Scrollable).first);
      await tester.tap(find.text('Entrées plus anciennes'));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      final audits = server.requests.where((r) => r.url.path == '/api/v1/admin/audit').toList();
      expect(audits.first.url.query, 'limit=50');
      expect(audits.last.url.query, 'cursor=51&limit=50');
      expect(find.text('Entrées plus anciennes'), findsNothing);
    });
  });
}
