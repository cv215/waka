import 'package:flutter/material.dart';

import '../../../core/config/local_api_configuration.dart';
import '../../../core/presentation/waka_page.dart';
import '../data/service_health_client.dart';

class ServiceHealthPage extends StatefulWidget {
  const ServiceHealthPage({super.key});

  @override
  State<ServiceHealthPage> createState() => _ServiceHealthPageState();
}

class _ServiceHealthPageState extends State<ServiceHealthPage> {
  late final ServiceHealthClient _client = ServiceHealthClient(
    LocalApiConfiguration.fromDefines(),
  );
  HealthSnapshot? _snapshot;
  bool _loading = false;

  Future<void> _check() async {
    if (_loading) return;
    setState(() => _loading = true);
    final result = await _client.check();
    if (!mounted) return;
    setState(() {
      _snapshot = result;
      _loading = false;
    });
  }

  @override
  void dispose() {
    _client.dispose();
    super.dispose();
  }

  String _label(HealthState state) => switch (state) {
    HealthState.notChecked => 'Non vérifiée',
    HealthState.up => 'Réponse reçue',
    HealthState.down => 'Indisponible',
    HealthState.unreachable =>
      'Connexion impossible. Vérifie le serveur et le réseau.',
    HealthState.timedOut => 'Délai dépassé. Tu peux réessayer.',
    HealthState.invalid => 'Réponse inattendue. Vérification impossible.',
    HealthState.unconfigured =>
      'Connexion locale à configurer dans les outils de développement.',
  };

  @override
  Widget build(BuildContext context) {
    return WakaPage(
      title: 'Diagnostic',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const InformationPanel(
            title: 'Contrôle technique local',
            description:
                'Ce diagnostic concerne uniquement la réponse technique du serveur. '
                'Il ne confirme pas la disponibilité des chauffeurs, '
                'des réservations ou des paiements.',
            icon: Icons.info_outline,
          ),
          const SizedBox(height: 20),
          if (_snapshot == null) const Text('Vérification non effectuée'),
          if (_snapshot != null) ...[
            Text('Serveur : ${_label(_snapshot!.process)}'),
            const SizedBox(height: 12),
            Text('Base de données : ${_label(_snapshot!.database)}'),
          ],
          const SizedBox(height: 20),
          if (_loading) const LinearProgressIndicator(),
          FilledButton(
            onPressed: _loading ? null : _check,
            child: Text(
              _loading ? 'Vérification en cours…' : 'Vérifier la connexion',
            ),
          ),
        ],
      ),
    );
  }
}
