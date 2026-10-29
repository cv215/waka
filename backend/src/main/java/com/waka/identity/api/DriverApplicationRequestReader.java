package com.waka.identity.api;

import com.waka.identity.admission.VehicleFields;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * Strict 4096-byte boundary for A28. Only expectedVersion and the eight vehicle fields are read;
 * documentRefs/photoRefs, a state, an approval or any other field is refused until its own point.
 */
final class DriverApplicationRequestReader {
    record VehiclePatch(long expectedVersion, VehicleFields fields) {
        @Override public String toString() { return "VehiclePatch[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private static final Set<String> TEXTS = Set.of("brand", "model", "color", "registration", "usageRight");
    private static final Set<String> NUMBERS = Set.of("year", "authorizedTotalCapacity", "offeredPassengerSeats");
    private DriverApplicationRequestReader() { }

    static VehiclePatch vehicle(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        Enumeration<String> encodings = request.getHeaders("Content-Encoding");
        if (encodings != null && encodings.hasMoreElements()) {
            String value = encodings.nextElement();
            if (encodings.hasMoreElements()) throw LoginRequestReader.invalid();
            if (!"identity".equalsIgnoreCase(value)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        if (request.getCharacterEncoding() != null && !"UTF-8".equalsIgnoreCase(request.getCharacterEncoding())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        byte[] body = request.getInputStream().readNBytes(4097);
        try {
            if (body.length > 4096) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(new ByteArrayInputStream(body), decoder);
                    JsonParser parser = JSON.createParser(reader)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Long version = null;
                Map<String, Object> vehicle = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
                    String field = parser.getString();
                    JsonToken value = parser.nextToken();
                    if ("expectedVersion".equals(field) && version == null && value == JsonToken.VALUE_NUMBER_INT) {
                        version = integer(parser.getText(), 16);
                        if (version > 9007199254740991L) throw LoginRequestReader.invalid();
                    } else if ("vehicleFields".equals(field) && vehicle == null && value == JsonToken.START_OBJECT) {
                        vehicle = vehicleObject(parser);
                    } else throw LoginRequestReader.invalid();
                }
                if (version == null || vehicle == null || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return new VehiclePatch(version, new VehicleFields((String) vehicle.get("brand"), (String) vehicle.get("model"),
                        (Integer) vehicle.get("year"), (String) vehicle.get("color"), (String) vehicle.get("registration"),
                        (Integer) vehicle.get("authorizedTotalCapacity"), (Integer) vehicle.get("offeredPassengerSeats"),
                        (String) vehicle.get("usageRight")));
            } catch (JacksonException | IllegalArgumentException | ClassCastException | IOException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    /** Exactly {"expectedVersion": n}, under the same strict limits as the vehicle body. */
    static long expectedVersionOnly(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        if (request.getCharacterEncoding() != null && !"UTF-8".equalsIgnoreCase(request.getCharacterEncoding())) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        Enumeration<String> encodings = request.getHeaders("Content-Encoding");
        if (encodings != null && encodings.hasMoreElements()) {
            String value = encodings.nextElement();
            if (encodings.hasMoreElements() || !"identity".equalsIgnoreCase(value)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }
        byte[] body = request.getInputStream().readNBytes(257);
        try {
            if (body.length > 256) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(new ByteArrayInputStream(body), decoder);
                    JsonParser parser = JSON.createParser(reader)) {
                if (parser.nextToken() != JsonToken.START_OBJECT || parser.nextToken() != JsonToken.PROPERTY_NAME
                        || !"expectedVersion".equals(parser.getString()) || parser.nextToken() != JsonToken.VALUE_NUMBER_INT) {
                    throw LoginRequestReader.invalid();
                }
                long version = integer(parser.getText(), 16);
                if (version > 9007199254740991L || parser.nextToken() != JsonToken.END_OBJECT || parser.nextToken() != null) {
                    throw LoginRequestReader.invalid();
                }
                return version;
            } catch (JacksonException | IllegalArgumentException | IOException hidden) { throw LoginRequestReader.invalid(); }
        } finally { Arrays.fill(body, (byte) 0); }
    }

    /** All eight keys are required; null explicitly means "not yet known" in a draft. */
    private static Map<String, Object> vehicleObject(JsonParser parser) {
        Map<String, Object> values = new HashMap<>();
        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
            String name = parser.getString();
            JsonToken token = parser.nextToken();
            if (values.containsKey(name)) throw LoginRequestReader.invalid();
            if (token == JsonToken.VALUE_NULL && (TEXTS.contains(name) || NUMBERS.contains(name))) values.put(name, null);
            else if (token == JsonToken.VALUE_STRING && TEXTS.contains(name)) values.put(name, parser.getString());
            else if (token == JsonToken.VALUE_NUMBER_INT && NUMBERS.contains(name)) values.put(name, (int) integer(parser.getText(), 4));
            else throw LoginRequestReader.invalid();
        }
        if (!values.keySet().equals(Set.copyOf(VehicleFields.NAMES))) throw LoginRequestReader.invalid();
        return values;
    }

    /** Plain positive decimal only: no sign, exponent, fraction or leading zero. */
    private static long integer(String raw, int maxDigits) {
        if (!raw.matches("[1-9][0-9]{0," + (maxDigits - 1) + "}")) throw LoginRequestReader.invalid();
        return Long.parseLong(raw);
    }
}
