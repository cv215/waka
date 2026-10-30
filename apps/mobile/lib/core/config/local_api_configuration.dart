import 'package:flutter/foundation.dart';

/// Les cibles distantes restent fermees jusqu'a leur qualification.
class LocalApiConfiguration {
  const LocalApiConfiguration._(this.baseUri);

  final Uri? baseUri;

  bool get isConfigured => baseUri != null;

  /// The local web preview passes `origin`: it calls the relay that served the page, on the home network.
  factory LocalApiConfiguration.fromDefines() {
    const baseUrl = String.fromEnvironment('WAKA_API_BASE_URL');
    final web = kIsWeb && baseUrl == 'origin';
    return LocalApiConfiguration.parse(
      environment: const String.fromEnvironment('WAKA_ENV'),
      baseUrl: web ? Uri.base.origin : baseUrl,
      releaseMode: kReleaseMode,
      homeNetwork: web,
    );
  }

  factory LocalApiConfiguration.parse({
    required String environment,
    required String baseUrl,
    required bool releaseMode,
    bool homeNetwork = false,
  }) {
    final uri = Uri.tryParse(baseUrl);
    final valid =
        !releaseMode &&
        environment == 'dev' &&
        uri != null &&
        uri.scheme == 'http' &&
        (const {'10.0.2.2', '127.0.0.1', 'localhost'}.contains(uri.host) ||
            (homeNetwork && isPrivateNetworkHost(uri.host))) &&
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

/// IPv4 addresses of a home network (10/8, 172.16/12, 192.168/16) only; never a public address or a name.
bool isPrivateNetworkHost(String host) {
  final parts = host.split('.');
  if (parts.length != 4) return false;
  final n = <int>[];
  for (final part in parts) {
    if (!RegExp(r'^(0|[1-9][0-9]{0,2})$').hasMatch(part)) return false;
    final value = int.parse(part);
    if (value > 255) return false;
    n.add(value);
  }
  return n[0] == 10 || (n[0] == 172 && n[1] >= 16 && n[1] <= 31) || (n[0] == 192 && n[1] == 168);
}
