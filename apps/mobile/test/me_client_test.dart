import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';

final _now = DateTime.utc(2026, 10, 18, 12);
LoginSession _session() => LoginSession(
  randomLoginId(),
  randomLoginId(),
  base64Url.encode(List.filled(32, 11)).replaceAll('=', ''),
  _now.add(const Duration(hours: 1)),
  _now.add(const Duration(minutes: 10)),
);

Map<String, dynamic> _response(LoginSession session) => {
  'accountId': session.accountId,
  'sessionId': session.id,
  'absoluteExpiresAt': session.absoluteExpiresAt.toIso8601String(),
  'idleExpiresAt': session.idleExpiresAt.toIso8601String(),
  'accountState': 'ACTIVE',
  'roles': ['CLIENT'],
  'serverTime': _now.toIso8601String(),
};

LoginClient _client(HttpServer server) => LoginClient(
  LocalApiConfiguration.parse(
    environment: 'dev',
    baseUrl: 'http://127.0.0.1:${server.port}',
    releaseMode: false,
  ),
);

void main() {
  test('La projection refuse autre compte, privilège, secret et dates incohérentes', () {
    final session = _session();
    final valid = _response(session);
    final account = LoginAccount.parse(valid, session);
    expect(account.accountId, session.accountId);
    expect(account.toString().contains(session.accountId), isFalse);
    final bad = <Object?>[
      null,
      [],
      {},
      {...valid, 'token': session.token},
      {...valid, 'accountId': randomLoginId()},
      {...valid, 'sessionId': randomLoginId()},
      {...valid, 'accountState': 'DISABLED'},
      {...valid, 'roles': 'CLIENT'},
      {...valid, 'roles': []},
      {
        ...valid,
        'roles': ['CLIENT', 'CLIENT'],
      },
      {
        ...valid,
        'roles': ['ADMIN'],
      },
      {
        ...valid,
        'roles': ['CLIENT', 'DRIVER'],
      },
      {
        ...valid,
        'absoluteExpiresAt': session.absoluteExpiresAt
            .add(const Duration(seconds: 1))
            .toIso8601String(),
      },
      {
        ...valid,
        'idleExpiresAt': session.absoluteExpiresAt
            .add(const Duration(seconds: 1))
            .toIso8601String(),
      },
      {...valid, 'idleExpiresAt': _now.toIso8601String()},
      {...valid, 'serverTime': session.absoluteExpiresAt.toIso8601String()},
      {...valid, 'serverTime': session.idleExpiresAt.toIso8601String()},
      {...valid, 'serverTime': '2026-02-31T12:00:00Z'},
      {...valid, 'serverTime': '2026-10-18T12:00:00+00:00'},
      {...valid, 'serverTime': '20261018T120000Z'},
      {...valid, 'serverTime': '${_now.toIso8601String()}\n'},
    ];
    for (final response in bad) {
      expect(
        () => LoginAccount.parse(response, session),
        throwsA(isA<LoginProblem>()),
      );
    }
  });

  test(
    'GET /me envoie seulement authentification et contexte, sans clé ni corps',
    () async {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final session = _session(), context = randomLoginId();
      var requests = 0;
      final subscription = server.listen((request) async {
        requests++;
        expect(request.method, 'GET');
        expect(request.uri.path, '/api/v1/me');
        expect(request.uri.hasQuery, isFalse);
        expect(
          request.headers.value('Authorization'),
          'Bearer ${session.token}',
        );
        expect(request.headers.value('X-Waka-Client-Instance'), context);
        expect(request.headers.value('Idempotency-Key'), isNull);
        expect(await utf8.decoder.bind(request).join(), isEmpty);
        request.response.headers.contentType = ContentType.json;
        request.response.headers.set('Cache-Control', 'no-store');
        request.response.write(jsonEncode(_response(session)));
        await request.response.close();
      });
      final gateway = _client(server);
      try {
        final account = await gateway.me(session, context);
        expect(account.sessionId, session.id);
        expect(account.serverTime, _now);
        expect(requests, 1);
      } finally {
        gateway.dispose();
        await subscription.cancel();
        await server.close(force: true);
      }
    },
  );

  test('GET /me distingue refus, panne et réponse non fiable sans redirection', () async {
    final modes = {
      '401': LoginFailure.denied,
      '403': LoginFailure.denied,
      '503': LoginFailure.unavailable,
      'network': LoginFailure.uncertain,
      'redirect': LoginFailure.invalidResponse,
      'cache': LoginFailure.invalidResponse,
      'mime': LoginFailure.invalidResponse,
      'large': LoginFailure.invalidResponse,
      'json': LoginFailure.invalidResponse,
      'otherAccount': LoginFailure.invalidResponse,
    };
    for (final entry in modes.entries) {
      final server = await HttpServer.bind(InternetAddress.loopbackIPv4, 0);
      final session = _session();
      var requests = 0;
      final subscription = server.listen((request) async {
        requests++;
        await request.drain<void>();
        if (entry.key == 'network') {
          (await request.response.detachSocket(writeHeaders: false)).destroy();
          return;
        }
        final status = int.tryParse(entry.key);
        if (status != null) {
          request.response.statusCode = status;
          // A refusal still invalidates the session when its body is oversized.
          request.response.write('x' * 8193);
        } else if (entry.key == 'redirect') {
          request.response.statusCode = 302;
          request.response.headers.set(
            'Location',
            'http://127.0.0.1:${server.port}/other',
          );
        } else {
          if (entry.key != 'cache') {
            request.response.headers.set('Cache-Control', 'no-store');
          }
          request.response.headers.contentType = entry.key == 'mime'
              ? ContentType.text
              : ContentType.json;
          final response = _response(session);
          if (entry.key == 'otherAccount') {
            response['accountId'] = randomLoginId();
          }
          request.response.write(switch (entry.key) {
            'large' => 'x' * 8193,
            'json' => '{',
            _ => jsonEncode(response),
          });
        }
        await request.response.close();
      });
      final gateway = _client(server);
      try {
        await expectLater(
          gateway.me(session, randomLoginId()),
          throwsA(
            isA<LoginProblem>().having(
              (problem) => problem.failure,
              entry.key,
              entry.value,
            ),
          ),
        );
        expect(requests, 1);
      } finally {
        gateway.dispose();
        await subscription.cancel();
        await server.close(force: true);
      }
    }
  });
}
