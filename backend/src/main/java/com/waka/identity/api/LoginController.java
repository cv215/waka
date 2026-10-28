package com.waka.identity.api;

import com.waka.identity.application.CameroonMobilePhoneFormat;
import com.waka.identity.application.LoginService;
import com.waka.identity.otp.OtpException;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.json.JsonFactory;

/** HTTP boundary; an absent qualified LoginService fails closed with 503, never a fake login. */
@RestController
public final class LoginController {
    private static final JsonFactory JSON = new JsonFactory();
    private final ObjectProvider<LoginService> services;

    public LoginController(ObjectProvider<LoginService> services) { this.services = services; }

    @PostMapping(path = "/api/v1/auth/phone-challenges", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChallengeResponse> request(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        var input = LoginRequestReader.body(request, Set.of("phoneNumber", "purpose", "clientInstanceRef"));
        if (!"login".equals(input.get("purpose"))) throw LoginRequestReader.invalid();
        String client = LoginRequestReader.client(input.get("clientInstanceRef"));
        String phone = phone(input.get("phoneNumber"));
        var result = invoke(() -> service().request(client, key, phone));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(new ChallengeResponse(
                result.id(), result.submission(), result.expiresAt(), "•••••••" + phone.substring(phone.length() - 2)));
    }

    @PostMapping(path = "/api/v1/auth/phone-challenges/{challengeId}/verifications",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public void verify(@PathVariable String challengeId, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        UUID challenge = LoginRequestReader.uuid(challengeId);
        UUID key = LoginRequestReader.key(request);
        var input = LoginRequestReader.body(request, Set.of("phoneNumber", "code", "clientInstanceRef"));
        String client = LoginRequestReader.client(input.get("clientInstanceRef"));
        String phone = phone(input.get("phoneNumber"));
        String supplied = input.remove("code");
        if (!supplied.matches("[0-9]{6}")) throw LoginRequestReader.invalid();
        char[] code = supplied.toCharArray();
        try (var grant = invoke(() -> service().verify(challenge, client, key, phone, code))) {
            char[] token = grant.token();
            try {
                response.setStatus(200);
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-store");
                // Serialize directly and clear our copies even if writing the network response fails.
                try (var writer = JSON.createGenerator(response.getOutputStream())) {
                    writer.writeStartObject();
                    writer.writeStringProperty("sessionId", grant.sessionId().toString());
                    writer.writeStringProperty("accountId", grant.accountId().toString());
                    writer.writeStringProperty("tokenType", "Bearer");
                    writer.writeName("token"); writer.writeString(token, 0, token.length);
                    writer.writeStringProperty("absoluteExpiresAt", grant.absoluteExpiresAt().toString());
                    writer.writeStringProperty("idleExpiresAt", grant.idleExpiresAt().toString());
                    writer.writeEndObject();
                }
            } finally { Arrays.fill(token, '\0'); }
        } finally { Arrays.fill(code, '\0'); }
    }

    @PostMapping(path = "/api/v1/auth/sessions/{sessionId}/revocations")
    public ResponseEntity<Void> revoke(@PathVariable String sessionId, HttpServletRequest request) throws IOException {
        UUID session = LoginRequestReader.uuid(sessionId);
        UUID key = LoginRequestReader.key(request);
        LoginRequestReader.emptyBody(request);
        String client = LoginRequestReader.client(LoginRequestReader.singleHeader(request, "X-Waka-Client-Instance"));
        char[] token = LoginRequestReader.bearer(request);
        try {
            invoke(() -> { service().revoke(session, token, client, key); return Boolean.TRUE; });
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } finally { Arrays.fill(token, '\0'); }
    }

    private LoginService service() {
        LoginService service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }

    private static String phone(String value) {
        try { return CameroonMobilePhoneFormat.normalize(value); }
        catch (IllegalArgumentException ignored) { throw LoginRequestReader.invalid(); }
    }

    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (OtpException error) {
            throw new ResponseStatusException(switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case RATE_LIMITED, BUDGET_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS;
                case VERIFICATION_INVALID -> HttpStatus.UNAUTHORIZED;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            });
        } catch (SessionException error) {
            throw new ResponseStatusException(switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case IDEMPOTENCY_KEY_REUSED -> HttpStatus.CONFLICT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.UNAUTHORIZED;
            });
        }
    }

    public record ChallengeResponse(UUID challengeId, PersistentOtpService.Submission submission,
            Instant expiresAt, String destinationHint) { }
}
