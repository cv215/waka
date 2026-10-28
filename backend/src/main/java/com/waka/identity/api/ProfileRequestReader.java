package com.waka.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Enumeration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Strict 2048-byte JSON boundary for the limited A05 command. */
final class ProfileRequestReader {
    record Patch(long expectedVersion, String displayName) {
        @Override public String toString() { return "ProfilePatch[REDACTED]"; }
    }
    private static final JsonFactory JSON = new JsonFactory();
    private ProfileRequestReader() { }

    static Patch patch(HttpServletRequest request) throws IOException {
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
        byte[] body = request.getInputStream().readNBytes(2049);
        try {
            if (body.length > 2048) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(new ByteArrayInputStream(body), decoder);
                    JsonParser parser = JSON.createParser(reader)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                boolean seenVersion = false, seenName = false;
                long version = 0; String name = null;
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw LoginRequestReader.invalid();
                    String field = parser.getString();
                    JsonToken value = parser.nextToken();
                    if ("expectedVersion".equals(field) && !seenVersion && value == JsonToken.VALUE_NUMBER_INT) {
                        String raw = parser.getText();
                        if (!raw.matches("[1-9][0-9]{0,15}")) throw LoginRequestReader.invalid();
                        version = Long.parseLong(raw);
                        if (version > 9007199254740991L) throw LoginRequestReader.invalid();
                        seenVersion = true;
                    } else if ("displayName".equals(field) && !seenName
                            && (value == JsonToken.VALUE_STRING || value == JsonToken.VALUE_NULL)) {
                        name = value == JsonToken.VALUE_NULL ? null : parser.getString();
                        seenName = true;
                    } else throw LoginRequestReader.invalid();
                }
                if (!seenVersion || !seenName || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return new Patch(version, name);
            } catch (JacksonException | IllegalArgumentException | IOException hidden) { throw LoginRequestReader.invalid(); }
        } finally { Arrays.fill(body, (byte) 0); }
    }
}
