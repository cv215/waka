import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/login_store.dart';
import 'package:waka_mobile/features/identity/presentation/sign_in_page.dart';

/// An isolated native namespace avoids touching any application's saved session.
class NativeTestStore implements LoginStore {
  NativeTestStore()
    : storage = FlutterSecureStorage(
        aOptions: AndroidOptions(
          resetOnError: false,
          migrateOnAlgorithmChange: false,
          storageNamespace: 'waka_test_${randomLoginId()}',
        ),
      );
  final FlutterSecureStorage storage;
  static const key = 'isolated-login-record';
  @override
  Future<LoginSnapshot?> read() async {
    final value = await storage.read(key: key);
    return value == null ? null : LoginSnapshot.parse(value);
  }

  @override
  Future<void> write(LoginSnapshot snapshot) =>
      storage.write(key: key, value: jsonEncode(snapshot.toJson()));
  Future<void> clean() => storage.delete(key: key);
}

Future<void> pumpUntil(
  WidgetTester tester,
  bool Function() condition, {
  required String description,
  required String Function() diagnostic,
}) async {
  const interval = Duration(milliseconds: 50);
  const maxPumps = 200;
  for (var pump = 0; pump < maxPumps; pump++) {
    if (condition()) return;
    await tester.pump(interval);
  }
  expect(
    condition(),
    isTrue,
    reason: 'Délai dépassé en attendant $description. ${diagnostic()}',
  );
}

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Android chiffre, relit et efface le seul enregistrement technique',
    (tester) async {
      final store = NativeTestStore();
      try {
        final first = LoginSnapshot(
          randomLoginId(),
          attempt: LoginAttempt.start('6${'0' * 8}'),
        );
        await store.write(first);
        final reopened = FlutterSecureStorage(aOptions: store.storage.aOptions);
        final actual = LoginSnapshot.parse(
          (await reopened.read(key: NativeTestStore.key))!,
        );
        expect(actual.client, first.client);
        expect(actual.attempt!.requestKey, first.attempt!.requestKey);
        await store.write(LoginSnapshot(first.client));
        expect((await store.read())!.attempt, isNull);
        await store.clean();
        expect(await store.read(), isNull);
      } finally {
        await store.clean();
      }
    },
  );

  testWidgets(
    'Écrans et stockage Android traversent le transport HTTP réservé au test',
    (tester) async {
      final store = NativeTestStore();
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final now = DateTime.now().toUtc();
      var requests = 0, verifications = 0, revocations = 0;
      final token = base64Url.encode(List.filled(32, 9)).replaceAll('=', '');
      final subscription = server.listen((request) async {
        await request.drain<void>();
        request.response.headers.set('Cache-Control', 'no-store');
        request.response.headers.contentType = ContentType.json;
        if (request.uri.path.endsWith('/verifications')) {
          verifications++;
          request.response.write(
            jsonEncode({
              'sessionId': randomLoginId(),
              'accountId': randomLoginId(),
              'tokenType': 'Bearer',
              'token': token,
              'absoluteExpiresAt': now
                  .add(const Duration(hours: 1))
                  .toIso8601String(),
              'idleExpiresAt': now
                  .add(const Duration(minutes: 10))
                  .toIso8601String(),
            }),
          );
        } else if (request.uri.path.endsWith('/revocations')) {
          revocations++;
          request.response.statusCode = 204;
        } else {
          requests++;
          request.response.statusCode = 202;
          request.response.write(
            jsonEncode({
              'challengeId': randomLoginId(),
              'submission': 'UNKNOWN',
              'expiresAt': now
                  .add(const Duration(minutes: 5))
                  .toIso8601String(),
              'destinationHint': '•••••••00',
            }),
          );
        }
        await request.response.close();
      });
      final flow = LoginFlow(
        LoginClient(
          LocalApiConfiguration.parse(
            environment: 'dev',
            baseUrl: 'http://127.0.0.1:${server.port}',
            releaseMode: false,
          ),
        ),
        store,
      );
      try {
        await tester.pumpWidget(MaterialApp(home: SignInPage(flow: flow)));
        await pumpUntil(
          tester,
          () => flow.ready || (!flow.busy && flow.failure != null),
          description: 'la fin de la lecture du stockage natif',
          diagnostic: () =>
              'ready=${flow.ready}, busy=${flow.busy}, '
              'failure=${flow.failure?.name ?? 'aucune'}',
        );
        expect(
          flow.ready,
          isTrue,
          reason:
              'L’initialisation native a échoué: '
              '${flow.failure?.name ?? 'état prêt absent'}',
        );
        await tester.pumpAndSettle();
        await tester.enterText(find.byType(TextFormField), '6${'0' * 8}');
        await tester.ensureVisible(find.text('Recevoir un code'));
        await tester.tap(find.text('Recevoir un code'));
        await pumpUntil(
          tester,
          () =>
              !flow.busy &&
              (flow.attempt?.challenge != null || flow.failure != null),
          description: 'la réponse du serveur HTTP local à la demande de code',
          diagnostic: () =>
              'busy=${flow.busy}, '
              'challenge=${flow.attempt?.challenge != null}, '
              'failure=${flow.failure?.name ?? 'aucune'}, requests=$requests',
        );
        expect(
          flow.failure,
          isNull,
          reason:
              'La demande HTTP locale a échoué: '
              '${flow.failure?.name ?? 'erreur inconnue'}',
        );
        expect(flow.attempt?.challenge, isNotNull);
        expect(flow.busy, isFalse);
        await tester.pumpAndSettle();
        expect(find.text('Saisis le code'), findsOneWidget);
        expect(
          find.text(
            'L’envoi n’est pas confirmé. Si tu as reçu un code, saisis-le.',
          ),
          findsOneWidget,
        );
        await tester.enterText(find.byType(TextFormField), '135790');
        await tester.ensureVisible(find.text('Vérifier le code'));
        await tester.tap(find.text('Vérifier le code'));
        await pumpUntil(
          tester,
          () => !flow.busy && (flow.session != null || flow.failure != null),
          description: 'la réponse du serveur HTTP local à la vérification',
          diagnostic: () =>
              'busy=${flow.busy}, session=${flow.session != null}, '
              'failure=${flow.failure?.name ?? 'aucune'}, '
              'verifications=$verifications',
        );
        expect(
          flow.failure,
          isNull,
          reason:
              'La vérification HTTP locale a échoué: '
              '${flow.failure?.name ?? 'erreur inconnue'}',
        );
        expect(flow.session, isNotNull);
        expect(flow.busy, isFalse);
        await tester.pumpAndSettle();
        expect(find.text('Connexion confirmée'), findsOneWidget);
        expect((await store.read())!.session!.token, token);
        await tester.tap(find.text('Se déconnecter'));
        await pumpUntil(
          tester,
          () => !flow.busy && (flow.session == null || flow.failure != null),
          description: 'la réponse du serveur HTTP local à la déconnexion',
          diagnostic: () =>
              'busy=${flow.busy}, session=${flow.session != null}, '
              'failure=${flow.failure?.name ?? 'aucune'}, '
              'revocations=$revocations',
        );
        expect(
          flow.failure,
          isNull,
          reason:
              'La déconnexion HTTP locale a échoué: '
              '${flow.failure?.name ?? 'erreur inconnue'}',
        );
        expect(flow.session, isNull);
        expect(flow.busy, isFalse);
        await tester.pumpAndSettle();
        expect(find.text('Déconnexion confirmée.'), findsOneWidget);
        expect((await store.read())!.session, isNull);
        expect([requests, verifications, revocations], [1, 1, 1]);
      } finally {
        await tester.pumpWidget(const SizedBox.shrink());
        flow.dispose();
        await store.clean();
        await subscription.cancel();
        await server.close(force: true);
      }
    },
  );
}
