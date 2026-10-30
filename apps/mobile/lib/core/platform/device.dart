// Whether the app runs on an Android device; always false in the browser build.
export 'device_io.dart' if (dart.library.js_interop) 'device_web.dart';
