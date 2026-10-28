package com.waka.identity.otp;

/** Only fixed codes; never attach a provider/database cause to public failures. */
public final class OtpException extends RuntimeException {
    public enum Code { INVALID_REQUEST, IDEMPOTENCY_KEY_REUSED, RATE_LIMITED,
        BUDGET_EXHAUSTED, VERIFICATION_INVALID, SERVICE_UNAVAILABLE }
    private final Code code;
    public OtpException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
