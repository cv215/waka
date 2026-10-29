package com.waka.mobility.voyage;

/** Fixed internal codes; never a place, a price list or another driver's identifier. */
public final class VoyageException extends RuntimeException {
    public enum Code { INVALID_REQUEST, DRIVER_NOT_ALLOWED, TOO_MANY_SEATS, NOT_FOUND, OWN_VOYAGE, DEPARTED,
        NOT_ENOUGH_SEATS, ALREADY_BOARDED, WRONG_CODE, NOT_PAID, BOARDING_LOCKED,
        IDEMPOTENCY_KEY_REUSED, SERVICE_UNAVAILABLE }
    private final Code code;
    public VoyageException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
