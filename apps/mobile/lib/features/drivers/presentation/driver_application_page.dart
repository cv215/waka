import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_application_models.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../application/vehicle_photo_flow.dart';

/// Returns the chosen photo's bytes, or null when the person cancels.
typedef PhotoPicker = Future<List<int>?> Function({required bool camera});

/// Bounded on the phone to keep uploads small; the server still checks and re-encodes everything.
Future<List<int>?> pickWithDevice({required bool camera}) async {
  final file = await ImagePicker().pickImage(
    source: camera ? ImageSource.camera : ImageSource.gallery,
    maxWidth: 3000,
    maxHeight: 3000,
    imageQuality: 85,
  );
  return file?.readAsBytes();
}

/// The owner's draft candidature. It shows no admission, no driver role and no access:
/// documents, submission and decisions come with their own points.
class DriverApplicationPage extends StatefulWidget {
  const DriverApplicationPage({super.key, required this.flow, this.pickPhoto = pickWithDevice});
  final LoginFlow flow;
  final PhotoPicker pickPhoto;

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
  late final VehiclePhotoFlow? _photos =
      _flow.gateway is VehiclePhotoGateway ? VehiclePhotoFlow(_flow, _flow.gateway as VehiclePhotoGateway) : null;

  @override
  void initState() {
    super.initState();
    if (_flow.application == null && !_flow.noApplication) {
      _reload();
    } else if (_flow.application != null) {
      _photos?.refresh();
    }
  }

  @override
  void dispose() {
    for (final c in [_brand, _model, _year, _color, _plate, _total, _seats]) {
      c.dispose();
    }
    _photos?.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    await _flow.refreshApplication();
    if (mounted) _fill();
    if (mounted && _flow.application != null) await _photos?.refresh();
  }

  Future<void> _pick(String view, {required bool camera}) async {
    final bytes = await widget.pickPhoto(camera: camera);
    if (bytes != null && mounted) await _photos?.upload(view, bytes);
  }

  String? get _photoError => switch (_photos?.failure) {
    PhotoFailure.denied => 'La session est refusée. Reconnecte-toi.',
    PhotoFailure.notFound => 'Ce dossier n’est pas accessible.',
    PhotoFailure.rejectedFile => 'Cette photo a été refusée : utilise une photo JPEG ou PNG nette, d’au moins 320 × 240 pixels.',
    PhotoFailure.keyReused => 'Cette demande ne correspond plus au serveur. Choisis la photo à nouveau.',
    PhotoFailure.notEditable => 'Le dossier ne peut plus être modifié.',
    PhotoFailure.tooLarge => 'La photo dépasse 10 Mo. Choisis une photo plus légère.',
    PhotoFailure.unsupported => 'Format non accepté : seules les photos JPEG ou PNG sont possibles.',
    PhotoFailure.unavailable => 'L’envoi des photos est momentanément indisponible. Réessaie plus tard.',
    PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie le même envoi pour connaître son résultat.',
    PhotoFailure.invalidResponse => 'La réponse n’a pas pu être vérifiée. Réessaie ou relis l’état des photos.',
    PhotoFailure.invalid => 'Cette action n’est pas possible maintenant.',
    null => null,
  };

  List<Widget> _photoSection() {
    final photos = _photos;
    if (photos == null) return const [];
    return [
      const Divider(height: 32),
      Text('Photos du véhicule', style: Theme.of(context).textTheme.titleMedium),
      const Text('Six vues du même véhicule. Une photo reçue n’est pas encore vérifiée par un agent.'),
      const SizedBox(height: 8),
      AnimatedBuilder(
        animation: photos,
        builder: (context, _) {
          final views = photos.views;
          if (views == null) {
            return OutlinedButton(onPressed: photos.busy ? null : photos.refresh, child: const Text('Relire l’état des photos'));
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final status in views)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(status.received ? Icons.check_circle_outline : Icons.radio_button_unchecked),
                  title: Text('${status.view} — ${vehiclePhotoLabels[status.view]}'),
                  subtitle: Text(photos.busyView == status.view
                      ? 'Envoi en cours…'
                      : status.received ? 'Reçue, non examinée' : 'Manquante'),
                  trailing: Wrap(spacing: 4, children: [
                    if (photos.hasPending(status.view))
                      IconButton(
                        tooltip: 'Réessayer l’envoi',
                        onPressed: photos.busy ? null : () => photos.retry(status.view),
                        icon: const Icon(Icons.refresh),
                      ),
                    IconButton(
                      tooltip: 'Prendre une photo',
                      onPressed: photos.busy ? null : () => _pick(status.view, camera: true),
                      icon: const Icon(Icons.photo_camera_outlined),
                    ),
                    IconButton(
                      tooltip: 'Choisir dans la galerie',
                      onPressed: photos.busy ? null : () => _pick(status.view, camera: false),
                      icon: const Icon(Icons.photo_library_outlined),
                    ),
                  ]),
                ),
              if (_photoError != null)
                Semantics(liveRegion: true, child: Text(_photoError!)),
            ],
          );
        },
      ),
    ];
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
    if (_photos?.views == null) _photos?.refresh();
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
      ..._photoSection(),
    ];
  }
}
