import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/drivers/presentation/driver_access_page.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/driver_access_models.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';

import 'driver_document_test.dart' as fixture;

String now() => DateTime.now().toUtc().toIso8601String();

class AccessFixture extends fixture.Gateway implements DriverAccessGateway {
  AccessFixture(LoginSession session, this.answer) : super(session, 'private', 'holder');
  Map<String, Object?> answer;
  @override
  Future<DriverAccessView> driverAccess(LoginSession s, String client) async => DriverAccessView.parse(answer);
}

Map<String, Object?> json(String access, {String? basis, List<String> reasons = const [], List<String> renew = const [],
    String? trial}) => {
  'access': access, 'basis': basis, 'applicationId': randomLoginId(), 'category': 'private', 'admissionState': 'approved',
  'reasons': reasons, 'documentsToRenew': renew, 'trial': trial, 'serverTime': now(),
};

Future<LoginFlow> show(WidgetTester tester, Map<String, Object?> answer) async {
  final login = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final login = LoginFlow(AccessFixture(session, answer), store);
    await login.initialize();
    return login;
  }))!;
  await tester.pumpWidget(MaterialApp(home: DriverAccessPage(login: login)));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await tester.pumpAndSettle();
  return login;
}

void main() {
  test('lecture stricte : un accès actif sans fondement ou un état inconnu est refusé', () {
    expect(() => DriverAccessView.parse(json('active')), throwsA(isA<PhotoProblem>()));
    expect(() => DriverAccessView.parse(json('unlimited', basis: 'taxi_no_subscription')), throwsA(isA<PhotoProblem>()));
    expect(() => DriverAccessView.parse({...json('none'), 'accountId': 'x'}), throwsA(isA<PhotoProblem>()));
  });

  testWidgets('particulier approuvé : accès provisoire et essai non commencé expliqué', (tester) async {
    final login = await show(tester, json('provisional', basis: 'provisional_before_first_service', trial: 'not_started'));
    expect(find.text('Accès provisoire'), findsOneWidget);
    expect(find.textContaining('après ta première course payée et terminée'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('pièce expirée : nouvelles réservations bloquées et pièce à renouveler', (tester) async {
    final login = await show(tester, json('new_reservations_blocked', reasons: ['DOCUMENT_EXPIRED'], renew: ['INSURANCE']));
    expect(find.text('Nouvelles réservations bloquées'), findsOneWidget);
    expect(find.textContaining('Une pièce essentielle a expiré'), findsOneWidget);
    expect(find.text('À renouveler dans ma candidature : 1 pièce(s).'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}
