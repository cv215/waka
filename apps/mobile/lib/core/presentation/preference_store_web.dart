import 'package:web/web.dart' as web;

/// The saved value, or null (none saved, or storage blocked: the default is used).
Future<String?> readPreference(String key) async {
  try {
    return web.window.localStorage.getItem(key);
  } catch (_) {
    return null;
  }
}

Future<void> writePreference(String key, String value) async {
  try {
    web.window.localStorage.setItem(key, value);
  } catch (_) {
    // A preference that is not kept only comes back to its default next time.
  }
}
