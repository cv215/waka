import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';

void main() {
  test(
    'Le client traverse réellement HTTP local et contrôle les contrats',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final context = randomLoginId(), challengeId = randomLoginId();
      final token = base64Url.encode(List.filled(32, 7)).replaceAll('=', '');
      final now = DateTime.now().toUtc();
      var commands = 0;
      final seenKeys = <String>[];
      final subscription = server.listen((request) async {
        commands++;
        seenKeys.add(request.headers.value('Idempotency-Key')!);
        request.response.headers.set('Cache-Control', 'no-store');
        request.response.headers.contentType = ContentType.json;
        if (request.uri.path.endsWith('/verifications')) {
          final body =
              jsonDecode(await utf8.decoder.bind(request).join()) as Map;
          expect(
            body.keys,
            unorderedEquals(['phoneNumber', 'code', 'clientInstanceRef']),
          );
          expect(body['clientInstanceRef'], context);
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
          expect(request.headers.value('Authorization'), 'Bearer $token');
          expect(request.headers.value('X-Waka-Client-Instance'), context);
          expect(await utf8.decoder.bind(request).join(), '');
          request.response.statusCode = 204;
        } else {
          final body =
              jsonDecode(await utf8.decoder.bind(request).join()) as Map;
          expect(
            body.keys,
            unorderedEquals(['phoneNumber', 'purpose', 'clientInstanceRef']),
          );
          request.response.statusCode = 202;
          request.response.write(
            jsonEncode({
              'challengeId': challengeId,
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
      final gateway = LoginClient(
        LocalApiConfiguration.parse(
          environment: 'dev',
          baseUrl: 'http://127.0.0.1:${server.port}',
          releaseMode: false,
        ),
      );
      try {
        final attempt = LoginAttempt.start('6${'0' * 8}');
        final challenge = await gateway.request(attempt, context);
        expect(challenge.submission, 'UNKNOWN');
        final active = attempt.withChallenge(challenge);
        final session = await gateway.verify(active, context, '135790');
        expect(session.token, token);
        final key = randomLoginId();
        await gateway.revoke(session, context, key);
        expect(commands, 3);
        expect(seenKeys, [
          attempt.requestKey,
          active.verificationKey('135790'),
          key,
        ]);
      } finally {
        gateway.dispose();
        await subscription.cancel();
        await server.close(force: true);
      }
    },
  );

  test(
    'Redirection, réponse trop grande et cache non protégé sont refusés',
    () async {
      for (final mode in ['redirect', 'large', 'cache', 'unavailable']) {
        final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
        var commands = 0;
        final subscription = server.listen((request) async {
          commands++;
          await request.drain<void>();
          if (mode == 'redirect') {
            request.response.statusCode = 302;
            request.response.headers.set(
              'Location',
              'http://127.0.0.1:${server.port}/redirected',
            );
          } else if (mode == 'unavailable') {
            request.response.statusCode = 503;
          } else {
            request.response.statusCode = 202;
            request.response.headers.contentType = ContentType.json;
            if (mode == 'large') {
              request.response.headers.set('Cache-Control', 'no-store');
            }
            request.response.write(mode == 'large' ? 'x' * 8193 : '{}');
          }
          await request.response.close();
        });
        final gateway = LoginClient(
          LocalApiConfiguration.parse(
            environment: 'dev',
            baseUrl: 'http://127.0.0.1:${server.port}',
            releaseMode: false,
          ),
        );
        try {
          await expectLater(
            gateway.request(LoginAttempt.start('6${'0' * 8}'), randomLoginId()),
            throwsA(
              isA<LoginProblem>().having(
                (v) => v.failure,
                'failure',
                mode == 'unavailable'
                    ? LoginFailure.unavailable
                    : LoginFailure.invalidResponse,
              ),
            ),
          );
          expect(commands, 1);
        } finally {
          gateway.dispose();
          await subscription.cancel();
          await server.close(force: true);
        }
      }
    },
  );
}
