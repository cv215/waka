// The few HTTP types the clients use: dart:io on Android (unchanged behaviour),
// a browser fetch adapter for the local web preview (iPhone through Safari).
export 'net_io.dart' if (dart.library.js_interop) 'net_web.dart';
