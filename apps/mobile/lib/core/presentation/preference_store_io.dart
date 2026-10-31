import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _storage = FlutterSecureStorage(aOptions: AndroidOptions(storageNamespace: 'waka_preferences'));

/// The saved value, or null (none saved, or the store is unavailable: the default is used).
Future<String?> readPreference(String key) async {
  try {
    return await _storage.read(key: key);
  } on Exception {
    return null;
  }
}

Future<void> writePreference(String key, String value) async {
  try {
    await _storage.write(key: key, value: value);
  } on Exception {
    // A preference that is not kept only comes back to its default next time.
  }
}
