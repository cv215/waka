import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/presentation/waka_page.dart';
import '../../identity/application/login_flow.dart';
import '../../identity/data/driver_application_models.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/vehicle_photo_models.dart';
import '../../identity/data/driver_document_models.dart';
import '../application/driver_document_flow.dart';
import '../application/vehicle_photo_flow.dart';

/// Returns the chosen photo's bytes, or null when the person cancels.
typedef PhotoPicker = Future<List<int>?> Function({required bool camera});
/// Asks for the end date printed on a document; null when there is none (or the person skips it).
typedef ValidityPicker = Future<DateTime?> Function(BuildContext context);

Future<DateTime?> pickValidity(BuildContext context) {
  final today = DateTime.now();
  return showDatePicker(
    context: context,
    firstDate: today,
    lastDate: DateTime(today.year + 20),
    helpText: 'Date de fin de validité (Annuler s’il n’y en a pas)',
  );
}

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

/// The owner's candidature (step 4 of the redesign): while it can be edited, a guided path of four steps — vehicle,
/// six photos, documents, sending — with a progress bar; once sent, where it stands and the last decision. It shows no
/// admission, no driver role and no access by itself.
class DriverApplicationPage extends StatefulWidget {
  const DriverApplicationPage({super.key, required this.flow, this.pickPhoto = pickWithDevice,
      this.pickDate = pickValidity, this.initialStep = 0});
  final LoginFlow flow;
  /// The step shown first (0 vehicle, 1 photos, 2 documents, 3 sending).
  final int initialStep;
  final PhotoPicker pickPhoto;
  final ValidityPicker pickDate;

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
  /// The step shown while the file can be edited: 0 vehicle, 1 photos, 2 documents, 3 sending.
  late int _step = widget.initialStep.clamp(0, 3);
  static const _steps = ['Ton véhicule', 'Photos du véhicule', 'Tes pièces', 'Envoi'];
  late final VehiclePhotoFlow? _photos =
      _flow.gateway is VehiclePhotoGateway ? VehiclePhotoFlow(_flow, _flow.gateway as VehiclePhotoGateway) : null;
  late final DriverDocumentFlow? _documents =
      _flow.gateway is DriverDocumentGateway ? DriverDocumentFlow(_flow, _flow.gateway as DriverDocumentGateway) : null;

  @override
  void initState() {
    super.initState();
    if (_flow.application == null && !_flow.noApplication) {
      _reload();
    } else if (_flow.application != null) {
      _refreshParts();
    }
  }

  @override
  void dispose() {
    for (final c in [_brand, _model, _year, _color, _plate, _total, _seats]) {
      c.dispose();
    }
    _photos?.dispose();
    _documents?.dispose();
    super.dispose();
  }

  Future<void> _reload() async {
    await _flow.refreshApplication();
    if (mounted) _fill();
    if (mounted && _flow.application != null) await _refreshParts();
  }

  /// Photos, applicable documents and decisions follow the file state (category, use right, version).
  Future<void> _refreshParts() async {
    await _photos?.refresh();
    if (mounted) await _documents?.refresh();
  }

  Future<void> _pickDocument(String code, {required bool camera}) async {
    final bytes = await widget.pickPhoto(camera: camera);
    if (bytes == null || !mounted) return;
    final validUntil = await widget.pickDate(context);
    if (mounted) await _documents?.upload(code, bytes, validUntil);
  }

  Future<void> _submit() async {
    await _flow.submitApplication();
    if (mounted) {
      _fill();
      await _documents?.refresh();
    }
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
    PhotoFailure.notApplicable => 'Cette pièce n’est pas demandée pour ta situation.',
    PhotoFailure.registrationRequired => 'Renseigne d’abord l’immatriculation du véhicule.',
    PhotoFailure.expired => 'La date de fin de validité est déjà passée : il faut une pièce en cours de validité.',
    PhotoFailure.selfReferral || PhotoFailure.codeNotRecognized || PhotoFailure.referrerAlreadySet ||
    PhotoFailure.driverNotAllowed || PhotoFailure.notEnoughSeats || PhotoFailure.ownVoyage || PhotoFailure.departed ||
    PhotoFailure.tooManySeats || PhotoFailure.wrongCode || PhotoFailure.notPaid || PhotoFailure.boardingLocked ||
    PhotoFailure.mutualReferral || PhotoFailure.referralWindowClosed || PhotoFailure.tooManyAttempts || PhotoFailure.tooEarly ||
    PhotoFailure.notPayable || PhotoFailure.insufficientCredit || PhotoFailure.creditLimit || PhotoFailure.phoneNotAccount ||
    PhotoFailure.outsideServiceArea || PhotoFailure.noRoute || PhotoFailure.tooFar =>
      'Cette action n’est pas possible maintenant.',
    null => null,
  };

  List<Widget> _photoSection() {
    final photos = _photos;
    if (photos == null) return const [];
    return [
      const Text('Six vues du même véhicule : avant, arrière avec la plaque, les deux côtés, l’intérieur.'),
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
                    if (_flow.application?.editable ?? false) ...[
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
                    ],
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
    ApplicationFailure.incomplete => 'Le dossier n’est pas encore complet : vérifie les informations, les six photos et les pièces marquées « à fournir ».',
    ApplicationFailure.notEditable => 'Le dossier est en cours d’examen ou fermé : il ne peut pas être modifié maintenant.',
    null => null,
  };

  @override
  Widget build(BuildContext context) => AnimatedBuilder(
    animation: _flow,
    builder: (context, _) {
      final current = _flow.application;
      final busy = _flow.busy;
      return WakaPage(
        title: current == null ? 'Candidature chauffeur'
            : 'Chauffeur ${current.requestedCategory == 'taxi' ? 'taxi' : 'particulier'}',
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
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
              if (current != null && current.editable) ..._wizard(current, busy),
              if (current != null && !current.editable) ..._status(current),
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

  /// The step header: « Étape n sur 4 », a four-part bar, the step title.
  List<Widget> _header() {
    final theme = Theme.of(context);
    return [
      Text('Étape ${_step + 1} sur 4', style: theme.textTheme.bodySmall),
      const SizedBox(height: 6),
      Row(children: [
        for (var i = 0; i < 4; i++) ...[
          if (i > 0) const SizedBox(width: 4),
          Expanded(child: Container(height: 5, decoration: BoxDecoration(borderRadius: BorderRadius.circular(3),
              color: i <= _step ? theme.colorScheme.primary : theme.colorScheme.outlineVariant))),
        ],
      ]),
      const SizedBox(height: 14),
      Semantics(header: true, child: Text(_steps[_step],
          style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700))),
      const SizedBox(height: 12),
    ];
  }

  Widget _nav({required VoidCallback? next, String label = 'Continuer'}) => Padding(
    padding: const EdgeInsets.only(top: 20),
    child: Row(children: [
      if (_step > 0) ...[
        Expanded(child: OutlinedButton(onPressed: () => setState(() => _step--), child: const Text('Retour'))),
        const SizedBox(width: 10),
      ],
      Expanded(flex: 2, child: FilledButton(onPressed: next, child: Text(label))),
    ]),
  );

  List<Widget> _wizard(DriverApplicationSnapshot current, bool busy) {
    final photos = _photos?.views;
    final photosMissing = photos == null ? 6 : photos.where((v) => !v.received).length;
    return [
      ..._header(),
      if (current.state == 'correction_requested') ...[
        Text('Un agent demande une correction : ${_lastDecision() ?? 'voir les pièces'}',
            style: TextStyle(color: Theme.of(context).colorScheme.error)),
        const SizedBox(height: 12),
      ],
      ...switch (_step) {
        0 => [..._vehicle(current, busy), _nav(next: busy || !_flow.canEditVehicle ? null : () async {
            await _save();
            if (mounted && _flow.applicationFailure == null && (_flow.application?.missing.isEmpty ?? false)) setState(() => _step = 1);
          })],
        1 => [..._photoSection(), _nav(next: photosMissing == 0 ? () => setState(() => _step = 2) : null,
            label: photosMissing == 0 ? 'Continuer' : 'Encore $photosMissing photo(s)')],
        2 => [..._documentSection(current), _nav(next: () => setState(() => _step = 3))],
        _ => [..._sending(current), _sendNav(current)],
      },
    ];
  }

  String? _lastDecision() {
    final decisions = _documents?.decisions ?? const <DecisionView>[];
    if (decisions.isEmpty) return null;
    final last = decisions.last;
    return last.details ?? last.reasonCode;
  }

  /// Sending: what is still missing, or the button that sends the file to an agent.
  List<Widget> _sending(DriverApplicationSnapshot current) {
    final missing = _documents?.missingForSubmission ?? const [];
    final photosMissing = (_photos?.views ?? const []).where((v) => !v.received).length;
    final ready = current.missing.isEmpty && photosMissing == 0 && missing.isEmpty && _photos?.views != null;
    return [Text(ready
        ? 'Tout est prêt. Un agent examine ton dossier ; tu verras sa décision ici.'
        : 'Il manque encore : ${[
            if (current.missing.isNotEmpty) '${current.missing.length} information(s) du véhicule',
            if (photosMissing > 0) '$photosMissing photo(s)',
            if (missing.isNotEmpty) '${missing.length} pièce(s)',
          ].join(', ')}.')];
  }

  Widget _sendNav(DriverApplicationSnapshot current) {
    final missing = _documents?.missingForSubmission ?? const [];
    final photosMissing = (_photos?.views ?? const []).where((v) => !v.received).length;
    final ready = current.missing.isEmpty && photosMissing == 0 && missing.isEmpty && _photos?.views != null;
    return _nav(next: ready && !_flow.busy && _flow.canEditVehicle ? _submit : null, label: 'Envoyer mon dossier');
  }

  /// Once sent: where the file stands and the last decision.
  List<Widget> _status(DriverApplicationSnapshot current) {
    final theme = Theme.of(context);
    final decisions = _documents?.decisions ?? const <DecisionView>[];
    return [
      Text(switch (current.state) {
        'submitted' => 'Dossier envoyé',
        'approved' => 'Dossier approuvé',
        'refused' => 'Dossier refusé',
        'replaced' => 'Dossier remplacé',
        _ => _stateLabel(current.state),
      }, style: theme.textTheme.headlineSmall?.copyWith(fontWeight: FontWeight.w700)),
      const SizedBox(height: 8),
      Text(switch (current.state) {
        'submitted' => 'Un agent examine ton dossier. Tu verras sa décision ici.',
        'approved' => 'Tu peux passer en mode chauffeur. L’accès dépend aussi de la validité de tes pièces et, pour un particulier, de l’abonnement.',
        _ => '',
      }),
      if (decisions.isNotEmpty) ...[
        const SizedBox(height: 16),
        Text('Dernière décision', style: theme.textTheme.titleSmall),
        Text('${switch (decisions.last.action) {
          'approve' => 'Approuvé',
          'refuse' => 'Refusé',
          _ => 'Correction demandée',
        }} — motif : ${decisions.last.reasonCode}'),
        if (decisions.last.details != null) Text(decisions.last.details!),
      ],
    ];
  }

  List<Widget> _vehicle(DriverApplicationSnapshot current, bool busy) {
    final editable = !busy && _flow.canEditVehicle;
    InputDecoration label(String text) => InputDecoration(labelText: text, border: const OutlineInputBorder());
    Widget gap() => const SizedBox(height: 12);
    final digits = [FilteringTextInputFormatter.digitsOnly];
    return [
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
      const Text('Une déclaration : la capacité et le droit réels sont vérifiés avec les pièces.'),
    ];
  }

  static String _stateLabel(String state) => switch (state) {
    'draft' => 'brouillon, non envoyé',
    'submitted' => 'envoyé, en attente d’examen',
    'correction_requested' => 'correction demandée',
    'refused' => 'refusé',
    'approved' => 'approuvé',
    _ => state,
  };

  static String _reviewLabel(RequirementView r) {
    if (!r.established && r.documentId == null) return 'En cours de confirmation par WAKA : facultatif pour l’instant';
    if (r.documentId == null && r.established && !r.requiredForSubmission) {
      return 'À fournir : tu peux envoyer ton dossier sans elle, WAKA décidera';
    }
    if (r.documentId == null) return 'À fournir';
    if (r.expired) return 'Expirée : à fournir de nouveau';
    final state = switch (r.reviewState) {
      'accepted' => 'Acceptée',
      'to_correct' => 'À corriger : dépose une nouvelle pièce',
      _ => 'Reçue, en attente d’examen',
    };
    final until = r.validUntil == null ? '' : ' (valable jusqu’au ${dayText(r.validUntil!)})';
    return r.established ? '$state$until' : '$state$until — exigence en cours de confirmation par WAKA';
  }

  String? get _documentError {
    final failure = _documents?.failure;
    if (failure == null) return null;
    return switch (failure) {
      PhotoFailure.rejectedFile => 'Cette image a été refusée : photographie le document en entier, net et lisible (JPEG ou PNG).',
      PhotoFailure.uncertain => 'La réponse n’est pas arrivée. Réessaie le même envoi pour connaître son résultat.',
      _ => _photoErrorFor(failure),
    };
  }

  String? _photoErrorFor(PhotoFailure failure) => switch (failure) {
    PhotoFailure.notApplicable => 'Cette pièce n’est pas demandée pour ta situation.',
    PhotoFailure.registrationRequired => 'Renseigne d’abord l’immatriculation du véhicule.',
    PhotoFailure.expired => 'La date de fin de validité est déjà passée : il faut une pièce en cours de validité.',
    PhotoFailure.selfReferral || PhotoFailure.codeNotRecognized || PhotoFailure.referrerAlreadySet ||
    PhotoFailure.driverNotAllowed || PhotoFailure.notEnoughSeats || PhotoFailure.ownVoyage || PhotoFailure.departed ||
    PhotoFailure.tooManySeats || PhotoFailure.wrongCode || PhotoFailure.notPaid || PhotoFailure.boardingLocked ||
    PhotoFailure.mutualReferral || PhotoFailure.referralWindowClosed || PhotoFailure.tooManyAttempts || PhotoFailure.tooEarly ||
    PhotoFailure.notPayable || PhotoFailure.insufficientCredit || PhotoFailure.creditLimit || PhotoFailure.phoneNotAccount ||
    PhotoFailure.outsideServiceArea || PhotoFailure.noRoute || PhotoFailure.tooFar =>
      'Cette action n’est pas possible maintenant.',
    PhotoFailure.tooLarge => 'L’image dépasse 10 Mo. Choisis une image plus légère.',
    PhotoFailure.unsupported => 'Format non accepté : seules les images JPEG ou PNG sont possibles.',
    PhotoFailure.notEditable => 'Le dossier ne peut plus être modifié.',
    PhotoFailure.unavailable => 'Les pièces sont momentanément indisponibles. Réessaie plus tard.',
    _ => 'Cette action n’a pas abouti. Relis l’état des pièces.',
  };

  List<Widget> _documentSection(DriverApplicationSnapshot current) {
    final documents = _documents;
    if (documents == null) return const [];
    return [
      const Text('Seules les pièces de ta situation sont demandées. Une pièce déjà acceptée et encore valide est réutilisée.'),
      const SizedBox(height: 8),
      AnimatedBuilder(
        animation: documents,
        builder: (context, _) {
          final requirements = documents.requirements;
          if (requirements == null) {
            return OutlinedButton(onPressed: documents.busy ? null : documents.refresh, child: const Text('Relire mes pièces'));
          }
          final editable = current.editable && !_flow.busy;
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              for (final r in requirements)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(r.document, style: Theme.of(context).textTheme.titleSmall),
                        Text(r.explanation),
                        const SizedBox(height: 4),
                        Text(documents.busyCode == r.code ? 'Envoi en cours…' : _reviewLabel(r)),
                        if (editable && (r.needsUpload || r.reviewState == 'received'))
                          Wrap(spacing: 4, children: [
                            if (documents.hasPending(r.code))
                              TextButton.icon(
                                onPressed: documents.busy ? null : () => documents.retry(r.code),
                                icon: const Icon(Icons.refresh), label: const Text('Réessayer'),
                              ),
                            TextButton.icon(
                              onPressed: documents.busy ? null : () => _pickDocument(r.code, camera: true),
                              icon: const Icon(Icons.photo_camera_outlined), label: const Text('Photographier'),
                            ),
                            TextButton.icon(
                              onPressed: documents.busy ? null : () => _pickDocument(r.code, camera: false),
                              icon: const Icon(Icons.photo_library_outlined), label: const Text('Choisir une image'),
                            ),
                          ]),
                      ],
                    ),
                  ),
                ),
              if (_documentError != null) Semantics(liveRegion: true, child: Text(_documentError!)),
            ],
          );
        },
      ),
    ];
  }
}
