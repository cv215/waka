import 'package:web/web.dart' as web;

const _key = 'waka_theme';

/// The saved theme name, or null (none saved, or storage blocked: the default theme is used).
Future<String?> readThemeName() async {
  try {
    return web.window.localStorage.getItem(_key);
  } catch (_) {
    return null;
  }
}

Future<void> writeThemeName(String name) async {
  try {
    web.window.localStorage.setItem(_key, name);
  } catch (_) {
    // A theme that is not kept only comes back to the default next time.
  }
}
