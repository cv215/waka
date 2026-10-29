package com.waka.identity.privacy;

/** Fixed internal codes; never the request details or an identifier of another person. */
public final class PrivacyException extends RuntimeException {
    public enum Code { INVALID_REQUEST, IDEMPOTENCY_KEY_REUSED, SERVICE_UNAVAILABLE }
    private final Code code;
    public PrivacyException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
