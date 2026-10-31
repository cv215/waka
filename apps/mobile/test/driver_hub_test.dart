import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/drivers/presentation/driver_hub_page.dart';
import 'package:waka_mobile/features/home/presentation/home_shell.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/driver_access_models.dart';
import 'package:waka_mobile/features/identity/data/driver_application_models.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';

import 'driver_document_test.dart' as fixture;
import 'driver_ride_test.dart' show DriverFixture;
import 'voyages_page_test.dart' show settle;

/// One driver file at a time (R16): none, or one with its category and state; the access follows the serving profile.
class HubFixture extends fixture.Gateway implements DriverAccessGateway {
  HubFixture(LoginSession session) : super(session, 'taxi', 'holder');
  String? fileCategory, fileState;
  String access = 'none';
  String? accessCategory;
  final created = <String>[];

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession s, String client) async => fileCategory == null
      ? null
      : DriverApplicationSnapshot.parse({
          'applicationId': applicationId, 'accountId': s.accountId, 'requestedCategory': fileCategory, 'state': fileState,
          'version': 2, 'vehicleFields': VehicleData(usageRight: 'holder', registration: 'AB 12').toJson(),
          'missingVehicleFields': VehicleData(usageRight: 'holder', registration: 'AB 12').missing,
          'createdAt': fixture.now(), 'updatedAt': fixture.now(), 'serverTime': fixture.now(), 'idempotentReplay': false,
        }, s);

  @override
  Future<DriverApplicationSnapshot> sendApplication(LoginSession s, String client, ApplicationCommand c) async {
    final category = (c as CreateApplicationCommand).requestedCategory;
    created.add(category);
    // The new file opens; an approved profile keeps serving (the access does not change).
    fileCategory = category;
    fileState = 'draft';
    return (await currentApplication(s, client))!;
  }

  @override
  Future<DriverAccessView> driverAccess(LoginSession s, String client) async => DriverAccessView.parse({
    'access': access, 'basis': access == 'active' ? 'taxi_no_subscription' : null,
    'applicationId': accessCategory == null ? null : randomLoginId(), 'category': accessCategory,
    'admissionState': accessCategory == null ? null : 'approved', 'reasons': <String>[], 'documentsToRenew': <String>[],
    'trial': null, 'serverTime': fixture.now(),
  });
}

Future<(LoginFlow, HubFixture, List<String>)> _open(WidgetTester tester, void Function(HubFixture) setUp) async {
  tester.view.physicalSize = const Size(900, 2000);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final opened = <String>[];
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = HubFixture(session);
    setUp(gateway);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: DriverHubPage(login: login,
      openApplication: (_) => opened.add(login.application?.requestedCategory ?? '-'))));
  await settle(tester);
  return (login, gateway, opened);
}

void main() {
  testWidgets('sans dossier : deux cartes, Taxi ou Particulier ; un choix ouvre la candidature guidée', (tester) async {
    final (_, server, opened) = await _open(tester, (_) {});
    expect(find.text('Taxi'), findsOneWidget);
    expect(find.text('Particulier'), findsOneWidget);
    expect(find.text('Sans abonnement'), findsOneWidget);
    expect(find.text('Essai, puis 3 000 F pour 30 jours'), findsOneWidget);
    await tester.tap(find.text('Taxi'));
    await settle(tester);
    expect(server.created, ['taxi']);
    expect(opened, ['taxi']);
  });

  testWidgets('chauffeur approuvé : changer de situation garde le profil actif pendant l’examen', (tester) async {
    final (_, server, opened) = await _open(tester, (s) {
      s.fileCategory = 'taxi';
      s.fileState = 'approved';
      s.access = 'active';
      s.accessCategory = 'taxi';
    });
    expect(find.text('Chauffeur taxi'), findsOneWidget);
    expect(find.text('Approuvé'), findsOneWidget);
    expect(find.text('Taxi'), findsNothing, reason: 'one profile at a time: no second card');
    await tester.tap(find.text('Changer de situation : devenir particulier'));
    await settle(tester);
    expect(find.textContaining('Ton profil actuel reste actif'), findsOneWidget);
    expect(find.textContaining('remboursés'), findsNothing, reason: 'only taxi drivers coming from private lose paid days');
    await tester.tap(find.text('Changer'));
    await settle(tester);
    expect(server.created, ['private']);
    expect(opened, ['private']);
    expect(find.text('Actif pendant le changement'), findsOneWidget);
    expect(find.text('Changement de situation'), findsOneWidget);
    expect(find.text('Chauffeur particulier'), findsOneWidget);
    expect(find.text('À compléter'), findsOneWidget);
  });

  testWidgets('de particulier à taxi : le message dit que les jours payés ne sont pas remboursés', (tester) async {
    await _open(tester, (s) {
      s.fileCategory = 'private';
      s.fileState = 'approved';
      s.access = 'active';
      s.accessCategory = 'private';
    });
    await tester.tap(find.text('Changer de situation : devenir taxi'));
    await settle(tester);
    expect(find.textContaining('les jours déjà payés ne sont pas remboursés'), findsOneWidget);
  });

  testWidgets('l’onglet Course bascule entre Client et Chauffeur pour un chauffeur actif', (tester) async {
    tester.view.physicalSize = const Size(900, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final login = (await tester.runAsync(() async {
      final session = fixture.grant();
      final store = fixture.MemoryStore();
      await store.write(LoginSnapshot(randomLoginId(), session: session));
      final login = LoginFlow(DriverFixture(session), store);
      await login.initialize();
      return login;
    }))!;
    await tester.pumpWidget(MaterialApp(home: HomeShell(login: login, course: const Text('carte client'),
        voyages: const Text('voyages'), driver: const Text('écran chauffeur'))));
    await settle(tester);
    expect(find.text('carte client'), findsOneWidget);
    await tester.tap(find.text('Chauffeur'));
    await settle(tester);
    expect(find.text('écran chauffeur'), findsOneWidget);
    await tester.tap(find.text('Client'));
    await settle(tester);
    expect(find.text('carte client'), findsOneWidget);
  });
}
