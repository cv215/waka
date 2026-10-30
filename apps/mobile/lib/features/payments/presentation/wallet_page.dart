import 'package:flutter/material.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/payment_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import 'pay_dialog.dart';

const _kindLabels = {
  'deposit': 'Recharge',
  'service_payment': 'Paiement',
  'settlement': 'Remboursement ou gain',
  'withdrawal': 'Retrait',
  'withdrawal_reversed': 'Retrait annulé, rendu',
  'other': 'Mouvement',
};

/// « Mon argent » (R14): the WAKA credit and the earnings, recent movements, top-up and withdrawal.
class WalletPage extends StatefulWidget {
  const WalletPage({super.key, required this.login, this.pollEvery = const Duration(seconds: 4)});
  final LoginFlow login;
  final Duration? pollEvery;
  @override
  State<WalletPage> createState() => _WalletPageState();
}

class _WalletPageState extends State<WalletPage> {
  PaymentGateway get _gateway => widget.login.gateway as PaymentGateway;
  WalletView? _wallet;
  WithdrawalView? _withdrawal;
  String? _message;
  /// The same withdrawal after a lost answer keeps its key: the money is never sent twice.
  final Map<String, String> _keys = {};
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _run(_load);
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

  Future<void> _load() async {
    final (session, client) = _context();
    final wallet = await _gateway.wallet(session, client);
    if (mounted) setState(() => _wallet = wallet);
  }

  Future<void> _topup() async {
    final amount = await showDialog<int>(context: context, builder: (_) => const _AmountDialog());
    if (amount == null || !mounted) return;
    await showDialog<PaymentView>(context: context, barrierDismissible: false, builder: (_) => PayDialog(
        login: widget.login, purpose: 'topup', amountXaf: amount, title: 'Recharger mon crédit', pollEvery: widget.pollEvery));
    await _run(_load);
  }

  Future<void> _withdraw() async {
    final wallet = _wallet;
    if (wallet == null) return;
    final request = await showDialog<_WithdrawRequest>(context: context, builder: (_) => _WithdrawDialog(wallet: wallet));
    if (request == null) return;
    await _run(() async {
      final (session, client) = _context();
      final signature = '${request.pocket}:${request.amount}:${request.method}:${request.phone}';
      final withdrawal = await _gateway.withdraw(session, client, _keys.putIfAbsent(signature, randomLoginId), request.pocket,
          request.amount, request.method, request.phone);
      _keys.remove(signature);
      _withdrawal = withdrawal;
      _message = _withdrawalText(withdrawal);
      await _load();
    });
  }

  Future<void> _checkWithdrawal() => _run(() async {
    final (session, client) = _context();
    final withdrawal = await _gateway.withdrawal(session, client, _withdrawal!.withdrawalId);
    _withdrawal = withdrawal;
    _message = _withdrawalText(withdrawal);
    await _load();
  });

  String _withdrawalText(WithdrawalView w) => switch (w.state) {
    'pending' => 'Retrait de ${w.amountXaf} F en cours (frais ${w.feeXaf} F). Il arrive en général en quelques minutes.',
    'succeeded' => 'Retrait de ${w.amountXaf} F envoyé.',
    _ => 'Le retrait a échoué : ${w.amountXaf + w.feeXaf} F ont été rendus, frais compris.',
  };

  @override
  Widget build(BuildContext context) {
    final wallet = _wallet;
    final theme = Theme.of(context);
    return WakaPage(
      title: 'Mon argent',
      actions: [IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : () => _run(_load), icon: const Icon(Icons.refresh))],
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (wallet == null && _message == null) const Text('Chargement…'),
        if (wallet != null) ...[
          Card(child: ListTile(title: const Text('Mon crédit WAKA'), trailing: Text('${wallet.creditXaf} F', style: theme.textTheme.titleLarge),
              subtitle: const Text('Recharges et remboursements. Sert à payer tes courses et voyages. Retrait : 3 % de frais.'))),
          Card(child: ListTile(title: const Text('Mes gains'), trailing: Text('${wallet.earningsXaf} F', style: theme.textTheme.titleLarge),
              subtitle: const Text('Courses de chauffeur et parrainage. Retrait : 2 % de frais.'))),
          const SizedBox(height: 8),
          Wrap(spacing: 8, runSpacing: 8, children: [
            FilledButton.icon(onPressed: _busy ? null : _topup, icon: const Icon(Icons.add), label: const Text('Recharger')),
            OutlinedButton.icon(onPressed: _busy || wallet.creditXaf + wallet.earningsXaf == 0 ? null : _withdraw,
                icon: const Icon(Icons.call_made), label: const Text('Retirer')),
            if (_withdrawal?.state == 'pending')
              TextButton(onPressed: _busy ? null : _checkWithdrawal, child: const Text('Vérifier le retrait')),
          ]),
        ],
        if (_message != null) Padding(padding: const EdgeInsets.only(top: 12),
            child: Semantics(liveRegion: true, child: Text(_message!))),
        if (wallet != null) ...[
          const Divider(height: 32),
          Text('Derniers mouvements', style: theme.textTheme.titleLarge),
          if (wallet.lines.isEmpty) const Padding(padding: EdgeInsets.only(top: 8), child: Text('Aucun mouvement pour l’instant.')),
          for (final line in wallet.lines)
            ListTile(
              dense: true,
              title: Text('${_kindLabels[line.kind]} · ${line.pocket == 'earnings' ? 'gains' : 'crédit'}'),
              subtitle: Text(line.at.toLocal().toString().substring(0, 16)),
              trailing: Text('${line.amountXaf > 0 ? '+' : ''}${line.amountXaf} F'),
            ),
        ],
      ]),
    );
  }
}

class _AmountDialog extends StatefulWidget {
  const _AmountDialog();
  @override
  State<_AmountDialog> createState() => _AmountDialogState();
}

class _AmountDialogState extends State<_AmountDialog> {
  final _amount = TextEditingController();
  String? _error;
  @override
  void dispose() { _amount.dispose(); super.dispose(); }
  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Recharger mon crédit'),
    content: TextField(controller: _amount, keyboardType: TextInputType.number, decoration: InputDecoration(
        labelText: 'Montant en F ($topupMin à $topupMax)', errorText: _error, border: const OutlineInputBorder())),
    actions: [
      TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuler')),
      FilledButton(onPressed: () {
        final value = int.tryParse(_amount.text.trim());
        if (value == null || value < topupMin || value > topupMax) {
          setState(() => _error = 'Entre $topupMin et $topupMax F.');
        } else {
          Navigator.of(context).pop(value);
        }
      }, child: const Text('Continuer')),
    ],
  );
}

class _WithdrawRequest {
  const _WithdrawRequest(this.pocket, this.amount, this.method, this.phone);
  final String pocket, method, phone;
  final int amount;
}

class _WithdrawDialog extends StatefulWidget {
  const _WithdrawDialog({required this.wallet});
  final WalletView wallet;
  @override
  State<_WithdrawDialog> createState() => _WithdrawDialogState();
}

class _WithdrawDialogState extends State<_WithdrawDialog> {
  final _amount = TextEditingController(), _phone = TextEditingController();
  late String _pocket = widget.wallet.earningsXaf > 0 ? 'earnings' : 'credit';
  String _method = 'cm.mtn';
  String? _error;

  @override
  void initState() {
    super.initState();
    _amount.addListener(() => setState(() {}));
  }
  @override
  void dispose() { _amount.dispose(); _phone.dispose(); super.dispose(); }

  int get _available => _pocket == 'earnings' ? widget.wallet.earningsXaf : widget.wallet.creditXaf;

  @override
  Widget build(BuildContext context) {
    final amount = int.tryParse(_amount.text.trim());
    final fee = amount == null ? null : withdrawalFee(_pocket, amount);
    return AlertDialog(
      title: const Text('Retirer vers mon numéro'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        RadioGroup<String>(groupValue: _pocket, onChanged: (v) => setState(() => _pocket = v!), child: Column(children: [
          RadioListTile<String>(value: 'earnings', title: Text('Mes gains (${widget.wallet.earningsXaf} F) · 2 %')),
          RadioListTile<String>(value: 'credit', title: Text('Mon crédit (${widget.wallet.creditXaf} F) · 3 %')),
        ])),
        TextField(controller: _amount, keyboardType: TextInputType.number,
            decoration: InputDecoration(labelText: 'Montant à recevoir ($withdrawMin à $withdrawMax F)', border: const OutlineInputBorder())),
        if (amount != null && fee != null)
          Padding(padding: const EdgeInsets.only(top: 8), child: Text('Frais : $fee F · retiré de la poche : ${amount + fee} F')),
        const SizedBox(height: 8),
        RadioGroup<String>(groupValue: _method, onChanged: (v) => setState(() => _method = v!), child: Column(children: [
          for (final entry in operatorLabels.entries) RadioListTile<String>(value: entry.key, title: Text(entry.value)),
        ])),
        TextField(controller: _phone, keyboardType: TextInputType.phone, maxLength: 16, decoration: const InputDecoration(
            labelText: 'Numéro de mon compte WAKA', helperText: 'Seulement le numéro avec lequel tu te connectes.',
            border: OutlineInputBorder())),
        if (_error != null) Text(_error!),
      ])),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuler')),
        FilledButton(onPressed: () {
          final phone = cameroonPhone(_phone.text);
          if (amount == null || fee == null || amount < withdrawMin || amount > withdrawMax) {
            setState(() => _error = 'Entre $withdrawMin et $withdrawMax F.');
          } else if (amount + fee > _available) {
            setState(() => _error = 'Cette poche ne contient pas ${amount + fee} F.');
          } else if (phone == null) {
            setState(() => _error = 'Numéro attendu : 9 chiffres commençant par 6.');
          } else {
            Navigator.of(context).pop(_WithdrawRequest(_pocket, amount, _method, phone));
          }
        }, child: const Text('Retirer')),
      ],
    );
  }
}
