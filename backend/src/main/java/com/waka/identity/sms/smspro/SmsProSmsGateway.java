package com.waka.identity.sms.smspro;

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
 * SmsPro (Mediasystem SARL, Yaounde): one account and one key for MTN and Orange Cameroon (founder, 22 November 2026,
 * after Orange announced the end of its SMS Cameroon API on 1 December 2026). Contract read on 22 November 2026 from
 * https://v2.smspro.cm/docs/api: POST /api/v1/messages {to, message, from}, bearer key, 201 when accepted.
 * Kept ready: SmsPro requires a NIU at sign-up, which the founder does not have yet (Cooms is used meanwhile).
 *
 * <p>WAKA generates and checks the code itself (PersistentOtpService); SmsPro only carries the text. A refusal that
 * SmsPro documents as "nothing created, nothing billed" (400, 401, 402, 404, 422, 429) is REJECTED. Anything else after
 * the request started (5xx, timeout, unreadable body) stays UNKNOWN and is never resent: SmsPro has no idempotency key.
 */
public final class SmsProSmsGateway implements SmsGateway, AutoCloseable {
    private static final URI PRODUCTION = URI.create("https://v2.smspro.cm");
    private static final String SEND_PATH = "/api/v1/messages";
    private static final Set<Integer> REFUSED = Set.of(400, 401, 402, 404, 422, 429);
    private static final JsonFactory JSON = new JsonFactory();
    private final SmsHttp http;
    private final URI base;
    private final String sender;
    private final char[] key;
    private boolean closed;

    /** {@code sender} must be a sender ID declared on the SmsPro account, in its exact case, for OTP use. */
    public SmsProSmsGateway(char[] apiKey, String sender) {
        this(PRODUCTION, apiKey, sender, Duration.ofSeconds(8));
    }

    // Bounded injection point for tests of the real HTTP client; no external property selects it.
    SmsProSmsGateway(URI base, char[] apiKey, String sender, Duration deadline) {
        if (!SmsHttp.plainOrigin(base) || (!PRODUCTION.equals(base) && !SmsHttp.loopback(base))
                || !validSender(sender) || !validKey(apiKey)) {
            throw invalid();
        }
        try { this.http = new SmsHttp(deadline); } catch (IllegalArgumentException wrong) { throw invalid(); }
        this.key = apiKey.clone();
        this.base = base;
        this.sender = sender;
    }

    /** A test key (smspro_test_...) is simulated by SmsPro: same checks, nothing sent or billed. */
    public static boolean isTestKey(char[] apiKey) {
        String prefix = "smspro_test_";
        if (apiKey == null || apiKey.length <= prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) { if (apiKey[i] != prefix.charAt(i)) return false; }
        return true;
    }

    static boolean validSender(String sender) {
        return sender != null && sender.matches("[A-Za-z][A-Za-z0-9]{0,10}");
    }

    static boolean validKey(char[] apiKey) {
        if (apiKey == null || apiKey.length < 16 || apiKey.length > 300) return false;
        String prefix = "smspro_";
        for (int i = 0; i < prefix.length(); i++) { if (apiKey[i] != prefix.charAt(i)) return false; }
        for (char c : apiKey) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
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
            // Every variable field has a closed alphabet without quote or control character; 1 segment, plain ASCII.
            String payload = "{\"to\":\"" + phone + "\",\"message\":\"Code WAKA : " + digits
                    + ". Valable 5 minutes. Ne le partagez pas.\",\"from\":\"" + sender + "\"}";
            body = payload.getBytes(StandardCharsets.US_ASCII);
            HttpRequest request = http.request(base.resolve(SEND_PATH)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + new String(key))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            started = true;
            HttpResponse<byte[]> response = http.exchange(request);
            responseBody = response.body();
            if (REFUSED.contains(response.statusCode())) { return Submission.REJECTED; }
            if (response.statusCode() != 201 || !SmsHttp.json(response)) { return Submission.UNKNOWN; }
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

    /** {"success": true, "message_id": "...", ...}: anything else is not proof of acceptance. */
    private static boolean accepted(byte[] body) throws IOException {
        boolean success = false;
        boolean id = false;
        var fields = new HashSet<String>();
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) { return false; }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.getString();
                if (!fields.add(field)) { return false; }
                JsonToken value = parser.nextToken();
                if ("success".equals(field)) {
                    success = value == JsonToken.VALUE_TRUE;
                } else if ("message_id".equals(field)) {
                    id = value == JsonToken.VALUE_STRING && parser.getString().matches("[0-9A-Fa-f-]{8,64}");
                } else {
                    parser.skipChildren();
                }
            }
            return success && id && parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_SMSPRO_CONFIGURATION");
    }

    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(key, '\0');
        http.close();
    }

    @Override public String toString() { return "SmsProSmsGateway[key=REDACTED]"; }
}
