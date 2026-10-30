import 'package:flutter/material.dart';

import 'agent_session.dart';
import 'governance_pages.dart' show shortDate;
import 'models.dart';

const _methods = {'cm.mtn': 'MTN', 'cm.orange': 'Orange', 'credit': 'crédit', 'earnings': 'gains'};

/// FINANCE (R14): the totals of the ledger against the provider, and the cases to settle after checking
/// the provider's dashboard. Every decision has a reason and is written to the audit journal.
class FinancePage extends StatefulWidget {
  const FinancePage({super.key, required this.session});
  final AgentSession session;
  @override
  State<FinancePage> createState() => _FinancePageState();
}

class _FinancePageState extends State<FinancePage> {
  FinanceOverview? _overview;
  List<FinanceCase>? _cases;
  String? _message;
  bool _busy = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _run(_load);
  }

  Future<void> _load() async {
    final overview = await s.api.financeOverview(s.client!, s.clientRef, s.admin!);
    final cases = await s.api.financeCases(s.client!, s.clientRef, s.admin!);
    if (mounted) setState(() { _overview = overview; _cases = cases; });
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _message = messageFor(problem));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _resolve(FinanceCase item) async {
    final result = await showDialog<(String, int, String)>(context: context, builder: (_) => _ResolveDialog(item: item));
    if (result == null) return;
    await _run(() async {
      await s.api.resolveCase(s.client!, s.clientRef, s.admin!, item, result.$1, result.$2, result.$3);
      _message = 'Décision enregistrée et inscrite au journal.';
      await _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final o = _overview;
    final theme = Theme.of(context);
    Widget row(String label, int amount) => ListTile(dense: true, title: Text(label), trailing: Text('$amount F'));
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text('Argent', style: theme.textTheme.headlineSmall),
      const Text('Ce que le registre WAKA attend chez le prestataire doit égaler la somme des soldes réels de ses services '
          '(encaissement et retraits) dans son tableau de bord.'),
      if (o != null) ...[
        Card(child: Column(children: [
          row('Crédits des clients', o.creditXaf),
          row('Gains des chauffeurs et parrains', o.earningsXaf),
          row('Argent gardé pour des prestations en cours', o.heldXaf),
          row('Retraits en route', o.payoutsInFlightXaf),
          row('Recettes WAKA', o.revenueXaf),
          const Divider(),
          ListTile(title: const Text('Attendu chez le prestataire'),
              trailing: Text('${o.expectedAtProviderXaf} F', style: theme.textTheme.titleLarge)),
        ])),
      ],
      if (_message != null) Padding(padding: const EdgeInsets.symmetric(vertical: 8),
          child: Semantics(liveRegion: true, child: Text(_message!))),
      const Divider(height: 32),
      Text('À régler', style: theme.textTheme.headlineSmall),
      const Text('Un montant qui ne correspond pas, ou une opération sans statut final après une heure. Vérifie d’abord '
          'dans le tableau de bord du prestataire, puis décide.'),
      if (_cases != null && _cases!.isEmpty) const Padding(padding: EdgeInsets.only(top: 8), child: Text('Rien à régler.')),
      for (final c in _cases ?? const <FinanceCase>[])
        Card(child: ListTile(
          title: Text('${c.kind == 'payment' ? 'Paiement' : 'Retrait'} · ${c.amountXaf} F · ${_methods[c.method]} · ${c.state}'),
          subtitle: Text('${shortDate(c.createdAt)} · ${c.id}'),
          trailing: TextButton(onPressed: _busy ? null : () => _resolve(c), child: const Text('Régler')),
        )),
    ]);
  }
}

class _ResolveDialog extends StatefulWidget {
  const _ResolveDialog({required this.item});
  final FinanceCase item;
  @override
  State<_ResolveDialog> createState() => _ResolveDialogState();
}

class _ResolveDialogState extends State<_ResolveDialog> {
  late final bool _payment = widget.item.kind == 'payment';
  late String _outcome = _payment ? 'received' : 'sent';
  late final _amount = TextEditingController(text: '${widget.item.amountXaf}');
  final _reason = TextEditingController(text: 'PROVIDER_DASHBOARD_CHECKED');
  String? _error;

  @override
  void dispose() { _amount.dispose(); _reason.dispose(); super.dispose(); }

  @override
  Widget build(BuildContext context) {
    final options = _payment
        ? {'received': 'Reçu chez le prestataire', 'not_received': 'Pas reçu'}
        : {'sent': 'Envoyé par le prestataire', 'not_sent': 'Pas envoyé : tout rendre'};
    return AlertDialog(
      title: Text(_payment ? 'Régler un paiement' : 'Régler un retrait'),
      content: SingleChildScrollView(child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        RadioGroup<String>(groupValue: _outcome, onChanged: (v) => setState(() => _outcome = v!), child: Column(children: [
          for (final e in options.entries) RadioListTile<String>(value: e.key, title: Text(e.value)),
        ])),
        if (_outcome == 'received') ...[
          TextField(controller: _amount, keyboardType: TextInputType.number, decoration: const InputDecoration(
              labelText: 'Montant réellement reçu (F)', border: OutlineInputBorder())),
          const Text('Il va dans le crédit WAKA du payeur, jamais directement à la prestation.'),
        ],
        const SizedBox(height: 8),
        TextField(controller: _reason, decoration: const InputDecoration(labelText: 'Motif (code en majuscules)', border: OutlineInputBorder())),
        if (_error != null) Text(_error!),
      ])),
      actions: [
        TextButton(onPressed: () => Navigator.of(context).pop(), child: const Text('Annuler')),
        FilledButton(onPressed: () {
          final amount = _outcome == 'received' ? int.tryParse(_amount.text.trim()) : 0;
          if (amount == null || (_outcome == 'received' && (amount < 1 || amount > widget.item.amountXaf))) {
            setState(() => _error = 'Montant entre 1 et ${widget.item.amountXaf} F.');
          } else if (!reasonPattern.hasMatch(_reason.text.trim())) {
            setState(() => _error = 'Motif en majuscules, par exemple PROVIDER_DASHBOARD_CHECKED.');
          } else {
            Navigator.of(context).pop((_outcome, amount, _reason.text.trim()));
          }
        }, child: const Text('Enregistrer')),
      ],
    );
  }
}
