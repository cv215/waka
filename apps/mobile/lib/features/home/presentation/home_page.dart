import 'package:flutter/material.dart';
import 'package:flutter/foundation.dart';

import '../../../core/presentation/waka_page.dart';
import '../../diagnostics/presentation/service_health_page.dart';
import '../../drivers/presentation/driver_information_page.dart';
import '../../identity/presentation/sign_in_page.dart';

class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return WakaPage(
      title: 'WAKA',
      actions: [
        if (kDebugMode)
          IconButton(
            tooltip: 'Diagnostic technique',
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute<void>(
                builder: (_) => const ServiceHealthPage(),
              ),
            ),
            icon: const Icon(Icons.info_outline),
          ),
      ],
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Semantics(
            header: true,
            child: Text(
              'Votre déplacement, à votre façon.',
              style: theme.textTheme.headlineMedium?.copyWith(
                fontWeight: FontWeight.w700,
                height: 1.2,
              ),
            ),
          ),
          const SizedBox(height: 12),
          Text(
            'Des places à partager ou un véhicule pour votre parcours.',
            style: theme.textTheme.bodyLarge,
          ),
          const SizedBox(height: 24),
          const InformationPanel(
            title: 'Partagé',
            description:
                'Une ou plusieurs places sur un trajet compatible, '
                'dans la capacité autorisée du véhicule.',
            icon: Icons.groups_outlined,
          ),
          const SizedBox(height: 12),
          const InformationPanel(
            title: 'VIP',
            description:
                'Le véhicule entier pour votre parcours exclusif, '
                'sans autre réservation à bord.',
            icon: Icons.directions_car_outlined,
          ),
          const SizedBox(height: 24),
          const Text('Les réservations ne sont pas encore ouvertes.'),
          const SizedBox(height: 16),
          FilledButton.icon(
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute<void>(builder: (_) => const SignInPage()),
            ),
            icon: const Icon(Icons.login),
            label: const Text('Se connecter'),
          ),
          const SizedBox(height: 12),
          OutlinedButton.icon(
            onPressed: () => Navigator.of(context).push<void>(
              MaterialPageRoute<void>(
                builder: (_) => const DriverInformationPage(),
              ),
            ),
            icon: const Icon(Icons.badge_outlined),
            label: const Text('Informations chauffeur'),
          ),
        ],
      ),
    );
  }
}
