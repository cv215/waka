package com.waka.identity.sms.cooms;

import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.sms.SmsHttp;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * Cooms (cooms.app): MTN and Orange Cameroon, sign-up with an e-mail only (founder, 23 November 2026; SmsPro needs a
 * NIU). Contract read on 23 November 2026 in the Cooms console: POST {gateway}/api/v1/sms/send with header x-api-key
 * and {phone, message, priority}; 200 = sent or queued; 401 key, 402 no credit, 403 account suspended or content
 * blocked, 422 prohibited content, 429 rate limit, 503 no gateway phone connected.
 *
 * <p>Those documented refusals are REJECTED: the user can ask for a new code. Cooms advises switching to another
 * provider after a timeout; WAKA never does, because the first SMS may still leave and a second one would be billed:
 * a timeout, a 5xx other than 503 or an unreadable answer stays UNKNOWN and is never resent.
 */
public final class CoomsSmsGateway implements SmsGateway, AutoCloseable {
    private static final String SEND_PATH = "/api/v1/sms/send";
    private static final Set<Integer> REFUSED = Set.of(400, 401, 402, 403, 422, 429, 503);
    private static final JsonFactory JSON = new JsonFactory();
    private final SmsHttp http;
    private final URI base;
    private final char[] key;
    private boolean closed;

    /** {@code gateway} is the API address shown in the Cooms console for this account, for example https://gw-89.cooms.app. */
    public CoomsSmsGateway(URI gateway, char[] apiKey) {
        this(gateway, apiKey, Duration.ofSeconds(8));
    }

    // Bounded injection point for tests of the real HTTP client; only a cooms.app host or the loopback is accepted.
    CoomsSmsGateway(URI base, char[] apiKey, Duration deadline) {
        if (!SmsHttp.plainOrigin(base) || !(coomsHost(base) || SmsHttp.loopback(base)) || !validKey(apiKey)) {
            throw invalid();
        }
        try { this.http = new SmsHttp(deadline); } catch (IllegalArgumentException wrong) { throw invalid(); }
        this.key = apiKey.clone();
        this.base = base;
    }

    static boolean coomsHost(URI base) {
        return "https".equals(base.getScheme()) && base.getPort() == -1 && base.getHost() != null
                && base.getHost().matches("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.cooms\\.app");
    }

    static boolean validKey(char[] apiKey) {
        if (apiKey == null || apiKey.length < 21 || apiKey.length > 220) return false;
        return new String(apiKey).matches("coom_[a-z]+_[A-Za-z0-9]{16,200}");
    }

    @Override
    public Submission send(SmsMessage message) {
        if (message == null) { return Submission.REJECTED; }
        char[] destination = null;
        char[] code = null;
        byte[] body = null;
        byte[] responseBody = null;
        boolean started = false;
        try {
            synchronized (this) { if (closed) { return Submission.REJECTED; } }
            destination = message.destination();
            code = message.code();
            String phone = new String(destination);
            String digits = new String(code);
            if (!phone.matches("\\+2376[0-9]{8}") || !digits.matches("[0-9]{6}")) {
                return Submission.REJECTED;
            }
            // Closed alphabets, no quote or control character; plain ASCII so that it stays one 160-character SMS.
            String payload = "{\"phone\":\"" + phone + "\",\"message\":\"Code WAKA : " + digits
                    + ". Valable 5 minutes. Ne le partagez pas.\",\"priority\":1}";
            body = payload.getBytes(StandardCharsets.US_ASCII);
            HttpRequest request = http.request(base.resolve(SEND_PATH)).header("Content-Type", "application/json")
                    .header("x-api-key", new String(key))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            started = true;
            HttpResponse<byte[]> response = http.exchange(request);
            responseBody = response.body();
            if (REFUSED.contains(response.statusCode())) { return Submission.REJECTED; }
            if (response.statusCode() != 200 || !SmsHttp.json(response)) { return Submission.UNKNOWN; }
            return accepted(responseBody) ? Submission.ACCEPTED : Submission.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return started ? Submission.UNKNOWN : Submission.REJECTED;
        } catch (Exception failure) {
            // No cause, content, number, key or provider message ever reaches an output.
            return started ? Submission.UNKNOWN : Submission.REJECTED;
        } finally {
            if (destination != null) { Arrays.fill(destination, '\0'); }
            if (code != null) { Arrays.fill(code, '\0'); }
            if (body != null) { Arrays.fill(body, (byte) 0); }
            if (responseBody != null) { Arrays.fill(responseBody, (byte) 0); }
        }
    }

    /**
     * The console does not document the success body: a single JSON object without "error" and without
     * "success": false is taken as accepted. Anything else is not proof.
     */
    private static boolean accepted(byte[] body) throws IOException {
        var fields = new HashSet<String>();
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) { return false; }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.getString();
                if (!fields.add(field) || "error".equals(field)) { return false; }
                JsonToken value = parser.nextToken();
                if ("success".equals(field) && value != JsonToken.VALUE_TRUE) { return false; }
                parser.skipChildren();
            }
            return parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_COOMS_CONFIGURATION");
    }

    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(key, '\0');
        http.close();
    }

    @Override public String toString() { return "CoomsSmsGateway[key=REDACTED]"; }
}
