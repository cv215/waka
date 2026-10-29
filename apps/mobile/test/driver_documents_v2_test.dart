import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/data/driver_document_models.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';

Map<String, Object?> row(String code, {required bool required, String? documentId, String? review}) => {
  'code': code, 'document': 'Document $code', 'explanation': 'Une phrase.', 'status': 'established',
  'requiredForSubmission': required, 'documentId': documentId, 'reviewState': review, 'validUntil': null,
  'expired': false, 'satisfied': review == 'accepted',
};

void main() {
  test('catalogue v2 : seules l’identité et le permis bloquent l’envoi, une pièce à corriger aussi', () {
    final list = RequirementView.parseList([
      row('IDENTITY', required: true, documentId: randomLoginId(), review: 'received'),
      row('DRIVING_LICENCE', required: true),
      row('PUBLIC_TRANSPORT_CARD', required: false),
      row('INSURANCE', required: false, documentId: randomLoginId(), review: 'to_correct'),
    ]);
    expect(list.where((r) => r.requiredForSubmission).map((r) => r.code), ['IDENTITY', 'DRIVING_LICENCE']);
    final blocking = list.where((r) =>
        (r.requiredForSubmission && (r.documentId == null || r.expired)) || r.reviewState == 'to_correct').map((r) => r.code);
    expect(blocking, ['DRIVING_LICENCE', 'INSURANCE'], reason: 'la carte bleue manquante ne bloque pas l’envoi');
  });

  test('une exigence à confirmer ne peut jamais être requise pour l’envoi', () {
    expect(() => RequirementView.parseList([{...row('X_CODE', required: true), 'status': 'to_confirm'}]), throwsA(anything));
  });
}
