package com.waka.finance;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notch Pay (R14), from its public API reference: {@code POST /payments} then {@code PUT /payments/{ref}}
 * with the channel ({@code cm.mtn}, {@code cm.orange}) to push the confirmation to the payer's phone;
 * {@code GET /payments/{ref}}; {@code POST /transfers} and {@code GET /transfers/{ref}} with the private
 * key in {@code X-Grant}; notifications signed with HMAC-SHA256 of the raw body in {@code x-notch-signature}.
 * The status words and response shapes are read leniently and must be confirmed in test mode before any
 * live key is used. Never assembled by default; keys come from the environment, never from Git.
 */
public final class NotchPayProvider implements PaymentProvider {
    private static final URI PRODUCTION = URI.create("https://api.notchpay.co");
    private static final int MAX_RESPONSE = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> DONE = Set.of("complete", "completed", "success", "successful");
    private static final Set<String> FAILED = Set.of("failed", "canceled", "cancelled", "expired", "rejected", "abandoned");

    private final HttpClient http;
    private final URI base;
    private final String publicKey, privateKey;
    private final byte[] hashKey;

    public NotchPayProvider(String publicKey, String privateKey, String hashKey) {
        this(PRODUCTION, publicKey, privateKey, hashKey);
    }

    // Bounded test seam: only a local http server on 127.0.0.1 may replace the real address.
    NotchPayProvider(URI base, String publicKey, String privateKey, String hashKey) {
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || (!PRODUCTION.equals(base) && !local) || !key(publicKey) || !key(privateKey) || !key(hashKey)) {
            throw new IllegalArgumentException("NOTCHPAY_CONFIGURATION");
        }
        this.base = base;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.hashKey = hashKey.getBytes(StandardCharsets.US_ASCII);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    private static boolean key(String value) { return value != null && value.matches("[A-Za-z0-9_.-]{8,200}"); }

    @Override public String name() { return "notchpay"; }

    @Override
    public Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> init = new LinkedHashMap<>();
        init.put("amount", amountXaf);
        init.put("currency", "XAF");
        init.put("phone", digits(phone));
        init.put("description", description);
        init.put("reference", reference);
        init.put("locked_channel", channel);
        init.put("locked_country", "CM");
        Reply created = call("POST", "/payments", init, false);
        if (created.status >= 400 && created.status < 500) return new Started(null);
        String providerReference = text(created.body.path("transaction"), "reference");
        if (!created.ok() || providerReference == null) throw new ProviderUnavailable("NOTCHPAY_INITIALIZE");
        Reply pushed = call("PUT", "/payments/" + path(providerReference),
                Map.of("channel", channel, "data", Map.of("phone", digits(phone), "country", "CM")), false);
        // The payment exists even if the push to the phone failed: it will simply never be confirmed.
        if (!pushed.ok() && pushed.status >= 500) throw new ProviderUnavailable("NOTCHPAY_PROCESS");
        return new Started(providerReference);
    }

    @Override public Status payment(String providerReference) throws ProviderUnavailable {
        return status(call("GET", "/payments/" + path(providerReference), null, false), "transaction");
    }

    @Override
    public Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountXaf);
        body.put("currency", "XAF");
        body.put("description", description);
        body.put("recipient", digits(phone));
        body.put("channel", channel);
        body.put("reference", reference);
        Reply sent = call("POST", "/transfers", body, true);
        if (sent.status >= 400 && sent.status < 500) return new Started(null);
        String providerReference = text(sent.body.path("transfer"), "reference");
        if (!sent.ok() || providerReference == null) throw new ProviderUnavailable("NOTCHPAY_TRANSFER");
        return new Started(providerReference);
    }

    @Override public Status transfer(String reference) throws ProviderUnavailable {
        return status(call("GET", "/transfers/" + path(reference), null, true), "transfer");
    }

    @Override public boolean authentic(byte[] body, String signature) {
        if (body == null || signature == null || !signature.matches("[0-9a-fA-F]{64}")) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hashKey, "HmacSHA256"));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.toLowerCase(Locale.ROOT)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            return false;
        }
    }

    /** The references a notification mentions; each one is only a hint to look the status up again. */
    public static Set<String> mentionedReferences(byte[] body) {
        Set<String> found = new java.util.LinkedHashSet<>();
        try {
            JsonNode data = JSON.readTree(body).path("data");
            for (String field : new String[] { "id", "reference", "merchant_reference", "trxref" }) {
                String value = text(data, field);
                if (value != null && value.length() <= 100) found.add(value);
            }
        } catch (JacksonException ignored) {
            // An unreadable body mentions nothing.
        }
        return found;
    }

    // ---- HTTP ----

    private record Reply(int status, JsonNode body) {
        boolean ok() { return status >= 200 && status < 300; }
    }

    private Reply call(String method, String path, Object body, boolean grant) throws ProviderUnavailable {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json").header("Authorization", publicKey);
            if (grant) request.header("X-Grant", privateKey);
            if (body == null) {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
            }
            HttpResponse<java.io.InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (var in = response.body()) { raw = in.readNBytes(MAX_RESPONSE + 1); }
            if (raw.length > MAX_RESPONSE) throw new ProviderUnavailable("NOTCHPAY_RESPONSE_TOO_LARGE");
            JsonNode json;
            try { json = raw.length == 0 ? JSON.createObjectNode() : JSON.readTree(raw); }
            catch (JacksonException unreadable) { json = JSON.createObjectNode(); }
            return new Reply(response.statusCode(), json);
        } catch (IOException error) {
            throw new ProviderUnavailable("NOTCHPAY_UNREACHABLE");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailable("NOTCHPAY_INTERRUPTED");
        }
    }

    private static Status status(Reply reply, String field) throws ProviderUnavailable {
        // Unknown at the provider is not a failure: money is never refunded or lost on a missing record;
        // finance reconciles it by hand.
        if (reply.status == 404) return new Status(State.PENDING, null, null);
        if (!reply.ok()) throw new ProviderUnavailable("NOTCHPAY_STATUS");
        JsonNode node = reply.body.path(field);
        String word = text(node, "status");
        if (word == null) throw new ProviderUnavailable("NOTCHPAY_STATUS_MISSING");
        word = word.toLowerCase(Locale.ROOT);
        State state = DONE.contains(word) ? State.SUCCEEDED : FAILED.contains(word) ? State.FAILED : State.PENDING;
        JsonNode amount = node.path("amount");
        Long xaf = amount.isIntegralNumber() ? Long.valueOf(amount.asLong())
                : amount.isString() && amount.asString().matches("[0-9]{1,9}") ? Long.valueOf(amount.asString()) : null;
        return new Status(state, xaf, text(node, "currency"));
    }

    /** A field of an object, or the node itself when the provider sent a bare string. */
    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isString()) return "reference".equals(field) || "id".equals(field) ? node.asString() : null;
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }
    private static String digits(String phone) { return phone.startsWith("+") ? phone.substring(1) : phone; }
    private static String path(String reference) {
        if (reference == null || !reference.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("NOTCHPAY_REFERENCE");
        return URLEncoder.encode(reference, StandardCharsets.US_ASCII);
    }

    @Override public String toString() { return "NotchPayProvider[REDACTED]"; }
}
