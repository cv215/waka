package com.waka.identity.profile;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProfileValidationCryptoTest {
    @Test void normalizesAccentsAndUnicodeBoundaries() {
        assertEquals("Élodie", ProfileName.normalize("  E\u0301lodie\u00a0"));
        assertEquals("😀".repeat(80), ProfileName.normalize("😀".repeat(80)));
        for (String invalid : new String[] { "", "\u00a0\u00a0", "\nNom", "Nom\r", "A\u2028B",
                "A\u2029B", "A\u200eB", "A\u202eB", "A\ud800B", "A\udc00B", "😀".repeat(81) }) {
            assertEquals(ProfileException.Code.INVALID_REQUEST,
                    assertThrows(ProfileException.class, () -> ProfileName.normalize(invalid)).code());
        }
        assertNull(ProfileName.normalize(null));
    }

    @Test void ciphertextAndMacAreBoundToTheirContextAndKeysAreRedacted() {
        byte[] aes = new byte[32], mac = new byte[32];
        Arrays.fill(aes, (byte) 3); Arrays.fill(mac, (byte) 5);
        ProfileCrypto crypto = new ProfileCrypto(aes, mac, "test-key", new SecureRandom());
        byte[] plain = "Élodie".getBytes(StandardCharsets.UTF_8);
        byte[] aad = ProfileCrypto.aad("name", "account-a", "2", "test-key");
        byte[] cipher = crypto.encrypt(plain, aad);
        assertFalse(Arrays.equals(plain, cipher));
        assertArrayEquals(plain, crypto.decrypt(cipher, aad));
        assertEquals(ProfileException.Code.SERVICE_UNAVAILABLE,
                assertThrows(ProfileException.class, () -> crypto.decrypt(cipher,
                        ProfileCrypto.aad("name", "account-b", "2", "test-key"))).code());
        cipher[15] ^= 1;
        assertEquals(ProfileException.Code.SERVICE_UNAVAILABLE,
                assertThrows(ProfileException.class, () -> crypto.decrypt(cipher, aad)).code());
        assertNotEquals(crypto.payloadMac("actor", 1, "Alice"), crypto.payloadMac("actor", 2, "Alice"));
        assertNotEquals(crypto.payloadMac("actor", 1, null), crypto.payloadMac("actor", 1, ""));
        assertEquals("ProfileCrypto[REDACTED]", crypto.toString());
    }
}
