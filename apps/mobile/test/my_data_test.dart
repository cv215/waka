import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/privacy_models.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';
import 'package:waka_mobile/features/privacy/application/my_data_flow.dart';

import 'driver_document_test.dart' as fixture;

String now() => DateTime.now().toUtc().toIso8601String();

/// Mirrors the K21 rules: one open request per kind, replays by key, own activity only.
class PrivacyFixture extends fixture.Gateway implements PrivacyGateway {
  PrivacyFixture(LoginSession session) : super(session, 'taxi', 'holder');
  final sent = <({String key, String kind, String? details})>[];
  final open = <String, String>{};
  final byKey = <String, String>{};
  int loseAnswers = 0;

  @override
  Future<PrivacyRequestView> requestPrivacy(LoginSession s, String client, String key, String kind, String? details) async {
    sent.add((key: key, kind: kind, details: details));
    final existing = byKey[key];
    final created = existing == null && !open.containsKey(kind);
    final id = existing ?? open.putIfAbsent(kind, randomLoginId);
    byKey[key] = id;
    if (loseAnswers > 0) {
      loseAnswers--;
      throw const PhotoProblem(PhotoFailure.uncertain);
    }
    return PrivacyRequestView.parse({'requestId': id, 'kind': kind, 'state': 'received', 'receivedAt': now(), 'created': created});
  }

  @override
  Future<ActivityPage> activity(LoginSession s, String client, String? cursor) async => ActivityPage.parse({
    'items': [for (final e in open.entries) {'objectType': 'privacy_request', 'objectId': e.value, 'state': 'received', 'at': now()}],
    'nextCursor': null,
  });
}

Future<(LoginFlow, PrivacyFixture, MyDataFlow)> ready() async {
  final session = fixture.grant();
  final store = fixture.MemoryStore();
  await store.write(LoginSnapshot(randomLoginId(), session: session));
  final gateway = PrivacyFixture(session);
  final login = LoginFlow(gateway, store);
  await login.initialize();
  final flow = MyDataFlow(login, gateway);
  await flow.refresh();
  return (login, gateway, flow);
}

void main() {
  test('une demande perdue est renvoyée avec la même clé et ne crée qu’une demande', () async {
    final (login, gateway, flow) = await ready();
    gateway.loseAnswers = 1;
    await flow.request('deletion', '  Mon compte  ');
    expect(flow.failure, PhotoFailure.uncertain);
    expect(flow.pendingKind, 'deletion');
    await flow.retry();
    expect(flow.failure, isNull);
    expect(gateway.sent.map((s) => s.key).toSet(), hasLength(1));
    expect(gateway.sent.last.details, 'Mon compte');
    expect(flow.lastRequest!.state, 'received');
    expect(flow.items!.single.objectType, 'privacy_request');
    await flow.request('deletion', null);
    expect(flow.lastRequest!.created, isFalse, reason: 'la demande ouverte est retrouvée');
    expect(gateway.open, hasLength(1));
    flow.dispose();
    login.dispose();
  });

  test('lecture stricte : un champ inattendu ou un état inconnu est refusé', () {
    expect(() => PrivacyRequestView.parse({'requestId': randomLoginId(), 'kind': 'deletion', 'state': 'deleted',
      'receivedAt': now(), 'created': true}), throwsA(isA<PhotoProblem>()));
    expect(() => ActivityPage.parse({'items': [], 'nextCursor': null, 'accountId': 'x'}), throwsA(isA<PhotoProblem>()));
    expect(() => ActivityPage.parse({'items': [{'objectType': 'payment', 'objectId': randomLoginId(), 'state': 'paid',
      'at': now()}], 'nextCursor': null}), throwsA(isA<PhotoProblem>()));
  });
}
