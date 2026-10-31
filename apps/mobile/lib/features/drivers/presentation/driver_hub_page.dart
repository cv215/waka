import 'package:flutter/material.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_access_models.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../rides/presentation/driver_ride_page.dart';
import 'driver_access_page.dart';
import 'driver_application_page.dart';
import 'driver_information_page.dart';

/// Step 4 of the redesign, « Devenir chauffeur »: one driver profile at a time (R16). Without a file, two cards — Taxi
/// or Particulier; with one, where it stands; once approved, the driver mode and a change of situation, the approved
/// profile serving until the new file is decided.
class DriverHubPage extends StatefulWidget {
  const DriverHubPage({super.key, required this.login, this.openApplication});
  final LoginFlow login;
  /// Opens the guided file; tests replace it.
  final void Function(BuildContext context)? openApplication;
  @override
  State<DriverHubPage> createState() => _DriverHubPageState();
}

class _DriverHubPageState extends State<DriverHubPage> {
  LoginFlow get _login => widget.login;
  DriverAccessView? _access;

  @override
  void initState() {
    super.initState();
    // After the first frame: reading the file notifies the page that is being built.
    WidgetsBinding.instance.addPostFrameCallback((_) { if (mounted) _load(); });
  }

  Future<void> _load() async {
    await _login.refreshApplication();
    final gateway = _login.gateway, session = _login.session, client = _login.clientInstance;
    if (gateway is DriverAccessGateway && session != null && client != null) {
      try {
        final access = await (gateway as DriverAccessGateway).driverAccess(session, client);
        if (mounted) setState(() => _access = access);
      } on PhotoProblem {
        // Without the access, the page still shows the file; the driver mode stays hidden.
      }
    }
  }

  void _open(Widget page) => Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => page)).then((_) => _load());

  void _openFile() => widget.openApplication != null ? widget.openApplication!(context) : _open(DriverApplicationPage(flow: _login));

  Future<void> _start(String category) async {
    await _login.startApplication(category);
    if (mounted && _login.application != null) _openFile();
  }

  Future<void> _change(String category) async {
    final toTaxi = category == 'taxi';
    final ok = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: Text(toTaxi ? 'Passer chauffeur taxi ?' : 'Passer chauffeur particulier ?'),
      content: Text([
        'Un nouveau dossier ${toTaxi ? 'taxi' : 'particulier'} est examiné par un agent. Ton profil actuel reste actif pendant ce temps.',
        'Une fois approuvé, il remplace l’ancien. S’il est refusé, rien ne change.',
        if (toTaxi) 'Ton abonnement de particulier s’arrête ; les jours déjà payés ne sont pas remboursés.',
      ].join('\n\n')),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Annuler')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Changer')),
      ],
    ));
    if (ok == true) await _start(category);
  }

  static String _name(String category) => category == 'taxi' ? 'Taxi' : 'Particulier';

  static String _state(String state) => switch (state) {
    'draft' => 'À compléter',
    'submitted' => 'En cours d’examen',
    'correction_requested' => 'Correction demandée',
    'approved' => 'Approuvé',
    'refused' => 'Refusé',
    _ => state,
  };

  @override
  Widget build(BuildContext context) => AnimatedBuilder(animation: _login, builder: (context, _) {
    final theme = Theme.of(context);
    final file = _login.application, access = _access;
    // The profile in service: the approved file, or the one still serving while a change is examined.
    final approved = access != null && access.category != null && {'active', 'provisional', 'new_reservations_blocked',
        'subscription_required'}.contains(access.access);
    final changing = approved && file != null && file.requestedCategory != access.category && file.state != 'approved';
    return Scaffold(
      appBar: AppBar(title: const Text('Devenir chauffeur')),
      body: ListView(padding: const EdgeInsets.fromLTRB(20, 4, 20, 24), children: [
        if (_login.busy && file == null) const LinearProgressIndicator(),
        if (_login.noApplication) ...[
          Text('Choisis ton type de chauffeur. Un seul profil à la fois.', style: theme.textTheme.bodyLarge),
          const SizedBox(height: 16),
          _choice(Icons.local_taxi_outlined, 'Taxi', 'Sans abonnement', () => _start('taxi')),
          _choice(Icons.directions_car_outlined, 'Particulier', 'Essai, puis 3 000 F pour 30 jours', () => _start('private')),
        ],
        if (approved) ...[
          _profileCard(theme, _name(access.category!), changing ? 'Actif pendant le changement' : 'Approuvé', selected: true),
          const SizedBox(height: 8),
          if (_login.gateway is DriverRideGateway)
            FilledButton.icon(onPressed: () => _open(DriverRidePage(login: _login)), icon: const Icon(Icons.drive_eta_outlined),
                label: const Text('Mode chauffeur')),
          const SizedBox(height: 8),
          OutlinedButton(onPressed: () => _open(DriverAccessPage(login: _login)), child: const Text('Mon accès chauffeur')),
        ],
        if (changing) ...[
          const SizedBox(height: 20),
          Text('Changement de situation', style: theme.textTheme.titleMedium),
          const SizedBox(height: 8),
          _profileCard(theme, _name(file.requestedCategory), _state(file.state), onTap: _openFile),
        ] else if (file != null && !approved) ...[
          _profileCard(theme, _name(file.requestedCategory), _state(file.state), selected: true, onTap: _openFile),
          const SizedBox(height: 8),
          Text(file.editable ? 'Complète ton dossier : une étape à la fois.' : 'Un agent examine ton dossier.',
              style: theme.textTheme.bodyMedium),
        ],
        if (approved && !changing && file != null && file.state == 'approved') ...[
          const SizedBox(height: 20),
          TextButton.icon(
            onPressed: _login.busy ? null : () => _change(file.requestedCategory == 'taxi' ? 'private' : 'taxi'),
            icon: const Icon(Icons.swap_horiz),
            label: Text('Changer de situation : devenir ${file.requestedCategory == 'taxi' ? 'particulier' : 'taxi'}'),
          ),
        ],
        const SizedBox(height: 12),
        TextButton(onPressed: () => _open(const DriverInformationPage()), child: const Text('Conduire avec WAKA : ce qu’il faut savoir')),
      ]),
    );
  });

  Widget _choice(IconData icon, String title, String detail, VoidCallback onTap) => Card(
    margin: const EdgeInsets.only(bottom: 12),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      leading: Icon(icon, size: 30),
      title: Text(title, style: Theme.of(context).textTheme.titleMedium),
      subtitle: Text(detail),
      trailing: const Icon(Icons.chevron_right),
      onTap: _login.busy || !_login.canStartApplication ? null : onTap,
    ),
  );

  Widget _profileCard(ThemeData theme, String title, String state, {bool selected = false, VoidCallback? onTap}) => Card(
    shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16),
        side: BorderSide(color: selected ? theme.colorScheme.primary : theme.colorScheme.outlineVariant, width: selected ? 2 : 1)),
    child: ListTile(
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      leading: Icon(title == 'Taxi' ? Icons.local_taxi_outlined : Icons.directions_car_outlined, size: 30),
      title: Text('Chauffeur ${title.toLowerCase()}', style: theme.textTheme.titleMedium),
      subtitle: Text(state),
      trailing: onTap == null ? null : const Icon(Icons.chevron_right),
      onTap: onTap,
    ),
  );
}

/// The driver access states in which the driver mode can be used (city rides need an active or provisional access).
bool canDriveCityRides(DriverAccessView? access) => access != null && {'active', 'provisional'}.contains(access.access);
