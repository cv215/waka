import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_application_models.dart';

/// The owner's draft candidature. It shows no admission, no driver role and no access:
/// files, submission and decisions come with their own points.
class DriverApplicationPage extends StatefulWidget {
  const DriverApplicationPage({super.key, required this.flow});
  final LoginFlow flow;

  @override
  State<DriverApplicationPage> createState() => _DriverApplicationPageState();
}

class _DriverApplicationPageState extends State<DriverApplicationPage> {
  LoginFlow get _flow => widget.flow;
  final _brand = TextEditingController(), _model = TextEditingController(), _year = TextEditingController();
  final _color = TextEditingController(), _plate = TextEditingController();
  final _total = TextEditingController(), _seats = TextEditingController();
  String? _usageRight;
  int? _shownVersion;

  @override
  void initState() {
    super.initState();
    if (_flow.application == null && !_flow.noApplication) _reload();
  }

  @override
  void dispose() {
    for (final c in [_brand, _model, _year, _color, _plate, _total, _seats]) {
      c.dispose();
    }
    super.dispose();
  }

  Future<void> _reload() async {
    await _flow.refreshApplication();
    if (mounted) _fill();
  }

  /// Copies the server state into the form only when a new version is shown.
  void _fill() {
    final current = _flow.application;
    if (current == null || current.version == _shownVersion) return;
    final v = current.vehicle;
    _brand.text = v.brand ?? '';
    _model.text = v.model ?? '';
    _year.text = v.year?.toString() ?? '';
    _color.text = v.color ?? '';
    _plate.text = v.registration ?? '';
    _total.text = v.authorizedTotalCapacity?.toString() ?? '';
    _seats.text = v.offeredPassengerSeats?.toString() ?? '';
    setState(() {
      _usageRight = v.usageRight;
      _shownVersion = current.version;
    });
  }

  String? _text(TextEditingController c) => c.text.trim().isEmpty ? null : c.text;
  int? _number(TextEditingController c) => c.text.trim().isEmpty ? null : int.tryParse(c.text.trim()) ?? -1;

  Future<void> _save() async {
    await _flow.saveVehicle(VehicleData(
      brand: _text(_brand),
      model: _text(_model),
      year: _number(_year),
      color: _text(_color),
      registration: _text(_plate),
      authorizedTotalCapacity: _number(_total),
      offeredPassengerSeats: _number(_seats),
      usageRight: _usageRight,
    ));
    if (mounted) _fill();
  }

  String? get _error => switch (_flow.applicationFailure) {
    ApplicationFailure.denied => 'La session est refusée. Les informations locales de connexion ont été effacées.',
    ApplicationFailure.versionConflict => 'Le dossier a changé depuis sa lecture. Il a été relu ; vérifie-le avant de modifier.',
    ApplicationFailure.keyReused => 'La demande enregistrée ne correspond plus au serveur. Le dossier a été relu.',
    ApplicationFailure.replayExpired => 'La reprise de cette demande a expiré. Le dossier a été relu ; vérifie son état.',
    ApplicationFailure.alreadyOpen => 'Un dossier est déjà ouvert pour ce compte. Il a été relu.',
    ApplicationFailure.notFound => 'Ce dossier n’est pas accessible. L’état actuel a été relu.',
    ApplicationFailure.unavailable => 'La candidature est momentanément indisponible. Réessaie plus tard.',
    ApplicationFailure.uncertain => 'La réponse est incertaine. Reprends la même demande pour retrouver son résultat.',
    ApplicationFailure.invalidResponse => 'La réponse n’a pas pu être vérifiée. Reprends la demande ou relis le dossier.',
    ApplicationFailure.storage => 'Le stockage protégé n’a pas accepté la demande. Rien n’a été envoyé.',
    ApplicationFailure.invalid => 'Vérifie les informations : années, capacité et places, immatriculation (lettres, chiffres, espace ou tiret).',
    ApplicationFailure.rejected => 'Le serveur a refusé ces informations sans les enregistrer. Le dossier a été relu.',
    null => null,
  };

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _flow,
    builder: (context, _) {
      final current = _flow.application;
      final busy = _flow.busy;
      return WakaPage(
        title: 'Candidature chauffeur',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Un dossier en brouillon ne donne ni admission ni accès : '
              'les pièces, les six photos et l’examen par un agent viendront ensuite.',
            ),
            const SizedBox(height: 16),
            if (!_flow.confirmedNow)
              const Text('Ta connexion doit d’abord être confirmée sur la page de connexion.')
            else ...[
              if (_flow.pendingApplication != null) ...[
                const Text('Une demande enregistrée attend une reprise. Son résultat doit être connu avant toute autre modification.'),
                FilledButton(
                  onPressed: busy ? null : () async { await _flow.resumeApplication(); if (mounted) _fill(); },
                  child: const Text('Reprendre la demande'),
                ),
              ],
              if (current == null && !_flow.noApplication)
                OutlinedButton(onPressed: busy ? null : _reload, child: const Text('Relire mon dossier')),
              if (_flow.noApplication && _flow.pendingApplication == null) ...[
                const Text('Aucun dossier ouvert. Choisis la catégorie demandée :'),
                const SizedBox(height: 8),
                FilledButton(
                  onPressed: busy || !_flow.canStartApplication ? null : () async { await _flow.startApplication('taxi'); if (mounted) _fill(); },
                  child: const Text('Commencer un dossier taxi'),
                ),
                OutlinedButton(
                  onPressed: busy || !_flow.canStartApplication ? null : () async { await _flow.startApplication('private'); if (mounted) _fill(); },
                  child: const Text('Commencer un dossier particulier'),
                ),
              ],
              if (current != null) ..._form(current, busy),
            ],
            if (_error != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Semantics(liveRegion: true, child: Text(_error!)),
              ),
          ],
        ),
      );
    },
  );

  List<Widget> _form(DriverApplicationSnapshot current, bool busy) {
    final editable = !busy && _flow.canEditVehicle;
    InputDecoration label(String text) => InputDecoration(labelText: text, border: const OutlineInputBorder());
    Widget gap() => const SizedBox(height: 12);
    final digits = [FilteringTextInputFormatter.digitsOnly];
    return [
      Text(
        'Dossier ${current.requestedCategory == 'taxi' ? 'taxi' : 'particulier'} — brouillon, non examiné',
        style: Theme.of(context).textTheme.titleMedium,
      ),
      Text(current.missing.isEmpty
          ? 'Les huit informations du véhicule sont renseignées. Elles seront vérifiées avec les pièces.'
          : 'Informations encore manquantes : ${current.missing.length} sur 8.'),
      gap(),
      TextField(controller: _brand, readOnly: !editable, maxLength: 40, decoration: label('Marque')),
      TextField(controller: _model, readOnly: !editable, maxLength: 40, decoration: label('Modèle')),
      TextField(controller: _year, readOnly: !editable, keyboardType: TextInputType.number, inputFormatters: digits, maxLength: 4, decoration: label('Année')),
      TextField(controller: _color, readOnly: !editable, maxLength: 40, decoration: label('Couleur')),
      TextField(controller: _plate, readOnly: !editable, maxLength: 16, textCapitalization: TextCapitalization.characters, decoration: label('Immatriculation')),
      TextField(controller: _total, readOnly: !editable, keyboardType: TextInputType.number, inputFormatters: digits, maxLength: 2,
          decoration: label('Capacité totale autorisée (chauffeur compris)')),
      TextField(controller: _seats, readOnly: !editable, keyboardType: TextInputType.number, inputFormatters: digits, maxLength: 1,
          decoration: label('Places passagers proposées (5 au maximum)')),
      DropdownButtonFormField<String>(
        initialValue: _usageRight,
        decoration: label('Droit d’utiliser le véhicule'),
        items: const [
          DropdownMenuItem(value: 'holder', child: Text('Je suis titulaire')),
          DropdownMenuItem(value: 'authorized_use', child: Text('J’ai un droit d’utilisation')),
        ],
        onChanged: editable ? (value) => setState(() => _usageRight = value) : null,
      ),
      gap(),
      const Text('Ces informations sont une déclaration : la capacité et le droit réels seront vérifiés avec les pièces.'),
      gap(),
      FilledButton(onPressed: editable ? _save : null, child: const Text('Enregistrer le véhicule')),
    ];
  }
}
