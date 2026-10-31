import 'package:flutter/material.dart';
import 'package:pointer_interceptor/pointer_interceptor.dart';

import '../../../core/presentation/theme_choice.dart';
import '../../../core/presentation/waka_theme.dart';
import '../../drivers/presentation/driver_hub_page.dart';
import '../../identity/data/driver_access_models.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/ride_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../identity/data/voyage_models.dart';
import '../../payments/presentation/wallet_page.dart';
import '../../privacy/presentation/my_data_page.dart';
import '../../referrals/presentation/referral_page.dart';
import '../../rides/data/recent_places.dart';
import '../../rides/presentation/city_ride_page.dart';
import '../../rides/presentation/driver_ride_page.dart';
import '../../voyages/presentation/voyages_page.dart';

/// Step 3 of the redesign: four tabs at the bottom — Course (the map), Voyages, Activité (in progress and history),
/// Compte. Each tab keeps its state while another one is shown. Step 4: an approved driver switches the Course tab
/// between Client and Chauffeur at its top.
class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.login, this.course, this.voyages, this.driver});
  final LoginFlow login;
  /// The ride, trip and driver screens; tests replace the maps and the polling pages.
  final Widget? course, voyages, driver;
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _tab = 0;
  /// Bumped each time Activité is opened, so it reads the server again.
  int _activityVisit = 0;
  DriverAccessView? _access;
  bool _driving = false;

  @override
  void initState() {
    super.initState();
    _readAccess();
  }

  /// Read again when the Course tab is shown: an approval or a change of situation shows up without restarting.
  Future<void> _readAccess() async {
    final gateway = widget.login.gateway, session = widget.login.session, client = widget.login.clientInstance;
    if (gateway is! DriverAccessGateway || session == null || client == null) return;
    try {
      final access = await (gateway as DriverAccessGateway).driverAccess(session, client);
      if (mounted) setState(() { _access = access; if (!canDriveCityRides(access)) _driving = false; });
    } on PhotoProblem {
      // Unknown access: the switch stays hidden, the client side works as usual.
    }
  }

  @override
  Widget build(BuildContext context) {
    final login = widget.login;
    return Scaffold(
      body: IndexedStack(index: _tab, children: [
        Stack(children: [
          Positioned.fill(child: _driving
              ? widget.driver ?? DriverRidePage(login: login, title: '')
              : widget.course ?? CityRidePage(login: login)),
          if (canDriveCityRides(_access) && login.gateway is DriverRideGateway)
            SafeArea(child: Align(alignment: Alignment.topCenter, child: Padding(
              padding: const EdgeInsets.only(top: 10),
              // In a browser the map would take the touches meant for the switch drawn over it.
              child: PointerInterceptor(child: Material(
                color: Theme.of(context).colorScheme.surface,
                elevation: 3,
                borderRadius: BorderRadius.circular(24),
                child: Padding(padding: const EdgeInsets.all(4), child: SegmentedButton<bool>(
                  segments: const [ButtonSegment(value: false, label: Text('Client')), ButtonSegment(value: true, label: Text('Chauffeur'))],
                  selected: {_driving},
                  showSelectedIcon: false,
                  onSelectionChanged: (s) => setState(() => _driving = s.first),
                )),
              )),
            ))),
        ]),
        widget.voyages ?? (login.gateway is VoyageGateway ? VoyagesPage(login: login) : const SizedBox.shrink()),
        ActivityTab(key: ValueKey(_activityVisit), login: login, openRide: () => setState(() => _tab = 0)),
        AccountTab(login: login),
      ]),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _tab,
        onDestinationSelected: (i) {
          setState(() {
            if (i == 2 && _tab != 2) _activityVisit++;
            _tab = i;
          });
          if (i == 0) _readAccess();
        },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.local_taxi_outlined), selectedIcon: Icon(Icons.local_taxi), label: 'Course'),
          NavigationDestination(icon: Icon(Icons.route_outlined), selectedIcon: Icon(Icons.route), label: 'Voyages'),
          NavigationDestination(icon: Icon(Icons.receipt_long_outlined), selectedIcon: Icon(Icons.receipt_long), label: 'Activité'),
          NavigationDestination(icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person), label: 'Compte'),
        ],
      ),
    );
  }
}

const _months = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
String _day(DateTime at) {
  final local = at.toLocal();
  return '${local.day} ${_months[local.month - 1]}';
}

String _outcome(String state, int? refund) => switch (state) {
  'finished' || 'boarded' => 'Terminée',
  'cancelled_by_client' => 'Annulée',
  'cancelled_by_driver' => 'Annulée par le chauffeur',
  'client_absent' || 'no_show' => 'Absent au départ',
  'expired' => 'Non payée',
  _ => state,
} + (refund != null && refund > 0 ? ' · remboursé $refund F' : '');

/// Activité: the ride in progress at the top (a touch opens it on the map), then the past rides and trips.
class ActivityTab extends StatefulWidget {
  const ActivityTab({super.key, required this.login, required this.openRide});
  final LoginFlow login;
  final VoidCallback openRide;
  @override
  State<ActivityTab> createState() => _ActivityTabState();
}

class _ActivityTabState extends State<ActivityTab> {
  CityRideView? _current;
  List<PastRideView> _rides = const [];
  List<ReservationView> _trips = const [];
  List<RecentPlace> _places = const [];
  bool _loading = true;
  String? _problem;

  @override
  void initState() {
    super.initState();
    // The names of recent places only decorate the list: never wait for them.
    loadRecentPlaces().then((places) { if (mounted) setState(() => _places = places); });
    _load();
  }

  Future<void> _load() async {
    final session = widget.login.session, client = widget.login.clientInstance, gateway = widget.login.gateway;
    setState(() { _loading = true; _problem = null; });
    try {
      if (session == null || client == null || !widget.login.confirmedNow) throw const PhotoProblem(PhotoFailure.invalid);
      CityRideView? current;
      var rides = const <PastRideView>[];
      var trips = const <ReservationView>[];
      if (gateway is RideGateway) {
        final ridesGateway = gateway as RideGateway;
        current = await ridesGateway.activeRide(session, client);
        if (current?.viewerRole != 'client') current = null;
        rides = await ridesGateway.pastRides(session, client);
      }
      if (gateway is VoyageGateway) trips = await (gateway as VoyageGateway).myVoyageReservations(session, client);
      if (mounted) setState(() { _current = current; _rides = rides; _trips = trips; });
    } on PhotoProblem {
      if (mounted) setState(() => _problem = 'L’activité n’a pas pu être lue. Tire vers le bas pour réessayer.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final current = _current;
    // Rides and trips together, newest first.
    final past = <(DateTime, Widget)>[
      for (final r in _rides)
        (r.createdAt, ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.local_taxi_outlined),
          title: Text(nameNear(_places, r.dropoff) ?? 'Course en ville'),
          subtitle: Text('${_day(r.createdAt)} · ${r.priceXaf} F · ${_outcome(r.state, r.refundXaf)}'),
        )),
      for (final t in _trips)
        (t.boardingAt, ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.route_outlined),
          title: const Text('Voyage entre villes'),
          subtitle: Text('${_day(t.boardingAt)} · ${t.totalXaf} F · ${switch (t.state) {
            'awaiting_payment' => 'À payer',
            'confirmed' => 'Payé',
            _ => _outcome(t.state, t.refundXaf),
          }}'),
        )),
    ]..sort((a, b) => b.$1.compareTo(a.$1));
    return Scaffold(
      appBar: AppBar(title: const Text('Activité')),
      body: RefreshIndicator(
        onRefresh: _load,
        child: ListView(padding: const EdgeInsets.fromLTRB(20, 4, 20, 24), children: [
          if (current != null)
            Card(
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16), side: BorderSide(color: theme.colorScheme.primary, width: 2)),
              child: ListTile(
                title: Text('En cours', style: theme.textTheme.bodySmall),
                subtitle: Text('${nameNear(_places, current.dropoff) ?? 'Course en ville'} · ${rideStateLabel(current.state)}',
                    style: theme.textTheme.titleMedium),
                trailing: const Icon(Icons.chevron_right),
                onTap: widget.openRide,
              ),
            ),
          if (past.isNotEmpty) ...[
            Padding(padding: const EdgeInsets.only(top: 16, bottom: 4), child: Text('Avant', style: theme.textTheme.titleSmall)),
            for (final (_, tile) in past) tile,
          ],
          if (!_loading && current == null && past.isEmpty && _problem == null)
            const Padding(padding: EdgeInsets.only(top: 48), child: Center(child: Text('Tes courses et tes voyages apparaîtront ici.'))),
          if (_loading) const Padding(padding: EdgeInsets.only(top: 24), child: Center(child: CircularProgressIndicator())),
          if (_problem != null) Padding(padding: const EdgeInsets.only(top: 24), child: Text(_problem!)),
        ]),
      ),
    );
  }
}

/// Compte: who you are, then each part of the account in one list; signing out returns to the first screen.
class AccountTab extends StatelessWidget {
  const AccountTab({super.key, required this.login});
  final LoginFlow login;

  void _open(BuildContext context, Widget page) =>
      Navigator.of(context).push<void>(MaterialPageRoute<void>(builder: (_) => page));

  @override
  Widget build(BuildContext context) => AnimatedBuilder(animation: login, builder: (context, _) {
    final theme = Theme.of(context);
    final name = login.profile?.displayName ?? '';
    final initials = name.split(' ').where((w) => w.isNotEmpty).take(2).map((w) => w[0].toUpperCase()).join();
    final gateway = login.gateway;
    return Scaffold(
      appBar: AppBar(title: const Text('Compte')),
      body: ListView(padding: const EdgeInsets.fromLTRB(20, 4, 20, 24), children: [
        Row(children: [
          CircleAvatar(radius: 28, backgroundColor: theme.colorScheme.primary, foregroundColor: theme.colorScheme.onPrimary,
              child: Text(initials, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w600))),
          const SizedBox(width: 14),
          Expanded(child: Text(name, style: theme.textTheme.titleLarge)),
          IconButton(tooltip: 'Changer mon nom', onPressed: login.busy ? null : () => _rename(context), icon: const Icon(Icons.edit_outlined)),
        ]),
        const SizedBox(height: 16),
        if (gateway is PaymentGateway)
          _row(Icons.account_balance_wallet_outlined, 'Mon argent', () => _open(context, WalletPage(login: login))),
        _row(Icons.directions_car_outlined, 'Devenir chauffeur', () => _open(context, DriverHubPage(login: login))),
        if (gateway is ReferralGateway)
          _row(Icons.card_giftcard_outlined, 'Parrainage', () => _open(context, ReferralPage(login: login))),
        _row(Icons.palette_outlined, 'Apparence', () => _appearance(context)),
        if (gateway is PrivacyGateway)
          _row(Icons.privacy_tip_outlined, 'Mes données', () => _open(context, MyDataPage(login: login))),
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: Icon(Icons.logout, color: theme.colorScheme.error),
          title: Text('Se déconnecter', style: TextStyle(color: theme.colorScheme.error)),
          onTap: login.busy ? null : login.revoke,
        ),
      ]),
    );
  });

  Widget _row(IconData icon, String label, VoidCallback onTap) => ListTile(
    contentPadding: EdgeInsets.zero,
    leading: Icon(icon),
    title: Text(label),
    trailing: const Icon(Icons.chevron_right),
    onTap: onTap,
  );

  void _appearance(BuildContext context) => showModalBottomSheet<void>(
    context: context,
    showDragHandle: true,
    builder: (context) => ValueListenableBuilder<WakaThemeChoice>(
      valueListenable: wakaThemeChoice,
      builder: (context, choice, _) => SafeArea(child: Column(mainAxisSize: MainAxisSize.min, children: [
        for (final c in WakaThemeChoice.values)
          ListTile(
            leading: Icon(choice == c ? Icons.radio_button_checked : Icons.radio_button_off),
            title: Text(switch (c) {
              WakaThemeChoice.light => 'Clair (noir et blanc)',
              WakaThemeChoice.dark => 'Sombre',
              WakaThemeChoice.cameroon => 'Cameroun (vert, rouge, jaune)',
            }),
            onTap: () => chooseTheme(c),
          ),
        const SizedBox(height: 12),
      ])),
    ),
  );

  Future<void> _rename(BuildContext context) async {
    final field = TextEditingController(text: login.profile?.displayName ?? '');
    final name = await showDialog<String>(context: context, builder: (context) => AlertDialog(
      title: const Text('Ton nom complet'),
      content: TextField(controller: field, autofocus: true, maxLength: 80, textCapitalization: TextCapitalization.words),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Annuler')),
        FilledButton(onPressed: () => Navigator.pop(context, field.text.trim().replaceAll(RegExp(r'\s+'), ' ')),
            child: const Text('Enregistrer')),
      ],
    ));
    field.dispose();
    if (name != null && name.split(' ').length >= 2 && name != login.profile?.displayName) await login.saveDisplayName(name);
  }
}
