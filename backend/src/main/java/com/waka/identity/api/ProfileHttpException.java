package com.waka.identity.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Internal safe code selector; user-supplied values are never exposed. */
public final class ProfileHttpException extends ResponseStatusException {
    private final String code;
    public ProfileHttpException(HttpStatus status, String code) {
        super(status);
        this.code = code;
    }
    public String code() { return code; }
}
