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

/// A stand-in server answering like K31 and K35; the payments come from [PayFixture].
class RideFixture extends PayFixture implements RideGateway {
  RideFixture(super.session);
  final rideCalls = <String>[];
  String rideState = 'awaiting_payment';
  String searchState = 'searching';
  bool outside = false;

  Map<String, Object?> rideJson() => {
    'reservationId': _ride, 'mode': 'shared', 'seats': 1, 'priceXaf': 370, 'driverXaf': 333, 'state': rideState,
    'holdExpiresAt': iso(departure), 'arrivedAt': null, 'viewerRole': 'client',
    'boardingCode': rideState == 'awaiting_payment' ? null : '048213', 'refundXaf': rideState == 'cancelled_by_client' ? 370 : null,
    'pickup': {'latitude': 3.8667, 'longitude': 11.5167}, 'dropoff': {'latitude': 3.8935, 'longitude': 11.509},
  };

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
      'fareVersion': 'launch-v1', 'bandCode': 'B05', 'placePriceXaf': 370, 'totalXaf': 370 * people, 'currency': 'XAF',
      'createdAt': iso(departure), 'expiresAt': iso(departure), 'idempotentReplay': false});
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

Widget _fakeMap(BuildContext context, RidePoint? origin, RidePoint? destination, void Function(RidePoint) onTap) =>
    GestureDetector(
      key: const Key('carte'),
      onTap: () => onTap(const RidePoint(3.86, 11.52)),
      child: ColoredBox(color: Colors.green, child: Text('carte ${origin != null ? 'D' : '-'}${destination != null ? 'A' : '-'}')),
    );

Future<(LoginFlow, RideFixture)> _open(WidgetTester tester) async {
  tester.view.physicalSize = const Size(900, 2600);
  tester.view.devicePixelRatio = 1.0;
  addTearDown(tester.view.reset);
  final (login, gateway) = (await tester.runAsync(() async {
    final session = fixture.grant();
    final store = fixture.MemoryStore();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final gateway = RideFixture(session);
    final login = LoginFlow(gateway, store);
    await login.initialize();
    return (login, gateway);
  }))!;
  await tester.pumpWidget(MaterialApp(home: CityRidePage(login: login, mapBuilder: _fakeMap, pollEvery: null,
      typingDelay: Duration.zero)));
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
    expect(() => RideSearchView.parse({'searchId': _search, 'state': 'matched', 'expiresAt': '2026-10-30T10:00:00Z',
      'proposalPending': false, 'ride': null, 'idempotentReplay': false}), throwsA(isA<PhotoProblem>()));
    expect(const RidePoint(3.86666666, 11.5).toJson(), {'latitude': 3.866667, 'longitude': 11.5});
  });

  testWidgets('chercher le départ et l’arrivée, voir le prix du serveur, commander, payer et recevoir le code', (tester) async {
    final (_, server) = await _open(tester);
    await _type(tester, 'Départ', 'Poste');
    await tester.tap(find.widgetWithText(ListTile, 'Poste centrale'));
    await settle(tester);
    expect(find.text('carte D-'), findsOneWidget);
    await _type(tester, 'Arrivée', 'Bast');
    await tester.tap(find.widgetWithText(ListTile, 'Bastos'));
    await settle(tester);
    expect(find.text('carte DA'), findsOneWidget);
    await tester.tap(find.text('Voir le prix'));
    await settle(tester);
    expect(find.text('370 F'), findsOneWidget);
    expect(find.textContaining('3,7 km par la route'), findsOneWidget);
    expect(server.rideCalls, contains('quote:shared:1:{latitude: 3.8667, longitude: 11.5167}:{latitude: 3.8935, longitude: 11.509}'));

    server.searchState = 'searching';
    await tester.tap(find.text('Commander'));
    // The search spinner never stops: pump instead of waiting for the screen to settle.
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump();
    expect(find.text('Recherche d’un chauffeur proche…'), findsOneWidget);
    server.searchState = 'matched';
    await tester.tap(find.text('Actualiser'));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 20)));
    await tester.pump();
    await settle(tester);
    expect(find.text('Chauffeur trouvé : paie la course pour la confirmer.'), findsOneWidget);
    expect(find.text('048213'), findsNothing, reason: 'no code before payment');

    await tester.tap(find.text('Payer 370 F'));
    await settle(tester);
    expect(find.text('Payer la course'), findsOneWidget);
    expect(find.text('Montant : 370 F'), findsOneWidget, reason: 'the amount is the server ride price');
  });

  testWidgets('un point choisi sur la carte, hors zone : le message du serveur est expliqué', (tester) async {
    final (_, server) = await _open(tester);
    server.outside = true;
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    expect(find.text('Point choisi sur la carte'), findsNWidgets(2));
    await tester.tap(find.text('Voir le prix'));
    await settle(tester);
    expect(find.textContaining('Yaoundé seulement'), findsOneWidget);
  });

  testWidgets('une course payée montre le code et peut être annulée avec remboursement', (tester) async {
    final (_, server) = await _open(tester);
    await tester.tap(find.byKey(const Key('carte')));
    await tester.tap(find.byKey(const Key('carte')));
    await settle(tester);
    await tester.tap(find.text('Voir le prix'));
    await settle(tester);
    server.searchState = 'matched';
    server.rideState = 'confirmed';
    await tester.tap(find.text('Commander'));
    await settle(tester);
    expect(find.text('Course payée : le chauffeur arrive.'), findsOneWidget);
    expect(find.text('048213'), findsOneWidget);
    await tester.tap(find.text('Annuler la course (remboursée)'));
    await settle(tester);
    expect(find.text('Course annulée.'), findsOneWidget);
    expect(find.text('Remboursé : 370 F'), findsOneWidget);
    expect(find.text('Nouvelle course'), findsOneWidget);
  });
}
