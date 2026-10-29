package com.waka.mobility.quote;

/** Fixed internal codes; never a coordinate, a distance from the client or a provider detail. */
public final class QuoteException extends RuntimeException {
    public enum Code { INVALID_REQUEST, OUTSIDE_SERVICE_AREA, NO_ROUTE, DISTANCE_OUT_OF_RANGE, ROUTE_UNAVAILABLE,
        IDEMPOTENCY_KEY_REUSED, SERVICE_UNAVAILABLE }
    private final Code code;
    public QuoteException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
