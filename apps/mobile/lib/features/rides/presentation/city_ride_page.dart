import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/payment_models.dart';
import '../../identity/data/ride_models.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../payments/presentation/pay_dialog.dart';

/// Launch city (R11): Yaoundé. The server alone decides whether a point is inside the area.
const yaoundeCentre = RidePoint(3.848, 11.502);

String rideMessage(PhotoFailure failure) => switch (failure) {
  PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
  PhotoFailure.outsideServiceArea => 'WAKA roule pour l’instant à Yaoundé seulement : choisis un départ et une arrivée dans la ville.',
  PhotoFailure.noRoute => 'Aucune route praticable entre ces deux points. Choisis-en d’autres.',
  PhotoFailure.expired => 'Ce prix a expiré. Redemande le prix.',
  PhotoFailure.notEditable => 'Cette course ne peut plus être modifiée.',
  PhotoFailure.notFound => 'Cette course n’existe plus.',
  PhotoFailure.keyReused => 'Cette demande ne correspond plus au serveur. Recommence.',
  PhotoFailure.unavailable => 'Le service est momentanément indisponible. Réessaie plus tard.',
  PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie : la même demande sera renvoyée, sans doublon.',
  _ => 'Demande refusée : vérifie le départ, l’arrivée et le nombre de personnes.',
};

String rideStateLabel(String state) => switch (state) {
  'awaiting_payment' => 'Chauffeur trouvé : paie la course pour la confirmer.',
  'confirmed' => 'Course payée : le chauffeur arrive.',
  'driver_arrived' => 'Le chauffeur est arrivé au point de départ.',
  'boarded' => 'Tu es à bord. Bonne route !',
  'finished' => 'Course terminée. Merci d’avoir voyagé avec WAKA.',
  'cancelled_by_client' => 'Course annulée.',
  'cancelled_by_driver' => 'Le chauffeur a annulé : la course est remboursée.',
  'client_absent' => 'Le chauffeur ne t’a pas trouvé au départ.',
  'expired' => 'Le délai de paiement est passé : la course est annulée.',
  _ => state,
};

/// Builds the map; tests replace it because platform maps need a device.
typedef RideMapBuilder = Widget Function(BuildContext context, RidePoint? origin, RidePoint? destination,
    void Function(RidePoint point) onTap);

Widget googleRideMap(BuildContext context, RidePoint? origin, RidePoint? destination, void Function(RidePoint point) onTap) =>
    _GoogleRideMap(origin: origin, destination: destination, onTap: onTap);

class _GoogleRideMap extends StatefulWidget {
  const _GoogleRideMap({required this.origin, required this.destination, required this.onTap});
  final RidePoint? origin, destination;
  final void Function(RidePoint point) onTap;
  @override
  State<_GoogleRideMap> createState() => _GoogleRideMapState();
}

class _GoogleRideMapState extends State<_GoogleRideMap> {
  GoogleMapController? _controller;

  LatLng _at(RidePoint p) => LatLng(p.latitude, p.longitude);

  @override
  void didUpdateWidget(_GoogleRideMap old) {
    super.didUpdateWidget(old);
    final o = widget.origin, d = widget.destination;
    if (o != null && d != null && (o != old.origin || d != old.destination)) {
      final bounds = LatLngBounds(
        southwest: LatLng(o.latitude < d.latitude ? o.latitude : d.latitude, o.longitude < d.longitude ? o.longitude : d.longitude),
        northeast: LatLng(o.latitude > d.latitude ? o.latitude : d.latitude, o.longitude > d.longitude ? o.longitude : d.longitude),
      );
      _controller?.animateCamera(CameraUpdate.newLatLngBounds(bounds, 48));
    } else if (o != null && o != old.origin) {
      _controller?.animateCamera(CameraUpdate.newLatLngZoom(_at(o), 15));
    } else if (d != null && d != old.destination) {
      _controller?.animateCamera(CameraUpdate.newLatLngZoom(_at(d), 15));
    }
  }

  @override
  Widget build(BuildContext context) => GoogleMap(
    initialCameraPosition: CameraPosition(target: _at(yaoundeCentre), zoom: 13),
    onMapCreated: (controller) => _controller = controller,
    onTap: (latLng) => widget.onTap(RidePoint(latLng.latitude, latLng.longitude)),
    myLocationButtonEnabled: false,
    mapToolbarEnabled: false,
    gestureRecognizers: {Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new)},
    markers: {
      if (widget.origin != null)
        Marker(markerId: const MarkerId('depart'), position: _at(widget.origin!), infoWindow: const InfoWindow(title: 'Départ'),
            icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueGreen)),
      if (widget.destination != null)
        Marker(markerId: const MarkerId('arrivee'), position: _at(widget.destination!), infoWindow: const InfoWindow(title: 'Arrivée')),
    },
  );
}

/// « Course en ville » (R13): choose the pickup and drop-off by search or on the map, see the server's price, find a
/// driver, pay within three minutes, follow the ride. Nothing about the price is computed on the phone.
class CityRidePage extends StatefulWidget {
  const CityRidePage({super.key, required this.login, this.mapBuilder = googleRideMap,
      this.pollEvery = const Duration(seconds: 4), this.typingDelay = const Duration(milliseconds: 400)});
  final LoginFlow login;
  final RideMapBuilder mapBuilder;
  /// Null in tests: the client refreshes by hand.
  final Duration? pollEvery;
  final Duration typingDelay;
  @override
  State<CityRidePage> createState() => _CityRidePageState();
}

enum _Field { origin, destination }

class _CityRidePageState extends State<CityRidePage> {
  RideGateway get _gateway => widget.login.gateway as RideGateway;
  final _originText = TextEditingController(), _destinationText = TextEditingController();
  RidePoint? _origin, _destination;
  _Field _active = _Field.origin;
  List<PlaceSuggestion> _suggestions = const [];
  String _placeSession = randomLoginId();
  Timer? _typing, _poll;
  String _mode = 'shared';
  int _people = 1;
  RideQuoteView? _quote;
  RideSearchView? _search;
  CityRideView? _ride;
  String? _message;
  bool _busy = false;
  /// Same request after a lost answer: same key, so the server never records it twice.
  final Map<String, String> _keys = {};

  @override
  void dispose() {
    _typing?.cancel();
    _poll?.cancel();
    _originText.dispose();
    _destinationText.dispose();
    super.dispose();
  }

  (LoginSession, String) _context() {
    final session = widget.login.session, client = widget.login.clientInstance;
    if (!widget.login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  String _key(String request) => _keys.putIfAbsent(request, randomLoginId);

  Future<void> _run(Future<void> Function() action, {bool quiet = false}) async {
    if (_busy && !quiet) return;
    if (!quiet) setState(() { _busy = true; _message = null; });
    try {
      await action();
    } on PhotoProblem catch (problem) {
      if (mounted) setState(() => _message = rideMessage(problem.failure));
      if (problem.failure == PhotoFailure.denied) await widget.login.checkAccount();
    } finally {
      if (mounted && !quiet) setState(() => _busy = false);
    }
  }

  void _typed(_Field field, String text) {
    _active = field;
    _typing?.cancel();
    setState(() {
      if (field == _Field.origin) { _origin = null; } else { _destination = null; }
      _quote = null;
      if (text.trim().length < 3) _suggestions = const [];
    });
    if (text.trim().length < 3) return;
    _typing = Timer(widget.typingDelay, () => _run(() async {
      final (session, client) = _context();
      final list = await _gateway.placeSuggestions(session, client, text.trim(), _placeSession);
      if (mounted && field == _active) setState(() => _suggestions = list);
    }, quiet: true));
  }

  Future<void> _choose(PlaceSuggestion place) => _run(() async {
    final (session, client) = _context();
    final point = await _gateway.placeLocation(session, client, place.placeId, _placeSession);
    _placeSession = randomLoginId(); // one billed session per chosen place
    if (!mounted) return;
    setState(() {
      _suggestions = const [];
      _quote = null;
      if (_active == _Field.origin) {
        _origin = point;
        _originText.text = place.mainText;
        _active = _Field.destination;
      } else {
        _destination = point;
        _destinationText.text = place.mainText;
      }
    });
  });

  void _tapped(RidePoint point) {
    if (_search != null) return;
    setState(() {
      _suggestions = const [];
      _quote = null;
      if (_active == _Field.origin) {
        _origin = point;
        _originText.text = 'Point choisi sur la carte';
        _active = _Field.destination;
      } else {
        _destination = point;
        _destinationText.text = 'Point choisi sur la carte';
      }
    });
  }

  Future<void> _price() => _run(() async {
    final (session, client) = _context();
    final request = 'quote:$_mode:$_people:${_origin!.toJson()}:${_destination!.toJson()}';
    final quote = await _gateway.quoteRide(session, client, _key(request), _mode, _people, _origin!, _destination!);
    _keys.remove(request);
    if (mounted) setState(() => _quote = quote);
  });

  Future<void> _order() => _run(() async {
    final (session, client) = _context();
    final quote = _quote!;
    final search = await _gateway.startRideSearch(session, client, _key('search:${quote.quoteId}'), quote.quoteId);
    if (mounted) setState(() { _search = search; _ride = search.ride; });
    _follow();
  });

  void _follow() {
    _poll?.cancel();
    final every = widget.pollEvery;
    if (every == null) return;
    _poll = Timer.periodic(every, (_) {
      if (!mounted) return;
      final ride = _ride;
      if (ride != null && !ride.active) { _poll?.cancel(); return; }
      if (ride == null && _search != null && _search!.state != 'searching') { _poll?.cancel(); return; }
      _run(_refreshNow, quiet: true);
    });
  }

  Future<void> _refreshNow() async {
    final (session, client) = _context();
    final ride = _ride;
    if (ride != null) {
      final fresh = await _gateway.cityRide(session, client, ride.reservationId);
      if (mounted) setState(() => _ride = fresh);
    } else if (_search != null) {
      final search = await _gateway.rideSearch(session, client, _search!.searchId);
      if (mounted) setState(() { _search = search; _ride = search.ride; });
    }
  }

  Future<void> _cancel() => _run(() async {
    final (session, client) = _context();
    final ride = _ride;
    if (ride != null) {
      final fresh = await _gateway.cancelCityRide(session, client, ride.reservationId);
      if (mounted) setState(() => _ride = fresh);
    } else if (_search != null) {
      final search = await _gateway.cancelRideSearch(session, client, _search!.searchId);
      if (mounted) setState(() { _search = search; _ride = search.ride; });
    }
  });

  Future<void> _pay(CityRideView ride) async {
    if (widget.login.gateway is! PaymentGateway) return;
    final payment = await showDialog<PaymentView>(context: context, barrierDismissible: false, builder: (_) => PayDialog(
        login: widget.login, purpose: 'ride', targetId: ride.reservationId, amountXaf: ride.priceXaf, title: 'Payer la course',
        pollEvery: widget.pollEvery));
    if (payment != null && payment.state == 'succeeded' && payment.applied && mounted) {
      setState(() => _message = 'Course payée : le chauffeur est en route.');
    }
    await _run(_refreshNow);
  }

  void _restart() {
    _poll?.cancel();
    setState(() { _search = null; _ride = null; _quote = null; _message = null; _keys.clear(); });
  }

  Widget _placeField(_Field field, TextEditingController controller, String label, IconData icon) => TextField(
    controller: controller,
    enabled: !_busy && _search == null,
    onTap: () => setState(() => _active = field),
    onChanged: (text) => _typed(field, text),
    decoration: InputDecoration(
      labelText: label,
      prefixIcon: Icon(icon),
      helperText: _active == field && _search == null ? 'Écris un lieu, ou touche la carte' : null,
      suffixIcon: (field == _Field.origin ? _origin : _destination) == null ? null : const Icon(Icons.check_circle_outline),
    ),
  );

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ride = _ride, search = _search, quote = _quote;
    return WakaPage(
      title: 'Course en ville',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: SizedBox(height: 260, child: widget.mapBuilder(context, _origin, _destination, _tapped)),
          ),
          const SizedBox(height: 16),
          _placeField(_Field.origin, _originText, 'Départ', Icons.trip_origin),
          if (_active == _Field.origin) ..._suggestionTiles(),
          const SizedBox(height: 8),
          _placeField(_Field.destination, _destinationText, 'Arrivée', Icons.place_outlined),
          if (_active == _Field.destination) ..._suggestionTiles(),
          const SizedBox(height: 16),
          if (search == null) ...[
            SegmentedButton<String>(
              segments: const [
                ButtonSegment(value: 'shared', label: Text('Partagé'), icon: Icon(Icons.groups_outlined)),
                ButtonSegment(value: 'vip', label: Text('VIP'), icon: Icon(Icons.star_outline)),
              ],
              selected: {_mode},
              onSelectionChanged: _busy ? null : (s) => setState(() { _mode = s.first; _quote = null; }),
            ),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(child: Text(_mode == 'shared' ? 'Places à réserver' : 'Personnes dans la voiture')),
              IconButton(tooltip: 'Moins', onPressed: _busy || _people <= 1 ? null : () => setState(() { _people--; _quote = null; }),
                  icon: const Icon(Icons.remove_circle_outline)),
              Text('$_people', style: theme.textTheme.titleMedium),
              IconButton(tooltip: 'Plus', onPressed: _busy || _people >= 5 ? null : () => setState(() { _people++; _quote = null; }),
                  icon: const Icon(Icons.add_circle_outline)),
            ]),
            const SizedBox(height: 8),
            if (quote == null)
              FilledButton.icon(
                onPressed: _busy || _origin == null || _destination == null ? null : _price,
                icon: const Icon(Icons.payments_outlined),
                label: const Text('Voir le prix'),
              )
            else ...[
              Card(child: ListTile(
                leading: const Icon(Icons.route),
                title: Text('${quote.totalXaf} F', style: theme.textTheme.headlineSmall),
                subtitle: Text('${(quote.distanceMetres / 1000).toStringAsFixed(1).replaceAll('.', ',')} km par la route · '
                    '${quote.mode == 'vip' ? 'VIP' : '${quote.passengerCount} place(s) partagée(s)'}'),
              )),
              const SizedBox(height: 8),
              FilledButton.icon(onPressed: _busy ? null : _order, icon: const Icon(Icons.local_taxi), label: const Text('Commander')),
            ],
          ] else if (ride == null) ...[
            Card(child: ListTile(
              leading: search.state == 'searching' ? const CircularProgressIndicator() : const Icon(Icons.search_off),
              title: Text(switch (search.state) {
                'searching' => 'Recherche d’un chauffeur proche…',
                'expired' => 'Aucun chauffeur disponible pour l’instant.',
                _ => 'Recherche annulée.',
              }),
            )),
            const SizedBox(height: 8),
            if (search.state == 'searching') ...[
              OutlinedButton(onPressed: _busy ? null : () => _run(_refreshNow), child: const Text('Actualiser')),
              TextButton(onPressed: _busy ? null : _cancel, child: const Text('Annuler la recherche')),
            ] else
              FilledButton(onPressed: _restart, child: const Text('Nouvelle course')),
          ] else ...[
            Card(child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(rideStateLabel(ride.state), style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                Text('Prix : ${ride.priceXaf} F'),
                if (ride.boardingCode != null && {'confirmed', 'driver_arrived'}.contains(ride.state)) ...[
                  const SizedBox(height: 8),
                  Text('Code à donner au chauffeur à la montée :'),
                  SelectableText(ride.boardingCode!, style: theme.textTheme.displaySmall),
                ],
                if (ride.refundXaf != null) Text('Remboursé : ${ride.refundXaf} F'),
              ]),
            )),
            const SizedBox(height: 8),
            if (ride.state == 'awaiting_payment')
              FilledButton.icon(onPressed: _busy ? null : () => _pay(ride), icon: const Icon(Icons.payments),
                  label: Text('Payer ${ride.priceXaf} F')),
            if (ride.active) OutlinedButton(onPressed: _busy ? null : () => _run(_refreshNow), child: const Text('Actualiser')),
            if (ride.active && ride.state != 'boarded')
              TextButton(onPressed: _busy ? null : _cancel, child: const Text('Annuler la course (remboursée)')),
            if (!ride.active) FilledButton(onPressed: _restart, child: const Text('Nouvelle course')),
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

  List<Widget> _suggestionTiles() => [
    for (final place in _suggestions)
      ListTile(
        dense: true,
        leading: const Icon(Icons.location_on_outlined),
        title: Text(place.mainText),
        subtitle: place.secondaryText.isEmpty ? null : Text(place.secondaryText),
        onTap: _busy ? null : () => _choose(place),
      ),
  ];
}
