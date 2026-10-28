package com.waka.identity.api;

import com.waka.identity.files.FileException;
import com.waka.identity.files.PhotoSanitizer;
import com.waka.identity.files.VehiclePhotoService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** K16: raw-body photo upload and owner-only reads. No public URL, no sharing link, never an approval. */
@RestController
public final class VehiclePhotoController {
    private final ObjectProvider<VehiclePhotoService> services;
    public VehiclePhotoController(ObjectProvider<VehiclePhotoService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/driver-applications/{applicationId}/photos/{view}",
            consumes = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VehiclePhotoService.PhotoView> upload(@PathVariable String applicationId, @PathVariable String view,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        if (!VehiclePhotoService.VIEWS.contains(view) || request.getQueryString() != null) throw LoginRequestReader.invalid();
        UUID key = LoginRequestReader.key(request);
        String type = contentType(request);
        byte[] body = rawBody(request);
        try {
            char[] token = MeRequestReader.bearer(request);
            try {
                String client = MeRequestReader.client(request);
                var photo = invoke(() -> service().upload(token, client, id, view, key, type, body));
                return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(photo);
            } finally { Arrays.fill(token, '\0'); }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    @GetMapping(path = "/api/v1/driver-applications/{applicationId}/photos", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<VehiclePhotoService.ViewStatus>> list(@PathVariable String applicationId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().list(token, client, id)));
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/private-files/{fileId}/content", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> content(@PathVariable String fileId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(fileId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            byte[] jpeg = invoke(() -> service().content(token, client, id)).jpeg();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                    .header("X-Content-Type-Options", "nosniff")
                    .header("Content-Security-Policy", "default-src 'none'; sandbox").body(jpeg);
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exact type only; the signature bytes are checked again by the sanitizer. */
    private static String contentType(HttpServletRequest request) {
        String type = LoginRequestReader.singleHeader(request, "Content-Type");
        if (!type.equals(MediaType.IMAGE_JPEG_VALUE) && !type.equals(MediaType.IMAGE_PNG_VALUE)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        return type;
    }
    private static byte[] rawBody(HttpServletRequest request) throws IOException {
        Enumeration<String> encodings = request.getHeaders("Content-Encoding");
        if (encodings != null && encodings.hasMoreElements()) {
            String value = encodings.nextElement();
            if (encodings.hasMoreElements() || !"identity".equalsIgnoreCase(value)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }
        byte[] body = request.getInputStream().readNBytes(PhotoSanitizer.MAX_BYTES + 1);
        if (body.length > PhotoSanitizer.MAX_BYTES) {
            Arrays.fill(body, (byte) 0);
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
        }
        return body;
    }
    private VehiclePhotoService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (FileException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST, INVALID_FILE -> HttpStatus.BAD_REQUEST;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case IDEMPOTENCY_KEY_REUSED, NOT_EDITABLE -> HttpStatus.CONFLICT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            String code = switch (error.code()) {
                case NOT_FOUND -> "RESOURCE_NOT_FOUND";
                case INVALID_FILE -> "FILE_REJECTED";
                default -> error.code().name();
            };
            throw new ProfileHttpException(status, code);
        }
    }
}
