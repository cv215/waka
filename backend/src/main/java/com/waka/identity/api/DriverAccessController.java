package com.waka.identity.api;

import com.waka.drivers.DriverAccessService;
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

/** A32 (K23): the bearer's own effective driver access; reading grants and extends nothing. */
@RestController
public final class DriverAccessController {
    private final ObjectProvider<DriverAccessService> services;
    public DriverAccessController(ObjectProvider<DriverAccessService> services) { this.services = services; }

    @GetMapping(path = "/api/v1/driver/access", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DriverAccessService.AccessView> access(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var service = services.getIfAvailable();
            if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
            try {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.access(token, client));
            } catch (SessionException error) {
                throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                        ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
            }
        } finally { Arrays.fill(token, '\0'); }
    }
}
