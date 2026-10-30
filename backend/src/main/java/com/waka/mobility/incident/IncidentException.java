package com.waka.mobility.incident;

/** Fixed internal codes; never a text, a party's identity or another person's identifier. */
public final class IncidentException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_FOUND, NOT_DECIDED, IDEMPOTENCY_KEY_REUSED, CONFLICT_OF_INTEREST, VERSION_CONFLICT,
        NOT_AUTHORIZED, INVALID_ADMIN_SESSION, SERVICE_UNAVAILABLE }
    private final Code code;
    public IncidentException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}