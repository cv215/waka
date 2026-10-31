import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'core/presentation/theme_choice.dart';
import 'core/presentation/waka_theme.dart';
import 'features/identity/application/login_flow.dart';
import 'features/identity/presentation/welcome_page.dart';

class WakaApp extends StatelessWidget {
  const WakaApp({super.key, this.flow});
  /// Tests give their own sign-in flow; the app builds the real one.
  final LoginFlow? flow;

  @override
  Widget build(BuildContext context) => ValueListenableBuilder<WakaThemeChoice>(
    valueListenable: wakaThemeChoice,
    builder: (context, choice, _) => MaterialApp(
      title: 'WAKA',
      debugShowCheckedModeBanner: false,
      locale: const Locale('fr'),
      supportedLocales: const [Locale('fr')],
      localizationsDelegates: GlobalMaterialLocalizations.delegates,
      theme: buildWakaTheme(choice),
      home: WelcomePage(flow: flow),
    ),
  );
}
