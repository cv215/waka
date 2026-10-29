import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/referral_models.dart';
import '../../identity/data/vehicle_photo_models.dart';

/// « Parrainage » : one's own code to share, and the single referrer one may attach (R05).
class ReferralPage extends StatefulWidget {
  const ReferralPage({super.key, required this.login});
  final LoginFlow login;
  @override
  State<ReferralPage> createState() => _ReferralPageState();
}

class _ReferralPageState extends State<ReferralPage> {
  ReferralGateway get _gateway => widget.login.gateway as ReferralGateway;
  final _code = TextEditingController();
  ReferralView? _view;
  PhotoFailure? _failure;
  String? _message;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _run(_load);
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final (session, client) = _context();
    final view = await _gateway.referral(session, client);
    if (mounted) setState(() => _view = view);
  }

  Future<void> _attach() => _run(() async {
    final code = _code.text.replaceAll(RegExp(r'[\s-]'), '').toUpperCase();
    if (!referralCodePattern.hasMatch(code)) throw const PhotoProblem(PhotoFailure.invalid);
    final (session, client) = _context();
    final linkage = await _gateway.attachReferrer(session, client, code);
    _code.clear();
    _message = linkage.created ? 'Parrain enregistré.' : 'Ce parrain était déjà enregistré.';
    await _load();
  });

  (LoginSession, String) _context() {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (!widget.login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() { _busy = true; _failure = null; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _failure = problem.failure);
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  String? get _error => switch (_failure) {
    null => null,
    PhotoFailure.selfReferral => 'C’est ton propre code : tu ne peux pas être ton propre parrain.',
    PhotoFailure.codeNotRecognized => 'Ce code n’est pas reconnu. Vérifie-le auprès de la personne qui te l’a donné.',
    PhotoFailure.referrerAlreadySet => 'Un parrain est déjà enregistré. Pour une erreur, contacte l’assistance WAKA.',
    PhotoFailure.mutualReferral => 'Cette personne est déjà ton filleul : vous ne pouvez pas vous parrainer l’un l’autre.',
    PhotoFailure.referralWindowClosed => 'Le parrain ne peut plus être ajouté : il se choisit avant ta première course.',
    PhotoFailure.tooManyAttempts => 'Trop de codes non reconnus. Réessaie dans 24 heures.',
    PhotoFailure.invalid => 'Le code compte 8 caractères (lettres et chiffres, sans 0, O, 1 ni I).',
    PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
    PhotoFailure.unavailable => 'Le parrainage est momentanément indisponible. Réessaie plus tard.',
    PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie : le même code ne crée pas de doublon.',
    _ => 'La réponse n’a pas pu être vérifiée. Réessaie.',
  };

  @override
  Widget build(BuildContext context) {
    final view = _view;
    return WakaPage(
      title: 'Parrainage',
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Mon code', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        if (view == null) const Text('Code non chargé.'),
        if (view != null)
          Row(children: [
            Expanded(child: SelectableText(view.code, style: Theme.of(context).textTheme.headlineSmall)),
            IconButton(tooltip: 'Copier mon code', icon: const Icon(Icons.copy),
                onPressed: () => Clipboard.setData(ClipboardData(text: view.code))),
          ]),
        const Text('Partage ce code. Un bonus n’est dû qu’après une réservation payée et terminée par la personne parrainée, '
            'selon les règles de WAKA ; l’inscription seule ne donne rien.'),
        const Divider(height: 32),
        Text('Mon parrain', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(height: 8),
        if (view != null && view.attached)
          const Text('Un parrain est enregistré pour ton compte. Il ne peut pas être changé librement.'),
        if (view != null && !view.attached) ...[
          const Text('Facultatif : si quelqu’un t’a recommandé WAKA, saisis son code. Ce choix est définitif.'),
          const SizedBox(height: 8),
          TextField(controller: _code, textCapitalization: TextCapitalization.characters, maxLength: 12,
              decoration: const InputDecoration(labelText: 'Code de mon parrain', border: OutlineInputBorder())),
          FilledButton(onPressed: _busy ? null : _attach, child: const Text('Enregistrer ce parrain')),
        ],
        if (_message != null) Padding(padding: const EdgeInsets.only(top: 12),
            child: Semantics(liveRegion: true, child: Text(_message!))),
        if (_error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_error!)),
      ]),
    );
  }
}
