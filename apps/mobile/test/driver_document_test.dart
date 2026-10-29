import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/drivers/application/driver_document_flow.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/driver_application_models.dart';
import 'package:waka_mobile/features/identity/data/driver_document_models.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';

String now() => DateTime.now().toUtc().toIso8601String();
final jpeg = [0xFF, 0xD8, 0xFF, 0xE0, ...List.filled(64, 7)];

LoginSession grant() => LoginSession(
  randomLoginId(),
  randomLoginId(),
  base64Url.encode(List.filled(32, 6)).replaceAll('=', ''),
  DateTime.now().toUtc().add(const Duration(hours: 1)),
  DateTime.now().toUtc().add(const Duration(minutes: 10)),
);

class MemoryStore implements LoginStore {
  String? raw;
  @override
  Future<LoginSnapshot?> read() async => raw == null ? null : LoginSnapshot.parse(raw!);
  @override
  Future<void> write(LoginSnapshot value) async => raw = jsonEncode(value.toJson());
}

/// Mirrors the server rules of catalogue v1 for one file: applicable documents, reuse and review states.
class Gateway implements LoginGateway, DriverApplicationGateway, DriverDocumentGateway {
  Gateway(this.session, this.category, this.usageRight);
  final LoginSession session;
  final String category;
  String? usageRight;
  final String applicationId = randomLoginId();
  final documents = <String, ({String id, String review, DateTime? until})>{};
  final uploads = <({String code, String key, DateTime? until})>[];
  final answers = <Object>[];
  final decisionsJson = <Map<String, Object?>>[];
  LoginFailure? accountFailure;

  static const _catalogue = [
    ('IDENTITY', true, {'taxi', 'private'}, false),
    ('DRIVING_LICENCE', true, {'taxi', 'private'}, false),
    ('REGISTRATION_CERTIFICATE', true, {'taxi', 'private'}, false),
    ('INSURANCE', true, {'taxi', 'private'}, false),
    ('TECHNICAL_INSPECTION', false, {'taxi', 'private'}, false),
    ('USE_AUTHORIZATION', true, {'taxi', 'private'}, true),
    ('TRANSPORT_LICENCE', false, {'taxi'}, false),
    ('PUBLIC_TRANSPORT_CARD', false, {'taxi'}, false),
    ('PAID_TRANSPORT_AUTHORIZATION', false, {'private'}, false),
  ];
  Iterable<(String, bool, Set<String>, bool)> get _applicable =>
      _catalogue.where((r) => r.$3.contains(category) && (!r.$4 || usageRight == 'authorized_use'));

  List<Map<String, Object?>> requirementsJson() => [
    for (final (code, established, _, _) in _applicable)
      {
        'code': code,
        'document': 'Document $code',
        'explanation': 'Explication simple pour $code.',
        'status': established ? 'established' : 'to_confirm',
        'requiredForSubmission': established,
        'documentId': documents[code]?.id,
        'reviewState': documents[code]?.review,
        'validUntil': documents[code]?.until == null ? null : dayText(documents[code]!.until!),
        'expired': false,
        'satisfied': established && documents[code]?.review == 'accepted',
      },
  ];

  @override
  Future<List<RequirementView>> requirements(LoginSession s, String client, String application) async =>
      RequirementView.parseList(requirementsJson());

  @override
  Future<List<DecisionView>> decisions(LoginSession s, String client, String application) async =>
      DecisionView.parseList(decisionsJson);

  @override
  Future<DocumentUpload> uploadDocument(LoginSession s, String client, String application, String code, String key,
      List<int> bytes, String contentType, DateTime? validUntil) async {
    uploads.add((code: code, key: key, until: validUntil));
    final answer = answers.isEmpty ? null : answers.removeAt(0);
    if (answer is PhotoProblem) throw answer;
    if (!_applicable.any((r) => r.$1 == code)) throw const PhotoProblem(PhotoFailure.notApplicable);
    final replay = documents[code]?.id != null && uploads.where((u) => u.key == key).length > 1;
    if (!replay) documents[code] = (id: randomLoginId(), review: 'received', until: validUntil);
    if (answer == #lose) throw const PhotoProblem(PhotoFailure.uncertain);
    return DocumentUpload.parse({
      'documentId': documents[code]!.id, 'documentType': code, 'reviewState': 'received',
      'validUntil': validUntil == null ? null : dayText(validUntil), 'byteSize': bytes.length, 'createdAt': now(),
      'idempotentReplay': replay,
    }, code);
  }

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession s, String client) async =>
      DriverApplicationSnapshot.parse({
        'applicationId': applicationId, 'accountId': s.accountId, 'requestedCategory': category, 'state': 'draft',
        'version': 2, 'vehicleFields': VehicleData(usageRight: usageRight, registration: 'AB 12').toJson(),
        'missingVehicleFields': VehicleData(usageRight: usageRight, registration: 'AB 12').missing,
        'createdAt': now(), 'updatedAt': now(), 'serverTime': now(), 'idempotentReplay': false,
      }, s);
  @override
  Future<DriverApplicationSnapshot> sendApplication(LoginSession s, String client, ApplicationCommand c) =>
      throw const ApplicationProblem(ApplicationFailure.invalid);
  @override
  Future<LoginAccount> me(LoginSession s, String client) async {
    if (accountFailure != null) throw LoginProblem(accountFailure!);
    return LoginAccount.parse({
      'accountId': s.accountId, 'sessionId': s.id, 'absoluteExpiresAt': s.absoluteExpiresAt.toIso8601String(),
      'idleExpiresAt': s.idleExpiresAt.toIso8601String(), 'accountState': 'ACTIVE', 'roles': ['CLIENT'], 'serverTime': now(),
    }, s);
  }
  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async => throw const LoginProblem(LoginFailure.invalid);
  @override
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code) async => session;
  @override
  Future<void> revoke(LoginSession s, String client, String key) async {}
  @override
  void dispose() {}
}

Future<(LoginFlow, Gateway, DriverDocumentFlow)> ready(String category, String? usageRight) async {
  final session = grant();
  final store = MemoryStore();
  await store.write(LoginSnapshot(randomLoginId(), session: session));
  final gateway = Gateway(session, category, usageRight);
  final login = LoginFlow(gateway, store);
  await login.initialize();
  await login.refreshApplication();
  final documents = DriverDocumentFlow(login, gateway);
  await documents.refresh();
  return (login, gateway, documents);
}

List<String> codes(DriverDocumentFlow flow) => flow.requirements!.map((r) => r.code).toList();

void main() {
  test('Parcours taxi : titres d’exploitation du véhicule, jamais d’autorisation de particulier', () async {
    final (login, _, documents) = await ready('taxi', 'holder');
    expect(codes(documents), ['IDENTITY', 'DRIVING_LICENCE', 'REGISTRATION_CERTIFICATE', 'INSURANCE',
        'TECHNICAL_INSPECTION', 'TRANSPORT_LICENCE', 'PUBLIC_TRANSPORT_CARD']);
    expect(documents.missingForSubmission.map((r) => r.code),
        ['IDENTITY', 'DRIVING_LICENCE', 'REGISTRATION_CERTIFICATE', 'INSURANCE'],
        reason: 'les exigences à confirmer ne bloquent pas l’envoi');
    documents.dispose();
    login.dispose();
  });

  test('Parcours voiture personnelle : aucun titre de taxi ; autorisation d’utilisation seulement si besoin', () async {
    final (login, _, documents) = await ready('private', 'authorized_use');
    expect(codes(documents), ['IDENTITY', 'DRIVING_LICENCE', 'REGISTRATION_CERTIFICATE', 'INSURANCE',
        'TECHNICAL_INSPECTION', 'USE_AUTHORIZATION', 'PAID_TRANSPORT_AUTHORIZATION']);
    await documents.upload('TRANSPORT_LICENCE', jpeg, null);
    expect(documents.failure, PhotoFailure.notApplicable);
    documents.dispose();
    login.dispose();
    final (holderLogin, _, holderDocuments) = await ready('private', 'holder');
    expect(codes(holderDocuments).contains('USE_AUTHORIZATION'), isFalse);
    holderDocuments.dispose();
    holderLogin.dispose();
  });

  test('Dépôt avec date de validité, puis la pièce n’est plus « à fournir »', () async {
    final (login, gateway, documents) = await ready('taxi', 'holder');
    final until = DateTime.utc(2027, 6, 30);
    await documents.upload('INSURANCE', jpeg, until);
    expect(gateway.uploads.single.until, until);
    final insurance = documents.requirements!.firstWhere((r) => r.code == 'INSURANCE');
    expect(insurance.reviewState, 'received');
    expect(insurance.satisfied, isFalse, reason: 'reçue n’est jamais acceptée');
    expect(insurance.needsUpload, isFalse);
    expect(documents.missingForSubmission.map((r) => r.code).contains('INSURANCE'), isFalse);
    documents.dispose();
    login.dispose();
  });

  test('Réponse perdue : même clé, une seule pièce ; refus définitif sans reprise', () async {
    final (login, gateway, documents) = await ready('taxi', 'holder');
    gateway.answers.add(#lose);
    await documents.upload('IDENTITY', jpeg, null);
    expect(documents.failure, PhotoFailure.uncertain);
    expect(documents.hasPending('IDENTITY'), isTrue);
    await documents.retry('IDENTITY');
    expect(gateway.uploads.map((u) => u.key).toSet(), hasLength(1));
    expect(documents.hasPending('IDENTITY'), isFalse);
    gateway.answers.add(const PhotoProblem(PhotoFailure.rejectedFile));
    await documents.upload('DRIVING_LICENCE', jpeg, null);
    expect(documents.failure, PhotoFailure.rejectedFile);
    expect(documents.hasPending('DRIVING_LICENCE'), isFalse);
    documents.dispose();
    login.dispose();
  });

  test('Déconnexion : pièces, décisions et reprises effacées', () async {
    final (login, gateway, documents) = await ready('taxi', 'holder');
    gateway.answers.add(#lose);
    await documents.upload('IDENTITY', jpeg, null);
    await login.revoke();
    expect(documents.requirements, isNull);
    expect(documents.decisions, isNull);
    expect(documents.hasPending('IDENTITY'), isFalse);
    documents.dispose();
    login.dispose();
  });

  group('Lecture stricte', () {
    Map<String, Object?> requirement({String status = 'established', String? review, bool satisfied = false}) => {
      'code': 'INSURANCE', 'document': 'Attestation', 'explanation': 'Une phrase.', 'status': status,
      'requiredForSubmission': status == 'established', 'documentId': review == null ? null : randomLoginId(),
      'reviewState': review, 'validUntil': null, 'expired': false, 'satisfied': satisfied,
    };
    void refused(List<Object?> value) => expect(() => RequirementView.parseList(value), throwsA(isA<PhotoProblem>()));

    test('une exigence à confirmer ne peut jamais être annoncée satisfaite', () {
      expect(RequirementView.parseList([requirement(review: 'accepted', satisfied: true)]).single.satisfied, isTrue);
      refused([requirement(status: 'to_confirm', review: 'accepted', satisfied: true)]);
      refused([requirement(review: 'received', satisfied: true)]);
      refused([requirement(status: 'to_confirm')..['requiredForSubmission'] = true]);
      refused([requirement(), requirement()]);
      refused([requirement()..['reviewState'] = 'approved']);
      refused([requirement()..['validUntil'] = '30/06/2027']);
      refused([requirement()..['code'] = 'insurance']);
    });

    test('décisions : action fermée, pièces à corriger avec motif', () {
      Map<String, Object?> decision(String action, List<Map<String, Object?>> documents) => {
        'decisionId': randomLoginId(), 'applicationId': randomLoginId(), 'examinedVersion': 3, 'action': action,
        'reasonCode': 'DOCUMENT_TO_REPLACE', 'details': null, 'photoReviews': [], 'documentReviews': documents,
        'decidedAt': now(), 'idempotentReplay': false,
      };
      final item = {'documentId': randomLoginId(), 'documentType': 'INSURANCE', 'result': 'to_correct', 'reasonCode': 'COVER_NOT_SHOWN'};
      final parsed = DecisionView.parseList([decision('request_correction', [item])]);
      expect(parsed.single.reviews.single.toCorrect, isTrue);
      expect(() => DecisionView.parseList([decision('grant', [])]), throwsA(isA<PhotoProblem>()));
      expect(() => DecisionView.parseList([decision('request_correction', [{...item, 'reasonCode': null}])]),
          throwsA(isA<PhotoProblem>()));
      expect(() => DecisionView.parseList([decision('approve', [])..['agentAccountId'] = randomLoginId()]),
          throwsA(isA<PhotoProblem>()), reason: 'l’agent n’est jamais nommé au candidat');
    });
  });

  group('Client HTTP pièces', () {
    late HttpServer server;
    late LoginClient client;
    final session = grant();
    final app = randomLoginId();
    final seen = <(String, String, String?, String?)>[];

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((request) async {
        await request.drain<void>();
        seen.add((request.method, request.uri.path, request.headers.value('X-Waka-Valid-Until'), request.headers.contentType?.mimeType));
        request.response.statusCode = request.method == 'POST' ? 409 : 200;
        request.response.headers.contentType = ContentType.parse(request.method == 'POST' ? 'application/problem+json' : 'application/json');
        request.response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
        request.response.write(request.method == 'POST' ? jsonEncode({'status': 409, 'code': 'DOCUMENT_NOT_APPLICABLE'}) : '[]');
        await request.response.close();
      });
      client = LoginClient(LocalApiConfiguration.parse(environment: 'dev', baseUrl: 'http://127.0.0.1:${server.port}', releaseMode: false));
    });
    tearDown(() async {
      client.dispose();
      await server.close(force: true);
      seen.clear();
    });

    test('envoi brut avec date de validité ; refus « non applicable » distingué', () async {
      await expectLater(client.uploadDocument(session, 'c' * 20, app, 'TRANSPORT_LICENCE', randomLoginId(), jpeg, 'image/jpeg',
          DateTime.utc(2027, 6, 30)), throwsA(isA<PhotoProblem>().having((p) => p.failure, 'f', PhotoFailure.notApplicable)));
      expect(seen.single, ('POST', '/api/v1/driver-applications/$app/documents/TRANSPORT_LICENCE', '2027-06-30', 'image/jpeg'));
      expect(await client.decisions(session, 'c' * 20, app), isEmpty);
      expect(() => client.uploadDocument(session, 'c' * 20, app, 'bad code', randomLoginId(), jpeg, 'image/jpeg', null),
          throwsA(isA<PhotoProblem>()));
    });
  });
}
