/// Google's encoded polyline (five decimals) to a list of (latitude, longitude). Throws [FormatException] on a
/// malformed or oversized text; drawing a route never trusts it further than this.
///
/// Plain arithmetic only: in a browser, Dart's bitwise operators work on unsigned 32-bit values (`~x` is never
/// negative), which would turn every negative offset into a huge one. Multiplication, `~/` and `%` behave the same
/// on the phone and on the web.
List<(double, double)> decodePolyline(String encoded) {
  if (encoded.length < 2 || encoded.length > 20000 || !RegExp(r'^[\x3F-\x7E]+$').hasMatch(encoded)) {
    throw const FormatException();
  }
  final points = <(double, double)>[];
  var index = 0, lat = 0, lon = 0;
  int next() {
    var factor = 1, result = 0;
    while (true) {
      if (index >= encoded.length || factor > 1 << 30) throw const FormatException();
      final b = encoded.codeUnitAt(index++) - 63;
      result += (b % 32) * factor;
      factor *= 32;
      if (b < 32) break;
    }
    // Zig-zag: an odd value is a negative offset.
    return result.isOdd ? -((result + 1) ~/ 2) : result ~/ 2;
  }
  while (index < encoded.length) {
    lat += next();
    lon += next();
    final point = (lat / 1e5, lon / 1e5);
    if (point.$1.abs() > 90 || point.$2.abs() > 180) throw const FormatException();
    points.add(point);
  }
  if (points.length < 2 || points.length > 5000) throw const FormatException();
  return points;
}
