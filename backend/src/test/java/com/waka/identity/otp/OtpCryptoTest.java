package com.waka.identity.otp;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OtpCryptoTest {
    private final SecureRandom random = new SecureRandom();
    private byte[] key() { byte[] key = new byte[32]; random.nextBytes(key); return key; }

    @Test void encryptionIsRandomAuthenticatedAndBoundToChallenge() {
        OtpCrypto crypto = new OtpCrypto(key(), key(), random);
        char[] contact = new char[] {'+', '2', '3', '7', '6', '0', '0', '0', '0', '0', '0', '0', '0'};
        UUID id = UUID.randomUUID();
        byte[] first = crypto.encryptDestination(id, contact);
        byte[] second = crypto.encryptDestination(id, contact);
        assertFalse(Arrays.equals(first, second));
        assertArrayEquals(contact, crypto.decryptDestination(id, first));
        assertThrows(OtpException.class, () -> crypto.decryptDestination(UUID.randomUUID(), first));
        first[first.length - 1] ^= 1;
        assertThrows(OtpException.class, () -> crypto.decryptDestination(id, first));
    }

    @Test void macSeparatesPurposeContextAndBoundariesAndCopiesKeys() {
        byte[] mac = key(), encryption = key();
        OtpCrypto crypto = new OtpCrypto(mac, encryption, random);
        String initial = crypto.fingerprint("context", "ab", "c");
        Arrays.fill(mac, (byte) 0); Arrays.fill(encryption, (byte) 0);
        assertEquals(initial, crypto.fingerprint("context", "ab", "c"));
        assertNotEquals(initial, crypto.fingerprint("context", "a", "bc"));
        assertNotEquals(initial, crypto.fingerprint("destination", "ab", "c"));
        char[] code = crypto.newCode();
        assertEquals(6, code.length);
        for (char c : code) assertTrue(c >= '0' && c <= '9');
        UUID id = UUID.randomUUID();
        assertNotEquals(crypto.codeFingerprint(id, "first", code), crypto.codeFingerprint(id, "second", code));
        assertNotEquals(crypto.codeFingerprint(id, "first", code), crypto.codeFingerprint(UUID.randomUUID(), "first", code));
    }

    @Test void keysAndPolicyFailClosed() {
        byte[] key = key();
        assertThrows(IllegalArgumentException.class, () -> new OtpCrypto(new byte[31], key, random));
        assertThrows(IllegalArgumentException.class, () -> new OtpCrypto(key, key, random));
        assertThrows(IllegalArgumentException.class, () -> new OtpCrypto(key, new byte[16], random));
        assertThrows(IllegalArgumentException.class, () -> new OtpPolicy(-1));
        assertThrows(IllegalArgumentException.class, () -> new OtpPolicy(10_001));
        assertEquals(Duration.ofMinutes(5), new OtpPolicy(0).lifetime());
        assertEquals(5, new OtpPolicy(1).maximumWrongAttempts());
        assertEquals(Duration.ofSeconds(60), new OtpPolicy(1).cooldown());
        assertEquals(3, new OtpPolicy(1).destinationRequests());
        assertEquals(5, new OtpPolicy(1).contextRequests());
        assertEquals(10, new OtpPolicy(1).destinationFailures());
    }

    @Test void ephemeralMessageCopiesErasesAndRedacts() {
        char[] contact = {'a', 'b'}, code = {'c', 'd'};
        SmsMessage message = new SmsMessage(UUID.randomUUID(), contact, code);
        contact[0] = 'x'; code[0] = 'x';
        assertArrayEquals(new char[] {'a', 'b'}, message.destination());
        char[] copy = message.code(); copy[0] = 'x';
        assertArrayEquals(new char[] {'c', 'd'}, message.code());
        assertEquals("SmsMessage[REDACTED]", message.toString());
        message.close();
        assertThrows(IllegalStateException.class, message::code);
        for (OtpException.Code error : OtpException.Code.values()) {
            var exception = new OtpException(error);
            assertEquals(error.name(), exception.getMessage()); assertNull(exception.getCause());
        }
    }
}
