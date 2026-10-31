import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/ride_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/rides/presentation/city_ride_page.dart' show RidePath;
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
    'viewerRole': 'driver', 'boardingCode': null, 'refundXaf': null, 'driverPosition': null, 'driverPositionAt': null,
    'driver': null, 'clientFirstName': {'confirmed', 'driver_arrived', 'boarded'}.contains(states[id] ?? rideState) ? 'Awa' : null};

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
    // The tour: each accepted ride still going, pickup (unless on board) then drop-off.
    final stops = [
      for (final id in accepted)
        if (activeRideStates.contains(states[id] ?? rideState)) ...[
          if ((states[id] ?? rideState) != 'boarded')
            {'kind': 'pickup', 'reservationId': id, 'point': {'latitude': 3.8667, 'longitude': 11.5167}},
          {'kind': 'dropoff', 'reservationId': id, 'point': {'latitude': 3.8935, 'longitude': 11.509}},
        ],
    ];
    return DriverPresenceView.parse({'online': online, 'positionAt': online ? iso(departure) : null,
      'destination': destination?.toJson(), 'route': destination == null ? null : '_p~iF~ps|U_ulLnnqC',
      'plan': online && stops.isNotEmpty ? '_p~iF~ps|U_ulLnnqC_mqNvxq`@' : null, 'stops': online ? stops : []});
  }

  @override
  Future<List<DriverProposalView>> driverProposals(LoginSession s, String c) async => DriverProposalView.parseList([
    if (offers > 0) {'proposalId': _proposalIds[accepted.length], 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333,
      'pickupSeconds': 240, 'expiresAt': iso(departure), 'pickup': {'latitude': 3.8667, 'longitude': 11.5167},
      'dropoff': {'latitude': 3.8935, 'longitude': 11.509}, 'detourSeconds': 120, 'route': '_p~iF~ps|U_ulLnnqC'},
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

  bool withDirection = false;
  @override
  Future<ProposalPathsView> proposalPaths(LoginSession s, String c, String id) async {
    driverCalls.add('paths:$id');
    return ProposalPathsView.parse({'normal': withDirection ? '_p~iF~ps|U_ulLnnqC' : null, 'client': '_p~iF~ps|U_ulLnnqC',
      'combined': '_p~iF~ps|U_ulLnnqC_mqNvxq`@'});
  }
}

Widget _fakeMap(BuildContext context, RidePoint? a, RidePoint? b, void Function(RidePoint) onTap,
        {RidePoint? driver, List<RidePath>? paths, List<RidePoint>? clients, bool follow = false}) =>
    GestureDetector(
  key: const Key('carte'), onTap: () => onTap(const RidePoint(3.87, 11.51)),
  child: ColoredBox(color: Colors.green, child: Text('tracés ${(paths ?? const []).map((p) => p.name).join('+')}'
      '${(clients ?? const []).isEmpty ? '' : ' clients:${clients!.length}'}${follow ? ' suivi' : ''}')));

final opened = <Uri>[];

Future<DriverFixture> _open(WidgetTester tester, {RidePoint? gps = const RidePoint(3.8601, 11.5202)}) async {
  opened.clear();
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
      openLink: (link) async { opened.add(link); return true; },
      pollEvery: null, presenceEvery: null)));
  await settle(tester);
  return gateway;
}

void main() {
  test('lecture stricte : une proposition où la part dépasse le prix est refusée', () {
    expect(() => DriverProposalView.parseList([{'proposalId': _proposalIds.first, 'mode': 'shared', 'seats': 1, 'priceXaf': 100,
      'driverXaf': 101, 'pickupSeconds': 60, 'expiresAt': '2026-10-30T10:00:00Z', 'pickup': {'latitude': 3.8, 'longitude': 11.5},
      'dropoff': {'latitude': 3.9, 'longitude': 11.5}, 'detourSeconds': 0, 'route': null}]), throwsA(isA<PhotoProblem>()));
    expect(DriverProposalView.parseList([{'proposalId': _proposalIds.first, 'mode': 'shared', 'seats': 1, 'priceXaf': 370,
      'driverXaf': 333, 'pickupSeconds': 600, 'expiresAt': '2026-10-30T10:00:00Z', 'pickup': {'latitude': 3.8, 'longitude': 11.5},
      'dropoff': {'latitude': 3.9, 'longitude': 11.5}, 'detourSeconds': 1500, 'route': null}]).single.detourSeconds, 1500,
        reason: 'revised R15: a long detour is shown, no longer refused');
    expect(() => CityRideView.parse({'reservationId': _rideIds.first, 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333,
      'state': 'confirmed', 'holdExpiresAt': null, 'arrivedAt': null, 'viewerRole': 'driver', 'boardingCode': '123456',
      'refundXaf': null, 'pickup': {'latitude': 3.8, 'longitude': 11.5}, 'dropoff': {'latitude': 3.9, 'longitude': 11.5},
      'driverPosition': null, 'driverPositionAt': null, 'route': null, 'driver': null, 'clientFirstName': null}),
        throwsA(isA<PhotoProblem>()), reason: 'a driver never receives the client code');
  });

  testWidgets('en ligne avec le GPS, accepter, arriver, faire monter avec le bon code, terminer', (tester) async {
    final server = await _open(tester);
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    expect(server.driverCalls.first, 'presence:true:{latitude: 3.8601, longitude: 11.5202}');
    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    expect(find.text('Client à 4 min de route'), findsOneWidget);
    expect(find.bySemanticsLabel(RegExp('secondes pour répondre')), findsOneWidget, reason: 'the 30-second countdown');
    expect(find.text('370 F'), findsOneWidget);
    expect(find.text('Ta part : 333 F'), findsOneWidget);
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    expect(find.textContaining('Course acceptée : le client a 3 minutes pour payer.'), findsOneWidget);
    expect(find.text('Je suis arrivé au départ'), findsNothing, reason: 'not before payment');

    server.rideState = 'confirmed';
    await tester.tap(find.byTooltip('Actualiser'));
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
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.8667, longitude: 11.5167}');
    expect(find.text('En ligne'), findsOneWidget);
  });

  test('un tracé reçu est décodé strictement', () {
    final shapes = ProposalPathsView.parse({'normal': null, 'client': '_p~iF~ps|U_ulLnnqC', 'combined': '_p~iF~ps|U_ulLnnqC'});
    expect(shapes.client!.first.latitude, 38.5);
    expect(shapes.client!.last.longitude, -120.95);
    expect(() => ProposalPathsView.parse({'normal': null, 'client': 'a b', 'combined': null}), throwsA(isA<PhotoProblem>()));
  });

  testWidgets('client en bleu, jaune une fois accepté ; Démarrer suit la voiture ; tout le trajet dans Google Maps', (tester) async {
    final server = await _open(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Où vas-tu ? (facultatif)'), 'Bast');
    await tester.pump(const Duration(milliseconds: 450));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await settle(tester);
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    expect(find.text('tracés normal+client clients:1'), findsOneWidget, reason: 'green, and the waiting client in blue with his pin');
    expect(find.text('Client qui attend ta réponse'), findsOneWidget);
    expect(server.driverCalls.where((c) => c.startsWith('paths:')), isEmpty, reason: 'the blue trip comes with the proposal');

    await tester.tap(find.text('Accepter'));
    await settle(tester);
    expect(find.text('tracés normal+combine clients:1'), findsOneWidget, reason: 'accepted: his trip is now in the yellow tour');
    expect(find.text('Ton trajet avec tes clients'), findsOneWidget);

    await tester.tap(find.text('Démarrer le trajet'));
    await settle(tester);
    expect(find.text('tracés normal+combine clients:1 suivi'), findsOneWidget, reason: 'the map follows the car');
    expect(find.text('Arrêter le suivi'), findsOneWidget);

    await tester.tap(find.text('Tout le trajet · Google Maps'));
    await settle(tester);
    expect(opened.single.queryParameters['waypoints'], '3.8667,11.5167|3.8935,11.509', reason: 'pickup, drop-off, then his direction');
    expect(opened.single.queryParameters['destination'], '3.8935,11.509');
    expect(opened.single.queryParameters['dir_action'], 'navigate');
    await tester.tap(find.text('Waze').first);
    await settle(tester);
    expect(opened.last.toString(), 'https://waze.com/ul?ll=3.8667%2C11.5167&navigate=yes', reason: 'Waze: the next stop');
    await tester.tap(find.textContaining('Aller au client'));
    await settle(tester);
    expect(opened.last.queryParameters['destination'], '3.8667,11.5167');

    server.states[server.accepted.single] = 'cancelled_by_client';
    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    expect(find.text('tracés normal suivi'), findsOneWidget, reason: 'the cancelled client leaves the yellow tour at once');
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
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.8601, longitude: 11.5202}:vers:{latitude: 3.8935, longitude: 11.509}');
    expect(find.textContaining('En route vers Bastos'), findsOneWidget);
    expect(find.text('tracés normal'), findsOneWidget, reason: 'his green route as soon as he is online with a direction');
    expect(find.text('Ton trajet normal'), findsOneWidget);

    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    expect(find.text('Détour sur ton chemin : 2 min'), findsOneWidget);
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    expect(find.text('En attente d’un autre client sur ton chemin…'), findsNothing, reason: 'a second client is offered');
    await tester.tap(find.text('Accepter'));
    await settle(tester);
    expect(find.textContaining('Course acceptée'), findsNWidgets(2), reason: 'two clients in the car');

    // Each ride has its own steps: the first one is paid and boards, the second still waits for payment.
    server.states[server.accepted.first] = 'confirmed';
    await tester.tap(find.byTooltip('Actualiser'));
    await settle(tester);
    await tester.tap(find.text('Je suis arrivé au départ'));
    await settle(tester);
    await tester.enterText(find.widgetWithText(TextField, 'Code du client (6 chiffres)'), '048213');
    await tester.tap(find.text('Faire monter le client'));
    await settle(tester);
    expect(find.text('Déposer Awa'), findsOneWidget, reason: 'the first name of the client, once paid');
    expect(find.textContaining('Course acceptée'), findsOneWidget);
    expect(find.text('En attente d’un autre client sur ton chemin…'), findsOneWidget, reason: 'still receiving on the way');
  });

  testWidgets('sans GPS, la position vient de la carte ; un compte non chauffeur est refusé clairement', (tester) async {
    final server = await _open(tester, gps: null);
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    expect(find.textContaining('touche la carte'), findsWidgets);
    expect(server.driverCalls, isEmpty, reason: 'nothing sent without a position');
    await tester.tap(find.byKey(const Key('carte')));
    server.allowed = false;
    await tester.tap(find.byKey(const Key('en-ligne')));
    await settle(tester);
    expect(server.driverCalls.single, 'presence:true:{latitude: 3.87, longitude: 11.51}');
    expect(find.textContaining('Seul un chauffeur approuvé'), findsOneWidget);
  });
}
