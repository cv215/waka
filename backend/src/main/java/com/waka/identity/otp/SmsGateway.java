package com.waka.identity.otp;

/** Mandatory port. No provider, bean or paid transport is installed by this module. */
public interface SmsGateway {
    Submission send(SmsMessage message) throws Exception;

    /** ACCEPTED is transport acceptance, never proof of delivery or ownership. */
    enum Submission { ACCEPTED, REJECTED, UNKNOWN }
}
