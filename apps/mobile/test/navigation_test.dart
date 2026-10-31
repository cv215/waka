import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:waka_mobile/app.dart';
import 'package:waka_mobile/core/presentation/theme_choice.dart';
import 'package:waka_mobile/core/presentation/waka_logo.dart';
import 'package:waka_mobile/core/presentation/waka_theme.dart';
import 'package:waka_mobile/features/identity/application/login_flow.dart';
import 'package:waka_mobile/features/identity/data/login_client.dart';
import 'package:waka_mobile/features/identity/data/login_models.dart';
import 'package:waka_mobile/features/identity/data/profile_models.dart';
import 'package:waka_mobile/features/identity/presentation/welcome_page.dart';

import 'login_flow_test.dart' show Gateway, MemoryStore;

/// The K-contract login server of login_flow_test, plus a profile that starts without a name.
class SignUpGateway extends Gateway implements ProfileGateway {
  String? name;
  final codes = <String>[];
  @override
  Future<LoginSession> verify(LoginAttempt attempt, String client, String code) {
    codes.add(code);
    return super.verify(attempt, client, code);
  }

  ProfileSnapshot _profile(LoginSession s) => ProfileSnapshot(accountId: s.accountId, version: name == null ? 1 : 2,
      displayName: name, updatedAt: null, serverTime: DateTime.now().toUtc(), replay: false);
  @override
  Future<ProfileSnapshot> profile(LoginSession session, String client) async => _profile(session);
  @override
  Future<ProfileSnapshot> updateProfile(LoginSession session, String client, ProfileCommand command) async {
    name = command.displayName;
    return _profile(session);
  }
}

Future<SignUpGateway> _open(WidgetTester tester, {MemoryStore? store, SignUpGateway? gateway}) async {
  final server = gateway ?? SignUpGateway();
  final flow = LoginFlow(server, store ?? MemoryStore());
  addTearDown(flow.dispose);
  await tester.pumpWidget(MaterialApp(theme: buildWakaTheme(), home: WelcomePage(flow: flow,
      home: (context, f) => Scaffold(body: Text('carte de ${f.profile!.displayName}')))));
  await tester.pumpAndSettle();
  return server;
}

void main() {
  testWidgets('numéro, code validé au 6e chiffre, nom complet, puis directement la carte', (tester) async {
    final server = await _open(tester);
    expect(find.byType(WakaLogo), findsOneWidget);
    expect(find.text('Ton numéro'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '12345');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Un numéro mobile à 9 chiffres.'), findsOneWidget);
    expect(server.requestKeys, isEmpty);

    await tester.enterText(find.byType(TextField), '699000001');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    expect(find.text('Le code reçu'), findsOneWidget);
    expect(find.text('Renvoyer le code'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '48213');
    await tester.pumpAndSettle();
    expect(server.codes, isEmpty, reason: 'nothing sent before the sixth digit');
    await tester.enterText(find.byType(TextField), '482130');
    await tester.pumpAndSettle();
    expect(server.codes, ['482130'], reason: 'no button: the sixth digit sends it');

    expect(find.text('Ton nom complet'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'Jean');
    await tester.tap(find.text('C’est parti'));
    await tester.pumpAndSettle();
    expect(find.text('Ton prénom et ton nom.'), findsOneWidget);
    await tester.enterText(find.byType(TextField), '  Jean-Paul   Mballa ');
    await tester.tap(find.text('C’est parti'));
    await tester.pumpAndSettle();
    expect(server.name, 'Jean-Paul Mballa');
    expect(find.text('carte de Jean-Paul Mballa'), findsOneWidget);
  });

  testWidgets('un client déjà inscrit arrive tout de suite sur la carte', (tester) async {
    final store = MemoryStore(), server = SignUpGateway()..name = 'Awa Ngono';
    final first = LoginFlow(server, store);
    await first.initialize();
    await first.request('699000001');
    await first.verify('111111');
    first.dispose();
    await _open(tester, store: store, gateway: server);
    expect(find.text('carte de Awa Ngono'), findsOneWidget);
    expect(find.text('Ton numéro'), findsNothing);
  });

  testWidgets('changer son nom ne fait pas quitter les écrans principaux', (tester) async {
    final store = MemoryStore(), server = SignUpGateway()..name = 'Awa Ngono';
    final flow = LoginFlow(server, store);
    addTearDown(flow.dispose);
    await flow.initialize();
    await flow.request('699000001');
    await flow.verify('111111');
    await flow.checkAccount();
    var built = 0;
    await tester.pumpWidget(MaterialApp(home: WelcomePage(flow: flow, home: (context, f) {
      return _Counted(onCreate: () => built++, name: f.profile?.displayName ?? '');
    })));
    await tester.pumpAndSettle();
    expect(built, 1);
    await flow.saveDisplayName('Awa Ngono Mbida');
    await tester.pumpAndSettle();
    expect(server.name, 'Awa Ngono Mbida');
    expect(built, 1, reason: 'the same screens stay: the tab in use is kept');
  });

  testWidgets('le retour depuis le code ramène au numéro', (tester) async {
    await _open(tester);
    await tester.enterText(find.byType(TextField), '699000001');
    await tester.tap(find.text('Continuer'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Retour'));
    await tester.pumpAndSettle();
    expect(find.text('Ton numéro'), findsOneWidget);
  });

  testWidgets('trois thèmes : clair noir et blanc par défaut, sombre, Cameroun', (tester) async {
    expect(wakaThemeChoice.value, WakaThemeChoice.light);
    final light = buildWakaTheme();
    expect(light.colorScheme.primary, const Color(0xFF111111));
    expect(light.colorScheme.surface, const Color(0xFFFFFFFF));
    expect(buildWakaTheme(WakaThemeChoice.dark).brightness, Brightness.dark);
    final cameroon = buildWakaTheme(WakaThemeChoice.cameroon);
    expect([cameroon.colorScheme.primary, cameroon.colorScheme.secondary, cameroon.colorScheme.error],
        [cameroonGreen, cameroonYellow, cameroonRed]);

    final flow = LoginFlow(SignUpGateway(), MemoryStore());
    addTearDown(flow.dispose);
    await tester.pumpWidget(WakaApp(flow: flow));
    await tester.pumpAndSettle();
    expect(Theme.of(tester.element(find.text('Ton numéro'))).colorScheme.primary, const Color(0xFF111111));
    wakaThemeChoice.value = WakaThemeChoice.dark;
    addTearDown(() => wakaThemeChoice.value = WakaThemeChoice.light);
    await tester.pumpAndSettle();
    expect(Theme.of(tester.element(find.text('Ton numéro'))).brightness, Brightness.dark);
  });

  testWidgets('petit écran et texte agrandi : le premier écran reste utilisable et accessible', (tester) async {
    tester.view.physicalSize = const Size(320, 640);
    tester.view.devicePixelRatio = 1;
    tester.platformDispatcher.textScaleFactorTestValue = 2;
    addTearDown(tester.view.reset);
    addTearDown(tester.platformDispatcher.clearTextScaleFactorTestValue);
    final semantics = tester.ensureSemantics();
    try {
      await _open(tester);
      expect(tester.takeException(), isNull);
      await tester.ensureVisible(find.text('Continuer'));
      await expectLater(tester, meetsGuideline(androidTapTargetGuideline));
      await expectLater(tester, meetsGuideline(labeledTapTargetGuideline));
      await expectLater(tester, meetsGuideline(textContrastGuideline));
    } finally {
      semantics.dispose();
    }
  });
}

class _Counted extends StatefulWidget {
  const _Counted({required this.onCreate, required this.name});
  final VoidCallback onCreate;
  final String name;
  @override
  State<_Counted> createState() => _CountedState();
}

class _CountedState extends State<_Counted> {
  @override
  void initState() {
    super.initState();
    widget.onCreate();
  }

  @override
  Widget build(BuildContext context) => Scaffold(body: Text(widget.name));
}
