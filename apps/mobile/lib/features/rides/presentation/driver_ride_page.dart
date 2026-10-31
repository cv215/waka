import 'dart:async';

import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';

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
  PhotoFailure.notEditable => 'Une course est déjà en cours avec ta voiture, ou cette étape n’est pas possible maintenant.',
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

/// Where the driver is. Null when the position cannot be read (refused, no GPS, or a page without HTTPS).
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

/// « Mode chauffeur » (R13): be online with a fresh position, answer proposals within 30 seconds, then take the ride
/// through its steps. The server alone decides who receives a proposal, the price and the driver's share.
class DriverRidePage extends StatefulWidget {
  const DriverRidePage({super.key, required this.login, this.mapBuilder = googleRideMap, this.readPosition = devicePosition,
      this.pollEvery = const Duration(seconds: 4), this.presenceEvery = const Duration(seconds: 20)});
  final LoginFlow login;
  final RideMapBuilder mapBuilder;
  final ReadPosition readPosition;
  /// Null in tests: the driver refreshes by hand.
  final Duration? pollEvery, presenceEvery;
  @override
  State<DriverRidePage> createState() => _DriverRidePageState();
}

class _DriverRidePageState extends State<DriverRidePage> {
  DriverRideGateway get _gateway => widget.login.gateway as DriverRideGateway;
  final _code = TextEditingController();
  bool _online = false, _busy = false, _gpsMissing = false;
  RidePoint? _position;
  DateTime? _positionAt;
  List<DriverProposalView> _proposals = const [];
  CityRideView? _ride;
  String? _message;
  Timer? _poll, _presence;

  @override
  void initState() {
    super.initState();
    // A ride still in progress (the screen or the phone was closed) is found again.
    _run(() async {
      final (session, client) = _context();
      final ride = await _gateway.activeRide(session, client);
      if (mounted && ride != null && ride.viewerRole == 'driver') {
        setState(() => _ride = ride);
        _startTimers();
      }
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    _presence?.cancel();
    _code.dispose();
    if (_online && _ride == null) {
      // Leaving the screen ends the availability: nobody is proposed a driver who is not looking.
      final session = widget.login.session, client = widget.login.clientInstance;
      if (session != null && client != null) {
        unawaited(_gateway.setPresence(session, client, false, null).catchError((_) => const DriverPresenceView(false, null)));
      }
    }
    super.dispose();
  }

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

  /// Sends the presence with the freshest position available (GPS, else the point touched on the map).
  Future<void> _sendPresence(bool online) async {
    final (session, client) = _context();
    RidePoint? where;
    if (online) {
      final gps = await widget.readPosition();
      _gpsMissing = gps == null;
      where = gps ?? _position;
      if (where == null) {
        if (mounted) setState(() => _message = 'Ta position est introuvable : touche la carte à l’endroit où tu te trouves.');
        return;
      }
    }
    final presence = await _gateway.setPresence(session, client, online, where);
    if (!mounted) return;
    setState(() {
      _online = presence.online;
      _position = online ? where : null;
      _positionAt = presence.positionAt;
      if (!online) _proposals = const [];
    });
  }

  Future<void> _goOnline() => _run(() async {
    await _sendPresence(true);
    if (_online) _startTimers();
  });

  Future<void> _goOffline() => _run(() async {
    _stopTimers();
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
    final ride = _ride;
    if (ride != null) {
      final fresh = await _gateway.cityRide(session, client, ride.reservationId);
      if (mounted) setState(() => _ride = fresh);
    } else if (_online) {
      final list = await _gateway.driverProposals(session, client);
      if (mounted) setState(() => _proposals = list);
    }
  }

  Future<void> _answer(DriverProposalView proposal, bool accept) => _run(() async {
    final (session, client) = _context();
    final ride = await _gateway.answerProposal(session, client, proposal.proposalId, accept);
    if (!mounted) return;
    setState(() {
      _proposals = _proposals.where((p) => p.proposalId != proposal.proposalId).toList();
      if (ride != null) _ride = ride;
    });
  });

  Future<void> _act(String action) => _run(() async {
    final (session, client) = _context();
    final code = action == 'board' ? _code.text.trim() : null;
    if (action == 'board' && !RegExp(r'^[0-9]{6}$').hasMatch(code!)) {
      setState(() => _message = 'Le code du client a 6 chiffres.');
      return;
    }
    final ride = await _gateway.driverRideAction(session, client, _ride!.reservationId, action, code);
    if (!mounted) return;
    setState(() { _ride = ride; if (action == 'board') _code.clear(); });
  });

  void _closeRide() => setState(() { _ride = null; _message = null; });

  String _minutes(int seconds) => seconds < 60 ? 'moins d’une minute' : '${(seconds / 60).round()} min';

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final ride = _ride;
    return WakaPage(
      title: 'Mode chauffeur',
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: SizedBox(
              height: 240,
              child: widget.mapBuilder(context, ride?.pickup ?? (_proposals.isEmpty ? _position : _proposals.first.pickup),
                  ride?.dropoff ?? (_proposals.isEmpty ? null : _proposals.first.dropoff),
                  (point) { if (!_online && ride == null) setState(() => _position = point); }),
            ),
          ),
          const SizedBox(height: 12),
          if (ride == null) ...[
            SwitchListTile(
              title: Text(_online ? 'En ligne : tu peux recevoir des courses' : 'Hors ligne'),
              subtitle: Text(_online
                  ? (_positionAt == null ? 'Position envoyée' : 'Position envoyée à ${TimeOfDay.fromDateTime(_positionAt!.toLocal()).format(context)}')
                  : 'Passe en ligne pour recevoir des courses près de toi.'),
              value: _online,
              onChanged: _busy ? null : (on) => on ? _goOnline() : _goOffline(),
            ),
            if (!_online && _gpsMissing)
              const Text('Sans GPS, touche la carte à l’endroit où tu te trouves avant de passer en ligne.'),
            if (_online) ...[
              OutlinedButton(onPressed: _busy ? null : () => _run(_refreshNow), child: const Text('Actualiser')),
              if (_proposals.isEmpty)
                const Padding(padding: EdgeInsets.only(top: 12), child: Text('En attente d’une course…')),
              for (final p in _proposals)
                Card(child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                    Text('Nouvelle course · ${p.mode == 'vip' ? 'VIP' : '${p.seats} place(s) partagée(s)'}',
                        style: theme.textTheme.titleMedium),
                    const SizedBox(height: 4),
                    Text('Client à ${_minutes(p.pickupSeconds)} de route'),
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
          ] else ...[
            Card(child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(driverStateLabel(ride.state), style: theme.textTheme.titleMedium),
                const SizedBox(height: 8),
                Text('Prix : ${ride.priceXaf} F · ta part : ${ride.driverXaf} F'),
              ]),
            )),
            const SizedBox(height: 8),
            if (ride.state == 'confirmed')
              FilledButton.icon(onPressed: _busy ? null : () => _act('arrive'), icon: const Icon(Icons.flag_outlined),
                  label: const Text('Je suis arrivé au départ')),
            if (ride.state == 'driver_arrived') ...[
              TextField(controller: _code, keyboardType: TextInputType.number, maxLength: 6,
                  decoration: const InputDecoration(labelText: 'Code du client (6 chiffres)')),
              FilledButton.icon(onPressed: _busy ? null : () => _act('board'), icon: const Icon(Icons.login),
                  label: const Text('Faire monter le client')),
              TextButton(onPressed: _busy ? null : () => _act('client_absent'), child: const Text('Client absent (après 5 min)')),
            ],
            if (ride.state == 'boarded')
              FilledButton.icon(onPressed: _busy ? null : () => _act('finish'), icon: const Icon(Icons.check_circle_outline),
                  label: const Text('Terminer la course')),
            if (ride.active) OutlinedButton(onPressed: _busy ? null : () => _run(_refreshNow), child: const Text('Actualiser')),
            if ({'awaiting_payment', 'confirmed', 'driver_arrived'}.contains(ride.state))
              TextButton(onPressed: _busy ? null : () => _act('cancel'), child: const Text('Annuler la course (client remboursé)')),
            if (!ride.active) FilledButton(onPressed: _closeRide, child: const Text('Retour aux courses')),
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
