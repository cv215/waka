package com.waka.identity.api;

import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
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

/**
 * 06.09 (K19): dated grants and revocations by a HABILITATIONS holder, and A41 audit reading for an
 * AUDIT holder. Naturally idempotent: an identical active grant or an already revoked grant is
 * returned unchanged (200) instead of creating a second effect.
 */
@RestController
public final class AdminGovernanceController {
    private static final Set<String> AUDIT_PARAMETERS = Set.of("objectType", "objectId", "cursor", "limit");
    private final ObjectProvider<PersistentAdminService> services;
    public AdminGovernanceController(ObjectProvider<PersistentAdminService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/admin/grants", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentAdminService.GrantView>> grants(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(AdminAccessController.invoke(() -> service().grants(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @PostMapping(path = "/api/v1/admin/grants", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentAdminService.GrantView> grant(HttpServletRequest request) throws IOException {
        Map<String, String> body = LoginRequestReader.body(request, Set.of("accountId", "function", "reasonCode"));
        UUID accountId = LoginRequestReader.uuid(body.get("accountId"));
        AdminFunction function = function(body.get("function"));
        String reason = body.get("reasonCode");
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            var grant = AdminAccessController.invoke(() -> service().grant(token, client, adminToken, accountId, function, reason));
            return ResponseEntity.status(grant.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED)
                    .cacheControl(CacheControl.noStore()).body(grant);
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @PostMapping(path = "/api/v1/admin/grants/{grantId}/revocations", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentAdminService.GrantView> revoke(@PathVariable String grantId,
            HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(grantId);
        String reason = LoginRequestReader.body(request, Set.of("reasonCode")).get("reasonCode");
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(AdminAccessController.invoke(() -> service().revoke(token, client, adminToken, id, reason)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** A41: {@code cursor} is the id of the last entry already shown; the next page holds older entries. */
    @GetMapping(path = "/api/v1/admin/audit", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<PersistentAdminService.AuditView>> audit(HttpServletRequest request) throws IOException {
        Map<String, String> query = query(request.getQueryString());
        if (request.getInputStream().read() != -1) throw LoginRequestReader.invalid();
        Long cursor = query.containsKey("cursor") ? number(query.get("cursor"), 18) : null;
        int limit = query.containsKey("limit") ? number(query.get("limit"), 3).intValue() : 50;
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminAccessController.invoke(() ->
                    service().audit(token, client, adminToken, query.get("objectType"), query.get("objectId"), cursor, limit)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** Only known, unrepeated, already safe ASCII parameters; no percent-encoding is needed or accepted. */
    static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw == null) return values;
        if (raw.length() > 256) throw LoginRequestReader.invalid();
        for (String pair : raw.split("&", -1)) {
            int equals = pair.indexOf('=');
            if (equals <= 0) throw LoginRequestReader.invalid();
            String name = pair.substring(0, equals), value = pair.substring(equals + 1);
            if (!AUDIT_PARAMETERS.contains(name) || values.containsKey(name) || !value.matches("[A-Za-z0-9_-]{1,128}")) {
                throw LoginRequestReader.invalid();
            }
            values.put(name, value);
        }
        return values;
    }
    private static Long number(String value, int digits) {
        if (!value.matches("[1-9][0-9]{0," + (digits - 1) + "}")) throw LoginRequestReader.invalid();
        return Long.parseLong(value);
    }
    private static AdminFunction function(String value) {
        for (AdminFunction function : AdminFunction.values()) if (function.name().equals(value)) return function;
        throw LoginRequestReader.invalid();
    }
    private PersistentAdminService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
}
