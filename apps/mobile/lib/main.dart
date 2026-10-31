import 'package:flutter/material.dart';

import 'app.dart';
import 'core/presentation/theme_choice.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await loadThemeChoice();
  runApp(const WakaApp());
}
