import 'dart:async';
import 'dart:convert';
import '../../../core/config/local_api_configuration.dart';
import '../../../core/net/net.dart';

enum HealthState {
  notChecked,
  up,
  down,
  unreachable,
  timedOut,
  invalid,
  unconfigured,
}

class HealthSnapshot {
  const HealthSnapshot(this.process, this.database);

  final HealthState process;
  final HealthState database;
}

/// Interpretation testable sans reponse simulee dans l'application.
HealthState interpretHealthResponse(
  int status,
  String contentType,
  String body,
) {
  if (contentType.split(';').first.trim().toLowerCase() != 'application/json') {
    return HealthState.invalid;
  }
  try {
    final value = jsonDecode(body);
    if (value is! Map<String, dynamic> || value.length != 1) {
      return HealthState.invalid;
    }
    if (status == 200 && value['status'] == 'UP') return HealthState.up;
    if (status == 503 && value['status'] == 'DOWN') return HealthState.down;
  } on FormatException {
    return HealthState.invalid;
  }
  return HealthState.invalid;
}

class ServiceHealthClient {
  ServiceHealthClient(this.configuration);

  final LocalApiConfiguration configuration;
  final Set<HttpClient> _active = {};
  bool _disposed = false;

  Future<HealthSnapshot> check() async {
    if (!configuration.isConfigured) {
      return const HealthSnapshot(
        HealthState.unconfigured,
        HealthState.notChecked,
      );
    }
    final process = await _read('/api/v1/health');
    final database = process == HealthState.up && !_disposed
        ? await _read('/api/v1/health/database')
        : HealthState.notChecked;
    return HealthSnapshot(process, database);
  }

  Future<HealthState> _read(String path) async {
    if (_disposed) return HealthState.notChecked;
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(client);
    try {
      return await _request(client, path).timeout(const Duration(seconds: 8));
    } on TimeoutException {
      return HealthState.timedOut;
    } on IOException {
      return HealthState.unreachable;
    } on FormatException {
      return HealthState.invalid;
    } on StateError {
      return _disposed ? HealthState.notChecked : HealthState.invalid;
    } finally {
      // timeout() seul n'annule pas l'operation : fermer aussi la connexion.
      client.close(force: true);
      _active.remove(client);
    }
  }

  Future<HealthState> _request(HttpClient client, String path) async {
    final request = await client.getUrl(configuration.baseUri!.resolve(path));
    request.followRedirects = false;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    final response = await request.close();
    final bytes = <int>[];
    await for (final chunk in response) {
      if (bytes.length + chunk.length > 4096) return HealthState.invalid;
      bytes.addAll(chunk);
    }
    return interpretHealthResponse(
      response.statusCode,
      response.headers.contentType?.mimeType ?? '',
      utf8.decode(bytes),
    );
  }

  void dispose() {
    _disposed = true;
    for (final client in _active) {
      client.close(force: true);
    }
    _active.clear();
  }
}
