import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/drivers/application/vehicle_photo_flow.dart';
import 'package:waka_mobile/features/drivers/presentation/driver_application_page.dart';
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
  testWidgets('L’écran affiche les six vues et envoie la photo choisie', (tester) async {
    final (login, gateway, _) = await tester.runAsync(ready) as (LoginFlow, Gateway, VehiclePhotoFlow);
    await tester.pumpWidget(MaterialApp(home: DriverApplicationPage(
      flow: login,
      pickPhoto: ({required bool camera}) async => camera ? jpeg : png,
    )));
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('PH06 — Habitacle arrière'), 300, scrollable: find.byType(Scrollable).first);
    expect(find.text('PH06 — Habitacle arrière'), findsOneWidget);
    expect(find.text('Manquante'), findsNWidgets(6));
    await tester.tap(find.byTooltip('Prendre une photo').first);
    await tester.runAsync(() => Future<void>.delayed(const Duration(milliseconds: 50)));
    await tester.pumpAndSettle();
    expect(gateway.uploads.single.view, 'PH01');
    expect(find.text('Reçue, non examinée'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
    login.dispose();
  });

}
