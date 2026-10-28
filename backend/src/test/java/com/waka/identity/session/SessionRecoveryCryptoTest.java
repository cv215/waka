package com.waka.identity.session;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryCryptoTest {
    @Test void authenticatedEncryptionRejectsTamperingWrongContextAndWrongKey() {
        var random = new SecureRandom(); byte[] key = new byte[32]; random.nextBytes(key);
        var crypto = new SessionRecoveryCrypto(key, "test-key", random);
        byte[] aad = "technical-bound-context".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        char[] token = new SessionCrypto(key, random).newToken();
        byte[] encrypted = crypto.encrypt(token, aad);
        assertEquals(71, encrypted.length);
        assertFalse(Arrays.equals(encrypted, crypto.encrypt(token, aad)));
        assertArrayEquals(token, crypto.decrypt(encrypted, aad));
        byte[] tampered = encrypted.clone(); tampered[25] ^= 1;
        assertThrows(SessionException.class, () -> crypto.decrypt(tampered, aad));
        assertThrows(SessionException.class, () -> crypto.decrypt(encrypted, new byte[0]));
        random.nextBytes(key);
        assertThrows(SessionException.class, () -> new SessionRecoveryCrypto(key, "test-key", random).decrypt(encrypted, aad));
        assertThrows(SessionException.class, () -> crypto.decrypt(new byte[70], aad));
        Arrays.fill(key, (byte) 0); Arrays.fill(token, '\0');
    }

    @Test void policyRequiresAnExplicitPositiveMicrosecondLifetime() {
        for (Duration invalid : new Duration[] { null, Duration.ZERO, Duration.ofSeconds(-1), Duration.ofNanos(1), Duration.ofDays(Long.MAX_VALUE / 86400) }) {
            assertThrows(IllegalArgumentException.class, () -> new SessionRecoveryPolicy(invalid));
        }
        assertEquals(Duration.ofSeconds(90), new SessionRecoveryPolicy(Duration.ofSeconds(90)).lifetime());
    }

    @Test void keyInputIsDefensivelyCopiedAndIdentifiersAreBounded() {
        var random = new SecureRandom(); byte[] key = new byte[32]; random.nextBytes(key);
        var identity = new SessionCrypto(key, random);
        var crypto = new SessionRecoveryCrypto(key, "test-key", random);
        assertTrue(crypto.reusesIdentityKey(identity));
        Arrays.fill(key, (byte) 0);
        assertTrue(crypto.reusesIdentityKey(identity));
        assertEquals("SessionRecoveryCrypto[REDACTED]", crypto.toString());
        assertThrows(IllegalArgumentException.class, () -> new SessionRecoveryCrypto(new byte[31], "key", random));
        assertThrows(IllegalArgumentException.class, () -> new SessionRecoveryCrypto(key, "bad key", random));
    }
}
