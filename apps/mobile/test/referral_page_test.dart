import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/referral_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/referrals/presentation/referral_page.dart';

import 'driver_document_test.dart' as fixture;

String now() => DateTime.now().toUtc().toIso8601String();

/// Mirrors K22: own code, never oneself, one permanent referrer.
class ReferralFixture extends fixture.Gateway implements ReferralGateway {
  ReferralFixture(LoginSession session) : super(session, 'taxi', 'holder');
  static const own = 'WAKA2345', known = 'BCDFGH78';
  String? attached;
  String? attachedAt;
  final attempts = <String>[];

  @override
  Future<ReferralView> referral(LoginSession s, String client) async =>
      ReferralView.parse({'referralCode': own, 'referrerAttached': attached != null, 'attachedAt': attachedAt});

  @override
  Future<LinkageView> attachReferrer(LoginSession s, String client, String code) async {
    attempts.add(code);
    if (code == own) throw const PhotoProblem(PhotoFailure.selfReferral);
    if (code != known) throw const PhotoProblem(PhotoFailure.codeNotRecognized);
    final created = attached == null;
    attached = code;
    attachedAt ??= now();
    return LinkageView.parse({'attachedAt': attachedAt, 'created': created});
  }
}

void main() {
  test('lecture stricte : code ambigu ou état incohérent refusé', () {
    expect(() => ReferralView.parse({'referralCode': 'ABCDEF01', 'referrerAttached': false, 'attachedAt': null}),
        throwsA(isA<PhotoProblem>()));
    expect(() => ReferralView.parse({'referralCode': 'ABCDEFGH', 'referrerAttached': true, 'attachedAt': null}),
        throwsA(isA<PhotoProblem>()));
  });

  testWidgets('son propre code est refusé, un code valable est enregistré une fois', (tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    final (login, gateway) = (await tester.runAsync(() async {
      final session = fixture.grant();
      final store = fixture.MemoryStore();
      await store.write(LoginSnapshot(randomLoginId(), session: session));
      final gateway = ReferralFixture(session);
      final login = LoginFlow(gateway, store);
      await login.initialize();
      return (login, gateway);
    }))!;
    await tester.pumpWidget(MaterialApp(home: ReferralPage(login: login)));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text(ReferralFixture.own), findsOneWidget);
    final field = find.widgetWithText(TextField, 'Code de mon parrain');
    await tester.enterText(field, 'waka 2345');
    await tester.tap(find.text('Enregistrer ce parrain'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.textContaining('ton propre code'), findsOneWidget);
    expect(gateway.attempts, ['WAKA2345'], reason: 'espaces retirés, majuscules');
    await tester.enterText(field, 'BCDFGH78');
    await tester.tap(find.text('Enregistrer ce parrain'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pumpAndSettle();
    expect(find.text('Parrain enregistré.'), findsOneWidget);
    expect(find.textContaining('Il ne peut pas être changé librement'), findsOneWidget);
    expect(find.text('Enregistrer ce parrain'), findsNothing);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}
