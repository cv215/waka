package com.waka.identity.admin;

/** Fixed internal codes; never carries a code, a secret, a token or a database cause. */
public final class AdminException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_AUTHORIZED, ENROLLMENT_REQUIRED, ALREADY_ENROLLED, INVALID_SECOND_FACTOR,
        LOCKED, INVALID_ADMIN_SESSION, BOOTSTRAP_ALREADY_DONE, ACCOUNT_NOT_FOUND, SERVICE_UNAVAILABLE }
    private final Code code;
    public AdminException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
