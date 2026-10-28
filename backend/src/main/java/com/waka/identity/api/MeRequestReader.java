package com.waka.identity.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Enumeration;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Only a bearer token and its bound client context can select the account. */
final class MeRequestReader {
    private MeRequestReader() { }

    static void emptyGet(HttpServletRequest request) throws IOException {
        Enumeration<String> encodings = request.getHeaders("Content-Encoding");
        if (encodings != null && encodings.hasMoreElements()) {
            String encoding = encodings.nextElement();
            if (encodings.hasMoreElements()) throw LoginRequestReader.invalid();
            if (!"identity".equalsIgnoreCase(encoding)) {
                throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            }
        }
        LoginRequestReader.emptyBody(request);
    }

    static char[] bearer(HttpServletRequest request) {
        String value = one(request, "Authorization");
        if (!value.matches("Bearer [A-Za-z0-9_-]{43}")) throw unauthorized();
        return value.substring(7).toCharArray();
    }

    static String client(HttpServletRequest request) {
        String value = one(request, "X-Waka-Client-Instance");
        if (!value.matches("[A-Za-z0-9_-]{16,128}")) throw unauthorized();
        return value;
    }

    private static String one(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) throw unauthorized();
        String value = values.nextElement();
        if (values.hasMoreElements()) throw LoginRequestReader.invalid();
        return value;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
}
