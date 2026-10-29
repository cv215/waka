import 'login_models.dart';
import 'profile_models.dart';

class ApplicationProblem implements Exception {
  const ApplicationProblem(this.failure);
  final ApplicationFailure failure;
  @override
  String toString() => 'ApplicationProblem(${failure.name})';
}

enum ApplicationFailure {
  denied,
  versionConflict,
  keyReused,
  replayExpired,
  alreadyOpen,
  notFound,
  unavailable,
  uncertain,
  invalidResponse,
  storage,
  invalid,

  /// The server refused the request itself (400, 413, 415): it was not applied.
  rejected,
  /// Submission refused: a vehicle field, a photo or a required document is missing or to correct.
  incomplete,
  /// The file is under examination or closed: nothing can be changed now.
  notEditable,
}

const vehicleFieldNames = [
  'brand',
  'model',
  'year',
  'color',
  'registration',
  'authorizedTotalCapacity',
  'offeredPassengerSeats',
  'usageRight',
];
const _texts = {'brand', 'model', 'color', 'registration', 'usageRight'};
const maxWakaPassengers = 5;
final _plate = RegExp(r'^[A-Z0-9][A-Z0-9 -]{0,14}[A-Z0-9]$');

/// The eight C03 fields as declared; null means "not known yet" in a draft.
/// Declared values are never a verified capacity, plate or right of use.
class VehicleData {
  const VehicleData({
    this.brand,
    this.model,
    this.year,
    this.color,
    this.registration,
    this.authorizedTotalCapacity,
    this.offeredPassengerSeats,
    this.usageRight,
  });
  static const empty = VehicleData();
  final String? brand, model, color, registration, usageRight;
  final int? year, authorizedTotalCapacity, offeredPassengerSeats;

  Map<String, Object?> toJson() => {
    'brand': brand,
    'model': model,
    'year': year,
    'color': color,
    'registration': registration,
    'authorizedTotalCapacity': authorizedTotalCapacity,
    'offeredPassengerSeats': offeredPassengerSeats,
    'usageRight': usageRight,
  };

  List<String> get missing => [
    for (final entry in toJson().entries)
      if (entry.value == null) entry.key,
  ];

  /// Same local bounds as the server, so an accepted value can always be restored.
  /// The server stays the authority (normalization, Unicode categories, server year).
  bool get acceptable {
    for (final text in [brand, model, color]) {
      if (text != null && (!isAcceptableProfileName(text) || text.runes.length > 40)) return false;
    }
    final total = authorizedTotalCapacity, seats = offeredPassengerSeats;
    return (registration == null || _plate.hasMatch(registration!)) &&
        (year == null || (year! >= 1900 && year! <= 9999)) &&
        (total == null || (total >= 2 && total <= 99)) &&
        (seats == null || (seats >= 1 && seats <= maxWakaPassengers)) &&
        (seats == null || total == null || seats <= (total - 1 < maxWakaPassengers ? total - 1 : maxWakaPassengers)) &&
        (usageRight == null || usageRight == 'holder' || usageRight == 'authorized_use');
  }

  /// Field-level normalization the server would apply; trimming and upper-case plate.
  VehicleData normalized() => VehicleData(
    brand: brand?.trim(),
    model: model?.trim(),
    year: year,
    color: color?.trim(),
    registration: registration?.trim().toUpperCase(),
    authorizedTotalCapacity: authorizedTotalCapacity,
    offeredPassengerSeats: offeredPassengerSeats,
    usageRight: usageRight,
  );

  factory VehicleData.parse(Object? value) {
    final m = exactObject(value, vehicleFieldNames.toSet());
    for (final name in vehicleFieldNames) {
      final v = m[name];
      if (v != null && (_texts.contains(name) ? v is! String : v is! int)) throw const FormatException();
    }
    final data = VehicleData(
      brand: m['brand'] as String?,
      model: m['model'] as String?,
      year: m['year'] as int?,
      color: m['color'] as String?,
      registration: m['registration'] as String?,
      authorizedTotalCapacity: m['authorizedTotalCapacity'] as int?,
      offeredPassengerSeats: m['offeredPassengerSeats'] as int?,
      usageRight: m['usageRight'] as String?,
    );
    if (!data.acceptable) throw const FormatException();
    return data;
  }

  @override
  bool operator ==(Object other) =>
      other is VehicleData && _sameMap(other.toJson(), toJson());
  @override
  int get hashCode => Object.hashAll(toJson().values);
  @override
  String toString() => 'VehicleData[REDACTED]';
}

bool _sameMap(Map<String, Object?> a, Map<String, Object?> b) =>
    a.length == b.length && a.keys.every((k) => b.containsKey(k) && a[k] == b[k]);

/// States of a candidature; only an explicit agent decision can lead to `approved`.
const applicationStates = {'draft', 'submitted', 'correction_requested', 'refused', 'approved'};
/// States in which the candidate may still change the vehicle, photos and documents.
const editableApplicationStates = {'draft', 'correction_requested'};

/// A candidature as read from the server; submitting it is never an admission or an access.
class DriverApplicationSnapshot {
  const DriverApplicationSnapshot({
    required this.applicationId,
    required this.accountId,
    required this.requestedCategory,
    required this.version,
    required this.vehicle,
    required this.missing,
    required this.updatedAt,
    required this.replay,
    this.state = 'draft',
  });
  final String applicationId, accountId, requestedCategory, state;
  bool get editable => editableApplicationStates.contains(state);
  final int version;
  final VehicleData vehicle;
  final List<String> missing;
  final DateTime updatedAt;
  final bool replay;

  /// [command] is the create or vehicle command a response answers; null for a read.
  factory DriverApplicationSnapshot.parse(
    Object? value,
    LoginSession session, {
    ApplicationCommand? command,
  }) {
    try {
      final m = exactObject(value, {
        'applicationId',
        'accountId',
        'requestedCategory',
        'state',
        'version',
        'vehicleFields',
        'missingVehicleFields',
        'createdAt',
        'updatedAt',
        'serverTime',
        'idempotentReplay',
      });
      final vehicle = VehicleData.parse(m['vehicleFields']);
      final missing = m['missingVehicleFields'];
      final createdAt = utcDate(m['createdAt']), updatedAt = utcDate(m['updatedAt']);
      final serverTime = utcDate(m['serverTime']);
      if (!isLoginUuid(m['applicationId']) ||
          m['accountId'] != session.accountId ||
          (m['requestedCategory'] != 'taxi' && m['requestedCategory'] != 'private') ||
          !applicationStates.contains(m['state']) ||
          m['version'] is! int ||
          (m['version'] as int) < 1 ||
          (m['version'] as int) > 9007199254740991 ||
          m['idempotentReplay'] is! bool ||
          missing is! List ||
          missing.length != vehicle.missing.length ||
          !missing.indexed.every((e) => e.$2 == vehicle.missing[e.$1]) ||
          updatedAt.isBefore(createdAt) ||
          serverTime.isBefore(updatedAt)) {
        throw const FormatException();
      }
      final version = m['version'] as int;
      final replay = m['idempotentReplay'] as bool;
      final id = m['applicationId'] as String;
      if (command == null && replay) throw const FormatException();
      if (command is CreateApplicationCommand &&
          (version != 1 || vehicle != VehicleData.empty || m['requestedCategory'] != command.requestedCategory ||
              m['state'] != 'draft')) {
        throw const FormatException();
      }
      if (command is SubmitApplicationCommand &&
          (version != command.expectedVersion + 1 || id != command.applicationId || m['state'] != 'submitted')) {
        throw const FormatException();
      }
      if (command is VehicleApplicationCommand &&
          // The server may normalize further (NFC): only the version and the target are bound here.
          (version != command.expectedVersion + 1 || id != command.applicationId)) {
        throw const FormatException();
      }
      return DriverApplicationSnapshot(
        applicationId: id,
        accountId: session.accountId,
        requestedCategory: m['requestedCategory'] as String,
        version: version,
        vehicle: vehicle,
        missing: vehicle.missing,
        updatedAt: updatedAt,
        replay: replay,
        state: m['state'] as String,
      );
    } on LoginProblem {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    } on FormatException {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    } on TypeError {
      throw const ApplicationProblem(ApplicationFailure.invalidResponse);
    }
  }

  @override
  String toString() => 'DriverApplicationSnapshot[REDACTED]';
}

/// A command persisted before any network effect; its key alone can recover the outcome.
sealed class ApplicationCommand {
  const ApplicationCommand(this.key, this.accountId);
  final String key, accountId;
  Map<String, Object?> toJson();

  static ApplicationCommand parse(Object? value) {
    try {
      final kind = value is Map ? value['kind'] : null;
      final m = exactObject(
        value,
        switch (kind) {
          'create' => {'kind', 'key', 'accountId', 'requestedCategory'},
          'submit' => {'kind', 'key', 'accountId', 'applicationId', 'expectedVersion'},
          _ => {'kind', 'key', 'accountId', 'applicationId', 'expectedVersion', 'vehicleFields'},
        },
      );
      if (!isLoginUuid(m['key']) || !isLoginUuid(m['accountId'])) throw const FormatException();
      if (kind == 'create') {
        if (m['requestedCategory'] != 'taxi' && m['requestedCategory'] != 'private') throw const FormatException();
        return CreateApplicationCommand(m['key'] as String, m['accountId'] as String, m['requestedCategory'] as String);
      }
      if (kind == 'submit') {
        if (!isLoginUuid(m['applicationId']) || m['expectedVersion'] is! int ||
            (m['expectedVersion'] as int) < 1 || (m['expectedVersion'] as int) > 9007199254740991) {
          throw const FormatException();
        }
        return SubmitApplicationCommand(m['key'] as String, m['accountId'] as String, m['applicationId'] as String,
            m['expectedVersion'] as int);
      }
      if (kind != 'vehicle' ||
          !isLoginUuid(m['applicationId']) ||
          m['expectedVersion'] is! int ||
          (m['expectedVersion'] as int) < 1 ||
          (m['expectedVersion'] as int) > 9007199254740991) {
        throw const FormatException();
      }
      return VehicleApplicationCommand(
        m['key'] as String,
        m['accountId'] as String,
        m['applicationId'] as String,
        m['expectedVersion'] as int,
        VehicleData.parse(m['vehicleFields']),
      );
    } on FormatException {
      throw const ApplicationProblem(ApplicationFailure.storage);
    } on LoginProblem {
      throw const ApplicationProblem(ApplicationFailure.storage);
    } on TypeError {
      throw const ApplicationProblem(ApplicationFailure.storage);
    }
  }

  @override
  String toString() => 'ApplicationCommand[REDACTED]';
}

class CreateApplicationCommand extends ApplicationCommand {
  const CreateApplicationCommand(super.key, super.accountId, this.requestedCategory);
  final String requestedCategory;
  @override
  Map<String, Object?> toJson() => {
    'kind': 'create',
    'key': key,
    'accountId': accountId,
    'requestedCategory': requestedCategory,
  };
}

/// Sends the exact version to examination; never an approval.
class SubmitApplicationCommand extends ApplicationCommand {
  const SubmitApplicationCommand(super.key, super.accountId, this.applicationId, this.expectedVersion);
  final String applicationId;
  final int expectedVersion;
  @override
  Map<String, Object?> toJson() => {
    'kind': 'submit',
    'key': key,
    'accountId': accountId,
    'applicationId': applicationId,
    'expectedVersion': expectedVersion,
  };
}

class VehicleApplicationCommand extends ApplicationCommand {
  const VehicleApplicationCommand(
    super.key,
    super.accountId,
    this.applicationId,
    this.expectedVersion,
    this.vehicle,
  );
  final String applicationId;
  final int expectedVersion;
  final VehicleData vehicle;
  @override
  Map<String, Object?> toJson() => {
    'kind': 'vehicle',
    'key': key,
    'accountId': accountId,
    'applicationId': applicationId,
    'expectedVersion': expectedVersion,
    'vehicleFields': vehicle.toJson(),
  };
}
