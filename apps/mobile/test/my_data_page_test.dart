import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/privacy/presentation/my_data_page.dart';

import 'driver_document_test.dart' as fixture;
import 'my_data_test.dart' as data;

void main() {
  testWidgets('la suppression est confirmée, puis annoncée comme reçue sans rien supprimer', (tester) async {
    tester.view.physicalSize = const Size(800, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (login, gateway) = (await tester.runAsync(() async {
      final session = fixture.grant();
      final store = fixture.MemoryStore();
      await store.write(LoginSnapshot(randomLoginId(), session: session));
      final gateway = data.PrivacyFixture(session);
      final login = LoginFlow(gateway, store);
      await login.initialize();
      return (login, gateway);
    }))!;
    await tester.pumpWidget(MaterialApp(home: MyDataPage(login: login)));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('Aucune activité pour l’instant.'), findsOneWidget);
    await tester.tap(find.text('Demander la suppression'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Rien n’est supprimé ni fermé'), findsOneWidget);
    await tester.tap(find.text('Annuler'));
    await tester.pumpAndSettle();
    expect(gateway.sent, isEmpty, reason: 'annuler n’envoie rien');
    await tester.tap(find.text('Demander la suppression'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Envoyer la demande'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('Demande de suppression reçue. Rien n’a encore été supprimé.'), findsOneWidget);
    expect(find.text('Demande sur mes données'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}
