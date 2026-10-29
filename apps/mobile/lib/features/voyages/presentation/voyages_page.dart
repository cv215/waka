import 'package:flutter/material.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../identity/data/voyage_models.dart';

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
String _route(VoyageView v) => v.stops.map((s) => s.place).join(' → ');

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

/// « Voyage » : intercity trips. Payment is not available yet: a reservation waits for it three minutes.
class VoyagesPage extends StatefulWidget {
  const VoyagesPage({super.key, required this.login, this.pickDateTime = pickWithDialogs});
  final LoginFlow login;
  final PickDateTime pickDateTime;
  @override
  State<VoyagesPage> createState() => _VoyagesPageState();
}

class _VoyagesPageState extends State<VoyagesPage> {
  VoyageGateway get _gateway => widget.login.gateway as VoyageGateway;
  List<VoyageView>? _upcoming, _mine;
  List<ReservationView>? _reservations;
  String? _message;
  bool _busy = false;
  /// Same request after a lost answer: same key, so the server never records it twice.
  final Map<String, String> _keys = {};

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
    final mine = await _gateway.myVoyages(session, client);
    if (mounted) setState(() { _upcoming = upcoming; _reservations = reservations; _mine = mine; });
  }

  String _key(String request) => _keys.putIfAbsent(request, randomLoginId);

  Future<void> _reserve(VoyageView voyage, int from, int to, int seats) => _run(() async {
    final (session, client) = _context();
    final request = 'reserve:${voyage.voyageId}:$from:$to:$seats';
    final reservation = await _gateway.reserveVoyage(session, client, _key(request), voyage.voyageId, from, to, seats);
    _keys.remove(request);
    _message = 'Réservation enregistrée : ${reservation.totalXaf} F. Le paiement Mobile Money n’est pas encore ouvert ; '
        'tes places sont gardées 3 minutes.';
    await _load();
  });

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
    length: 3,
    child: Scaffold(
      appBar: AppBar(
        title: const Text('Voyage'),
        actions: [IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : _refresh, icon: const Icon(Icons.refresh))],
        bottom: const TabBar(tabs: [Tab(text: 'Trouver'), Tab(text: 'Mes réservations'), Tab(text: 'Mes voyages')]),
      ),
      body: Column(children: [
        if (_message != null)
          Padding(padding: const EdgeInsets.all(12), child: Semantics(liveRegion: true, child: Text(_message!))),
        Expanded(child: TabBarView(children: [_find(), _myReservations(), _myVoyages()])),
      ]),
    ),
  );

  Widget _find() {
    final voyages = _upcoming;
    if (voyages == null) return const Center(child: Text('Chargement…'));
    if (voyages.isEmpty) return const Center(child: Text('Aucun voyage à venir pour l’instant.'));
    return ListView(padding: const EdgeInsets.all(12), children: [
      for (final v in voyages)
        Card(child: ListTile(
          title: Text(_route(v)),
          subtitle: Text('Départ ${_when(v.stops.first.plannedAt)} · ${v.category == 'taxi' ? 'taxi' : 'voiture personnelle'} · '
              'trajet complet ${v.prices['0-${v.stops.length - 1}']} F la place'),
          trailing: TextButton(onPressed: _busy ? null : () => _openReservation(v), child: const Text('Réserver')),
        )),
    ]);
  }

  Future<void> _openReservation(VoyageView voyage) async {
    final choice = await showDialog<(int, int, int)>(context: context, builder: (_) => _ReservationDialog(voyage: voyage));
    if (choice != null) await _reserve(voyage, choice.$1, choice.$2, choice.$3);
  }

  Widget _myReservations() {
    final reservations = _reservations;
    if (reservations == null) return const Center(child: Text('Chargement…'));
    if (reservations.isEmpty) return const Center(child: Text('Aucune réservation.'));
    return ListView(padding: const EdgeInsets.all(12), children: [
      for (final r in reservations)
        Card(child: ListTile(
          title: Text('${r.seats} place(s) · ${r.totalXaf} F · ${_stateLabel(r.state)}'),
          subtitle: Text('Embarquement prévu ${_when(r.boardingAt)}'
              '${r.refundXaf == null || r.state == 'boarded' ? '' : ' · remboursé ${r.refundXaf} F (frais ${r.feePercent} %)'}'
              '${r.boardingCode == null ? '' : '\nCode d’embarquement : ${r.boardingCode} — à donner au chauffeur au départ, à personne d’autre.'}'),
          trailing: {'awaiting_payment', 'confirmed'}.contains(r.state)
              ? TextButton(onPressed: _busy ? null : () => _confirmCancel(r), child: const Text('Annuler'))
              : null,
        )),
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
    return ListView(padding: const EdgeInsets.all(12), children: [
      FilledButton.icon(
        onPressed: _busy ? null : () => Navigator.of(context).push<void>(MaterialPageRoute<void>(
            builder: (_) => PublishVoyagePage(pickDateTime: widget.pickDateTime, onPublish: _publish))),
        icon: const Icon(Icons.add),
        label: const Text('Publier un voyage'),
      ),
      const SizedBox(height: 4),
      const Text('Aucun abonnement n’est demandé pour les voyages. WAKA garde 10 % du prix ; tu es payé à l’embarquement.'),
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

class _ReservationDialog extends StatefulWidget {
  const _ReservationDialog({required this.voyage});
  final VoyageView voyage;
  @override
  State<_ReservationDialog> createState() => _ReservationDialogState();
}

class _ReservationDialogState extends State<_ReservationDialog> {
  int _from = 0, _seats = 1;
  late int _to = widget.voyage.stops.length - 1;

  @override
  Widget build(BuildContext context) {
    final v = widget.voyage, pair = '$_from-$_to';
    final free = v.availableSeats[pair] ?? 0, price = v.prices[pair];
    if (_seats > free && free > 0) _seats = free;
    return AlertDialog(
      title: const Text('Réserver'),
      content: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        DropdownButtonFormField<int>(
          initialValue: _from, isExpanded: true, decoration: const InputDecoration(labelText: 'Je monte à'),
          items: [for (var i = 0; i < v.stops.length - 1; i++) DropdownMenuItem(value: i, child: Text(v.stops[i].place))],
          onChanged: (value) => setState(() { _from = value!; if (_to <= _from) _to = _from + 1; }),
        ),
        DropdownButtonFormField<int>(
          initialValue: _to, isExpanded: true, decoration: const InputDecoration(labelText: 'Je descends à'),
          items: [for (var i = _from + 1; i < v.stops.length; i++) DropdownMenuItem(value: i, child: Text(v.stops[i].place))],
          onChanged: (value) => setState(() => _to = value!),
        ),
        DropdownButtonFormField<int>(
          initialValue: free == 0 ? null : _seats, decoration: const InputDecoration(labelText: 'Places'),
          items: [for (var n = 1; n <= free; n++) DropdownMenuItem(value: n, child: Text('$n'))],
          onChanged: (value) => setState(() => _seats = value!),
        ),
        const SizedBox(height: 8),
        Text(free == 0 ? 'Plus de place sur ce trajet.' : 'Total : ${price! * _seats} F ($price F la place).'),
      ]),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: const Text('Fermer')),
        FilledButton(onPressed: free == 0 ? null : () => Navigator.pop(context, (_from, _to, _seats)), child: const Text('Réserver')),
      ],
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
              }, child: Text('Choisir — ${i == 0 ? 'départ' : 'arrêt ${i + 1}'}')),
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
              decoration: InputDecoration(labelText: '${name(int.parse(pair[0]))} → ${name(int.parse(pair[2]))} (F)')),
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

  String _section(PassengerView p) => '${widget.voyage.stops[p.fromIndex].place} → ${widget.voyage.stops[p.toIndex].place}';

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
        if (passengers == null) const Text('Chargement…'),
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