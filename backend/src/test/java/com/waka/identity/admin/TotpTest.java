package com.waka.identity.admin;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TotpTest {
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test void matchesRfc6238Sha1Vectors() {
        long[][] vectors = { { 59L, 94287082L }, { 1111111109L, 7081804L }, { 1111111111L, 14050471L },
                { 1234567890L, 89005924L }, { 2000000000L, 69279037L }, { 20000000000L, 65353130L } };
        for (long[] vector : vectors) {
            String expected = String.format("%08d", vector[1]);
            assertEquals(expected, Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(vector[0])), 8));
        }
        assertEquals("287082", Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(59)), 6));
    }

    @Test void acceptsOneStepOfDriftOnlyOnceAndRefusesMalformedCodes() {
        Instant now = Instant.ofEpochSecond(1_800_000_000L);
        long current = Totp.step(now);
        String previous = Totp.code(RFC_SECRET, current - 1, 6), next = Totp.code(RFC_SECRET, current + 1, 6);
        String old = Totp.code(RFC_SECRET, current - 2, 6), late = Totp.code(RFC_SECRET, current + 2, 6);
        assertEquals(current - 1, Totp.verify(RFC_SECRET, previous, now, -1));
        assertEquals(current + 1, Totp.verify(RFC_SECRET, next, now, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, old, now, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, late, now, -1));
        String code = Totp.code(RFC_SECRET, current, 6);
        assertEquals(current, Totp.verify(RFC_SECRET, code, now, -1));
        assertEquals(-1, Totp.verify(RFC_SECRET, code, now, current), "same code twice");
        assertEquals(-1, Totp.verify(RFC_SECRET, previous, now, current), "older step after a newer one");
        for (String bad : new String[] { null, "", "12345", "1234567", "12a456", " 123456" }) {
            assertEquals(-1, Totp.verify(RFC_SECRET, bad, now, -1));
        }
    }

    @Test void base32MatchesRfc4648WithoutPadding() {
        String[][] vectors = { { "", "" }, { "f", "MY" }, { "fo", "MZXQ" }, { "foo", "MZXW6" },
                { "foob", "MZXW6YQ" }, { "fooba", "MZXW6YTB" }, { "foobar", "MZXW6YTBOI" } };
        for (String[] vector : vectors) {
            assertEquals(vector[1], Totp.base32(vector[0].getBytes(StandardCharsets.US_ASCII)));
        }
    }
}
