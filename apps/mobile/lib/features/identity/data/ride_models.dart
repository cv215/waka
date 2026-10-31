import '../../../core/geo/polyline.dart';
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

/// The client's trip shape (drawn in blue), display only: an unreadable one is dropped, never the price or the ride.
List<RidePoint>? _tripShape(Object? value) {
  if (value == null) return null;
  if (value is! String) throw const FormatException();
  try {
    return List.unmodifiable(decodePolyline(value).map((p) => RidePoint(p.$1, p.$2)));
  } on FormatException {
    return null;
  }
}

class RideQuoteView {
  const RideQuoteView(this.quoteId, this.mode, this.passengerCount, this.distanceMetres, this.placePriceXaf, this.totalXaf,
      this.expiresAt, [this.route]);
  final String quoteId, mode;
  final int passengerCount, distanceMetres, placePriceXaf, totalXaf;
  final DateTime expiresAt;
  /// The trip by road, from the same maps call as the price, or null.
  final List<RidePoint>? route;
  static RideQuoteView parse(Object? value) {
    try {
      final m = _exact(value, {'quoteId', 'mode', 'passengerCount', 'distanceMetres', 'fareVersion', 'bandCode', 'placePriceXaf',
        'totalXaf', 'currency', 'createdAt', 'expiresAt', 'idempotentReplay', 'route'});
      if (!{'shared', 'vip'}.contains(m['mode']) || m['currency'] != 'XAF' || m['fareVersion'] is! String ||
          m['bandCode'] is! String || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      _instant(m['createdAt']);
      return RideQuoteView(_id(m['quoteId']), m['mode'] as String, _amount(m['passengerCount'], min: 1, max: 5),
          _amount(m['distanceMetres'], max: 200000), _amount(m['placePriceXaf'], min: 1), _amount(m['totalXaf'], min: 1),
          _instant(m['expiresAt']), _tripShape(m['route']));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// Who the paying client looks for: the driver's first name, the car, its colour and plate (each may be missing).
class DriverCardView {
  const DriverCardView(this.firstName, this.vehicle, this.color, this.plate);
  final String? firstName, vehicle, color, plate;
  static DriverCardView parse(Object? value) {
    final m = _exact(value, {'firstName', 'vehicle', 'color', 'plate'});
    String? text(Object? v) {
      if (v == null) return null;
      if (v is! String || v.length > 80) throw const FormatException();
      return v.trim().isEmpty ? null : v.trim();
    }
    return DriverCardView(text(m['firstName']), text(m['vehicle']), text(m['color']), text(m['plate']));
  }
}

class CityRideView {
  const CityRideView(this.reservationId, this.mode, this.seats, this.priceXaf, this.state, this.holdExpiresAt, this.arrivedAt,
      this.boardingCode, this.refundXaf, this.pickup, this.dropoff, {this.driverXaf = 0, this.viewerRole = 'client',
      this.driverPosition, this.driverPositionAt, this.route, this.driver, this.clientFirstName});
  final String reservationId, mode, state;
  /// The trip by road kept from the quote (blue), or null.
  final List<RidePoint>? route;
  /// For the client, once paid: who to look for.
  final DriverCardView? driver;
  /// For the driver, once paid: the client's first name.
  final String? clientFirstName;
  /// The driver coming, for the client only, when the server has a position less than a minute old.
  final RidePoint? driverPosition;
  final DateTime? driverPositionAt;
  /// 'client' (sees the boarding code) or 'driver' (sees his share, never the code).
  final String viewerRole;
  final int seats, priceXaf, driverXaf;
  final DateTime? holdExpiresAt, arrivedAt;
  final String? boardingCode;
  final int? refundXaf;
  final RidePoint pickup, dropoff;
  bool get active => activeRideStates.contains(state);
  static CityRideView parse(Object? value) {
    try {
      final m = _exact(value, {'reservationId', 'mode', 'seats', 'priceXaf', 'driverXaf', 'state', 'holdExpiresAt', 'arrivedAt',
        'viewerRole', 'boardingCode', 'refundXaf', 'pickup', 'dropoff', 'driverPosition', 'driverPositionAt', 'route', 'driver', 'clientFirstName'});
      final code = m['boardingCode'], refund = m['refundXaf'], role = m['viewerRole'];
      final seen = m['driverPosition'], seenAt = m['driverPositionAt'];
      if (!{'shared', 'vip'}.contains(m['mode']) || !rideStates.contains(m['state']) || !{'client', 'driver'}.contains(role) ||
          (code != null && (role != 'client' || code is! String || !RegExp(r'^[0-9]{4,8}$').hasMatch(code))) ||
          ((seen == null) != (seenAt == null)) || (seen != null && role != 'client') || (m['driver'] != null && role != 'client') ||
          (m['clientFirstName'] != null && (role != 'driver' || m['clientFirstName'] is! String || (m['clientFirstName'] as String).length > 80))) {
        throw const FormatException();
      }
      return CityRideView(_id(m['reservationId']), m['mode'] as String, _amount(m['seats'], min: 1, max: 5),
          _amount(m['priceXaf'], min: 1), m['state'] as String, _instantOrNull(m['holdExpiresAt']), _instantOrNull(m['arrivedAt']),
          code as String?, refund == null ? null : _amount(refund), RidePoint.parse(m['pickup']), RidePoint.parse(m['dropoff']),
          driverXaf: _amount(m['driverXaf']), viewerRole: role as String,
          driverPosition: seen == null ? null : RidePoint.parse(seen), driverPositionAt: _instantOrNull(seenAt),
          route: _tripShape(m['route']), driver: m['driver'] == null ? null : DriverCardView.parse(m['driver']),
          clientFirstName: m['clientFirstName'] as String?);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// A ride offered to this driver (K31): 30 seconds to answer.
class DriverProposalView {
  const DriverProposalView(this.proposalId, this.mode, this.seats, this.priceXaf, this.driverXaf, this.pickupSeconds,
      this.expiresAt, this.pickup, this.dropoff, {this.detourSeconds = 0, this.route});
  final String proposalId, mode;
  final int seats, priceXaf, driverXaf, pickupSeconds;
  /// R15: the detour this client adds to the driver's direction (0 without a direction), shown, not capped.
  final int detourSeconds;
  /// The client's trip by road (blue on the driver's map), or null.
  final List<RidePoint>? route;
  final DateTime expiresAt;
  final RidePoint pickup, dropoff;
  static DriverProposalView parse(Object? value) {
    final m = _exact(value, {'proposalId', 'mode', 'seats', 'priceXaf', 'driverXaf', 'pickupSeconds', 'expiresAt', 'pickup', 'dropoff',
      'detourSeconds', 'route'});
    if (!{'shared', 'vip'}.contains(m['mode'])) throw const FormatException();
    final price = _amount(m['priceXaf'], min: 1), share = _amount(m['driverXaf']);
    if (share > price) throw const FormatException();
    return DriverProposalView(_id(m['proposalId']), m['mode'] as String, _amount(m['seats'], min: 1, max: 5), price, share,
        _amount(m['pickupSeconds'], max: 600), _instant(m['expiresAt']), RidePoint.parse(m['pickup']), RidePoint.parse(m['dropoff']),
        detourSeconds: _amount(m['detourSeconds'], max: 7200), route: _tripShape(m['route']));
  }
  static List<DriverProposalView> parseList(Object? value) {
    try {
      if (value is! List || value.length > 10) throw const FormatException();
      return List.unmodifiable(value.map(parse));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// The three route shapes of a proposal (display only): the driver's own route to his direction (null without one),
/// the client's trip, and the driver's route with this client.
class ProposalPathsView {
  const ProposalPathsView(this.normal, this.client, this.combined);
  final List<RidePoint>? normal;
  final List<RidePoint>? client, combined;
  static ProposalPathsView parse(Object? value) {
    try {
      final m = _exact(value, {'normal', 'client', 'combined'});
      List<RidePoint>? path(Object? v) {
        if (v == null) return null;
        if (v is! String) throw const FormatException();
        return List.unmodifiable(decodePolyline(v).map((p) => RidePoint(p.$1, p.$2)));
      }
      return ProposalPathsView(path(m['normal']), path(m['client']), path(m['combined']));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// One stop of the driver's tour: a client's pickup or drop-off, in the order the server chose.
class RideStop {
  const RideStop(this.kind, this.reservationId, this.point);
  final String kind, reservationId;
  final RidePoint point;
  bool get pickup => kind == 'pickup';
}

/// The driver's presence as the server recorded it; the position time is the server's.
class DriverPresenceView {
  const DriverPresenceView(this.online, this.positionAt, [this.destination, this.route, this.plan, this.stops = const []]);
  final bool online;
  final DateTime? positionAt;
  /// R15: the direction the server recorded, or null.
  final RidePoint? destination;
  /// The driver's own road route to his direction (drawn in green), or null.
  final List<RidePoint>? route;
  /// His tour through the clients he carries, then his direction (drawn in yellow), or null.
  final List<RidePoint>? plan;
  final List<RideStop> stops;
  static DriverPresenceView parse(Object? value) {
    try {
      final m = _exact(value, {'online', 'positionAt', 'destination', 'route', 'plan', 'stops'});
      final stops = m['stops'];
      if (m['online'] is! bool || stops is! List || stops.length > 10) throw const FormatException();
      return DriverPresenceView(m['online'] as bool, _instantOrNull(m['positionAt']),
          m['destination'] == null ? null : RidePoint.parse(m['destination']), _tripShape(m['route']), _tripShape(m['plan']),
          List.unmodifiable(stops.map((v) {
            final s = _exact(v, {'kind', 'reservationId', 'point'});
            if (!{'pickup', 'dropoff'}.contains(s['kind'])) throw const FormatException();
            return RideStop(s['kind'] as String, _id(s['reservationId']), RidePoint.parse(s['point']));
          })));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// One of the client's ended city rides (history): when, how much, the outcome, any refund, and the drop-off point
/// (named on the phone from its recent places, never sent anywhere else).
class PastRideView {
  const PastRideView(this.reservationId, this.mode, this.seats, this.priceXaf, this.refundXaf, this.state, this.createdAt, this.dropoff);
  final String reservationId, mode, state;
  final int seats, priceXaf;
  final int? refundXaf;
  final DateTime createdAt;
  final RidePoint? dropoff;
  static List<PastRideView> parseList(Object? value) {
    try {
      if (value is! List || value.length > 20) throw const FormatException();
      return List.unmodifiable(value.map((v) {
        final m = _exact(v, {'reservationId', 'mode', 'seats', 'priceXaf', 'refundXaf', 'state', 'createdAt', 'dropoff'});
        if (!{'shared', 'vip'}.contains(m['mode']) || !rideStates.contains(m['state']) || activeRideStates.contains(m['state'])) {
          throw const FormatException();
        }
        final refund = m['refundXaf'];
        return PastRideView(_id(m['reservationId']), m['mode'] as String, _amount(m['seats'], min: 1, max: 5),
            _amount(m['priceXaf'], min: 1), refund == null ? null : _amount(refund), m['state'] as String, _instant(m['createdAt']),
            m['dropoff'] == null ? null : RidePoint.parse(m['dropoff']));
      }));
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
