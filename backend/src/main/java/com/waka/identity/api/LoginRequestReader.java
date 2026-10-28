package com.waka.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Bounded, strict login input. Never bind arbitrary DTO fields or accept secrets in URLs. */
final class LoginRequestReader {
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_BODY = 1024;
    private LoginRequestReader() { }

    static Map<String, String> body(HttpServletRequest request, Set<String> expected) throws IOException {
        requireNoQuery(request);
        String encoding = request.getHeader("Content-Encoding");
        if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw invalid();
        String charset = request.getCharacterEncoding();
        if (charset != null && !"UTF-8".equalsIgnoreCase(charset)) throw invalid();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
        try {
            if (bytes.length > MAX_BODY) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            Map<String, String> fields = new HashMap<>();
            var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try (var reader = new InputStreamReader(new ByteArrayInputStream(bytes), decoder);
                    JsonParser parser = JSON.createParser(reader)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw invalid();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken() != JsonToken.PROPERTY_NAME) throw invalid();
                    String name = parser.getString();
                    if (!expected.contains(name) || fields.containsKey(name)
                            || parser.nextToken() != JsonToken.VALUE_STRING) throw invalid();
                    String value = parser.getString();
                    if (value.length() > 128) throw invalid();
                    fields.put(name, value);
                }
                if (!fields.keySet().equals(expected) || parser.nextToken() != null) throw invalid();
                return fields;
            } catch (JacksonException | IllegalArgumentException | IOException ignored) { throw invalid(); }
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    static void emptyBody(HttpServletRequest request) throws IOException {
        requireNoQuery(request);
        if (request.getInputStream().read() != -1) throw invalid();
    }

    static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid();
        }
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException ignored) { throw invalid(); }
    }

    static UUID key(HttpServletRequest request) { return uuid(singleHeader(request, "Idempotency-Key")); }

    static String client(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{16,128}")) throw invalid();
        return value;
    }

    static String singleHeader(HttpServletRequest request, String name) {
        var headers = request.getHeaders(name);
        if (headers == null || !headers.hasMoreElements()) throw invalid();
        String value = headers.nextElement();
        if (headers.hasMoreElements()) throw invalid();
        return value;
    }

    static char[] bearer(HttpServletRequest request) {
        String header = singleHeader(request, "Authorization");
        if (!header.matches("Bearer [A-Za-z0-9_-]{43}")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return header.substring(7).toCharArray();
    }

    private static void requireNoQuery(HttpServletRequest request) {
        if (request.getQueryString() != null) throw invalid();
    }

    static ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST); }
}
