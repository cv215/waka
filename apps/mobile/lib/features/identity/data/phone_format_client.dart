import 'dart:async';
import 'dart:convert';
import '../../../core/config/local_api_configuration.dart';
import '../../../core/net/net.dart';

enum PhoneFormatResult {
  accepted,
  rejected,
  unavailable,
  timeout,
  invalidResponse,
}

/// Résultat strict du précontrôle : il ne crée ni compte ni session.
PhoneFormatResult interpretPhoneFormatResponse(
  int status,
  String contentType,
  String body,
) {
  if (status == 400 || status == 413) return PhoneFormatResult.rejected;
  if (status >= 500) return PhoneFormatResult.unavailable;
  if (status != 200 ||
      contentType.split(';').first.trim().toLowerCase() != 'application/json') {
    return PhoneFormatResult.invalidResponse;
  }
  try {
    final value = jsonDecode(body);
    if (value is! Map<String, dynamic> ||
        value.length != 3 ||
        value['formatValid'] is! bool ||
        value['ownershipVerified'] is! bool ||
        value['country'] is! String) {
      return PhoneFormatResult.invalidResponse;
    }
    if (value['formatValid'] == true &&
        value['ownershipVerified'] == false &&
        value['country'] == 'CM') {
      return PhoneFormatResult.accepted;
    }
  } on FormatException {
    return PhoneFormatResult.invalidResponse;
  }
  return PhoneFormatResult.invalidResponse;
}

/// Miroir du contrôle syntaxique serveur, sans attribution ni vérification réelle.
bool isValidCameroonMobilePhoneFormat(String input) {
  if (input.isEmpty || input.length > 40) return false;
  final compact = input.replaceAll(RegExp(r'[ \u00a0\u202f\-()]'), '');
  final national = compact.startsWith('+237')
      ? compact.substring(4)
      : compact.startsWith('00237')
      ? compact.substring(5)
      : compact;
  final match = RegExp(r'^6[0-9]{8}$').firstMatch(national);
  return match != null && match.group(0)!.length == national.length;
}

abstract interface class PhoneFormatGateway {
  Future<PhoneFormatResult> verify(String phoneNumber);

  void dispose();
}

class PhoneFormatClient implements PhoneFormatGateway {
  PhoneFormatClient(this.configuration);

  final LocalApiConfiguration configuration;
  final Set<HttpClient> _active = {};
  bool _disposed = false;

  @override
  Future<PhoneFormatResult> verify(String phoneNumber) async {
    if (_disposed || !configuration.isConfigured) {
      return PhoneFormatResult.unavailable;
    }
    if (!isValidCameroonMobilePhoneFormat(phoneNumber)) {
      return PhoneFormatResult.rejected;
    }
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 3);
    _active.add(client);
    try {
      return await _request(
        client,
        phoneNumber,
      ).timeout(const Duration(seconds: 8));
    } on TimeoutException {
      return PhoneFormatResult.timeout;
    } on IOException {
      return PhoneFormatResult.unavailable;
    } on FormatException {
      return PhoneFormatResult.invalidResponse;
    } on StateError {
      return _disposed
          ? PhoneFormatResult.unavailable
          : PhoneFormatResult.invalidResponse;
    } finally {
      client.close(force: true);
      _active.remove(client);
    }
  }

  Future<PhoneFormatResult> _request(
    HttpClient client,
    String phoneNumber,
  ) async {
    final request = await client.postUrl(
      configuration.baseUri!.resolve('/api/v1/identity/phone-format'),
    );
    request.followRedirects = false;
    request.headers.contentType = ContentType.json;
    request.headers.set(HttpHeaders.acceptHeader, 'application/json');
    request.write(jsonEncode({'phoneNumber': phoneNumber}));
    final response = await request.close();
    if (response.statusCode == 400 || response.statusCode == 413) {
      return PhoneFormatResult.rejected;
    }
    final bytes = <int>[];
    await for (final chunk in response) {
      if (bytes.length + chunk.length > 4096) {
        return PhoneFormatResult.invalidResponse;
      }
      bytes.addAll(chunk);
    }
    if (_disposed) return PhoneFormatResult.unavailable;
    return interpretPhoneFormatResponse(
      response.statusCode,
      response.headers.contentType?.mimeType ?? '',
      utf8.decode(bytes),
    );
  }

  @override
  void dispose() {
    _disposed = true;
    for (final client in _active) {
      client.close(force: true);
    }
    _active.clear();
  }
}
