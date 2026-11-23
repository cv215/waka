package com.waka.finance;

import com.waka.finance.ProviderHttp.Reply;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MoneyFusion (founder, 23 November 2026: CamPay needs a registered company to go live; MoneyFusion accepts an
 * individual). Contract read on 23 November 2026 at docs.moneyfusion.net (webapi, payout) and moneyfusion.net (faq, fees):
 * <ul>
 *   <li>Payin: {@code POST <the application's own API address, from the dashboard>} {totalPrice, article, personal_Info,
 *   numeroSend, nomclient, return_url, webhook_url} gives {statut, token, url}. The payer chooses the operator and
 *   confirms on that page ({@code checkoutUrl}), not on a push to the phone. No key header: MoneyFusion only accepts
 *   the server addresses declared in the application.</li>
 *   <li>{@code GET https://pay.moneyfusion.net/paiementNotif/{token}}: data.statut pending, paid, failure or "no paid";
 *   the amount is data.Montant. No currency is returned: the application is a Cameroon one, priced in XAF.</li>
 *   <li>Payout: {@code POST https://pay.moneyfusion.net/api/v1/withdraw} with header {@code moneyfusion-private-key}
 *   {countryCode cm, phone, amount, withdraw_mode mtn-cm or orange-cm} gives {statut, tokenPay};
 *   {@code GET /api/v1/payout/check-status/{tokenPay}}: status pending, success or failed. Codes cm, mtn-cm and orange-cm
 *   read from {@code GET /api/v1/withdraw/methods}.</li>
 *   <li>No sandbox: every call moves real money. No idempotency key and no signature on notifications: a notification
 *   is only a reason to look again, with WAKA's own reading of the status.</li>
 *   <li>Fees (moneyfusion.net/fees, Cameroon): 3 % Orange and MTN.</li>
 * </ul>
 * Never assembled by default; the payment address and the private key come from the environment, never from Git.
 */
public final class MoneyFusionProvider implements PaymentProvider {
    public static final URI API = URI.create("https://pay.moneyfusion.net");
    private static final int MAX_RESPONSE = 262144;
    private static final JsonMapper JSON = ProviderHttp.JSON;
    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String TOKEN = "[0-9A-Za-z_-]{6,64}";
    /** The application's payment address: https, on a moneyfusion.net host, no query. */
    private static final String PAY_ADDRESS = "https://([a-z0-9-]+\\.)*moneyfusion\\.net/[!-~&&[^?#\\s]]{1,250}";

    private final HttpClient http;
    private final URI payAddress;
    private final URI api;
    private final String privateKey;
    private final String webhookUrl;

    /**
     * @param payAddress the application's API address from the MoneyFusion dashboard
     * @param privateKey the withdrawal key from "Paramètre", or null: withdrawals are then refused without sending anything
     * @param webhookUrl WAKA's public notification address, or null when this server cannot be reached from outside
     */
    public MoneyFusionProvider(String payAddress, String privateKey, String webhookUrl) {
        this(checkedPayAddress(payAddress), API, privateKey, webhookUrl);
    }

    // Bounded test seam: the real addresses, or a local http server on 127.0.0.1.
    MoneyFusionProvider(URI payAddress, URI api, String privateKey, String webhookUrl) {
        if (payAddress == null || api == null || !(API.equals(api) || local(api)) || !(local(payAddress) || payAddress.toString().matches(PAY_ADDRESS))
                || (privateKey != null && !privateKey.matches("[!-~]{16,300}"))
                || (webhookUrl != null && !webhookUrl.matches("https://[!-~&&[^\\s]]{8,250}"))) {
            throw new IllegalArgumentException("MONEYFUSION_CONFIGURATION");
        }
        this.payAddress = payAddress;
        this.api = api;
        this.privateKey = privateKey;
        this.webhookUrl = webhookUrl;
        this.http = ProviderHttp.client();
    }

    private static URI checkedPayAddress(String address) {
        if (address == null || !address.strip().matches(PAY_ADDRESS)) throw new IllegalArgumentException("MONEYFUSION_CONFIGURATION");
        return URI.create(address.strip());
    }

    private static boolean local(URI uri) {
        return uri != null && "http".equals(uri.getScheme()) && "127.0.0.1".equals(uri.getHost()) && uri.getPort() > 0;
    }

    @Override public String name() { return "moneyfusion"; }

    /** The channel is not sent: the payer chooses Orange or MTN on MoneyFusion's page. */
    @Override
    public Started collect(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalPrice", ProviderHttp.positive(amountXaf, "MONEYFUSION"));
        body.put("article", List.of(Map.of(ProviderHttp.label(description, 60), amountXaf)));
        body.put("personal_Info", List.of(Map.of("wakaPayment", ours(reference))));
        body.put("numeroSend", local(phone));
        body.put("nomclient", "Client WAKA");
        if (webhookUrl != null) body.put("webhook_url", webhookUrl);
        Reply reply = call(payAddress, body, false);
        if (reply.status >= 400 && reply.status < 500) return new Started(null);
        JsonNode statut = reply.body.path("statut"), token = reply.body.path("token"), url = reply.body.path("url");
        if (reply.ok() && statut.isBoolean() && !statut.asBoolean()) return new Started(null);
        if (!reply.ok() || !statut.asBoolean(false) || !token.isString() || !token.asString().matches(TOKEN) || !url.isString() || !page(url.asString())) {
            // No idempotency key: nothing can be asked again. The payer never saw a page, so nothing can be paid.
            throw new ProviderUnavailable("MONEYFUSION_START");
        }
        return new Started(token.asString(), url.asString());
    }

    /** Only MoneyFusion's own https pages are handed to the app. */
    static boolean page(String url) {
        return url != null && url.length() <= 300 && url.matches("https://([a-z0-9-]+\\.)*moneyfusion\\.net/[!-~&&[^\\s]]*");
    }

    /**
     * By MoneyFusion's token. WAKA's own id means the creation answer was lost: no page was shown, so nothing can have
     * been paid and the payment has failed. "no paid" stays pending: it may only mean the page is not used yet.
     */
    @Override
    public Status payment(String reference) throws ProviderUnavailable {
        if (reference != null && reference.matches(UUID)) return new Status(State.FAILED, null, null);
        Reply one = call(api.resolve("/paiementNotif/" + token(reference)), null, false);
        if (one.status == 404) return new Status(State.PENDING, null, null);
        JsonNode data = one.body.path("data");
        if (!one.ok() || !one.body.path("statut").asBoolean(false) || !data.isObject()) throw new ProviderUnavailable("MONEYFUSION_STATUS");
        JsonNode word = data.path("statut");
        if (!word.isString()) throw new ProviderUnavailable("MONEYFUSION_STATUS_MISSING");
        State state = switch (word.asString()) {
            case "paid" -> State.SUCCEEDED;
            case "failure" -> State.FAILED;
            case "pending", "no paid" -> State.PENDING;
            default -> throw new ProviderUnavailable("MONEYFUSION_STATUS_UNKNOWN");
        };
        return new Status(state, ProviderHttp.wholeXaf(data.path("Montant")), "XAF");
    }

    @Override
    public Started send(String reference, long amountXaf, String channel, String phone, String description) throws ProviderUnavailable {
        ours(reference);
        if (privateKey == null) return new Started(null);
        String mode = switch (channel == null ? "" : channel) {
            case "cm.mtn" -> "mtn-cm";
            case "cm.orange" -> "orange-cm";
            default -> throw new IllegalArgumentException("MONEYFUSION_CHANNEL");
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("countryCode", "cm");
        body.put("phone", local(phone));
        body.put("amount", ProviderHttp.positive(amountXaf, "MONEYFUSION"));
        body.put("withdraw_mode", mode);
        if (webhookUrl != null) body.put("webhook_url", webhookUrl);
        Reply reply = call(api.resolve("/api/v1/withdraw"), body, true);
        if (reply.status >= 400 && reply.status < 500) return new Started(null);
        JsonNode statut = reply.body.path("statut"), token = reply.body.path("tokenPay");
        if (reply.ok() && statut.isBoolean() && !statut.asBoolean()) return new Started(null);
        if (!reply.ok() || !statut.asBoolean(false) || !token.isString() || !token.asString().matches(TOKEN)) {
            // Money may have left: never refunded before finance finds the outcome in the MoneyFusion history.
            throw new ProviderUnavailable("MONEYFUSION_SEND");
        }
        return new Started(token.asString());
    }

    /** WAKA's own id means the answer was lost: unknown, never a failure, until finance reconciles it. */
    @Override
    public Status transfer(String reference) throws ProviderUnavailable {
        if (reference != null && reference.matches(UUID)) return new Status(State.PENDING, null, null);
        if (privateKey == null) throw new ProviderUnavailable("MONEYFUSION_NO_KEY");
        Reply one = call(api.resolve("/api/v1/payout/check-status/" + token(reference)), null, true);
        if (one.status == 404) return new Status(State.PENDING, null, null);
        JsonNode data = one.body.path("data");
        if (!one.ok() || !one.body.path("success").asBoolean(false) || !data.isObject()) throw new ProviderUnavailable("MONEYFUSION_TRANSFER");
        JsonNode word = data.path("status");
        if (!word.isString()) throw new ProviderUnavailable("MONEYFUSION_TRANSFER_MISSING");
        State state = switch (word.asString()) {
            case "success" -> State.SUCCEEDED;
            case "failed" -> State.FAILED;
            case "pending" -> State.PENDING;
            default -> throw new ProviderUnavailable("MONEYFUSION_TRANSFER_UNKNOWN");
        };
        return new Status(state, ProviderHttp.wholeXaf(data.path("amount")), "XAF");
    }

    /**
     * MoneyFusion signs nothing. A notification is accepted only as a reason to look again: it must look like one of
     * MoneyFusion's events, and the status is then read back from MoneyFusion for references WAKA already holds.
     */
    @Override
    public boolean authentic(byte[] body, UnaryOperator<String> header) {
        if (body == null) return false;
        try {
            JsonNode event = JSON.readTree(body);
            JsonNode name = event.path("event"), token = event.path("tokenPay");
            return name.isString() && name.asString().matches("(payin|payout)\\.session\\.(pending|completed|cancelled)")
                    && token.isString() && token.asString().matches(TOKEN);
        } catch (JacksonException unreadable) {
            return false;
        }
    }

    @Override
    public Set<String> references(byte[] body) {
        Set<String> found = new LinkedHashSet<>();
        try {
            JsonNode token = JSON.readTree(body).path("tokenPay");
            if (token.isString() && token.asString().matches(TOKEN)) found.add(token.asString());
        } catch (JacksonException ignored) {
            // An unreadable body mentions nothing.
        }
        return found;
    }

    // ---- Requests ----

    private Reply call(URI address, Object body, boolean keyed) throws ProviderUnavailable {
        HttpRequest.Builder request = HttpRequest.newBuilder(address).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (keyed) request.header("moneyfusion-private-key", privateKey);
        if (body == null) request.GET();
        else request.header("Content-Type", "application/json").POST(ProviderHttp.json(body));
        return ProviderHttp.send(http, request.build(), MAX_RESPONSE, "MONEYFUSION");
    }

    private static String token(String reference) {
        if (reference == null || !reference.matches(TOKEN)) throw new IllegalArgumentException("MONEYFUSION_REFERENCE");
        return reference;
    }

    private static String ours(String reference) {
        if (reference == null || !reference.matches(UUID)) throw new IllegalArgumentException("MONEYFUSION_REFERENCE");
        return reference;
    }

    /** MoneyFusion's examples use the national number: 6XXXXXXXX for Cameroon. */
    private static String local(String phone) {
        if (phone == null || !phone.matches("\\+2376[0-9]{8}")) throw new IllegalArgumentException("MONEYFUSION_PHONE");
        return phone.substring(4);
    }

    @Override public String toString() { return "MoneyFusionProvider[REDACTED]"; }
}
