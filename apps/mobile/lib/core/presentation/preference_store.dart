// Small preferences kept on this device only (theme, recent places): the app's protected store on a phone, the
// browser's local storage on the web. Never sent anywhere.
export 'preference_store_io.dart' if (dart.library.js_interop) 'preference_store_web.dart';
