import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/drivers/application/vehicle_photo_flow.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/driver_application_models.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/vehicle_photo_models.dart';

String now() => DateTime.now().toUtc().toIso8601String();
final jpeg = [0xFF, 0xD8, 0xFF, 0xE0, ...List.filled(64, 7)];
final png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, ...List.filled(64, 3)];

LoginSession grant() => LoginSession(
  randomLoginId(),
  randomLoginId(),
  base64Url.encode(List.filled(32, 5)).replaceAll('=', ''),
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

/// Server model for the identity, the draft and the six views.
class Gateway implements LoginGateway, DriverApplicationGateway, VehiclePhotoGateway {
  Gateway(this.session);
  final LoginSession session;
  final String applicationId = randomLoginId();
  final files = <String, String>{};
  final uploads = <({String view, String key, String type})>[];
  final answers = <Object>[];
  final appliedKeys = <String>{};
  LoginFailure? accountFailure;
  Completer<void>? gate;

  @override
  Future<List<PhotoViewStatus>> listPhotos(LoginSession s, String client, String application) async =>
      PhotoViewStatus.parseList([
        for (final view in vehiclePhotoViews)
          {'view': view, 'state': files.containsKey(view) ? 'received' : 'missing', 'fileId': files[view],
           'createdAt': files.containsKey(view) ? now() : null},
      ]);

  @override
  Future<PhotoUpload> uploadPhoto(LoginSession s, String client, String application, String view, String key,
      List<int> bytes, String contentType) async {
    uploads.add((view: view, key: key, type: contentType));
    if (gate != null) await gate!.future;
    final answer = answers.isEmpty ? null : answers.removeAt(0);
    if (answer is PhotoProblem) throw answer;
    final replay = appliedKeys.contains(key);
    if (!replay) {
      appliedKeys.add(key);
      files[view] = randomLoginId();
    }
    if (answer == #lose) throw const PhotoProblem(PhotoFailure.uncertain);
    return PhotoUpload.parse({
      'fileId': files[view], 'applicationId': application, 'view': view, 'state': 'received', 'width': 640,
      'height': 480, 'byteSize': bytes.length, 'createdAt': now(), 'idempotentReplay': replay,
    }, application, view);
  }

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession s, String client) async =>
      DriverApplicationSnapshot.parse({
        'applicationId': applicationId, 'accountId': s.accountId, 'requestedCategory': 'taxi', 'state': 'draft',
        'version': 1, 'vehicleFields': VehicleData.empty.toJson(), 'missingVehicleFields': VehicleData.empty.missing,
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

Future<(LoginFlow, Gateway, VehiclePhotoFlow)> ready() async {
  final session = grant();
  final store = MemoryStore();
  await store.write(LoginSnapshot(randomLoginId(), session: session));
  final gateway = Gateway(session);
  final login = LoginFlow(gateway, store);
  await login.initialize();
  await login.refreshApplication();
  final photos = VehiclePhotoFlow(login, gateway);
  await photos.refresh();
  return (login, gateway, photos);
}

void main() {
  test('Six vues manquantes puis reçues ; type reconnu par la signature', () async {
    final (login, gateway, photos) = await ready();
    expect(photos.views!.map((v) => v.view), vehiclePhotoViews);
    expect(photos.views!.every((v) => !v.received), isTrue);
    await photos.upload('PH01', jpeg);
    await photos.upload('PH02', png);
    expect(gateway.uploads.map((u) => u.type), ['image/jpeg', 'image/png']);
    expect(photos.views!.where((v) => v.received).map((v) => v.view), ['PH01', 'PH02']);
    expect(photos.failure, isNull);
    photos.dispose();
    login.dispose();
  });

  test('Formats et tailles refusés avant tout envoi', () async {
    final (login, gateway, photos) = await ready();
    await photos.upload('PH01', utf8.encode('GIF89a synthetic'));
    expect(photos.failure, PhotoFailure.unsupported);
    await photos.upload('PH01', [...jpeg, ...List.filled(maxPhotoBytes, 0)]);
    expect(photos.failure, PhotoFailure.tooLarge);
    await photos.upload('PH07', jpeg);
    expect(photos.failure, PhotoFailure.invalid);
    expect(gateway.uploads, isEmpty);
    photos.dispose();
    login.dispose();
  });

  test('Réponse perdue : même clé au nouvel essai, aucune double photo', () async {
    final (login, gateway, photos) = await ready();
    gateway.answers.add(#lose);
    await photos.upload('PH03', jpeg);
    expect(photos.failure, PhotoFailure.uncertain);
    expect(photos.hasPending('PH03'), isTrue);
    await login.refreshApplication();
    expect(photos.hasPending('PH03'), isTrue, reason: 'une relecture du dossier ne perd pas la reprise');
    await photos.retry('PH03');
    expect(gateway.uploads.map((u) => u.key).toSet(), hasLength(1));
    expect(gateway.appliedKeys, hasLength(1));
    expect(photos.hasPending('PH03'), isFalse);
    expect(photos.views!.firstWhere((v) => v.view == 'PH03').received, isTrue);
    await photos.upload('PH03', jpeg);
    expect(gateway.uploads.map((u) => u.key).toSet(), hasLength(2), reason: 'un nouveau choix a une nouvelle clé');
    photos.dispose();
    login.dispose();
  });

  for (final failure in [PhotoFailure.rejectedFile, PhotoFailure.keyReused, PhotoFailure.notEditable, PhotoFailure.tooLarge]) {
    test('Refus définitif ${failure.name} : pas de reprise proposée', () async {
      final (login, gateway, photos) = await ready();
      gateway.answers.add(PhotoProblem(failure));
      await photos.upload('PH04', jpeg);
      expect(photos.failure, failure);
      expect(photos.hasPending('PH04'), isFalse);
      photos.dispose();
      login.dispose();
    });
  }

  test('Perte de confirmation ou déconnexion : photos et reprises effacées', () async {
    final (login, gateway, photos) = await ready();
    gateway.answers.add(#lose);
    await photos.upload('PH05', jpeg);
    expect(photos.hasPending('PH05'), isTrue);
    gateway.accountFailure = LoginFailure.unavailable;
    await login.checkAccount();
    expect(photos.views, isNull);
    expect(photos.hasPending('PH05'), isFalse);
    gateway.accountFailure = null;
    await login.checkAccount();
    await login.refreshApplication();
    await photos.refresh();
    expect(photos.views, isNotNull);
    await login.revoke();
    expect(photos.views, isNull);
    photos.dispose();
    login.dispose();
  });

  test('Fermeture pendant un envoi : aucune notification après dispose', () async {
    final (login, gateway, photos) = await ready();
    gateway.gate = Completer<void>();
    final sending = photos.upload('PH06', jpeg);
    await Future<void>.delayed(Duration.zero);
    photos.dispose();
    gateway.gate!.complete();
    await sending;
    login.dispose();
  });

  group('Client HTTP photos', () {
    late HttpServer server;
    late LoginClient client;
    final session = grant();
    final app = randomLoginId();
    late Future<void> Function(HttpRequest) handler;
    final seen = <(String, String, String?, int)>[];

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((request) async {
        final body = await request.fold<List<int>>([], (all, chunk) => all..addAll(chunk));
        seen.add((request.method, request.uri.path, request.headers.contentType?.mimeType, body.length));
        await handler(request);
      });
      client = LoginClient(LocalApiConfiguration.parse(
        environment: 'dev', baseUrl: 'http://127.0.0.1:${server.port}', releaseMode: false));
    });
    tearDown(() async {
      client.dispose();
      await server.close(force: true);
      seen.clear();
    });

    Future<void> reply(HttpRequest r, int status, Object? body, {String type = 'application/json'}) async {
      r.response.statusCode = status;
      r.response.headers.contentType = ContentType.parse(type);
      r.response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
      r.response.write(jsonEncode(body));
      await r.response.close();
    }

    test('Envoi brut avec type exact et clé ; réponse vérifiée', () async {
      final file = randomLoginId();
      handler = (r) => reply(r, 201, {
        'fileId': file, 'applicationId': app, 'view': 'PH02', 'state': 'received', 'width': 640, 'height': 480,
        'byteSize': 900, 'createdAt': now(), 'idempotentReplay': false,
      });
      final result = await client.uploadPhoto(session, 'c' * 20, app, 'PH02', randomLoginId(), jpeg, 'image/jpeg');
      expect(result.fileId, file);
      expect(seen.single, ('POST', '/api/v1/driver-applications/$app/photos/PH02', 'image/jpeg', jpeg.length));
      expect(() => client.uploadPhoto(session, 'c' * 20, app, 'PH02', randomLoginId(), jpeg, 'image/png'),
          throwsA(isA<PhotoProblem>()));
    });

    test('Codes de refus et réponses non fiables', () async {
      Future<PhotoFailure> failure() async {
        try {
          await client.uploadPhoto(session, 'c' * 20, app, 'PH01', randomLoginId(), jpeg, 'image/jpeg');
          return PhotoFailure.invalid;
        } on PhotoProblem catch (p) {
          return p.failure;
        }
      }
      for (final (status, code, expected) in [
        (400, 'FILE_REJECTED', PhotoFailure.rejectedFile),
        (409, 'IDEMPOTENCY_KEY_REUSED', PhotoFailure.keyReused),
        (409, 'NOT_EDITABLE', PhotoFailure.notEditable),
        (413, 'FILE_TOO_LARGE', PhotoFailure.tooLarge),
        (415, 'CONTENT_TYPE_UNSUPPORTED', PhotoFailure.unsupported),
        (404, 'RESOURCE_NOT_FOUND', PhotoFailure.notFound),
        (401, 'AUTHENTICATION_REQUIRED', PhotoFailure.denied),
        (503, 'SERVICE_UNAVAILABLE', PhotoFailure.unavailable),
      ]) {
        handler = (r) => reply(r, status, {'status': status, 'code': code}, type: 'application/problem+json');
        expect(await failure(), expected, reason: '$status $code');
      }
      handler = (r) => reply(r, 201, {'fileId': randomLoginId(), 'applicationId': randomLoginId(), 'view': 'PH01',
        'state': 'received', 'width': 640, 'height': 480, 'byteSize': 9, 'createdAt': now(), 'idempotentReplay': false});
      expect(await failure(), PhotoFailure.invalidResponse, reason: 'autre dossier');
      await server.close(force: true);
      expect(await failure(), PhotoFailure.uncertain);
    });

    test('Liste stricte des six vues', () {
      final valid = [for (final v in vehiclePhotoViews) {'view': v, 'state': 'missing', 'fileId': null, 'createdAt': null}];
      expect(PhotoViewStatus.parseList(valid), hasLength(6));
      for (final bad in [
        valid.sublist(1),
        [...valid.reversed],
        [{...valid.first, 'state': 'approved'}, ...valid.sublist(1)],
        [{...valid.first, 'fileId': randomLoginId()}, ...valid.sublist(1)],
        [{...valid.first, 'state': 'received'}, ...valid.sublist(1)],
      ]) {
        expect(() => PhotoViewStatus.parseList(bad), throwsA(isA<PhotoProblem>()));
      }
    });
  });
}
