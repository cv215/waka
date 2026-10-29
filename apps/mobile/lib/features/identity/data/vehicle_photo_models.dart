import 'login_models.dart';

class PhotoProblem implements Exception {
  const PhotoProblem(this.failure);
  final PhotoFailure failure;
  @override
  String toString() => 'PhotoProblem(${failure.name})';
}

enum PhotoFailure {
  denied,
  notFound,
  /// The server examined the file and refused it (format, size, dimensions).
  rejectedFile,
  keyReused,
  notEditable,
  tooLarge,
  unsupported,
  unavailable,
  uncertain,
  invalidResponse,
  invalid,
}

const vehiclePhotoViews = ['PH01', 'PH02', 'PH03', 'PH04', 'PH05', 'PH06'];
const vehiclePhotoLabels = {
  'PH01': 'Avant',
  'PH02': 'Arrière avec plaque lisible',
  'PH03': 'Côté gauche',
  'PH04': 'Côté droit',
  'PH05': 'Habitacle avant',
  'PH06': 'Habitacle arrière',
};
const maxPhotoBytes = 10 * 1024 * 1024;

/// JPEG or PNG recognized by their signature bytes; anything else is refused before sending.
String? photoContentType(List<int> bytes) {
  if (bytes.length >= 3 && bytes[0] == 0xFF && bytes[1] == 0xD8 && bytes[2] == 0xFF) return 'image/jpeg';
  const png = [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A];
  if (bytes.length >= png.length && List.generate(png.length, (i) => bytes[i] == png[i]).every((same) => same)) {
    return 'image/png';
  }
  return null;
}

/// One required view: received (a file exists) or missing. Never an examination result.
class PhotoViewStatus {
  const PhotoViewStatus(this.view, this.received, this.fileId);
  final String view;
  final bool received;
  final String? fileId;

  static List<PhotoViewStatus> parseList(Object? value) {
    try {
      if (value is! List || value.length != vehiclePhotoViews.length) throw const FormatException();
      final result = <PhotoViewStatus>[];
      for (var i = 0; i < value.length; i++) {
        final m = exactObject(value[i], {'view', 'state', 'fileId', 'createdAt'});
        final received = m['state'] == 'received';
        if (m['view'] != vehiclePhotoViews[i] ||
            (m['state'] != 'received' && m['state'] != 'missing') ||
            (received != isLoginUuid(m['fileId'])) ||
            (!received && (m['fileId'] != null || m['createdAt'] != null))) {
          throw const FormatException();
        }
        if (received) utcDate(m['createdAt']);
        result.add(PhotoViewStatus(m['view'] as String, received, m['fileId'] as String?));
      }
      return List.unmodifiable(result);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on LoginProblem {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on TypeError {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}

class PhotoUpload {
  const PhotoUpload(this.fileId, this.view, this.replay);
  final String fileId, view;
  final bool replay;

  factory PhotoUpload.parse(Object? value, String applicationId, String view) {
    try {
      final m = exactObject(value, {
        'fileId', 'applicationId', 'view', 'state', 'width', 'height', 'byteSize', 'createdAt', 'idempotentReplay',
      });
      if (!isLoginUuid(m['fileId']) ||
          m['applicationId'] != applicationId ||
          m['view'] != view ||
          (m['state'] != 'received' && m['state'] != 'replaced') ||
          m['width'] is! int || m['height'] is! int || m['byteSize'] is! int ||
          (m['width'] as int) < 320 || (m['height'] as int) < 240 ||
          (m['byteSize'] as int) < 1 ||
          m['idempotentReplay'] is! bool) {
        throw const FormatException();
      }
      utcDate(m['createdAt']);
      return PhotoUpload(m['fileId'] as String, view, m['idempotentReplay'] as bool);
    } on FormatException {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on LoginProblem {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    } on TypeError {
      throw const PhotoProblem(PhotoFailure.invalidResponse);
    }
  }
}
