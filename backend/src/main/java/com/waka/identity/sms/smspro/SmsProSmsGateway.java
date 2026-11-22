package com.waka.identity.sms.smspro;

import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/**
 * SmsPro (Mediasystem SARL, Yaounde): one account and one key for MTN and Orange Cameroon (founder, 22 November 2026,
 * after Orange announced the end of its SMS Cameroon API on 1 December 2026). Contract read on 22 November 2026 from
 * https://v2.smspro.cm/docs/api: POST /api/v1/messages {to, message, from}, bearer key, 201 when accepted.
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
    private static final int MAX_RESPONSE = 8192;
    private final HttpClient http;
    private final URI base;
    private final String sender;
    private final Duration deadline;
    private final char[] key;
    private boolean closed;

    /** {@code sender} must be a sender ID declared on the SmsPro account, in its exact case, for OTP use. */
    public SmsProSmsGateway(char[] apiKey, String sender) {
        this(PRODUCTION, apiKey, sender, Duration.ofSeconds(8));
    }

    // Bounded injection point for tests of the real HTTP client; no external property selects it.
    SmsProSmsGateway(URI base, char[] apiKey, String sender, Duration deadline) {
        boolean localTest = base != null && "http".equals(base.getScheme())
                && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || (!PRODUCTION.equals(base) && !localTest)
                || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !base.getRawPath().isEmpty()
                || !validSender(sender) || !validKey(apiKey)
                || deadline == null || deadline.toMillis() < 50 || deadline.compareTo(Duration.ofSeconds(8)) > 0) {
            throw invalid();
        }
        this.key = apiKey.clone();
        this.base = base;
        this.sender = sender;
        this.deadline = deadline;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
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
            HttpRequest request = HttpRequest.newBuilder(base.resolve(SEND_PATH)).timeout(deadline)
                    .header("Accept", "application/json").header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + new String(key))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            started = true;
            HttpResponse<byte[]> response = exchange(request);
            responseBody = response.body();
            if (REFUSED.contains(response.statusCode())) { return Submission.REJECTED; }
            if (response.statusCode() != 201 || !json(response)) { return Submission.UNKNOWN; }
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

    private HttpResponse<byte[]> exchange(HttpRequest request) throws Exception {
        var pending = http.sendAsync(request, ignored -> new BoundedBody());
        try { return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception failure) { pending.cancel(true); throw failure; }
    }

    private static boolean json(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").map(s ->
                "application/json".equalsIgnoreCase(s.split(";", 2)[0].trim())).orElse(false);
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
        http.shutdownNow();
    }

    @Override public String toString() { return "SmsProSmsGateway[key=REDACTED]"; }

    /** The deadline also covers the body; the subscription is cancelled at the first byte over the limit. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ErasableOutput output = new ErasableOutput();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (output.size() + chunk.remaining() > MAX_RESPONSE) {
                    subscription.cancel();
                    output.erase();
                    result.completeExceptionally(new IOException("SMS_RESPONSE_LIMIT"));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                output.writeBytes(part);
                Arrays.fill(part, (byte) 0);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {
            output.erase();
            result.completeExceptionally(new IOException("SMS_HTTP_FAILED"));
        }
        @Override public void onComplete() {
            byte[] bytes = output.toByteArray();
            output.erase();
            result.complete(bytes);
        }
    }

    private static final class ErasableOutput extends ByteArrayOutputStream {
        private void erase() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
