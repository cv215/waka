import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_admin/src/finance_page.dart';
import 'package:waka_admin/src/models.dart';

import 'governance_test.dart' as g;

Map<String, Object?> overview({int credit = 5000, int revenue = 125}) => {
  'creditXaf': credit, 'earningsXaf': 900, 'heldXaf': 0, 'payoutsInFlightXaf': 0, 'revenueXaf': revenue,
  'expectedAtProviderXaf': credit + 900 + revenue, 'openCases': 1, 'at': g.now(),
};

void main() {
  test('lecture stricte : un total qui ne tombe pas juste est refusé', () {
    expect(FinanceOverview.parse(overview()).expectedAtProviderXaf, 6025);
    expect(() => FinanceOverview.parse({...overview(), 'expectedAtProviderXaf': 6000}), throwsA(isA<ResponseShapeError>()));
    expect(() => FinanceCase.parse({'kind': 'payment', 'id': g.id(), 'state': 'review', 'method': 'cm.mtn', 'amountXaf': 1000,
      'createdAt': g.now(), 'phone': '+237670000000'}), throwsA(isA<ResponseShapeError>()));
  });

  testWidgets('finance : un paiement au montant inexact est réglé avec le montant reçu et un motif', (tester) async {
    tester.view.physicalSize = const Size(1200, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (server, s, _) = (await tester.runAsync(() => g.ready(['FINANCE'])))!;
    final payment = g.id();
    Map<String, Object?> item(String state) =>
        {'kind': 'payment', 'id': payment, 'state': state, 'method': 'cm.mtn', 'amountXaf': 1000, 'createdAt': g.now()};
    server.on('GET /api/v1/admin/finance/overview', (_) => g.json(200, overview()));
    server.on('GET /api/v1/admin/finance/cases', (_) => g.json(200, [item('review')]));
    server.on('POST /api/v1/admin/payments/$payment/resolutions', (_) => g.json(200, item('succeeded')));
    server.on('GET /api/v1/admin/finance/overview', (_) => g.json(200, overview(credit: 5999)));
    server.on('GET /api/v1/admin/finance/cases', (_) => g.json(200, []));
    await tester.pumpWidget(MaterialApp(home: Scaffold(body: FinancePage(session: s))));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('6025 F'), findsOneWidget);
    expect(find.textContaining('Paiement · 1000 F · MTN · review'), findsOneWidget);
    await tester.tap(find.text('Régler'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Montant réellement reçu (F)'), '1001');
    await tester.tap(find.text('Enregistrer'));
    await tester.pump();
    expect(find.text('Montant entre 1 et 1000 F.'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Montant réellement reçu (F)'), '999');
    await tester.tap(find.text('Enregistrer'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    final post = server.requests.lastWhere((r) => r.url.path.endsWith('/resolutions'));
    expect(jsonDecode(post.body), {'outcome': 'received', 'receivedXaf': '999', 'reasonCode': 'PROVIDER_DASHBOARD_CHECKED'});
    expect(find.text('Décision enregistrée et inscrite au journal.'), findsOneWidget);
    expect(find.text('Rien à régler.'), findsOneWidget);
    expect(find.text('7024 F'), findsOneWidget);
  });
}
