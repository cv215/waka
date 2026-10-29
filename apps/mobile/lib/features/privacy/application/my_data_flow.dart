import 'package:flutter/foundation.dart';

import '../../identity/application/login_flow.dart';
import '../../identity/data/login_client.dart';
import '../../identity/data/login_models.dart';
import '../../identity/data/privacy_models.dart';
import '../../identity/data/vehicle_photo_models.dart';

/// A06/A07 for the confirmed account. A request sent without a reliable answer keeps its key in
/// memory: retrying sends the same request, so the server never records two.
class MyDataFlow extends ChangeNotifier {
  MyDataFlow(this.login, this.gateway) : _sessionId = login.confirmedNow ? login.session?.id : null {
    login.addListener(_loginChanged);
  }
  final LoginFlow login;
  final PrivacyGateway gateway;
  String? _sessionId;
  final List<ActivityItem> _items = [];
  String? _next;
  bool _loaded = false, _closed = false, busy = false;
  ({String key, String kind, String? details})? _pending;
  PrivacyRequestView? lastRequest;
  PhotoFailure? failure;

  List<ActivityItem>? get items => login.confirmedNow && _loaded ? List.unmodifiable(_items) : null;
  bool get hasMore => _next != null;
  /// A request whose answer was lost; [retry] sends exactly the same one again.
  String? get pendingKind => _pending?.kind;

  Future<void> refresh() => _run(() async {
    final (session, client) = _context();
    final page = await gateway.activity(session, client, null);
    if (_still(session)) {
      _items..clear()..addAll(page.items);
      _next = page.nextCursor;
      _loaded = true;
    }
  });

  Future<void> more() => _run(() async {
    if (_next == null) return;
    final (session, client) = _context();
    final page = await gateway.activity(session, client, _next);
    if (_still(session)) {
      _items.addAll(page.items);
      _next = page.nextCursor;
    }
  });

  /// [kind] is `access` or `deletion`; [details] is optional free text.
  Future<void> request(String kind, String? details) => _run(() async {
    if (!privacyKinds.contains(kind)) throw const PhotoProblem(PhotoFailure.invalid);
    final text = details?.trim();
    _pending = (key: randomLoginId(), kind: kind, details: text == null || text.isEmpty ? null : text);
    await _send();
  });

  Future<void> retry() => _run(() async {
    if (_pending == null) throw const PhotoProblem(PhotoFailure.invalid);
    await _send();
  });

  Future<void> _send() async {
    final (session, client) = _context();
    final pending = _pending!;
    try {
      final view = await gateway.requestPrivacy(session, client, pending.key, pending.kind, pending.details);
      _pending = null;
      if (!_still(session)) return;
      lastRequest = view;
    } on PhotoProblem catch (problem) {
      if (problem.failure != PhotoFailure.uncertain && problem.failure != PhotoFailure.unavailable) _pending = null;
      rethrow;
    }
    final page = await gateway.activity(session, client, null);
    if (_still(session)) {
      _items..clear()..addAll(page.items);
      _next = page.nextCursor;
      _loaded = true;
    }
  }

  (LoginSession, String) _context() {
    final session = login.session, client = login.clientInstance;
    if (!login.confirmedNow || session == null || client == null) throw const PhotoProblem(PhotoFailure.invalid);
    return (session, client);
  }

  bool _still(LoginSession session) => !_closed && login.confirmedNow && login.session?.id == session.id;

  Future<void> _run(Future<void> Function() operation) async {
    if (busy || _closed) return;
    busy = true;
    failure = null;
    _changed();
    try {
      await operation();
    } on PhotoProblem catch (problem) {
      failure = problem.failure;
      if (problem.failure == PhotoFailure.denied) await login.checkAccount();
    } finally {
      busy = false;
      _changed();
    }
  }

  void _loginChanged() {
    final current = login.confirmedNow ? login.session?.id : null;
    if (current == _sessionId) return;
    _sessionId = current;
    _items.clear();
    _next = null;
    _loaded = false;
    _pending = null;
    lastRequest = null;
    _changed();
  }

  void _changed() {
    if (!_closed) notifyListeners();
  }

  @override
  void dispose() {
    _closed = true;
    login.removeListener(_loginChanged);
    _pending = null;
    super.dispose();
  }
}
