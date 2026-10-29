package com.waka.identity.api;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 05.05 administrative access: client session + active grant + single-use TOTP code.
 * The founder bootstrap stays local maintenance; later grants go through AdminGovernanceController (K19).
 */
@RestController
public final class AdminAccessController {
    public record EnrollmentView(String secret, String otpauthUri) {
        @Override public String toString() { return "EnrollmentView[REDACTED]"; }
    }
    public record AdminSessionView(String adminSessionToken, UUID adminSessionId, List<AdminFunction> functions,
            Instant idleExpiresAt, Instant absoluteExpiresAt, Instant serverTime) {
        @Override public String toString() { return "AdminSessionView[REDACTED]"; }
    }
    public record AdminMeView(UUID accountId, UUID adminSessionId, List<AdminFunction> functions,
            Instant idleExpiresAt, Instant absoluteExpiresAt, Instant serverTime) { }
    static final String ADMIN_HEADER = "X-Waka-Admin-Session";
    private final ObjectProvider<PersistentAdminService> services;
    public AdminAccessController(ObjectProvider<PersistentAdminService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/admin/second-factor/enrollments", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<EnrollmentView> enroll(HttpServletRequest request) throws IOException {
        LoginRequestReader.emptyBody(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var enrollment = invoke(() -> service().enroll(token, client));
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                    .body(new EnrollmentView(enrollment.secret(), enrollment.otpauthUri()));
        } finally { Arrays.fill(token, '\0'); }
    }

    @PostMapping(path = "/api/v1/admin/second-factor/confirmations", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> confirm(HttpServletRequest request) throws IOException {
        String code = code(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            invoke(() -> { service().confirmEnrollment(token, client, code); return Boolean.TRUE; });
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } finally { Arrays.fill(token, '\0'); }
    }

    /** K15: not replayable by design; a lost answer needs a new code and the orphan session expires. */
    @PostMapping(path = "/api/v1/admin/sessions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminSessionView> open(HttpServletRequest request) throws IOException {
        String code = code(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            try (var grant = invoke(() -> service().open(token, client, code))) {
                char[] adminToken = grant.token();
                try {
                    var context = grant.context();
                    return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                            .body(new AdminSessionView(new String(adminToken), context.adminSessionId(), context.functions(),
                                    context.idleExpiresAt(), context.absoluteExpiresAt(), context.serverTime()));
                } finally { Arrays.fill(adminToken, '\0'); }
            }
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/admin/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminMeView> me(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            var context = invoke(() -> service().authenticate(token, client, adminToken));
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new AdminMeView(context.accountId(),
                    context.adminSessionId(), context.functions(), context.idleExpiresAt(), context.absoluteExpiresAt(), context.serverTime()));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    @PostMapping(path = "/api/v1/admin/sessions/current/revocations")
    public ResponseEntity<Void> close(HttpServletRequest request) throws IOException {
        LoginRequestReader.emptyBody(request);
        char[] token = MeRequestReader.bearer(request);
        char[] adminToken = adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            invoke(() -> { service().close(token, client, adminToken); return Boolean.TRUE; });
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }

    /** A malformed code is refused here (400) and never counts as a second-factor failure. */
    private static String code(HttpServletRequest request) throws IOException {
        String code = LoginRequestReader.body(request, Set.of("code")).get("code");
        if (!code.matches("[0-9]{6}")) throw LoginRequestReader.invalid();
        return code;
    }
    static char[] adminToken(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(ADMIN_HEADER);
        if (values == null || !values.hasMoreElements()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String value = values.nextElement();
        if (values.hasMoreElements()) throw LoginRequestReader.invalid();
        if (!value.matches("[A-Za-z0-9_-]{43}")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return value.toCharArray();
    }
    private PersistentAdminService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (AdminException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_AUTHORIZED -> HttpStatus.FORBIDDEN;
                case ENROLLMENT_REQUIRED, ALREADY_ENROLLED -> HttpStatus.CONFLICT;
                case INVALID_SECOND_FACTOR, INVALID_ADMIN_SESSION -> HttpStatus.UNAUTHORIZED;
                case LOCKED -> HttpStatus.TOO_MANY_REQUESTS;
                case BOOTSTRAP_ALREADY_DONE, ACCOUNT_NOT_FOUND, SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                case SELF_GRANT_FORBIDDEN -> HttpStatus.FORBIDDEN;
                case LAST_HABILITATIONS_HOLDER -> HttpStatus.CONFLICT;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
            };
            String code = switch (error.code()) {
                case NOT_AUTHORIZED -> "ACTION_NOT_ALLOWED";
                case LOCKED -> "RATE_LIMITED";
                case BOOTSTRAP_ALREADY_DONE, ACCOUNT_NOT_FOUND -> "SERVICE_UNAVAILABLE";
                case NOT_FOUND -> "RESOURCE_NOT_FOUND";
                default -> error.code().name();
            };
            throw new ProfileHttpException(status, code);
        }
    }
}
