import 'package:flutter/material.dart';

import 'agent_session.dart';
import 'governance_pages.dart' show shortDate;
import 'models.dart';

/// R12: requests about personal data, for a PRIVACY agent. Reading them is audited by the server;
/// closing records an outcome and a reason but deletes nothing by itself (procedure A05).
class PrivacyPage extends StatefulWidget {
  const PrivacyPage({super.key, required this.session});
  final AgentSession session;
  @override
  State<PrivacyPage> createState() => _PrivacyPageState();
}

class _PrivacyPageState extends State<PrivacyPage> {
  List<PrivacyItem>? _items;
  String? _message;
  bool _busy = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _run(_load);
  }

  Future<void> _load() async {
    final items = await s.api.privacyRequests(s.client!, s.clientRef, s.admin!);
    if (mounted) setState(() => _items = items);
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

  Future<void> _close(PrivacyItem item) async {
    final result = await showDialog<(String, String)>(context: context, builder: (_) => const _ClosureDialog());
    if (result == null) return;
    await _run(() async {
      await s.api.closePrivacyRequest(s.client!, s.clientRef, s.admin!, item.requestId, result.$1, result.$2);
      _message = 'Demande clôturée.';
      await _load();
    });
  }

  @override
  Widget build(BuildContext context) {
    final items = _items;
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text('Demandes sur les données', style: Theme.of(context).textTheme.headlineSmall),
      const Text('Chaque lecture d’une demande est inscrite au journal. Clôturer n’efface rien : la suppression suit la '
          'procédure prévue, et les durées de conservation restent à qualifier juridiquement.'),
      if (_message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_message!)),
      const SizedBox(height: 12),
      if (items != null && items.isEmpty) const Text('Aucune demande.'),
      for (final item in items ?? const <PrivacyItem>[])
        Card(child: ListTile(
          title: Text('${item.kind == 'access' ? 'Accès aux données' : 'Suppression'} · reçue le ${shortDate(item.receivedAt)}'),
          subtitle: Text([
            if (item.details != null) '« ${item.details} »',
            item.open ? 'À traiter' : 'Clôturée : ${item.outcome == 'completed' ? 'traitée' : 'refusée'} (${item.outcomeReason})',
          ].join('\n')),
          trailing: item.open ? TextButton(onPressed: _busy ? null : () => _close(item), child: const Text('Clôturer')) : null,
        )),
    ]);
  }
}

class _ClosureDialog extends StatefulWidget {
  const _ClosureDialog();
  @override
  State<_ClosureDialog> createState() => _ClosureDialogState();
}

class _ClosureDialogState extends State<_ClosureDialog> {
  String _outcome = 'completed';
  final _reason = TextEditingController();
  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Clôturer la demande'),
    content: Column(mainAxisSize: MainAxisSize.min, children: [
      SegmentedButton<String>(
        segments: const [ButtonSegment(value: 'completed', label: Text('Traitée')), ButtonSegment(value: 'refused', label: Text('Refusée'))],
        selected: {_outcome},
        onSelectionChanged: (value) => setState(() => _outcome = value.first),
      ),
      TextField(controller: _reason, onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(labelText: 'Motif (code en majuscules)')),
    ]),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
      FilledButton(
        onPressed: reasonPattern.hasMatch(_reason.text.trim()) ? () => Navigator.pop(context, (_outcome, _reason.text.trim())) : null,
        child: const Text('Clôturer'),
      ),
    ],
  );
}

/// ASSISTANCE: voyages whose boarding is locked after ten wrong codes; unlocking needs a reason.
class AssistancePage extends StatefulWidget {
  const AssistancePage({super.key, required this.session});
  final AgentSession session;
  @override
  State<AssistancePage> createState() => _AssistancePageState();
}

class _AssistancePageState extends State<AssistancePage> {
  List<LockedVoyage>? _voyages;
  final _reason = TextEditingController(text: 'PASSENGER_IDENTIFIED');
  String? _message;
  bool _busy = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _run(_load);
  }

  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final voyages = await s.api.lockedVoyages(s.client!, s.clientRef, s.admin!);
    if (mounted) setState(() => _voyages = voyages);
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

  @override
  Widget build(BuildContext context) {
    final voyages = _voyages;
    return ListView(padding: const EdgeInsets.all(24), children: [
      Text('Voyages bloqués', style: Theme.of(context).textTheme.headlineSmall),
      const Text('Après 10 codes d’embarquement faux, un voyage est bloqué. Vérifie la situation avec le chauffeur, '
          'puis débloque avec un motif : l’action est inscrite au journal.'),
      const SizedBox(height: 12),
      TextField(controller: _reason, decoration: const InputDecoration(labelText: 'Motif du déblocage (code)', border: OutlineInputBorder())),
      if (_message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_message!)),
      const SizedBox(height: 12),
      if (voyages != null && voyages.isEmpty) const Text('Aucun voyage bloqué.'),
      for (final v in voyages ?? const <LockedVoyage>[])
        Card(child: ListTile(
          title: Text('Voyage ${v.voyageId.substring(0, 8)} · départ ${shortDate(v.departureAt)}'),
          subtitle: Text('${v.failures} codes erronés'),
          trailing: TextButton(
            onPressed: _busy || !reasonPattern.hasMatch(_reason.text.trim()) ? null : () => _run(() async {
              await s.api.unlockVoyage(s.client!, s.clientRef, s.admin!, v.voyageId, _reason.text.trim());
              _message = 'Voyage débloqué.';
              await _load();
            }),
            child: const Text('Débloquer'),
          ),
        )),
    ]);
  }
}
