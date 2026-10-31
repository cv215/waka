import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/drivers/presentation/driver_application_page.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';

import 'driver_document_test.dart' as fixture;

Future<LoginFlow> _show(WidgetTester tester, String category, String? usageRight, {int step = 2}) async {
  final (login, _, documents) =
      await tester.runAsync(() => fixture.ready(category, usageRight)) as (LoginFlow, fixture.Gateway, dynamic);
  documents.dispose();
  await tester.pumpWidget(MaterialApp(home: DriverApplicationPage(
    flow: login,
    pickPhoto: ({required bool camera}) async => fixture.jpeg,
    pickDate: (_) async => null,
    initialStep: step,
  )));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
  await tester.pumpAndSettle();
  return login;
}

void main() {
  testWidgets('L’écran taxi n’affiche que les pièces du taxi, avec les exigences à confirmer signalées', (tester) async {
    final login = await _show(tester, 'taxi', 'holder');
    expect(find.text('Document TRANSPORT_LICENCE'), findsOneWidget);
    expect(find.text('Document PUBLIC_TRANSPORT_CARD'), findsOneWidget);
    expect(find.text('Document PAID_TRANSPORT_AUTHORIZATION'), findsNothing);
    expect(find.text('Document USE_AUTHORIZATION'), findsNothing);
    expect(find.text('Explication simple pour INSURANCE.'), findsOneWidget);
    expect(find.text('En cours de confirmation par WAKA : facultatif pour l’instant'), findsNWidgets(3));
    expect(find.text('À fournir'), findsNWidgets(4));
    final next = find.widgetWithText(FilledButton, 'Continuer');
    await tester.scrollUntilVisible(next, 300, scrollable: find.byType(Scrollable).first);
    await tester.tap(next);
    await tester.pumpAndSettle();
    expect(find.text('Étape 4 sur 4'), findsOneWidget);
    expect(find.textContaining('Il manque encore'), findsOneWidget);
    final send = find.widgetWithText(FilledButton, 'Envoyer mon dossier');
    expect(tester.widget<FilledButton>(send).onPressed, isNull, reason: 'dossier incomplet : envoi impossible');
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('L’écran voiture personnelle ne montre aucun titre de taxi', (tester) async {
    final login = await _show(tester, 'private', 'authorized_use');
    expect(find.text('Document TRANSPORT_LICENCE'), findsNothing);
    expect(find.text('Document PUBLIC_TRANSPORT_CARD'), findsNothing);
    expect(find.text('Document USE_AUTHORIZATION'), findsOneWidget);
    expect(find.text('Document PAID_TRANSPORT_AUTHORIZATION'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}
