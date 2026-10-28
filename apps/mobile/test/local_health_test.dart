import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/diagnostics/data/service_health_client.dart';

void main() {
  test('Une cible locale exige environnement, port et absence de secret', () {
    expect(
      LocalApiConfiguration.parse(
        environment: 'dev',
        baseUrl: 'http://10.0.2.2:8080',
        releaseMode: false,
      ).isConfigured,
      isTrue,
    );
    for (final url in [
      'http://10.0.2.2',
      'http://example.com:8080',
      'https://10.0.2.2:8080',
      'http://user:secret@127.0.0.1:8080',
      'http://127.0.0.1:8080/path',
      'http://127.0.0.1:8080?key=value',
      'http://127.0.0.1:8080#fragment',
    ]) {
      expect(
        LocalApiConfiguration.parse(
          environment: 'dev',
          baseUrl: url,
          releaseMode: false,
        ).isConfigured,
        isFalse,
        reason: url,
      );
    }
    for (final env in ['', 'prod', 'staging', 'test']) {
      expect(
        LocalApiConfiguration.parse(
          environment: env,
          baseUrl: 'http://127.0.0.1:8080',
          releaseMode: false,
        ).isConfigured,
        isFalse,
      );
    }
    expect(
      LocalApiConfiguration.parse(
        environment: 'dev',
        baseUrl: 'http://127.0.0.1:8080',
        releaseMode: true,
      ).isConfigured,
      isFalse,
    );
  });

  test('Une erreur ou un corps inattendu ne devient jamais UP', () {
    expect(
      interpretHealthResponse(200, 'application/json', '{"status":"UP"}'),
      HealthState.up,
    );
    expect(
      interpretHealthResponse(503, 'application/json', '{"status":"DOWN"}'),
      HealthState.down,
    );
    for (final body in [
      '',
      '{',
      '{}',
      '[]',
      '{"status":"DOWN"}',
      '{"status":"UP","extra":true}',
    ]) {
      expect(
        interpretHealthResponse(200, 'application/json', body),
        HealthState.invalid,
      );
    }
    expect(
      interpretHealthResponse(500, 'application/json', '{"status":"UP"}'),
      HealthState.invalid,
    );
    expect(
      interpretHealthResponse(200, 'text/html', '{"status":"UP"}'),
      HealthState.invalid,
    );
  });

  test(
    'Configuration absente : aucun acces reseau ni DB declaree DOWN',
    () async {
      final client = ServiceHealthClient(
        LocalApiConfiguration.parse(
          environment: '',
          baseUrl: '',
          releaseMode: false,
        ),
      );
      final result = await client.check();
      expect(result.process, HealthState.unconfigured);
      expect(result.database, HealthState.notChecked);
      client.dispose();
    },
  );
}
