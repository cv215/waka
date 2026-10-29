package com.waka.referrals;

/** Fixed internal codes; never reveals who owns a code. */
public final class ReferralException extends RuntimeException {
    public enum Code { INVALID_REQUEST, CODE_NOT_RECOGNIZED, SELF_REFERRAL, REFERRER_ALREADY_SET, SERVICE_UNAVAILABLE }
    private final Code code;
    public ReferralException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
