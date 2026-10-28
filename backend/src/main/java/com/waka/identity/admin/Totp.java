package com.waka.identity.admin;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RFC 6238 time-based one-time codes (HMAC-SHA1, 30 s steps), the format read by common
 * free authenticator applications. No network or provider is involved.
 */
public final class Totp {
    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private Totp() { }

    public static long step(Instant time) { return Math.floorDiv(time.getEpochSecond(), STEP_SECONDS); }

    /** Code for one step, zero-padded; {@code digits} is 6 in use, 8 for the RFC vectors. */
    static String code(byte[] secret, long step, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            int modulus = 1;
            for (int i = 0; i < digits; i++) modulus *= 10;
            String value = Integer.toString(binary % modulus);
            return "0".repeat(digits - value.length()) + value;
        } catch (GeneralSecurityException hidden) { throw new AdminException(AdminException.Code.SERVICE_UNAVAILABLE); }
    }

    /**
     * Returns the matched step within one step of clock drift and strictly after {@code lastUsedStep},
     * or -1. A code is therefore accepted at most once.
     */
    static long verify(byte[] secret, String code, Instant now, long lastUsedStep) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        long current = step(now);
        long matched = -1;
        for (long candidate = current - 1; candidate <= current + 1; candidate++) {
            // Compare every candidate to keep timing independent of which one matches.
            boolean same = MessageDigest.isEqual(code(secret, candidate, DIGITS).getBytes(), code.getBytes());
            if (same && candidate > lastUsedStep && matched < 0) matched = candidate;
        }
        return matched;
    }

    /** RFC 4648 base32 without padding, as expected by otpauth URIs. */
    static String base32(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { out.append(BASE32[(buffer >> (bits - 5)) & 31]); bits -= 5; }
        }
        if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 31]);
        return out.toString();
    }
}
