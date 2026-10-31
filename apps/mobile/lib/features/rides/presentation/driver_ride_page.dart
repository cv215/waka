import 'dart:async';

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../core/presentation/waka_page.dart';
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
Uri wazeNavigation(RidePoint to) => Uri.https('waze.com', '/ul', {'ll': '${to.latitude},${to.longitude}', 'navigate': 'yes'});

/// « Mode chauffeur » (R13, R15): be online with a fresh position; with a direction, take several shared clients on the
/// way; answer proposals within 30 seconds, then take each ride through its steps. The server alone decides who
/// receives a proposal, the price, the driver's share and the detour allowed.
class DriverRidePage extends StatefulWidget {
  const DriverRidePage({super.key, required this.login, this.mapBuilder = googleRideMap, this.readPosition = devicePosition,
      this.openLink = openExternally, this.pollEvery = const Duration(seconds: 4), this.presenceEvery = const Duration(seconds: 20)});
  final LoginFlow login;
  final RideMapBuilder mapBuilder;
  final ReadPosition readPosition;
  final OpenLink openLink;
  /// Null in tests: the driver refreshes by hand.
  final Duration? pollEvery, presenceEvery;
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
  DateTime? _positionAt;
  List<DriverProposalView> _proposals = const [];
  /// Route shapes per proposal, asked once each (the screen refreshes every few seconds).
  final Map<String, ProposalPathsView> _shapes = {};
  final Set<String> _askedShapes = {};
  /// Rides carried now, plus the ones that just ended until the driver closes them.
  List<CityRideView> _rides = const [];
  String? _message;
  Timer? _poll, _presence;

  @override
  void initState() {
    super.initState();
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
      if (!online) _proposals = const [];
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
    final every = widget.pollEvery, presence = widget.presenceEvery;
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
    }
    if (_canReceive) {
      final list = await _gateway.driverProposals(session, client);
      if (mounted) setState(() => _proposals = list);
      final first = list.firstOrNull;
      if (first != null && _askedShapes.add(first.proposalId)) {
        try {
          final shapes = await _gateway.proposalPaths(session, client, first.proposalId);
          if (mounted) setState(() => _shapes[first.proposalId] = shapes);
        } on PhotoProblem {
          // Without shapes the proposal is still shown with its pins; the routes are only an aid.
        }
      }
    }
  }

  List<RidePath>? _drawn(DriverProposalView? offer) {
    final shapes = offer == null ? null : _shapes[offer.proposalId];
    if (shapes == null) return null;
    return [
      if (shapes.normal != null) RidePath.normal(shapes.normal!),
      if (shapes.combined != null) RidePath.combined(shapes.combined!),
      if (shapes.client != null) RidePath.client(shapes.client!),
    ];
  }

  Future<void> _navigate(Uri link) async {
    final opened = await widget.openLink(link);
    if (!opened && mounted) setState(() => _message = 'Impossible d’ouvrir l’application de navigation sur ce téléphone.');
  }

  Widget _legend(ThemeData theme, bool withDirection) => Padding(
    padding: const EdgeInsets.only(top: 6),
    child: Wrap(spacing: 12, runSpacing: 4, children: [
      if (withDirection) _swatch(const Color(0xFF2E7D32), 'Ton trajet normal'),
      _swatch(const Color(0xFF1565C0), 'Trajet du client'),
      _swatch(const Color(0xFFF9A825), withDirection ? 'Ton nouveau trajet' : 'Ton trajet avec le client'),
    ]),
  );

  Widget _swatch(Color color, String label) => Row(mainAxisSize: MainAxisSize.min, children: [
    Container(width: 18, height: 6, decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(3))),
    const SizedBox(width: 6),
    Text(label),
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
  });

  void _close(CityRideView ride) => setState(() {
    _rides = _rides.where((r) => r.reservationId != ride.reservationId).toList();
    _codes.remove(ride.reservationId)?.dispose();
    _message = null;
  });

  String _minutes(int seconds) => seconds < 60 ? 'moins d’une minute' : '${(seconds / 60).round()} min';

  Widget _placeField(_Spot spot, TextEditingController controller, String label, String help, IconData icon, RidePoint? chosen) =>
      TextField(
        controller: controller,
        enabled: !_busy && !_online,
        onTap: () => setState(() { _spot = spot; _places = const []; }),
        onChanged: (text) => _typedPlace(spot, text),
        decoration: InputDecoration(
          labelText: label,
          helperText: help,
          prefixIcon: Icon(icon),
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
          leading: const Icon(Icons.location_on_outlined),
          title: Text(place.mainText),
          subtitle: place.secondaryText.isEmpty ? null : Text(place.secondaryText),
          onTap: _busy ? null : () => _choosePlace(place),
        ),
  ];

  Widget _rideCard(ThemeData theme, CityRideView ride) => Card(child: Padding(
    padding: const EdgeInsets.all(16),
    child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
      Text('${ride.mode == 'vip' ? 'VIP' : '${ride.seats} place(s)'} · ${driverStateLabel(ride.state)}', style: theme.textTheme.titleMedium),
      const SizedBox(height: 4),
      Text('Prix : ${ride.priceXaf} F · ta part : ${ride.driverXaf} F'),
      const SizedBox(height: 8),
      if ({'awaiting_payment', 'confirmed'}.contains(ride.state)) _navigation('Aller au client', ride.pickup),
      if (ride.state == 'boarded') _navigation('Aller à l’arrivée', ride.dropoff),
      if (ride.state == 'confirmed')
        FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'arrive'), icon: const Icon(Icons.flag_outlined),
            label: const Text('Je suis arrivé au départ')),
      if (ride.state == 'driver_arrived') ...[
        TextField(controller: _code(ride.reservationId), keyboardType: TextInputType.number, maxLength: 6,
            decoration: const InputDecoration(labelText: 'Code du client (6 chiffres)')),
        FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'board'), icon: const Icon(Icons.login),
            label: const Text('Faire monter le client')),
        TextButton(onPressed: _busy ? null : () => _act(ride, 'client_absent'), child: const Text('Client absent (après 5 min)')),
      ],
      if (ride.state == 'boarded')
        FilledButton.icon(onPressed: _busy ? null : () => _act(ride, 'finish'), icon: const Icon(Icons.check_circle_outline),
            label: const Text('Terminer la course')),
      if ({'awaiting_payment', 'confirmed', 'driver_arrived'}.contains(ride.state))
        TextButton(onPressed: _busy ? null : () => _act(ride, 'cancel'), child: const Text('Annuler cette course (client remboursé)')),
      if (!ride.active) FilledButton(onPressed: () => _close(ride), child: const Text('Fermer')),
    ]),
  ));

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final shown = _rides.where((r) => r.active).firstOrNull;
    final offer = _canReceive ? _proposals.firstOrNull : null;
    final drawn = _drawn(offer);
    return WakaPage(
      title: 'Mode chauffeur',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: SizedBox(
              height: 240,
              child: widget.mapBuilder(context, offer?.pickup ?? shown?.pickup ?? _position, offer?.dropoff ?? shown?.dropoff ?? _destination,
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
                  }, paths: drawn),
            ),
          ),
          if (drawn != null && drawn.isNotEmpty) _legend(theme, _destination != null),
          const SizedBox(height: 12),
          SwitchListTile(
            title: Text(_online ? 'En ligne' : 'Hors ligne'),
            subtitle: Text(!_online
                ? 'Passe en ligne pour recevoir des courses près de toi.'
                : _destination != null
                    ? 'En route vers ${_whereTo.text} : tu reçois des clients en partagé sur ton chemin.'
                    : (_positionAt == null ? 'Position envoyée' : 'Position envoyée à ${TimeOfDay.fromDateTime(_positionAt!.toLocal()).format(context)}')),
            value: _online,
            onChanged: _busy ? null : (on) => on ? _goOnline() : _goOffline(),
          ),
          if (!_online && widget.login.gateway is RideGateway) ...[
            _placeField(_Spot.here, _where, 'Où es-tu ?', 'Écris un lieu (quartier, carrefour, marché…), ou touche la carte',
                Icons.my_location, _position),
            ..._suggestions(_Spot.here),
            const SizedBox(height: 8),
            _placeField(_Spot.there, _whereTo, 'Où vas-tu ? (facultatif)',
                'Avec une direction, tu prends plusieurs clients en partagé sur ton chemin (détour de 5 min au plus)',
                Icons.flag_outlined, _destination),
            ..._suggestions(_Spot.there),
          ],
          if (!_online && _gpsMissing && _position == null)
            const Text('Sans GPS, écris le lieu où tu te trouves ou touche la carte avant de passer en ligne.'),
          for (final ride in _rides) _rideCard(theme, ride),
          if (_online || _carrying)
            OutlinedButton(onPressed: _busy ? null : () => _run(_refreshNow), child: const Text('Actualiser')),
          if (_canReceive) ...[
            if (_proposals.isEmpty)
              Padding(padding: const EdgeInsets.only(top: 12),
                  child: Text(_carrying ? 'En attente d’un autre client sur ton chemin…' : 'En attente d’une course…')),
            for (final p in _proposals)
              Card(child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                  Text('Nouvelle course · ${p.mode == 'vip' ? 'VIP' : '${p.seats} place(s) partagée(s)'}', style: theme.textTheme.titleMedium),
                  const SizedBox(height: 4),
                  Text('Client à ${_minutes(p.pickupSeconds)} de route'),
                  if (_destination != null) Text('Détour sur ton chemin : ${_minutes(p.detourSeconds)}'),
                  Text('Prix : ${p.priceXaf} F · ta part : ${p.driverXaf} F'),
                  const SizedBox(height: 8),
                  Row(children: [
                    Expanded(child: FilledButton(onPressed: _busy ? null : () => _answer(p, true), child: const Text('Accepter'))),
                    const SizedBox(width: 8),
                    Expanded(child: OutlinedButton(onPressed: _busy ? null : () => _answer(p, false), child: const Text('Refuser'))),
                  ]),
                ]),
              )),
          ],
          if (_busy) const Padding(padding: EdgeInsets.only(top: 12), child: LinearProgressIndicator()),
          if (_message != null) Padding(
            padding: const EdgeInsets.only(top: 12),
            child: Text(_message!, style: TextStyle(color: theme.colorScheme.error)),
          ),
        ],
      ),
    );
  }
}
