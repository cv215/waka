import 'package:flutter/foundation.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/vehicle_photo_models.dart';

/// Six vehicle views of the confirmed account's own draft. A photo (up to 10 MB) is not kept in the
/// small protected record: a lost answer is retried with the same key and bytes while the app is
/// open; after a restart the person picks the photo again, and the server keeps the history.
class VehiclePhotoFlow extends ChangeNotifier {
  VehiclePhotoFlow(this.login, this.gateway) : _sessionId = login.confirmedNow ? login.session?.id : null {
    login.addListener(_loginChanged);
  }
  final LoginFlow login;
  final VehiclePhotoGateway gateway;
  String? _sessionId;
  List<PhotoViewStatus>? _views;
  final Map<String, ({String key, List<int> bytes, String type, String applicationId})> _pending = {};
  String? busyView;
  bool _closed = false;
  PhotoFailure? failure;
  String? failedView;

  List<PhotoViewStatus>? get views => login.confirmedNow && login.application != null ? _views : null;
  bool hasPending(String view) => _pending.containsKey(view);
  bool get busy => busyView != null;

  Future<void> refresh() => _run(null, () async {
    final (session, client, applicationId) = _context();
    final received = await gateway.listPhotos(session, client, applicationId);
    if (_stillFor(session, applicationId)) _views = received;
  });

  /// A new pick always gets a new key; only a retry of the same bytes reuses one.
  Future<void> upload(String view, List<int> bytes) => _run(view, () async {
    final type = photoContentType(bytes);
    if (!vehiclePhotoViews.contains(view)) throw const PhotoProblem(PhotoFailure.invalid);
    if (type == null) throw const PhotoProblem(PhotoFailure.unsupported);
    if (bytes.length > maxPhotoBytes) throw const PhotoProblem(PhotoFailure.tooLarge);
    final (_, _, applicationId) = _context();
    _pending[view] = (key: randomLoginId(), bytes: List.unmodifiable(bytes), type: type, applicationId: applicationId);
    await _send(view);
  });

  Future<void> retry(String view) => _run(view, () async {
    if (!_pending.containsKey(view)) throw const PhotoProblem(PhotoFailure.invalid);
    await _send(view);
  });

  Future<void> _send(String view) async {
    final (session, client, applicationId) = _context();
    final pending = _pending[view]!;
    if (pending.applicationId != applicationId) {
      _pending.remove(view);
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    try {
      await gateway.uploadPhoto(session, client, applicationId, view, pending.key, pending.bytes, pending.type);
    } on PhotoProblem catch (problem) {
      // Unknown outcomes keep the same key and bytes for a retry; every other answer is final.
      if (problem.failure != PhotoFailure.uncertain && problem.failure != PhotoFailure.unavailable) {
        _pending.remove(view);
      }
      rethrow;
    }
    _pending.remove(view);
    if (!_stillFor(session, applicationId)) return;
    final received = await gateway.listPhotos(session, client, applicationId);
    if (_stillFor(session, applicationId)) _views = received;
  }

  (LoginSession, String, String) _context() {
    final session = login.session, client = login.clientInstance, application = login.application;
    if (!login.confirmedNow || session == null || client == null || application == null) {
      throw const PhotoProblem(PhotoFailure.invalid);
    }
    return (session, client, application.applicationId);
  }

  bool _stillFor(LoginSession session, String applicationId) =>
      !_closed && login.confirmedNow && login.session?.id == session.id && login.application?.applicationId == applicationId;

  Future<void> _run(String? view, Future<void> Function() operation) async {
    if (busyView != null || _closed) return;
    busyView = view ?? '';
    failure = null;
    failedView = null;
    _changed();
    try {
      await operation();
    } on PhotoProblem catch (problem) {
      failure = problem.failure;
      failedView = view;
      if (problem.failure == PhotoFailure.denied) await login.checkAccount();
    } finally {
      busyView = null;
      _changed();
    }
  }

  /// Another session or a lost confirmation never inherits photos or retries. A brief re-read of the
  /// draft (application momentarily null) is not a change of session and keeps pending retries;
  /// a retry bound to another draft is refused in [_send].
  void _loginChanged() {
    final current = login.confirmedNow ? login.session?.id : null;
    if (current == _sessionId) return;
    _sessionId = current;
    _views = null;
    _pending.clear();
    _changed();
  }

  void _changed() {
    if (!_closed) notifyListeners();
  }

  @override
  void dispose() {
    _closed = true;
    login.removeListener(_loginChanged);
    _pending.clear();
    super.dispose();
  }
}
