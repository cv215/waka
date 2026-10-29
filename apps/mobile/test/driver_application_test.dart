import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/driver_application_models.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/profile_models.dart';

LoginSession grant() => LoginSession(
  randomLoginId(),
  randomLoginId(),
  base64Url.encode(List.filled(32, 9)).replaceAll('=', ''),
  DateTime.now().toUtc().add(const Duration(hours: 1)),
  DateTime.now().toUtc().add(const Duration(minutes: 10)),
);
String now() => DateTime.now().toUtc().toIso8601String();
const car = VehicleData(
  brand: 'Marque', model: 'Modele', year: 2020, color: 'Bleu', registration: 'AB 12-CD',
  authorizedTotalCapacity: 5, offeredPassengerSeats: 4, usageRight: 'holder',
);

Map<String, Object?> applicationJson(
  LoginSession session, {
  required String id,
  String category = 'taxi',
  int version = 1,
  VehicleData vehicle = VehicleData.empty,
  bool replay = false,
  String state = 'draft',
}) => {
  'applicationId': id,
  'accountId': session.accountId,
  'requestedCategory': category,
  'state': state,
  'version': version,
  'vehicleFields': vehicle.toJson(),
  'missingVehicleFields': vehicle.missing,
  'createdAt': now(),
  'updatedAt': now(),
  'serverTime': now(),
  'idempotentReplay': replay,
};

class MemoryStore implements LoginStore {
  String? raw;
  bool failWrites = false;
  @override
  Future<LoginSnapshot?> read() async => raw == null ? null : LoginSnapshot.parse(raw!);
  @override
  Future<void> write(LoginSnapshot value) async {
    if (failWrites) throw const LoginProblem(LoginFailure.storage);
    raw = jsonEncode(value.toJson());
  }
  Map<String, dynamic> get decoded => jsonDecode(raw!) as Map<String, dynamic>;
  ApplicationCommand? get command {
    final map = decoded['applicationCommand'];
    return map == null ? null : ApplicationCommand.parse(map);
  }
}

/// A server model: one open draft per account, versions, and queued failures.
class Gateway implements LoginGateway, DriverApplicationGateway, ProfileGateway {
  Gateway(this.session);
  final LoginSession session;
  String? id, category;
  String state = 'draft';
  int version = 0;
  VehicleData vehicle = VehicleData.empty;
  final sent = <ApplicationCommand>[];
  final answers = <Object>[];
  final applied = <String>{};
  int reads = 0, disposed = 0;
  LoginFailure? accountFailure;
  /// When false, a submission is refused as the server would with missing pieces.
  bool complete = true;

  Map<String, Object?> _json(LoginSession s, {bool replay = false}) =>
      applicationJson(s, id: id!, category: category!, version: version, vehicle: vehicle, replay: replay, state: state);

  @override
  Future<DriverApplicationSnapshot?> currentApplication(LoginSession s, String client) async {
    reads++;
    final answer = answers.isNotEmpty && answers.first is ApplicationProblem ? answers.removeAt(0) : null;
    if (answer != null) throw answer;
    return id == null ? null : DriverApplicationSnapshot.parse(_json(s), s);
  }

  @override
  Future<DriverApplicationSnapshot> sendApplication(LoginSession s, String client, ApplicationCommand command) async {
    sent.add(command);
    final answer = answers.isEmpty ? null : answers.removeAt(0);
    if (answer is ApplicationProblem) throw answer;
    final replay = applied.contains(command.key);
    if (!replay) {
      switch (command) {
        case CreateApplicationCommand c:
          if (id != null) throw const ApplicationProblem(ApplicationFailure.alreadyOpen);
          id = randomLoginId(); category = c.requestedCategory; version = 1;
        case VehicleApplicationCommand c:
          if (state != 'draft' && state != 'correction_requested') throw const ApplicationProblem(ApplicationFailure.notEditable);
          if (c.expectedVersion != version) throw const ApplicationProblem(ApplicationFailure.versionConflict);
          version++; vehicle = c.vehicle;
        case SubmitApplicationCommand c:
          if (state != 'draft' && state != 'correction_requested') throw const ApplicationProblem(ApplicationFailure.notEditable);
          if (c.expectedVersion != version) throw const ApplicationProblem(ApplicationFailure.versionConflict);
          if (!complete) throw const ApplicationProblem(ApplicationFailure.incomplete);
          version++; state = 'submitted';
      }
      applied.add(command.key);
    }
    if (answer == #lose) throw const ApplicationProblem(ApplicationFailure.uncertain);
    return DriverApplicationSnapshot.parse(_json(s, replay: replay), s, command: command);
  }

  @override
  Future<LoginAccount> me(LoginSession s, String client) async {
    if (accountFailure != null) throw LoginProblem(accountFailure!);
    return LoginAccount.parse({
      'accountId': s.accountId, 'sessionId': s.id,
      'absoluteExpiresAt': s.absoluteExpiresAt.toIso8601String(),
      'idleExpiresAt': s.idleExpiresAt.toIso8601String(),
      'accountState': 'ACTIVE', 'roles': ['CLIENT'], 'serverTime': now(),
    }, s);
  }
  String? profileName;
  int profileVersion = 1;
  @override
  Future<ProfileSnapshot> profile(LoginSession s, String client) async => ProfileSnapshot.parse({
    'accountId': s.accountId, 'version': profileVersion, 'displayName': profileName,
    'updatedAt': profileVersion == 1 ? null : now(), 'serverTime': now(), 'idempotentReplay': false,
  }, s);
  @override
  Future<ProfileSnapshot> updateProfile(LoginSession s, String client, ProfileCommand command) async {
    profileVersion = command.expectedVersion + 1; profileName = command.displayName;
    return profile(s, client);
  }
  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async => throw const LoginProblem(LoginFailure.invalid);
  @override
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code) async => session;
  @override
  Future<void> revoke(LoginSession s, String client, String key) async {}
  @override
  void dispose() => disposed++;
}

Future<(LoginFlow, Gateway, MemoryStore)> confirmed() async {
  final session = grant();
  final store = MemoryStore();
  await store.write(LoginSnapshot(randomLoginId(), session: session));
  final gateway = Gateway(session);
  final flow = LoginFlow(gateway, store);
  await flow.initialize();
  await flow.refreshApplication();
  return (flow, gateway, store);
}

void main() {
  group('Flux de candidature', () {
    test('Aucun dossier, puis brouillon taxi sans admission', () async {
      final (flow, gateway, store) = await confirmed();
      expect(flow.noApplication, isTrue);
      expect(flow.canStartApplication, isTrue);
      await flow.startApplication('taxi');
      expect(flow.application!.requestedCategory, 'taxi');
      expect(flow.application!.version, 1);
      expect(flow.application!.missing, vehicleFieldNames);
      expect(store.command, isNull);
      expect(flow.canStartApplication, isFalse);
      expect(flow.canEditVehicle, isTrue);
      flow.dispose();
    });

    test('Création perdue : même clé après revalidation et redémarrage, un seul dossier', () async {
      final (flow, gateway, store) = await confirmed();
      gateway.answers.add(#lose);
      await flow.startApplication('private');
      expect(flow.applicationFailure, ApplicationFailure.uncertain);
      final kept = store.command!;
      expect(kept, isA<CreateApplicationCommand>());
      await flow.startApplication('taxi');
      expect(flow.applicationFailure, ApplicationFailure.invalid);
      await flow.checkAccount();
      expect(store.command!.key, kept.key);
      flow.dispose();
      final restored = LoginFlow(gateway, store);
      await restored.initialize();
      expect(restored.pendingApplication!.key, kept.key);
      await restored.resumeApplication();
      expect(gateway.sent.map((c) => c.key).toSet(), {kept.key});
      expect(store.command, isNull);
      expect(restored.application!.requestedCategory, 'private');
      expect(gateway.applied, hasLength(1));
      restored.dispose();
    });

    test('Dossier déjà ouvert : commande oubliée et dossier existant relu', () async {
      final (flow, gateway, store) = await confirmed();
      gateway..id = randomLoginId()..category = 'taxi'..version = 1;
      gateway.answers.add(const ApplicationProblem(ApplicationFailure.uncertain));
      await flow.startApplication('private');
      await flow.resumeApplication();
      expect(flow.applicationFailure, ApplicationFailure.alreadyOpen);
      expect(store.command, isNull);
      expect(flow.application!.applicationId, gateway.id);
      flow.dispose();
    });

    test('Véhicule enregistré puis relu ; immatriculation mise en forme', () async {
      final (flow, gateway, store) = await confirmed();
      await flow.startApplication('taxi');
      await flow.saveVehicle(const VehicleData(
        brand: ' Marque ', model: 'Modele', year: 2020, color: 'Bleu', registration: ' ab 12-cd ',
        authorizedTotalCapacity: 5, offeredPassengerSeats: 4, usageRight: 'holder',
      ));
      expect(flow.applicationFailure, isNull);
      expect(flow.application!.version, 2);
      expect(flow.application!.vehicle.registration, 'AB 12-CD');
      expect(flow.application!.vehicle.brand, 'Marque');
      expect(flow.application!.missing, isEmpty);
      expect(store.command, isNull);
      flow.dispose();
    });

    test('Capacité et places refusées localement, sans envoi', () async {
      final (flow, gateway, _) = await confirmed();
      await flow.startApplication('taxi');
      final before = gateway.sent.length;
      for (final bad in [
        const VehicleData(authorizedTotalCapacity: 5, offeredPassengerSeats: 5),
        const VehicleData(authorizedTotalCapacity: 7, offeredPassengerSeats: 6),
        const VehicleData(offeredPassengerSeats: 0),
        const VehicleData(authorizedTotalCapacity: 1),
        const VehicleData(registration: 'A'),
        const VehicleData(registration: 'AB_12'),
        const VehicleData(year: 1899),
        const VehicleData(usageRight: 'owner'),
        VehicleData(brand: 'A${String.fromCharCode(0x202e)}B'),
        VehicleData(model: 'M' * 41),
      ]) {
        await flow.saveVehicle(bad);
        expect(flow.applicationFailure, ApplicationFailure.invalid);
      }
      expect(gateway.sent.length, before);
      await flow.saveVehicle(const VehicleData(authorizedTotalCapacity: 7, offeredPassengerSeats: 5));
      expect(flow.application!.vehicle.offeredPassengerSeats, 5);
      flow.dispose();
    });

    for (final failure in [
      ApplicationFailure.versionConflict,
      ApplicationFailure.keyReused,
      ApplicationFailure.replayExpired,
      ApplicationFailure.notFound,
      ApplicationFailure.rejected,
    ]) {
      test('Refus définitif ${failure.name} : commande oubliée et dossier relu', () async {
        final (flow, gateway, store) = await confirmed();
        await flow.startApplication('taxi');
        gateway.answers.add(const ApplicationProblem(ApplicationFailure.uncertain));
        await flow.saveVehicle(car);
        expect(store.command, isA<VehicleApplicationCommand>());
        gateway.answers.add(ApplicationProblem(failure));
        await flow.resumeApplication();
        expect(flow.applicationFailure, failure);
        expect(store.command, isNull);
        expect(flow.application, isNotNull);
        expect(flow.canEditVehicle, isTrue);
        flow.dispose();
      });
    }

    test('Stockage refusé avant envoi : aucune demande', () async {
      final (flow, gateway, store) = await confirmed();
      store.failWrites = true;
      await flow.startApplication('taxi');
      expect(flow.applicationFailure, ApplicationFailure.storage);
      expect(gateway.sent, isEmpty);
      flow.dispose();
    });

    test('Le profil enregistré n’efface pas une demande de candidature en attente', () async {
      final (flow, gateway, store) = await confirmed();
      gateway.answers.add(#lose);
      await flow.startApplication('taxi');
      final key = store.command!.key;
      await flow.saveDisplayName('Alpha');
      expect(flow.profile!.displayName, 'Alpha');
      expect(store.command!.key, key);
      expect(store.decoded['profileCommand'], isNull);
      flow.dispose();
    });

    test('Refus de session ou déconnexion retirent dossier et commande', () async {
      final (flow, gateway, store) = await confirmed();
      await flow.startApplication('taxi');
      gateway.answers.add(const ApplicationProblem(ApplicationFailure.denied));
      await flow.refreshApplication();
      expect(flow.session, isNull);
      expect(flow.application, isNull);
      expect(store.decoded['applicationCommand'], isNull);
      flow.dispose();

      final (other, otherGateway, otherStore) = await confirmed();
      otherGateway.answers.add(#lose);
      await other.startApplication('private');
      await other.revoke();
      expect(other.application, isNull);
      expect(other.pendingApplication, isNull);
      expect(otherStore.decoded['applicationCommand'], isNull);
      other.dispose();
    });

    test('Une perte de confirmation masque le dossier', () async {
      final (flow, gateway, _) = await confirmed();
      await flow.startApplication('taxi');
      gateway.accountFailure = LoginFailure.unavailable;
      await flow.checkAccount();
      expect(flow.application, isNull);
      expect(flow.canEditVehicle, isFalse);
      flow.dispose();
    });

    test('Envoi : version exacte, plus aucune modification pendant l’examen', () async {
      final (flow, gateway, store) = await confirmed();
      await flow.startApplication('private');
      await flow.saveVehicle(car);
      await flow.submitApplication();
      expect(flow.applicationFailure, isNull);
      expect(flow.application!.state, 'submitted');
      expect(flow.application!.editable, isFalse);
      expect(flow.canEditVehicle, isFalse);
      expect((gateway.sent.last as SubmitApplicationCommand).expectedVersion, 2);
      expect(store.command, isNull);
      await flow.saveVehicle(car);
      expect(flow.applicationFailure, ApplicationFailure.invalid, reason: 'rien n’est envoyé pendant l’examen');
      expect(gateway.sent.whereType<VehicleApplicationCommand>(), hasLength(1));
      flow.dispose();
    });

    test('Envoi incomplet refusé définitivement ; envoi perdu repris avec la même clé', () async {
      final (flow, gateway, store) = await confirmed();
      await flow.startApplication('taxi');
      gateway.complete = false;
      await flow.submitApplication();
      expect(flow.applicationFailure, ApplicationFailure.incomplete);
      expect(store.command, isNull, reason: 'un refus définitif oublie la commande');
      expect(flow.application!.state, 'draft');
      gateway.complete = true;
      gateway.answers.add(#lose);
      await flow.submitApplication();
      expect(flow.applicationFailure, ApplicationFailure.uncertain);
      final kept = store.command!;
      expect(kept, isA<SubmitApplicationCommand>());
      await flow.resumeApplication();
      expect(gateway.sent.whereType<SubmitApplicationCommand>().map((c) => c.key).toSet(), hasLength(2));
      expect(gateway.sent.last.key, kept.key);
      expect(flow.application!.state, 'submitted');
      flow.dispose();
    });

    test('Correction demandée : le dossier redevient modifiable', () async {
      final (flow, gateway, _) = await confirmed();
      await flow.startApplication('taxi');
      gateway..state = 'correction_requested'..version = 3;
      await flow.refreshApplication();
      expect(flow.application!.editable, isTrue);
      expect(flow.canEditVehicle, isTrue);
      gateway.state = 'approved';
      await flow.refreshApplication();
      expect(flow.canEditVehicle, isFalse);
      flow.dispose();
    });
  });

  group('Modèles et stockage', () {
    final session = grant();
    final id = randomLoginId();
    void refused(Map<String, Object?> value, {ApplicationCommand? command}) => expect(
      () => DriverApplicationSnapshot.parse(value, session, command: command),
      throwsA(isA<ApplicationProblem>().having((p) => p.failure, 'f', ApplicationFailure.invalidResponse)),
    );

    test('Réponse stricte : états connus seulement, propre compte, champs cohérents', () {
      final valid = applicationJson(session, id: id);
      expect(DriverApplicationSnapshot.parse(valid, session).version, 1);
      refused({...valid, 'state': 'admitted'});
      refused({...valid, 'state': 'APPROVED'});
      expect(DriverApplicationSnapshot.parse({...valid, 'state': 'approved'}, session).editable, isFalse);
      // A creation can only answer with a draft.
      refused({...valid, 'state': 'submitted'}, command: CreateApplicationCommand(randomLoginId(), session.accountId, 'taxi'));
      refused({...valid, 'accountId': randomLoginId()});
      refused({...valid, 'requestedCategory': 'vip'});
      refused({...valid, 'role': 'DRIVER'});
      refused({...valid, 'missingVehicleFields': <String>[]});
      refused({...valid, 'idempotentReplay': true});
      refused({...valid, 'version': 0});
      refused({...valid, 'vehicleFields': {...VehicleData.empty.toJson(), 'authorizedTotalCapacity': 5, 'offeredPassengerSeats': 5}});
      refused({...valid, 'vehicleFields': {...VehicleData.empty.toJson(), 'year': '2020'}});
      refused({...valid, 'vehicleFields': {...VehicleData.empty.toJson(), 'vin': 'X'}});
      refused({...valid, 'updatedAt': '2999-01-01T00:00:00Z'});
      final patch = VehicleApplicationCommand(randomLoginId(), session.accountId, id, 3, car);
      refused(applicationJson(session, id: id, version: 3, vehicle: car), command: patch);
      refused(applicationJson(session, id: randomLoginId(), version: 4, vehicle: car), command: patch);
      expect(DriverApplicationSnapshot.parse(applicationJson(session, id: id, version: 4, vehicle: car, replay: true),
          session, command: patch).replay, isTrue);
      final create = CreateApplicationCommand(randomLoginId(), session.accountId, 'private');
      refused(applicationJson(session, id: id), command: create);
    });

    test('Commande stockée : aller-retour et altérations refusées comme stockage', () {
      final patch = VehicleApplicationCommand(randomLoginId(), session.accountId, id, 2, car);
      final back = ApplicationCommand.parse(jsonDecode(jsonEncode(patch.toJson())));
      expect(back, isA<VehicleApplicationCommand>());
      expect((back as VehicleApplicationCommand).vehicle, car);
      for (final altered in [
        {...patch.toJson(), 'kind': 'approve'},
        {...patch.toJson(), 'expectedVersion': 0},
        {...patch.toJson(), 'applicationId': 'x'},
        {...patch.toJson(), 'vehicleFields': {...car.toJson(), 'offeredPassengerSeats': 6}},
        {...CreateApplicationCommand(randomLoginId(), session.accountId, 'taxi').toJson(), 'requestedCategory': 'vip'},
      ]) {
        expect(() => ApplicationCommand.parse(altered),
            throwsA(isA<ApplicationProblem>().having((p) => p.failure, 'f', ApplicationFailure.storage)));
      }
    });

    test('Formats v2 et v3 relus ; v3 exige une session pour une commande', () {
      final client = randomLoginId();
      final v2 = LoginSnapshot.parse(jsonEncode({
        'version': 2, 'client': client, 'attempt': null, 'session': session.toJson(), 'logoutKey': null, 'profileCommand': null,
      }));
      expect(v2.session!.id, session.id);
      expect(v2.toJson()['version'], 3);
      final command = CreateApplicationCommand(randomLoginId(), session.accountId, 'taxi');
      final v3 = LoginSnapshot(client, session: session, applicationCommand: command);
      expect(LoginSnapshot.parse(jsonEncode(v3.toJson())).applicationCommand!.key, command.key);
      expect(() => LoginSnapshot.parse(jsonEncode({...LoginSnapshot(client).toJson(), 'applicationCommand': command.toJson()})),
          throwsA(isA<LoginProblem>()));
      expect(() => LoginSnapshot.parse(jsonEncode({...v2.toJson(), 'version': 2})), throwsA(isA<LoginProblem>()));
    });
  });

  group('Client HTTP candidature', () {
    late HttpServer server;
    late LoginClient client;
    final session = grant();
    final id = randomLoginId();
    late Future<void> Function(HttpRequest) handler;
    final seen = <HttpRequest>[];

    setUp(() async {
      server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      server.listen((request) async {
        seen.add(request);
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

    Future<void> reply(HttpRequest request, int status, Object? body, {String type = 'application/json', bool store = false}) async {
      request.response.statusCode = status;
      request.response.headers.contentType = ContentType.parse(type);
      if (!store) request.response.headers.set(HttpHeaders.cacheControlHeader, 'no-store');
      request.response.write(jsonEncode(body));
      await request.response.close();
    }

    test('404 de lecture : aucun dossier ; création 201 vérifiée avec clé et preuves', () async {
      handler = (r) => reply(r, 404, {'status': 404, 'code': 'RESOURCE_NOT_FOUND'}, type: 'application/problem+json');
      expect(await client.currentApplication(session, 'c' * 20), isNull);
      handler = (r) async {
        final body = jsonDecode(await utf8.decoder.bind(r).join());
        expect(body, {'requestedCategory': 'private'});
        await reply(r, 201, applicationJson(session, id: id, category: 'private'));
      };
      final command = CreateApplicationCommand(randomLoginId(), session.accountId, 'private');
      final created = await client.sendApplication(session, 'c' * 20, command);
      expect(created.applicationId, id);
      final last = seen.last;
      expect(last.method, 'POST');
      expect(last.uri.path, '/api/v1/driver-applications');
      expect(last.headers.value('Idempotency-Key'), command.key);
      expect(last.headers.value('Authorization'), 'Bearer ${session.token}');
    });

    test('Codes de refus distincts et réponses non fiables', () async {
      final patch = VehicleApplicationCommand(randomLoginId(), session.accountId, id, 1, car);
      Future<ApplicationFailure> failure() async {
        try {
          await client.sendApplication(session, 'c' * 20, patch);
          return ApplicationFailure.invalid;
        } on ApplicationProblem catch (p) {
          return p.failure;
        }
      }
      final cases = <(int, String?, ApplicationFailure)>[
        (409, 'VERSION_CONFLICT', ApplicationFailure.versionConflict),
        (409, 'IDEMPOTENCY_KEY_REUSED', ApplicationFailure.keyReused),
        (409, 'APPLICATION_ALREADY_OPEN', ApplicationFailure.alreadyOpen),
        (409, 'OTHER', ApplicationFailure.invalidResponse),
        (410, 'REPLAY_EXPIRED', ApplicationFailure.replayExpired),
        (404, 'RESOURCE_NOT_FOUND', ApplicationFailure.notFound),
        (400, 'INVALID_REQUEST', ApplicationFailure.rejected),
        (401, 'AUTHENTICATION_REQUIRED', ApplicationFailure.denied),
        (503, 'SERVICE_UNAVAILABLE', ApplicationFailure.unavailable),
        (302, null, ApplicationFailure.invalidResponse),
      ];
      for (final (status, code, expected) in cases) {
        handler = (r) => reply(r, status, {'status': status, 'code': code}, type: 'application/problem+json');
        expect(await failure(), expected, reason: '$status $code');
      }
      handler = (r) => reply(r, 200, applicationJson(session, id: id, version: 2, vehicle: car), store: true);
      expect(await failure(), ApplicationFailure.invalidResponse);
      handler = (r) => reply(r, 200, applicationJson(session, id: id, version: 2, vehicle: car));
      expect((await client.sendApplication(session, 'c' * 20, patch)).version, 2);
      expect(seen.last.method, 'PATCH');
      expect(seen.last.uri.path, '/api/v1/driver-applications/$id');
      await server.close(force: true);
      expect(await failure(), ApplicationFailure.uncertain);
    });
  });
}
