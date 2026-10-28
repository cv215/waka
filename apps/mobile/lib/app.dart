import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/presentation/waka_theme.dart';
import 'features/home/presentation/home_page.dart';

class WakaApp extends StatelessWidget {
  const WakaApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WAKA',
      debugShowCheckedModeBanner: false,
      locale: const Locale('fr'),
      supportedLocales: const [Locale('fr')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: buildWakaTheme(),
      home: const HomePage(),
    );
  }
}
