import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_admin/src/assistance_pages.dart';
import 'package:waka_admin/src/models.dart';

import 'governance_test.dart' as g;

void main() {
  test('lecture stricte : état et issue cohérents, champ inattendu refusé', () {
    final base = {'requestId': g.id(), 'kind': 'deletion', 'state': 'received', 'receivedAt': g.now(), 'details': null,
      'outcome': null, 'outcomeReason': null};
    expect(PrivacyItem.parse(base).open, isTrue);
    expect(() => PrivacyItem.parse({...base, 'state': 'closed'}), throwsA(isA<ResponseShapeError>()));
    expect(() => PrivacyItem.parse({...base, 'accountId': g.id()}), throwsA(isA<ResponseShapeError>()));
  });

  testWidgets('données : la demande est lue puis clôturée avec un motif', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (server, s, _) = (await tester.runAsync(() => g.ready(['PRIVACY'])))!;
    final request = g.id();
    Map<String, Object?> item(String state) => {'requestId': request, 'kind': 'deletion', 'state': state, 'receivedAt': g.now(),
      'details': state == 'received' ? 'Merci de fermer mon compte' : null,
      'outcome': state == 'closed' ? 'completed' : null, 'outcomeReason': state == 'closed' ? 'DONE_BY_PROCEDURE' : null};
    server.on('GET /api/v1/admin/privacy-requests', (_) => g.json(200, [item('received')]));
    server.on('GET /api/v1/admin/privacy-requests', (_) => g.json(200, [item('closed')]));
    server.on('POST /api/v1/admin/privacy-requests/$request/closures', (_) => g.json(200, item('closed')));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: PrivacyPage(session: s))));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.textContaining('Merci de fermer mon compte'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Clôturer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Motif (code en majuscules)'), 'DONE_BY_PROCEDURE');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Clôturer'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.url.path.endsWith('/closures'));
    expect(jsonDecode(post.body), {'outcome': 'completed', 'reasonCode': 'DONE_BY_PROCEDURE'});
    expect(find.text('Demande clôturée.'), findsOneWidget);
    expect(find.textContaining('Clôturée : traitée (DONE_BY_PROCEDURE)'), findsOneWidget);
  });

  testWidgets('assistance : un voyage bloqué est débloqué avec un motif', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (server, s, _) = (await tester.runAsync(() => g.ready(['ASSISTANCE'])))!;
    final voyage = g.id();
    server.on('GET /api/v1/admin/incidents', (_) => g.json(200, []));
    server.on('GET /api/v1/admin/locked-voyages', (_) => g.json(200, [{'voyageId': voyage, 'departureAt': g.now(), 'failures': 10}]));
    server.on('GET /api/v1/admin/locked-voyages', (_) => g.json(200, []));
    server.on('POST /api/v1/admin/voyages/$voyage/boarding-unlocks',
        (_) => g.json(200, {'voyageId': voyage, 'departureAt': g.now(), 'failures': 0}));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: AssistancePage(session: s))));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('10 codes erronés'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Débloquer'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.url.path.endsWith('/boarding-unlocks'));
    expect(jsonDecode(post.body), {'reasonCode': 'PASSENGER_IDENTIFIED'});
    expect(find.text('Voyage débloqué.'), findsOneWidget);
    expect(find.text('Aucun voyage bloqué.'), findsOneWidget);
  });

  testWidgets('incidents : décision motivée envoyée avec la version lue, sans identité affichée', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (server, s, _) = (await tester.runAsync(() => g.ready(['ASSISTANCE'])))!;
    final incident = g.id();
    Map<String, Object?> json(String state, int version) => {'incidentId': incident, 'reservationId': g.id(), 'category': 'behaviour',
      'state': state, 'version': version, 'viewerRole': 'assistance', 'createdAt': g.now(), 'idempotentReplay': false,
      'messages': [{'kind': 'report', 'authorRole': 'client', 'decision': null, 'reasonCode': null, 'text': 'Trop vite', 'at': g.now()}]};
    server.on('GET /api/v1/admin/incidents', (_) => g.json(200, [json('open', 2)]));
    server.on('GET /api/v1/admin/incidents', (_) => g.json(200, [json('decided', 3)]));
    server.on('GET /api/v1/admin/locked-voyages', (_) => g.json(200, []));
    server.on('POST /api/v1/admin/incidents/$incident/decisions', (_) => g.json(201, json('decided', 3)));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: AssistancePage(session: s))));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('Client : Trop vite'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Décider'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Motif (code)'), 'SPEEDING_REPORTED');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Enregistrer'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.url.path.endsWith('/decisions'));
    expect(jsonDecode(post.body), {'expectedVersion': 2, 'decision': 'no_action', 'reasonCode': 'SPEEDING_REPORTED', 'text': null});
    expect(post.headers['Idempotency-Key'], isNotNull);
    expect(find.text('Décision enregistrée.'), findsOneWidget);
    expect(find.widgetWithText(TextButton, 'Décider'), findsNothing);
  });
}