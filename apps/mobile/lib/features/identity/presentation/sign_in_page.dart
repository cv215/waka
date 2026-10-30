import '../../payments/presentation/wallet_page.dart';
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/config/local_api_configuration.dart';
import '../../../core/presentation/waka_page.dart';
import '../../drivers/presentation/driver_access_page.dart';
import '../../drivers/presentation/driver_application_page.dart';
import '../../privacy/presentation/my_data_page.dart';
import '../../referrals/presentation/referral_page.dart';
import '../../voyages/presentation/voyages_page.dart';
import '../application/login_flow.dart';
import '../data/login_client.dart';
import '../data/login_models.dart';
import '../data/login_store.dart';
import '../data/phone_format_client.dart';
import '../data/profile_models.dart';

class SignInPage extends StatefulWidget {
  const SignInPage({super.key, this.flow});
  final LoginFlow? flow;
  @override
  State<SignInPage> createState() => _SignInPageState();
}

class _SignInPageState extends State<SignInPage> {
  final _form = GlobalKey<FormState>();
  final _phone = TextEditingController(), _code = TextEditingController();
  final _profileName = TextEditingController();
  late final LoginFlow _flow =
      widget.flow ??
      LoginFlow(
        LoginClient(LocalApiConfiguration.fromDefines()),
        AndroidLoginStore(),
      );
  Timer? _clock;
  @override
  void initState() {
    super.initState();
    _flow.initialize().then((_) {
      if (mounted) {
        _phone.text = _flow.attempt?.phone ?? '';
        _profileName.text = _flow.profile?.displayName ?? '';
        setState(() {});
      }
    });
    _clock = Timer.periodic(const Duration(seconds: 15), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    _phone.dispose();
    _code.dispose();
    _profileName.dispose();
    if (widget.flow == null) _flow.dispose();
    super.dispose();
  }

  String? get _error => switch (_flow.failure) {
    LoginFailure.invalid => 'Vérifie les informations saisies.',
    LoginFailure.denied => 'Le code ou la session n’est plus accepté. Vérifie le code ou recommence la connexion.',
    LoginFailure.conflict => 'Cette tentative ne correspond pas à la précédente. Vérifie le code saisi.',
    LoginFailure.limited =>
      'Trop de tentatives rapprochées. Patiente avant de réessayer.',
    LoginFailure.unavailable =>
      'Connexion momentanément indisponible. Réessaie plus tard.',
    LoginFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie la même demande pour retrouver son résultat.',
    LoginFailure.invalidResponse =>
      'La réponse n’a pas pu être vérifiée. Réessaie la même demande.',
    LoginFailure.storage =>
      'Le stockage protégé est indisponible. Réessaie avant de poursuivre.',
    null => null,
  };

  String? get _profileError => switch (_flow.profileFailure) {
    ProfileFailure.denied => 'La session est refusée. Les informations locales de connexion ont été effacées.',
    ProfileFailure.versionConflict => 'Le profil a changé depuis sa lecture. Relis-le avant de décider quoi faire.',
    ProfileFailure.keyReused => 'La demande enregistrée ne correspond plus à la réponse du serveur. Relis le profil avant de continuer.',
    ProfileFailure.replayExpired => 'La reprise de cette demande a expiré. Relis le profil et choisis ensuite une nouvelle modification.',
    ProfileFailure.unavailable => 'Le profil est momentanément indisponible. Réessaie plus tard.',
    ProfileFailure.uncertain => 'La réponse est incertaine. Réessaie la même demande pour retrouver son résultat.',
    ProfileFailure.invalidResponse => 'La réponse du profil n’a pas pu être vérifiée. Relis-le avant de continuer.',
    ProfileFailure.storage => 'Le stockage protégé n’a pas accepté la demande. Aucune modification n’a été envoyée.',
    ProfileFailure.invalid => 'Cette action sur le profil ne peut pas être effectuée maintenant.',
    ProfileFailure.rejected => 'Le serveur a refusé ce nom sans l’enregistrer. Le profil a été relu ; choisis un autre nom.',
    null => null,
  };

  Future<void> _restart() async {
    await _flow.restart();
    if (mounted && _flow.attempt == null && _flow.failure == null) {
      _code.clear();
      _phone.clear();
    }
  }

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _flow,
    builder: (context, _) {
      final challenge = _flow.attempt?.challenge;
      final expired =
          challenge != null &&
          !DateTime.now().toUtc().isBefore(challenge.expiresAt);
      return WakaPage(
        title: 'Connexion',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_flow.session != null) ...[
              Text(
                _flow.logoutPending
                    ? 'Déconnexion à terminer'
                    : _flow.cleanupPending
                    ? 'Connexion refusée'
                    : !_flow.sessionSaved
                    ? 'Enregistrement à terminer'
                    : _flow.locallyExpired
                    ? 'Connexion expirée'
                    : _flow.confirmedNow
                    ? 'Connexion confirmée'
                    : 'Connexion enregistrée',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 12),
              Text(
                _flow.logoutPending
                    ? 'La déconnexion du serveur attend une confirmation. Réessaie lorsque le réseau est disponible.'
                    : _flow.cleanupPending
                    ? 'Le serveur refuse cette session. Son effacement du stockage protégé reste à terminer.'
                    : !_flow.sessionSaved
                    ? 'La session n’a pas pu être enregistrée de façon protégée.'
                    : _flow.account != null
                    ? 'Ton compte est actif et ton accès client de base a été vérifié. Les réservations ne sont pas encore ouvertes.'
                    : _flow.confirmedNow && !_flow.locallyExpired
                    ? 'La connexion a été confirmée. Vérifie ton compte pour retrouver son état et ton accès client.'
                    : 'La session est enregistrée, mais ton compte n’est pas encore confirmé. Vérifie-le avant toute utilisation.',
              ),
              const SizedBox(height: 20),
              if (_flow.cleanupPending && !_flow.logoutPending)
                FilledButton(
                  onPressed: _flow.busy ? null : _flow.retrySessionCleanup,
                  child: const Text('Terminer l’effacement local'),
                ),
              if (!_flow.sessionSaved &&
                  !_flow.logoutPending &&
                  !_flow.cleanupPending)
                FilledButton(
                  onPressed: _flow.busy ? null : _flow.retrySessionStorage,
                  child: const Text('Réessayer l’enregistrement'),
                ),
              if (_flow.sessionSaved &&
                  !_flow.logoutPending &&
                  !_flow.cleanupPending)
                FilledButton(
                  onPressed: _flow.busy ? null : _flow.checkAccount,
                  child: Text(
                    _flow.account != null
                        ? 'Actualiser mon compte'
                        : _flow.failure != null
                        ? 'Réessayer la vérification du compte'
                        : 'Vérifier mon compte',
                  ),
                ),
              if (!_flow.cleanupPending || _flow.logoutPending)
                OutlinedButton.icon(
                  onPressed: _flow.busy ? null : _flow.revoke,
                  icon: const Icon(Icons.logout),
                  label: Text(
                    _flow.logoutPending
                        ? 'Réessayer la déconnexion'
                        : 'Se déconnecter',
                  ),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.applicationGateway != null)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => DriverApplicationPage(flow: _flow)),
                        ),
                  icon: const Icon(Icons.badge_outlined),
                  label: const Text('Ma candidature chauffeur'),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.gateway is VoyageGateway)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => VoyagesPage(login: _flow)),
                        ),
                  icon: const Icon(Icons.route_outlined),
                  label: const Text('Voyage entre villes'),
                ),              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.gateway is PaymentGateway)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => WalletPage(login: _flow)),
                        ),
                  icon: const Icon(Icons.account_balance_wallet_outlined),
                  label: const Text('Mon argent'),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.gateway is PrivacyGateway)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => MyDataPage(login: _flow)),
                        ),
                  icon: const Icon(Icons.privacy_tip_outlined),
                  label: const Text('Mes données'),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.gateway is ReferralGateway)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => ReferralPage(login: _flow)),
                        ),
                  icon: const Icon(Icons.card_giftcard_outlined),
                  label: const Text('Parrainage'),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.gateway is DriverAccessGateway)
                TextButton.icon(
                  onPressed: _flow.busy
                      ? null
                      : () => Navigator.of(context).push<void>(
                          MaterialPageRoute<void>(builder: (_) => DriverAccessPage(login: _flow)),
                        ),
                  icon: const Icon(Icons.verified_user_outlined),
                  label: const Text('Mon accès chauffeur'),
                ),
              if (_flow.confirmedNow && !_flow.logoutPending && !_flow.cleanupPending && _flow.profileGateway != null) ...[
                const Divider(height: 32),
                Text('Mon profil', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                if (_flow.profile == null)
                  const Text('Le profil n’est pas chargé. Relis-le avant de le modifier.'),
                if (_flow.profile != null) ...[
                  TextFormField(
                    controller: _profileName,
                    maxLength: 80,
                    maxLines: 1,
                    readOnly: _flow.busy || !_flow.canEditProfile || _flow.pendingProfile != null,
                    decoration: const InputDecoration(labelText: 'Nom d’affichage (facultatif)', border: OutlineInputBorder()),
                  ),
                  Text('Tu peux laisser ce nom vide. Il ne change ni ton numéro ni tes droits.'),
                ],
                if (_flow.profileNeedsReview || _flow.profile == null)
                  OutlinedButton(onPressed: _flow.busy ? null : () async { await _flow.refreshProfile(); if (mounted) _profileName.text = _flow.profile?.displayName ?? ''; }, child: const Text('Relire mon profil')),
                if (_flow.pendingProfile != null) ...[
                  // No abandon here: a read cannot prove that an uncertain change has finished.
                  const Text('Une demande enregistrée attend une reprise. Son résultat doit être connu avant toute nouvelle modification.'),
                  FilledButton(onPressed: _flow.busy ? null : () async { await _flow.resumeProfile(); if (mounted) _profileName.text = _flow.profile?.displayName ?? ''; }, child: const Text('Reprendre la demande')),
                ],
                if (_flow.profile != null && _flow.pendingProfile == null)
                  Wrap(spacing: 8, children: [
                  FilledButton(onPressed: _flow.busy || !_flow.canEditProfile ? null : () async { await _flow.saveDisplayName(_profileName.text); if (mounted) _profileName.text = _flow.profile?.displayName ?? ''; }, child: const Text('Enregistrer le nom')),
                    TextButton(onPressed: _flow.busy || !_flow.canEditProfile || _flow.profile!.displayName == null ? null : () async { await _flow.saveDisplayName(null); if (mounted) _profileName.clear(); }, child: const Text('Effacer le nom')),
                  ]),
              ],
            ] else if (challenge == null) ...[
              Text(
                'Connecte-toi à WAKA',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              const Text(
                'Saisis ton numéro mobile camerounais. Un code temporaire te permettra de vérifier ton accès.',
              ),
              const SizedBox(height: 20),
              Form(
                key: _form,
                child: TextFormField(
                  controller: _phone,
                  readOnly: _flow.busy || _flow.attempt != null,
                  keyboardType: TextInputType.phone,
                  maxLength: 40,
                  autofillHints: const [AutofillHints.telephoneNumber],
                  decoration: const InputDecoration(
                    labelText: 'Numéro mobile Cameroun',
                    hintText: '+237…',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) => value == null || value.isEmpty
                      ? 'Saisis un numéro mobile.'
                      : !isValidCameroonMobilePhoneFormat(value)
                      ? 'Vérifie le format du numéro mobile camerounais.'
                      : null,
                ),
              ),
              const SizedBox(height: 12),
              FilledButton.icon(
                onPressed: _flow.busy
                    ? null
                    : () {
                        if (_form.currentState!.validate()) {
                          FocusScope.of(context).unfocus();
                          _flow.request(_phone.text);
                        }
                      },
                icon: const Icon(Icons.sms_outlined),
                label: Text(
                  _flow.attempt == null
                      ? 'Recevoir un code'
                      : 'Réessayer la demande',
                ),
              ),
              if (_flow.attempt != null)
                TextButton(
                  onPressed: _flow.busy ? null : _restart,
                  child: const Text('Changer de numéro'),
                ),
              if (!_flow.ready && !_flow.busy)
                OutlinedButton(
                  onPressed: _flow.initialize,
                  child: const Text('Réessayer le stockage protégé'),
                ),
            ] else ...[
              Text(
                'Saisis le code',
                style: Theme.of(context).textTheme.titleLarge,
              ),
              const SizedBox(height: 8),
              Text(
                'Numéro se terminant par ${challenge.destinationHint.substring(7)}',
              ),
              const SizedBox(height: 8),
              Text(
                expired
                    ? 'Le code a expiré. Recommence la connexion pour demander un nouveau code.'
                    : switch (challenge.submission) {
                        'UNKNOWN' => 'L’envoi n’est pas confirmé. Si tu as reçu un code, saisis-le.',
                        'SENDING' => 'La demande d’envoi est encore en cours.',
                        'REJECTED' => 'La demande n’a pas abouti. Tu peux recommencer plus tard.',
                        _ => 'Saisis le code reçu sur ton téléphone.',
                      },
              ),
              const SizedBox(height: 16),
              Form(
                key: _form,
                child: TextFormField(
                  controller: _code,
                  obscureText: true,
                  enableSuggestions: false,
                  autocorrect: false,
                  readOnly: _flow.busy || expired,
                  maxLength: 6,
                  keyboardType: TextInputType.number,
                  autofillHints: const [AutofillHints.oneTimeCode],
                  inputFormatters: [
                    FilteringTextInputFormatter.allow(RegExp('[0-9]')),
                  ],
                  decoration: const InputDecoration(
                    labelText: 'Code à 6 chiffres',
                    border: OutlineInputBorder(),
                  ),
                  validator: (value) => value == null || value.length != 6
                      ? 'Saisis les 6 chiffres du code.'
                      : null,
                ),
              ),
              const SizedBox(height: 12),
              FilledButton(
                onPressed:
                    _flow.busy ||
                        expired ||
                        !const {
                          'ACCEPTED',
                          'UNKNOWN',
                        }.contains(challenge.submission)
                    ? null
                    : () async {
                        if (!_form.currentState!.validate()) return;
                        FocusScope.of(context).unfocus();
                        await _flow.verify(_code.text);
                        if (mounted && _flow.session != null) _code.clear();
                      },
                child: const Text('Vérifier le code'),
              ),
              if (challenge.submission == 'SENDING')
                OutlinedButton(
                  onPressed: _flow.busy
                      ? null
                      : () => _flow.request(_flow.attempt!.phone),
                  child: const Text('Vérifier la demande'),
                ),
              TextButton(
                onPressed: _flow.busy ? null : _restart,
                child: const Text('Recommencer la connexion'),
              ),
            ],
            if (_flow.busy)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 16),
                child: LinearProgressIndicator(
                  semanticsLabel: 'Demande en cours',
                ),
              ),
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Semantics(liveRegion: true, child: Text(_error!)),
              ),
            if (_flow.notice != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Semantics(liveRegion: true, child: Text(_flow.notice!)),
              ),
            if (_profileError != null)
              Padding(padding: const EdgeInsets.only(top: 16), child: Semantics(liveRegion: true, child: Text(_profileError!))),
          ],
        ),
      );
    },
  );
}
