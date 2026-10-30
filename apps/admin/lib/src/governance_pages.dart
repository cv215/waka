import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'admin_api.dart';
import 'agent_session.dart';
import 'models.dart';

const functionLabels = {
  'ADMISSION': 'Admission des chauffeurs',
  'ASSISTANCE': 'Assistance',
  'FINANCE': 'Finance',
  'AUDIT': 'Lecture du journal',
  'HABILITATIONS': 'Gestion des habilitations',
  'PRIVACY': 'Demandes sur les données',
};

String shortDate(DateTime value) => value.toLocal().toString().substring(0, 16);

/// K19: list, grant and revoke. The server refuses self-grants and keeps one manager at least.
class GrantsPage extends StatefulWidget {
  const GrantsPage({super.key, required this.session});
  final AgentSession session;
  @override
  State<GrantsPage> createState() => _GrantsPageState();
}

class _GrantsPageState extends State<GrantsPage> {
  final _account = TextEditingController(), _reason = TextEditingController(text: 'NOUVELLE_MISSION');
  String _function = 'ADMISSION';
  List<GrantView>? _grants;
  String? _message;
  bool _busy = false, _showRevoked = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _account.dispose();
    _reason.dispose();
    super.dispose();
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

  Future<void> _load() => _run(() async {
    final grants = await s.api.grants(s.client!, s.clientRef, s.admin!);
    if (mounted) setState(() => _grants = grants);
  });

  Future<void> _grant() => _run(() async {
    final account = _account.text.trim().toLowerCase(), reason = _reason.text.trim().toUpperCase();
    if (!uuidPattern.hasMatch(account)) throw const ApiProblem(400, null);
    final grant = await s.api.grant(s.client!, s.clientRef, s.admin!, account, _function, reason);
    _account.clear();
    final grants = await s.api.grants(s.client!, s.clientRef, s.admin!);
    if (mounted) {
      setState(() {
        _grants = grants;
        _message = 'Habilitation « ${functionLabels[grant.function]} » active pour ce compte.';
      });
    }
  });

  Future<void> _revoke(GrantView grant) async {
    final reason = await showDialog<String>(context: context, builder: (_) => const _ReasonDialog());
    if (reason == null) return;
    await _run(() async {
      await s.api.revoke(s.client!, s.clientRef, s.admin!, grant.grantId, reason);
      final grants = await s.api.grants(s.client!, s.clientRef, s.admin!);
      if (mounted) setState(() { _grants = grants; _message = 'Habilitation retirée.'; });
    });
  }

  @override
  Widget build(BuildContext context) {
    final own = s.client!.accountId;
    final grants = (_grants ?? const <GrantView>[]).where((g) => _showRevoked || g.active).toList();
    return ListView(padding: const EdgeInsets.all(24), children: [
      Row(children: [
        Expanded(child: Text('Habilitations', style: Theme.of(context).textTheme.headlineSmall)),
        IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : _load, icon: const Icon(Icons.refresh)),
      ]),
      const Text('Chaque attribution et chaque retrait sont datés, motivés et inscrits au journal. '
          'Personne ne peut s’attribuer ou se retirer une habilitation.'),
      const SizedBox(height: 8),
      Row(children: [
        Flexible(child: SelectableText('Ton identifiant de compte : $own')),
        IconButton(tooltip: 'Copier mon identifiant', icon: const Icon(Icons.copy, size: 18),
            onPressed: () => Clipboard.setData(ClipboardData(text: own))),
      ]),
      const SizedBox(height: 16),
      Card(child: Padding(padding: const EdgeInsets.all(16), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        Text('Attribuer une habilitation', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        const Text('L’agent te communique l’identifiant affiché sur son propre écran WAKA. Son compte doit être actif.'),
        const SizedBox(height: 12),
        TextField(controller: _account, decoration: const InputDecoration(
            labelText: 'Identifiant du compte de l’agent', border: OutlineInputBorder())),
        const SizedBox(height: 12),
        DropdownButtonFormField<String>(
          initialValue: _function,
          decoration: const InputDecoration(labelText: 'Fonction', border: OutlineInputBorder()),
          items: [for (final f in adminFunctions) DropdownMenuItem(value: f, child: Text(functionLabels[f]!))],
          onChanged: (value) => setState(() => _function = value ?? _function),
        ),
        const SizedBox(height: 12),
        TextField(controller: _reason, decoration: const InputDecoration(
            labelText: 'Motif (code en majuscules, ex. NOUVELLE_MISSION)', border: OutlineInputBorder())),
        const SizedBox(height: 12),
        FilledButton(onPressed: _busy ? null : _grant, child: const Text('Attribuer')),
      ]))),
      if (_message != null) Padding(padding: const EdgeInsets.symmetric(vertical: 12),
          child: Semantics(liveRegion: true, child: Text(_message!))),
      SwitchListTile(title: const Text('Afficher aussi les habilitations retirées'), value: _showRevoked,
          onChanged: (value) => setState(() => _showRevoked = value)),
      if (_grants != null && grants.isEmpty) const Text('Aucune habilitation à afficher.'),
      for (final grant in grants)
        Card(child: ListTile(
          title: Text('${functionLabels[grant.function]} — compte ${grant.accountId}${grant.accountId == own ? ' (toi)' : ''}'),
          subtitle: Text(grant.active
              ? 'Depuis le ${shortDate(grant.grantedAt)} · motif ${grant.reasonCode}'
              : 'Retirée le ${shortDate(grant.revokedAt!)} · motif ${grant.revokeReason}'),
          trailing: grant.active && grant.accountId != own
              ? TextButton(onPressed: _busy ? null : () => _revoke(grant), child: const Text('Retirer'))
              : null,
        )),
    ]);
  }
}

class _ReasonDialog extends StatefulWidget {
  const _ReasonDialog();
  @override
  State<_ReasonDialog> createState() => _ReasonDialogState();
}

class _ReasonDialogState extends State<_ReasonDialog> {
  final _reason = TextEditingController(text: 'FIN_DE_MISSION');
  @override
  void dispose() {
    _reason.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Retirer cette habilitation ?'),
    content: TextField(controller: _reason, decoration: const InputDecoration(labelText: 'Motif (code en majuscules)')),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
      FilledButton(onPressed: () => Navigator.pop(context, _reason.text.trim().toUpperCase()), child: const Text('Retirer')),
    ],
  );
}

/// A41: newest first, optional filter on one object, older pages on request.
class AuditPage extends StatefulWidget {
  const AuditPage({super.key, required this.session});
  final AgentSession session;
  @override
  State<AuditPage> createState() => _AuditPageState();
}

class _AuditPageState extends State<AuditPage> {
  static const pageSize = 50;
  static const objectTypes = {'': 'Tous', 'admin_grant': 'Habilitations', 'vehicle_photo': 'Photos de véhicule',
    'driver_document': 'Pièces justificatives'};
  final _objectId = TextEditingController();
  String _objectType = '';
  final List<AuditEntry> _entries = [];
  bool _busy = false, _more = false;
  String? _message;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _load(reset: true);
  }

  @override
  void dispose() {
    _objectId.dispose();
    super.dispose();
  }

  Future<void> _load({required bool reset}) async {
    if (_busy) return;
    setState(() { _busy = true; _message = null; });
    try {
      final id = _objectId.text.trim();
      final page = await s.api.audit(s.client!, s.clientRef, s.admin!,
          objectType: _objectType.isEmpty ? null : _objectType, objectId: id.isEmpty ? null : id,
          cursor: reset || _entries.isEmpty ? null : _entries.last.id, limit: pageSize);
      if (mounted) {
        setState(() {
          if (reset) _entries.clear();
          _entries.addAll(page);
          _more = page.length == pageSize;
        });
      }
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _message = messageFor(problem));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => ListView(padding: const EdgeInsets.all(24), children: [
    Text('Journal d’audit', style: Theme.of(context).textTheme.headlineSmall),
    const Text('Actions sensibles, de la plus récente à la plus ancienne. Le journal ne contient que des références et des '
        'codes : jamais le contenu d’une pièce, un nom ou un secret.'),
    const SizedBox(height: 12),
    Wrap(spacing: 12, runSpacing: 12, crossAxisAlignment: WrapCrossAlignment.center, children: [
      SizedBox(width: 300, child: DropdownButtonFormField<String>(
        isExpanded: true,
        initialValue: _objectType,
        decoration: const InputDecoration(labelText: 'Objet', border: OutlineInputBorder()),
        items: [for (final e in objectTypes.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
        onChanged: (value) => setState(() => _objectType = value ?? ''),
      )),
      SizedBox(width: 360, child: TextField(controller: _objectId,
          decoration: const InputDecoration(labelText: 'Référence de l’objet (facultatif)', border: OutlineInputBorder()))),
      FilledButton(onPressed: _busy ? null : () => _load(reset: true), child: const Text('Filtrer')),
    ]),
    if (_message != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_message!)),
    const SizedBox(height: 12),
    if (!_busy && _entries.isEmpty && _message == null) const Text('Aucune entrée.'),
    for (final e in _entries)
      Card(child: ListTile(
        dense: true,
        title: Text('${shortDate(e.occurredAt)} · ${e.eventType} · ${e.outcome}'),
        subtitle: SelectableText('Par ${e.actorRef ?? '—'} (${e.actorScope}) · ${e.objectType} ${e.objectRef ?? ''} · motif ${e.reasonCode}'),
      )),
    if (_more) TextButton(onPressed: _busy ? null : () => _load(reset: false), child: const Text('Entrées plus anciennes')),
  ]);
}
