import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';

void main() {
  LocalApiConfiguration parse(String url, {bool home = true, bool release = false}) =>
      LocalApiConfiguration.parse(environment: 'dev', baseUrl: url, releaseMode: release, homeNetwork: home);

  test('apercu web : seules les adresses du reseau de la maison sont acceptees', () {
    expect(parse('http://192.168.1.29:8090').isConfigured, isTrue);
    expect(parse('http://10.0.0.7:8090').isConfigured, isTrue);
    expect(parse('http://172.20.1.2:8090').isConfigured, isTrue);
    expect(parse('http://172.32.1.2:8090').isConfigured, isFalse);
    expect(parse('http://8.8.8.8:8090').isConfigured, isFalse);
    expect(parse('http://waka.example:8090').isConfigured, isFalse);
    expect(parse('http://192.168.1.290:8090').isConfigured, isFalse);
    expect(parse('http://192.168.01.2:8090').isConfigured, isFalse);
    expect(parse('https://192.168.1.29:8090').isConfigured, isFalse);
  });

  test('lien de test : seulement https sous trycloudflare.com, et seulement pour l apercu web', () {
    expect(parse('https://calm-river-bright-sun.trycloudflare.com').isConfigured, isTrue);
    expect(parse('http://calm-river.trycloudflare.com').isConfigured, isFalse);
    expect(parse('https://trycloudflare.com.evil.example').isConfigured, isFalse);
    expect(parse('https://evil.example').isConfigured, isFalse);
    expect(parse('https://calm-river.trycloudflare.com/api').isConfigured, isFalse);
    expect(parse('https://calm-river.trycloudflare.com', home: false).isConfigured, isFalse);
    expect(parse('https://calm-river.trycloudflare.com', release: true).isConfigured, isFalse);
  });

  test('hors apercu web ou en version finale, le reseau de la maison reste ferme', () {
    expect(parse('http://192.168.1.29:8090', home: false).isConfigured, isFalse);
    expect(parse('http://192.168.1.29:8090', release: true).isConfigured, isFalse);
    expect(parse('http://10.0.2.2:8080', home: false).isConfigured, isTrue);
  });
}
