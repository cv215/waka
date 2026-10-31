import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/ride_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/rides/presentation/city_ride_page.dart';

import 'driver_document_test.dart' as fixture;
import 'payments_test.dart' show PayFixture;
import 'voyages_page_test.dart' show iso, departure, settle;

const _ride = '11111111-2222-4333-8444-555555555555';
const _quote = '21111111-2222-4333-8444-555555555555';
const _search = '31111111-2222-4333-8444-555555555555';
/// Google's documented sample polyline: three points.
const _shape = '_p~iF~ps|U_ulLnnqC_mqNvxq`@';

/// A stand-in server answering like K31 and K35; the payments come from [PayFixture].
class RideFixture extends PayFixture implements RideGateway {
  RideFixture(super.session);
  final rideCalls = <String>[];
  String rideState = 'awaiting_payment';
  String searchState = 'searching';
  bool outside = false, inProgress = false;

  Map<String, Object?> rideJson() => {
    'reservationId': _ride, 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333, 'state': rideState,
    'holdExpiresAt': iso(departure), 'arrivedAt': null, 'viewerRole': 'client',
    'boardingCode': rideState == 'awaiting_payment' ? null : '048213', 'refundXaf': rideState == 'cancelled_by_client' ? 370 : null,
    'pickup': {'latitude': 3.8667, 'longitude': 11.5167}, 'dropoff': {'latitude': 3.8935, 'longitude': 11.509},
    'driverPosition': rideState == 'confirmed' ? {'latitude': 3.87, 'longitude': 11.52} : null,
    'driverPositionAt': rideState == 'confirmed' ? iso(departure) : null, 'route': _shape,
    'driver': {'confirmed', 'driver_arrived', 'boarded'}.contains(rideState)
        ? {'firstName': 'Paul', 'vehicle': 'Toyota Yaris', 'color': 'Grise', 'plate': 'LT 123 AB'} : null,
  };

  @override
  Future<CityRideView?> activeRide(LoginSession s, String c) async => inProgress ? CityRideView.parse(rideJson()) : null;

  Map<String, Object?> searchJson() => {
    'searchId': _search, 'state': searchState, 'expiresAt': iso(departure), 'proposalPending': searchState == 'searching',
    'ride': searchState == 'matched' ? rideJson() : null, 'idempotentReplay': false,
  };

  @override
  Future<List<PlaceSuggestion>> placeSuggestions(LoginSession s, String c, String query, String token) async {
    rideCalls.add('suggest:$query');
    return PlaceSuggestion.parseList([
      {'placeId': 'ChIJposte0001', 'mainText': 'Poste centrale', 'secondaryText': 'Yaoundé, Cameroun'},
      {'placeId': 'ChIJbastos0001', 'mainText': 'Bastos', 'secondaryText': 'Yaoundé, Cameroun'},
    ]);
  }

  @override
  Future<RidePoint> placeLocation(LoginSession s, String c, String placeId, String token) async {
    rideCalls.add('locate:$placeId');
    return parsePlaceLocation(placeId == 'ChIJposte0001'
        ? {'placeId': placeId, 'latitude': 3.8667, 'longitude': 11.5167}
        : {'placeId': placeId, 'latitude': 3.8935, 'longitude': 11.509}, placeId);
  }

  @override
  Future<RideQuoteView> quoteRide(LoginSession s, String c, String key, String mode, int people, RidePoint o, RidePoint d) async {
    rideCalls.add('quote:$mode:$people:${o.toJson()}:${d.toJson()}');
    if (outside) throw const PhotoProblem(PhotoFailure.outsideServiceArea);
    return RideQuoteView.parse({'quoteId': _quote, 'mode': mode, 'passengerCount': people, 'distanceMetres': 3677,
      'fareVersion': 'launch-v1', 'bandCode': 'B05', 'placePriceXaf': 370, 'totalXaf': mode == 'vip' ? 4625 : 370 * people, 'currency': 'XAF',
      'createdAt': iso(departure), 'expiresAt': iso(departure), 'idempotentReplay': false, 'route': _shape});
  }

  @override
  Future<RideSearchView> startRideSearch(LoginSession s, String c, String key, String quoteId) async {
    rideCalls.add('search:$quoteId');
    return RideSearchView.parse(searchJson());
  }

  @override
  Future<RideSearchView> rideSearch(LoginSession s, String c, String id) async => RideSearchView.parse(searchJson());
  @override
  Future<RideSearchView> cancelRideSearch(LoginSession s, String c, String id) async {
    searchState = 'cancelled';
    return RideSearchView.parse(searchJson());
  }
  @override
  Future<CityRideView> cityRide(LoginSession s, String c, String id) async => CityRideView.parse(rideJson());
  @override
  Future<CityRideView> cancelCityRide(LoginSession s, String c, String id) async {
    rideState = 'cancelled_by_client';
    return CityRideView.parse(rideJson());
  }
}

Widget _fakeMap(BuildContext context, RidePoint? origin, RidePoint? destination, void Function(RidePoint) onTap,
        {RidePoint? driver, List<RidePath>? paths, List<RidePoint>? clients, bool follow = false}) =>
    GestureDetector(
      key: const Key('carte'),
      onTap: () => onTap(const RidePoint(3.86, 11.52)),
      child: ColoredBox(color: Colors.green,
          child: Text('carte ${origin != null ? 'D' : '-'}${destination != null ? 'A' : '-'}${driver != null ? 'C' : ''}'
              '${paths == null ? '' : ' ${paths.map((p) => '${p.name}:${p.points.length}').join(',')}'}')),
    );

Future<(LoginFlow, RideFixture)> _open(WidgetTester tester, {void Function(RideFixture)? before,
    RidePoint? gps = const RidePoint(3.8667, 11.5167), Duration? pollEvery}) async {
  tester.view.physicalSize = const Size(900, 2600);
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
  await tester.pumpWidget(MaterialApp(home: CityRidePage(login: login, mapBuilder: _fakeMap, pollEvery: pollEvery,
      readPosition: () async => gps, typingDelay: Duration.zero)));
  await settle(tester);
  return (login, gateway);
}

Future<void> _type(WidgetTester tester, String label, String text) async {
  await tester.enterText(find.widgetWithText(TextField, label), text);
  await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
  await settle(tester);
}

void main() {
  test('lecture stricte : une course mal formée ou un lieu inconnu est refusé', () {
    expect(() => PlaceSuggestion.parseList([{'placeId': '../x', 'mainText': 'A', 'secondaryText': ''}]), throwsA(isA<PhotoProblem>()));
    expect(() => parsePlaceLocation({'placeId': 'ChIJother0001', 'latitude': 3.8, 'longitude': 11.5}, 'ChIJposte0001'),
        throwsA(isA<PhotoProblem>()));
    expect(RideQuoteView.parse({'quoteId': _quote, 'mode': 'shared', 'passengerCount': 1, 'distanceMetres': 10, 'fareVersion': 'v',
      'bandCode': 'B01', 'placePriceXaf': 1, 'totalXaf': 1, 'currency': 'XAF', 'createdAt': '2026-10-30T10:00:00Z',
      'expiresAt': '2026-10-30T10:05:00Z', 'idempotentReplay': false, 'route': 'not a shape'}).route, isNull,
        reason: 'an unreadable shape is dropped, the price stays');
    expect(() => RideSearchView.parse({'searchId': _search, 'state': 'matched', 'expiresAt': '2026-10-30T10:00:00Z',
      'proposalPending': false, 'ride': null, 'idempotentReplay': false}), throwsA(isA<PhotoProblem>()));
    expect(const RidePoint(3.86666666, 11.5).toJson(), {'latitude': 3.866667, 'longitude': 11.5});
  });

  testWidgets('départ = ma position ; une destination donne Partagé et VIP côte à côte ; commander et payer', (tester) async {
    final (_, server) = await _open(tester, pollEvery: const Duration(seconds: 1));
    expect(find.text('Où vas-tu ?'), findsOneWidget);
    expect(find.text('Départ : Ma position'), findsOneWidget, reason: 'the pickup is the phone position, no field to fill');
    expect(find.text('carte D-'), findsOneWidget);
    await _type(tester, 'Chercher un lieu', 'Bast');
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    expect(find.text('370 F'), findsOneWidget);
    expect(find.text('4625 F'), findsOneWidget, reason: 'both prices at once');
    expect(find.text('Commander · 370 F'), findsOneWidget);
    expect(find.textContaining('3,7 km'), findsOneWidget);
    expect(find.text('carte DA client:3'), findsOneWidget, reason: 'the trip by road in blue');
    expect(server.rideCalls.where((c) => c.startsWith('quote:')),
        ['quote:shared:1:{latitude: 3.8667, longitude: 11.5167}:{latitude: 3.8935, longitude: 11.509}',
          'quote:vip:1:{latitude: 3.8667, longitude: 11.5167}:{latitude: 3.8935, longitude: 11.509}']);

    await tester.tap(find.text('VIP'));
    await settle(tester);
    expect(find.text('Commander · 4625 F'), findsOneWidget);
    await tester.tap(find.text('Partagé'));
    await tester.tap(find.byTooltip('Plus'));
    await settle(tester);
    expect(find.text('Commander · 740 F'), findsOneWidget, reason: 'two places, priced again by the server');

    server.searchState = 'searching';
    await tester.tap(find.text('Commander · 740 F'));
    await tester.pump();
    await tester.pump();
    expect(find.text('Recherche d’un chauffeur proche…'), findsOneWidget);
    server.searchState = 'matched';
    await tester.pump(const Duration(seconds: 1));
    await tester.pump();
    expect(find.text('Chauffeur trouvé : paie pour confirmer'), findsOneWidget);
    expect(find.text('048213'), findsNothing, reason: 'no code before payment');
    expect(find.text('Paul · Toyota Yaris'), findsNothing, reason: 'no driver card before payment');
    await tester.tap(find.text('Payer 370 F'));
    await settle(tester);
    expect(find.text('Payer la course'), findsOneWidget);
    expect(find.text('Montant : 370 F'), findsOneWidget, reason: 'the amount is the server ride price');
  });

  testWidgets('la destination choisie revient en raccourci ; le départ se change', (tester) async {
    await _open(tester);
    await _type(tester, 'Chercher un lieu', 'Bast');
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    await tester.tap(find.byTooltip('Retour'));
    await settle(tester);
    expect(find.widgetWithIcon(ListTile, Icons.history), findsOneWidget);
    await tester.tap(find.text('Changer'));
    await settle(tester);
    expect(find.text('D’où pars-tu ?'), findsOneWidget);
    await _type(tester, 'Chercher un lieu', 'Poste');
    await tester.tap(find.widgetWithText(ListTile, 'Poste centrale'));
    await settle(tester);
    expect(find.text('Départ : Poste centrale'), findsOneWidget);
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    expect(find.text('Commander · 370 F'), findsOneWidget, reason: 'one touch on a recent place gives the prices');
  });

  testWidgets('sans GPS, le départ est demandé après la destination ; hors zone, le message du serveur est expliqué', (tester) async {
    final (_, server) = await _open(tester, gps: null);
    expect(find.text('Départ : à choisir'), findsOneWidget);
    server.outside = true;
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    expect(find.text('D’où pars-tu ?'), findsOneWidget, reason: 'no pickup yet: asked next');
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    expect(find.textContaining('Yaoundé seulement'), findsOneWidget);
  });

  test('une réponse illisible demande de recharger, pas de vérifier le trajet', () {
    expect(rideMessage(PhotoFailure.invalidResponse), contains('recharge la page'));
  });

  testWidgets('une course en cours est retrouvée : chauffeur sur la carte, prénom, voiture, plaque et code', (tester) async {
    await _open(tester, before: (server) { server.inProgress = true; server.rideState = 'confirmed'; });
    expect(find.text('Ton chauffeur arrive'), findsOneWidget);
    expect(find.text('carte DAC client:3'), findsOneWidget, reason: 'pickup, drop-off, the driver and the trip in blue');
    expect(find.text('Paul · Toyota Yaris'), findsOneWidget);
    expect(find.text('Grise · LT 123 AB'), findsOneWidget);
    expect(find.text('048213'), findsOneWidget);
    expect(find.text('Où vas-tu ?'), findsNothing);
  });

  testWidgets('une course payée peut être annulée avec remboursement, puis une nouvelle commence', (tester) async {
    final (_, server) = await _open(tester);
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    server.searchState = 'matched';
    server.rideState = 'confirmed';
    await tester.tap(find.text('Commander · 370 F'));
    await settle(tester);
    expect(find.text('Ton chauffeur arrive'), findsOneWidget);
    await tester.tap(find.text('Annuler la course'));
    await settle(tester);
    expect(find.text('Course annulée.'), findsOneWidget);
    expect(find.text('370 F · remboursé : 370 F'), findsOneWidget);
    await tester.tap(find.text('Nouvelle course'));
    await settle(tester);
    expect(find.text('Où vas-tu ?'), findsOneWidget);
  });
}
