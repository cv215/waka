package com.waka.identity.profile;

public final class ProfileException extends RuntimeException {
    public enum Code { INVALID_REQUEST, VERSION_CONFLICT, IDEMPOTENCY_KEY_REUSED, REPLAY_EXPIRED, SERVICE_UNAVAILABLE }
    private final Code code;
    public ProfileException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
