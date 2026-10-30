import 'dart:async';

import 'package:flutter/material.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/payment_models.dart';
import '../../identity/data/vehicle_photo_models.dart';

const operatorLabels = {'cm.mtn': 'MTN Mobile Money', 'cm.orange': 'Orange Money'};

String paymentMessage(PhotoFailure failure) => switch (failure) {
  PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
  PhotoFailure.notPayable => 'Il n’y a plus rien à payer ici : le délai est passé ou c’est déjà payé.',
  PhotoFailure.insufficientCredit => 'Le solde de cette poche ne suffit pas.',
  PhotoFailure.creditLimit => 'Ton crédit WAKA ne peut pas dépasser 300 000 F, recharges en attente comprises.',
  PhotoFailure.phoneNotAccount => 'Un retrait part seulement vers le numéro de ton compte WAKA.',
  PhotoFailure.driverNotAllowed => 'Seul un chauffeur particulier approuvé peut prendre une période d’abonnement.',
  PhotoFailure.invalid => 'Vérifie le montant et le numéro (9 chiffres commençant par 6).',
  PhotoFailure.unavailable => 'Le paiement est momentanément indisponible. Réessaie plus tard.',
  PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie : la même demande sera renvoyée, sans double paiement.',
  _ => 'La réponse n’a pas pu être vérifiée. Réessaie.',
};

/// A Cameroon mobile number as the server expects it, or null.
String? cameroonPhone(String input) {
  final digits = input.replaceAll(RegExp(r'[\s.-]'), '').replaceFirst(RegExp(r'^(\+237|00237)'), '');
  return RegExp(r'^6[0-9]{8}$').hasMatch(digits) ? '+237$digits' : null;
}

/// Asks how to pay {@code amountXaf}, pays, and follows the payment until the provider answers. Returns the
/// last known payment, or null if nothing was sent. The amount shown is the server's; the phone never sets it
/// except for a top-up.
class PayDialog extends StatefulWidget {
  const PayDialog({super.key, required this.login, required this.purpose, required this.amountXaf, this.targetId,
      this.title = 'Payer', this.pollEvery = const Duration(seconds: 4)});
  final LoginFlow login;
  final String purpose;
  final String? targetId;
  final int amountXaf;
  final String title;
  /// Null in tests: the payer then checks by hand.
  final Duration? pollEvery;
  @override
  State<PayDialog> createState() => _PayDialogState();
}

class _PayDialogState extends State<PayDialog> {
  PaymentGateway get _gateway => widget.login.gateway as PaymentGateway;
  final _phone = TextEditingController();
  /// One key for this dialog: a retry after a lost answer finds the same payment.
  final _key = randomLoginId();
  String _method = 'cm.mtn';
  WalletView? _wallet;
  PaymentView? _payment;
  String? _message;
  bool _busy = false;
  Timer? _poll;
  int _polls = 0;

  bool get _topup => widget.purpose == 'topup';

  @override
  void initState() {
    super.initState();
    if (!_topup) {
      _run(() async {
        final (session, client) = _context();
        final wallet = await _gateway.wallet(session, client);
        if (mounted) setState(() => _wallet = wallet);
      });
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    _phone.dispose();
    super.dispose();
  }

  (LoginSession, String) _context() {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (!widget.login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = paymentMessage(problem.failure));
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _pay() => _run(() async {
    String? phone;
    if (operatorLabels.containsKey(_method)) {
      phone = cameroonPhone(_phone.text);
      if (phone == null) throw const PhotoProblem(PhotoFailure.invalid);
    }
    final (session, client) = _context();
    final payment = await _gateway.pay(session, client, _key, widget.purpose, widget.targetId, _method, phone,
        _topup ? widget.amountXaf : null);
    _show(payment);
  });

  Future<void> _check() => _run(() async {
    final (session, client) = _context();
    _show(await _gateway.payment(session, client, _payment!.paymentId));
  });

  void _show(PaymentView payment) {
    if (!mounted) return;
    setState(() => _payment = payment);
    _poll?.cancel();
    final every = widget.pollEvery;
    // About two minutes of automatic checks; after that the payer checks by hand.
    if (payment.pending && every != null && _polls < 30) {
      _poll = Timer(every, () { _polls++; _check(); });
    }
  }

  String _status(PaymentView p) => switch (p.state) {
    'pending' => 'Confirme le paiement de ${p.chargedXaf} F sur ton téléphone avec ton code secret Mobile Money. '
        'WAKA vérifie automatiquement…',
    'succeeded' when p.applied || _topup => _topup ? 'Recharge reçue : ${p.chargedXaf} F ajoutés à ton crédit WAKA.' : 'Paiement confirmé.',
    'succeeded' => 'Paiement reçu après le délai : la réservation n’a pas pu être gardée. L’argent est dans ton crédit WAKA.',
    'failed' => 'Paiement refusé ou annulé. Aucun argent n’a été pris.',
    _ => 'Paiement en vérification par WAKA : le montant reçu ne correspond pas. L’assistance te recontactera.',
  };

  @override
  Widget build(BuildContext context) {
    final payment = _payment, wallet = _wallet;
    return AlertDialog(
      title: Text(widget.title),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        Text('Montant : ${widget.amountXaf} F', style: Theme.of(context).textTheme.titleMedium),
        if (_topup) const Text('Aucun frais à la recharge.'),
        const SizedBox(height: 8),
        if (payment == null) ...[
          RadioGroup<String>(
            groupValue: _method,
            onChanged: (value) => setState(() => _method = value!),
            child: Column(children: [
              for (final entry in operatorLabels.entries)
                RadioListTile<String>(value: entry.key, title: Text(entry.value)),
              if (!_topup) ...[
                RadioListTile<String>(value: 'credit', title: Text('Mon crédit WAKA${wallet == null ? '' : ' (${wallet.creditXaf} F)'}')),
                RadioListTile<String>(value: 'earnings', title: Text('Mes gains${wallet == null ? '' : ' (${wallet.earningsXaf} F)'}')),
              ],
            ]),
          ),
          if (operatorLabels.containsKey(_method))
            TextField(controller: _phone, keyboardType: TextInputType.phone, maxLength: 16,
                decoration: const InputDecoration(labelText: 'Numéro qui paie (6XXXXXXXX)', border: OutlineInputBorder())),
        ],
        if (payment != null) Semantics(liveRegion: true, child: Text(_status(payment))),
        if (_message != null) Padding(padding: const EdgeInsets.only(top: 8), child: Text(_message!)),
      ])),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(payment), child: Text(payment == null ? 'Annuler' : 'Fermer')),
        if (payment == null) FilledButton(onPressed: _busy ? null : _pay, child: Text('Payer ${widget.amountXaf} F')),
        if (payment != null && payment.pending) FilledButton(onPressed: _busy ? null : _check, child: const Text('Vérifier')),
      ],
    );
  }
}
