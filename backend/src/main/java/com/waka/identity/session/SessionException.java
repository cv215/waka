package com.waka.identity.session;

/** Fixed internal codes, with no token, contact or database cause. */
public final class SessionException extends RuntimeException {
    public enum Code { INVALID_REQUEST, INVALID_PROOF, PROOF_ALREADY_USED, INVALID_SESSION,
        IDEMPOTENCY_KEY_REUSED, RECOVERY_EXPIRED, SERVICE_UNAVAILABLE }
    private final Code code;
    public SessionException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}

