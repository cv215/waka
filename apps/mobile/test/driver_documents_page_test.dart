import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/presentation/waka_page.dart';
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

  testWidgets('véhicule : chaque valeur impossible a son message sous le champ, rien n’est envoyé', (tester) async {
    final login = await _show(tester, 'taxi', 'holder', step: 0);
    Future<void> type(String label, String value) async {
      final field = find.widgetWithText(TextField, label);
      await tester.scrollUntilVisible(field, 200, scrollable: find.byType(Scrollable).first);
      await tester.enterText(field, value);
    }
    await type('Année', '88');
    await type('Immatriculation', 'V');
    await type('Capacité totale autorisée (chauffeur compris)', '85');
    await type('Places passagers proposées (5 au maximum)', '8');
    final next = find.widgetWithText(FilledButton, 'Continuer');
    await tester.ensureVisible(next);
    await tester.pumpAndSettle();
    final press = tester.widget<FilledButton>(next).onPressed;
    expect(press, isNotNull);
    press!();
    await tester.pumpAndSettle();
    expect(find.textContaining('L’année s’écrit en 4 chiffres'), findsOneWidget);
    expect(find.textContaining('au moins 2 caractères'), findsOneWidget);
    expect(find.textContaining('Entre 1 et 5 places'), findsOneWidget);
    expect(find.text('3 champs sont à corriger : regarde les messages en rouge.'), findsOneWidget,
        reason: '85 is a possible capacity (a bus): only year, plate and seats are wrong');
    expect(find.text('Étape 1 sur 4'), findsOneWidget, reason: 'not sent, still on the vehicle step');
    await type('Places passagers proposées (5 au maximum)', '4');
    await tester.pump();
    expect(find.textContaining('Entre 1 et 5 places'), findsNothing, reason: 'the message goes as soon as the value is right');
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('action lente : barre tout de suite, phrase d’attente après 3 secondes', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BusyNotice(busy: true))));
    expect(find.byType(LinearProgressIndicator), findsOneWidget);
    expect(find.textContaining('Ça prend un peu de temps'), findsNothing);
    await tester.pump(const Duration(seconds: 3));
    expect(find.textContaining('Ça prend un peu de temps'), findsOneWidget);
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BusyNotice(busy: false))));
    expect(find.byType(LinearProgressIndicator), findsNothing);
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
