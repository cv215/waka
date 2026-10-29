import 'vehicle_photo_models.dart';

final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final _cursor = RegExp(r'^[0-9]{1,19}_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
const privacyKinds = {'access', 'deletion'};
const activityTypes = {'driver_application', 'privacy_request', 'quote'};

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

/// A received request about one's own data. "received" never means the data were deleted.
class PrivacyRequestView {
  const PrivacyRequestView(this.requestId, this.kind, this.state, this.receivedAt, this.created);
  final String requestId, kind, state;
  final DateTime receivedAt;
  /// False when the server returned an already open request of the same kind.
  final bool created;

  static PrivacyRequestView parse(Object? value) {
    try {
      final m = _exact(value, {'requestId', 'kind', 'state', 'receivedAt', 'created'});
      if (!privacyKinds.contains(m['kind']) || m['state'] != 'received' || m['created'] is! bool) {
        throw const FormatException();
      }
      return PrivacyRequestView(_id(m['requestId']), m['kind'] as String, 'received', _instant(m['receivedAt']),
          m['created'] as bool);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

class ActivityItem {
  const ActivityItem(this.objectType, this.objectId, this.state, this.at);
  final String objectType, objectId, state;
  final DateTime at;
}

class ActivityPage {
  const ActivityPage(this.items, this.nextCursor);
  final List<ActivityItem> items;
  final String? nextCursor;

  static ActivityPage parse(Object? value) {
    try {
      final m = _exact(value, {'items', 'nextCursor'});
      final items = m['items'], next = m['nextCursor'];
      if (items is! List || items.length > 50 || !(next == null || next is String && _cursor.hasMatch(next))) {
        throw const FormatException();
      }
      return ActivityPage(List.unmodifiable(items.map((item) {
        final i = _exact(item, {'objectType', 'objectId', 'state', 'at'});
        if (!activityTypes.contains(i['objectType']) || i['state'] is! String ||
            !RegExp(r'^[a-z_]{3,32}$').hasMatch(i['state'] as String)) {
          throw const FormatException();
        }
        return ActivityItem(i['objectType'] as String, _id(i['objectId']), i['state'] as String, _instant(i['at']));
      })), next as String?);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}
