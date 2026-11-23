package com.waka.finance;

import com.waka.finance.ProviderHttp.Reply;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * CamPay (founder, 23 November 2026: Fapshi did not validate the real account). Contract read on 23 November 2026
 * in the CamPay Postman documentation (documenter.getpostman.com/view/2391374/T1LV8PVA):
 * <ul>
 *   <li>{@code POST /api/collect/} {amount, currency XAF, from 2376XXXXXXXX, description, external_reference} gives a
 *   {@code reference}; the payer confirms with their PIN; {@code POST /api/withdraw/} {amount, to, description,
 *   external_reference} sends money (withdrawals must be allowed in the app settings).</li>
 *   <li>{@code GET /api/transaction/{reference}/}: status PENDING, SUCCESSFUL or FAILED.</li>
 *   <li>Permanent app token: {@code Authorization: Token ...}.</li>
 *   <li>Both creations are idempotent on {@code external_reference} (a UUID4): the same one returns the first result.
 *   WAKA's id is sent there, so a request whose answer was lost is sent again with the same id, never with a new one.</li>
 *   <li>Notifications are sent once and never retried, with a {@code signature} JWT signed HS256 with the app's
 *   webhook key. A notification is only a hint: the status is always read back with the token.</li>
 * </ul>
 * Never assembled by default; the token and webhook key come from the environment, never from Git.
 */
public final class CamPayProvider implements PaymentProvider {
    public static final URI DEMO = URI.create("https://demo.campay.net");
    /** Live address to confirm in the CamPay account once it goes live; the documentation only shows the demo one. */
    public static final URI LIVE = URI.create("https://www.campay.net");
    private static final int MAX_RESPONSE = 262144;
    private static final JsonMapper JSON = ProviderHttp.JSON;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String REFERENCE = "[0-9A-Za-z-]{8,64}";

    private final HttpClient http;
    private final URI base;
    private final String token;
    private final byte[] webhookKey;
    private final Clock clock;
    /** WAKA id -> exact request whose answer was lost, to send it again with the same external_reference. */
    private final Map<String, Map<String, Object>> lostAnswers = new java.util.concurrent.ConcurrentHashMap<>();
    /** WAKA id -> CamPay reference found by sending a lost request again. */
    private final Map<String, String> found = new java.util.concurrent.ConcurrentHashMap<>();

    public CamPayProvider(boolean live, String token, String webhookKey) {
        this(live ? LIVE : DEMO, token, webhookKey, Clock.systemUTC());
    }

    // Bounded test seam: only the two real addresses or a local http server on 127.0.0.1.
    CamPayProvider(URI base, String token, String webhookKey, Clock clock) {
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || !(LIVE.equals(base) || DEMO.equals(base) || local) || token == null || !token.matches("[!-~]{20,300}")
                || webhookKey == null || webhookKey.length() < 8 || webhookKey.length() > 300 || clock == null) {
            throw new IllegalArgumentException("CAMPAY_CONFIGURATION");
        }
        this.base = base;
        this.token = token;
        this.webhookKey = webhookKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.http = ProviderHttp.client();
    }

    @Override public String name() { return "campay"; }

    @Override
    public Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Long.toString(ProviderHttp.positive(amountXaf, "CAMPAY")));
        body.put("currency", "XAF");
        body.put("from", international(phone));
        body.put("description", ProviderHttp.label(description, 100));
        body.put("external_reference", ours(reference));
        return start("/api/collect/", body);
    }
    @Override public Status payment(String reference) throws ProviderUnavailable { return lookup(reference, "/api/collect/"); }

    @Override
    public Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", Long.toString(ProviderHttp.positive(amountXaf, "CAMPAY")));
        body.put("to", international(phone));
        body.put("description", ProviderHttp.label(description, 100));
        body.put("external_reference", ours(reference));
        return start("/api/withdraw/", body);
    }
    @Override public Status transfer(String reference) throws ProviderUnavailable { return lookup(reference, "/api/withdraw/"); }

    /** The {@code signature} field of a POST notification must be a JWT signed HS256 with the webhook key, not expired. */
    @Override public boolean authentic(byte[] body, UnaryOperator<String> header) {
        if (body == null) return false;
        try {
            JsonNode signature = JSON.readTree(body).path("signature");
            return signature.isString() && validJwt(signature.asString());
        } catch (JacksonException unreadable) {
            return false;
        }
    }

    boolean validJwt(String jwt) {
        String[] parts = jwt.split("\\.", -1);
        if (parts.length != 3 || jwt.length() > 4096) return false;
        try {
            var decoder = Base64.getUrlDecoder();
            JsonNode head = JSON.readTree(decoder.decode(parts[0]));
            if (!"HS256".equals(head.path("alg").asString(""))) return false;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookKey, "HmacSHA256"));
            byte[] expected = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(expected, decoder.decode(parts[2]))) return false;
            JsonNode exp = JSON.readTree(decoder.decode(parts[1])).path("exp");
            return !exp.isNumber() || exp.asLong() > clock.instant().getEpochSecond();
        } catch (IllegalArgumentException | JacksonException | GeneralSecurityException invalid) {
            return false;
        }
    }

    /** A notification names CamPay's reference and WAKA's id; each is only a hint to look the status up again. */
    @Override public Set<String> references(byte[] body) {
        Set<String> found = new LinkedHashSet<>();
        try {
            JsonNode tx = JSON.readTree(body);
            for (String field : new String[] { "reference", "external_reference" }) {
                JsonNode value = tx.path(field);
                if (value.isString() && value.asString().matches(REFERENCE)) found.add(value.asString());
            }
        } catch (JacksonException ignored) {
            // An unreadable body mentions nothing.
        }
        return found;
    }

    // ---- Requests ----

    /** A 4xx at the first attempt (ER101 number, ER102 operator, ER201 amount, ER301 balance...) created nothing. */
    private Started start(String path, Map<String, Object> body) throws ProviderUnavailable {
        String ours = (String) body.get("external_reference");
        Reply reply;
        try {
            reply = call(path, body);
        } catch (ProviderUnavailable lost) {
            lostAnswers.put(ours, new LinkedHashMap<>(body));
            throw lost;
        }
        if (reply.status >= 400 && reply.status < 500) return new Started(null);
        JsonNode reference = reply.body.path("reference");
        if (!reply.ok() || !reference.isString() || !reference.asString().matches(REFERENCE)) {
            lostAnswers.put(ours, new LinkedHashMap<>(body));
            throw new ProviderUnavailable("CAMPAY_START");
        }
        return new Started(reference.asString());
    }

    /**
     * By CamPay's reference. When the creation answer was lost, WAKA looks up its own id: the exact same request is
     * sent again with the same {@code external_reference}, which CamPay documents as returning the first result, and
     * the reference found is remembered. If that request is refused or this server restarted in between, the item
     * stays PENDING and finance reconciles it in the CamPay history. Unknown is never a failure.
     */
    private Status lookup(String reference, String path) throws ProviderUnavailable {
        if (reference == null || !reference.matches(REFERENCE)) throw new IllegalArgumentException("CAMPAY_REFERENCE");
        String campay = found.get(reference);
        if (campay == null) {
            Map<String, Object> lost = lostAnswers.get(reference);
            if (lost != null) {
                Reply again = call(path, lost);
                JsonNode ref = again.body.path("reference");
                if (!again.ok() || !ref.isString() || !ref.asString().matches(REFERENCE)) return new Status(State.PENDING, null, null);
                campay = ref.asString();
                found.put(reference, campay);
                lostAnswers.remove(reference);
            } else {
                campay = reference;
            }
        }
        Reply one = call("/api/transaction/" + campay + "/", null);
        if (one.status == 404) return new Status(State.PENDING, null, null);
        if (!one.ok()) throw new ProviderUnavailable("CAMPAY_STATUS");
        return status(one.body);
    }

    private static Status status(JsonNode tx) throws ProviderUnavailable {
        JsonNode word = tx.path("status");
        if (!word.isString()) throw new ProviderUnavailable("CAMPAY_STATUS_MISSING");
        State state = switch (word.asString()) {
            case "SUCCESSFUL" -> State.SUCCEEDED;
            case "FAILED" -> State.FAILED;
            case "PENDING" -> State.PENDING;
            default -> throw new ProviderUnavailable("CAMPAY_STATUS_UNKNOWN");
        };
        String currency = tx.path("currency").isString() ? tx.path("currency").asString() : null;
        return new Status(state, ProviderHttp.wholeXaf(tx.path("amount")), currency);
    }

    private Reply call(String path, Object body) throws ProviderUnavailable {
        HttpRequest.Builder request = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Authorization", "Token " + token);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(ProviderHttp.json(body));
        return ProviderHttp.send(http, request.build(), MAX_RESPONSE, "CAMPAY");
    }

    private static String ours(String reference) {
        if (reference == null || !reference.matches(UUID)) throw new IllegalArgumentException("CAMPAY_REFERENCE");
        return reference;
    }

    /** CamPay expects the country code without the plus: 2376XXXXXXXX. */
    private static String international(String phone) {
        if (phone == null || !phone.matches("\\+2376[0-9]{8}")) throw new IllegalArgumentException("CAMPAY_PHONE");
        return phone.substring(1);
    }

    @Override public String toString() { return "CamPayProvider[REDACTED]"; }
}
