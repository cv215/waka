import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:waka_admin/src/admin_api.dart';
import 'package:waka_admin/src/agent_session.dart';
import 'package:waka_admin/src/models.dart';
import 'package:waka_admin/src/pages.dart';

String now() => DateTime.now().toUtc().toIso8601String();
String id() => randomKey();
final token = 'T' * 43, adminToken = 'A' * 43;

http.Response json(int status, Object? body, {String type = 'application/json'}) =>
    http.Response(jsonEncode(body), status, headers: {'content-type': type, 'cache-control': 'no-store'});
http.Response problem(int status, String code) => json(status, {'status': status, 'code': code}, type: 'application/problem+json');

Map<String, Object?> detailJson(String applicationId, {String category = 'taxi', bool toConfirm = true}) => {
  'application': {
    'applicationId': applicationId, 'accountId': id(), 'requestedCategory': category, 'state': 'submitted', 'version': 3,
    'vehicleFields': {'brand': 'Marque', 'model': 'Modele', 'year': 2020, 'color': 'Bleu', 'registration': 'AB 12',
      'authorizedTotalCapacity': 5, 'offeredPassengerSeats': 4, 'usageRight': 'holder'},
    'missingVehicleFields': [], 'createdAt': now(), 'updatedAt': now(), 'serverTime': now(), 'idempotentReplay': false,
  },
  'submittedAt': now(),
  'photos': [for (final view in photoViews) {'view': view, 'fileId': id()}],
  'requirements': [
    {'code': 'INSURANCE', 'document': 'Attestation d’assurance', 'explanation': 'Une phrase.', 'status': 'established',
      'requiredForSubmission': true, 'documentId': id(), 'reviewState': 'received', 'validUntil': '2027-06-30',
      'expired': false, 'satisfied': false},
    if (toConfirm)
      {'code': 'TRANSPORT_LICENCE', 'document': 'Licence de transport', 'explanation': 'Une phrase.', 'status': 'to_confirm',
        'requiredForSubmission': false, 'documentId': null, 'reviewState': null, 'validUntil': null, 'expired': false,
        'satisfied': false},
  ],
  'decisions': [],
};

/// A scripted server; records every request so that headers and bodies can be checked.
class Server {
  final requests = <http.Request>[];
  final Map<String, List<http.Response Function(http.Request)>> routes = {};
  void on(String route, http.Response Function(http.Request) answer) => routes.putIfAbsent(route, () => []).add(answer);
  late final client = MockClient((request) async {
    requests.add(request);
    final route = '${request.method} ${request.url.path}';
    final answers = routes[route];
    if (answers == null || answers.isEmpty) return problem(404, 'RESOURCE_NOT_FOUND');
    return (answers.length > 1 ? answers.removeAt(0) : answers.first)(request);
  });
  AdminApi api() => AdminApi(Uri.parse('http://localhost:8080'), client: client);
}

void main() {
  group('Règles du client', () {
    test('adresse : http seulement en local, https sinon, sans chemin', () {
      AdminApi(Uri.parse('http://localhost:8080'));
      AdminApi(Uri.parse('https://api.example.org'));
      for (final refused in ['http://api.example.org', 'ftp://localhost', 'https://api.example.org/v1']) {
        expect(() => AdminApi(Uri.parse(refused)), throwsArgumentError, reason: refused);
      }
    });

    test('lecture stricte : fonction inconnue, champ en trop ou jeton malformé refusés', () {
      final valid = {'adminSessionToken': adminToken, 'adminSessionId': id(), 'functions': ['ADMISSION'],
        'idleExpiresAt': now(), 'absoluteExpiresAt': now(), 'serverTime': now()};
      expect(AdminSession.parse(valid).functions, ['ADMISSION']);
      for (final bad in [
        {...valid, 'functions': ['ROOT']},
        {...valid, 'functions': []},
        {...valid, 'adminSessionToken': 'short'},
        {...valid, 'extra': true},
      ]) {
        expect(() => AdminSession.parse(bad), throwsA(isA<ResponseShapeError>()));
      }
      final app = id();
      expect(Detail.parse(detailJson(app)).hasRequirementToConfirm, isTrue);
      expect(Detail.parse(detailJson(app, toConfirm: false)).hasRequirementToConfirm, isFalse);
      expect(() => Detail.parse({...detailJson(app), 'agentNote': 'x'}), throwsA(isA<ResponseShapeError>()));
    });

    test('en-têtes de preuve, no-store exigé, codes de refus lus, image JPEG vérifiée', () async {
      final server = Server();
      final session = ClientSession(id(), id(), token);
      final admin = AdminSession(adminToken, const ['ADMISSION']);
      server.on('GET /api/v1/admin/work-items', (_) => json(200, []));
      expect(await server.api().workItems(session, 'c' * 32, admin), isEmpty);
      final request = server.requests.single;
      expect(request.url.query, 'category=admission');
      expect(request.headers['Authorization'], 'Bearer $token');
      expect(request.headers['X-Waka-Admin-Session'], adminToken);
      expect(request.headers['X-Waka-Client-Instance'], 'c' * 32);
      server.routes['GET /api/v1/admin/work-items'] = [(_) => http.Response('[]', 200, headers: {'content-type': 'application/json'})];
      await expectLater(server.api().workItems(session, 'c' * 32, admin), throwsA(isA<ApiProblem>()), reason: 'sans no-store');
      final photo = id();
      server.on('GET /api/v1/admin/private-files/$photo/content', (_) => http.Response('not an image', 200,
          headers: {'content-type': 'image/jpeg', 'cache-control': 'no-store'}));
      await expectLater(server.api().image(session, 'c' * 32, admin, photo: true, id: photo),
          throwsA(isA<ApiProblem>().having((p) => p.uncertain, 'uncertain', isTrue)));
      server.on('GET /api/v1/admin/driver-documents/$photo/content', (_) => problem(403, 'ACTION_NOT_ALLOWED'));
      await expectLater(server.api().image(session, 'c' * 32, admin, photo: false, id: photo),
          throwsA(isA<ApiProblem>().having((p) => p.code, 'code', 'ACTION_NOT_ALLOWED')));
    });
  });

  group('Connexion agent', () {
    const challengeId = '00000000-0000-4000-8000-0000000000aa';
    Server scripted() {
      final server = Server();
      server.on('POST /api/v1/auth/phone-challenges', (_) => json(202,
          {'challengeId': challengeId, 'submission': 'ACCEPTED', 'expiresAt': now(), 'destinationHint': '•••••••01'}));
      return server;
    }

    test('SMS puis enrôlement puis code : session d’administration en mémoire seulement', () async {
      final server = scripted();
      final verify = json(200, {'sessionId': id(), 'accountId': id(), 'tokenType': 'Bearer', 'token': token,
        'absoluteExpiresAt': now(), 'idleExpiresAt': now()});
      final session = AgentSession(server.api());
      await session.requestCode('6 00 00 00 01');
      expect(session.step, AgentStep.smsCode);
      final challenge = jsonDecode(server.requests.single.body) as Map;
      expect(challenge['phoneNumber'], '+237600000001');
      server.on('POST /api/v1/auth/phone-challenges/$challengeId/verifications', (_) => verify);
      await session.verifySms('135790');
      await session.verifySms('135790');
      final verifyKeys = server.requests.where((r) => r.url.path.endsWith('/verifications')).map((r) => r.headers['Idempotency-Key']);
      expect(verifyKeys.toSet(), hasLength(1), reason: 'même code, même clé');
      expect(session.step, AgentStep.secondFactor);
      server.on('POST /api/v1/admin/sessions', (_) => problem(409, 'ENROLLMENT_REQUIRED'));
      server.on('POST /api/v1/admin/second-factor/enrollments', (_) => json(201,
          {'secret': 'JBSWY3DPEHPK3PXPJBSWY3DP', 'otpauthUri': 'otpauth://totp/WAKA%3Aadministration?secret=JBSWY3DPEHPK3PXPJBSWY3DP'}));
      await session.openWithAuthenticator('111111');
      expect(session.step, AgentStep.enrollment);
      expect(session.enrollment!.secret, 'JBSWY3DPEHPK3PXPJBSWY3DP');
      server.on('POST /api/v1/admin/second-factor/confirmations', (_) =>
          http.Response('', 204, headers: {'cache-control': 'no-store'}));
      await session.confirmEnrollment('222222');
      expect(session.enrollment, isNull);
      server.routes['POST /api/v1/admin/sessions'] = [(_) => json(201, {'adminSessionToken': adminToken,
        'adminSessionId': id(), 'functions': ['ADMISSION', 'AUDIT'], 'idleExpiresAt': now(), 'absoluteExpiresAt': now(), 'serverTime': now()})];
      await session.openWithAuthenticator('333333');
      expect(session.step, AgentStep.ready);
      expect(session.canExamine, isTrue);
      server.on('POST /api/v1/admin/sessions/current/revocations', (_) => http.Response('', 204, headers: {'cache-control': 'no-store'}));
      await session.logout();
      expect(session.step, AgentStep.phone);
      expect(session.client, isNull);
      expect(session.admin, isNull);
    });

    test('une demande répétée garde la même clé ; un mauvais code ne ferme pas la session client', () async {
      final server = scripted();
      final session = AgentSession(server.api());
      await session.requestCode('600000001');
      await session.requestCode('600000001');
      final keys = server.requests.map((r) => r.headers['Idempotency-Key']).toSet();
      expect(keys, hasLength(1));
      await session.requestCode('600000002');
      expect(server.requests.map((r) => r.headers['Idempotency-Key']).toSet(), hasLength(2));
      await session.requestCode('12345');
      expect(session.error, contains('vérifie'));
    });
  });

  group('Écran d’examen', () {
    Future<(Server, AgentSession)> ready(WidgetTester tester, Map<String, Object?> detail, String app) async {
      final server = Server();
      final session = AgentSession(server.api());
      // Directly in the ready state, as after a successful login.
      await tester.runAsync(() async {
        server.on('POST /api/v1/auth/phone-challenges', (_) => json(202,
            {'challengeId': '00000000-0000-4000-8000-000000000001', 'submission': 'ACCEPTED', 'expiresAt': now(), 'destinationHint': '•••••••01'}));
        server.on('POST /api/v1/auth/phone-challenges/00000000-0000-4000-8000-000000000001/verifications', (_) => json(200,
            {'sessionId': id(), 'accountId': id(), 'tokenType': 'Bearer', 'token': token, 'absoluteExpiresAt': now(), 'idleExpiresAt': now()}));
        server.on('POST /api/v1/admin/sessions', (_) => json(201, {'adminSessionToken': adminToken, 'adminSessionId': id(),
          'functions': ['ADMISSION'], 'idleExpiresAt': now(), 'absoluteExpiresAt': now(), 'serverTime': now()}));
        await session.requestCode('600000001');
        await session.verifySms('135790');
        await session.openWithAuthenticator('123456');
      });
      server.on('GET /api/v1/admin/driver-applications/$app', (_) => json(200, detail));
      await tester.pumpWidget(MaterialApp(home: ReviewPage(session: session, applicationId: app)));
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      return (server, session);
    }

    testWidgets('exigence à confirmer : avertissement et approbation impossible', (tester) async {
      tester.view.physicalSize = const Size(1200, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final app = id();
      await ready(tester, detailJson(app), app);
      expect(find.textContaining('encore à confirmer par WAKA'), findsWidgets);
      await tester.tap(find.text('Approuver'));
      await tester.enterText(find.widgetWithText(TextField, 'Motif (code)'), 'ALL_CONDITIONS_MET');
      await tester.pump();
      final button = find.widgetWithText(FilledButton, 'Enregistrer la décision');
      expect(tester.widget<FilledButton>(button).onPressed, isNull);
      expect(find.text('Approbation impossible : une exigence de ce dossier est encore à confirmer par WAKA.'), findsOneWidget);
    });

    testWidgets('correction ciblée : une seule pièce, même clé si la réponse se perd', (tester) async {
      tester.view.physicalSize = const Size(1200, 4000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      final app = id();
      final detail = detailJson(app);
      final (server, _) = await ready(tester, detail, app);
      var attempts = 0;
      server.on('POST /api/v1/admin/driver-applications/$app/decisions', (request) {
        attempts++;
        if (attempts == 1) throw http.ClientException('lost');
        return json(201, {'applicationId': app});
      });
      await tester.tap(find.text('À corriger').last);
      await tester.pump();
      await tester.enterText(find.widgetWithText(TextField, 'Motif de correction (code)'), 'COVER_NOT_SHOWN');
      await tester.enterText(find.widgetWithText(TextField, 'Motif (code)'), 'DOCUMENT_TO_REPLACE');
      await tester.pump();
      final button = find.widgetWithText(FilledButton, 'Enregistrer la décision');
      await tester.tap(button);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pump();
      expect(find.textContaining('Pas de réponse fiable'), findsOneWidget);
      await tester.tap(button);
      await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
      await tester.pumpAndSettle();
      final posts = server.requests.where((r) => r.method == 'POST' && r.url.path.endsWith('/decisions')).toList();
      expect(posts, hasLength(2));
      expect(posts.map((r) => r.headers['Idempotency-Key']).toSet(), hasLength(1));
      final body = jsonDecode(posts.last.body) as Map<String, dynamic>;
      expect(body['action'], 'request_correction');
      expect(body['expectedVersion'], 3);
      expect(body['photoDecisions'], isEmpty, reason: 'aucune photo jugée : rien d’envoyé pour elles');
      expect(body['documentDecisions'], [
        {'documentId': ((detail['requirements'] as List).first as Map)['documentId'], 'result': 'to_correct', 'reasonCode': 'COVER_NOT_SHOWN'},
      ]);
      expect(body.containsKey('documentType'), isFalse);
    });
  });
}
