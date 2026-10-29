/// Strict readings of the administration responses. Anything unexpected is refused, never guessed.
library;

final uuidPattern = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final tokenPattern = RegExp(r'^[A-Za-z0-9_-]{43}$');
final reasonPattern = RegExp(r'^[A-Z][A-Z0-9_]{2,47}$');
const photoViews = ['PH01', 'PH02', 'PH03', 'PH04', 'PH05', 'PH06'];
const photoLabels = {
  'PH01': 'Avant',
  'PH02': 'Arrière avec plaque lisible',
  'PH03': 'Côté gauche',
  'PH04': 'Côté droit',
  'PH05': 'Habitacle avant',
  'PH06': 'Habitacle arrière',
};

class ResponseShapeError implements Exception {
  const ResponseShapeError();
  @override
  String toString() => 'ResponseShapeError';
}

Map<String, dynamic> exact(Object? value, Set<String> fields) {
  if (value is! Map<String, dynamic> || value.length != fields.length || !value.keys.every(fields.contains)) {
    throw const ResponseShapeError();
  }
  return value;
}

String uuid(Object? value) {
  if (value is! String || !uuidPattern.hasMatch(value)) throw const ResponseShapeError();
  return value;
}

DateTime instant(Object? value) {
  if (value is! String || !value.endsWith('Z')) throw const ResponseShapeError();
  final parsed = DateTime.tryParse(value);
  if (parsed == null) throw const ResponseShapeError();
  return parsed.toUtc();
}

DateTime? day(Object? value) {
  if (value == null) return null;
  if (value is! String || !RegExp(r'^\d{4}-\d{2}-\d{2}$').hasMatch(value)) throw const ResponseShapeError();
  return DateTime.parse('${value}T00:00:00Z');
}

class Challenge {
  const Challenge(this.id, this.expiresAt, this.hint);
  final String id, hint;
  final DateTime expiresAt;
  factory Challenge.parse(Object? value) {
    final m = exact(value, {'challengeId', 'submission', 'expiresAt', 'destinationHint'});
    if (!{'SENDING', 'ACCEPTED', 'REJECTED', 'UNKNOWN'}.contains(m['submission']) || m['destinationHint'] is! String) {
      throw const ResponseShapeError();
    }
    return Challenge(uuid(m['challengeId']), instant(m['expiresAt']), m['destinationHint'] as String);
  }
}

/// The client session: necessary, never sufficient, for administration.
class ClientSession {
  const ClientSession(this.sessionId, this.accountId, this.token);
  final String sessionId, accountId, token;
  factory ClientSession.parse(Object? value) {
    final m = exact(value, {'sessionId', 'accountId', 'tokenType', 'token', 'absoluteExpiresAt', 'idleExpiresAt'});
    if (m['tokenType'] != 'Bearer' || m['token'] is! String || !tokenPattern.hasMatch(m['token'] as String)) {
      throw const ResponseShapeError();
    }
    instant(m['absoluteExpiresAt']);
    instant(m['idleExpiresAt']);
    return ClientSession(uuid(m['sessionId']), uuid(m['accountId']), m['token'] as String);
  }
  @override
  String toString() => 'ClientSession[REDACTED]';
}

class Enrollment {
  const Enrollment(this.secret, this.uri);
  final String secret, uri;
  factory Enrollment.parse(Object? value) {
    final m = exact(value, {'secret', 'otpauthUri'});
    if (m['secret'] is! String || !RegExp(r'^[A-Z2-7]{16,64}$').hasMatch(m['secret'] as String) ||
        m['otpauthUri'] is! String || !(m['otpauthUri'] as String).startsWith('otpauth://totp/')) {
      throw const ResponseShapeError();
    }
    return Enrollment(m['secret'] as String, m['otpauthUri'] as String);
  }
  @override
  String toString() => 'Enrollment[REDACTED]';
}

const adminFunctions = {'ADMISSION', 'ASSISTANCE', 'FINANCE', 'AUDIT', 'HABILITATIONS'};

class AdminSession {
  const AdminSession(this.token, this.functions);
  final String token;
  final List<String> functions;
  factory AdminSession.parse(Object? value) {
    final m = exact(value, {'adminSessionToken', 'adminSessionId', 'functions', 'idleExpiresAt', 'absoluteExpiresAt', 'serverTime'});
    final functions = m['functions'];
    if (m['adminSessionToken'] is! String || !tokenPattern.hasMatch(m['adminSessionToken'] as String) ||
        functions is! List || functions.isEmpty || !functions.every(adminFunctions.contains)) {
      throw const ResponseShapeError();
    }
    uuid(m['adminSessionId']);
    instant(m['idleExpiresAt']);
    instant(m['absoluteExpiresAt']);
    instant(m['serverTime']);
    return AdminSession(m['adminSessionToken'] as String, List.unmodifiable(functions.cast<String>()));
  }
  @override
  String toString() => 'AdminSession[REDACTED]';
}

class WorkItem {
  const WorkItem(this.applicationId, this.category, this.version, this.submittedAt);
  final String applicationId, category;
  final int version;
  final DateTime submittedAt;
  static List<WorkItem> parseList(Object? value) {
    if (value is! List || value.length > 50) throw const ResponseShapeError();
    return List.unmodifiable(value.map((item) {
      final m = exact(item, {'applicationId', 'requestedCategory', 'version', 'submittedAt'});
      if (!{'taxi', 'private'}.contains(m['requestedCategory']) || m['version'] is! int) throw const ResponseShapeError();
      return WorkItem(uuid(m['applicationId']), m['requestedCategory'] as String, m['version'] as int, instant(m['submittedAt']));
    }));
  }
}

class Requirement {
  const Requirement(this.code, this.document, this.established, this.documentId, this.reviewState, this.validUntil,
      this.expired);
  final String code, document;
  final bool established, expired;
  final String? documentId, reviewState;
  final DateTime? validUntil;
}

class Detail {
  const Detail({
    required this.applicationId,
    required this.category,
    required this.state,
    required this.version,
    required this.vehicle,
    required this.missingVehicleFields,
    required this.photos,
    required this.requirements,
    required this.decisionCount,
  });
  final String applicationId, category, state;
  final int version;
  final Map<String, Object?> vehicle;
  final List<String> missingVehicleFields;
  /// view -> file id of the current photo.
  final Map<String, String> photos;
  final List<Requirement> requirements;
  final int decisionCount;

  bool get hasRequirementToConfirm => requirements.any((r) => !r.established);

  factory Detail.parse(Object? value) {
    final m = exact(value, {'application', 'submittedAt', 'photos', 'requirements', 'decisions'});
    final a = exact(m['application'], {
      'applicationId', 'accountId', 'requestedCategory', 'state', 'version', 'vehicleFields', 'missingVehicleFields',
      'createdAt', 'updatedAt', 'serverTime', 'idempotentReplay',
    });
    final vehicle = exact(a['vehicleFields'], {
      'brand', 'model', 'year', 'color', 'registration', 'authorizedTotalCapacity', 'offeredPassengerSeats', 'usageRight',
    });
    if (!{'taxi', 'private'}.contains(a['requestedCategory']) || a['version'] is! int || a['state'] is! String ||
        a['missingVehicleFields'] is! List || m['photos'] is! List || m['requirements'] is! List || m['decisions'] is! List) {
      throw const ResponseShapeError();
    }
    final photos = <String, String>{};
    for (final photo in m['photos'] as List) {
      final p = exact(photo, {'view', 'fileId'});
      if (!photoViews.contains(p['view']) || photos.containsKey(p['view'])) throw const ResponseShapeError();
      photos[p['view'] as String] = uuid(p['fileId']);
    }
    final requirements = (m['requirements'] as List).map((item) {
      final r = exact(item, {
        'code', 'document', 'explanation', 'status', 'requiredForSubmission', 'documentId', 'reviewState', 'validUntil',
        'expired', 'satisfied',
      });
      if (r['code'] is! String || !reasonPattern.hasMatch(r['code'] as String) || r['document'] is! String ||
          !{'established', 'to_confirm'}.contains(r['status']) || r['expired'] is! bool ||
          (r['reviewState'] != null && !{'received', 'accepted', 'to_correct'}.contains(r['reviewState']))) {
        throw const ResponseShapeError();
      }
      return Requirement(r['code'] as String, r['document'] as String, r['status'] == 'established',
          r['documentId'] == null ? null : uuid(r['documentId']), r['reviewState'] as String?, day(r['validUntil']),
          r['expired'] as bool);
    }).toList();
    return Detail(
      applicationId: uuid(a['applicationId']),
      category: a['requestedCategory'] as String,
      state: a['state'] as String,
      version: a['version'] as int,
      vehicle: Map.unmodifiable(vehicle),
      missingVehicleFields: List.unmodifiable((a['missingVehicleFields'] as List).cast<String>()),
      photos: Map.unmodifiable(photos),
      requirements: List.unmodifiable(requirements),
      decisionCount: (m['decisions'] as List).length,
    );
  }
}

/// One element the agent judged: a photo view or a document.
class Review {
  Review({required this.label, required this.id, required this.isPhoto, this.view});
  final String label, id;
  final bool isPhoto;
  final String? view;
  String? result;
  String reason = '';

  bool get valid => result == 'acceptable' || (result == 'to_correct' && reasonPattern.hasMatch(reason));
  Map<String, Object?> toJson() => isPhoto
      ? {'view': view, 'fileId': id, 'result': result, 'reasonCode': result == 'to_correct' ? reason : null}
      : {'documentId': id, 'result': result, 'reasonCode': result == 'to_correct' ? reason : null};
}
