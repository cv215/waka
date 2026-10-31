import 'package:flutter/material.dart';

import 'waka_theme.dart';

/// The WAKA symbol: the W drawn as a road with its dashed centre line, a dot for the departure and a ring for the
/// arrival (same geometry as tools/generate_brand_assets.py). Colours follow the theme.
class WakaLogoMark extends StatelessWidget {
  const WakaLogoMark({super.key, this.size = 48});
  final double size;

  @override
  Widget build(BuildContext context) => Semantics(
    label: 'WAKA',
    image: true,
    child: CustomPaint(size: Size.square(size), painter: _RoadW(Theme.of(context).extension<WakaBrand>() ??
        buildWakaTheme().extension<WakaBrand>()!)),
  );
}

/// The symbol followed by the word, for the first screen.
class WakaLogo extends StatelessWidget {
  const WakaLogo({super.key, this.height = 40});
  final double height;

  @override
  Widget build(BuildContext context) => Row(mainAxisSize: MainAxisSize.min, children: [
    WakaLogoMark(size: height * 1.3),
    SizedBox(width: height * 0.25),
    ExcludeSemantics(child: Text('WAKA', style: TextStyle(fontSize: height * 0.7, fontWeight: FontWeight.w800,
        letterSpacing: height * 0.08, color: Theme.of(context).colorScheme.onSurface))),
  ]);
}

class _RoadW extends CustomPainter {
  _RoadW(this.brand);
  final WakaBrand brand;
  static const _points = [Offset(20, 34), Offset(35, 70), Offset(50, 44), Offset(65, 70), Offset(80, 34)];

  @override
  void paint(Canvas canvas, Size size) {
    canvas.scale(size.width / 100, size.height / 100);
    final road = Path()..addPolygon(_points, false);
    canvas.drawPath(road, Paint()
      ..color = brand.road
      ..style = PaintingStyle.stroke
      ..strokeWidth = 12
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round);
    final marking = Paint()
      ..color = brand.marking
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2;
    for (final metric in road.computeMetrics()) {
      for (double at = 0; at < metric.length; at += 8) {
        canvas.drawPath(metric.extractPath(at, (at + 4.5).clamp(0, metric.length)), marking);
      }
    }
    canvas.drawCircle(_points.first, 8, Paint()..color = brand.departure);
    canvas.drawCircle(_points.last, 7.5, Paint()..color = brand.arrivalFill);
    canvas.drawCircle(_points.last, 7.5, Paint()
      ..color = brand.arrivalRing
      ..style = PaintingStyle.stroke
      ..strokeWidth = 4);
  }

  @override
  bool shouldRepaint(_RoadW old) => old.brand != brand;
}
