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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * SasPay (founder, 23 November 2026). Contract read on 23 November 2026 at docs.saspay.me (introduction, payments,
 * payouts, webhooks, reference/formats, sandbox):
 * <ul>
 *   <li>One secret key, {@code Authorization: Bearer sk_live_...} on api.saspay.me or {@code sk_test_...} on
 *   sandbox-api.saspay.me. The environment is set by the address, not the key: WAKA refuses any other pairing,
 *   because a test key on the live address would move real money.</li>
 *   <li>{@code POST /payments/softpay/} {amount "5000.00", currency, country, network mtn_cm or orange_cm, customer}
 *   gives {id, status, checkout_url}: a push to the phone, or a SasPay page when checkout_url is not empty (Orange
 *   Money). {@code GET /payments/{id}/verify/}: status PENDING, SUCCESS or FAILED, amounts as decimal strings.</li>
 *   <li>{@code POST /payouts/initialize/} {amount, currency, country, method, recipient.msisdn, customer};
 *   {@code GET /payouts/{id}/verify/}. The server address must be declared in the SasPay account.</li>
 *   <li>{@code Idempotency-Key}: the same key with the same body returns the first answer and creates nothing. WAKA's
 *   id is the key, so a request whose answer was lost is sent again unchanged, never as a new one.</li>
 *   <li>Notifications: {@code X-Webhook-Signature} = hex HMAC-SHA256 of "{X-Webhook-Timestamp}.{raw body}" with the
 *   endpoint's signing secret, refused beyond 5 minutes. A notification is only a reason to look again.</li>
 * </ul>
 * Fees: WAKA asks SasPay to take its fee from WAKA's side ({@code fee_charge_mode}), so the payer pays exactly the price.
 * If the account does not allow that choice and the payer is charged more, the payment goes to review, never credited.
 * Never assembled by default; the key, secret and contact address come from the environment, never from Git.
 */
public final class SasPayProvider implements PaymentProvider {
    public static final URI LIVE = URI.create("https://api.saspay.me/api/v1/");
    public static final URI SANDBOX = URI.create("https://sandbox-api.saspay.me/api/v1/");
    private static final int MAX_RESPONSE = 262144;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final long SIGNATURE_WINDOW_SECONDS = 300;

    private final HttpClient http = ProviderHttp.client();
    private final URI base;
    private final String key;
    private final byte[] signingSecret;
    private final String email;
    private final Clock clock;
    /** WAKA id -> exact request whose answer was lost, sent again with the same Idempotency-Key. */
    private final Map<String, Map<String, Object>> lostAnswers = new ConcurrentHashMap<>();
    /** WAKA id -> SasPay id found by sending a lost request again. */
    private final Map<String, String> found = new ConcurrentHashMap<>();

    /**
     * @param key           sk_live_... (real money) or sk_test_... (sandbox, no money)
     * @param signingSecret the notification endpoint's signing secret, or null when this server receives none
     * @param email         contact address SasPay requires on every payment (WAKA does not collect customers' e-mails)
     */
    public SasPayProvider(String key, String signingSecret, String email) {
        this(key != null && key.startsWith("sk_live_") ? LIVE : SANDBOX, key, signingSecret, email, Clock.systemUTC());
    }

    // Bounded test seam: the two real addresses, or a local http server on 127.0.0.1 (with a test key).
    SasPayProvider(URI base, String key, String signingSecret, String email, Clock clock) {
        boolean local = base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        boolean paired = key != null && (LIVE.equals(base) ? key.startsWith("sk_live_") : (SANDBOX.equals(base) || local) && key.startsWith("sk_test_"));
        if (base == null || !(LIVE.equals(base) || SANDBOX.equals(base) || local) || !paired || !key.matches("sk_(live|test)_[!-~]{8,300}")
                || (signingSecret != null && (signingSecret.length() < 8 || signingSecret.length() > 300))
                || email == null || !email.matches("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,190}\\.[A-Za-z]{2,24}") || clock == null) {
            throw new IllegalArgumentException("SASPAY_CONFIGURATION");
        }
        this.base = base;
        this.key = key;
        this.signingSecret = signingSecret == null ? null : signingSecret.getBytes(StandardCharsets.UTF_8);
        this.email = email;
        this.clock = clock;
    }

    /** Whether this provider moves real money. */
    public boolean live() { return LIVE.equals(base); }

    @Override public String name() { return "saspay"; }

    @Override
    public Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", decimal(amountXaf));
        body.put("currency", "XAF");
        body.put("country", "CM");
        body.put("network", network(channel));
        body.put("customer", customer(phone));
        body.put("description", ProviderHttp.label(description, 100));
        body.put("fee_charge_mode", "DEDUCTED");
        body.put("metadata", Map.of("wakaPayment", ours(reference)));
        return start("payments/softpay/", reference, body);
    }

    @Override public Status payment(String reference) throws ProviderUnavailable { return lookup(reference, "payments/softpay/", "payments/"); }

    @Override
    public Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", decimal(amountXaf));
        body.put("currency", "XAF");
        body.put("country", "CM");
        body.put("method", network(channel));
        body.put("recipient", Map.of("msisdn", national(phone)));
        body.put("customer", customer(phone));
        body.put("description", ProviderHttp.label(description, 100));
        // The recipient receives the full amount; SasPay's fee is taken from WAKA's balance.
        body.put("fee_charge_mode", "ADD_ON");
        body.put("metadata", Map.of("wakaWithdrawal", ours(reference)));
        return start("payouts/initialize/", reference, body);
    }

    @Override public Status transfer(String reference) throws ProviderUnavailable { return lookup(reference, "payouts/initialize/", "payouts/"); }

    @Override
    public boolean authentic(byte[] body, UnaryOperator<String> header) {
        if (body == null || signingSecret == null || header == null) return false;
        String signature = header.apply("X-Webhook-Signature"), timestamp = header.apply("X-Webhook-Timestamp");
        if (signature == null || timestamp == null || !timestamp.matches("[0-9]{1,12}") || !signature.matches("[0-9a-fA-F]{64}")) return false;
        if (Math.abs(clock.instant().getEpochSecond() - Long.parseLong(timestamp)) > SIGNATURE_WINDOW_SECONDS) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return MessageDigest.isEqual(mac.doFinal(body), HexFormat.of().parseHex(signature.toLowerCase(java.util.Locale.ROOT)));
        } catch (GeneralSecurityException | IllegalArgumentException invalid) {
            return false;
        }
    }

    /** A notification names SasPay's transaction id; it is only a hint to look the status up again. */
    @Override
    public Set<String> references(byte[] body) {
        Set<String> names = new LinkedHashSet<>();
        try {
            JsonNode id = ProviderHttp.JSON.readTree(body).path("data").path("id");
            if (id.isString() && id.asString().matches(UUID)) names.add(id.asString());
        } catch (JacksonException unreadable) {
            // An unreadable body mentions nothing.
        }
        return names;
    }

    // ---- Requests ----

    /** A 4xx at the first attempt (unknown network, bad number, no route...) created nothing. */
    private Started start(String path, String reference, Map<String, Object> body) throws ProviderUnavailable {
        Reply reply;
        try {
            reply = post(path, reference, body);
        } catch (ProviderUnavailable lost) {
            lostAnswers.put(reference, body);
            throw lost;
        }
        if (reply.status >= 400 && reply.status < 500) return new Started(null);
        JsonNode data = data(reply), id = data.path("id");
        if (!reply.ok() || !id.isString() || !id.asString().matches(UUID)) {
            lostAnswers.put(reference, body);
            throw new ProviderUnavailable("SASPAY_START");
        }
        JsonNode page = data.path("checkout_url");
        String checkout = page.isString() && !page.asString().isBlank() ? page.asString() : null;
        // A page outside saspay.me is never handed to the app; the payment then simply stays pending and is looked up.
        return new Started(id.asString(), checkout != null && page(checkout) ? checkout : null);
    }

    /** Only SasPay's own https pages are handed to the app. */
    static boolean page(String url) {
        return url != null && url.length() <= 300 && url.matches("https://([a-z0-9-]+\\.)*saspay\\.me/[!-~&&[^\\s]]*");
    }

    /**
     * By SasPay's id. WAKA's own id means the creation answer was lost: the exact same request is sent again with the
     * same Idempotency-Key, which SasPay documents as returning the first answer, and the id found is remembered. If
     * that request is refused, or this server restarted in between, the item stays PENDING and finance reconciles it
     * in the SasPay dashboard. Unknown is never a failure.
     */
    private Status lookup(String reference, String createPath, String verifyPrefix) throws ProviderUnavailable {
        if (reference == null || !reference.matches(UUID)) throw new IllegalArgumentException("SASPAY_REFERENCE");
        String saspay = found.get(reference);
        if (saspay == null) {
            Map<String, Object> lost = lostAnswers.get(reference);
            if (lost != null) {
                Reply again = post(createPath, reference, lost);
                JsonNode id = data(again).path("id");
                if (!again.ok() || !id.isString() || !id.asString().matches(UUID)) return new Status(State.PENDING, null, null);
                saspay = id.asString();
                found.put(reference, saspay);
                lostAnswers.remove(reference);
            } else {
                saspay = reference;
            }
        }
        Reply one = get(verifyPrefix + saspay + "/verify/");
        if (one.status == 404) return new Status(State.PENDING, null, null);
        if (!one.ok()) throw new ProviderUnavailable("SASPAY_STATUS");
        return status(data(one), "payouts/".equals(verifyPrefix));
    }

    /**
     * For a payment the amount reported is what the payer was charged ({@code debited_amount}); for a withdrawal, what
     * the recipient received ({@code net_amount}). Anything that is not whole francs is not trusted.
     */
    static Status status(JsonNode tx, boolean payout) throws ProviderUnavailable {
        JsonNode word = tx.path("status");
        if (!word.isString()) throw new ProviderUnavailable("SASPAY_STATUS_MISSING");
        State state = switch (word.asString()) {
            case "SUCCESS" -> State.SUCCEEDED;
            case "FAILED", "CANCELLED" -> State.FAILED;
            case "PENDING" -> State.PENDING;
            default -> throw new ProviderUnavailable("SASPAY_STATUS_UNKNOWN");
        };
        String currency = tx.path("currency").isString() ? tx.path("currency").asString() : null;
        return new Status(state, ProviderHttp.wholeXaf(tx.path(payout ? "net_amount" : "debited_amount")), currency);
    }

    /** SasPay wraps answers in {success, data, code}; some examples show the fields at the top. */
    private static JsonNode data(Reply reply) {
        JsonNode data = reply.body.path("data");
        return data.isObject() ? data : reply.body;
    }

    private Reply post(String path, String reference, Map<String, Object> body) throws ProviderUnavailable {
        HttpRequest request = request(path).header("Content-Type", "application/json").header("Idempotency-Key", ours(reference))
                .POST(ProviderHttp.json(body)).build();
        return ProviderHttp.send(http, request, MAX_RESPONSE, "SASPAY");
    }

    private Reply get(String path) throws ProviderUnavailable {
        return ProviderHttp.send(http, request(path).GET().build(), MAX_RESPONSE, "SASPAY");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json").header("Authorization", "Bearer " + key);
    }

    private Map<String, Object> customer(String phone) {
        Map<String, Object> customer = new LinkedHashMap<>();
        customer.put("email", email);
        customer.put("first_name", "Client");
        customer.put("last_name", "WAKA");
        customer.put("phone", international(phone));
        return customer;
    }

    private static String network(String channel) {
        return switch (channel == null ? "" : channel) {
            case "cm.mtn" -> "mtn_cm";
            case "cm.orange" -> "orange_cm";
            default -> throw new IllegalArgumentException("SASPAY_CHANNEL");
        };
    }

    private static String decimal(long amountXaf) { return ProviderHttp.positive(amountXaf, "SASPAY") + ".00"; }

    private static String ours(String reference) {
        if (reference == null || !reference.matches(UUID)) throw new IllegalArgumentException("SASPAY_REFERENCE");
        return reference;
    }

    private static String international(String phone) {
        if (phone == null || !phone.matches("\\+2376[0-9]{8}")) throw new IllegalArgumentException("SASPAY_PHONE");
        return phone;
    }

    /** The payout examples use the nine national digits (677889900). */
    private static String national(String phone) { return international(phone).substring(4); }

    @Override public String toString() { return "SasPayProvider[REDACTED]"; }
}
