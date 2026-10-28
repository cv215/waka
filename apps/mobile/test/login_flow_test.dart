import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/presentation/sign_in_page.dart';

String get technicalPhone => '6${'0' * 8}';
LoginSession grant() => LoginSession(
  randomLoginId(),
  randomLoginId(),
  base64Url.encode(List.filled(32, 7)).replaceAll('=', ''),
  DateTime.now().toUtc().add(const Duration(hours: 1)),
  DateTime.now().toUtc().add(const Duration(minutes: 10)),
);

Map<String, Object> accountResponse(LoginSession session, {DateTime? now}) => {
  'accountId': session.accountId,
  'sessionId': session.id,
  'absoluteExpiresAt': session.absoluteExpiresAt.toIso8601String(),
  'idleExpiresAt': session.idleExpiresAt.toIso8601String(),
  'accountState': 'ACTIVE',
  'roles': ['CLIENT'],
  'serverTime': (now ?? DateTime.now().toUtc()).toIso8601String(),
};

class MemoryStore implements LoginStore {
  String? raw;
  bool failWrites = false, failSessionWrite = false;
  @override
  Future<LoginSnapshot?> read() async =>
      raw == null ? null : LoginSnapshot.parse(raw!);
  @override
  Future<void> write(LoginSnapshot value) async {
    if (failWrites || (failSessionWrite && value.session != null)) {
      throw const LoginProblem(LoginFailure.storage);
    }
    raw = jsonEncode(value.toJson());
  }
}

class Gateway implements LoginGateway {
  final requestKeys = <String>[],
      verifyKeys = <String>[],
      logoutKeys = <String>[];
  LoginFailure? requestFailure, verifyFailure, logoutFailure, accountFailure;
  int accountReads = 0;
  DateTime? accountServerTime;
  Completer<LoginAccount>? delayedAccount;
  Completer<LoginChallenge>? delayedRequest;
  LoginChallenge challenge = LoginChallenge(
    randomLoginId(),
    'ACCEPTED',
    DateTime.now().toUtc().add(const Duration(minutes: 5)),
    '•••••••00',
  );
  final session = grant();
  @override
  Future<LoginAccount> me(LoginSession session, String client) async {
    accountReads++;
    if (accountFailure != null) throw LoginProblem(accountFailure!);
    return delayedAccount?.future ??
        LoginAccount.parse(
          accountResponse(session, now: accountServerTime),
          session,
        );
  }

  @override
  Future<LoginChallenge> request(LoginAttempt attempt, String client) async {
    requestKeys.add(attempt.requestKey);
    if (requestFailure != null) throw LoginProblem(requestFailure!);
    return delayedRequest?.future ?? challenge;
  }

  @override
  Future<LoginSession> verify(
    LoginAttempt attempt,
    String client,
    String code,
  ) async {
    verifyKeys.add(attempt.verificationKey(code));
    if (verifyFailure != null) throw LoginProblem(verifyFailure!);
    return session;
  }

  @override
  Future<void> revoke(LoginSession session, String client, String key) async {
    logoutKeys.add(key);
    if (logoutFailure != null) throw LoginProblem(logoutFailure!);
  }

  @override
  void dispose() {}
}

void main() {
  test(
    'Une requête perdue conserve son identifiant après redémarrage',
    () async {
      final store = MemoryStore(),
          gateway = Gateway()..requestFailure = LoginFailure.uncertain;
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      await flow.request(technicalPhone);
      expect(flow.session, isNull);
      expect(flow.failure, LoginFailure.uncertain);
      flow.dispose();
      gateway.requestFailure = null;
      final restored = LoginFlow(gateway, store);
      await restored.initialize();
      await restored.request(technicalPhone);
      expect(gateway.requestKeys.toSet().length, 1);
      expect(restored.attempt!.challenge, isNotNull);
      expect(gateway.verifyKeys, isEmpty);
      restored.dispose();
    },
  );

  test('Code non stocké, même vérification après fermeture et nouvelle clé si code changé', () async {
    final store = MemoryStore(),
        gateway = Gateway()..verifyFailure = LoginFailure.uncertain;
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    await flow.request(technicalPhone);
    await flow.verify('135790');
    expect(
      (jsonDecode(store.raw!) as Map)['attempt'].keys,
      unorderedEquals(['phone', 'requestKey', 'seed', 'challenge']),
    );
    expect(store.raw!.contains('135790'), isFalse);
    expect(flow.confirmedNow, isFalse);
    flow.dispose();
    final restored = LoginFlow(gateway, store);
    await restored.initialize();
    await restored.verify('135790');
    expect(gateway.verifyKeys[0], gateway.verifyKeys[1]);
    await restored.verify('246801');
    expect(gateway.verifyKeys.last, isNot(gateway.verifyKeys.first));
    gateway.verifyFailure = null;
    await restored.verify('135790');
    expect(restored.confirmedNow, isTrue);
    expect(restored.session!.id, gateway.session.id);
    expect(gateway.requestKeys.length, 1);
    restored.dispose();
  });

  test('Déconnexion incertaine conservée, aucune reconnexion locale après redémarrage', () async {
    final store = MemoryStore(), gateway = Gateway();
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    await flow.request(technicalPhone);
    await flow.verify('135790');
    gateway.logoutFailure = LoginFailure.uncertain;
    await flow.revoke();
    expect(flow.logoutPending, isTrue);
    expect(flow.confirmedNow, isFalse);
    flow.dispose();
    final restored = LoginFlow(gateway, store);
    await restored.initialize();
    expect(restored.logoutPending, isTrue);
    expect(restored.confirmedNow, isFalse);
    await restored.request(technicalPhone);
    expect(gateway.requestKeys.length, 1);
    gateway.logoutFailure = null;
    await restored.revoke();
    expect(gateway.logoutKeys.toSet().length, 1);
    expect(restored.session, isNull);
    expect(store.raw!.contains(gateway.session.token), isFalse);
    expect(restored.notice, 'Déconnexion confirmée.');
    restored.dispose();
  });

  test('Un stockage indisponible bloque l’envoi et un échec après ouverture reste explicite', () async {
    final store = MemoryStore()..failWrites = true, gateway = Gateway();
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    await flow.request(technicalPhone);
    expect(gateway.requestKeys, isEmpty);
    store.failWrites = false;
    await flow.initialize();
    await flow.request(technicalPhone);
    store.failSessionWrite = true;
    await flow.verify('135790');
    expect(flow.sessionSaved, isFalse);
    expect(flow.confirmedNow, isFalse);
    expect(flow.failure, LoginFailure.storage);
    store.failSessionWrite = false;
    await flow.retrySessionStorage();
    expect(flow.sessionSaved, isTrue);
    expect(flow.confirmedNow, isFalse);
    flow.dispose();
  });

  test(
    'Une session restaurée attend la réponse du serveur avant confirmation',
    () async {
      final store = MemoryStore();
      await store.write(LoginSnapshot(randomLoginId(), session: grant()));
      final gateway = Gateway()..delayedAccount = Completer<LoginAccount>();
      final flow = LoginFlow(gateway, store);
      final initialization = flow.initialize();
      await Future<void>.delayed(Duration.zero);
      expect(flow.session, isNotNull);
      expect(flow.confirmedNow, isFalse);
      expect(flow.account, isNull);
      expect(flow.logoutPending, isFalse);
      gateway.delayedAccount!.complete(
        LoginAccount.parse(accountResponse(flow.session!), flow.session!),
      );
      await initialization;
      expect(flow.confirmedNow, isTrue);
      expect(flow.account, isNotNull);
      expect(gateway.accountReads, 1);
      expect(gateway.requestKeys, isEmpty);
      flow.dispose();
    },
  );

  test('Double clic bloqué et expiration locale sans nouvel appel', () async {
    final store = MemoryStore(),
        gateway = Gateway()..delayedRequest = Completer<LoginChallenge>();
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    final pending = flow.request(technicalPhone);
    await Future<void>.delayed(Duration.zero);
    await flow.request(technicalPhone);
    expect(gateway.requestKeys.length, 1);
    gateway.delayedRequest!.complete(
      LoginChallenge(
        randomLoginId(),
        'ACCEPTED',
        DateTime.now().toUtc().subtract(const Duration(seconds: 1)),
        '•••••••00',
      ),
    );
    await pending;
    await flow.verify('135790');
    expect(gateway.verifyKeys, isEmpty);
    expect(flow.failure, LoginFailure.denied);
    flow.dispose();
  });

  test(
    'A02 ne fournit aucun droit et la vérification du compte reste explicite',
    () async {
      final gateway = Gateway(), store = MemoryStore();
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      await flow.request(technicalPhone);
      await flow.verify('135790');
      expect(flow.confirmedNow, isTrue);
      expect(flow.account, isNull);
      expect(gateway.accountReads, 0);
      await flow.checkAccount();
      expect(flow.account?.accountId, gateway.session.accountId);
      expect(gateway.accountReads, 1);
      expect(gateway.requestKeys.length, 1);
      final saved = jsonDecode(store.raw!) as Map;
      expect(
        saved.keys,
        unorderedEquals([
          'version',
          'client',
          'attempt',
          'session',
          'logoutKey',
          'profileCommand',
          'applicationCommand',
        ]),
      );
      expect(saved['version'], 3);
      expect(saved['profileCommand'], isNull);
      expect(saved['applicationCommand'], isNull);
      expect(store.raw!.contains('roles'), isFalse);
      flow.dispose();
    },
  );

  test(
    'Le temps serveur confirme un compte même si la date du téléphone diverge',
    () async {
      final serverTime = DateTime.utc(2000, 1, 1);
      final source = grant();
      final session = LoginSession(
        source.id,
        source.accountId,
        source.token,
        serverTime.add(const Duration(hours: 1)),
        serverTime.add(const Duration(minutes: 10)),
      );
      final store = MemoryStore();
      await store.write(LoginSnapshot(randomLoginId(), session: session));
      final gateway = Gateway()..accountServerTime = serverTime;
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      expect(flow.confirmedNow, isTrue);
      expect(flow.locallyExpired, isFalse);
      expect(flow.account, isNotNull);
      flow.dispose();
    },
  );

  test(
    'Les dates actualisées de /me sont sauvegardées sans changer le jeton',
    () async {
      final source = grant(), store = MemoryStore();
      final response = accountResponse(source);
      final refreshedIdle = source.idleExpiresAt.add(
        const Duration(minutes: 2),
      );
      response['idleExpiresAt'] = refreshedIdle.toIso8601String();
      final gateway = Gateway()..delayedAccount = Completer<LoginAccount>();
      gateway.delayedAccount!.complete(LoginAccount.parse(response, source));
      await store.write(LoginSnapshot(randomLoginId(), session: source));
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      final saved = (await store.read())!.session!;
      expect(saved.idleExpiresAt, refreshedIdle);
      expect(saved.absoluteExpiresAt, source.absoluteExpiresAt);
      expect(saved.token, source.token);
      expect(flow.account, isNotNull);
      flow.dispose();
    },
  );

  test(
    'Une session révoquée ou expirée est effacée sans nouvelle demande SMS',
    () async {
      for (final expired in [false, true]) {
        final source = grant();
        final session = expired
            ? LoginSession(
                source.id,
                source.accountId,
                source.token,
                DateTime.now().toUtc().subtract(const Duration(hours: 1)),
                DateTime.now().toUtc().subtract(const Duration(hours: 2)),
              )
            : source;
        final store = MemoryStore();
        await store.write(LoginSnapshot(randomLoginId(), session: session));
        final gateway = Gateway()..accountFailure = LoginFailure.denied;
        final flow = LoginFlow(gateway, store);
        await flow.initialize();
        expect(flow.session, isNull);
        expect(flow.account, isNull);
        expect(flow.confirmedNow, isFalse);
        expect((await store.read())!.session, isNull);
        expect(store.raw!.contains(source.token), isFalse);
        expect(gateway.accountReads, 1);
        expect(gateway.requestKeys, isEmpty);
        expect(flow.notice, contains('n’est plus acceptée'));
        flow.dispose();
      }
    },
  );

  test('Réseau, service indisponible ou mauvaise réponse gardent une session non confirmée', () async {
    for (final failure in [
      LoginFailure.uncertain,
      LoginFailure.unavailable,
      LoginFailure.invalidResponse,
    ]) {
      final store = MemoryStore(), gateway = Gateway();
      await store.write(
        LoginSnapshot(randomLoginId(), session: gateway.session),
      );
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      expect(flow.account, isNotNull);
      final before = store.raw;
      gateway.accountFailure = failure;
      await flow.checkAccount();
      expect(flow.failure, failure);
      expect(flow.account, isNull);
      expect(flow.confirmedNow, isFalse);
      expect(store.raw, before);
      gateway.accountFailure = null;
      await flow.checkAccount();
      expect(flow.account, isNotNull);
      expect(gateway.requestKeys, isEmpty);
      flow.dispose();
    }
  });

  test('Échec du stockage après /me : aucun droit après la seule reprise d’écriture', () async {
    final store = MemoryStore(), gateway = Gateway();
    await store.write(LoginSnapshot(randomLoginId(), session: gateway.session));
    store.failSessionWrite = true;
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    expect(flow.failure, LoginFailure.storage);
    expect(flow.sessionSaved, isFalse);
    expect(flow.account, isNull);
    expect(flow.confirmedNow, isFalse);
    store.failSessionWrite = false;
    await flow.retrySessionStorage();
    expect(flow.sessionSaved, isTrue);
    expect(flow.account, isNull);
    expect(flow.confirmedNow, isFalse);
    expect(gateway.accountReads, 1);
    await flow.checkAccount();
    expect(flow.account, isNotNull);
    flow.dispose();
  });

  test('Échec de l’effacement après refus : reprise locale sans réutiliser la session', () async {
    final store = MemoryStore(),
        gateway = Gateway()..accountFailure = LoginFailure.denied;
    await store.write(LoginSnapshot(randomLoginId(), session: gateway.session));
    store.failWrites = true;
    final flow = LoginFlow(gateway, store);
    await flow.initialize();
    expect(flow.failure, LoginFailure.storage);
    expect(flow.cleanupPending, isTrue);
    expect(flow.account, isNull);
    expect(flow.confirmedNow, isFalse);
    expect((await store.read())!.session, isNotNull);
    await flow.checkAccount();
    await flow.retrySessionStorage();
    expect(gateway.accountReads, 1);
    store.failWrites = false;
    await flow.retrySessionCleanup();
    expect(flow.cleanupPending, isFalse);
    expect(flow.session, isNull);
    expect((await store.read())!.session, isNull);
    expect(gateway.accountReads, 1);
    flow.dispose();
  });

  test(
    'La déconnexion persistée termine avant toute lecture de compte',
    () async {
      final store = MemoryStore(), gateway = Gateway();
      final key = randomLoginId();
      await store.write(
        LoginSnapshot(
          randomLoginId(),
          session: gateway.session,
          logoutKey: key,
        ),
      );
      final flow = LoginFlow(gateway, store);
      await flow.initialize();
      expect(gateway.logoutKeys, [key]);
      expect(gateway.accountReads, 0);
      expect(flow.session, isNull);
      expect(flow.notice, 'Déconnexion confirmée.');
      flow.dispose();
    },
  );

  test('Lecture et révocation restent sérialisées ; aucun /me après déconnexion en attente', () async {
    final store = MemoryStore(),
        gateway = Gateway()..delayedAccount = Completer<LoginAccount>();
    await store.write(LoginSnapshot(randomLoginId(), session: gateway.session));
    final flow = LoginFlow(gateway, store);
    final initialized = flow.initialize();
    await Future<void>.delayed(Duration.zero);
    await flow.checkAccount();
    await flow.revoke();
    expect(gateway.accountReads, 1);
    expect(gateway.logoutKeys, isEmpty);
    gateway.delayedAccount!.complete(
      LoginAccount.parse(accountResponse(gateway.session), gateway.session),
    );
    await initialized;
    gateway.logoutFailure = LoginFailure.uncertain;
    await flow.revoke();
    expect(flow.logoutPending, isTrue);
    expect(flow.account, isNull);
    await flow.checkAccount();
    expect(gateway.accountReads, 1);
    expect(flow.confirmedNow, isFalse);
    flow.dispose();
  });

  test(
    'La réponse de session doit être exacte et ne se journalise pas en clair',
    () {
      final session = grant();
      expect(LoginSession.parse(session.toJson()).id, session.id);
      for (final value in [
        null,
        {},
        {...session.toJson(), 'role': 'ADMIN'},
        {...session.toJson(), 'token': 'invalid'},
        {
          ...session.toJson(),
          'idleExpiresAt': session.absoluteExpiresAt
              .add(const Duration(hours: 1))
              .toIso8601String(),
        },
      ]) {
        expect(() => LoginSession.parse(value), throwsA(isA<LoginProblem>()));
      }
      expect(session.toString().contains(session.token), isFalse);
      expect(
        () => LoginChallenge.parse({
          ...Gateway().challenge.toJson(),
          'destinationHint': '•••••••00\n',
        }),
        throwsA(isA<LoginProblem>()),
      );
      expect(
        () => LoginSnapshot.parse(
          jsonEncode({
            'version': 1,
            'client': randomLoginId(),
            'session': null,
            'attempt': null,
            'logoutKey': randomLoginId(),
          }),
        ),
        throwsA(isA<LoginProblem>()),
      );
    },
  );

  testWidgets(
    'Le formulaire réel suit les réponses, sans afficher code ou jeton',
    (tester) async {
      final gateway = Gateway();
      final visible = LoginFlow(gateway, MemoryStore());
      await tester.pumpWidget(MaterialApp(home: SignInPage(flow: visible)));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Recevoir un code'));
      await tester.pumpAndSettle();
      expect(find.text('Saisis un numéro mobile.'), findsOneWidget);
      expect(gateway.requestKeys, isEmpty);
      await tester.enterText(find.byType(TextFormField), technicalPhone);
      await tester.tap(find.text('Recevoir un code'));
      await tester.pumpAndSettle();
      expect(find.text('Saisis le code'), findsOneWidget);
      expect(find.text('Connexion confirmée'), findsNothing);
      await tester.enterText(find.byType(TextFormField), '135790');
      await tester.tap(find.text('Vérifier le code'));
      await tester.pumpAndSettle();
      expect(find.text('Connexion confirmée'), findsOneWidget);
      expect(find.text(gateway.session.token), findsNothing);
      await tester.tap(find.text('Se déconnecter'));
      await tester.pumpAndSettle();
      expect(find.text('Déconnexion confirmée.'), findsOneWidget);
      await tester.pumpWidget(const SizedBox.shrink());
      visible.dispose();
    },
  );

  testWidgets(
    'La reprise réseau affiche seulement un compte confirmé et masque les identifiants',
    (tester) async {
      final gateway = Gateway()..accountFailure = LoginFailure.unavailable;
      final store = MemoryStore();
      await store.write(
        LoginSnapshot(randomLoginId(), session: gateway.session),
      );
      final flow = LoginFlow(gateway, store);
      await tester.pumpWidget(MaterialApp(home: SignInPage(flow: flow)));
      await tester.pumpAndSettle();
      expect(find.text('Connexion enregistrée'), findsOneWidget);
      expect(find.textContaining('Ton compte est actif'), findsNothing);
      expect(find.text('Réessayer la vérification du compte'), findsOneWidget);
      gateway.accountFailure = null;
      await tester.tap(find.text('Réessayer la vérification du compte'));
      await tester.pumpAndSettle();
      expect(find.textContaining('Ton compte est actif'), findsOneWidget);
      expect(find.text(gateway.session.accountId), findsNothing);
      expect(find.text(gateway.session.id), findsNothing);
      expect(find.text(gateway.session.token), findsNothing);
      expect(gateway.requestKeys, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      flow.dispose();
    },
  );

  testWidgets(
    'Un effacement bloqué n’affiche aucun accès ni bouton de nouvelle vérification',
    (tester) async {
      final gateway = Gateway()..accountFailure = LoginFailure.denied;
      final store = MemoryStore();
      await store.write(
        LoginSnapshot(randomLoginId(), session: gateway.session),
      );
      store.failWrites = true;
      final flow = LoginFlow(gateway, store);
      await tester.pumpWidget(MaterialApp(home: SignInPage(flow: flow)));
      await tester.pumpAndSettle();
      expect(find.text('Connexion refusée'), findsOneWidget);
      expect(find.text('Terminer l’effacement local'), findsOneWidget);
      expect(find.text('Se déconnecter'), findsNothing);
      expect(find.text('Vérifier mon compte'), findsNothing);
      expect(find.textContaining('Ton compte est actif'), findsNothing);
      store.failWrites = false;
      await tester.tap(find.text('Terminer l’effacement local'));
      await tester.pumpAndSettle();
      expect(find.text('Connecte-toi à WAKA'), findsOneWidget);
      expect(gateway.accountReads, 1);
      expect(gateway.requestKeys, isEmpty);
      await tester.pumpWidget(const SizedBox.shrink());
      flow.dispose();
    },
  );

  testWidgets(
    'Une panne affiche une reprise et ne prétend pas envoyer un code',
    (tester) async {
      final gateway = Gateway()..requestFailure = LoginFailure.unavailable;
      final flow = LoginFlow(gateway, MemoryStore());
      await tester.pumpWidget(MaterialApp(home: SignInPage(flow: flow)));
      await tester.pumpAndSettle();
      await tester.enterText(find.byType(TextFormField), technicalPhone);
      await tester.tap(find.text('Recevoir un code'));
      await tester.pumpAndSettle();
      expect(
        find.text('Connexion momentanément indisponible. Réessaie plus tard.'),
        findsOneWidget,
      );
      expect(find.text('Réessayer la demande'), findsOneWidget);
      expect(find.text('Saisis le code'), findsNothing);
      await tester.pumpWidget(const SizedBox.shrink());
      flow.dispose();
    },
  );
}
