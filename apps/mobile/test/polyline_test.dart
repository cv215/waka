import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/core/geo/polyline.dart';

void main() {
  test('un vrai tracé Google de Yaoundé (décalages négatifs) se décode, du centre à Bastos', () {
    // Received from Google Routes for Poste centrale -> Bastos (31 October 2026).
    const text = r'mfrVukheA{@PKBCJHHXPfCjESTO^AR@RUR[HkAJsHtAe@BeAL]?c@G]SYGg@Sm@MeAGy@Ii@UE?gJlCWLwDXyEJoBEyBScKuAcClAaFpBiFfA}IhCaA^_@^a@h@MRc@hAaCaBSGcCa@iEw@qDIuCEo@Fg@V}FrDw@`@k@NsAHw@AwFSs@QWMIj@]fBWt@eDbH';
    final points = decodePolyline(text);
    expect(points, hasLength(63));
    expect(points.first, (3.86679, 11.51691));
    expect(points.last, (3.89352, 11.50901));
  });

  test('exemple de Google : longitudes négatives', () {
    expect(decodePolyline('_p~iF~ps|U_ulLnnqC_mqNvxq`@'), [(38.5, -120.2), (40.7, -120.95), (43.252, -126.453)]);
  });
}
