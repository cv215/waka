import 'package:flutter/material.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/privacy_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../application/my_data_flow.dart';

const _typeLabels = {
  'driver_application': 'Candidature chauffeur',
  'privacy_request': 'Demande sur mes données',
  'quote': 'Devis',
};
const _stateLabels = {
  'draft': 'brouillon',
  'submitted': 'envoyée à l’examen',
  'correction_requested': 'correction demandée',
  'refused': 'refusée',
  'approved': 'approuvée',
  'received': 'reçue',
  'valid': 'valable',
  'expired': 'expiré',
};

/// « Mes données » : ask for one's data or their deletion, and see one's own activity.
class MyDataPage extends StatefulWidget {
  const MyDataPage({super.key, required this.login});
  final LoginFlow login;
  @override
  State<MyDataPage> createState() => _MyDataPageState();
}

class _MyDataPageState extends State<MyDataPage> {
  late final MyDataFlow _flow = MyDataFlow(widget.login, widget.login.gateway as PrivacyGateway);
  final _details = TextEditingController();

  @override
  void initState() {
    super.initState();
    _flow.refresh();
  }

  @override
  void dispose() {
    _flow.dispose();
    _details.dispose();
    super.dispose();
  }

  Future<void> _ask(String kind) async {
    if (kind == 'deletion') {
      final confirmed = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
        title: const Text('Demander la suppression ?'),
        content: const Text('WAKA examinera ta demande. Les données qui doivent être gardées pour une obligation '
            '(paiements, litiges) seront expliquées. Rien n’est supprimé ni fermé à l’envoi de la demande.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Envoyer la demande')),
        ],
      ));
      if (confirmed != true) return;
    }
    await _flow.request(kind, _details.text);
    if (mounted && _flow.failure == null) _details.clear();
  }

  String? get _error => switch (_flow.failure) {
    null => null,
    PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
    PhotoFailure.keyReused => 'Cette demande ne correspond plus au serveur. Recommence.',
    PhotoFailure.unavailable => 'Ce service est momentanément indisponible. Réessaie plus tard.',
    PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie : la même demande sera renvoyée, sans doublon.',
    PhotoFailure.invalidResponse => 'La réponse n’a pas pu être vérifiée. Vérifie le texte saisi puis réessaie.',
    _ => 'Cette action n’est pas possible maintenant.',
  };

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _flow,
    builder: (context, _) {
      final last = _flow.lastRequest;
      final items = _flow.items;
      return WakaPage(
        title: 'Mes données',
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          const Text('Tu peux demander une copie de tes données ou leur suppression. WAKA te répondra après examen.'),
          const SizedBox(height: 12),
          TextField(
            controller: _details,
            maxLength: 500,
            maxLines: 3,
            decoration: const InputDecoration(labelText: 'Précision (facultatif)', border: OutlineInputBorder()),
          ),
          const SizedBox(height: 8),
          FilledButton.tonal(onPressed: _flow.busy ? null : () => _ask('access'), child: const Text('Demander mes données')),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: _flow.busy ? null : () => _ask('deletion'), child: const Text('Demander la suppression')),
          if (_flow.pendingKind != null && _flow.failure == PhotoFailure.uncertain)
            TextButton(onPressed: _flow.busy ? null : _flow.retry, child: const Text('Réessayer l’envoi')),
          if (last != null)
            Padding(
              padding: const EdgeInsets.only(top: 12),
              child: Semantics(liveRegion: true, child: Text(last.created
                  ? 'Demande ${last.kind == 'access' ? 'd’accès' : 'de suppression'} reçue. Rien n’a encore été supprimé.'
                  : 'Une demande ${last.kind == 'access' ? 'd’accès' : 'de suppression'} est déjà en cours : elle est conservée.')),
            ),
          if (_error != null) Padding(padding: const EdgeInsets.only(top: 12), child: Text(_error!)),
          const Divider(height: 32),
          Row(children: [
            Expanded(child: Text('Mon activité', style: Theme.of(context).textTheme.titleLarge)),
            IconButton(tooltip: 'Actualiser', onPressed: _flow.busy ? null : _flow.refresh, icon: const Icon(Icons.refresh)),
          ]),
          if (items == null) const Text('Activité non chargée.'),
          if (items != null && items.isEmpty) const Text('Aucune activité pour l’instant.'),
          for (final item in items ?? const <ActivityItem>[])
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(_typeLabels[item.objectType] ?? item.objectType),
              subtitle: Text('${_stateLabels[item.state] ?? item.state} · ${item.at.toLocal().toString().substring(0, 16)}'),
            ),
          if (_flow.hasMore)
            TextButton(onPressed: _flow.busy ? null : _flow.more, child: const Text('Voir plus')),
        ]),
      );
    },
  );
}
