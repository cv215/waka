import 'dart:math';

import 'package:flutter/foundation.dart';

import 'admin_api.dart';
import 'models.dart';

enum AgentStep { phone, smsCode, secondFactor, enrollment, ready }

String randomKey() {
  final random = Random.secure();
  String hex(int count) => List.generate(count, (_) => random.nextInt(16).toRadixString(16)).join();
  return '${hex(8)}-${hex(4)}-4${hex(3)}-${['8', '9', 'a', 'b'][random.nextInt(4)]}${hex(3)}-${hex(12)}';
}

/// Plain-language message for a refusal; never the server's internal detail.
String messageFor(Object error) {
  if (error is ResponseShapeError) return 'La réponse du serveur n’a pas pu être vérifiée. Réessaie.';
  if (error is! ApiProblem) return 'Action impossible. Réessaie.';
  if (error.uncertain) return 'Pas de réponse fiable du serveur. Réessaie la même action : elle ne sera pas appliquée deux fois.';
  return switch (error.code) {
    'INVALID_SECOND_FACTOR' => 'Code à 6 chiffres incorrect ou déjà utilisé. Attends le code suivant.',
    'RATE_LIMITED' => 'Trop d’essais. Patiente quelques minutes.',
    'ACTION_NOT_ALLOWED' => 'Ce compte n’a pas l’habilitation nécessaire.',
    'INVALID_ADMIN_SESSION' => 'La session d’administration a expiré. Reconnecte-toi.',
    'AUTHENTICATION_REQUIRED' => 'La connexion n’est plus valable. Reconnecte-toi.',
    'CONFLICT_OF_INTEREST' => 'Tu ne peux pas examiner ton propre dossier.',
    'APPROVAL_NOT_AVAILABLE' => 'Approbation impossible : une exigence du dossier est encore à confirmer par WAKA.',
    'INCOMPLETE' => 'Approbation impossible : toutes les photos et pièces doivent être jugées acceptables et valides.',
    'VERSION_CONFLICT' => 'Le dossier a changé depuis ta lecture. Relis-le avant de décider.',
    'IDEMPOTENCY_KEY_REUSED' => 'Cette décision ne correspond plus à la précédente tentative. Relis le dossier.',
    'RESOURCE_NOT_FOUND' => 'Élément introuvable dans ton périmètre (dossier, fichier, compte actif ou habilitation).',
    'SERVICE_UNAVAILABLE' => 'Service momentanément indisponible.',
    'SELF_GRANT_FORBIDDEN' => 'Tu ne peux ni t’attribuer ni te retirer une habilitation : un autre agent habilité doit le faire.',
    'LAST_HABILITATIONS_HOLDER' => 'Impossible : il doit toujours rester au moins une personne pouvant gérer les habilitations.',
    _ => error.status == 400 ? 'Demande refusée : vérifie les informations saisies.' : 'Action refusée (${error.status}).',
  };
}

/// Agent login: client session by SMS code, then an active grant and a single-use authenticator code.
/// Tokens live in memory only; reloading the page means logging in again.
class AgentSession extends ChangeNotifier {
  AgentSession(this.api);
  final AdminApi api;
  final String clientRef = randomKey().replaceAll('-', '');
  AgentStep step = AgentStep.phone;
  bool busy = false;
  String? error;
  String? _phone, _requestKey;
  Challenge? _challenge;
  final Map<String, String> _verifyKeys = {};
  ClientSession? _client;
  AdminSession? _admin;
  Enrollment? enrollment;
  String? get destinationHint => _challenge?.hint;
  ClientSession? get client => _client;
  AdminSession? get admin => _admin;
  bool get canExamine => _admin?.functions.contains('ADMISSION') ?? false;
  bool get canManageGrants => _admin?.functions.contains('HABILITATIONS') ?? false;
  bool get canReadAudit => _admin?.functions.contains('AUDIT') ?? false;
  bool get canHandlePrivacy => _admin?.functions.contains('PRIVACY') ?? false;
  bool get canAssist => _admin?.functions.contains('ASSISTANCE') ?? false;
  bool get canFinance => _admin?.functions.contains('FINANCE') ?? false;

  static String? normalizePhone(String input) {
    final compact = input.replaceAll(RegExp(r'[ ()-]'), '');
    final local = compact.startsWith('+237') ? compact.substring(4) : compact.startsWith('00237') ? compact.substring(5) : compact;
    return RegExp(r'^6[0-9]{8}$').hasMatch(local) ? '+237$local' : null;
  }

  Future<void> requestCode(String input) => _run(() async {
    final phone = normalizePhone(input);
    if (phone == null) throw const ApiProblem(400, null);
    // The same request is replayed with the same key until a new number is typed.
    if (phone != _phone) _requestKey = randomKey();
    _phone = phone;
    _challenge = await api.requestCode(phone, clientRef, _requestKey!);
    step = AgentStep.smsCode;
  });

  Future<void> verifySms(String code) => _run(() async {
    if (!RegExp(r'^[0-9]{6}$').hasMatch(code) || _challenge == null) throw const ApiProblem(400, null);
    final key = _verifyKeys.putIfAbsent('${_challenge!.id}:$code', randomKey);
    _client = await api.verifyCode(_challenge!.id, _phone!, code, clientRef, key);
    step = AgentStep.secondFactor;
  });

  Future<void> openWithAuthenticator(String code) => _run(() async {
    _requireCode(code);
    try {
      _admin = await api.openAdmin(_client!, clientRef, code);
      step = AgentStep.ready;
    } on ApiProblem catch (problem) {
      if (problem.code != 'ENROLLMENT_REQUIRED') rethrow;
      enrollment = await api.enroll(_client!, clientRef);
      step = AgentStep.enrollment;
    }
  });

  /// After the authenticator shows codes, the first one confirms it; the next one opens the session.
  Future<void> confirmEnrollment(String code) => _run(() async {
    _requireCode(code);
    await api.confirmFactor(_client!, clientRef, code);
    enrollment = null;
    step = AgentStep.secondFactor;
    error = 'Application d’authentification confirmée. Saisis maintenant le code suivant pour ouvrir l’administration.';
  });

  Future<void> logout() async {
    final client = _client, admin = _admin;
    _forget();
    notifyListeners();
    if (client != null && admin != null) {
      try {
        await api.closeAdmin(client, clientRef, admin);
      } on ApiProblem {
        // The administrative session also expires by itself; nothing is kept locally.
      }
    }
  }

  /// A refused administrative proof sends the agent back to the right step without keeping it.
  void handleSessionLoss(Object problem) {
    if (problem is ApiProblem && (problem.code == 'INVALID_ADMIN_SESSION' || problem.code == 'AUTHENTICATION_REQUIRED')) {
      if (problem.code == 'AUTHENTICATION_REQUIRED') {
        _forget();
      } else {
        _admin = null;
        step = AgentStep.secondFactor;
      }
      error = messageFor(problem);
      notifyListeners();
    }
  }

  void _requireCode(String code) {
    if (!RegExp(r'^[0-9]{6}$').hasMatch(code) || _client == null) throw const ApiProblem(400, null);
  }

  void _forget() {
    _phone = null;
    _requestKey = null;
    _challenge = null;
    _verifyKeys.clear();
    _client = null;
    _admin = null;
    enrollment = null;
    step = AgentStep.phone;
  }

  Future<void> _run(Future<void> Function() action) async {
    if (busy) return;
    busy = true;
    error = null;
    notifyListeners();
    try {
      await action();
    } on Object catch (problem) {
      error = messageFor(problem);
      if (problem is ApiProblem && problem.code == 'AUTHENTICATION_REQUIRED') _forget();
    } finally {
      busy = false;
      notifyListeners();
    }
  }
}
