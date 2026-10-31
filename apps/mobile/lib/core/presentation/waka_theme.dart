import 'package:flutter/material.dart';

/// The three looks chosen by the founder: light black and white (the default), dark, and the national colours.
enum WakaThemeChoice {
  light('Clair'),
  dark('Sombre'),
  cameroon('Cameroun');

  const WakaThemeChoice(this.label);
  final String label;
}

/// The logo's colours in each theme: the road, its centre marking, the departure dot and the arrival ring.
@immutable
class WakaBrand extends ThemeExtension<WakaBrand> {
  const WakaBrand({required this.road, required this.marking, required this.departure, required this.arrivalFill,
      required this.arrivalRing});
  final Color road, marking, departure, arrivalFill, arrivalRing;

  @override
  WakaBrand copyWith({Color? road, Color? marking, Color? departure, Color? arrivalFill, Color? arrivalRing}) => WakaBrand(
      road: road ?? this.road, marking: marking ?? this.marking, departure: departure ?? this.departure,
      arrivalFill: arrivalFill ?? this.arrivalFill, arrivalRing: arrivalRing ?? this.arrivalRing);

  @override
  WakaBrand lerp(WakaBrand? other, double t) => t < 0.5 || other == null ? this : other;
}

const _ink = Color(0xFF111111), _paper = Color(0xFFFFFFFF), _night = Color(0xFF121212);
const cameroonGreen = Color(0xFF007A5E), cameroonRed = Color(0xFFCE1126), cameroonYellow = Color(0xFFFCD116);

ThemeData buildWakaTheme([WakaThemeChoice choice = WakaThemeChoice.light]) {
  final dark = choice == WakaThemeChoice.dark;
  final scheme = switch (choice) {
    WakaThemeChoice.light => ColorScheme.fromSeed(seedColor: _ink, brightness: Brightness.light).copyWith(
        primary: _ink, onPrimary: _paper, primaryContainer: const Color(0xFFEDEDED), onPrimaryContainer: _ink,
        secondary: const Color(0xFF555555), onSecondary: _paper, secondaryContainer: const Color(0xFFF1F1F1),
        onSecondaryContainer: _ink, surface: _paper, onSurface: _ink, onSurfaceVariant: const Color(0xFF5C5C5C),
        surfaceContainerLowest: _paper, surfaceContainerLow: const Color(0xFFF7F7F7), surfaceContainer: const Color(0xFFF3F3F3),
        surfaceContainerHigh: const Color(0xFFEEEEEE), surfaceContainerHighest: const Color(0xFFE8E8E8),
        outline: const Color(0xFFBDBDBD), outlineVariant: const Color(0xFFE0E0E0), error: const Color(0xFFB3261E)),
    WakaThemeChoice.dark => ColorScheme.fromSeed(seedColor: _ink, brightness: Brightness.dark).copyWith(
        primary: const Color(0xFFF2F2F2), onPrimary: _ink, primaryContainer: const Color(0xFF2A2A2A),
        onPrimaryContainer: const Color(0xFFF2F2F2), secondary: const Color(0xFFBDBDBD), onSecondary: _ink,
        secondaryContainer: const Color(0xFF262626), onSecondaryContainer: const Color(0xFFF2F2F2), surface: _night,
        onSurface: const Color(0xFFF2F2F2), onSurfaceVariant: const Color(0xFFB0B0B0), surfaceContainerLowest: const Color(0xFF0B0B0B),
        surfaceContainerLow: const Color(0xFF1A1A1A), surfaceContainer: const Color(0xFF1E1E1E),
        surfaceContainerHigh: const Color(0xFF242424), surfaceContainerHighest: const Color(0xFF2C2C2C),
        outline: const Color(0xFF5A5A5A), outlineVariant: const Color(0xFF333333), error: const Color(0xFFF2B8B5)),
    WakaThemeChoice.cameroon => ColorScheme.fromSeed(seedColor: cameroonGreen, brightness: Brightness.light).copyWith(
        primary: cameroonGreen, onPrimary: _paper, secondary: cameroonYellow, onSecondary: _ink,
        secondaryContainer: const Color(0xFFFFF4C2), onSecondaryContainer: _ink, tertiary: cameroonRed, onTertiary: _paper,
        surface: _paper, onSurface: _ink, error: cameroonRed, onError: _paper),
  };
  final brand = switch (choice) {
    WakaThemeChoice.light => const WakaBrand(road: _ink, marking: _paper, departure: _ink, arrivalFill: _paper, arrivalRing: _ink),
    WakaThemeChoice.dark => const WakaBrand(road: Color(0xFFF2F2F2), marking: _night, departure: Color(0xFFF2F2F2),
        arrivalFill: _night, arrivalRing: Color(0xFFF2F2F2)),
    WakaThemeChoice.cameroon => const WakaBrand(road: cameroonGreen, marking: cameroonYellow, departure: cameroonGreen,
        arrivalFill: cameroonRed, arrivalRing: cameroonGreen),
  };
  final base = ThemeData(useMaterial3: true, colorScheme: scheme, brightness: dark ? Brightness.dark : Brightness.light);
  final buttonShape = RoundedRectangleBorder(borderRadius: BorderRadius.circular(16));
  const buttonText = TextStyle(fontSize: 16, fontWeight: FontWeight.w600);
  return base.copyWith(
    scaffoldBackgroundColor: scheme.surface,
    extensions: [brand],
    appBarTheme: AppBarTheme(
      backgroundColor: scheme.surface,
      foregroundColor: scheme.onSurface,
      centerTitle: false,
      elevation: 0,
      scrolledUnderElevation: 0,
    ),
    textTheme: base.textTheme.apply(bodyColor: scheme.onSurface, displayColor: scheme.onSurface),
    inputDecorationTheme: InputDecorationTheme(
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(14)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: scheme.primary, width: 2)),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: scheme.surfaceContainerLow,
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 56),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        textStyle: buttonText,
        shape: buttonShape,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 56),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        textStyle: buttonText,
        shape: buttonShape,
        foregroundColor: scheme.onSurface,
      ),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: scheme.onSurface)),
  );
}
