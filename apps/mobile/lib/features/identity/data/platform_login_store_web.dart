import 'dart:convert';

import 'package:web/web.dart' as web;

import 'login_models.dart';
import 'login_store.dart';

LoginStore platformLoginStore() => SessionTabLoginStore();

/// Local web preview only (dev build, test data): the record lives in the browser tab and is gone when the tab closes.
/// A plain http page on the home network has no WebCrypto, so the Android protected store cannot be used here.
class SessionTabLoginStore implements LoginStore {
  static const _key = 'waka.identity.v1';

  @override
  Future<LoginSnapshot?> read() async {
    try {
      final raw = web.window.sessionStorage.getItem(_key);
      return raw == null ? null : LoginSnapshot.parse(raw);
    } on Exception {
      throw const LoginProblem(LoginFailure.storage);
    }
  }

  @override
  Future<void> write(LoginSnapshot snapshot) async {
    try {
      web.window.sessionStorage.setItem(_key, jsonEncode(snapshot.toJson()));
    } on Exception {
      throw const LoginProblem(LoginFailure.storage);
    }
  }
}
