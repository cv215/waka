package com.waka.identity.admission;

/** Fixed internal codes; never carries a field value, a plate or a database cause. */
public final class AdmissionException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_FOUND, APPLICATION_ALREADY_OPEN, VERSION_CONFLICT,
        IDEMPOTENCY_KEY_REUSED, REPLAY_EXPIRED, SERVICE_UNAVAILABLE }
    private final Code code;
    public AdmissionException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
