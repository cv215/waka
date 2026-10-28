package com.waka.identity.admission;

import com.waka.identity.profile.ProfileException;
import com.waka.identity.profile.ProfileName;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The eight C03 fields as declared by the candidate. Each may stay unknown (null) in a draft.
 * Declared values are never a verified capacity, plate or right of use: examination decides.
 */
public record VehicleFields(String brand, String model, Integer year, String color, String registration,
        Integer authorizedTotalCapacity, Integer offeredPassengerSeats, String usageRight) {
    /** Commercial ceiling of the launch rules: at most five passengers besides the driver. */
    public static final int MAX_PASSENGERS = 5;
    public static final List<String> NAMES = List.of("brand", "model", "year", "color", "registration",
            "authorizedTotalCapacity", "offeredPassengerSeats", "usageRight");
    public static final VehicleFields EMPTY = new VehicleFields(null, null, null, null, null, null, null, null);

    /** Normalizes and validates declared values; serverYear bounds the declared year. */
    public static VehicleFields declared(String brand, String model, Integer year, String color, String registration,
            Integer authorizedTotalCapacity, Integer offeredPassengerSeats, String usageRight, int serverYear) {
        Integer total = authorizedTotalCapacity, seats = offeredPassengerSeats;
        if (year != null && (year < 1900 || year > serverYear + 1)) throw invalid();
        // Total occupants including the driver, as on the authorizing document.
        if (total != null && (total < 2 || total > 99)) throw invalid();
        if (seats != null && (seats < 1 || seats > MAX_PASSENGERS)) throw invalid();
        if (seats != null && total != null && seats > Math.min(MAX_PASSENGERS, total - 1)) throw invalid();
        if (usageRight != null && !usageRight.equals("holder") && !usageRight.equals("authorized_use")) throw invalid();
        return new VehicleFields(text(brand), text(model), year, text(color), registration(registration),
                total, seats, usageRight);
    }

    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        Object[] values = { brand, model, year, color, registration, authorizedTotalCapacity, offeredPassengerSeats, usageRight };
        for (int i = 0; i < values.length; i++) if (values[i] == null) missing.add(NAMES.get(i));
        return List.copyOf(missing);
    }

    void write(DataOutputStream output) throws IOException {
        for (String value : new String[] { brand, model, color, registration, usageRight }) {
            output.writeBoolean(value != null); if (value != null) output.writeUTF(value);
        }
        for (Integer value : new Integer[] { year, authorizedTotalCapacity, offeredPassengerSeats }) {
            output.writeBoolean(value != null); if (value != null) output.writeInt(value);
        }
    }

    /** Stored values are revalidated: a decrypted value that no longer passes is treated as unavailable. */
    static VehicleFields read(DataInputStream input) throws IOException {
        String[] texts = new String[5];
        for (int i = 0; i < texts.length; i++) texts[i] = input.readBoolean() ? input.readUTF() : null;
        Integer[] numbers = new Integer[3];
        for (int i = 0; i < numbers.length; i++) numbers[i] = input.readBoolean() ? input.readInt() : null;
        VehicleFields fields;
        try {
            // The year was bounded by the server year when written; only its lower bound is rechecked.
            fields = declared(texts[0], texts[1], numbers[0], texts[2], texts[3], numbers[1], numbers[2], texts[4], 9998);
        } catch (AdmissionException invalid) { throw new IOException("STORED_VEHICLE_INVALID"); }
        if (!Objects.equals(fields.brand, texts[0]) || !Objects.equals(fields.model, texts[1])
                || !Objects.equals(fields.color, texts[2]) || !Objects.equals(fields.registration, texts[3])) {
            throw new IOException("STORED_VEHICLE_NOT_NORMALIZED");
        }
        return fields;
    }

    private static String text(String input) {
        if (input == null) return null;
        String value;
        try { value = ProfileName.normalize(input); }
        catch (ProfileException invalid) { throw invalid(); }
        if (value.codePointCount(0, value.length()) > 40) throw invalid();
        return value;
    }

    private static String registration(String input) {
        String value = text(input);
        if (value == null) return null;
        value = value.toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z0-9][A-Z0-9 -]{0,14}[A-Z0-9]")) throw invalid();
        return value;
    }

    private static AdmissionException invalid() { return new AdmissionException(AdmissionException.Code.INVALID_REQUEST); }

    @Override public String toString() { return "VehicleFields[REDACTED]"; }
}
