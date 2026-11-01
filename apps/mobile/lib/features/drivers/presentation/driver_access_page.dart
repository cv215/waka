import 'package:flutter/material.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_access_models.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/payment_models.dart';
import '../../payments/presentation/pay_dialog.dart';
import '../../identity/data/vehicle_photo_models.dart';

const _reasonTexts = {
  'NO_APPLICATION': 'Aucun dossier chauffeur pour ce compte.',
  'NOT_APPROVED': 'Ton dossier n’est pas encore approuvé.',
  'DOCUMENT_EXPIRED': 'Une pièce essentielle a expiré : renouvelle-la pour recevoir de nouvelles réservations.',
  'DOCUMENT_NOT_VALID': 'Une pièce essentielle n’est plus valable : remplace-la.',
  'REQUIREMENTS_NOT_QUALIFIED': 'Une exigence de ton dossier est en cours de confirmation par WAKA.',
  'SUBSCRIPTION_REQUIRED': 'Ton essai est terminé : un abonnement de 3 000 F pour 30 jours est nécessaire pour de nouvelles courses en ville.',
};

/// « Mon accès chauffeur » : what the server allows now, and why. Nothing is decided on the phone.
class DriverAccessPage extends StatefulWidget {
  const DriverAccessPage({super.key, required this.login, this.pollEvery = const Duration(seconds: 4)});
  final LoginFlow login;
  final Duration? pollEvery;
  @override
  State<DriverAccessPage> createState() => _DriverAccessPageState();
}

class _DriverAccessPageState extends State<DriverAccessPage> {
  DriverAccessView? _view;
  PhotoFailure? _failure;
  String? _message;
  bool _busy = false;
  /// One purchase key until the server answers: a lost answer never creates a second period.
  String? _purchaseKey;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (_busy || !widget.login.confirmedNow || session == null || client == null) return;
    setState(() { _busy = true; _failure = null; });
    try {
      final view = await (widget.login.gateway as DriverAccessGateway).driverAccess(session, client);
      if (mounted) setState(() => _view = view);
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _failure = problem.failure);
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// K32: a private driver whose trial has started buys 30 days at 3 000 F, then pays it (R14).
  Future<void> _subscribe() async {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (_busy || !widget.login.confirmedNow || session == null || client == null) return;
    SubscriptionPeriodView? period;
    setState(() { _busy = true; _message = null; });
    try {
      period = await (widget.login.gateway as PaymentGateway).buySubscription(session, client, _purchaseKey ??= randomLoginId());
      _purchaseKey = null;
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = paymentMessage(problem.failure));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    if (period == null || !mounted) return;
    final payment = await showDialog<PaymentView>(context: context, barrierDismissible: false, builder: (_) => PayDialog(
        login: widget.login, purpose: 'subscription', targetId: period!.periodId, amountXaf: period.priceXaf,
        title: 'Abonnement 30 jours', pollEvery: widget.pollEvery));
    if (payment != null && payment.state == 'succeeded' && payment.applied && mounted) {
      setState(() => _message = 'Abonnement payé : 30 jours, qui commencent après ton essai ou ta période en cours.');
    }
    await _load();
  }

  (String, String) _summary(DriverAccessView view) => switch (view.access) {
    'active' => ('Accès actif', switch (view.basis) {
      'trial' => 'Tu es dans ton essai de 7 jours.',
      'paid_period' => 'Ton abonnement est en cours.',
      _ => 'En taxi, aucun abonnement n’est demandé.',
    }),
    'subscription_required' => ('Abonnement nécessaire', 'Les voyages entre villes restent possibles sans abonnement.'),
    'provisional' => ('Accès provisoire',
        'Tu peux recevoir ta première réservation. L’essai de 7 périodes de 24 h sans abonnement commencera seulement après ta première '
            'course payée et terminée.'),
    'new_reservations_blocked' => ('Nouvelles réservations bloquées',
        'Les courses déjà acceptées restent suivies par l’assistance WAKA.'),
    _ => ('Pas encore d’accès chauffeur', 'L’accès s’ouvre après l’approbation de ton dossier.'),
  };

  Widget _step(String label, {required bool done, bool now = false}) {
    final scheme = Theme.of(context).colorScheme;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      dense: true,
      leading: Icon(done ? Icons.check_circle : now ? Icons.timelapse : Icons.radio_button_unchecked,
          color: done || now ? scheme.primary : scheme.outline),
      title: Text(label),
      subtitle: now ? const Text('En cours') : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    final view = _view;
    final theme = Theme.of(context), scheme = theme.colorScheme;
    return WakaPage(
      title: 'Mon accès chauffeur',
      actions: [IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : _load, icon: const Icon(Icons.refresh))],
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (view == null && _failure == null) const WakaLoading(),
        if (_failure != null)
          Text(switch (_failure) {
            PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
            PhotoFailure.unavailable => 'Ce service est momentanément indisponible. Réessaie plus tard.',
            PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Actualise.',
            _ => 'La réponse n’a pas pu être vérifiée. Actualise.',
          }),
        if (view != null) ...[
          // Step 7 of the redesign: the state in one block, then where a private driver stands.
          Container(
            padding: const EdgeInsets.all(18),
            decoration: BoxDecoration(color: scheme.primary, borderRadius: BorderRadius.circular(20)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text('État', style: theme.textTheme.bodySmall?.copyWith(color: scheme.onPrimary.withValues(alpha: 0.75))),
              Semantics(header: true, child: Text(_summary(view).$1,
                  style: theme.textTheme.headlineSmall?.copyWith(color: scheme.onPrimary, fontWeight: FontWeight.w700))),
              const SizedBox(height: 6),
              Text(_summary(view).$2, style: theme.textTheme.bodyMedium?.copyWith(color: scheme.onPrimary.withValues(alpha: 0.85))),
            ]),
          ),
          if (view.category == 'private' && view.access != 'none' && !view.reasons.contains('NOT_APPROVED')) ...[
            const SizedBox(height: 16),
            Text('Étapes', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
            _step('Dossier approuvé', done: true),
            _step('Première course payée et terminée', done: view.trial == 'running' || view.trial == 'ended'),
            _step('Essai de 7 périodes de 24 h', done: view.trial == 'ended', now: view.trial == 'running'),
            _step('Abonnement de 30 jours (3 000 F)', done: view.basis == 'paid_period'),
          ],
          for (final reason in view.reasons)
            Padding(padding: const EdgeInsets.only(top: 8), child: Text(_reasonTexts[reason] ?? reason)),
          if (view.documentsToRenew.isNotEmpty)
            Padding(padding: const EdgeInsets.only(top: 8),
                child: Text('À renouveler dans ma candidature : ${view.documentsToRenew.length} pièce(s).')),
          if (view.category == 'private' && view.trial != null && view.trial != 'not_started' && widget.login.gateway is PaymentGateway)
            Padding(padding: const EdgeInsets.only(top: 16), child: FilledButton(onPressed: _busy ? null : _subscribe,
                child: const Text('Prendre 30 jours d’abonnement (3 000 F)'))),
        ],
        if (_message != null) Padding(padding: const EdgeInsets.only(top: 12),
            child: Semantics(liveRegion: true, child: Text(_message!))),
      ]),
    );
  }
}
