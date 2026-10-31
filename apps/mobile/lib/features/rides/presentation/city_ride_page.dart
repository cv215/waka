import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:pointer_interceptor/pointer_interceptor.dart';

import '../../../core/presentation/preference_store.dart';
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
  // Usually an app older than the server (a page left open across an update): reloading fixes it.
  PhotoFailure.invalidResponse => 'L’application n’est plus à jour : recharge la page (ou mets l’application à jour), puis réessaie.',
  _ => 'Demande refusée : vérifie le départ, l’arrivée et le nombre de personnes.',
};

String rideStateLabel(String state) => switch (state) {
  'awaiting_payment' => 'Chauffeur trouvé : paie pour confirmer',
  'confirmed' => 'Ton chauffeur arrive',
  'driver_arrived' => 'Ton chauffeur est là',
  'boarded' => 'Tu es à bord. Bonne route !',
  'finished' => 'Course terminée. Merci d’avoir voyagé avec WAKA.',
  'cancelled_by_client' => 'Course annulée.',
  'cancelled_by_driver' => 'Le chauffeur a annulé : la course est remboursée.',
  'client_absent' => 'Le chauffeur ne t’a pas trouvé au départ.',
  'expired' => 'Le délai de paiement est passé : la course est annulée.',
  _ => state,
};

/// Where this phone is. Null when the position cannot be read (refused, no GPS, or a page without HTTPS).
typedef ReadPosition = Future<RidePoint?> Function();

Future<RidePoint?> devicePosition() async {
  try {
    if (!await Geolocator.isLocationServiceEnabled()) return null;
    var permission = await Geolocator.checkPermission();
    if (permission == LocationPermission.denied) permission = await Geolocator.requestPermission();
    if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) return null;
    final p = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high, timeLimit: Duration(seconds: 10)));
    return RidePoint(p.latitude, p.longitude);
  } on Exception {
    return null;
  }
}

/// A route drawn on the map. Widths are fixed so overlapping routes stay readable: the widest underneath.
class RidePath {
  const RidePath(this.name, this.points, this.color, this.width);
  final String name;
  final List<RidePoint> points;
  final Color color;
  final int width;
  /// R15 colours: the driver's own route, the client's trip, the driver's route with this client.
  static RidePath normal(List<RidePoint> p) => RidePath('normal', p, const Color(0xFF2E7D32), 9);
  static RidePath combined(List<RidePoint> p) => RidePath('combine', p, const Color(0xFFF9A825), 6);
  static RidePath client(List<RidePoint> p) => RidePath('client', p, const Color(0xFF1565C0), 3);
  /// The passenger's own trip: the same blue, wider since it is alone on his map.
  static RidePath trip(List<RidePoint> p) => RidePath('client', p, const Color(0xFF1565C0), 6);
}

/// Builds the map; tests replace it because platform maps need a device. [clients]: clients' pickups (violet pins);
/// [follow]: the camera stays on [driver] as it moves (the driver's « Démarrer » mode).
typedef RideMapBuilder = Widget Function(BuildContext context, RidePoint? origin, RidePoint? destination,
    void Function(RidePoint point) onTap, {RidePoint? driver, List<RidePath>? paths, List<RidePoint>? clients, bool follow});

Widget googleRideMap(BuildContext context, RidePoint? origin, RidePoint? destination, void Function(RidePoint point) onTap,
        {RidePoint? driver, List<RidePath>? paths, List<RidePoint>? clients, bool follow = false}) =>
    _GoogleRideMap(origin: origin, destination: destination, driver: driver, paths: paths ?? const [], clients: clients ?? const [],
        follow: follow, onTap: onTap);

class _GoogleRideMap extends StatefulWidget {
  const _GoogleRideMap({required this.origin, required this.destination, required this.onTap, this.driver, this.paths = const [],
      this.clients = const [], this.follow = false});
  final RidePoint? origin, destination, driver;
  final List<RidePath> paths;
  final List<RidePoint> clients;
  final bool follow;
  final void Function(RidePoint point) onTap;
  @override
  State<_GoogleRideMap> createState() => _GoogleRideMapState();
}

class _GoogleRideMapState extends State<_GoogleRideMap> {
  GoogleMapController? _controller;

  LatLng _at(RidePoint p) => LatLng(p.latitude, p.longitude);

  /// Same routes, same signature: the camera is framed only when the drawn routes really change.
  static String _signature(List<RidePath> paths) =>
      paths.map((p) => '${p.name}:${p.points.length}:${p.points.first.latitude}:${p.points.last.longitude}').join('|');

  @override
  void didUpdateWidget(_GoogleRideMap old) {
    super.didUpdateWidget(old);
    final o = widget.origin, d = widget.destination, car = widget.driver;
    if (widget.follow) {
      // Following the car: close and centred on it, whatever else changes.
      if (car != null && (!old.follow || car != old.driver)) _controller?.animateCamera(CameraUpdate.newLatLngZoom(_at(car), 16));
    } else if (widget.paths.isNotEmpty && _signature(widget.paths) != _signature(old.paths)) {
      // Frame every drawn route once, when they arrive.
      final all = [for (final p in widget.paths) ...p.points];
      double minLat = all.first.latitude, maxLat = minLat, minLon = all.first.longitude, maxLon = minLon;
      for (final p in all) {
        if (p.latitude < minLat) minLat = p.latitude;
        if (p.latitude > maxLat) maxLat = p.latitude;
        if (p.longitude < minLon) minLon = p.longitude;
        if (p.longitude > maxLon) maxLon = p.longitude;
      }
      _controller?.animateCamera(CameraUpdate.newLatLngBounds(
          LatLngBounds(southwest: LatLng(minLat, minLon), northeast: LatLng(maxLat, maxLon)), 32));
    } else if (o != null && d != null && (o != old.origin || d != old.destination)) {
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
    polylines: {
      for (final (i, path) in widget.paths.indexed)
        Polyline(polylineId: PolylineId('${path.name}$i'), points: [for (final p in path.points) _at(p)], color: path.color,
            width: path.width, zIndex: i, startCap: Cap.roundCap, endCap: Cap.roundCap, jointType: JointType.round),
    },
    markers: {
      if (widget.origin != null)
        Marker(markerId: const MarkerId('depart'), position: _at(widget.origin!), infoWindow: const InfoWindow(title: 'Départ'),
            icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueGreen)),
      if (widget.destination != null)
        Marker(markerId: const MarkerId('arrivee'), position: _at(widget.destination!), infoWindow: const InfoWindow(title: 'Arrivée')),
      if (widget.driver != null)
        Marker(markerId: const MarkerId('chauffeur'), position: _at(widget.driver!), infoWindow: const InfoWindow(title: 'Ton chauffeur'),
            icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueAzure)),
      for (final (i, client) in widget.clients.indexed)
        Marker(markerId: MarkerId('client$i'), position: _at(client), infoWindow: const InfoWindow(title: 'Client'),
            icon: BitmapDescriptor.defaultMarkerWithHue(BitmapDescriptor.hueViolet)),
    },
  );
}

/// « Course en ville » (R13), step 2 of the redesign: a full-screen map and one panel at the bottom that follows the
/// moment — where to (the pickup is this phone's position), the two prices side by side and one order button, then
/// the search, the payment and the driver coming, in the same panel. Nothing about the price is computed on the phone.
class CityRidePage extends StatefulWidget {
  const CityRidePage({super.key, required this.login, this.mapBuilder = googleRideMap, this.readPosition = devicePosition,
      this.pollEvery = const Duration(seconds: 4), this.typingDelay = const Duration(milliseconds: 400), this.actions});
  final LoginFlow login;
  /// Top-right buttons (the account, when this page is the home).
  final List<Widget>? actions;
  final RideMapBuilder mapBuilder;
  final ReadPosition readPosition;
  /// Null in tests: the client refreshes by hand.
  final Duration? pollEvery;
  final Duration typingDelay;
  @override
  State<CityRidePage> createState() => _CityRidePageState();
}

enum _Field { origin, destination }

/// A destination chosen before, kept on this device only (three at most) to choose it again in one touch.
class _Recent {
  const _Recent(this.label, this.point);
  final String label;
  final RidePoint point;
  Map<String, Object> toJson() => {'label': label, 'lat': point.latitude, 'lon': point.longitude};
  static List<_Recent> parse(String? raw) {
    try {
      final list = jsonDecode(raw ?? '[]') as List;
      return [
        for (final e in list.take(3))
          _Recent((e as Map)['label'] as String, RidePoint((e['lat'] as num).toDouble(), (e['lon'] as num).toDouble())),
      ];
    } catch (_) {
      return const [];
    }
  }
}

class _CityRidePageState extends State<CityRidePage> {
  RideGateway get _gateway => widget.login.gateway as RideGateway;
  final _originText = TextEditingController(), _destinationText = TextEditingController();
  RidePoint? _origin, _destination;
  /// Which point the field and a touch on the map set: the destination, unless the client changes the pickup.
  _Field _active = _Field.destination;
  List<PlaceSuggestion> _suggestions = const [];
  List<_Recent> _recents = const [];
  String _placeSession = randomLoginId();
  Timer? _typing, _poll;
  String _mode = 'shared';
  int _people = 1;
  /// Both prices of the same trip ('shared', 'vip'); the server measures the road once for the two.
  Map<String, RideQuoteView> _quotes = const {};
  RideSearchView? _search;
  CityRideView? _ride;
  String? _message;
  bool _busy = false;
  /// Same request after a lost answer: same key, so the server never records it twice.
  final Map<String, String> _keys = {};

  @override
  void initState() {
    super.initState();
    readPreference(_recentKey).then((raw) { if (mounted) setState(() => _recents = _Recent.parse(raw)); });
    _run(() async {
      final (session, client) = _context();
      // A ride still in progress (the screen or the phone was closed) is found again.
      final ride = await _gateway.activeRide(session, client);
      if (mounted && ride != null && ride.viewerRole == 'client') {
        setState(() => _ride = ride);
        _follow();
        return;
      }
      // Otherwise the pickup is where the phone is, when it can tell.
      final here = await widget.readPosition();
      if (mounted && here != null && _origin == null) setState(() { _origin = here; _originText.text = 'Ma position'; });
    });
  }

  static const _recentKey = 'waka_recent_places';

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
      _quotes = const {};
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
    // Inside the same busy step: the prices follow at once when both points are known.
    if (_set(_active, point, place.mainText)) await _askPrices();
  });

  void _tapped(RidePoint point) {
    if (_search != null || _ride != null) return;
    if (_set(_active, point, 'Point choisi sur la carte')) _prices();
  }

  /// A point is set; true once both are known (the caller then asks the two prices).
  bool _set(_Field field, RidePoint point, String label) {
    FocusScope.of(context).unfocus();
    setState(() {
      _suggestions = const [];
      _quotes = const {};
      if (field == _Field.origin) {
        _origin = point;
        _originText.text = label;
      } else {
        _destination = point;
        _destinationText.text = label;
      }
      // Without a pickup yet (no GPS), the panel asks for it next.
      _active = _origin == null ? _Field.origin : _Field.destination;
    });
    if (field == _Field.destination && label != 'Point choisi sur la carte') _remember(_Recent(label, point));
    return _origin != null && _destination != null;
  }

  void _remember(_Recent place) {
    final kept = [place, ..._recents.where((r) => r.label != place.label)].take(3).toList();
    setState(() => _recents = kept);
    writePreference(_recentKey, jsonEncode([for (final r in kept) r.toJson()]));
  }

  /// Partagé and VIP for the same trip and number of people, one after the other (the second reuses the road).
  Future<void> _prices() => _run(_askPrices);

  Future<void> _askPrices() async {
    final (session, client) = _context();
    final found = <String, RideQuoteView>{};
    for (final mode in const ['shared', 'vip']) {
      final request = 'quote:$mode:$_people:${_origin!.toJson()}:${_destination!.toJson()}';
      found[mode] = await _gateway.quoteRide(session, client, _key(request), mode, _people, _origin!, _destination!);
      _keys.remove(request);
      if (mounted) setState(() => _quotes = Map.unmodifiable({..._quotes, ...found}));
    }
  }

  void _changePeople(int people) {
    setState(() { _people = people; _quotes = const {}; });
    _prices();
  }

  Future<void> _order() => _run(() async {
    final (session, client) = _context();
    var quote = _quotes[_mode]!;
    if (!DateTime.now().toUtc().isBefore(quote.expiresAt)) {
      // A price is valid five minutes: ask it again before ordering, never order on a stale one.
      final request = 'quote:$_mode:$_people:${_origin!.toJson()}:${_destination!.toJson()}';
      quote = await _gateway.quoteRide(session, client, _key(request), _mode, _people, _origin!, _destination!);
      _keys.remove(request);
      if (mounted) setState(() => _quotes = Map.unmodifiable({..._quotes, _mode: quote}));
    }
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
    if (payment != null && payment.state == 'succeeded' && payment.applied && mounted) setState(() => _message = null);
    await _run(_refreshNow);
  }

  /// Back to « Où vas-tu ? », keeping the pickup.
  void _restart() {
    _poll?.cancel();
    setState(() {
      _search = null;
      _ride = null;
      _quotes = const {};
      _destination = null;
      _destinationText.clear();
      _message = null;
      _keys.clear();
      _active = _Field.destination;
    });
  }

  /// The pickup at this phone's position (GPS; only over https in a browser).
  Future<void> _here() => _run(() async {
    final point = await widget.readPosition();
    if (!mounted) return;
    if (point == null) {
      setState(() => _message = 'Position introuvable : autorise la localisation, ou écris le lieu de départ.');
      return;
    }
    if (_set(_Field.origin, point, 'Ma position')) await _askPrices();
  });

  @override
  Widget build(BuildContext context) {
    final ride = _ride, search = _search;
    final choosing = ride == null && search == null;
    final priced = choosing && _origin != null && _destination != null;
    // Blue: the trip by road, from the prices onwards and during the ride.
    final trip = ride?.route ?? (_quotes[_mode] ?? _quotes.values.firstOrNull)?.route;
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: Stack(children: [
        Positioned.fill(child: widget.mapBuilder(context, ride?.pickup ?? _origin, ride?.dropoff ?? _destination, _tapped,
            driver: ride?.driverPosition, paths: trip == null ? null : [RidePath.trip(trip)])),
        SafeArea(child: Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            if (priced) _round(IconButton(tooltip: 'Retour', onPressed: _busy ? null : _restart, icon: const Icon(Icons.arrow_back))),
            const Spacer(),
            for (final action in widget.actions ?? const <Widget>[]) _round(action),
          ]),
        )),
        // In a browser the map is a page element that would take the touches meant for what is drawn over it.
        Align(alignment: Alignment.bottomCenter, child: PointerInterceptor(child: ConstrainedBox(
          constraints: BoxConstraints(maxHeight: MediaQuery.sizeOf(context).height * 0.62, maxWidth: 640),
          child: Material(
            color: scheme.surface,
            elevation: 8,
            borderRadius: const BorderRadius.vertical(top: Radius.circular(22)),
            child: SafeArea(top: false, child: SingleChildScrollView(
              padding: const EdgeInsets.fromLTRB(20, 10, 20, 16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, mainAxisSize: MainAxisSize.min, children: [
                Center(child: Container(width: 36, height: 4, margin: const EdgeInsets.only(bottom: 14),
                    decoration: BoxDecoration(color: scheme.outlineVariant, borderRadius: BorderRadius.circular(2)))),
                if (ride != null) ..._ridePanel(ride)
                else if (search != null) ..._searchPanel(search)
                else if (priced) ..._pricePanel()
                else ..._wherePanel(),
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

  Widget _round(Widget child) => Padding(
    padding: const EdgeInsets.only(left: 8),
    child: PointerInterceptor(child: Material(color: Theme.of(context).colorScheme.surface, shape: const CircleBorder(),
        elevation: 3, child: child)),
  );

  /// 1. Where to: the pickup is this phone's position (changeable), one search field, the recent places.
  List<Widget> _wherePanel() {
    final theme = Theme.of(context);
    final editingOrigin = _active == _Field.origin;
    return [
      Text(editingOrigin ? 'D’où pars-tu ?' : 'Où vas-tu ?', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
      const SizedBox(height: 12),
      TextField(
        key: ValueKey(_active),
        controller: editingOrigin ? _originText : _destinationText,
        enabled: !_busy,
        onChanged: (text) => _typed(_active, text),
        decoration: InputDecoration(
          hintText: 'Chercher un lieu',
          prefixIcon: const Icon(Icons.search),
          filled: true,
          fillColor: theme.colorScheme.surfaceContainerHigh,
          border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
          suffixIcon: editingOrigin
              ? IconButton(tooltip: 'Ma position', icon: const Icon(Icons.my_location), onPressed: _busy ? null : _here)
              : null,
        ),
      ),
      for (final place in _suggestions)
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: const Icon(Icons.location_on_outlined),
          title: Text(place.mainText),
          subtitle: place.secondaryText.isEmpty ? null : Text(place.secondaryText),
          onTap: _busy ? null : () => _choose(place),
        ),
      if (_suggestions.isEmpty && !editingOrigin)
        for (final recent in _recents)
          ListTile(
            contentPadding: EdgeInsets.zero,
            leading: const Icon(Icons.history),
            title: Text(recent.label),
            onTap: _busy ? null : () { if (_set(_Field.destination, recent.point, recent.label)) _prices(); },
          ),
      const SizedBox(height: 8),
      ListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        leading: Icon(Icons.trip_origin, color: theme.colorScheme.onSurface),
        title: Text(editingOrigin
            ? 'Choisis le départ, ou touche la carte'
            : 'Départ : ${_origin == null ? 'à choisir' : _originText.text}'),
        trailing: editingOrigin ? null : TextButton(
            onPressed: _busy ? null : () => setState(() { _active = _Field.origin; _suggestions = const []; }),
            child: const Text('Changer')),
      ),
      if (_busy) const LinearProgressIndicator(),
    ];
  }

  /// 2. The two prices side by side, the number of people, one order button.
  List<Widget> _pricePanel() {
    final theme = Theme.of(context);
    final shared = _quotes['shared'], vip = _quotes['vip'], chosen = _quotes[_mode];
    final km = (shared ?? vip)?.distanceMetres;
    return [
      Text('${_originText.text} → ${_destinationText.text}${km == null ? '' : ' · ${(km / 1000).toStringAsFixed(1).replaceAll('.', ',')} km'}',
          maxLines: 2, overflow: TextOverflow.ellipsis, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      const SizedBox(height: 12),
      Row(children: [
        Expanded(child: _option('shared', 'Partagé', shared == null ? null : '${shared.totalXaf} F',
            _people == 1 ? 'une place' : '$_people places', Icons.groups_outlined)),
        const SizedBox(width: 10),
        Expanded(child: _option('vip', 'VIP', vip == null ? null : '${vip.totalXaf} F', 'voiture entière', Icons.star_outline)),
      ]),
      const SizedBox(height: 8),
      Row(children: [
        Expanded(child: Text('Personnes', style: theme.textTheme.bodyLarge)),
        IconButton(tooltip: 'Moins', onPressed: _busy || _people <= 1 ? null : () => _changePeople(_people - 1),
            icon: const Icon(Icons.remove_circle_outline)),
        Text('$_people', style: theme.textTheme.titleMedium),
        IconButton(tooltip: 'Plus', onPressed: _busy || _people >= 5 ? null : () => _changePeople(_people + 1),
            icon: const Icon(Icons.add_circle_outline)),
      ]),
      const SizedBox(height: 8),
      FilledButton(
        onPressed: _busy || chosen == null ? null : _order,
        child: _busy && chosen == null
            ? const SizedBox(width: 22, height: 22, child: CircularProgressIndicator(strokeWidth: 2.5))
            : Text(chosen == null ? 'Commander' : 'Commander · ${chosen.totalXaf} F'),
      ),
    ];
  }

  Widget _option(String mode, String title, String? price, String detail, IconData icon) {
    final theme = Theme.of(context);
    final on = _mode == mode;
    return Semantics(
      selected: on,
      button: true,
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: () => setState(() => _mode = mode),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: on ? theme.colorScheme.primary : theme.colorScheme.outlineVariant, width: on ? 2 : 1),
          ),
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Row(children: [Icon(icon, size: 20), const SizedBox(width: 6), Text(title, style: theme.textTheme.titleMedium)]),
            const SizedBox(height: 6),
            Text(price ?? '…', style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
            Text(detail, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ]),
        ),
      ),
    );
  }

  /// 3a. The search for a driver.
  List<Widget> _searchPanel(RideSearchView search) {
    final theme = Theme.of(context);
    final searching = search.state == 'searching';
    return [
      Row(children: [
        if (searching) const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 3))
        else const Icon(Icons.search_off),
        const SizedBox(width: 14),
        Expanded(child: Text(switch (search.state) {
          'searching' => 'Recherche d’un chauffeur proche…',
          'expired' => 'Aucun chauffeur disponible pour l’instant.',
          _ => 'Recherche annulée.',
        }, style: theme.textTheme.titleMedium)),
      ]),
      const SizedBox(height: 16),
      if (searching)
        OutlinedButton(onPressed: _busy ? null : _cancel, child: const Text('Annuler'))
      else
        FilledButton(onPressed: _restart, child: const Text('Nouvelle course')),
    ];
  }

  /// 3b. The ride: pay, then who is coming and the code, in the same panel.
  List<Widget> _ridePanel(CityRideView ride) {
    final theme = Theme.of(context);
    final card = ride.driver;
    return [
      Text(rideStateLabel(ride.state), style: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w700)),
      if (card != null) ...[
        const SizedBox(height: 12),
        Row(children: [
          CircleAvatar(backgroundColor: theme.colorScheme.surfaceContainerHigh, foregroundColor: theme.colorScheme.onSurface,
              child: const Icon(Icons.person_outline)),
          const SizedBox(width: 12),
          Expanded(child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text([card.firstName, card.vehicle].whereType<String>().join(' · '), style: theme.textTheme.titleMedium),
            if (card.plate != null || card.color != null)
              Text([card.color, card.plate].whereType<String>().join(' · '),
                  style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
          ])),
        ]),
      ],
      if (ride.boardingCode != null && {'confirmed', 'driver_arrived'}.contains(ride.state)) ...[
        const SizedBox(height: 14),
        Container(
          padding: const EdgeInsets.symmetric(vertical: 12),
          decoration: BoxDecoration(color: theme.colorScheme.surfaceContainerHigh, borderRadius: BorderRadius.circular(14)),
          child: Column(children: [
            Text('Ton code à donner au chauffeur', style: theme.textTheme.bodySmall),
            SelectableText(ride.boardingCode!, style: theme.textTheme.headlineMedium?.copyWith(letterSpacing: 6, fontWeight: FontWeight.w700)),
          ]),
        ),
      ],
      const SizedBox(height: 8),
      Text('${ride.priceXaf} F${ride.refundXaf != null ? ' · remboursé : ${ride.refundXaf} F' : ''}',
          style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      const SizedBox(height: 12),
      if (ride.state == 'awaiting_payment')
        FilledButton(onPressed: _busy ? null : () => _pay(ride), child: Text('Payer ${ride.priceXaf} F')),
      if (ride.active && ride.state != 'boarded')
        TextButton(onPressed: _busy ? null : _cancel, child: const Text('Annuler la course')),
      if (!ride.active) FilledButton(onPressed: _restart, child: const Text('Nouvelle course')),
    ];
  }
}
