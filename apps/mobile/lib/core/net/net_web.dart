import 'dart:async';
import 'dart:convert';

import 'package:http/browser_client.dart';
import 'package:http/http.dart' as http;

/// Browser stand-in for the subset of dart:io HttpClient used by the clients.
/// Redirects are followed by the browser itself; the clients still refuse anything but the expected answers.
abstract class IOException implements Exception {}

class _WebIOException implements IOException {
  const _WebIOException();
  @override
  String toString() => 'IOException';
}

abstract final class HttpHeaders {
  static const acceptHeader = 'accept';
  static const contentTypeHeader = 'content-type';
  static const cacheControlHeader = 'cache-control';
}

class ContentType {
  const ContentType._(this.mimeType, this._value);
  final String mimeType;
  final String _value;
  static const json = ContentType._('application/json', 'application/json; charset=utf-8');
  static ContentType? parse(String? value) =>
      value == null ? null : ContentType._(value.split(';').first.trim().toLowerCase(), value);
  @override
  String toString() => _value;
}

class HttpClient {
  final BrowserClient _client = BrowserClient();
  Duration? connectionTimeout;

  Future<HttpClientRequest> openUrl(String method, Uri url) async => HttpClientRequest._(this, method, url);
  Future<HttpClientRequest> getUrl(Uri url) => openUrl('GET', url);
  Future<HttpClientRequest> postUrl(Uri url) => openUrl('POST', url);

  void close({bool force = false}) => _client.close();
}

class HttpClientRequest {
  HttpClientRequest._(this._owner, this._method, this._url);
  final HttpClient _owner;
  final String _method;
  final Uri _url;
  final HttpRequestHeaders headers = HttpRequestHeaders();
  final List<int> _body = [];
  bool followRedirects = true;
  int contentLength = -1;

  void add(List<int> data) => _body.addAll(data);
  void write(Object? text) => _body.addAll(utf8.encode('$text'));

  Future<HttpClientResponse> close() async {
    final request = http.Request(_method, _url)..followRedirects = followRedirects;
    request.headers.addAll(headers._values);
    if (_body.isNotEmpty) request.bodyBytes = _body;
    try {
      final response = await _owner._client.send(request);
      return HttpClientResponse._(response);
    } on http.ClientException {
      throw const _WebIOException();
    }
  }
}

class HttpRequestHeaders {
  final Map<String, String> _values = {};
  void set(String name, Object value) => _values[name.toLowerCase()] = '$value';
  set contentType(ContentType? type) {
    if (type != null) _values[HttpHeaders.contentTypeHeader] = '$type';
  }
}

class HttpClientResponse extends Stream<List<int>> {
  HttpClientResponse._(this._response) : headers = HttpResponseHeaders(_response.headers);
  final http.StreamedResponse _response;
  final HttpResponseHeaders headers;
  int get statusCode => _response.statusCode;

  @override
  StreamSubscription<List<int>> listen(void Function(List<int>)? onData,
      {Function? onError, void Function()? onDone, bool? cancelOnError}) {
    final stream = _response.stream.handleError((Object _) => throw const _WebIOException(),
        test: (error) => error is http.ClientException);
    return stream.listen(onData, onError: onError, onDone: onDone, cancelOnError: cancelOnError);
  }
}

class HttpResponseHeaders {
  HttpResponseHeaders(this._values);
  final Map<String, String> _values;
  String? value(String name) => _values[name.toLowerCase()];
  ContentType? get contentType => ContentType.parse(value(HttpHeaders.contentTypeHeader));
}
