package com.waka.finance;

/** Fixed internal codes for payments, the WAKA credit and withdrawals (R14). */
public final class PaymentException extends RuntimeException {
    public enum Code { INVALID_REQUEST, NOT_FOUND, NOT_PAYABLE, INSUFFICIENT_CREDIT, CREDIT_LIMIT, PHONE_NOT_ACCOUNT, NOT_AUTHENTIC, SERVICE_UNAVAILABLE }
    private final Code code;
    public PaymentException(Code code) { super(code.name(), null, false, false); this.code = code; }
    public Code code() { return code; }
}
