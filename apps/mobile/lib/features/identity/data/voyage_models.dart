import 'vehicle_photo_models.dart';

/// R11 (K25): intercity voyages. Every amount and seat count comes from the server; the phone only shows them.
const maxSeatPriceXaf = 10000;
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final _pair = RegExp(r'^[0-5]-[0-5]$');
const reservationStates = {'awaiting_payment', 'expired', 'confirmed', 'boarded', 'cancelled_by_client', 'cancelled_by_driver',
  'no_show'};

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

Map<String, int> _counts(Object? value, int min, int max) {
  if (value is! Map<String, dynamic>) throw const FormatException();
  return Map.unmodifiable(value.map((k, v) {
    if (!_pair.hasMatch(k) || v is! int || v < min || v > max) throw const FormatException();
    return MapEntry(k, v);
  }));
}

class VoyageStop {
  const VoyageStop(this.place, this.plannedAt);
  final String place;
  final DateTime plannedAt;
  Map<String, Object?> toJson() => {'place': place, 'plannedAt': '${plannedAt.toUtc().toIso8601String().substring(0, 19)}Z'};
}

class VoyageView {
  const VoyageView(this.voyageId, this.category, this.seats, this.state, this.stops, this.prices, this.availableSeats);
  final String voyageId, category, state;
  final int seats;
  final List<VoyageStop> stops;
  /// "from-to" stop indexes -> seat price in XAF, and seats still free on that section.
  final Map<String, int> prices, availableSeats;

  static VoyageView parse(Object? value) {
    try {
      final m = _exact(value, {'voyageId', 'category', 'seats', 'state', 'stops', 'prices', 'availableSeats', 'createdAt',
        'idempotentReplay'});
      final stops = m['stops'];
      if (!{'taxi', 'private'}.contains(m['category']) || m['seats'] is! int || (m['seats'] as int) < 1 ||
          (m['seats'] as int) > 5 || !{'published', 'cancelled'}.contains(m['state']) || stops is! List ||
          stops.length < 2 || stops.length > 6 || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      _instant(m['createdAt']);
      final parsed = [
        for (final s in stops)
          (() {
            final stop = _exact(s, {'place', 'plannedAt'});
            if (stop['place'] is! String || (stop['place'] as String).isEmpty) throw const FormatException();
            return VoyageStop(stop['place'] as String, _instant(stop['plannedAt']));
          })(),
      ];
      final prices = _counts(m['prices'], 1, maxSeatPriceXaf);
      final available = _counts(m['availableSeats'], 0, m['seats'] as int);
      if (prices.length != stops.length * (stops.length - 1) ~/ 2 || available.keys.toSet().difference(prices.keys.toSet()).isNotEmpty) {
        throw const FormatException();
      }
      return VoyageView(_id(m['voyageId']), m['category'] as String, m['seats'] as int, m['state'] as String,
          List.unmodifiable(parsed), prices, available);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }

  static List<VoyageView> parseList(Object? value) {
    if (value is! List || value.length > 50) throw const PhotoProblem(PhotoFailure.invalidResponse);
    return List.unmodifiable(value.map(parse));
  }
}

class ReservationView {
  const ReservationView(this.reservationId, this.voyageId, this.fromIndex, this.toIndex, this.seats, this.totalXaf, this.state,
      this.boardingAt, this.refundXaf, this.feePercent, this.boardingCode);
  final String reservationId, voyageId, state;
  final int fromIndex, toIndex, seats, totalXaf;
  final DateTime boardingAt;
  final int? refundXaf, feePercent;
  /// Six digits to give the driver at boarding; shown only while the reservation can still board.
  final String? boardingCode;

  static ReservationView parse(Object? value) {
    try {
      final m = _exact(value, {'reservationId', 'voyageId', 'fromIndex', 'toIndex', 'seats', 'seatPriceXaf', 'totalXaf', 'state',
        'holdExpiresAt', 'boardingAt', 'refundXaf', 'driverXaf', 'wakaXaf', 'feePercent', 'boardingCode', 'idempotentReplay'});
      final from = m['fromIndex'], to = m['toIndex'], seats = m['seats'], price = m['seatPriceXaf'], total = m['totalXaf'];
      if (from is! int || to is! int || from < 0 || to <= from || to > 5 || seats is! int || seats < 1 || seats > 5 ||
          price is! int || price < 1 || price > maxSeatPriceXaf || total != price * seats ||
          !reservationStates.contains(m['state']) || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      final refund = m['refundXaf'], driver = m['driverXaf'], waka = m['wakaXaf'], fee = m['feePercent'];
      final settled = refund != null;
      if (settled && (refund is! int || driver is! int || waka is! int || fee is! int || refund + driver + waka != total ||
          !{0, 15, 30}.contains(fee))) {
        throw const FormatException();
      }
      final code = m['boardingCode'];
      if (code != null && (code is! String || !RegExp(r'^[0-9]{6}$').hasMatch(code))) throw const FormatException();
      _instant(m['holdExpiresAt']);
      return ReservationView(_id(m['reservationId']), _id(m['voyageId']), from, to, seats, total, m['state'] as String,
          _instant(m['boardingAt']), refund as int?, fee as int?, code as String?);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }

  static List<ReservationView> parseList(Object? value) {
    if (value is! List || value.length > 100) throw const PhotoProblem(PhotoFailure.invalidResponse);
    return List.unmodifiable(value.map(parse));
  }
}

/// What the driver sees of a passenger (K27): the section and seats, never who it is nor the code.
class PassengerView {
  const PassengerView(this.reservationId, this.fromIndex, this.toIndex, this.seats, this.state, this.driverXaf);
  final String reservationId, state;
  final int fromIndex, toIndex, seats;
  final int? driverXaf;

  static PassengerView parse(Object? value) {
    try {
      final m = _exact(value, {'reservationId', 'fromIndex', 'toIndex', 'seats', 'state', 'boardingAt', 'driverXaf'});
      final from = m['fromIndex'], to = m['toIndex'], seats = m['seats'], driver = m['driverXaf'];
      if (from is! int || to is! int || from < 0 || to <= from || to > 5 || seats is! int || seats < 1 || seats > 5 ||
          !{'awaiting_payment', 'confirmed', 'boarded', 'no_show'}.contains(m['state']) || (driver != null && driver is! int) ||
          ({'boarded', 'no_show'}.contains(m['state']) != (driver != null))) {
        throw const FormatException();
      }
      _instant(m['boardingAt']);
      return PassengerView(_id(m['reservationId']), from, to, seats, m['state'] as String, driver as int?);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }

  static List<PassengerView> parseList(Object? value) {
    if (value is! List || value.length > 100) throw const PhotoProblem(PhotoFailure.invalidResponse);
    return List.unmodifiable(value.map(parse));
  }
}
const incidentCategories = {
  'no_show_dispute': 'Absence contestée',
  'safety': 'Sécurité',
  'behaviour': 'Comportement',
  'payment': 'Paiement',
  'vehicle': 'Véhicule',
  'other': 'Autre',
};
const incidentDecisions = {
  'no_action': 'Sans suite',
  'refund_review': 'Remboursement à étudier',
  'driver_warning': 'Avertissement au chauffeur',
  'account_review': 'Revue du compte',
};

class IncidentMessage {
  const IncidentMessage(this.kind, this.authorRole, this.decision, this.reasonCode, this.text, this.at);
  final String kind, authorRole;
  final String? decision, reasonCode, text;
  final DateTime at;
}

/// R12 incident (K30), seen by one of its parties: roles only, never the other party's identity.
class IncidentView {
  const IncidentView(this.incidentId, this.reservationId, this.category, this.state, this.version, this.viewerRole, this.messages);
  final String incidentId, reservationId, category, state, viewerRole;
  final int version;
  final List<IncidentMessage> messages;

  static IncidentView parse(Object? value) {
    try {
      final m = _exact(value, {'incidentId', 'reservationId', 'category', 'state', 'version', 'viewerRole', 'createdAt', 'messages',
        'idempotentReplay'});
      final messages = m['messages'];
      if (!incidentCategories.containsKey(m['category']) || !{'open', 'decided', 'contested'}.contains(m['state']) ||
          m['version'] is! int || !{'client', 'driver'}.contains(m['viewerRole']) || messages is! List || messages.isEmpty ||
          messages.length > 200 || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      _instant(m['createdAt']);
      return IncidentView(_id(m['incidentId']), _id(m['reservationId']), m['category'] as String, m['state'] as String,
          m['version'] as int, m['viewerRole'] as String, List.unmodifiable(messages.map((item) {
            final x = _exact(item, {'kind', 'authorRole', 'decision', 'reasonCode', 'text', 'at'});
            if (!{'report', 'reply', 'contest', 'decision'}.contains(x['kind']) || !{'client', 'driver', 'assistance'}.contains(x['authorRole']) ||
                (x['decision'] != null && !incidentDecisions.containsKey(x['decision'])) || (x['text'] != null && x['text'] is! String) ||
                (x['reasonCode'] != null && x['reasonCode'] is! String)) {
              throw const FormatException();
            }
            return IncidentMessage(x['kind'] as String, x['authorRole'] as String, x['decision'] as String?, x['reasonCode'] as String?,
                x['text'] as String?, _instant(x['at']));
          })));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }

  static List<IncidentView> parseList(Object? value) {
    if (value is! List || value.length > 50) throw const PhotoProblem(PhotoFailure.invalidResponse);
    return List.unmodifiable(value.map(parse));
  }
}