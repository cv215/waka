import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/identity/data/voyage_models.dart';
import 'package:waka_mobile/features/voyages/presentation/voyages_page.dart';

import 'driver_document_test.dart' as fixture;

String iso(DateTime t) => '${t.toUtc().toIso8601String().substring(0, 19)}Z';
final departure = DateTime.utc(2026, 11, 15, 6);

Map<String, Object?> voyageJson(String id, {Map<String, int>? available}) => {
  'voyageId': id, 'category': 'private', 'seats': 4, 'state': 'published',
  'stops': [
    {'place': 'Yaoundé', 'plannedAt': iso(departure)},
    {'place': 'Edéa', 'plannedAt': iso(departure.add(const Duration(hours: 3)))},
    {'place': 'Douala', 'plannedAt': iso(departure.add(const Duration(hours: 4)))},
  ],
  'prices': {'0-1': 4000, '0-2': 6000, '1-2': 2000},
  'availableSeats': available ?? {'0-1': 4, '0-2': 4, '1-2': 4},
  'createdAt': iso(departure.subtract(const Duration(days: 2))), 'idempotentReplay': false,
};

Map<String, Object?> reservationJson(String voyage, {String state = 'awaiting_payment', int? refund, int? fee}) => {
  'reservationId': randomLoginId(), 'voyageId': voyage, 'fromIndex': 0, 'toIndex': 2, 'seats': 2, 'seatPriceXaf': 6000,
  'totalXaf': 12000, 'state': state, 'holdExpiresAt': iso(departure), 'boardingAt': iso(departure),
  'refundXaf': refund, 'driverXaf': refund == null ? null : (12000 - refund) ~/ 2,
  'wakaXaf': refund == null ? null : 12000 - refund - (12000 - refund) ~/ 2, 'feePercent': fee,
  'boardingCode': {'awaiting_payment', 'confirmed'}.contains(state) ? '482913' : null, 'idempotentReplay': false,
};

class VoyageFixture extends fixture.Gateway implements VoyageGateway {
  VoyageFixture(LoginSession session) : super(session, 'private', 'holder');
  final voyageId = randomLoginId();
  final reserved = <String>[];
  final published = <(List<VoyageStop>, int, Map<String, int>)>[];
  final reservations = <Map<String, Object?>>[];
  final boardings = <String>[];
  final absents = <String>[];
  final incidents = <Map<String, Object?>>[];
  final sentMessages = <String>[];

  Map<String, Object?> incidentJson(String state, List<Map<String, Object?>> messages) => {
    'incidentId': '11111111-1111-4111-8111-111111111111', 'reservationId': voyageId, 'category': 'behaviour', 'state': state,
    'version': messages.length, 'viewerRole': 'client', 'createdAt': iso(departure), 'messages': messages, 'idempotentReplay': false,
  };
  Map<String, Object?> msg(String kind, String role, String? text, {String? decision}) => {
    'kind': kind, 'authorRole': role, 'decision': decision, 'reasonCode': decision == null ? null : 'SPEEDING_REPORTED',
    'text': text, 'at': iso(departure),
  };

  @override
  Future<IncidentView> reportIncident(LoginSession s, String c, String key, String reservation, String category, String text) async {
    final json = incidentJson('open', [msg('report', 'client', text)]);
    incidents..clear()..add(json);
    return IncidentView.parse(json);
  }
  @override
  Future<List<IncidentView>> myIncidents(LoginSession s, String c) async => IncidentView.parseList(incidents);
  @override
  Future<IncidentView> incidentMessage(LoginSession s, String c, String key, String incident, String kind, String text) async {
    sentMessages.add('$kind:$text');
    final json = incidentJson(kind == 'contest' ? 'contested' : 'decided', [msg('report', 'client', 'Trop vite'), msg(kind, 'client', text)]);
    incidents..clear()..add(json);
    return IncidentView.parse(json);
  }

  @override
  Future<PassengerView> markNoShow(LoginSession s, String c, String voyage, String reservation) async {
    absents.add(reservation);
    return PassengerView.parse({'reservationId': reservation, 'fromIndex': 0, 'toIndex': 2, 'seats': 2, 'state': 'no_show',
      'boardingAt': iso(departure), 'driverXaf': 1800});
  }
  bool mineHasVoyage = false;

  @override
  Future<List<PassengerView>> voyagePassengers(LoginSession s, String c, String voyage) async => [
    PassengerView.parse({'reservationId': randomLoginId(), 'fromIndex': 0, 'toIndex': 2, 'seats': 2,
      'state': boardings.isEmpty ? 'confirmed' : 'boarded', 'boardingAt': iso(departure),
      'driverXaf': boardings.isEmpty ? null : 10800}),
  ];
  @override
  Future<PassengerView> boardPassenger(LoginSession s, String c, String voyage, String code) async {
    if (code != '482913') throw const PhotoProblem(PhotoFailure.wrongCode);
    boardings.add(code);
    return PassengerView.parse({'reservationId': randomLoginId(), 'fromIndex': 0, 'toIndex': 2, 'seats': 2, 'state': 'boarded',
      'boardingAt': iso(departure), 'driverXaf': 10800});
  }

  @override
  Future<List<VoyageView>> upcomingVoyages(LoginSession s, String c) async => [VoyageView.parse(voyageJson(voyageId))];
  @override
  Future<List<VoyageView>> myVoyages(LoginSession s, String c) async =>
      mineHasVoyage ? [VoyageView.parse(voyageJson(voyageId))] : const [];
  @override
  Future<VoyageView> publishVoyage(LoginSession s, String c, String key, List<VoyageStop> stops, int seats, Map<String, int> prices) async {
    published.add((stops, seats, prices));
    return VoyageView.parse(voyageJson(randomLoginId()));
  }
  @override
  Future<VoyageView> cancelVoyage(LoginSession s, String c, String id, String reason) async => VoyageView.parse(voyageJson(id));
  @override
  Future<ReservationView> reserveVoyage(LoginSession s, String c, String key, String voyage, int from, int to, int seats) async {
    reserved.add('$key:$from-$to:$seats');
    final json = reservationJson(voyage);
    reservations.add(json);
    return ReservationView.parse(json);
  }
  @override
  Future<List<ReservationView>> myVoyageReservations(LoginSession s, String c) async =>
      ReservationView.parseList(reservations);
  @override
  Future<ReservationView> cancelVoyageReservation(LoginSession s, String c, String id) async {
    final cancelled = reservationJson(voyageId, state: 'cancelled_by_client', refund: 10200, fee: 15);
    reservations..clear()..add(cancelled);
    return ReservationView.parse(cancelled);
  }
}

Future<(LoginFlow, VoyageFixture)> show(WidgetTester tester, {PickDateTime? pick}) async {
  tester.view.physicalSize = const Size(900, 2400);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = VoyageFixture(session);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: VoyagesPage(login: login, pickDateTime: pick ?? (_) async => null)));
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await tester.pumpAndSettle();
  return (login, gateway);
}

Future<void> settle(WidgetTester tester) async {
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await tester.pumpAndSettle();
}

void main() {
  test('lecture stricte : total incohérent, règlement faux ou prix au-dessus du plafond refusés', () {
    final id = randomLoginId();
    expect(() => ReservationView.parse({...reservationJson(id), 'totalXaf': 11000}), throwsA(isA<PhotoProblem>()));
    expect(() => ReservationView.parse({...reservationJson(id, state: 'cancelled_by_client', refund: 10200, fee: 15), 'wakaXaf': 1}),
        throwsA(isA<PhotoProblem>()));
    expect(() => VoyageView.parse({...voyageJson(id), 'prices': {'0-1': 4000, '0-2': 10001, '1-2': 2000}}), throwsA(isA<PhotoProblem>()));
    expect(() => VoyageView.parse({...voyageJson(id), 'driverAccountId': 'x'}), throwsA(isA<PhotoProblem>()));
  });

  testWidgets('réserver le trajet complet : prix du chauffeur, paiement demandé dans les 3 minutes', (tester) async {
    final (login, gateway) = await show(tester);
    expect(find.text('Yaoundé → Edéa → Douala'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Réserver'));
    await tester.pumpAndSettle();
    expect(find.text('Total : 6000 F (6000 F la place).'), findsOneWidget);
    await tester.tap(find.widgetWithText(FilledButton, 'Réserver'));
    await settle(tester);
    expect(gateway.reserved.single, endsWith(':0-2:1'));
    expect(find.textContaining('Tes places sont gardées 3 minutes : paie maintenant.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('annuler une réservation affiche le remboursement calculé par le serveur', (tester) async {
    final (login, gateway) = await show(tester);
    gateway.reservations.add(reservationJson(gateway.voyageId, state: 'confirmed'));
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Mes réservations'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Annuler'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Gratuit jusqu’à 12 h avant le départ'), findsOneWidget);
    await tester.tap(find.text('Annuler la réservation'));
    await settle(tester);
    expect(find.text('Réservation annulée : 10200 F remboursés (frais 15 %).'), findsOneWidget);
    expect(find.textContaining('482913'), findsNothing, reason: 'a cancelled reservation no longer shows its code');
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('publier : un prix par paire d’arrêts, plafonné à 10 000 F', (tester) async {
    var calls = 0;
    final (login, gateway) = await show(tester, pick: (_) async => DateTime(2026, 11, 15, 6 + 4 * calls++));
    await tester.tap(find.text('Mes voyages'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Publier un voyage'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Lieu de départ'), 'Yaoundé');
    await tester.enterText(find.widgetWithText(TextField, 'Lieu d’arrivée'), 'Douala');
    await tester.tap(find.text('Choisir — départ'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Choisir — arrêt 2'));
    await tester.pumpAndSettle();
    final price = find.widgetWithText(TextField, 'Yaoundé → Douala (F)');
    await tester.enterText(price, '10001');
    await tester.pump();
    expect(find.text('Chaque prix va de 1 à 10 000 F la place.'), findsOneWidget);
    await tester.enterText(price, '6000');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Publier'));
    await settle(tester);
    final (stops, seats, prices) = gateway.published.single;
    expect(stops.map((s) => s.place), ['Yaoundé', 'Douala']);
    expect(seats, 1);
    expect(prices, {'0-1': 6000});
    expect(find.text('Voyage publié.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
  testWidgets('le client voit son code ; le chauffeur embarque avec ce code et voit son gain', (tester) async {
    final (login, gateway) = await show(tester);
    gateway.reservations.add(reservationJson(gateway.voyageId, state: 'confirmed'));
    gateway.mineHasVoyage = true;
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Mes réservations'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Code d’embarquement : 482913'), findsOneWidget);
    await tester.tap(find.text('Mes voyages'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Passagers'));
    await settle(tester);
    expect(find.text('Yaoundé → Douala · 2 place(s)'), findsOneWidget);
    expect(find.text('Payé, à embarquer'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Code du passager'), '111111');
    await tester.tap(find.widgetWithText(FilledButton, 'Embarquer'));
    await settle(tester);
    expect(find.text('Ce code ne correspond à aucun passager de ce voyage.'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Code du passager'), '482913');
    await tester.tap(find.widgetWithText(FilledButton, 'Embarquer'));
    await settle(tester);
    expect(find.text('Passager embarqué : 2 place(s). Tu recevras 10800 F.'), findsOneWidget);
    expect(find.text('Embarqué · 10800 F pour toi'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('le chauffeur déclare un passager payé absent : 30 % retenus', (tester) async {
    final (login, gateway) = await show(tester);
    gateway.mineHasVoyage = true;
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Mes voyages'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Passagers'));
    await settle(tester);
    await tester.tap(find.widgetWithText(TextButton, 'Absent'));
    await settle(tester);
    expect(gateway.absents, hasLength(1));
    expect(find.text('Passager déclaré absent : 30 % retenus, 1800 F pour toi, le reste lui est remboursé.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

  testWidgets('signaler un problème puis contester la décision de l’assistance', (tester) async {
    final (login, gateway) = await show(tester);
    gateway.reservations.add(reservationJson(gateway.voyageId, state: 'confirmed'));
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Mes réservations'));
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(TextButton, 'Signaler'));
    await tester.pumpAndSettle();
    await tester.enterText(find.widgetWithText(TextField, 'Ce qui s’est passé'), 'Trop vite');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Envoyer'));
    await settle(tester);
    expect(find.text('Signalement envoyé à l’assistance WAKA.'), findsOneWidget);
    gateway.incidents
      ..clear()
      ..add(gateway.incidentJson('decided', [gateway.msg('report', 'client', 'Trop vite'),
        gateway.msg('decision', 'assistance', 'Rappel envoyé.', decision: 'driver_warning')]));
    await tester.tap(find.byIcon(Icons.refresh));
    await settle(tester);
    await tester.tap(find.text('Incidents'));
    await tester.pumpAndSettle();
    expect(find.text('Assistance WAKA — décision : Avertissement au chauffeur · Rappel envoyé.'), findsOneWidget);
    await tester.tap(find.widgetWithText(TextButton, 'Contester'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField).last, 'Pas suffisant');
    await tester.pump();
    await tester.tap(find.widgetWithText(FilledButton, 'Envoyer'));
    await settle(tester);
    expect(gateway.sentMessages, ['contest:Pas suffisant']);
    expect(find.text('Contestation envoyée : l’examen est rouvert.'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });
}