import 'dart:convert';
import 'dart:math' as math;

import '../../../core/presentation/preference_store.dart';
import '../../identity/data/ride_models.dart';

/// A destination chosen before, kept on this device only (three at most) to choose it again in one touch and to name
/// past rides in the history.
class RecentPlace {
  const RecentPlace(this.label, this.point);
  final String label;
  final RidePoint point;
  Map<String, Object> toJson() => {'label': label, 'lat': point.latitude, 'lon': point.longitude};
}

const _key = 'waka_recent_places';

Future<List<RecentPlace>> loadRecentPlaces() async {
  try {
    final list = jsonDecode(await readPreference(_key) ?? '[]') as List;
    return [
      for (final e in list.take(3))
        RecentPlace((e as Map)['label'] as String, RidePoint((e['lat'] as num).toDouble(), (e['lon'] as num).toDouble())),
    ];
  } catch (_) {
    return const [];
  }
}

/// [place] first, the others after it, three at most; returns what is kept.
Future<List<RecentPlace>> rememberPlace(List<RecentPlace> current, RecentPlace place) async {
  final kept = [place, ...current.where((r) => r.label != place.label)].take(3).toList();
  await writePreference(_key, jsonEncode([for (final r in kept) r.toJson()]));
  return kept;
}

/// The name of a recent place within about 150 m of [point], or null.
String? nameNear(List<RecentPlace> places, RidePoint? point) {
  if (point == null) return null;
  for (final p in places) {
    final dLat = (p.point.latitude - point.latitude) * 111000;
    final dLon = (p.point.longitude - point.longitude) * 111000 * math.cos(point.latitude * math.pi / 180);
    if (dLat * dLat + dLon * dLon < 150 * 150) return p.label;
  }
  return null;
}
