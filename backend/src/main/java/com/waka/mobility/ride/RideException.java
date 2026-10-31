package com.waka.mobility.ride;

/** Fixed internal codes; never a position, a code or another person's identifier. */
public final class RideException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_FOUND, DRIVER_NOT_ALLOWED, QUOTE_EXPIRED, PROPOSAL_EXPIRED, CAR_BUSY, NOT_PAID,
        WRONG_CODE, NOT_BOARDED, NOT_ARRIVED, TOO_EARLY, ALREADY_BOARDED, SERVICE_UNAVAILABLE, TOO_FAR }
    private final Code code;
    public RideException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
