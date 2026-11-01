import 'package:flutter/material.dart';

import '../../../core/presentation/waka_page.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/payment_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../identity/data/voyage_models.dart';
import '../../payments/presentation/pay_dialog.dart';

typedef PickDateTime = Future<DateTime?> Function(BuildContext context);

Future<DateTime?> pickWithDialogs(BuildContext context) async {
  final now = DateTime.now();
  final day = await showDatePicker(context: context, firstDate: now, lastDate: now.add(const Duration(days: 60)), initialDate: now);
  if (day == null || !context.mounted) return null;
  final time = await showTimePicker(context: context, initialTime: TimeOfDay.fromDateTime(now));
  if (time == null) return null;
  return DateTime(day.year, day.month, day.day, time.hour, time.minute);
}

String _when(DateTime value) => value.toLocal().toString().substring(0, 16);
String _hour(DateTime value) => value.toLocal().toString().substring(11, 16);
const _weekdays = ['lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.', 'dim.'];
/// « Aujourd'hui », « Demain », or the day and its date (« sam. 15 »).
String _dayLabel(DateTime day) {
  final today = DateTime.now();
  final start = DateTime(today.year, today.month, today.day);
  final d = DateTime(day.year, day.month, day.day);
  final gap = d.difference(start).inDays;
  return gap == 0 ? 'Aujourd’hui' : gap == 1 ? 'Demain' : '${_weekdays[d.weekday - 1]} ${d.day}';
}
String _route(VoyageView v) => v.stops.map((s) => s.place).join(' – ');

String voyageMessage(PhotoFailure failure) => switch (failure) {
  PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
  PhotoFailure.driverNotAllowed => 'Seul un chauffeur approuvé, avec ses pièces valables, peut publier un voyage.',
  PhotoFailure.notEnoughSeats => 'Il ne reste plus assez de places sur ce trajet.',
  PhotoFailure.ownVoyage => 'Tu ne peux pas réserver ton propre voyage.',
  PhotoFailure.departed => 'Ce départ est déjà passé.',
  PhotoFailure.tooManySeats => 'Ton véhicule approuvé ne propose pas autant de places.',
  PhotoFailure.wrongCode => 'Ce code ne correspond à aucun passager de ce voyage.',
  PhotoFailure.notPaid => 'Cette réservation n’est pas encore payée : le passager ne peut pas embarquer.',
  PhotoFailure.tooEarly => 'Un passager ne peut être déclaré absent qu’après l’heure de départ prévue.',
  PhotoFailure.boardingLocked => 'Trop de codes erronés : l’embarquement de ce voyage est bloqué. Contacte l’assistance WAKA.',
  PhotoFailure.notFound => 'Ce voyage n’est plus disponible.',
  PhotoFailure.keyReused => 'Cette demande ne correspond plus au serveur. Recommence.',
  PhotoFailure.unavailable => 'Les voyages sont momentanément indisponibles. Réessaie plus tard.',
  PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie : la même demande sera renvoyée, sans doublon.',
  _ => 'Demande refusée : vérifie les informations saisies.',
};

/// « Voyage » : intercity trips. A reservation is paid within three minutes (R14), by Mobile Money or from the WAKA credit.
class VoyagesPage extends StatefulWidget {
  const VoyagesPage({super.key, required this.login, this.pickDateTime = pickWithDialogs, this.pollEvery = const Duration(seconds: 4)});
  final LoginFlow login;
  final PickDateTime pickDateTime;
  final Duration? pollEvery;
  @override
  State<VoyagesPage> createState() => _VoyagesPageState();
}

class _VoyagesPageState extends State<VoyagesPage> {
  VoyageGateway get _gateway => widget.login.gateway as VoyageGateway;
  List<VoyageView>? _upcoming, _mine;
  List<ReservationView>? _reservations;
  List<IncidentView>? _incidents;
  String? _message;
  bool _busy = false;
  /// Same request after a lost answer: same key, so the server never records it twice.
  final Map<String, String> _keys = {};
  /// Publishing a trip needs an approved driver account (the server refuses anyone else): only then the « Mes voyages »
  /// tab appears. A private driver whose subscription is due still publishes trips (R12: no subscription for them).
  bool _driver = false;
  /// The search of the « Trouver » tab: departure, arrival (parts of a stop name) and the day (null: every day).
  final _fromText = TextEditingController(), _toText = TextEditingController();
  DateTime? _day;

  @override
  void dispose() {
    _fromText.dispose();
    _toText.dispose();
    super.dispose();
  }
  static const _publishing = {'active', 'provisional', 'subscription_required'};

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  (LoginSession, String) _context() {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (!widget.login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = voyageMessage(problem.failure));
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _refresh() => _run(_load);

  Future<void> _load() async {
    final (session, client) = _context();
    final upcoming = await _gateway.upcomingVoyages(session, client);
    final reservations = await _gateway.myVoyageReservations(session, client);
    var driver = false;
    final access = widget.login.gateway;
    if (access is DriverAccessGateway) {
      try {
        driver = _publishing.contains((await (access as DriverAccessGateway).driverAccess(session, client)).access);
      } on PhotoProblem {
        driver = false; // unknown access: nothing to publish rather than a refused form
      }
    }
    final mine = driver ? await _gateway.myVoyages(session, client) : const <VoyageView>[];
    final incidents = await _gateway.myIncidents(session, client);
    if (mounted) {
      setState(() { _upcoming = upcoming; _reservations = reservations; _mine = mine; _incidents = incidents; _driver = driver; });
    }
  }

  String _key(String request) => _keys.putIfAbsent(request, randomLoginId);

  Future<void> _reserve(VoyageView voyage, int from, int to, int seats) => _run(() async {
    final (session, client) = _context();
    final request = 'reserve:${voyage.voyageId}:$from:$to:$seats';
    final reservation = await _gateway.reserveVoyage(session, client, _key(request), voyage.voyageId, from, to, seats);
    _keys.remove(request);
    _message = 'Réservation enregistrée : ${reservation.totalXaf} F. Tes places sont gardées 3 minutes : paie maintenant.';
    await _load();
    if (mounted && widget.login.gateway is PaymentGateway) await _pay(reservation.reservationId, reservation.totalXaf, reload: false);
  });

  Future<void> _pay(String reservationId, int amount, {bool reload = true}) async {
    final payment = await showDialog<PaymentView>(context: context, barrierDismissible: false, builder: (_) => PayDialog(
        login: widget.login, purpose: 'voyage', targetId: reservationId, amountXaf: amount, title: 'Payer mes places',
        pollEvery: widget.pollEvery));
    if (payment != null && payment.state == 'succeeded' && payment.applied) {
      _message = 'Places payées : ton code d’embarquement est actif.';
    }
    if (reload) {
      await _run(_load);
    } else {
      await _load();
    }
  }

  Future<void> _cancelReservation(ReservationView reservation) => _run(() async {
    final (session, client) = _context();
    final result = await _gateway.cancelVoyageReservation(session, client, reservation.reservationId);
    _message = result.refundXaf == null
        ? 'Réservation annulée.'
        : 'Réservation annulée : ${result.refundXaf} F remboursés (frais ${result.feePercent} %).';
    await _load();
  });

  Future<void> _cancelVoyage(VoyageView voyage) => _run(() async {
    final (session, client) = _context();
    await _gateway.cancelVoyage(session, client, voyage.voyageId, 'DRIVER_CANCELLED');
    _message = 'Voyage annulé : chaque passager est remboursé en entier.';
    await _load();
  });

  Future<bool> _publish(List<VoyageStop> stops, int seats, Map<String, int> prices) async {
    var published = false;
    await _run(() async {
    final (session, client) = _context();
    final request = 'publish:${stops.map((s) => '${s.place}@${s.plannedAt.toIso8601String()}').join('|')}:$seats:$prices';
    await _gateway.publishVoyage(session, client, _key(request), stops, seats, prices);
    _keys.remove(request);
      _message = 'Voyage publié.';
      published = true;
      await _load();
    });
    return published;
  }

  @override
  Widget build(BuildContext context) => DefaultTabController(
    key: ValueKey(_driver),
    length: _driver ? 4 : 3,
    child: Scaffold(
      appBar: AppBar(
        title: const Text('Voyages'),
        actions: [IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : _refresh, icon: const Icon(Icons.refresh))],
        bottom: TabBar(isScrollable: true, tabs: [
          const Tab(text: 'Trouver'),
          const Tab(text: 'Mes réservations'),
          if (_driver) const Tab(text: 'Mes voyages'),
          const Tab(text: 'Incidents'),
        ]),
      ),
      body: Column(children: [
        if (_message != null)
          Padding(padding: const EdgeInsets.all(12), child: Semantics(liveRegion: true, child: Text(_message!))),
        Expanded(child: TabBarView(children: [_find(), _myReservations(), if (_driver) _myVoyages(), _myIncidents()])),
      ]),
    ),
  );

  /// Step 6 of the redesign: where from, where to, which day, then simple cards; one opens the trip.
  Widget _find() {
    final voyages = _upcoming;
    if (voyages == null) return const WakaLoading();
    final from = _fromText.text.trim().toLowerCase(), to = _toText.text.trim().toLowerCase();
    // The stops a trip serves for this search: the first one matching the departure, a later one matching the arrival.
    (int, int)? section(VoyageView v) {
      final a = from.isEmpty ? 0 : v.stops.indexWhere((st) => st.place.toLowerCase().contains(from));
      if (a < 0 || a == v.stops.length - 1) return null;
      var b = v.stops.length - 1;
      if (to.isNotEmpty) {
        b = -1;
        for (var i = a + 1; i < v.stops.length; i++) {
          if (v.stops[i].place.toLowerCase().contains(to)) { b = i; break; }
        }
      }
      return b < 0 ? null : (a, b);
    }
    DateTime dayOf(DateTime t) { final l = t.toLocal(); return DateTime(l.year, l.month, l.day); }
    final days = {for (final v in voyages) dayOf(v.stops.first.plannedAt)}.toList()..sort();
    final shown = [
      for (final v in voyages)
        if (section(v) != null && (_day == null || dayOf(v.stops.first.plannedAt) == _day)) (v, section(v)!),
    ];
    final theme = Theme.of(context);
    InputDecoration field(String hint, IconData icon) => InputDecoration(hintText: hint, prefixIcon: Icon(icon), filled: true,
        fillColor: theme.colorScheme.surfaceContainerHigh,
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none));
    return ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
      TextField(controller: _fromText, onChanged: (_) => setState(() {}), decoration: field('Départ', Icons.trip_origin)),
      const SizedBox(height: 8),
      TextField(controller: _toText, onChanged: (_) => setState(() {}), decoration: field('Arrivée', Icons.place_outlined)),
      const SizedBox(height: 10),
      if (days.length > 1)
        Wrap(spacing: 8, children: [
          ChoiceChip(label: const Text('Tous'), selected: _day == null, onSelected: (_) => setState(() => _day = null)),
          for (final d in days)
            ChoiceChip(label: Text(_dayLabel(d)), selected: _day == d, onSelected: (_) => setState(() => _day = d)),
        ]),
      const SizedBox(height: 8),
      if (shown.isEmpty)
        Padding(padding: const EdgeInsets.only(top: 32), child: Center(child: Text(voyages.isEmpty
            ? 'Aucun voyage à venir pour l’instant.' : 'Aucun voyage pour cette recherche.'))),
      for (final (v, (a, b)) in shown)
        Card(child: InkWell(
          borderRadius: BorderRadius.circular(16),
          onTap: _busy ? null : () => _openReservation(v, from: a, to: b),
          child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            Text(_route(v), style: theme.textTheme.bodySmall),
            const SizedBox(height: 4),
            Row(children: [
              Expanded(child: Text('${_dayLabel(v.stops[a].plannedAt.toLocal())} · ${_hour(v.stops[a].plannedAt)} – ${_hour(v.stops[b].plannedAt)}',
                  style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700))),
              Text('${v.prices['$a-$b']} F', style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
            ]),
            Text([
              ?v.driverFirstName,
              ?v.vehicle,
              '${v.availableSeats['$a-$b'] ?? 0} place(s)',
            ].join(' · '), style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ])),
        )),
    ]);
  }

  Future<void> _openReservation(VoyageView voyage, {int from = 0, int? to}) async {
    final choice = await Navigator.of(context).push<(int, int, int)>(MaterialPageRoute(
        builder: (_) => _VoyageDetailPage(voyage: voyage, from: from, to: to ?? voyage.stops.length - 1)));
    if (choice != null) await _reserve(voyage, choice.$1, choice.$2, choice.$3);
  }

  Widget _myReservations() {
    final reservations = _reservations;
    if (reservations == null) return const WakaLoading();
    if (reservations.isEmpty) return const Center(child: Text('Aucune réservation.'));
    return ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
      for (final r in reservations)
        Card(child: ListTile(
          title: Text('${r.seats} place(s) · ${r.totalXaf} F · ${_stateLabel(r.state)}'),
          subtitle: Text('Embarquement prévu ${_when(r.boardingAt)}'
              '${r.refundXaf == null || r.state == 'boarded' ? '' : ' · remboursé ${r.refundXaf} F (frais ${r.feePercent} %)'}'
              '${r.boardingCode == null ? '' : '\nCode d’embarquement : ${r.boardingCode} – à donner au chauffeur au départ, à personne d’autre.'}'),
          trailing: Wrap(spacing: 4, children: [
            if (r.state == 'awaiting_payment' && widget.login.gateway is PaymentGateway)
              FilledButton(onPressed: _busy ? null : () => _pay(r.reservationId, r.totalXaf), child: const Text('Payer')),
            TextButton(onPressed: _busy ? null : () => _report(r.reservationId), child: const Text('Signaler')),
            if ({'awaiting_payment', 'confirmed'}.contains(r.state))
              TextButton(onPressed: _busy ? null : () => _confirmCancel(r), child: const Text('Annuler')),
          ]),
        )),
    ]);
  }

  Future<void> _report(String reservationId) async {
    final result = await showDialog<(String, String)>(context: context, builder: (_) => const _IncidentDialog());
    if (result == null) return;
    await _run(() async {
      final (session, client) = _context();
      final request = 'incident:$reservationId:${result.$1}:${result.$2}';
      await _gateway.reportIncident(session, client, _key(request), reservationId, result.$1, result.$2);
      _keys.remove(request);
      _message = 'Signalement envoyé à l’assistance WAKA.';
      await _load();
    });
  }

  Future<void> _answer(IncidentView incident, String kind) async {
    final text = await showDialog<String>(context: context, builder: (_) => _TextDialog(
        title: kind == 'contest' ? 'Contester la décision' : 'Répondre'));
    if (text == null) return;
    await _run(() async {
      final (session, client) = _context();
      final request = 'message:${incident.incidentId}:$kind:$text';
      await _gateway.incidentMessage(session, client, _key(request), incident.incidentId, kind, text);
      _keys.remove(request);
      _message = kind == 'contest' ? 'Contestation envoyée : l’examen est rouvert.' : 'Réponse envoyée.';
      await _load();
    });
  }

  Widget _myIncidents() {
    final incidents = _incidents;
    if (incidents == null) return const WakaLoading();
    if (incidents.isEmpty) return const Center(child: Text('Aucun incident.'));
    String who(String role) => switch (role) { 'client' => 'Client', 'driver' => 'Chauffeur', _ => 'Assistance WAKA' };
    return ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
      for (final i in incidents)
        Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Text('${incidentCategories[i.category]} · ${switch (i.state) { 'open' => 'en examen', 'decided' => 'décidé', _ => 'contesté' }}',
              style: Theme.of(context).textTheme.titleSmall),
          for (final m in i.messages)
            Padding(padding: const EdgeInsets.only(top: 6), child: Text(m.kind == 'decision'
                ? '${who(m.authorRole)} – décision : ${incidentDecisions[m.decision]}${m.text == null ? '' : ' · ${m.text}'}'
                : '${who(m.authorRole)} : ${m.text ?? ''}')),
          Wrap(spacing: 4, children: [
            TextButton(onPressed: _busy ? null : () => _answer(i, 'reply'), child: const Text('Répondre')),
            if (i.state == 'decided')
              TextButton(onPressed: _busy ? null : () => _answer(i, 'contest'), child: const Text('Contester')),
          ]),
        ]))),
    ]);
  }

  Future<void> _confirmCancel(ReservationView r) async {
    final ok = await showDialog<bool>(context: context, builder: (context) => AlertDialog(
      title: const Text('Annuler cette réservation ?'),
      content: const Text('Gratuit jusqu’à 12 h avant le départ. Ensuite, 15 % du prix sont retenus, et 30 % à moins de 3 h.'),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Garder')),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Annuler la réservation')),
      ],
    ));
    if (ok == true) await _cancelReservation(r);
  }

  Widget _myVoyages() {
    final mine = _mine;
    return ListView(padding: const EdgeInsets.fromLTRB(16, 12, 16, 24), children: [
      FilledButton.icon(
        onPressed: _busy ? null : () => Navigator.of(context).push<void>(MaterialPageRoute<void>(
            builder: (_) => PublishVoyagePage(pickDateTime: widget.pickDateTime, onPublish: _publish))),
        icon: const Icon(Icons.add),
        label: const Text('Publier un voyage'),
      ),
      const SizedBox(height: 8),
      Text('Aucun abonnement n’est demandé pour les voyages. WAKA garde 10 % du prix ; tu es payé à l’embarquement.',
          style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.onSurfaceVariant)),
      const SizedBox(height: 12),
      if (mine != null && mine.isEmpty) const Text('Tu n’as publié aucun voyage.'),
      for (final v in mine ?? const <VoyageView>[])
        Card(child: ListTile(
          title: Text(_route(v)),
          subtitle: Text('Départ ${_when(v.stops.first.plannedAt)} · ${v.state == 'published' ? 'publié' : 'annulé'} · '
              '${v.availableSeats['0-${v.stops.length - 1}']} place(s) libre(s) sur ${v.seats}'),
          trailing: v.state == 'published'
              ? Wrap(spacing: 4, children: [
                  TextButton(onPressed: _busy ? null : () => Navigator.of(context).push<void>(MaterialPageRoute<void>(
                      builder: (_) => PassengersPage(login: widget.login, voyage: v))), child: const Text('Passagers')),
                  TextButton(onPressed: _busy ? null : () => _cancelVoyage(v), child: const Text('Annuler')),
                ])
              : null,
        )),
    ]);
  }

  static String _stateLabel(String state) => switch (state) {
    'awaiting_payment' => 'en attente de paiement',
    'expired' => 'expirée (non payée)',
    'confirmed' => 'confirmée',
    'boarded' => 'embarqué',
    'cancelled_by_client' => 'annulée par toi',
    'cancelled_by_driver' => 'annulée par le chauffeur',
    'no_show' => 'absent au départ',
    _ => state,
  };
}

/// One trip: its stops and driver, where to get on and off, how many seats, then one button.
class _VoyageDetailPage extends StatefulWidget {
  const _VoyageDetailPage({required this.voyage, required this.from, required this.to});
  final VoyageView voyage;
  final int from, to;
  @override
  State<_VoyageDetailPage> createState() => _VoyageDetailPageState();
}

class _VoyageDetailPageState extends State<_VoyageDetailPage> {
  late int _from = widget.from, _to = widget.to;
  int _seats = 1;

  @override
  Widget build(BuildContext context) {
    final v = widget.voyage, pair = '$_from-$_to', theme = Theme.of(context);
    final free = v.availableSeats[pair] ?? 0, price = v.prices[pair];
    if (_seats > free && free > 0) _seats = free;
    return Scaffold(
      appBar: AppBar(title: Text('${v.stops[_from].place} – ${v.stops[_to].place}')),
      body: SafeArea(child: ListView(padding: const EdgeInsets.fromLTRB(20, 4, 20, 24), children: [
        Text('${_dayLabel(v.stops[_from].plannedAt.toLocal())} · départ ${_hour(v.stops[_from].plannedAt)} · arrivée vers ${_hour(v.stops[_to].plannedAt)}',
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        const SizedBox(height: 12),
        Card(child: Padding(padding: const EdgeInsets.all(14), child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          if (v.driverFirstName != null || v.vehicle != null)
            Text([?v.driverFirstName, ?v.vehicle].join(' · '), style: theme.textTheme.titleMedium),
          Text('Arrêts : ${_route(v)}', style: theme.textTheme.bodyMedium),
        ]))),
        const SizedBox(height: 12),
        DropdownButtonFormField<int>(
          initialValue: _from, isExpanded: true, decoration: const InputDecoration(labelText: 'Je monte à'),
          items: [for (var i = 0; i < v.stops.length - 1; i++) DropdownMenuItem(value: i, child: Text(v.stops[i].place))],
          onChanged: (value) => setState(() { _from = value!; if (_to <= _from) _to = _from + 1; }),
        ),
        const SizedBox(height: 10),
        DropdownButtonFormField<int>(
          key: ValueKey(_from),
          initialValue: _to, isExpanded: true, decoration: const InputDecoration(labelText: 'Je descends à'),
          items: [for (var i = _from + 1; i < v.stops.length; i++) DropdownMenuItem(value: i, child: Text(v.stops[i].place))],
          onChanged: (value) => setState(() => _to = value!),
        ),
        const SizedBox(height: 10),
        Row(children: [
          Expanded(child: Text('Places', style: theme.textTheme.bodyLarge)),
          IconButton(tooltip: 'Moins', onPressed: _seats <= 1 ? null : () => setState(() => _seats--), icon: const Icon(Icons.remove_circle_outline)),
          Text('$_seats', style: theme.textTheme.titleMedium),
          IconButton(tooltip: 'Plus', onPressed: _seats >= free ? null : () => setState(() => _seats++), icon: const Icon(Icons.add_circle_outline)),
        ]),
        Text(free == 0 ? 'Plus de place sur ce trajet.' : 'Total : ${price! * _seats} F ($price F la place).'),
        const SizedBox(height: 4),
        Text('Annulation gratuite jusqu’à 12 h avant le départ.', style: theme.textTheme.bodySmall),
        const SizedBox(height: 20),
        FilledButton(onPressed: free == 0 ? null : () => Navigator.pop(context, (_from, _to, _seats)),
            child: Text(free == 0 ? 'Complet' : 'Réserver · ${price! * _seats} F')),
      ])),
    );
  }
}

/// The driver's listing: stops (departure, stopovers, arrival), seats and a price for every pair of stops.
class PublishVoyagePage extends StatefulWidget {
  const PublishVoyagePage({super.key, required this.pickDateTime, required this.onPublish});
  final PickDateTime pickDateTime;
  /// True once the server accepted the listing; on failure the form stays filled in.
  final Future<bool> Function(List<VoyageStop> stops, int seats, Map<String, int> prices) onPublish;
  @override
  State<PublishVoyagePage> createState() => _PublishVoyagePageState();
}

class _PublishVoyagePageState extends State<PublishVoyagePage> {
  final List<TextEditingController> _places = [TextEditingController(), TextEditingController()];
  final List<DateTime?> _times = [null, null];
  final Map<String, TextEditingController> _prices = {};
  int _seats = 1;

  @override
  void dispose() {
    for (final c in [..._places, ..._prices.values]) {
      c.dispose();
    }
    super.dispose();
  }

  List<String> get _pairs => [
    for (var from = 0; from < _places.length; from++)
      for (var to = from + 1; to < _places.length; to++) '$from-$to',
  ];

  String? get _problem {
    for (var i = 0; i < _places.length; i++) {
      final place = _places[i].text.trim();
      if (place.length < 2 || place.length > 60) return 'Donne un nom à chaque arrêt (2 à 60 caractères).';
      if (_times[i] == null) return 'Choisis la date et l’heure de chaque arrêt.';
      if (i > 0 && !_times[i]!.isAfter(_times[i - 1]!)) return 'Chaque arrêt doit venir après le précédent.';
    }
    for (final pair in _pairs) {
      final price = int.tryParse(_prices[pair]?.text.trim() ?? '');
      if (price == null || price < 1 || price > maxSeatPriceXaf) return 'Chaque prix va de 1 à 10 000 F la place.';
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    for (final pair in _pairs) {
      _prices.putIfAbsent(pair, TextEditingController.new);
    }
    String name(int i) => _places[i].text.trim().isEmpty ? 'arrêt ${i + 1}' : _places[i].text.trim();
    return Scaffold(
      appBar: AppBar(title: const Text('Publier un voyage')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        for (var i = 0; i < _places.length; i++)
          Card(child: Padding(padding: const EdgeInsets.all(12), child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
            TextField(controller: _places[i], onChanged: (_) => setState(() {}), decoration: InputDecoration(
                labelText: i == 0 ? 'Lieu de départ' : i == _places.length - 1 ? 'Lieu d’arrivée' : 'Escale $i')),
            Row(children: [
              Expanded(child: Text(_times[i] == null ? 'Date et heure à choisir' : _when(_times[i]!))),
              TextButton(onPressed: () async {
                final picked = await widget.pickDateTime(context);
                if (picked != null) setState(() => _times[i] = picked);
              }, child: Text('Choisir – ${i == 0 ? 'départ' : 'arrêt ${i + 1}'}')),
            ]),
          ]))),
        if (_places.length < 6)
          TextButton.icon(onPressed: () => setState(() {
            _places.insert(_places.length - 1, TextEditingController());
            _times.insert(_times.length - 1, null);
            _prices.clear();
          }), icon: const Icon(Icons.add_location_alt_outlined), label: const Text('Ajouter une escale')),
        DropdownButtonFormField<int>(
          initialValue: _seats, decoration: const InputDecoration(labelText: 'Places proposées'),
          items: [for (var n = 1; n <= 5; n++) DropdownMenuItem(value: n, child: Text('$n'))],
          onChanged: (value) => setState(() => _seats = value!),
        ),
        const SizedBox(height: 12),
        Text('Prix par place (10 000 F au plus)', style: Theme.of(context).textTheme.titleMedium),
        for (final pair in _pairs)
          TextField(controller: _prices[pair], keyboardType: TextInputType.number, onChanged: (_) => setState(() {}),
              decoration: InputDecoration(labelText: '${name(int.parse(pair[0]))} – ${name(int.parse(pair[2]))} (F)')),
        const SizedBox(height: 12),
        if (_problem != null) Text(_problem!),
        FilledButton(
          onPressed: _problem != null ? null : () async {
            final navigator = Navigator.of(context);
            final published = await widget.onPublish(
              [for (var i = 0; i < _places.length; i++) VoyageStop(_places[i].text.trim(), _times[i]!)],
              _seats,
              {for (final pair in _pairs) pair: int.parse(_prices[pair]!.text.trim())},
            );
            if (published) navigator.pop();
          },
          child: const Text('Publier'),
        ),
      ]),
    );
  }
}

/// The driver's passengers (K27): sections and seats only; boarding with the code the passenger gives.
class PassengersPage extends StatefulWidget {
  const PassengersPage({super.key, required this.login, required this.voyage});
  final LoginFlow login;
  final VoyageView voyage;
  @override
  State<PassengersPage> createState() => _PassengersPageState();
}

class _PassengersPageState extends State<PassengersPage> {
  VoyageGateway get _gateway => widget.login.gateway as VoyageGateway;
  final _code = TextEditingController();
  List<PassengerView>? _passengers;
  String? _message;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _run(_load);
  }

  @override
  void dispose() {
    _code.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    final list = await _gateway.voyagePassengers(session, client, widget.voyage.voyageId);
    if (mounted) setState(() => _passengers = list);
  }

  Future<void> _run(Future<void> Function() action) async {
    if (_busy) return;
    setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = voyageMessage(problem.failure));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _board() => _run(() async {
    final session = widget.login.session, client = widget.login.clientInstance;
    final code = _code.text.trim();
    if (session == null || client == null || !RegExp(r'^[0-9]{6}$').hasMatch(code)) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    final passenger = await _gateway.boardPassenger(session, client, widget.voyage.voyageId, code);
    _code.clear();
    _message = 'Passager embarqué : ${passenger.seats} place(s). Tu recevras ${passenger.driverXaf} F.';
    await _load();
  });

  Future<void> _noShow(PassengerView p) => _run(() async {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    final result = await _gateway.markNoShow(session, client, widget.voyage.voyageId, p.reservationId);
    _message = 'Passager déclaré absent : 30 % retenus, ${result.driverXaf} F pour toi, le reste lui est remboursé.';
    await _load();
  });

  String _section(PassengerView p) => '${widget.voyage.stops[p.fromIndex].place} – ${widget.voyage.stops[p.toIndex].place}';

  @override
  Widget build(BuildContext context) {
    final passengers = _passengers;
    return Scaffold(
      appBar: AppBar(title: const Text('Passagers')),
      body: ListView(padding: const EdgeInsets.all(16), children: [
        Text(_route(widget.voyage), style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        const Text('Au départ, demande à chaque passager son code à 6 chiffres. Seul un passager qui a payé peut embarquer.'),
        const SizedBox(height: 8),
        TextField(controller: _code, keyboardType: TextInputType.number, maxLength: 6,
            decoration: const InputDecoration(labelText: 'Code du passager', border: OutlineInputBorder())),
        FilledButton(onPressed: _busy ? null : _board, child: const Text('Embarquer')),
        if (_message != null) Padding(padding: const EdgeInsets.only(top: 12),
            child: Semantics(liveRegion: true, child: Text(_message!))),
        const Divider(height: 32),
        if (passengers == null) const WakaLoading(),
        if (passengers != null && passengers.isEmpty) const Text('Aucun passager pour l’instant.'),
        for (final p in passengers ?? const <PassengerView>[])
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: Text('${_section(p)} · ${p.seats} place(s)'),
            subtitle: Text(switch (p.state) {
              'awaiting_payment' => 'En attente de paiement',
              'confirmed' => 'Payé, à embarquer',
              'no_show' => 'Absent · ${p.driverXaf} F pour toi',
              _ => 'Embarqué · ${p.driverXaf} F pour toi',
            }),
            trailing: p.state == 'confirmed'
                ? TextButton(onPressed: _busy ? null : () => _noShow(p), child: const Text('Absent'))
                : null,
          ),
      ]),
    );
  }
}
class _IncidentDialog extends StatefulWidget {
  const _IncidentDialog();
  @override
  State<_IncidentDialog> createState() => _IncidentDialogState();
}

class _IncidentDialogState extends State<_IncidentDialog> {
  String _category = 'other';
  final _text = TextEditingController();
  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: const Text('Signaler un problème'),
    content: Column(mainAxisSize: MainAxisSize.min, children: [
      DropdownButtonFormField<String>(
        initialValue: _category, isExpanded: true, decoration: const InputDecoration(labelText: 'Type de problème'),
        items: [for (final e in incidentCategories.entries) DropdownMenuItem(value: e.key, child: Text(e.value))],
        onChanged: (value) => setState(() => _category = value!),
      ),
      TextField(controller: _text, maxLength: 1000, maxLines: 4, onChanged: (_) => setState(() {}),
          decoration: const InputDecoration(labelText: 'Ce qui s’est passé')),
    ]),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Fermer')),
      FilledButton(onPressed: _text.text.trim().isEmpty ? null : () => Navigator.pop(context, (_category, _text.text.trim())),
          child: const Text('Envoyer')),
    ],
  );
}

class _TextDialog extends StatefulWidget {
  const _TextDialog({required this.title});
  final String title;
  @override
  State<_TextDialog> createState() => _TextDialogState();
}

class _TextDialogState extends State<_TextDialog> {
  final _text = TextEditingController();
  @override
  void dispose() {
    _text.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => AlertDialog(
    title: Text(widget.title),
    content: TextField(controller: _text, maxLength: 1000, maxLines: 4, onChanged: (_) => setState(() {})),
    actions: [
      TextButton(onPressed: () => Navigator.pop(context), child: const Text('Fermer')),
      FilledButton(onPressed: _text.text.trim().isEmpty ? null : () => Navigator.pop(context, _text.text.trim()), child: const Text('Envoyer')),
    ],
  );
}