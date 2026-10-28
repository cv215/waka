import 'dart:async';
import 'dart:convert';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/data/profile_models.dart';

LoginSession grant([String? accountId]) => LoginSession(
  randomLoginId(),
  accountId ?? randomLoginId(),
  base64Url.encode(List.filled(32, 7)).replaceAll('=', ''),
  DateTime.now().toUtc().add(const Duration(hours: 1)),
  DateTime.now().toUtc().add(const Duration(minutes: 10)),
);

String now() => DateTime.now().toUtc().toIso8601String();

/// Builds invisible or direction characters without hiding them in the source.
String cp(int code) => String.fromCharCode(code);

Map<String, Object?> profileJson(
  LoginSession session, {
  int version = 1,
  String? name,
  bool replay = false,
}) => {
  'accountId': session.accountId,
  'version': version,
  'displayName': name,
  'updatedAt': version == 1 ? null : now(),
  'serverTime': now(),
  'idempotentReplay': replay,
};

class MemoryStore implements LoginStore {
  String? raw;
  bool failWrites = false;
  bool Function(LoginSnapshot value)? failWhen;
  @override
  Future<LoginSnapshot?> read() async =>
      raw == null ? null : LoginSnapshot.parse(raw!);
  @override
  Future<void> write(LoginSnapshot value) async {
    if (failWrites || (failWhen?.call(value) ?? false)) {
      throw const LoginProblem(LoginFailure.storage);
    }
    raw = jsonEncode(value.toJson());
  }

  Map<String, dynamic> get decoded => jsonDecode(raw!) as Map<String, dynamic>;
}

/// One fake for both gateways; profile answers are queued to describe a server sequence.
class Gateway implements LoginGateway, ProfileGateway {
  Gateway(this.session);
  LoginSession session;
  LoginFailure? accountFailure;
  final reads = <String>[];
  final patches = <ProfileCommand>[];
  final readAnswers = <Object>[];
  final patchAnswers = <Object>[];
  int disposed = 0;
  String? name;
  int version = 1;

  @override
  Future<LoginAccount> me(LoginSession sent, String client) async {
    if (accountFailure != null) throw LoginProblem(accountFailure!);
    return LoginAccount.parse({
      'accountId': sent.accountId,
      'sessionId': sent.id,
      'absoluteExpiresAt': sent.absoluteExpiresAt.toIso8601String(),
      'idleExpiresAt': sent.idleExpiresAt.toIso8601String(),
      'accountState': 'ACTIVE',
      'roles': ['CLIENT'],
      'serverTime': now(),
    }, sent);
  }

  @override
  Future<ProfileSnapshot> profile(LoginSession sent, String client) async {
    reads.add(sent.accountId);
    final answer = readAnswers.isEmpty ? null : readAnswers.removeAt(0);
    if (answer is ProfileProblem) throw answer;
    if (answer is Completer<ProfileSnapshot>) return answer.future;
    return ProfileSnapshot.parse(profileJson(sent, version: version, name: name), sent);
  }

  @override
  Future<ProfileSnapshot> updateProfile(LoginSession sent, String client, ProfileCommand command) async {
    patches.add(command);
    final answer = patchAnswers.isEmpty ? null : patchAnswers.removeAt(0);
    if (answer == #applyThenLose) {
      _apply(command);
      throw const ProfileProblem(ProfileFailure.uncertain);
    }
    if (answer is ProfileProblem) throw answer;
    _apply(command);
    return ProfileSnapshot.parse(
      profileJson(sent, version: command.expectedVersion + 1, name: command.displayName),
      sent,
      expectedVersion: command.expectedVersion,
    );
  }

  void _apply(ProfileCommand command) {
    version = command.expectedVersion + 1;
    name = command.displayName;
  }

  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async =>
      throw const LoginProblem(LoginFailure.invalid);
  @override
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code) async => session;
  @override
  Future<void> revoke(LoginSession sent, String client, String key) async {}
  @override
  void dispose() => disposed++;
}

class SeparateProfileGateway implements ProfileGateway {
  int disposed = 0;
  @override
  Future<ProfileSnapshot> profile(LoginSession session, String client) async =>
      ProfileSnapshot.parse(profileJson(session), session);
  @override
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command) =>
      throw const ProfileProblem(ProfileFailure.unavailable);
  @override
  void dispose() => disposed++;
}

Future<(LoginFlow, Gateway, MemoryStore)> confirmed({LoginSession? session}) async {
  final restored = session ?? grant();
  final store = MemoryStore();
  await store.write(LoginSnapshot(randomLoginId(), session: restored));
  final gateway = Gateway(restored);
  final flow = LoginFlow(gateway, store);
  await flow.initialize();
  return (flow, gateway, store);
}

ProfileCommand? storedCommand(MemoryStore store) {
  final map = store.decoded['profileCommand'];
  return map == null ? null : ProfileCommand.parse(map);
}

void main() {
  test('Le profil initial est relu après confirmation, sans nom', () async {
    final (flow, gateway, _) = await confirmed();
    expect(flow.confirmedNow, isTrue);
    expect(flow.profile!.version, 1);
    expect(flow.profile!.displayName, isNull);
    expect(flow.canEditProfile, isTrue);
    expect(gateway.reads, hasLength(1));
    flow.dispose();
  });

  test('Une réponse perdue garde la commande ; la revalidation /me la conserve', () async {
    final (flow, gateway, store) = await confirmed();
    gateway.patchAnswers.add(#applyThenLose);
    await flow.saveDisplayName('  Alpha ');
    expect(flow.profileFailure, ProfileFailure.uncertain);
    final kept = storedCommand(store)!;
    expect(kept.displayName, 'Alpha');
    expect(flow.pendingProfile, isNotNull);
    expect(flow.canEditProfile, isFalse);

    // Aucune nouvelle intention tant que l'issue n'est pas connue.
    await flow.saveDisplayName('Beta');
    expect(flow.profileFailure, ProfileFailure.invalid);
    expect(gateway.patches, hasLength(1));

    // Revalidation du compte : le défaut corrigé perdait ici la commande.
    await flow.checkAccount();
    expect(flow.confirmedNow, isTrue);
    expect(storedCommand(store)!.key, kept.key);
    flow.dispose();

    final restored = LoginFlow(gateway, store);
    await restored.initialize();
    expect(restored.pendingProfile!.key, kept.key);
    expect(restored.canEditProfile, isFalse);
    await restored.resumeProfile();
    expect(gateway.patches.map((c) => c.key).toSet(), {kept.key});
    expect(gateway.patches.last.expectedVersion, kept.expectedVersion);
    expect(gateway.patches.last.displayName, kept.displayName);
    expect(storedCommand(store), isNull);
    expect(restored.profile!.displayName, 'Alpha');
    expect(restored.profile!.version, 2);
    expect(restored.canEditProfile, isTrue);
    restored.dispose();
  });

  test('Après succès, seul un GET établit le profil courant', () async {
    final (flow, gateway, store) = await confirmed();
    final readsBefore = gateway.reads.length;
    await flow.saveDisplayName('Alpha');
    expect(gateway.reads.length, readsBefore + 1);
    expect(storedCommand(store), isNull);
    expect(flow.profile!.replay, isFalse);
    expect(flow.profile!.version, 2);
    await flow.saveDisplayName(null);
    expect(gateway.patches.last.expectedVersion, 2);
    expect(flow.profile!.displayName, isNull);
    expect(flow.profile!.version, 3);
    flow.dispose();
  });

  for (final failure in [
    ProfileFailure.versionConflict,
    ProfileFailure.keyReused,
    ProfileFailure.replayExpired,
    ProfileFailure.rejected,
  ]) {
    test('Refus définitif ${failure.name} : commande oubliée et profil relu', () async {
      final (flow, gateway, store) = await confirmed();
      gateway.patchAnswers.add(const ProfileProblem(ProfileFailure.uncertain));
      await flow.saveDisplayName('Alpha');
      expect(storedCommand(store), isNotNull);
      gateway.patchAnswers.add(ProfileProblem(failure));
      final readsBefore = gateway.reads.length;
      await flow.resumeProfile();
      expect(flow.profileFailure, failure);
      expect(storedCommand(store), isNull);
      expect(flow.pendingProfile, isNull);
      expect(gateway.reads.length, readsBefore + 1);
      expect(flow.profile, isNotNull);
      expect(flow.canEditProfile, isTrue);
      flow.dispose();
    });
  }

  for (final failure in [
    ProfileFailure.uncertain,
    ProfileFailure.unavailable,
    ProfileFailure.invalidResponse,
  ]) {
    test('Issue inconnue ${failure.name} : la même commande reste à reprendre', () async {
      final (flow, gateway, store) = await confirmed();
      gateway.patchAnswers.addAll([ProfileProblem(failure), ProfileProblem(failure)]);
      await flow.saveDisplayName('Alpha');
      final key = storedCommand(store)!.key;
      await flow.refreshProfile();
      expect(flow.pendingProfile, isNotNull, reason: 'une lecture ne prouve pas la fin du PATCH');
      expect(flow.canEditProfile, isFalse);
      await flow.resumeProfile();
      expect(storedCommand(store)!.key, key);
      expect(gateway.patches.map((c) => c.key).toSet(), {key});
      flow.dispose();
    });
  }

  test('Stockage refusé avant envoi : aucun PATCH', () async {
    final (flow, gateway, store) = await confirmed();
    store.failWrites = true;
    await flow.saveDisplayName('Alpha');
    expect(flow.profileFailure, ProfileFailure.storage);
    expect(gateway.patches, isEmpty);
    store.failWrites = false;
    expect(storedCommand(store), isNull);
    flow.dispose();
  });

  test('Effacement refusé après succès : la commande reste et se rejoue sans nouvelle clé', () async {
    final (flow, gateway, store) = await confirmed();
    store.failWhen = (value) => value.profileCommand == null;
    await flow.saveDisplayName('Alpha');
    expect(flow.failure, LoginFailure.storage);
    final key = storedCommand(store)!.key;
    store.failWhen = null;
    await flow.resumeProfile();
    expect(gateway.patches.map((c) => c.key).toSet(), {key});
    expect(storedCommand(store), isNull);
    expect(flow.profile!.displayName, 'Alpha');
    flow.dispose();
  });

  test('Nom refusé localement s’il rendrait le stockage illisible', () async {
    final (flow, gateway, store) = await confirmed();
    for (final value in ['', '   ', 'A${cp(0x200b)}B', 'A${cp(0x202e)}B', 'A${cp(0x0a)}B', 'A' * 81]) {
      await flow.saveDisplayName(value);
      expect(flow.profileFailure, ProfileFailure.invalid);
    }
    expect(gateway.patches, isEmpty);
    expect(storedCommand(store), isNull);
    flow.dispose();
  });

  test('Fermeture pendant une lecture : aucune notification après dispose', () async {
    final (flow, gateway, _) = await confirmed();
    final pending = Completer<ProfileSnapshot>();
    gateway.readAnswers.add(pending);
    final reading = flow.refreshProfile();
    await Future<void>.delayed(Duration.zero);
    flow.dispose();
    pending.complete(ProfileSnapshot.parse(profileJson(gateway.session, version: 1), gateway.session));
    await reading;
    expect(flow.profile, isNull);
    expect(gateway.disposed, 1);
  });

  test('Un transport profil distinct est aussi fermé', () async {
    final store = MemoryStore();
    final session = grant();
    await store.write(LoginSnapshot(randomLoginId(), session: session));
    final separate = SeparateProfileGateway();
    final gateway = Gateway(session);
    final flow = LoginFlow(gateway, store, profileGateway: separate);
    await flow.initialize();
    expect(flow.profile, isNotNull);
    flow.dispose();
    expect(separate.disposed, 1);
    expect(gateway.disposed, 1);
  });

  test('Déconnexion, refus et perte de confirmation retirent le profil affiché', () async {
    final (flow, gateway, store) = await confirmed();
    gateway.name = 'Alpha';
    gateway.version = 2;
    await flow.refreshProfile();
    expect(flow.profile!.displayName, 'Alpha');

    gateway.accountFailure = LoginFailure.unavailable;
    await flow.checkAccount();
    expect(flow.confirmedNow, isFalse);
    expect(flow.profile, isNull);
    expect(flow.canEditProfile, isFalse);

    gateway.accountFailure = null;
    await flow.checkAccount();
    expect(flow.profile!.displayName, 'Alpha');
    gateway.readAnswers.add(const ProfileProblem(ProfileFailure.denied));
    await flow.refreshProfile();
    expect(flow.session, isNull);
    expect(flow.profile, isNull);
    expect(store.decoded['session'], isNull);
    flow.dispose();

    final (other, otherGateway, _) = await confirmed();
    await other.revoke();
    expect(other.session, isNull);
    expect(other.profile, isNull);
    expect(otherGateway.reads, hasLength(1));
    other.dispose();
  });

  test('Une commande d’un autre compte n’est jamais proposée', () async {
    final session = grant();
    final store = MemoryStore();
    final foreign = ProfileCommand(key: randomLoginId(), expectedVersion: 1, displayName: 'Alpha', accountId: randomLoginId());
    await store.write(LoginSnapshot(randomLoginId(), session: session, profileCommand: foreign));
    final gateway = Gateway(session);
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    expect(flow.pendingProfile, isNull);
    await flow.resumeProfile();
    expect(gateway.patches, isEmpty);
    flow.dispose();
  });

  test('Format protégé v1 relu puis réécrit au format courant (v3) sans perdre la session', () async {
    final session = grant();
    final client = randomLoginId();
    final store = MemoryStore()
      ..raw = jsonEncode({
        'version': 1,
        'client': client,
        'attempt': null,
        'session': session.toJson(),
        'logoutKey': null,
      });
    final gateway = Gateway(session);
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    expect(flow.confirmedNow, isTrue);
    expect(store.decoded['version'], 3);
    expect(store.decoded['client'], client);
    expect(store.decoded['session']['sessionId'], session.id);
    expect(() => LoginSnapshot.parse(jsonEncode({
      'version': 1, 'client': client, 'attempt': null, 'session': session.toJson(),
      'logoutKey': null, 'profileCommand': null,
    })), throwsA(isA<LoginProblem>()));
    flow.dispose();
  });

  group('Validation des réponses profil', () {
    final session = grant();
    void refused(Map<String, Object?> value, {int? expectedVersion}) => expect(
      () => ProfileSnapshot.parse(value, session, expectedVersion: expectedVersion),
      throwsA(isA<ProfileProblem>().having((p) => p.failure, 'failure', ProfileFailure.invalidResponse)),
    );

    test('bornes et cohérence de version', () {
      refused({...profileJson(session), 'version': 0});
      refused({...profileJson(session), 'version': 9007199254740992});
      refused({...profileJson(session), 'version': '1'});
      refused({...profileJson(session), 'displayName': 'Alpha'});
      refused({...profileJson(session), 'updatedAt': now()});
      refused({...profileJson(session, version: 2), 'updatedAt': null});
      refused({...profileJson(session), 'accountId': randomLoginId()});
      refused({...profileJson(session), 'extra': true});
      refused(profileJson(session, version: 2, replay: true));
      refused(profileJson(session, version: 3), expectedVersion: 1);
      refused({...profileJson(session, version: 2), 'updatedAt': '2999-01-01T00:00:00Z'});
      expect(ProfileSnapshot.parse(profileJson(session, version: 2, replay: true), session, expectedVersion: 1).replay, isTrue);
    });

    test('Unicode contrôlé et types inattendus convertis', () {
      for (final name in ['', ' A', 'A ', 'A${cp(0x200b)}B', 'A${cp(0x2028)}B', 'A${cp(0x07)}B', 'A${cp(0xfeff)}B', '😀' * 81]) {
        refused(profileJson(session, version: 2, name: name));
      }
      refused({...profileJson(session, version: 2), 'displayName': 3});
      refused({...profileJson(session, version: 2), 'idempotentReplay': 'false'});
      expect(ProfileSnapshot.parse(profileJson(session, version: 2, name: 'Élodie'), session).displayName, 'Élodie');
      expect(isAcceptableProfileName('😀' * 80), isTrue);
    });

    test('commande stockée altérée refusée comme erreur de stockage', () {
      final valid = {'key': randomLoginId(), 'expectedVersion': 1, 'displayName': 'Alpha', 'accountId': randomLoginId()};
      expect(ProfileCommand.parse(valid).displayName, 'Alpha');
      for (final altered in [
        {...valid, 'expectedVersion': 0},
        {...valid, 'expectedVersion': 9007199254740992},
        {...valid, 'key': 'cle'},
        {...valid, 'displayName': 'A${cp(0x202e)}B'},
        {...valid, 'displayName': 7},
        {...valid, 'extra': 1},
      ]) {
        expect(() => ProfileCommand.parse(altered),
            throwsA(isA<ProfileProblem>().having((p) => p.failure, 'failure', ProfileFailure.storage)));
      }
    });
  });
}
