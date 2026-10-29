import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter/material.dart';

import 'agent_session.dart';
import 'models.dart';

/// Login steps, then the admission queue once an administrative session is open.
class AdminHome extends StatelessWidget {
  const AdminHome({super.key, required this.session});
  final AgentSession session;

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: session,
    builder: (context, _) => Scaffold(
      appBar: AppBar(
        title: const Text('WAKA — administration'),
        actions: [
          if (session.step != AgentStep.phone)
            TextButton.icon(onPressed: session.logout, icon: const Icon(Icons.logout), label: const Text('Se déconnecter')),
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 960),
          child: session.step == AgentStep.ready ? QueuePage(session: session) : LoginPanel(session: session),
        ),
      ),
    ),
  );
}

class LoginPanel extends StatefulWidget {
  const LoginPanel({super.key, required this.session});
  final AgentSession session;
  @override
  State<LoginPanel> createState() => _LoginPanelState();
}

class _LoginPanelState extends State<LoginPanel> {
  final _input = TextEditingController();
  AgentSession get s => widget.session;

  @override
  void dispose() {
    _input.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final (title, help, label, action) = switch (s.step) {
      AgentStep.phone => ('Connexion agent', 'Saisis le numéro de ton compte WAKA. Un code te sera envoyé par SMS.',
          'Numéro de téléphone', 'Recevoir le code'),
      AgentStep.smsCode => ('Code reçu par SMS', 'Code envoyé au ${s.destinationHint ?? 'numéro indiqué'}.', 'Code SMS à 6 chiffres', 'Valider'),
      AgentStep.secondFactor => ('Second facteur', 'Saisis le code à 6 chiffres affiché par ton application d’authentification.',
          'Code de l’application', 'Ouvrir l’administration'),
      AgentStep.enrollment => ('Première configuration', 'Ajoute ce compte dans ton application d’authentification, puis saisis le premier code affiché.',
          'Premier code de l’application', 'Confirmer'),
      AgentStep.ready => ('', '', '', ''),
    };
    Future<void> submit() async {
      final value = _input.text.trim();
      switch (s.step) {
        case AgentStep.phone: await s.requestCode(value);
        case AgentStep.smsCode: await s.verifySms(value);
        case AgentStep.secondFactor: await s.openWithAuthenticator(value);
        case AgentStep.enrollment: await s.confirmEnrollment(value);
        case AgentStep.ready: break;
      }
      _input.clear();
    }

    return ListView(padding: const EdgeInsets.all(24), shrinkWrap: true, children: [
      Text(title, style: Theme.of(context).textTheme.headlineSmall),
      const SizedBox(height: 8),
      Text(help),
      if (s.enrollment != null) ...[
        const SizedBox(height: 12),
        const Text('Clé à saisir dans l’application (affichée une seule fois, ne la note nulle part ailleurs) :'),
        SelectableText(s.enrollment!.secret, style: const TextStyle(fontFamily: 'monospace', fontSize: 18)),
        const Text('Type : basé sur le temps, 6 chiffres, 30 secondes.'),
      ],
      const SizedBox(height: 16),
      TextField(
        controller: _input,
        decoration: InputDecoration(labelText: label, border: const OutlineInputBorder()),
        keyboardType: TextInputType.number,
        obscureText: s.step != AgentStep.phone,
        onSubmitted: (_) => submit(),
      ),
      const SizedBox(height: 12),
      FilledButton(onPressed: s.busy ? null : submit, child: Text(action)),
      if (s.error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Semantics(liveRegion: true, child: Text(s.error!))),
      const SizedBox(height: 24),
      const Text('Rien n’est conservé dans ce navigateur : recharger la page impose de se reconnecter.'),
    ]);
  }
}

class QueuePage extends StatefulWidget {
  const QueuePage({super.key, required this.session});
  final AgentSession session;
  @override
  State<QueuePage> createState() => _QueuePageState();
}

class _QueuePageState extends State<QueuePage> {
  List<WorkItem>? _items;
  String? _error;
  bool _loading = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    if (!s.canExamine) return;
    setState(() { _loading = true; _error = null; });
    try {
      final items = await s.api.workItems(s.client!, s.clientRef, s.admin!);
      if (mounted) setState(() => _items = items);
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _error = messageFor(problem));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (!s.canExamine) {
      return const Padding(padding: EdgeInsets.all(24),
          child: Text('Ta session est ouverte, mais tu n’as pas la fonction ADMISSION : aucun dossier à examiner.'));
    }
    final items = _items;
    return ListView(padding: const EdgeInsets.all(24), children: [
      Row(children: [
        Expanded(child: Text('Dossiers envoyés à l’examen', style: Theme.of(context).textTheme.headlineSmall)),
        IconButton(tooltip: 'Actualiser', onPressed: _loading ? null : _load, icon: const Icon(Icons.refresh)),
      ]),
      Text('Fonctions de ta session : ${s.admin!.functions.join(', ')}. Ton propre dossier n’apparaît jamais ici.'),
      const SizedBox(height: 12),
      if (_error != null) Text(_error!),
      if (items != null && items.isEmpty) const Text('Aucun dossier en attente.'),
      for (final item in items ?? const <WorkItem>[])
        Card(child: ListTile(
          title: Text('Dossier ${item.category == 'taxi' ? 'taxi' : 'voiture personnelle'} — version ${item.version}'),
          subtitle: Text('Envoyé le ${item.submittedAt.toLocal().toString().substring(0, 16)}'),
          trailing: const Icon(Icons.chevron_right),
          onTap: () async {
            await Navigator.of(context).push(MaterialPageRoute<void>(
                builder: (_) => ReviewPage(session: s, applicationId: item.applicationId)));
            if (mounted) await _load();
          },
        )),
    ]);
  }
}

class ReviewPage extends StatefulWidget {
  const ReviewPage({super.key, required this.session, required this.applicationId});
  final AgentSession session;
  final String applicationId;
  @override
  State<ReviewPage> createState() => _ReviewPageState();
}

class _ReviewPageState extends State<ReviewPage> {
  Detail? _detail;
  List<Review> _reviews = [];
  final Map<String, Uint8List> _images = {};
  final _reason = TextEditingController(), _details = TextEditingController();
  String _action = 'request_correction';
  String? _error, _decisionKey, _decisionBody;
  bool _busy = false;
  AgentSession get s => widget.session;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _reason.dispose();
    _details.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() { _busy = true; _error = null; });
    try {
      final detail = await s.api.detail(s.client!, s.clientRef, s.admin!, widget.applicationId);
      if (!mounted) return;
      setState(() {
        _detail = detail;
        _reviews = [
          for (final view in photoViews)
            if (detail.photos[view] != null) Review(label: '$view — ${photoLabels[view]}', id: detail.photos[view]!, isPhoto: true, view: view),
          for (final r in detail.requirements)
            if (r.documentId != null) Review(label: r.document, id: r.documentId!, isPhoto: false),
        ];
        _images.clear();
        _decisionKey = null;
      });
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _error = messageFor(problem));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Loaded only on request: each read of a candidate's file is written to the audit journal.
  Future<void> _show(Review review) async {
    try {
      final bytes = await s.api.image(s.client!, s.clientRef, s.admin!, photo: review.isPhoto, id: review.id);
      if (mounted) setState(() => _images[review.id] = bytes);
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _error = messageFor(problem));
    }
  }

  Map<String, Object?> _body() => {
    'expectedVersion': _detail!.version,
    'action': _action,
    'reasonCode': _reason.text.trim(),
    if (_details.text.trim().isNotEmpty) 'details': _details.text.trim(),
    'photoDecisions': [for (final r in _reviews.where((r) => r.isPhoto && r.result != null)) r.toJson()],
    'documentDecisions': [for (final r in _reviews.where((r) => !r.isPhoto && r.result != null)) r.toJson()],
  };

  String? get _formProblem {
    if (!reasonPattern.hasMatch(_reason.text.trim())) return 'Motif : code en majuscules, par exemple DOCUMENT_UNREADABLE.';
    if (_reviews.any((r) => r.result != null && !r.valid)) return 'Chaque élément « à corriger » doit avoir un motif en majuscules.';
    if (_action == 'request_correction' && !_reviews.any((r) => r.result == 'to_correct') && _details.text.trim().isEmpty) {
      return 'Pour une correction, indique l’élément à corriger ou explique-le dans le détail.';
    }
    if (_action == 'approve' && _detail!.hasRequirementToConfirm) {
      return 'Approbation impossible : une exigence de ce dossier est encore à confirmer par WAKA.';
    }
    if (_action == 'approve' && _reviews.any((r) => r.result != 'acceptable')) {
      return 'Pour approuver, juge chaque photo et chaque pièce « acceptable ».';
    }
    return null;
  }

  Future<void> _decide() async {
    final body = _body();
    final encoded = jsonEncode(body);
    // Same content as the last attempt: same key, so a lost answer is never applied twice.
    if (encoded != _decisionBody) {
      _decisionBody = encoded;
      _decisionKey = randomKey();
    }
    setState(() { _busy = true; _error = null; });
    try {
      await s.api.decide(s.client!, s.clientRef, s.admin!, widget.applicationId, _decisionKey!, body);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Décision enregistrée.')));
      Navigator.of(context).pop();
    } on Object catch (problem) {
      s.handleSessionLoss(problem);
      if (mounted) setState(() => _error = messageFor(problem));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final detail = _detail;
    return Scaffold(
      appBar: AppBar(title: const Text('Examen du dossier')),
      body: detail == null
          ? Center(child: _error == null ? const CircularProgressIndicator() : Text(_error!))
          : ListView(padding: const EdgeInsets.all(24), children: [
              Text('Dossier ${detail.category == 'taxi' ? 'taxi' : 'voiture personnelle'} — version ${detail.version} — état ${detail.state}',
                  style: Theme.of(context).textTheme.titleLarge),
              if (detail.decisionCount > 0) Text('${detail.decisionCount} décision(s) précédente(s) sur ce dossier.'),
              const SizedBox(height: 12),
              Text('Véhicule déclaré', style: Theme.of(context).textTheme.titleMedium),
              for (final entry in detail.vehicle.entries) Text('${_vehicleLabel(entry.key)} : ${entry.value ?? 'non renseigné'}'),
              const Text('Ces informations sont une déclaration : vérifie-les avec la carte grise et les photos.'),
              if (detail.hasRequirementToConfirm)
                Card(color: Theme.of(context).colorScheme.tertiaryContainer, child: const Padding(padding: EdgeInsets.all(12),
                    child: Text('Ce parcours comporte une exigence encore à confirmer par WAKA : l’approbation est impossible pour l’instant. '
                        'Tu peux demander une correction ou refuser, avec motif.'))),
              const Divider(height: 32),
              Text('Photos et pièces', style: Theme.of(context).textTheme.titleMedium),
              for (final r in detail.requirements.where((r) => r.documentId == null))
                Text('• ${r.document} : ${r.established ? 'non fournie' : 'non fournie — exigence à confirmer'}'),
              for (final review in _reviews) _reviewCard(detail, review),
              const Divider(height: 32),
              Text('Décision', style: Theme.of(context).textTheme.titleMedium),
              SegmentedButton<String>(
                segments: const [
                  ButtonSegment(value: 'request_correction', label: Text('Demander une correction')),
                  ButtonSegment(value: 'refuse', label: Text('Refuser')),
                  ButtonSegment(value: 'approve', label: Text('Approuver')),
                ],
                selected: {_action},
                onSelectionChanged: (value) => setState(() => _action = value.first),
              ),
              const SizedBox(height: 12),
              TextField(controller: _reason, onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(labelText: 'Motif (code)', helperText: 'Ex. DOCUMENT_UNREADABLE, PLATE_UNREADABLE, VEHICLE_NOT_ELIGIBLE',
                      border: OutlineInputBorder())),
              const SizedBox(height: 12),
              TextField(controller: _details, maxLength: 500, maxLines: 3, onChanged: (_) => setState(() {}),
                  decoration: const InputDecoration(labelText: 'Explication pour le candidat (facultative)', border: OutlineInputBorder())),
              if (_formProblem != null) Text(_formProblem!),
              FilledButton(onPressed: _busy || _formProblem != null ? null : _decide, child: const Text('Enregistrer la décision')),
              if (_error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Semantics(liveRegion: true, child: Text(_error!))),
            ]),
    );
  }

  Widget _reviewCard(Detail detail, Review review) {
    final requirement = review.isPhoto ? null : detail.requirements.firstWhere((r) => r.documentId == review.id);
    final image = _images[review.id];
    return Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text(review.label, style: Theme.of(context).textTheme.titleSmall),
      if (requirement != null)
        Text([
          requirement.established ? 'Exigence établie' : 'Exigence à confirmer par WAKA',
          'état : ${requirement.reviewState}',
          if (requirement.validUntil != null) 'valable jusqu’au ${requirement.validUntil!.toIso8601String().substring(0, 10)}',
          if (requirement.expired) 'EXPIRÉE',
        ].join(' — ')),
      if (image == null)
        Align(alignment: Alignment.centerLeft, child: TextButton.icon(onPressed: () => _show(review),
            icon: const Icon(Icons.visibility_outlined), label: const Text('Afficher (lecture tracée)')))
      else
        ConstrainedBox(constraints: const BoxConstraints(maxHeight: 360), child: Image.memory(image, fit: BoxFit.contain)),
      Wrap(spacing: 8, crossAxisAlignment: WrapCrossAlignment.center, children: [
        ChoiceChip(label: const Text('Acceptable'), selected: review.result == 'acceptable',
            onSelected: (_) => setState(() => review.result = 'acceptable')),
        ChoiceChip(label: const Text('À corriger'), selected: review.result == 'to_correct',
            onSelected: (_) => setState(() => review.result = 'to_correct')),
        if (review.result != null)
          TextButton(onPressed: () => setState(() => review.result = null), child: const Text('Ne pas juger')),
      ]),
      if (review.result == 'to_correct')
        TextField(onChanged: (value) => setState(() => review.reason = value.trim()),
            decoration: const InputDecoration(labelText: 'Motif de correction (code)', border: OutlineInputBorder())),
    ])));
  }

  static String _vehicleLabel(String key) => switch (key) {
    'brand' => 'Marque',
    'model' => 'Modèle',
    'year' => 'Année',
    'color' => 'Couleur',
    'registration' => 'Immatriculation',
    'authorizedTotalCapacity' => 'Capacité totale autorisée',
    'offeredPassengerSeats' => 'Places passagers proposées',
    'usageRight' => 'Droit d’utilisation',
    _ => key,
  };
}
