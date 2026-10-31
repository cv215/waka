import 'dart:async';

import 'package:flutter/material.dart';
import 'package:pointer_interceptor/pointer_interceptor.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/ride_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import 'city_ride_page.dart';

String driverRideMessage(PhotoFailure failure) => switch (failure) {
  PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
  PhotoFailure.driverNotAllowed => 'Seul un chauffeur approuvé, avec un accès actif, peut recevoir des courses.',
  PhotoFailure.expired => 'Cette proposition a expiré.',
  PhotoFailure.notEditable => 'Ta voiture n’a plus de place pour ce client, ou cette étape n’est pas possible maintenant.',
  PhotoFailure.notPaid => 'Le client n’a pas encore payé : attends la confirmation du paiement.',
  PhotoFailure.wrongCode => 'Ce code ne correspond pas : demande au client le code affiché sur son téléphone.',
  PhotoFailure.tooEarly => 'Le client peut être déclaré absent seulement après 5 minutes d’attente.',
  PhotoFailure.notFound => 'Cette course n’existe plus.',
  PhotoFailure.unavailable => 'Le service est momentanément indisponible. Réessaie plus tard.',
  PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie.',
  _ => 'Cette action n’est pas possible maintenant.',
};

String driverStateLabel(String state) => switch (state) {
  'awaiting_payment' => 'Course acceptée : le client a 3 minutes pour payer.',
  'confirmed' => 'Course payée : rends-toi au point de départ.',
  'driver_arrived' => 'Tu es au départ : demande le code au client pour le faire monter.',
  'boarded' => 'Client à bord : conduis-le à l’arrivée.',
  'finished' => 'Course terminée : ta part est dans tes gains.',
  'cancelled_by_client' => 'Le client a annulé la course.',
  'cancelled_by_driver' => 'Tu as annulé la course : le client est remboursé.',
  'client_absent' => 'Client absent : la course est close et le client remboursé.',
  'expired' => 'Le client n’a pas payé à temps : la course est annulée.',
  _ => state,
};

enum _Spot { here, there }

/// Opens a navigation link in the phone's maps app (Google Maps or Waze); tests replace it.
typedef OpenLink = Future<bool> Function(Uri link);

Future<bool> openExternally(Uri link) => launchUrl(link, mode: LaunchMode.externalApplication);

/// Turn-by-turn guidance by the maps app the driver already has, free: the destination is filled in.
Uri googleMapsNavigation(RidePoint to) => Uri.https('www.google.com', '/maps/dir/', {
  'api': '1', 'destination': '${to.latitude},${to.longitude}', 'travelmode': 'driving', 'dir_action': 'navigate'});
/// The whole tour in Google Maps: every stop in order (waypoints), the last one as the destination.
Uri googleMapsTour(List<RidePoint> stops) => Uri.https('www.google.com', '/maps/dir/', {
  'api': '1', 'destination': '${stops.last.latitude},${stops.last.longitude}',
  if (stops.length > 1) 'waypoints': [for (final p in stops.take(stops.length - 1)) '${p.latitude},${p.longitude}'].join('|'),
  'travelmode': 'driving', 'dir_action': 'navigate'});
Uri wazeNavigation(RidePoint to) => Uri.https('waze.com', '/ul', {'ll': '${to.latitude},${to.longitude}', 'navigate': 'yes'});

/// « Mode chauffeur » (R13, R15): be online with a fresh position; with a direction, take several shared clients on the
/// way; answer proposals within 30 seconds, then take each ride through its steps. The server alone decides who
/// receives a proposal, the price, the driver's share and the order of the stops. « Démarrer » keeps the map on the
/// car and sends the position more often; the turn-by-turn guidance stays in Google Maps or Waze (free).
class DriverRidePage extends StatefulWidget {
  const DriverRidePage({super.key, required this.login, this.mapBuilder = googleRideMap, this.readPosition = devicePosition,
      this.openLink = openExternally, this.pollEvery = const Duration(seconds: 4), this.presenceEvery = const Duration(seconds: 20),
      this.followEvery = const Duration(seconds: 5), this.title = 'Mode chauffeur'});
  /// Empty when the page sits under the Client | Chauffeur switch.
  final String title;
  final LoginFlow login;
  final RideMapBuilder mapBuilder;
  final ReadPosition readPosition;
  final OpenLink openLink;
  /// Null in tests: the driver refreshes by hand.
  final Duration? pollEvery, presenceEvery, followEvery;
  @override
  State<DriverRidePage> createState() => _DriverRidePageState();
}

class _DriverRidePageState extends State<DriverRidePage> {
  DriverRideGateway get _gateway => widget.login.gateway as DriverRideGateway;
  final _where = TextEditingController(), _whereTo = TextEditingController();
  final Map<String, TextEditingController> _codes = {};
  bool _online = false, _busy = false, _gpsMissing = false;
  /// Where the driver is and, optionally, where he goes (R15), by name (K35), GPS or the map.
  _Spot _spot = _Spot.here;
  List<PlaceSuggestion> _places = const [];
  String _placeSession = randomLoginId();
  Timer? _typing;
  RidePoint? _position, _destination;
  /// The driver's own route to his direction, as the server drew it (green).
  List<RidePoint>? _myRoute;
  /// His tour through the clients he carries, then his direction (yellow), and its stops in order.
  List<RidePoint>? _plan;
  List<RideStop> _stops = const [];
  /// « Démarrer »: the map follows the car.
  bool _following = false;
  DateTime? _positionAt;
  List<DriverProposalView> _proposals = const [];
  /// Rides carried now, plus the ones that just ended until the driver closes them.
  List<CityRideView> _rides = const [];
  String? _message;
  Timer? _poll, _presence;

  @override
  void initState() {
    super.initState();
    // The position is looked for once: « Où es-tu ? » only shows when the phone cannot tell.
    widget.readPosition().then((gps) {
      if (!mounted || _online) return;
      setState(() { if (gps == null) { _gpsMissing = true; } else { _position ??= gps; } });
    });
    // Rides still in progress (the screen or the phone was closed) are found again.
    _run(() async {
      final (session, client) = _context();
      final rides = await _gateway.driverRides(session, client);
      if (mounted && rides.isNotEmpty) {
        setState(() => _rides = rides);
        _startTimers();
      }
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _presence?.cancel();
    _typing?.cancel();
    _where.dispose();
    _whereTo.dispose();
    for (final c in _codes.values) {
      c.dispose();
    }
    if (_online && !_carrying) {
      // Leaving the screen ends the availability: nobody is proposed a driver who is not looking.
      final session = widget.login.session, client = widget.login.clientInstance;
      if (session != null && client != null) {
        unawaited(_gateway.setPresence(session, client, false, null).catchError((_) => const DriverPresenceView(false, null)));
      }
    }
    super.dispose();
  }

  bool get _carrying => _rides.any((r) => r.active);
  /// R15: a driver with a direction keeps receiving shared clients while he carries others; without one, one ride at a time.
  bool get _canReceive => _online && (_destination != null || !_carrying);

  (LoginSession, String) _context() {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (!widget.login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  Future<void> _run(Future<void> Function() action, {bool quiet = false}) async {
    if (_busy && !quiet) return;
    if (!quiet) setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = driverRideMessage(problem.failure));
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted && !quiet) setState(() => _busy = false);
    }
  }

  /// Sends the presence with the freshest position (GPS, else the place typed or touched) and the direction, if any.
  Future<void> _sendPresence(bool online) async {
    final (session, client) = _context();
    RidePoint? where;
    if (online) {
      final gps = await widget.readPosition();
      _gpsMissing = gps == null;
      where = gps ?? _position;
      if (where == null) {
        if (mounted) setState(() => _message = 'Ta position est introuvable : écris où tu es, ou touche la carte.');
        return;
      }
    }
    final presence = await _gateway.setPresence(session, client, online, where, destination: online ? _destination : null);
    if (!mounted) return;
    setState(() {
      _online = presence.online;
      _position = online ? where : _position;
      _positionAt = presence.positionAt;
      // Keep the last route drawn if this refresh brought none (the server redraws it at most every five minutes).
      _myRoute = !online || _destination == null ? null : (presence.route ?? _myRoute);
      // Same for the tour: kept while it still has stops, dropped with the last one.
      _stops = presence.stops;
      _plan = _stops.isEmpty ? null : (presence.plan ?? _plan);
      if (!online) {
        _proposals = const [];
        _following = false;
      }
    });
  }

  void _typedPlace(_Spot spot, String text) {
    _spot = spot;
    _typing?.cancel();
    if (text.trim().length < 3) {
      setState(() => _places = const []);
      return;
    }
    _typing = Timer(const Duration(milliseconds: 400), () => _run(() async {
      final (session, client) = _context();
      final list = await (widget.login.gateway as RideGateway).placeSuggestions(session, client, text.trim(), _placeSession);
      if (mounted && spot == _spot) setState(() => _places = list);
    }, quiet: true));
  }

  Future<void> _choosePlace(PlaceSuggestion place) => _run(() async {
    final (session, client) = _context();
    final point = await (widget.login.gateway as RideGateway).placeLocation(session, client, place.placeId, _placeSession);
    _placeSession = randomLoginId();
    if (!mounted) return;
    setState(() {
      if (_spot == _Spot.here) {
        _position = point;
        _where.text = place.mainText;
      } else {
        _destination = point;
        _whereTo.text = place.mainText;
      }
      _places = const [];
    });
  });

  Future<void> _goOnline() => _run(() async {
    await _sendPresence(true);
    if (_online) _startTimers();
  });

  Future<void> _goOffline() => _run(() async {
    if (!_carrying) _stopTimers();
    await _sendPresence(false);
  });

  void _startTimers() {
    _stopTimers();
    final every = widget.pollEvery, presence = _following ? widget.followEvery : widget.presenceEvery;
    if (every != null) _poll = Timer.periodic(every, (_) { if (mounted) _run(_refreshNow, quiet: true); });
    // The server forgets a position older than 60 seconds.
    if (presence != null) {
      _presence = Timer.periodic(presence, (_) { if (mounted && _online) _run(() => _sendPresence(true), quiet: true); });
    }
  }

  void _stopTimers() {
    _poll?.cancel();
    _presence?.cancel();
  }

  Future<void> _refreshNow() async {
    final (session, client) = _context();
    if (_rides.isNotEmpty) {
      final carried = await _gateway.driverRides(session, client);
      // Keep the ones that just ended (with their outcome) until the driver closes them.
      final ended = <CityRideView>[];
      for (final r in _rides) {
        if (carried.any((c) => c.reservationId == r.reservationId)) continue;
        ended.add(r.active ? await _gateway.cityRide(session, client, r.reservationId) : r);
      }
      if (mounted) setState(() => _rides = [...carried, ...ended]);
      // A ride ended (a client cancelled, did not pay…): its stops leave the yellow tour at once.
      if (ended.any((r) => !r.active) && _stops.any((s) => ended.any((r) => r.reservationId == s.reservationId))) await _refreshTour();
    }
    if (_canReceive) {
      final list = await _gateway.driverProposals(session, client);
      if (mounted) setState(() => _proposals = list);
    }
  }

  /// Green: his own route; yellow: his tour with the clients he accepted; blue: each client waiting for his answer
  /// (it becomes part of the yellow tour once accepted). Widest underneath so all stay readable.
  List<RidePath> _drawn() => [
    if (_myRoute != null) RidePath.normal(_myRoute!),
    if (_plan != null) RidePath.combined(_plan!),
    if (_canReceive)
      for (final p in _proposals)
        if (p.route != null) RidePath.client(p.route!),
  ];

  /// The rest of the tour, in order: the stops, then his direction.
  List<RidePoint> get _tour => [for (final s in _stops) s.point, ?_destination];

  void _toggleFollow() {
    setState(() => _following = !_following);
    _startTimers();
    if (_following) _run(() => _sendPresence(true), quiet: true);
  }

  /// After an answer or a step, the tour changes: ask the server for the new one at once.
  Future<void> _refreshTour() async {
    if (_online) await _sendPresence(true);
  }

  Future<void> _navigate(Uri link) async {
    final opened = await widget.openLink(link);
    if (!opened && mounted) setState(() => _message = 'Impossible d’ouvrir l’application de navigation sur ce téléphone.');
  }

  Widget _legend(List<RidePath> drawn) => Wrap(spacing: 12, runSpacing: 4, children: [
    for (final path in {for (final p in drawn) p.name: p}.values)
      _swatch(path.color, switch (path.name) {
        'normal' => 'Ton trajet normal',
        'client' => 'Client qui attend ta réponse',
        _ => 'Ton trajet avec tes clients',
      }),
  ]);

  Widget _swatch(Color color, String label) => Row(mainAxisSize: MainAxisSize.min, children: [
    Container(width: 18, height: 6, decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(3))),
    const SizedBox(width: 6),
    Text(label, style: Theme.of(context).textTheme.bodySmall),
  ]);

  Widget _navigation(String label, RidePoint to) => Row(children: [
    Expanded(child: OutlinedButton.icon(onPressed: () => _navigate(googleMapsNavigation(to)), icon: const Icon(Icons.navigation_outlined),
        label: Text('$label · Google Maps'))),
    const SizedBox(width: 8),
    OutlinedButton(onPressed: () => _navigate(wazeNavigation(to)), child: const Text('Waze')),
  ]);

  Future<void> _answer(DriverProposalView proposal, bool accept) => _run(() async {
    final (session, client) = _context();
    final ride = await _gateway.answerProposal(session, client, proposal.proposalId, accept);
    if (!mounted) return;
    setState(() {
      _proposals = _proposals.where((p) => p.proposalId != proposal.proposalId).toList();
      if (ride != null) _rides = [..._rides, ride];
    });
    if (ride != null) await _refreshTour();
  });

  TextEditingController _code(String rideId) => _codes.putIfAbsent(rideId, TextEditingController.new);

  Future<void> _act(CityRideView ride, String action) => _run(() async {
    final (session, client) = _context();
    final code = action == 'board' ? _code(ride.reservationId).text.trim() : null;
    if (action == 'board' && !RegExp(r'^[0-9]{6}$').hasMatch(code!)) {
      setState(() => _message = 'Le code du client a 6 chiffres.');
      return;
    }
    final fresh = await _gateway.driverRideAction(session, client, ride.reservationId, action, code);
    if (!mounted) return;
    setState(() {
      _rides = [for (final r in _rides) r.reservationId == fresh.reservationId ? fresh : r];
      if (action == 'board') _code(ride.reservationId).clear();
    });
    if (action != 'arrive') await _refreshTour();
  });

  void _close(CityRideView ride) => setState(() {
    _rides = _rides.where((r) => r.reservationId != ride.reservationId).toList();
    _codes.remove(ride.reservationId)?.dispose();
    _message = null;
  });

  String _minutes(int seconds) => seconds < 60 ? 'moins d’une minute' : '${(seconds / 60).round()} min';

  Widget _placeField(_Spot spot, TextEditingController controller, String label, IconData icon, RidePoint? chosen) =>
      TextField(
        controller: controller,
        enabled: !_busy && !_online,
        onTap: () => setState(() { _spot = spot; _places = const []; }),
        onChanged: (text) => _typedPlace(spot, text),
        decoration: InputDecoration(
          hintText: label,
          prefixIcon: Icon(icon),
          filled: true,
          fillColor: Theme.of(context).colorScheme.surfaceContainerHigh,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
          suffixIcon: chosen == null
              ? null
              : spot == _Spot.there
                  ? IconButton(tooltip: 'Sans direction', icon: const Icon(Icons.clear),
                      onPressed: _busy ? null : () => setState(() { _destination = null; _whereTo.clear(); }))
                  : const Icon(Icons.check_circle_outline),
        ),
      );

  List<Widget> _suggestions(_Spot spot) => [
    if (_spot == spot)
      for (final place in _places)
        ListTile(
          dense: true,
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.location_on_outlined),
          title: Text(place.mainText),
          subtitle: place.secondaryText.isEmpty ? null : Text(place.secondaryText),
          onTap: _busy ? null : () => _choosePlace(place),
        ),
  ];

  /// One ride: what to do next, one main button, the way there.
  Widget _rideCard(ThemeData theme, CityRideView ride, int index, int count) {
    final who = ride.clientFirstName ?? 'le client';
    final title = switch (ride.state) {
      'awaiting_payment' => 'Course acceptée : le client a 3 minutes pour payer.',
      'confirmed' => 'Aller chercher $who',
      'driver_arrived' => 'Tu es au départ : demande le code à $who',
      'boarded' => 'Déposer $who',
      _ => driverStateLabel(ride.state),
    };
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(borderRadius: BorderRadius.circular(16),
          border: Border.all(color: ride.active ? theme.colorScheme.primary : theme.colorScheme.outlineVariant, width: ride.active ? 2 : 1)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
        if (count > 1) Text('Course ${index + 1} sur $count', style: theme.textTheme.bodySmall),
        Text(title, style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700)),
        Text('${ride.mode == 'vip' ? 'VIP' : '${ride.seats} place(s)'} · ${ride.priceXaf} F · ta part ${ride.driverXaf} F',
            style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        const SizedBox(height: 10),
        if ({'awaiting_payment', 'confirmed'}.contains(ride.state)) _navigation('Aller au client', ride.pickup),
        if (ride.state == 'boarded') _navigation('Aller à l’arrivée', ride.dropoff),
        if (ride.state == 'confirmed')
          Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'arrive'),
              icon: const Icon(Icons.flag_outlined), label: const Text('Je suis arrivé au départ'))),
        if (ride.state == 'driver_arrived') ...[
          TextField(controller: _code(ride.reservationId), keyboardType: TextInputType.number, maxLength: 6,
              decoration: const InputDecoration(labelText: 'Code du client (6 chiffres)')),
          FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'board'), icon: const Icon(Icons.login),
              label: const Text('Faire monter le client')),
          TextButton(onPressed: _busy ? null : () => _act(ride, 'client_absent'), child: const Text('Client absent (après 5 min)')),
        ],
        if (ride.state == 'boarded')
          Padding(padding: const EdgeInsets.only(top: 8), child: FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'finish'),
              icon: const Icon(Icons.check_circle_outline), label: const Text('Terminer la course'))),
        if ({'awaiting_payment', 'confirmed', 'driver_arrived'}.contains(ride.state))
          TextButton(onPressed: _busy ? null : () => _act(ride, 'cancel'), child: const Text('Annuler cette course (client remboursé)')),
        if (!ride.active) FilledButton(onPressed: () => _close(ride), child: const Text('Fermer')),
      ]),
    );
  }

  /// A proposal: 30 seconds to answer, the price and the share, how far and how long a detour.
  Widget _proposalCard(ThemeData theme, DriverProposalView p, int others) => Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
    Row(children: [
      Expanded(child: Text('Nouvelle course · ${p.mode == 'vip' ? 'VIP' : '${p.seats} place(s) partagée(s)'}',
          style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w700))),
      _Countdown(until: p.expiresAt),
    ]),
    const SizedBox(height: 4),
    Text('${p.priceXaf} F', style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
    Text('Ta part : ${p.driverXaf} F', style: theme.textTheme.bodyMedium),
    const SizedBox(height: 4),
    Text('Client à ${_minutes(p.pickupSeconds)} de route'),
    if (_destination != null) Text('Détour sur ton chemin : ${_minutes(p.detourSeconds)}'),
    if (others > 0) Text('+ $others autre(s) en attente', style: theme.textTheme.bodySmall),
    const SizedBox(height: 12),
    Row(children: [
      Expanded(child: OutlinedButton(onPressed: _busy ? null : () => _answer(p, false), child: const Text('Refuser'))),
      const SizedBox(width: 10),
      Expanded(flex: 2, child: FilledButton(onPressed: _busy ? null : () => _answer(p, true), child: const Text('Accepter'))),
    ]),
  ]);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final scheme = theme.colorScheme;
    final drawn = _drawn();
    final tour = _tour;
    final waiting = [
      if (_canReceive) for (final p in _proposals) p.pickup,
      for (final s in _stops) if (s.pickup) s.point,
    ];
    final offer = _canReceive ? _proposals.firstOrNull : null;
    return Scaffold(
      body: Stack(children: [
        Positioned.fill(child: widget.mapBuilder(context, _position, _destination ?? (_stops.isEmpty ? null : _stops.last.point),
            (point) {
              if (_online) return;
              setState(() {
                if (_spot == _Spot.there) {
                  _destination = point;
                  _whereTo.text = 'Point choisi sur la carte';
                } else {
                  _position = point;
                  _where.text = 'Point choisi sur la carte';
                }
                _places = const [];
              });
            }, paths: drawn, clients: waiting, driver: _following ? _position : null, follow: _following)),
        if (widget.title.isNotEmpty)
          SafeArea(child: Padding(padding: const EdgeInsets.all(12), child: PointerInterceptor(child: Material(
            color: scheme.surface, shape: const CircleBorder(), elevation: 3,
            child: IconButton(tooltip: 'Retour', onPressed: () => Navigator.of(context).maybePop(), icon: const Icon(Icons.arrow_back)),
          )))),
        // In a browser the map is a page element that would take the touches meant for the panel over it.
        Align(alignment: Alignment.bottomCenter, child: PointerInterceptor(child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.66, maxWidth: 640),
          child: Material(
            color: scheme.surface,
            elevation: 8,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
            child: SafeArea(top: false, child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 10, 20, 16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
                Center(child: Container(width: 36, height: 4, margin: const EdgeInsets.only(bottom: 12),
                    decoration: BoxDecoration(color: scheme.outlineVariant, borderRadius: BorderRadius.circular(2)))),
                Row(children: [
                  Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text(_online ? 'En ligne' : 'Hors ligne', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
                    if (_online && _destination != null)
                      Text('En route vers ${_whereTo.text} : clients dans ton sens', style: theme.textTheme.bodySmall)
                    else if (_online && _positionAt != null)
                      Text('Position envoyée à ${TimeOfDay.fromDateTime(_positionAt!.toLocal()).format(context)}',
                          style: theme.textTheme.bodySmall),
                  ])),
                  if (_online || _carrying)
                    IconButton(tooltip: 'Actualiser', onPressed: _busy ? null : () => _run(_refreshNow), icon: const Icon(Icons.refresh)),
                ]),
                if (drawn.isNotEmpty) Padding(padding: const EdgeInsets.only(top: 6), child: _legend(drawn)),
                const SizedBox(height: 12),
                if (!_online) ...[
                  Center(child: Semantics(button: true, label: 'Passer en ligne', child: Material(
                    color: scheme.primary,
                    shape: const CircleBorder(),
                    elevation: 4,
                    child: InkWell(
                      key: const Key('en-ligne'),
                      customBorder: const CircleBorder(),
                      onTap: _busy ? null : _goOnline,
                      child: SizedBox(width: 96, height: 96, child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                        Icon(Icons.power_settings_new, color: scheme.onPrimary, size: 30),
                        Text('En ligne', style: TextStyle(color: scheme.onPrimary, fontWeight: FontWeight.w600)),
                      ])),
                    ),
                  ))),
                  const SizedBox(height: 16),
                  if (widget.login.gateway is RideGateway) ...[
                    if (_gpsMissing || _where.text.isNotEmpty) ...[
                      _placeField(_Spot.here, _where, 'Où es-tu ?', Icons.my_location, _position),
                      ..._suggestions(_Spot.here),
                      const SizedBox(height: 8),
                    ],
                    _placeField(_Spot.there, _whereTo, 'Où vas-tu ? (facultatif)', Icons.flag_outlined, _destination),
                    ..._suggestions(_Spot.there),
                    const SizedBox(height: 4),
                    Text('Avec une direction, tu reçois les clients à 10 min au plus qui vont dans ton sens.',
                        style: theme.textTheme.bodySmall),
                  ],
                  if (_gpsMissing && _position == null)
                    const Padding(padding: EdgeInsets.only(top: 8),
                        child: Text('Ta position est introuvable : écris où tu es, ou touche la carte.')),
                ],
                if (offer != null) _proposalCard(theme, offer, _proposals.length - 1),
                if (_online && tour.isNotEmpty) ...[
                  if (offer != null) const SizedBox(height: 16),
                  Row(children: [
                    Expanded(flex: 2, child: FilledButton.icon(
                      onPressed: _toggleFollow,
                      icon: Icon(_following ? Icons.stop_circle_outlined : Icons.navigation),
                      label: Text(_following ? 'Arrêter le suivi' : 'Démarrer le trajet'),
                    )),
                    const SizedBox(width: 8),
                    OutlinedButton(onPressed: () => _navigate(wazeNavigation(tour.first)), child: const Text('Waze')),
                  ]),
                  TextButton.icon(onPressed: () => _navigate(googleMapsTour(tour)), icon: const Icon(Icons.map_outlined),
                      label: Text(tour.length > 1 ? 'Tout le trajet · Google Maps' : 'Google Maps')),
                  const SizedBox(height: 4),
                ],
                for (final (i, ride) in _rides.indexed) _rideCard(theme, ride, i, _rides.length),
                if (_canReceive && offer == null)
                  Text(_carrying ? 'En attente d’un autre client sur ton chemin…' : 'En attente d’une course…'),
                if (_online && !_carrying)
                  TextButton(onPressed: _busy ? null : _goOffline, child: const Text('Passer hors ligne')),
                if (_busy) const Padding(padding: EdgeInsets.only(top: 12), child: LinearProgressIndicator()),
                if (_message != null) Padding(
                  padding: const EdgeInsets.only(top: 12),
                  child: Text(_message!, style: TextStyle(color: scheme.error)),
                ),
              ]),
            )),
          ),
        ))),
      ]),
    );
  }
}

/// The seconds left to answer a proposal, refreshed every second.
class _Countdown extends StatefulWidget {
  const _Countdown({required this.until});
  final DateTime until;
  @override
  State<_Countdown> createState() => _CountdownState();
}

class _CountdownState extends State<_Countdown> {
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
      if (!DateTime.now().isBefore(widget.until)) _tick?.cancel();
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final left = widget.until.difference(DateTime.now()).inSeconds.clamp(0, 99);
    final scheme = Theme.of(context).colorScheme;
    return Semantics(label: '$left secondes pour répondre', child: Container(
      width: 40, height: 40,
      alignment: Alignment.center,
      decoration: BoxDecoration(shape: BoxShape.circle, border: Border.all(color: left <= 10 ? scheme.error : scheme.primary, width: 3)),
      child: Text('$left', style: const TextStyle(fontWeight: FontWeight.w700)),
    ));
  }
}
