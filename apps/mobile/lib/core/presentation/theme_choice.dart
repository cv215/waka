import 'package:flutter/foundation.dart';

import 'preference_store.dart';
import 'waka_theme.dart';

/// The theme in use, for the whole app; light black and white unless the user chose another one.
final ValueNotifier<WakaThemeChoice> wakaThemeChoice = ValueNotifier(WakaThemeChoice.light);

Future<void> loadThemeChoice() async {
  final name = await readPreference('waka_theme');
  wakaThemeChoice.value = WakaThemeChoice.values.where((c) => c.name == name).firstOrNull ?? WakaThemeChoice.light;
}

Future<void> chooseTheme(WakaThemeChoice choice) async {
  wakaThemeChoice.value = choice;
  await writePreference('waka_theme', choice.name);
}
