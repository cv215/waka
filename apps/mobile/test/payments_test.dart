import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/payment_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/payments/presentation/wallet_page.dart';
import 'package:waka_mobile/features/voyages/presentation/voyages_page.dart';

import 'driver_document_test.dart' as fixture;
import 'voyages_page_test.dart' show VoyageFixture, iso, departure, reservationJson, settle;

/// A stand-in server that keeps the two pockets and answers like K33.
class PayFixture extends VoyageFixture implements PaymentGateway {
  PayFixture(super.session);
  int credit = 2000, earnings = 1200;
  final calls = <String>[];
  final payments = <String, Map<String, Object?>>{};
  String nextState = 'pending';

  Map<String, Object?> paymentJson(String id, String purpose, String? target, String method, int amount, String state, bool applied) => {
    'paymentId': id, 'purpose': purpose, 'targetId': target, 'method': method, 'chargedXaf': amount, 'creditedXaf': amount,
    'feeXaf': 0, 'state': state, 'applied': applied, 'createdAt': iso(departure), 'settledAt': state == 'pending' ? null : iso(departure),
    'idempotentReplay': false,
  };

  @override
  Future<PaymentView> pay(LoginSession s, String c, String key, String purpose, String? target, String method, String? phone,
      int? amount) async {
    calls.add('pay:$purpose:$method:${phone ?? '-'}:${amount ?? '-'}');
    final charged = amount ?? 12000;
    final internal = method == 'credit' || method == 'earnings';
    if (internal && (method == 'credit' ? credit : earnings) < charged) throw const PhotoProblem(PhotoFailure.insufficientCredit);
    final json = paymentJson(key, purpose, target, method, charged, internal ? 'succeeded' : nextState, internal);
    payments[key] = json;
    return PaymentView.parse(json);
  }

  @override
  Future<PaymentView> payment(LoginSession s, String c, String id) async {
    calls.add('check:$id');
    final json = {...payments[id]!, 'state': 'succeeded', 'applied': payments[id]!['purpose'] != 'topup', 'settledAt': iso(departure)};
    if (json['purpose'] == 'topup') credit += json['chargedXaf'] as int;
    payments[id] = json;
    return PaymentView.parse(json);
  }

  @override
  Future<WalletView> wallet(LoginSession s, String c) async => WalletView.parse({
    'creditXaf': credit, 'earningsXaf': earnings,
    'lines': [{'at': iso(departure), 'pocket': 'earnings', 'amountXaf': 1200, 'kind': 'settlement'}],
  });

  @override
  Future<WithdrawalView> withdraw(LoginSession s, String c, String key, String pocket, int amount, String method, String phone) async {
    calls.add('withdraw:$pocket:$amount:$method:$phone');
    final fee = withdrawalFee(pocket, amount);
    if (pocket == 'earnings') { earnings -= amount + fee; } else { credit -= amount + fee; }
    return WithdrawalView.parse({'withdrawalId': key, 'pocket': pocket, 'method': method, 'amountXaf': amount, 'feeXaf': fee,
      'state': 'pending', 'createdAt': iso(departure), 'settledAt': null, 'idempotentReplay': false});
  }

  @override
  Future<WithdrawalView> withdrawal(LoginSession s, String c, String id) async => throw UnimplementedError();
  @override
  Future<SubscriptionPeriodView> buySubscription(LoginSession s, String c, String key) async => throw UnimplementedError();
  @override
  Future<List<SubscriptionPeriodView>> subscriptions(LoginSession s, String c) async => const [];
}

Future<(LoginFlow, PayFixture)> open(WidgetTester tester, Widget Function(LoginFlow) page) async {
  tester.view.physicalSize = const Size(900, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = PayFixture(session);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: page(login)));
  await settle(tester);
  return (login, gateway);
}

void main() {
  test('lecture stricte : champ en trop, état inconnu ou montant négatif refusés', () {
    final ok = {'paymentId': randomLoginId(), 'purpose': 'topup', 'targetId': null, 'method': 'cm.mtn', 'chargedXaf': 5000,
      'creditedXaf': 5000, 'feeXaf': 0, 'state': 'pending', 'applied': false, 'createdAt': iso(departure), 'settledAt': null,
      'idempotentReplay': false};
    expect(PaymentView.parse(ok).pending, isTrue);
    expect(() => PaymentView.parse({...ok, 'phone': '+237670000000'}), throwsA(isA<PhotoProblem>()));
    expect(() => PaymentView.parse({...ok, 'state': 'paid'}), throwsA(isA<PhotoProblem>()));
    expect(() => PaymentView.parse({...ok, 'chargedXaf': -1}), throwsA(isA<PhotoProblem>()));
    expect(() => WalletView.parse({'creditXaf': 0, 'earningsXaf': 0, 'lines': [
      {'at': iso(departure), 'pocket': 'savings', 'amountXaf': 5, 'kind': 'deposit'}]}), throwsA(isA<PhotoProblem>()));
    expect(withdrawalFee('earnings', 500), 10);
    expect(withdrawalFee('credit', 500), 15);
    expect(withdrawalFee('credit', 501), 16, reason: 'rounded up to the franc');
  });

  testWidgets('recharger : aucun frais, confirmation sur le téléphone puis crédit ajouté', (tester) async {
    final (login, gateway) = await open(tester, (login) => WalletPage(login: login, pollEvery: null));
    expect(find.text('2000 F'), findsOneWidget);
    expect(find.text('1200 F'), findsOneWidget);
    await tester.tap(find.text('Recharger'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), '5000');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Aucun frais à la recharge.'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '67 000 0000');
    await tester.tap(find.text('Payer 5000 F'));
    await settle(tester);
    expect(gateway.calls.last, 'pay:topup:cm.mtn:+237670000000:5000');
    expect(find.textContaining('Confirme le paiement de 5000 F sur ton téléphone'), findsOneWidget);
    await tester.tap(find.text('Vérifier'));
    await settle(tester);
    expect(find.text('Recharge reçue : 5000 F ajoutés à ton crédit WAKA.'), findsOneWidget);
    await tester.tap(find.text('Fermer'));
    await settle(tester);
    expect(find.text('7000 F'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('retirer ses gains : 2 % annoncés avant, seulement vers son numéro', (tester) async {
    final (login, gateway) = await open(tester, (login) => WalletPage(login: login, pollEvery: null));
    await tester.tap(find.text('Retirer'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Montant à recevoir (500 à 200000 F)'), '500');
    await tester.pump();
    expect(find.text('Frais : 10 F · retiré de la poche : 510 F'), findsOneWidget);
    await tester.tap(find.textContaining('Mon crédit (2000 F)'));
    await tester.pump();
    expect(find.text('Frais : 15 F · retiré de la poche : 515 F'), findsOneWidget);
    await tester.tap(find.textContaining('Mes gains (1200 F)'));
    await tester.pump();
    await tester.enterText(find.widgetWithText(TextField, 'Numéro de mon compte WAKA'), '600000000');
    await tester.tap(find.widgetWithText(FilledButton, 'Retirer'));
    await settle(tester);
    expect(gateway.calls.last, 'withdraw:earnings:500:cm.mtn:+237600000000');
    expect(find.textContaining('Retrait de 500 F en cours (frais 10 F)'), findsOneWidget);
    expect(find.text('690 F'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('payer une réservation de voyage avec ses gains, sans Mobile Money', (tester) async {
    final (login, gateway) = await open(tester, (login) => VoyagesPage(login: login, pollEvery: null));
    gateway.earnings = 20000;
    gateway.reservations.add(reservationJson(gateway.voyageId));
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Mes réservations'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Payer'));
    await settle(tester);
    expect(find.text('Montant : 12000 F'), findsOneWidget);
    await tester.tap(find.textContaining('Mon crédit WAKA (2000 F)'));
    await tester.pump();
    await tester.tap(find.text('Payer 12000 F'));
    await settle(tester);
    expect(find.text('Le solde de cette poche ne suffit pas.'), findsOneWidget);
    await tester.tap(find.textContaining('Mes gains (20000 F)'));
    await tester.pump();
    await tester.tap(find.text('Payer 12000 F'));
    await settle(tester);
    expect(gateway.calls.last, 'pay:voyage:earnings:-:-');
    expect(find.text('Paiement confirmé.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}
