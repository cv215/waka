package com.waka.identity.api;

import com.waka.identity.session.SessionException;
import com.waka.referrals.PersistentReferralService;
import com.waka.referrals.ReferralException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
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

/** A34 (K22): own code and a single, permanent, self-service referrer attachment. */
@RestController
public final class ReferralController {
    private final ObjectProvider<PersistentReferralService> services;
    public ReferralController(ObjectProvider<PersistentReferralService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/me/referral", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentReferralService.ReferralView> mine(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> service().mine(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Naturally idempotent: the same code again returns the same attachment with 200. */
    @PostMapping(path = "/api/v1/me/referral-linkages", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PersistentReferralService.LinkageView> attach(HttpServletRequest request) throws IOException {
        String code = LoginRequestReader.body(request, Set.of("referralCode")).get("referralCode");
        if (!PersistentReferralService.CODE.matcher(code).matches()) throw LoginRequestReader.invalid();
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var linkage = invoke(() -> service().attach(token, client, code));
            return ResponseEntity.status(linkage.created() ? HttpStatus.CREATED : HttpStatus.OK)
                    .cacheControl(CacheControl.noStore()).body(linkage);
        } finally { Arrays.fill(token, '\0'); }
    }

    private PersistentReferralService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (ReferralException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case CODE_NOT_RECOGNIZED, SELF_REFERRAL -> HttpStatus.UNPROCESSABLE_CONTENT;
                case REFERRER_ALREADY_SET -> HttpStatus.CONFLICT;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code().name());
        }
    }
}
