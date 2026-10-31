import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/ride_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/rides/presentation/driver_ride_page.dart';

import 'city_ride_test.dart' show RideFixture;
import 'driver_document_test.dart' as fixture;
import 'voyages_page_test.dart' show iso, departure, settle;

const _rideIds = ['11111111-2222-4333-8444-555555555555', '61111111-2222-4333-8444-555555555555'];
const _proposalIds = ['41111111-2222-4333-8444-555555555555', '71111111-2222-4333-8444-555555555555'];

/// A stand-in server for the driver side of K31 and R15: rides follow [rideState] until an action changes them.
class DriverFixture extends RideFixture implements DriverRideGateway {
  DriverFixture(super.session);
  final driverCalls = <String>[];
  bool allowed = true;
  /// Proposals still to offer, in order; each acceptance creates the next ride.
  int offers = 1;
  final accepted = <String>[];
  final states = <String, String>{};

  Map<String, Object?> driverRide(String id) => {...rideJson(), 'reservationId': id, 'state': states[id] ?? rideState,
    'viewerRole': 'driver', 'boardingCode': null, 'refundXaf': null, 'driverPosition': null, 'driverPositionAt': null};

  @override
  Future<CityRideView?> activeRide(LoginSession s, String c) async => null;

  @override
  Future<List<CityRideView>> driverRides(LoginSession s, String c) async => [
    for (final id in accepted)
      if (activeRideStates.contains(states[id] ?? rideState)) CityRideView.parse(driverRide(id)),
  ];

  @override
  Future<DriverPresenceView> setPresence(LoginSession s, String c, bool online, RidePoint? at, {RidePoint? destination}) async {
    driverCalls.add('presence:$online:${at?.toJson()}${destination == null ? '' : ':vers:${destination.toJson()}'}');
    if (!allowed) throw const PhotoProblem(PhotoFailure.driverNotAllowed);
    return DriverPresenceView.parse({'online': online, 'positionAt': online ? iso(departure) : null,
      'destination': destination?.toJson()});
  }

  @override
  Future<List<DriverProposalView>> driverProposals(LoginSession s, String c) async => DriverProposalView.parseList([
    if (offers > 0) {'proposalId': _proposalIds[accepted.length], 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333,
      'pickupSeconds': 240, 'expiresAt': iso(departure), 'pickup': {'latitude': 3.8667, 'longitude': 11.5167},
      'dropoff': {'latitude': 3.8935, 'longitude': 11.509}, 'detourSeconds': 120},
  ]);

  @override
  Future<CityRideView?> answerProposal(LoginSession s, String c, String id, bool accept) async {
    driverCalls.add('answer:$accept');
    offers--;
    if (!accept) return null;
    final ride = _rideIds[accepted.length];
    accepted.add(ride);
    return CityRideView.parse(driverRide(ride));
  }

  @override
  Future<CityRideView> driverRideAction(LoginSession s, String c, String id, String action, String? code) async {
    driverCalls.add('action:$action:${code ?? '-'}');
    if (action == 'board' && code != '048213') throw const PhotoProblem(PhotoFailure.wrongCode);
    states[id] = switch (action) { 'arrive' => 'driver_arrived', 'board' => 'boarded', 'finish' => 'finished', _ => 'cancelled_by_driver' };
    return CityRideView.parse(driverRide(id));
  }

  @override
  Future<CityRideView> cityRide(LoginSession s, String c, String id) async => CityRideView.parse(driverRide(id));
}

Widget _fakeMap(BuildContext context, RidePoint? a, RidePoint? b, void Function(RidePoint) onTap, {RidePoint? driver}) =>
    GestureDetector(
  key: const Key('carte'), onTap: () => onTap(const RidePoint(3.87, 11.51)), child: const ColoredBox(color: Colors.green));

Future<DriverFixture> _open(WidgetTester tester, {RidePoint? gps = const RidePoint(3.8601, 11.5202)}) async {
  tester.view.physicalSize = const Size(900, 2600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = DriverFixture(session);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: DriverRidePage(login: login, mapBuilder: _fakeMap, readPosition: () async => gps,
      pollEvery: null, presenceEvery: null)));
  await settle(tester);
  return gateway;
}

void main() {
  test('lecture stricte : une proposition où la part dépasse le prix est refusée', () {
    expect(() => DriverProposalView.parseList([{'proposalId': _proposalIds.first, 'mode': 'shared', 'seats': 1, 'priceXaf': 100,
      'driverXaf': 101, 'pickupSeconds': 60, 'expiresAt': '2026-10-30T10:00:00Z', 'pickup': {'latitude': 3.8, 'longitude': 11.5},
      'dropoff': {'latitude': 3.9, 'longitude': 11.5}, 'detourSeconds': 0}]), throwsA(isA<PhotoProblem>()));
    expect(() => CityRideView.parse({'reservationId': _rideIds.first, 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333,
      'state': 'confirmed', 'holdExpiresAt': null, 'arrivedAt': null, 'viewerRole': 'driver', 'boardingCode': '123456',
      'refundXaf': null, 'pickup': {'latitude': 3.8, 'longitude': 11.5}, 'dropoff': {'latitude': 3.9, 'longitude': 11.5},
      'driverPosition': null, 'driverPositionAt': null}),
        throwsA(isA<PhotoProblem>()), reason: 'a driver never receives the client code');
  });

  testWidgets('en ligne avec le GPS, accepter, arriver, faire monter avec le bon code, terminer', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byType(Switch));
    await settle(tester);
    expect(server.driverCalls.first, 'presence:true:{latitude: 3.8601, longitude: 11.5202}');
    await tester.tap(find.text('Actualiser'));
    await settle(tester);
    expect(find.text('Client à 4 min de route'), findsOneWidget);
    expect(find.text('Prix : 370 F · ta part : 333 F'), findsOneWidget);
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    expect(find.textContaining('Course acceptée : le client a 3 minutes pour payer.'), findsOneWidget);
    expect(find.text('Je suis arrivé au départ'), findsNothing, reason: 'not before payment');

    server.rideState = 'confirmed';
    await tester.tap(find.text('Actualiser'));
    await settle(tester);
    await tester.tap(find.text('Je suis arrivé au départ'));
    await settle(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Code du client (6 chiffres)'), '111111');
    await tester.tap(find.text('Faire monter le client'));
    await settle(tester);
    expect(find.textContaining('Ce code ne correspond pas'), findsOneWidget);
    await tester.enterText(find.widgetWithText(TextField, 'Code du client (6 chiffres)'), '048213');
    await tester.tap(find.text('Faire monter le client'));
    await settle(tester);
    await tester.tap(find.text('Terminer la course'));
    await settle(tester);
    expect(find.textContaining('Course terminée : ta part est dans tes gains.'), findsOneWidget);
    expect(server.driverCalls, containsAllInOrder(['answer:true', 'action:arrive:-', 'action:board:111111', 'action:board:048213',
      'action:finish:-']));
  });

  testWidgets('sans GPS, le chauffeur écrit le lieu où il se trouve, puis passe en ligne à ce point', (tester) async {
    final server = await _open(tester, gps: null);
    await tester.enterText(find.widgetWithText(TextField, 'Où es-tu ?'), 'Poste');
    await tester.pump(const Duration(milliseconds: 450)); // the typing delay, on the test clock
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);
    await tester.tap(find.widgetWithText(ListTile, 'Poste centrale'));
    await settle(tester);
    expect(find.byIcon(Icons.check_circle_outline), findsOneWidget);
    await tester.tap(find.byType(Switch));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.8667, longitude: 11.5167}');
    expect(find.text('En ligne'), findsOneWidget);
  });

  testWidgets('avec une direction, le chauffeur prend deux clients en partagé et mène chaque course', (tester) async {
    final server = await _open(tester);
    server.offers = 2;
    await tester.enterText(find.widgetWithText(TextField, 'Où vas-tu ? (facultatif)'), 'Bast');
    await tester.pump(const Duration(milliseconds: 450));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    await tester.tap(find.byType(Switch));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.8601, longitude: 11.5202}:vers:{latitude: 3.8935, longitude: 11.509}');
    expect(find.textContaining('En route vers Bastos'), findsOneWidget);

    await tester.tap(find.text('Actualiser'));
    await settle(tester);
    expect(find.text('Détour sur ton chemin : 2 min'), findsOneWidget);
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    await tester.tap(find.text('Actualiser'));
    await settle(tester);
    expect(find.text('En attente d’un autre client sur ton chemin…'), findsNothing, reason: 'a second client is offered');
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    expect(find.textContaining('Course acceptée'), findsNWidgets(2), reason: 'two clients in the car');

    // Each ride has its own steps: the first one is paid and boards, the second still waits for payment.
    server.states[server.accepted.first] = 'confirmed';
    await tester.tap(find.text('Actualiser'));
    await settle(tester);
    await tester.tap(find.text('Je suis arrivé au départ'));
    await settle(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Code du client (6 chiffres)'), '048213');
    await tester.tap(find.text('Faire monter le client'));
    await settle(tester);
    expect(find.textContaining('Client à bord'), findsOneWidget);
    expect(find.textContaining('Course acceptée'), findsOneWidget);
    expect(find.text('En attente d’un autre client sur ton chemin…'), findsOneWidget, reason: 'still receiving on the way');
  });

  testWidgets('sans GPS, la position vient de la carte ; un compte non chauffeur est refusé clairement', (tester) async {
    final server = await _open(tester, gps: null);
    await tester.tap(find.byType(Switch));
    await settle(tester);
    expect(find.textContaining('touche la carte'), findsWidgets);
    expect(server.driverCalls, isEmpty, reason: 'nothing sent without a position');
    await tester.tap(find.byKey(const Key('carte')));
    server.allowed = false;
    await tester.tap(find.byType(Switch));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.87, longitude: 11.51}');
    expect(find.textContaining('Seul un chauffeur approuvé'), findsOneWidget);
  });
}
