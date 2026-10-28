import 'package:flutter/foundation.dart';

/// Les cibles distantes restent fermees jusqu'a leur qualification.
class LocalApiConfiguration {
  const LocalApiConfiguration._(this.baseUri);

  final Uri? baseUri;

  bool get isConfigured => baseUri != null;

  factory LocalApiConfiguration.fromDefines() => LocalApiConfiguration.parse(
    environment: const String.fromEnvironment('WAKA_ENV'),
    baseUrl: const String.fromEnvironment('WAKA_API_BASE_URL'),
    releaseMode: kReleaseMode,
  );

  factory LocalApiConfiguration.parse({
    required String environment,
    required String baseUrl,
    required bool releaseMode,
  }) {
    final uri = Uri.tryParse(baseUrl);
    final valid =
        !releaseMode &&
        environment == 'dev' &&
        uri != null &&
        uri.scheme == 'http' &&
        const {'10.0.2.2', '127.0.0.1', 'localhost'}.contains(uri.host) &&
        uri.hasPort &&
        uri.port > 0 &&
        uri.port <= 65535 &&
        uri.userInfo.isEmpty &&
        uri.path.isEmpty &&
        !uri.hasQuery &&
        !uri.hasFragment;
    return LocalApiConfiguration._(valid ? uri : null);
  }
}
