import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/config/local_api_configuration.dart';
import '../../../core/presentation/waka_logo.dart';
import '../../home/presentation/home_shell.dart';
import '../application/login_flow.dart';
import '../data/login_client.dart';
import '../data/login_models.dart';
import '../data/phone_format_client.dart';
import '../data/platform_login_store.dart';
import '../data/profile_models.dart';
import 'sign_in_page.dart';

/// Step 1 of the redesign: one question per screen — the number, the code (checked on its sixth digit), the full name —
/// then straight to the map. A returning client goes to the map at once. Any unusual session state (storage to retry,
/// logout to finish, profile to read again…) is left to the full account page, which already handles each case.
class WelcomePage extends StatefulWidget {
  const WelcomePage({super.key, this.flow, this.home});
  final LoginFlow? flow;
  /// What a signed-in client with a name sees; by default the four tabs (the map first). Tests replace it.
  final Widget Function(BuildContext context, LoginFlow flow)? home;
  @override
  State<WelcomePage> createState() => _WelcomePageState();
}

class _WelcomePageState extends State<WelcomePage> {
  late final LoginFlow _flow = widget.flow ?? LoginFlow(LoginClient(LocalApiConfiguration.fromDefines()), platformLoginStore());
  final _phone = TextEditingController(), _code = TextEditingController(), _name = TextEditingController();
  String? _phoneError, _nameError;
  bool _hadSession = false;

  @override
  void initState() {
    super.initState();
    _flow.addListener(_changed);
    _flow.initialize().then((_) {
      final known = _flow.attempt?.phone;
      if (mounted && known != null) _phone.text = known.replaceFirst('+237', '');
    });
  }

  @override
  void dispose() {
    _flow.removeListener(_changed);
    _phone.dispose();
    _code.dispose();
    _name.dispose();
    if (widget.flow == null) _flow.dispose();
    super.dispose();
  }

  /// Signed out from the account page: back to the first screen, nothing left on top.
  void _changed() {
    final has = _flow.session != null;
    if (_hadSession && !has && mounted) {
      Navigator.of(context).popUntil((route) => route.isFirst);
      _code.clear();
    }
    _hadSession = has;
  }

  String? get _loginError => switch (_flow.failure) {
    LoginFailure.invalid => 'Vérifie ce que tu as saisi.',
    LoginFailure.denied => 'Code incorrect ou expiré.',
    LoginFailure.conflict => 'Ce code ne correspond pas à la demande. Vérifie-le.',
    LoginFailure.limited => 'Trop d’essais. Patiente un peu.',
    LoginFailure.unavailable => 'Service indisponible. Réessaie plus tard.',
    LoginFailure.uncertain => 'Pas de réponse. Réessaie.',
    LoginFailure.invalidResponse => 'Réponse illisible. Réessaie.',
    LoginFailure.storage => 'Stockage du téléphone indisponible. Réessaie.',
    null => null,
  };

  Future<void> _sendPhone() async {
    final full = '+237${_phone.text.trim()}';
    if (!isValidCameroonMobilePhoneFormat(full)) {
      setState(() => _phoneError = 'Un numéro mobile à 9 chiffres.');
      return;
    }
    setState(() => _phoneError = null);
    await _flow.request(full);
  }

  Future<void> _verify(String code) async {
    if (code.length != 6 || _flow.busy) return;
    await _flow.verify(code);
    if (_flow.session != null) {
      _code.clear();
      // Reads the account and the profile: the name screen or the map follows.
      await _flow.checkAccount();
    } else if (_flow.failure == LoginFailure.denied || _flow.failure == LoginFailure.conflict) {
      _code.clear();
    }
  }

  Future<void> _resend() async {
    final phone = _flow.attempt?.phone;
    await _flow.restart();
    _code.clear();
    if (phone != null && _flow.attempt == null && _flow.failure == null) await _flow.request(phone);
  }

  Future<void> _saveName() async {
    final name = _name.text.trim().replaceAll(RegExp(r'\s+'), ' ');
    if (name.split(' ').where((w) => w.isNotEmpty).length < 2) {
      setState(() => _nameError = 'Ton prénom et ton nom.');
      return;
    }
    if (!isAcceptableProfileName(name)) {
      setState(() => _nameError = 'Ce nom ne peut pas être utilisé.');
      return;
    }
    setState(() => _nameError = null);
    await _flow.saveDisplayName(name);
    if (mounted && _flow.profileFailure != null) setState(() => _nameError = 'Le nom n’a pas été enregistré. Réessaie.');
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(animation: _flow, builder: (context, _) {
    final f = _flow;
    if (!f.ready) {
      return f.failure == null ? const _Splash() : _Step(
        title: 'Démarrage impossible',
        field: Text(_loginError ?? ''),
        action: FilledButton(onPressed: f.busy ? null : f.initialize, child: const Text('Réessayer')),
      );
    }
    if (f.session != null) {
      final profile = f.profile;
      if (f.confirmedNow && profile != null && f.pendingProfile == null) {
        if (profile.displayName == null) return _nameStep(f);
        return widget.home?.call(context, f) ?? HomeShell(login: f);
      }
      if (f.busy) return const _Splash();
      return SignInPage(flow: f);
    }
    final challenge = f.attempt?.challenge;
    return challenge == null ? _phoneStep(f) : _codeStep(f, challenge);
  });

  Widget _phoneStep(LoginFlow f) => _Step(
    top: const WakaLogo(height: 30),
    title: 'Ton numéro',
    field: TextField(
      controller: _phone,
      autofocus: true,
      readOnly: f.busy,
      keyboardType: TextInputType.phone,
      autofillHints: const [AutofillHints.telephoneNumberNational],
      inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(9)],
      style: const TextStyle(fontSize: 22, letterSpacing: 1),
      onSubmitted: (_) => _sendPhone(),
      decoration: InputDecoration(prefixText: '+237  ', hintText: '6XX XX XX XX', errorText: _phoneError ?? _loginError),
    ),
    action: FilledButton(onPressed: f.busy ? null : _sendPhone, child: _label(f, f.attempt == null ? 'Continuer' : 'Réessayer')),
  );

  Widget _codeStep(LoginFlow f, LoginChallenge challenge) {
    final expired = !DateTime.now().toUtc().isBefore(challenge.expiresAt);
    final note = expired
        ? 'Ce code a expiré.'
        : switch (challenge.submission) {
            'SENDING' => 'Envoi en cours…',
            'REJECTED' => 'L’envoi a échoué.',
            _ => null,
          };
    return _Step(
      back: f.busy ? null : () { _code.clear(); f.restart(); },
      title: 'Le code reçu',
      field: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        TextField(
          controller: _code,
          autofocus: true,
          readOnly: f.busy || expired,
          keyboardType: TextInputType.number,
          autofillHints: const [AutofillHints.oneTimeCode],
          inputFormatters: [FilteringTextInputFormatter.digitsOnly, LengthLimitingTextInputFormatter(6)],
          style: const TextStyle(fontSize: 30, letterSpacing: 14, fontWeight: FontWeight.w600),
          onChanged: _verify,
          decoration: InputDecoration(hintText: '······', errorText: _loginError),
        ),
        if (note != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(note)),
        if (f.busy) const Padding(padding: EdgeInsets.only(top: 16), child: LinearProgressIndicator()),
      ]),
      action: challenge.submission == 'SENDING' && !expired
          ? TextButton(onPressed: f.busy ? null : () => f.request(f.attempt!.phone), child: const Text('Vérifier l’envoi'))
          : TextButton(onPressed: f.busy ? null : _resend, child: const Text('Renvoyer le code')),
    );
  }

  Widget _nameStep(LoginFlow f) => _Step(
    title: 'Ton nom complet',
    field: TextField(
      controller: _name,
      autofocus: true,
      readOnly: f.busy,
      maxLength: 80,
      textCapitalization: TextCapitalization.words,
      autofillHints: const [AutofillHints.name],
      style: const TextStyle(fontSize: 20),
      onSubmitted: (_) => _saveName(),
      decoration: InputDecoration(hintText: 'Prénom Nom', counterText: '', errorText: _nameError),
    ),
    action: FilledButton(onPressed: f.busy ? null : _saveName, child: _label(f, 'C’est parti')),
  );

  Widget _label(LoginFlow f, String text) => f.busy
      ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
      : Text(text);
}

/// One screen of the sign-up: an optional logo or back arrow, a title, one field, one action at the bottom.
class _Step extends StatelessWidget {
  const _Step({required this.title, required this.field, required this.action, this.top, this.back});
  final String title;
  final Widget field, action;
  final Widget? top;
  final VoidCallback? back;

  @override
  Widget build(BuildContext context) => Scaffold(
    body: SafeArea(child: LayoutBuilder(builder: (context, box) => SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(24, 16, 24, 24),
      // The action stays at the bottom of the screen; on a short screen everything scrolls.
      child: Align(
        alignment: Alignment.topCenter,
        child: ConstrainedBox(
          constraints: BoxConstraints(maxWidth: 480, minHeight: box.maxHeight - 40),
          child: IntrinsicHeight(child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            SizedBox(height: 48, child: Align(alignment: Alignment.centerLeft, child: back != null
                ? IconButton(tooltip: 'Retour', onPressed: back, icon: const Icon(Icons.arrow_back))
                : top ?? const SizedBox.shrink())),
            const SizedBox(height: 48),
            Semantics(header: true, child: Text(title,
                style: Theme.of(context).textTheme.headlineMedium?.copyWith(fontWeight: FontWeight.w700))),
            const SizedBox(height: 24),
            field,
            const Spacer(),
            const SizedBox(height: 24),
            action,
          ])),
        ),
      ),
    ))),
  );
}

class _Splash extends StatelessWidget {
  const _Splash();
  @override
  Widget build(BuildContext context) => const Scaffold(body: Center(child: WakaLogoMark(size: 96)));
}
