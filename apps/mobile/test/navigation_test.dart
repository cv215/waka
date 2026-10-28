import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/app.dart';

void main() {
  testWidgets('Le formulaire ouvre la connexion sans faux compte', (
    tester,
  ) async {
    await tester.pumpWidget(const WakaApp());
    await tester.scrollUntilVisible(find.text('Se connecter'), 200);
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();

    expect(find.text('Connexion'), findsOneWidget);
    expect(find.byType(TextFormField), findsOneWidget);
    expect(find.text('Numéro mobile Cameroun'), findsOneWidget);
    expect(find.text('Connexion confirmée'), findsNothing);
    await tester.ensureVisible(find.text('Recevoir un code'));
    await tester.tap(find.text('Recevoir un code'));
    await tester.pumpAndSettle();
    expect(find.text('Saisis un numéro mobile.'), findsOneWidget);
    expect(
      find.text('Connexion momentanément indisponible. Réessaie plus tard.'),
      findsNothing,
    );

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('WAKA'), findsOneWidget);
    expect(
      find.text('Les réservations ne sont pas encore ouvertes.'),
      findsOneWidget,
    );
  });

  testWidgets('Les informations chauffeur n’accordent pas de rôle', (
    tester,
  ) async {
    await tester.pumpWidget(const WakaApp());
    await tester.scrollUntilVisible(find.text('Informations chauffeur'), 200);
    await tester.tap(find.text('Informations chauffeur'));
    await tester.pumpAndSettle();

    expect(find.text('Conduire avec WAKA'), findsOneWidget);
    await tester.scrollUntilVisible(
      find.text('Le dépôt des candidatures n’est pas encore ouvert.'),
      200,
    );
    expect(find.byType(TextField), findsNothing);
    expect(find.byType(Switch), findsNothing);
    expect(find.text('Votre dossier est approuvé'), findsNothing);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('WAKA'), findsOneWidget);
  });

  testWidgets('Le diagnostic reste explicitement non vérifié', (tester) async {
    await tester.pumpWidget(const WakaApp());
    await tester.tap(find.byTooltip('Diagnostic technique'));
    await tester.pumpAndSettle();

    expect(find.text('Vérification non effectuée'), findsOneWidget);
    expect(
      find.textContaining('réservations ou des paiements'),
      findsOneWidget,
    );
    expect(find.byType(CircularProgressIndicator), findsNothing);
    expect(find.text('UP'), findsNothing);
    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    expect(find.text('WAKA'), findsOneWidget);
  });

  testWidgets('Petit écran et texte agrandi conservent la navigation', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);

    await tester.pumpWidget(const WakaApp());
    await tester.scrollUntilVisible(find.text('Se connecter'), 250);
    await tester.tap(find.text('Se connecter'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Connexion'), findsOneWidget);

    await tester.binding.handlePopRoute();
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Informations chauffeur'), 250);
    await tester.tap(find.text('Informations chauffeur'));
    await tester.pumpAndSettle();
    expect(tester.takeException(), isNull);
    expect(find.text('Chauffeur'), findsOneWidget);
  });

  testWidgets('Les actions visibles ont des cibles et libellés accessibles', (
    tester,
  ) async {
    final semantics = tester.ensureSemantics();
    try {
      await tester.pumpWidget(const WakaApp());
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
      await tester.scrollUntilVisible(find.text('Informations chauffeur'), 200);
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
    } finally {
      semantics.dispose();
    }
  });
}
