import 'package:flutter/material.dart';

ThemeData buildWakaTheme() {
  final scheme = ColorScheme.fromSeed(seedColor: const Color(0xFF145E4D));
  final base = ThemeData(useMaterial3: true, colorScheme: scheme);
  final buttonShape = RoundedRectangleBorder(
    borderRadius: BorderRadius.circular(14),
  );
  return base.copyWith(
    scaffoldBackgroundColor: const Color(0xFFF4F7F6),
    appBarTheme: const AppBarTheme(
      backgroundColor: Color(0xFFF4F7F6),
      foregroundColor: Color(0xFF152A25),
      centerTitle: false,
      scrolledUnderElevation: 0,
    ),
    textTheme: base.textTheme.apply(
      bodyColor: const Color(0xFF152A25),
      displayColor: const Color(0xFF152A25),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        minimumSize: const Size(48, 52),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        shape: buttonShape,
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        minimumSize: const Size(48, 52),
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 16),
        textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        shape: buttonShape,
      ),
    ),
  );
}
