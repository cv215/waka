import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/config/local_api_configuration.dart';
import 'package:waka_mobile/features/identity/data/phone_format_client.dart';

String syntheticNumber([String firstDigit = '1']) =>
    '6$firstDigit${List.filled(7, '2').join()}';

void main() {
  test('Le contrôle local suit les caractères admis par le serveur', () {
    expect(isValidCameroonMobilePhoneFormat(syntheticNumber()), isTrue);
    expect(
      isValidCameroonMobilePhoneFormat('+237 ${syntheticNumber()}'),
      isTrue,
    );
    expect(
      isValidCameroonMobilePhoneFormat('00237-${syntheticNumber()}'),
      isTrue,
    );
    expect(
      isValidCameroonMobilePhoneFormat('6\u00a01${List.filled(7, '2').join()}'),
      isTrue,
    );
    expect(isValidCameroonMobilePhoneFormat(''), isFalse);
    expect(
      isValidCameroonMobilePhoneFormat('5${syntheticNumber().substring(1)}'),
      isFalse,
    );
    expect(
      isValidCameroonMobilePhoneFormat('6${List.filled(7, '2').join()}'),
      isFalse,
    );
    expect(
      isValidCameroonMobilePhoneFormat('6${List.filled(9, '2').join()}'),
      isFalse,
    );
    expect(
      isValidCameroonMobilePhoneFormat('٦${List.filled(8, '٢').join()}'),
      isFalse,
    );
    expect(
      isValidCameroonMobilePhoneFormat('+237+${syntheticNumber()}'),
      isFalse,
    );
    expect(
      isValidCameroonMobilePhoneFormat('237${syntheticNumber()}'),
      isFalse,
    );
    expect(isValidCameroonMobilePhoneFormat('x${syntheticNumber()}'), isFalse);
    expect(isValidCameroonMobilePhoneFormat('${syntheticNumber()}\n'), isFalse);
    expect(isValidCameroonMobilePhoneFormat('${syntheticNumber()}\r'), isFalse);
    expect(
      isValidCameroonMobilePhoneFormat('${syntheticNumber()}\u0000'),
      isFalse,
    );
  });

  test('Seule la réponse exacte du serveur est acceptée', () {
    expect(
      interpretPhoneFormatResponse(
        200,
        'application/json; charset=utf-8',
        '{"formatValid":true,"ownershipVerified":false,"country":"CM"}',
      ),
      PhoneFormatResult.accepted,
    );
    for (final body in [
      '',
      '{',
      '[]',
      '{}',
      '{"formatValid":true,"ownershipVerified":false,"country":"CM","extra":1}',
      '{"formatValid":false,"ownershipVerified":false,"country":"CM"}',
      '{"formatValid":true,"ownershipVerified":true,"country":"CM"}',
      '{"formatValid":true,"ownershipVerified":false,"country":"FR"}',
      '{"formatValid":"true","ownershipVerified":false,"country":"CM"}',
    ]) {
      expect(
        interpretPhoneFormatResponse(200, 'application/json', body),
        PhoneFormatResult.invalidResponse,
      );
    }
    expect(
      interpretPhoneFormatResponse(400, 'application/problem+json', ''),
      PhoneFormatResult.rejected,
    );
    expect(
      interpretPhoneFormatResponse(413, 'application/problem+json', ''),
      PhoneFormatResult.rejected,
    );
    expect(
      interpretPhoneFormatResponse(503, 'application/problem+json', ''),
      PhoneFormatResult.unavailable,
    );
    expect(
      interpretPhoneFormatResponse(200, 'text/html', '{"formatValid":true}'),
      PhoneFormatResult.invalidResponse,
    );
  });

  test(
    'Configuration absente retourne une indisponibilité générique',
    () async {
      final client = PhoneFormatClient(
        LocalApiConfiguration.parse(
          environment: '',
          baseUrl: '',
          releaseMode: false,
        ),
      );
      expect(
        await client.verify(syntheticNumber()),
        PhoneFormatResult.unavailable,
      );
      client.dispose();
    },
  );
}
