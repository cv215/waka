package com.waka.pricing.domain;

/** Codes techniques sans distance, identifiant personnel ou detail de connexion. */
public final class PricingException extends IllegalArgumentException {
    public enum Code { UNKNOWN_FARE_VERSION, INVALID_FARE_SCHEDULE, INVALID_DISTANCE,
        DISTANCE_OUT_OF_RANGE, INVALID_SHARED_SEATS }

    private final Code code;

    public PricingException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() { return code; }
}
