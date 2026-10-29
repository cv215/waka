package com.waka.identity.privacy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyDetailsTest {
    @Test void textIsBoundedAndWithoutHiddenControls() {
        assertNull(PrivacyDetails.normalize(null));
        assertEquals("Ligne un\nligne deux", PrivacyDetails.normalize("  Ligne un\nligne deux  "));
        assertEquals(500, PrivacyDetails.normalize("é".repeat(500)).length());
        for (String bad : new String[] { "", "   ", "a".repeat(501), "a" + (char) 0x202e + "b", "a" + (char) 0x0007, "a\rb" }) {
            assertEquals(PrivacyException.Code.INVALID_REQUEST,
                    assertThrows(PrivacyException.class, () -> PrivacyDetails.normalize(bad)).code(), bad.length() + "");
        }
    }
}
