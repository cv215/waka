package com.waka.identity.admission;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VehicleFieldsTest {
    private static final int YEAR = 2026;

    @Test void passengerSeatsStayWithinLegalTotalAndTheFivePassengerCeiling() {
        // C03: the total includes the driver; commercial ceiling five passengers.
        assertEquals(4, seats(5, 4).offeredPassengerSeats());
        assertInvalid(() -> seats(5, 5));
        assertEquals(5, seats(6, 5).offeredPassengerSeats());
        assertEquals(5, seats(7, 5).offeredPassengerSeats());
        assertInvalid(() -> seats(7, 6));
        assertInvalid(() -> seats(2, 2));
        assertEquals(1, seats(2, 1).offeredPassengerSeats());
        assertInvalid(() -> seats(1, null));
        assertInvalid(() -> seats(100, null));
        assertInvalid(() -> seats(null, 0));
        assertInvalid(() -> seats(null, 6));
        assertEquals(5, seats(null, 5).offeredPassengerSeats());
    }

    @Test void declaredTextIsNormalizedAndBounded() {
        String combining = "E" + (char) 0x0301 + "toile";
        var fields = VehicleFields.declared("  " + combining + " ", "Modele", 2019, "Bleu", " lt 123-ab ",
                5, 4, "holder", YEAR);
        assertEquals(String.valueOf((char) 0x00c9) + "toile", fields.brand());
        assertEquals("LT 123-AB", fields.registration());
        assertTrue(fields.missing().isEmpty());
        for (String bad : List.of("", " ", "A" + (char) 0x202e + "B", "A" + (char) 0x200b + "B", "A\nB", "X".repeat(41))) {
            assertInvalid(() -> VehicleFields.declared(bad, null, null, null, null, null, null, null, YEAR));
        }
        for (String plate : List.of("A", "-AB", "AB-", "AB_12", "AB" + (char) 0x00c9, "A".repeat(17))) {
            assertInvalid(() -> VehicleFields.declared(null, null, null, null, plate, null, null, null, YEAR));
        }
        assertInvalid(() -> VehicleFields.declared(null, null, 1899, null, null, null, null, null, YEAR));
        assertInvalid(() -> VehicleFields.declared(null, null, YEAR + 2, null, null, null, null, null, YEAR));
        assertEquals(YEAR + 1, VehicleFields.declared(null, null, YEAR + 1, null, null, null, null, null, YEAR).year());
        assertInvalid(() -> VehicleFields.declared(null, null, null, null, null, null, null, "owner", YEAR));
        assertEquals("authorized_use", VehicleFields.declared(null, null, null, null, null, null, null, "authorized_use", YEAR).usageRight());
    }

    @Test void draftListsUnknownFieldsAndRoundTripsRedacted() throws Exception {
        assertEquals(VehicleFields.NAMES, VehicleFields.EMPTY.missing());
        var partial = VehicleFields.declared("Marque", null, 2020, null, "AB 12", 6, null, null, YEAR);
        assertEquals(List.of("model", "color", "offeredPassengerSeats", "usageRight"), partial.missing());
        var bytes = new ByteArrayOutputStream();
        partial.write(new DataOutputStream(bytes));
        assertEquals(partial, VehicleFields.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))));
        assertEquals("VehicleFields[REDACTED]", partial.toString());
    }

    private static VehicleFields seats(Integer total, Integer seats) {
        return VehicleFields.declared(null, null, null, null, null, total, seats, null, YEAR);
    }
    private static void assertInvalid(Runnable operation) {
        assertEquals(AdmissionException.Code.INVALID_REQUEST, assertThrows(AdmissionException.class, operation::run).code());
    }
}
