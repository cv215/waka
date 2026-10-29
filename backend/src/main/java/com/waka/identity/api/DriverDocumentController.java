package com.waka.identity.api;

import com.waka.identity.admission.DocumentCatalog;
import com.waka.identity.files.DriverDocumentService;
import com.waka.identity.files.FileException;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Enumeration;
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

/**
 * K18: catalogue documents as a raw image of the paper document (JPEG or PNG), optional end of
 * validity in {@code X-Waka-Valid-Until} (yyyy-MM-dd). Owner reads; ADMISSION reads are audited.
 */
@RestController
public final class DriverDocumentController {
    static final String VALID_UNTIL = "X-Waka-Valid-Until";
    private final ObjectProvider<DriverDocumentService> services;
    public DriverDocumentController(ObjectProvider<DriverDocumentService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/driver-applications/{applicationId}/documents/{documentType}",
            consumes = { MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DriverDocumentService.DocumentView> upload(@PathVariable String applicationId,
            @PathVariable String documentType, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(applicationId);
        if (DocumentCatalog.byCode(documentType) == null || request.getQueryString() != null) throw LoginRequestReader.invalid();
        UUID key = LoginRequestReader.key(request);
        LocalDate validUntil = validUntil(request);
        String type = VehiclePhotoController.imageType(request);
        byte[] body = VehiclePhotoController.imageBody(request);
        try {
            char[] token = MeRequestReader.bearer(request);
            try {
                String client = MeRequestReader.client(request);
                var document = invoke(() -> service().upload(token, client, id, documentType, key, type, body, validUntil));
                return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(document);
            } finally { Arrays.fill(token, '\0'); }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    @GetMapping(path = "/api/v1/driver-documents/{documentId}/content", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> content(@PathVariable String documentId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(documentId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return image(invoke(() -> service().content(token, client, id)).jpeg());
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/admin/driver-documents/{documentId}/content", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> examinationContent(@PathVariable String documentId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(documentId);
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return image(invoke(() -> service().examinationContent(token, client, adminToken, id)).jpeg());
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Absent header means "no end date printed"; the agent checks the date against the document. */
    private static LocalDate validUntil(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(VALID_UNTIL);
        if (values == null || !values.hasMoreElements()) return null;
        String value = values.nextElement();
        if (values.hasMoreElements() || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw LoginRequestReader.invalid();
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException hidden) { throw LoginRequestReader.invalid(); }
    }
    private static ResponseEntity<byte[]> image(byte[] jpeg) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG)
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox").body(jpeg);
    }
    private DriverDocumentService service() {
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
            throw VehiclePhotoController.fileError(error);
        }
    }
}
