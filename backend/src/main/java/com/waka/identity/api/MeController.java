package com.waka.identity.api;

import com.waka.identity.application.MeService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** A04: revalidate the current session before showing the account's base right. */
@RestController
public final class MeController {
    private final ObjectProvider<MeService> services;

    public MeController(ObjectProvider<MeService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MeService.MeView> me(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            MeService service = services.getIfAvailable();
            if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            try {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(token, client));
            } catch (SessionException error) {
                throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
            }
        } finally { Arrays.fill(token, '\0'); }
    }
}
