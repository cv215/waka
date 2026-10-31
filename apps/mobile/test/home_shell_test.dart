import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/presentation/theme_choice.dart';
import 'package:waka_mobile/core/presentation/waka_theme.dart';
import 'package:waka_mobile/features/home/presentation/home_shell.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';

import 'city_ride_test.dart' show RideFixture;
import 'driver_document_test.dart' as fixture;
import 'voyages_page_test.dart' show iso, departure, settle;

Future<(LoginFlow, RideFixture)> _open(WidgetTester tester, {void Function(RideFixture)? before}) async {
  tester.view.physicalSize = const Size(900, 2000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = RideFixture(session);
    before?.call(gateway);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: HomeShell(login: login, course: const Text('carte des courses'),
      voyages: const Text('voyages'))));
  await settle(tester);
  return (login, gateway);
}

void main() {
  testWidgets('quatre onglets en bas ; la course est le premier écran', (tester) async {
    await _open(tester);
    expect(find.text('carte des courses'), findsOneWidget);
    for (final tab in ['Course', 'Voyages', 'Activité', 'Compte']) {
      expect(find.widgetWithText(NavigationDestination, tab), findsOneWidget);
    }
    await tester.tap(find.text('Compte'));
    await settle(tester);
    for (final row in ['Mon argent', 'Devenir chauffeur', 'Apparence', 'Se déconnecter']) {
      expect(find.text(row), findsWidgets, reason: row);
    }
    await tester.tap(find.text('Devenir chauffeur'));
    await settle(tester);
    expect(find.text('Conduire avec WAKA'), findsOneWidget);
    expect(find.text('Ma candidature'), findsOneWidget);
  });

  testWidgets('Activité : la course en cours en haut (un toucher la rouvre), puis l’historique', (tester) async {
    await _open(tester, before: (server) {
      server.inProgress = true;
      server.rideState = 'confirmed';
      server.past = [
        {'reservationId': '91111111-2222-4333-8444-555555555555', 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'refundXaf': null,
          'state': 'finished', 'createdAt': iso(departure), 'dropoff': {'latitude': 3.8935, 'longitude': 11.509}},
        {'reservationId': '81111111-2222-4333-8444-555555555555', 'mode': 'vip', 'seats': 1, 'priceXaf': 4625, 'refundXaf': 4625,
          'state': 'cancelled_by_client', 'createdAt': iso(departure), 'dropoff': null},
      ];
    });
    await tester.tap(find.text('Activité'));
    await settle(tester);
    expect(find.text('En cours'), findsOneWidget);
    expect(find.textContaining('Ton chauffeur arrive'), findsOneWidget);
    expect(find.text('Avant'), findsOneWidget);
    expect(find.textContaining('370 F · Terminée'), findsOneWidget);
    expect(find.textContaining('4625 F · Annulée · remboursé 4625 F'), findsOneWidget);
    await tester.tap(find.text('En cours'));
    await settle(tester);
    expect(find.text('carte des courses'), findsOneWidget, reason: 'the ride opens on the map tab');
  });

  testWidgets('Compte : l’apparence change le thème ; se déconnecter efface la session', (tester) async {
    final (login, _) = await _open(tester);
    await tester.tap(find.text('Compte'));
    await settle(tester);
    await tester.tap(find.text('Apparence'));
    await settle(tester);
    addTearDown(() => wakaThemeChoice.value = WakaThemeChoice.light);
    await tester.tap(find.text('Cameroun (vert, rouge, jaune)'));
    await settle(tester);
    expect(wakaThemeChoice.value, WakaThemeChoice.cameroon);
    await tester.tapAt(const Offset(450, 100));
    await settle(tester);
    await tester.tap(find.text('Se déconnecter'));
    await settle(tester);
    expect(login.session, isNull);
  });
}
