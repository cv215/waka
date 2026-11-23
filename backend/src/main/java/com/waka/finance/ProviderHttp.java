package com.waka.finance;

import com.waka.finance.PaymentProvider.ProviderUnavailable;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What every Mobile Money provider shares: one bounded JSON exchange, and the checks on amounts and labels. */
final class ProviderHttp {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private ProviderHttp() { }

    /** An HTTP status and a JSON body; an empty or unreadable body becomes an empty object. */
    static final class Reply {
        final int status;
        final JsonNode body;
        Reply(int status, JsonNode body) { this.status = status; this.body = body; }
        boolean ok() { return status >= 200 && status < 300; }
    }

    /** No redirects: a provider's answer is only trusted from the address WAKA called. */
    static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static HttpRequest.BodyPublisher json(Object body) {
        return HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body));
    }

    /**
     * Sends and reads at most {@code maxResponse} bytes. Any failure is an unknown outcome, named
     * {@code <name>_UNREACHABLE}, {@code <name>_INTERRUPTED} or {@code <name>_RESPONSE_TOO_LARGE}.
     */
    static Reply send(HttpClient http, HttpRequest request, int maxResponse, String name) throws ProviderUnavailable {
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var in = response.body()) { raw = in.readNBytes(maxResponse + 1); }
            if (raw.length > maxResponse) throw new ProviderUnavailable(name + "_RESPONSE_TOO_LARGE");
            JsonNode json;
            try { json = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw); }
            catch (JacksonException unreadable) { json = JSON.createObjectNode(); }
            return new Reply(response.statusCode(), json);
        } catch (IOException error) {
            throw new ProviderUnavailable(name + "_UNREACHABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailable(name + "_INTERRUPTED");
        }
    }

    /** Whole francs written as 100, "100" or "100.00"; zero, a decimal or anything else is not trusted (null). */
    static Long wholeXaf(JsonNode amount) {
        try {
            BigDecimal value = amount.isNumber() ? amount.decimalValue()
                    : amount.isString() && amount.asString().matches("[0-9]{1,12}(\\.[0-9]{1,2})?") ? new BigDecimal(amount.asString()) : null;
            if (value == null || value.signum() <= 0) return null;
            return value.stripTrailingZeros().scale() <= 0 ? value.longValueExact() : null;
        } catch (ArithmeticException notWhole) {
            return null;
        }
    }

    static long positive(long amountXaf, String name) {
        if (amountXaf <= 0) throw new IllegalArgumentException(name + "_AMOUNT");
        return amountXaf;
    }

    /** A label for the provider: no control characters, at most {@code max} characters, "WAKA" when empty. */
    static String label(String description, int max) {
        String clean = description == null ? "WAKA" : description.replaceAll("[\\p{Cntrl}]", " ").strip();
        return clean.isEmpty() ? "WAKA" : clean.substring(0, Math.min(clean.length(), max));
    }
}
