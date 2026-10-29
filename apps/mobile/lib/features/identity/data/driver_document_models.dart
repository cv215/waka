import 'login_models.dart';
import 'vehicle_photo_models.dart';

final _code = RegExp(r'^[A-Z][A-Z0-9_]{2,47}$');
final _day = RegExp(r'^\d{4}-\d{2}-\d{2}$');

DateTime? _date(Object? value) {
  if (value == null) return null;
  if (value is! String || !_day.hasMatch(value)) throw const FormatException();
  final parsed = DateTime.parse('${value}T00:00:00Z');
  if (parsed.toIso8601String().substring(0, 10) != value) throw const FormatException();
  return parsed;
}

String dayText(DateTime day) => day.toIso8601String().substring(0, 10);

/// One document that applies to the declared situation. A requirement still to be confirmed by WAKA
/// is shown but never satisfied.
class RequirementView {
  const RequirementView({
    required this.code,
    required this.document,
    required this.explanation,
    required this.established,
    required this.documentId,
    required this.reviewState,
    required this.validUntil,
    required this.expired,
    required this.satisfied,
    this.requiredForSubmission = false,
  });
  final String code, document, explanation;
  final bool established, expired, satisfied;
  /// Identity and licence (catalogue v2): needed to send the file. Vehicle documents may follow; WAKA decides.
  final bool requiredForSubmission;
  final String? documentId, reviewState;
  final DateTime? validUntil;

  /// What the candidate still has to do for this document.
  bool get needsUpload => documentId == null || expired || reviewState == 'to_correct';

  static List<RequirementView> parseList(Object? value) {
    try {
      if (value is! List || value.isEmpty || value.length > 20) throw const FormatException();
      final seen = <String>{};
      return List.unmodifiable(value.map((item) {
        final m = exactObject(item, {
          'code', 'document', 'explanation', 'status', 'requiredForSubmission', 'documentId', 'reviewState',
          'validUntil', 'expired', 'satisfied',
        });
        final established = m['status'] == 'established';
        final hasDocument = m['documentId'] != null;
        if (m['code'] is! String || !_code.hasMatch(m['code'] as String) || !seen.add(m['code'] as String) ||
            m['document'] is! String || (m['document'] as String).isEmpty ||
            m['explanation'] is! String || (m['explanation'] as String).isEmpty ||
            (m['status'] != 'established' && m['status'] != 'to_confirm') ||
            m['requiredForSubmission'] is! bool || (m['requiredForSubmission'] == true && !established) ||
            (hasDocument && !isLoginUuid(m['documentId'])) ||
            (hasDocument != (m['reviewState'] != null)) ||
            (hasDocument && !{'received', 'accepted', 'to_correct'}.contains(m['reviewState'])) ||
            m['expired'] is! bool || m['satisfied'] is! bool ||
            (m['expired'] == true && !hasDocument) ||
            // Never trust a "satisfied" that the rules forbid.
            (m['satisfied'] == true && !(established && m['reviewState'] == 'accepted' && m['expired'] == false))) {
          throw const FormatException();
        }
        return RequirementView(
          code: m['code'] as String,
          document: m['document'] as String,
          explanation: m['explanation'] as String,
          established: established,
          documentId: m['documentId'] as String?,
          reviewState: m['reviewState'] as String?,
          validUntil: _date(m['validUntil']),
          expired: m['expired'] as bool,
          satisfied: m['satisfied'] as bool,
          requiredForSubmission: m['requiredForSubmission'] as bool,
        );
      }));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on LoginProblem {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on TypeError {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

class DocumentUpload {
  const DocumentUpload(this.documentId, this.code, this.replay);
  final String documentId, code;
  final bool replay;

  factory DocumentUpload.parse(Object? value, String code) {
    try {
      final m = exactObject(value, {
        'documentId', 'documentType', 'reviewState', 'validUntil', 'byteSize', 'createdAt', 'idempotentReplay',
      });
      if (!isLoginUuid(m['documentId']) || m['documentType'] != code ||
          !{'received', 'accepted', 'to_correct'}.contains(m['reviewState']) ||
          m['byteSize'] is! int || (m['byteSize'] as int) < 1 || m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      _date(m['validUntil']);
      utcDate(m['createdAt']);
      return DocumentUpload(m['documentId'] as String, code, m['idempotentReplay'] as bool);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on LoginProblem {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on TypeError {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

/// One reviewed element (photo view or document) of a decision.
class ReviewItem {
  const ReviewItem(this.label, this.toCorrect, this.reasonCode);
  final String label;
  final bool toCorrect;
  final String? reasonCode;
}

/// A decision about the candidate's own file; the agent is never named.
class DecisionView {
  const DecisionView(this.action, this.reasonCode, this.details, this.decidedAt, this.reviews);
  final String action, reasonCode;
  final String? details;
  final DateTime decidedAt;
  final List<ReviewItem> reviews;

  static List<DecisionView> parseList(Object? value) {
    try {
      if (value is! List || value.length > 100) throw const FormatException();
      return List.unmodifiable(value.map((item) {
        final m = exactObject(item, {
          'decisionId', 'applicationId', 'examinedVersion', 'action', 'reasonCode', 'details', 'photoReviews',
          'documentReviews', 'decidedAt', 'idempotentReplay',
        });
        if (!isLoginUuid(m['decisionId']) || !isLoginUuid(m['applicationId']) || m['examinedVersion'] is! int ||
            !{'request_correction', 'refuse', 'approve'}.contains(m['action']) ||
            m['reasonCode'] is! String || !_code.hasMatch(m['reasonCode'] as String) ||
            (m['details'] != null && m['details'] is! String) ||
            m['photoReviews'] is! List || m['documentReviews'] is! List || m['idempotentReplay'] != false) {
          throw const FormatException();
        }
        final reviews = <ReviewItem>[];
        for (final review in m['photoReviews'] as List) {
          final r = exactObject(review, {'view', 'fileId', 'result', 'reasonCode'});
          if (!vehiclePhotoViews.contains(r['view'])) throw const FormatException();
          reviews.add(_review('${r['view']} — ${vehiclePhotoLabels[r['view']]}', r));
        }
        for (final review in m['documentReviews'] as List) {
          final r = exactObject(review, {'documentId', 'documentType', 'result', 'reasonCode'});
          if (r['documentType'] is! String || !_code.hasMatch(r['documentType'] as String)) throw const FormatException();
          reviews.add(_review(r['documentType'] as String, r));
        }
        return DecisionView(m['action'] as String, m['reasonCode'] as String, m['details'] as String?,
            utcDate(m['decidedAt']), List.unmodifiable(reviews));
      }));
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on LoginProblem {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on TypeError {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }

  static ReviewItem _review(String label, Map<String, dynamic> r) {
    final toCorrect = r['result'] == 'to_correct';
    if ((!toCorrect && r['result'] != 'acceptable') || (toCorrect && r['reasonCode'] is! String) ||
        (r['reasonCode'] != null && (r['reasonCode'] is! String || !_code.hasMatch(r['reasonCode'] as String)))) {
      throw const FormatException();
    }
    return ReviewItem(label, toCorrect, r['reasonCode'] as String?);
  }
}
