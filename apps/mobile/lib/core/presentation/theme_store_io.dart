import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _storage = FlutterSecureStorage(aOptions: AndroidOptions(storageNamespace: 'waka_preferences'));
const _key = 'waka_theme';

/// The saved theme name, or null (none saved, or the store is unavailable: the default theme is used).
Future<String?> readThemeName() async {
  try {
    return await _storage.read(key: _key);
  } on Exception {
    return null;
  }
}

Future<void> writeThemeName(String name) async {
  try {
    await _storage.write(key: _key, value: name);
  } on Exception {
    // A theme that is not kept only comes back to the default next time.
  }
}
