package com.waka.identity.api;

import com.waka.identity.privacy.PrivacyAdminService;
import com.waka.mobility.voyage.VoyageAssistanceService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

/** K29: PRIVACY handles data requests; ASSISTANCE unlocks voyages after too many wrong boarding codes. */
@RestController
public final class AdminAssistanceController {
    private final ObjectProvider<PrivacyAdminService> privacy;
    private final ObjectProvider<VoyageAssistanceService> voyages;
    public AdminAssistanceController(ObjectProvider<PrivacyAdminService> privacy, ObjectProvider<VoyageAssistanceService> voyages) {
        this.privacy = privacy;
        this.voyages = voyages;
    }

    @GetMapping(path = "/api/v1/admin/privacy-requests", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PrivacyAdminService.RequestItem>> privacyRequests(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(AdminAccessController.invoke(() -> service(privacy).requests(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Body {outcome: completed|refused, reasonCode}. */
    @PostMapping(path = "/api/v1/admin/privacy-requests/{requestId}/closures", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PrivacyAdminService.RequestItem> closePrivacyRequest(@PathVariable String requestId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(requestId);
        Map<String, String> body = LoginRequestReader.body(request, Set.of("outcome", "reasonCode"));
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(AdminAccessController.invoke(() -> service(privacy).close(token, client, adminToken, id, body.get("outcome"), body.get("reasonCode"))));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @GetMapping(path = "/api/v1/admin/locked-voyages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<VoyageAssistanceService.LockedVoyage>> lockedVoyages(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(AdminAccessController.invoke(() -> service(voyages).locked(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Body {reasonCode}. */
    @PostMapping(path = "/api/v1/admin/voyages/{voyageId}/boarding-unlocks", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<VoyageAssistanceService.LockedVoyage> unlock(@PathVariable String voyageId, HttpServletRequest request)
            throws IOException {
        UUID id = LoginRequestReader.uuid(voyageId);
        String reason = LoginRequestReader.body(request, Set.of("reasonCode")).get("reasonCode");
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ok(AdminAccessController.invoke(() -> service(voyages).unlock(token, client, adminToken, id, reason)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    private static <T> T service(ObjectProvider<T> provider) {
        var service = provider.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
