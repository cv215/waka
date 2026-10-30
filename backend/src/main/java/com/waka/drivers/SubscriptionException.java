package com.waka.drivers;

/** Fixed internal codes for trial and subscription periods. */
public final class SubscriptionException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_PRIVATE_DRIVER, TRIAL_NOT_STARTED, NOT_FOUND }
    private final Code code;
    public SubscriptionException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}