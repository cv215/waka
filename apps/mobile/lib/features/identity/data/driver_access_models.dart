import 'vehicle_photo_models.dart';

const driverAccessStates = {'none', 'provisional', 'active', 'new_reservations_blocked'};
const _bases = {'taxi_no_subscription', 'provisional_before_first_service'};
const _reasons = {'NO_APPLICATION', 'NOT_APPROVED', 'DOCUMENT_EXPIRED', 'DOCUMENT_NOT_VALID', 'REQUIREMENTS_NOT_QUALIFIED'};
final _uuid = RegExp(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');
final _code = RegExp(r'^[A-Z][A-Z0-9_]{2,47}$');

/// A32 (K23): the effective access computed by the server; the phone never decides it.
class DriverAccessView {
  const DriverAccessView(this.access, this.basis, this.category, this.reasons, this.documentsToRenew, this.trial);
  final String access;
  final String? basis, category, trial;
  final List<String> reasons, documentsToRenew;

  static DriverAccessView parse(Object? value) {
    try {
      const fields = {'access', 'basis', 'applicationId', 'category', 'admissionState', 'reasons', 'documentsToRenew',
        'trial', 'serverTime'};
      if (value is! Map<String, dynamic> || value.length != fields.length || !value.keys.every(fields.contains)) {
        throw const FormatException();
      }
      final access = value['access'], basis = value['basis'], category = value['category'], trial = value['trial'];
      final reasons = value['reasons'], renew = value['documentsToRenew'], id = value['applicationId'];
      if (!driverAccessStates.contains(access) || !(basis == null || _bases.contains(basis)) ||
          !(category == null || category == 'taxi' || category == 'private') || !(trial == null || trial == 'not_started') ||
          !(id == null || id is String && _uuid.hasMatch(id)) || reasons is! List || !reasons.every(_reasons.contains) ||
          renew is! List || !renew.every((c) => c is String && _code.hasMatch(c)) ||
          (access == 'active' || access == 'provisional') != (basis != null)) {
        throw const FormatException();
      }
      return DriverAccessView(access as String, basis as String?, category as String?,
          List.unmodifiable(reasons.cast<String>()), List.unmodifiable(renew.cast<String>()), trial as String?);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}
