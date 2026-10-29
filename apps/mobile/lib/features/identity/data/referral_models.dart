import 'vehicle_photo_models.dart';

/// Same alphabet as the server: no 0/O or 1/I.
final referralCodePattern = RegExp(r'^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8}$');

Map<String, dynamic> _exact(Object? value, Set<String> fields) {
  if (value is! Map<String, dynamic> || value.length != fields.length || !value.keys.every(fields.contains)) {
    throw const FormatException();
  }
  return value;
}

DateTime? _instant(Object? value) {
  if (value == null) return null;
  if (value is! String || !value.endsWith('Z')) throw const FormatException();
  return DateTime.parse(value).toUtc();
}

class ReferralView {
  const ReferralView(this.code, this.attached, this.attachedAt);
  final String code;
  final bool attached;
  final DateTime? attachedAt;

  static ReferralView parse(Object? value) {
    try {
      final m = _exact(value, {'referralCode', 'referrerAttached', 'attachedAt'});
      final code = m['referralCode'], attached = m['referrerAttached'], at = _instant(m['attachedAt']);
      if (code is! String || !referralCodePattern.hasMatch(code) || attached is! bool || attached != (at != null)) {
        throw const FormatException();
      }
      return ReferralView(code, attached, at);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// Answer to an attachment; [created] is false when the same attachment was found again.
class LinkageView {
  const LinkageView(this.attachedAt, this.created);
  final DateTime attachedAt;
  final bool created;

  static LinkageView parse(Object? value) {
    try {
      final m = _exact(value, {'attachedAt', 'created'});
      final at = _instant(m['attachedAt']);
      if (at == null || m['created'] is! bool) throw const FormatException();
      return LinkageView(at, m['created'] as bool);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}
