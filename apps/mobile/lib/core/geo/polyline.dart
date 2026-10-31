/// Google's encoded polyline (five decimals) to a list of (latitude, longitude). Throws [FormatException] on a
/// malformed or oversized text; drawing a route never trusts it further than this.
List<(double, double)> decodePolyline(String encoded) {
  if (encoded.length < 2 || encoded.length > 20000 || !RegExp(r'^[\x3F-\x7E]+$').hasMatch(encoded)) {
    throw const FormatException();
  }
  final points = <(double, double)>[];
  var index = 0, lat = 0, lon = 0;
  int next() {
    var shift = 0, result = 0;
    while (true) {
      if (index >= encoded.length || shift > 30) throw const FormatException();
      final b = encoded.codeUnitAt(index++) - 63;
      result |= (b & 0x1f) << shift;
      shift += 5;
      if (b < 0x20) break;
    }
    return (result & 1) != 0 ? ~(result >> 1) : result >> 1;
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
