import 'vehicle_photo_models.dart';

/// R13 (K31, K35): city rides. Distances, prices and states come from the server; the phone only shows them.
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final _placeId = RegExp(r'^[A-Za-z0-9_-]{10,300}$');
const searchStates = {'searching', 'matched', 'cancelled', 'expired'};
const rideStates = {'awaiting_payment', 'confirmed', 'driver_arrived', 'boarded', 'finished', 'cancelled_by_client',
  'cancelled_by_driver', 'client_absent', 'expired'};
/// A ride still in progress: the client can follow it, pay it (awaiting_payment) or cancel it before boarding.
const activeRideStates = {'awaiting_payment', 'confirmed', 'driver_arrived', 'boarded'};

Map<String, dynamic> _exact(Object? value, Set<String> fields) {
  if (value is! Map<String, dynamic> || value.length != fields.length || !value.keys.every(fields.contains)) {
    throw const FormatException();
  }
  return value;
}

String _id(Object? value) {
  if (value is! String || !_uuid.hasMatch(value)) throw const FormatException();
  return value;
}

DateTime _instant(Object? value) {
  if (value is! String || !value.endsWith('Z')) throw const FormatException();
  return DateTime.parse(value).toUtc();
}

DateTime? _instantOrNull(Object? value) => value == null ? null : _instant(value);

int _amount(Object? value, {int min = 0, int max = 1000000}) {
  if (value is! int || value < min || value > max) throw const FormatException();
  return value;
}

double _degrees(Object? value, double limit) {
  if (value is! num || value.abs() > limit) throw const FormatException();
  return value.toDouble();
}

/// A point on the map, WGS84 degrees with at most six decimals when sent.
class RidePoint {
  const RidePoint(this.latitude, this.longitude);
  final double latitude, longitude;
  static RidePoint parse(Object? value) {
    final m = _exact(value, {'latitude', 'longitude'});
    return RidePoint(_degrees(m['latitude'], 90), _degrees(m['longitude'], 180));
  }
  Map<String, Object?> toJson() => {
    'latitude': double.parse(latitude.toStringAsFixed(6)),
    'longitude': double.parse(longitude.toStringAsFixed(6)),
  };
  @override
  bool operator ==(Object other) => other is RidePoint && other.latitude == latitude && other.longitude == longitude;
  @override
  int get hashCode => Object.hash(latitude, longitude);
  @override
  String toString() => 'RidePoint[REDACTED]';
}

/// One suggestion while typing a place (K35).
class PlaceSuggestion {
  const PlaceSuggestion(this.placeId, this.mainText, this.secondaryText);
  final String placeId, mainText, secondaryText;
  static List<PlaceSuggestion> parseList(Object? value) {
    try {
      if (value is! List || value.length > 5) throw const FormatException();
      return List.unmodifiable(value.map((item) {
        final m = _exact(item, {'placeId', 'mainText', 'secondaryText'});
        final id = m['placeId'], main = m['mainText'], secondary = m['secondaryText'];
        if (id is! String || !_placeId.hasMatch(id) || main is! String || main.isEmpty || main.length > 120 ||
            secondary is! String || secondary.length > 120) {
          throw const FormatException();
        }
        return PlaceSuggestion(id, main, secondary);
      }));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// The point of a chosen place.
RidePoint parsePlaceLocation(Object? value, String placeId) {
  try {
    final m = _exact(value, {'placeId', 'latitude', 'longitude'});
    if (m['placeId'] != placeId) throw const FormatException();
    return RidePoint(_degrees(m['latitude'], 90), _degrees(m['longitude'], 180));
  } on FormatException {
    throw const PhotoProblem(PhotoFailure.invalidResponse);
  }
}

class RideQuoteView {
  const RideQuoteView(this.quoteId, this.mode, this.passengerCount, this.distanceMetres, this.placePriceXaf, this.totalXaf,
      this.expiresAt);
  final String quoteId, mode;
  final int passengerCount, distanceMetres, placePriceXaf, totalXaf;
  final DateTime expiresAt;
  static RideQuoteView parse(Object? value) {
    try {
      final m = _exact(value, {'quoteId', 'mode', 'passengerCount', 'distanceMetres', 'fareVersion', 'bandCode', 'placePriceXaf',
        'totalXaf', 'currency', 'createdAt', 'expiresAt', 'idempotentReplay'});
      if (!{'shared', 'vip'}.contains(m['mode']) || m['currency'] != 'XAF' || m['fareVersion'] is! String ||
          m['bandCode'] is! String || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      _instant(m['createdAt']);
      return RideQuoteView(_id(m['quoteId']), m['mode'] as String, _amount(m['passengerCount'], min: 1, max: 5),
          _amount(m['distanceMetres'], max: 200000), _amount(m['placePriceXaf'], min: 1), _amount(m['totalXaf'], min: 1),
          _instant(m['expiresAt']));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

class CityRideView {
  const CityRideView(this.reservationId, this.mode, this.seats, this.priceXaf, this.state, this.holdExpiresAt, this.arrivedAt,
      this.boardingCode, this.refundXaf, this.pickup, this.dropoff);
  final String reservationId, mode, state;
  final int seats, priceXaf;
  final DateTime? holdExpiresAt, arrivedAt;
  final String? boardingCode;
  final int? refundXaf;
  final RidePoint pickup, dropoff;
  bool get active => activeRideStates.contains(state);
  static CityRideView parse(Object? value) {
    try {
      final m = _exact(value, {'reservationId', 'mode', 'seats', 'priceXaf', 'driverXaf', 'state', 'holdExpiresAt', 'arrivedAt',
        'viewerRole', 'boardingCode', 'refundXaf', 'pickup', 'dropoff'});
      final code = m['boardingCode'], refund = m['refundXaf'];
      if (!{'shared', 'vip'}.contains(m['mode']) || !rideStates.contains(m['state']) || m['viewerRole'] != 'client' ||
          (code != null && (code is! String || !RegExp(r'^[0-9]{4,8}$').hasMatch(code)))) {
        throw const FormatException();
      }
      _amount(m['driverXaf']);
      return CityRideView(_id(m['reservationId']), m['mode'] as String, _amount(m['seats'], min: 1, max: 5),
          _amount(m['priceXaf'], min: 1), m['state'] as String, _instantOrNull(m['holdExpiresAt']), _instantOrNull(m['arrivedAt']),
          code as String?, refund == null ? null : _amount(refund), RidePoint.parse(m['pickup']), RidePoint.parse(m['dropoff']));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

class RideSearchView {
  const RideSearchView(this.searchId, this.state, this.expiresAt, this.proposalPending, this.ride);
  final String searchId, state;
  final DateTime expiresAt;
  final bool proposalPending;
  final CityRideView? ride;
  static RideSearchView parse(Object? value) {
    try {
      final m = _exact(value, {'searchId', 'state', 'expiresAt', 'proposalPending', 'ride', 'idempotentReplay'});
      if (!searchStates.contains(m['state']) || m['proposalPending'] is! bool || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      final ride = m['ride'] == null ? null : CityRideView.parse(m['ride']);
      if ((m['state'] == 'matched') != (ride != null)) throw const FormatException();
      return RideSearchView(_id(m['searchId']), m['state'] as String, _instant(m['expiresAt']), m['proposalPending'] as bool, ride);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}
