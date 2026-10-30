package com.waka.finance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fapshi (R14), from its public API reference. Collections use {@code POST /direct-pay} with the collection
 * service's {@code apiuser}/{@code apikey}; payouts use {@code POST /payout} with a separate payout service
 * (Fapshi forbids collecting on a service once its payouts are enabled). Statuses are CREATED, PENDING,
 * SUCCESSFUL, FAILED and EXPIRED, read with {@code GET /payment-status/{transId}}. Fapshi has no idempotency:
 * WAKA's id is sent as {@code userId} and {@code externalId}, and a request whose answer was lost is found again
 * with {@code GET /transaction/{userId}}, never sent twice. Notifications carry a static secret in
 * {@code x-wh-secret} and are never retried by Fapshi, so pending items must also be looked up periodically.
 * Never assembled by default; credentials come from the environment, never from Git.
 */
public final class FapshiProvider implements PaymentProvider {
    public static final URI LIVE = URI.create("https://live.fapshi.com"), SANDBOX = URI.create("https://sandbox.fapshi.com");
    private static final int MAX_RESPONSE = 262144;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OUR_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    public record Credentials(String user, String key) {
        public Credentials {
            if (user == null || key == null || !user.matches("[A-Za-z0-9_.-]{4,200}") || !key.matches("[A-Za-z0-9_.-]{8,300}")) {
                throw new IllegalArgumentException("FAPSHI_CONFIGURATION");
            }
        }
        @Override public String toString() { return "Credentials[REDACTED]"; }
    }

    private final HttpClient http;
    private final URI base;
    private final Credentials collection, payout;
    private final byte[] webhookSecret;

    public FapshiProvider(boolean live, Credentials collection, Credentials payout, String webhookSecret) {
        this(live ? LIVE : SANDBOX, collection, payout, webhookSecret);
    }

    // Bounded test seam: only the two real addresses or a local http server on 127.0.0.1.
    FapshiProvider(URI base, Credentials collection, Credentials payout, String webhookSecret) {
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || !(LIVE.equals(base) || SANDBOX.equals(base) || local) || collection == null || payout == null
                || webhookSecret == null || webhookSecret.length() < 16 || webhookSecret.length() > 200) {
            throw new IllegalArgumentException("FAPSHI_CONFIGURATION");
        }
        this.base = base;
        this.collection = collection;
        this.payout = payout;
        this.webhookSecret = webhookSecret.getBytes(StandardCharsets.UTF_8);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public String name() { return "fapshi"; }

    @Override
    public Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        return start("/direct-pay", collection, reference, amountXaf, channel, phone, description);
    }
    @Override public Status payment(String reference) throws ProviderUnavailable { return lookup(reference, collection); }

    @Override
    public Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        return start("/payout", payout, reference, amountXaf, channel, phone, description);
    }
    @Override public Status transfer(String reference) throws ProviderUnavailable { return lookup(reference, payout); }

    @Override public boolean authentic(byte[] body, UnaryOperator<String> header) {
        String secret = header.apply("x-wh-secret");
        return body != null && secret != null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), webhookSecret);
    }

    /** A notification is the transaction itself: its Fapshi id and WAKA's id. */
    @Override public Set<String> references(byte[] body) {
        Set<String> found = new LinkedHashSet<>();
        try {
            JsonNode tx = JSON.readTree(body);
            for (String field : new String[] { "transId", "externalId" }) {
                JsonNode value = tx.path(field);
                if (value.isString() && value.asString().matches("[A-Za-z0-9_-]{1,100}")) found.add(value.asString());
            }
        } catch (JacksonException ignored) {
            // An unreadable body mentions nothing.
        }
        return found;
    }

    // ---- Requests ----

    private Started start(String path, Credentials credentials, String reference, long amountXaf, String channel, String phone,
            String description) throws ProviderUnavailable {
        if (!reference.matches(OUR_ID)) throw new IllegalArgumentException("FAPSHI_REFERENCE");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountXaf);
        body.put("phone", national(phone));
        body.put("medium", "cm.orange".equals(channel) ? "orange money" : "mobile money");
        body.put("userId", reference);
        body.put("externalId", reference);
        body.put("message", description);
        Reply reply = call("POST", path, body, credentials);
        if (reply.status >= 400 && reply.status < 500) return new Started(null);
        JsonNode transId = reply.body.path("transId");
        if (!reply.ok() || !transId.isString() || !transId.asString().matches("[A-Za-z0-9_-]{1,100}")) throw new ProviderUnavailable("FAPSHI_START");
        return new Started(transId.asString());
    }

    /**
     * By Fapshi id, or by WAKA's id when the creation answer was lost. Unknown is never a failure: money is
     * never refunded or lost on a missing record; finance reconciles it by hand.
     */
    private Status lookup(String reference, Credentials credentials) throws ProviderUnavailable {
        if (reference == null || !reference.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("FAPSHI_REFERENCE");
        if (reference.matches(OUR_ID)) {
            Reply list = call("GET", "/transaction/" + reference, null, credentials);
            if (list.status == 404) return new Status(State.PENDING, null, null);
            if (!list.ok() || !list.body.isArray()) throw new ProviderUnavailable("FAPSHI_TRANSACTIONS");
            JsonNode match = null;
            for (JsonNode tx : list.body) {
                if (tx.path("externalId").isString() && reference.equals(tx.path("externalId").asString())) match = tx;
            }
            return match == null ? new Status(State.PENDING, null, null) : status(match);
        }
        Reply one = call("GET", "/payment-status/" + reference, null, credentials);
        if (one.status == 404) return new Status(State.PENDING, null, null);
        if (!one.ok()) throw new ProviderUnavailable("FAPSHI_STATUS");
        return status(one.body);
    }

    private static Status status(JsonNode tx) throws ProviderUnavailable {
        JsonNode word = tx.path("status");
        if (!word.isString()) throw new ProviderUnavailable("FAPSHI_STATUS_MISSING");
        State state = switch (word.asString()) {
            case "SUCCESSFUL" -> State.SUCCEEDED;
            case "FAILED", "EXPIRED" -> State.FAILED;
            case "CREATED", "PENDING" -> State.PENDING;
            default -> throw new ProviderUnavailable("FAPSHI_STATUS_UNKNOWN");
        };
        JsonNode amount = tx.path("amount");
        // Fapshi works only in XAF and does not repeat the currency.
        return new Status(state, amount.isIntegralNumber() ? Long.valueOf(amount.asLong()) : null, "XAF");
    }

    private record Reply(int status, JsonNode body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    private Reply call(String method, String path, Object body, Credentials credentials) throws ProviderUnavailable {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json").header("apiuser", credentials.user()).header("apikey", credentials.key());
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            }
            HttpResponse<java.io.InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var in = response.body()) { raw = in.readNBytes(MAX_RESPONSE + 1); }
            if (raw.length > MAX_RESPONSE) throw new ProviderUnavailable("FAPSHI_RESPONSE_TOO_LARGE");
            JsonNode json;
            try { json = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw); }
            catch (JacksonException unreadable) { json = JSON.createObjectNode(); }
            return new Reply(response.statusCode(), json);
        } catch (IOException error) {
            throw new ProviderUnavailable("FAPSHI_UNREACHABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailable("FAPSHI_INTERRUPTED");
        }
    }

    /** Fapshi expects the nine national digits (6XXXXXXXX). */
    private static String national(String phone) {
        if (phone == null || !phone.matches("\\+2376[0-9]{8}")) throw new IllegalArgumentException("FAPSHI_PHONE");
        return phone.substring(4);
    }

    @Override public String toString() { return "FapshiProvider[REDACTED]"; }
}
