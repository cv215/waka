import 'package:flutter/material.dart';

import '../../../core/presentation/waka_page.dart';

/// Information publique : aucun dossier ou accès chauffeur créé par cette vue.
class DriverInformationPage extends StatelessWidget {
  const DriverInformationPage({super.key});

  @override
  Widget build(BuildContext context) {
    return const WakaPage(
      title: 'Chauffeur',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          InformationPanel(
            title: 'Conduire avec WAKA',
            description:
                'Taxi ou particulier : l’accès dépend de l’examen du dossier, '
                'du véhicule et des autorisations applicables.',
            icon: Icons.fact_check_outlined,
          ),
          SizedBox(height: 12),
          InformationPanel(
            title: 'Un véhicule identifié',
            description:
                'Les informations du véhicule et six photos sont nécessaires : '
                'avant, arrière avec plaque lisible, gauche, droite, '
                'habitacle avant et habitacle arrière. '
                'Le maximum est de cinq passagers hors chauffeur, '
                'dans la capacité légalement autorisée.',
            icon: Icons.directions_car_outlined,
          ),
          SizedBox(height: 12),
          InformationPanel(
            title: 'Des justificatifs à vérifier',
            description:
                'Les pièces requises dépendent de la catégorie et des exigences '
                'applicables. Un agent examine le dossier et peut demander '
                'une correction avant toute approbation.',
            icon: Icons.folder_outlined,
          ),
          SizedBox(height: 20),
          Text('Le dépôt des candidatures n’est pas encore ouvert.'),
        ],
      ),
    );
  }
}
