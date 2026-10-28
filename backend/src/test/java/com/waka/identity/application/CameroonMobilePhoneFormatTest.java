package com.waka.identity.application;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CameroonMobilePhoneFormatTest {
    // Valeur synthetique de test de syntaxe seulement ; attribution/joignabilite inconnues, aucun envoi.
    private static final String NATIONAL = "6" + "0".repeat(8);

    @Test
    void acceptsNationalAndBothInternationalForms() {
        for (String input : List.of(NATIONAL, "+237" + NATIONAL, "00237" + NATIONAL,
                " (6)00-000-000 ", "+237\u00a0" + NATIONAL, "00237\u202f" + NATIONAL)) {
            assertEquals("+237" + NATIONAL, CameroonMobilePhoneFormat.normalize(input));
        }
    }

    @Test
    void boundsRawInputBeforeRemovingFormatting() {
        assertEquals("+237" + NATIONAL, CameroonMobilePhoneFormat.normalize(" ".repeat(31) + NATIONAL));
        assertThrows(IllegalArgumentException.class,
                () -> CameroonMobilePhoneFormat.normalize(" ".repeat(32) + NATIONAL));
    }

    @Test
    void rejectsOtherTerritoriesFixedLinesExtensionsAndNonAsciiDigits() {
        for (String input : List.of("", " ", "6" + "0".repeat(7), "6" + "0".repeat(9),
                "2" + "0".repeat(8), "+238" + NATIONAL, "+23700237" + NATIONAL,
                "00237+237" + NATIONAL, "+237+" + NATIONAL, "237" + NATIONAL,
                NATIONAL + "ext1", NATIONAL + "#1", NATIONAL + "\t", NATIONAL + "\n",
                NATIONAL + "\u0000", "\uff16" + "0".repeat(8), "6" + "\u0660".repeat(8),
                "6\u2009" + "0".repeat(8), "6\u200b" + "0".repeat(8), NATIONAL + "A")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> CameroonMobilePhoneFormat.normalize(input));
            assertEquals("INVALID_PHONE_FORMAT", error.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> CameroonMobilePhoneFormat.normalize(null));
    }
}
