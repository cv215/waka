package com.waka.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real local HTTP against a stand-in of the SasPay API (no call leaves the machine). */
class SasPayProviderTest {
    private static final String KEY = "sk_test_4f9a2c7e1b8d3a6f";
    private static final String SECRET = "whsec_0123456789abcdef";
    private static final String EMAIL = "paiements@example.com";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private HttpServer server;
    private SasPayProvider provider;
    private URI base;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();
    private final String ours = UUID.randomUUID().toString();
    private final String theirs = "9c3f2a10-4b7e-4f1a-9d2e-9b6a7c1e4a02";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(route + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " key=" + exchange.getRequestHeaders().getFirst("Idempotency-Key") + " " + body);
            byte[] out = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codes.getOrDefault(route, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/");
        provider = new SasPayProvider(base, KEY, SECRET, EMAIL, Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void stop() { server.stop(0); }

    private static String created(String id, String page) {
        return "{\"success\":true,\"data\":{\"message\":\"ok\",\"id\":\"" + id + "\",\"status\":\"PENDING\",\"checkout_url\":\"" + page + "\"},\"code\":201}";
    }

    @Test void collectPushesWithWakaIdAsIdempotencyKey() throws Exception {
        answers.put("POST /api/v1/payments/softpay/", created(theirs, ""));
        codes.put("POST /api/v1/payments/softpay/", 201);
        var started = provider.collect(ours, 5000, "cm.mtn", "+237670000000", "Recharge WAKA");
        assertEquals(theirs, started.reference());
        assertNull(started.checkoutUrl(), "MTN: a push to the phone, no page");
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /api/v1/payments/softpay/ auth=Bearer " + KEY + " key=" + ours + " "), call);
        assertTrue(call.contains("\"amount\":\"5000.00\"") && call.contains("\"currency\":\"XAF\"") && call.contains("\"country\":\"CM\"")
                && call.contains("\"network\":\"mtn_cm\"") && call.contains("\"phone\":\"+237670000000\"")
                && call.contains("\"email\":\"" + EMAIL + "\"") && call.contains("\"fee_charge_mode\":\"DEDUCTED\""), call);
    }

    @Test void orangeMoneyGivesTheSasPayPageOnly() throws Exception {
        answers.put("POST /api/v1/payments/softpay/", created(theirs, "https://pay.saspay.me/checkout/abc123"));
        var started = provider.collect(ours, 5000, "cm.orange", "+237690000000", "x");
        assertEquals("https://pay.saspay.me/checkout/abc123", started.checkoutUrl());
        assertTrue(seen.getFirst().contains("\"network\":\"orange_cm\""));
        answers.put("POST /api/v1/payments/softpay/", created(theirs, "https://evil.example/pay"));
        assertNull(provider.collect(UUID.randomUUID().toString(), 5000, "cm.orange", "+237690000000", "x").checkoutUrl(),
                "a page outside saspay.me is never handed to the app");
    }

    @Test void refusalsCreateNothing() throws Exception {
        codes.put("POST /api/v1/payments/softpay/", 422);
        answers.put("POST /api/v1/payments/softpay/", "{\"success\":false,\"error\":{\"code\":\"no_route_available\"}}");
        assertNull(provider.collect(ours, 500, "cm.mtn", "+237670000000", "x").reference());
        assertThrows(IllegalArgumentException.class, () -> provider.collect("not-ours", 500, "cm.mtn", "+237670000000", "x"));
        assertThrows(IllegalArgumentException.class, () -> provider.collect(ours, 500, "cm.mtn", "670000000", "x"));
        assertThrows(IllegalArgumentException.class, () -> provider.collect(ours, 500, "credit", "+237670000000", "x"));
    }

    @Test void statusesAreSasPayWordsAndThePayerChargedAmount() throws Exception {
        String route = "GET /api/v1/payments/" + theirs + "/verify/";
        answers.put(route, "{\"success\":true,\"data\":{\"id\":\"" + theirs + "\",\"status\":\"SUCCESS\",\"requested_amount\":\"5000.00\","
                + "\"debited_amount\":\"5000.00\",\"net_amount\":\"4912.50\",\"currency\":\"XAF\"}}");
        var paid = provider.payment(theirs);
        assertEquals(PaymentProvider.State.SUCCEEDED, paid.state());
        assertEquals(5000L, paid.amountXaf());
        assertEquals("XAF", paid.currency());
        answers.put(route, "{\"data\":{\"status\":\"SUCCESS\",\"requested_amount\":\"5000.00\",\"debited_amount\":\"5087.50\",\"currency\":\"XAF\"}}");
        assertEquals(null, provider.payment(theirs).amountXaf(), "fees added on top: not whole francs, the payment goes to review");
        answers.put(route, "{\"data\":{\"status\":\"FAILED\",\"currency\":\"XAF\"}}");
        assertEquals(PaymentProvider.State.FAILED, provider.payment(theirs).state());
        answers.put(route, "{\"data\":{\"status\":\"PENDING\"}}");
        assertEquals(PaymentProvider.State.PENDING, provider.payment(theirs).state());
        answers.put(route, "{\"data\":{\"status\":\"REVERSED\"}}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.payment(theirs));
        codes.put(route, 404);
        assertEquals(PaymentProvider.State.PENDING, provider.payment(theirs).state());
    }

    @Test void aLostAnswerIsSentAgainUnchangedWithTheSameKey() throws Exception {
        codes.put("POST /api/v1/payments/softpay/", 502);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect(ours, 5000, "cm.mtn", "+237670000000", "Recharge WAKA"));
        codes.put("POST /api/v1/payments/softpay/", 201);
        answers.put("POST /api/v1/payments/softpay/", created(theirs, ""));
        answers.put("GET /api/v1/payments/" + theirs + "/verify/", "{\"data\":{\"status\":\"SUCCESS\",\"debited_amount\":\"5000.00\",\"currency\":\"XAF\"}}");
        assertEquals(PaymentProvider.State.SUCCEEDED, provider.payment(ours).state());
        String first = seen.get(0).replaceFirst("^\\S+ \\S+ ", ""), again = seen.get(1).replaceFirst("^\\S+ \\S+ ", "");
        assertEquals(first, again, "exactly the same request and Idempotency-Key");
        assertEquals("GET /api/v1/payments/" + theirs + "/verify/", seen.get(2).split(" auth=")[0]);
    }

    @Test void withdrawalsSendTheFullAmountToTheNationalNumber() throws Exception {
        answers.put("POST /api/v1/payouts/initialize/", "{\"success\":true,\"data\":{\"message\":\"ok\",\"id\":\"" + theirs + "\"}}");
        codes.put("POST /api/v1/payouts/initialize/", 201);
        assertEquals(theirs, provider.send(ours, 2000, "cm.orange", "+237690000000", "Retrait WAKA").reference());
        String call = seen.getFirst();
        assertTrue(call.contains(" key=" + ours + " ") && call.contains("\"method\":\"orange_cm\"") && call.contains("\"msisdn\":\"690000000\"")
                && call.contains("\"amount\":\"2000.00\"") && call.contains("\"fee_charge_mode\":\"ADD_ON\""), call);
        String route = "GET /api/v1/payouts/" + theirs + "/verify/";
        answers.put(route, "{\"data\":{\"status\":\"SUCCESS\",\"requested_amount\":\"2000.00\",\"net_amount\":\"2000.00\",\"currency\":\"XAF\"}}");
        var done = provider.transfer(theirs);
        assertEquals(PaymentProvider.State.SUCCEEDED, done.state());
        assertEquals(2000L, done.amountXaf());
        codes.put("POST /api/v1/payouts/initialize/", 403);
        assertNull(provider.send(UUID.randomUUID().toString(), 2000, "cm.mtn", "+237670000000", "x").reference(), "ip_not_whitelisted: nothing sent");
    }

    @Test void aLostWithdrawalIsNeverAFailure() throws Exception {
        codes.put("POST /api/v1/payouts/initialize/", 504);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.send(ours, 2000, "cm.mtn", "+237670000000", "x"));
        codes.put("POST /api/v1/payouts/initialize/", 409);
        assertEquals(PaymentProvider.State.PENDING, provider.transfer(ours).state(), "money may have left: finance reconciles");
    }

    @Test void notificationsMustBeSignedAndRecent() throws Exception {
        byte[] body = ("{\"event\":\"transaction.success\",\"data\":{\"id\":\"" + theirs + "\",\"reference\":\"TXN-2026-000456\"}}").getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(NOW.getEpochSecond());
        String good = sign(ts, body);
        assertTrue(provider.authentic(body, h -> switch (h) { case "X-Webhook-Signature" -> good; case "X-Webhook-Timestamp" -> ts; default -> null; }));
        assertEquals(Set.of(theirs), provider.references(body));
        String old = Long.toString(NOW.getEpochSecond() - 301);
        String oldSig = sign(old, body);
        assertFalse(provider.authentic(body, h -> "X-Webhook-Signature".equals(h) ? oldSig : "X-Webhook-Timestamp".equals(h) ? old : null), "replayed later");
        byte[] other = "{\"event\":\"transaction.success\",\"data\":{\"id\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
        assertFalse(provider.authentic(other, h -> "X-Webhook-Signature".equals(h) ? good : "X-Webhook-Timestamp".equals(h) ? ts : null), "body changed");
        assertFalse(provider.authentic(body, h -> null));
        var unsigned = new SasPayProvider(base, KEY, null, EMAIL, Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(unsigned.authentic(body, h -> "X-Webhook-Signature".equals(h) ? good : "X-Webhook-Timestamp".equals(h) ? ts : null));
    }

    private static String sign(String ts, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        mac.update((ts + ".").getBytes(StandardCharsets.US_ASCII));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test void aTestKeyNeverReachesTheLiveAddress() {
        assertFalse(new SasPayProvider("sk_test_4f9a2c7e1b8d3a6f", null, EMAIL).live());
        assertTrue(new SasPayProvider("sk_live_4f9a2c7e1b8d3a6f", null, EMAIL).live());
        assertThrows(IllegalArgumentException.class, () -> new SasPayProvider(SasPayProvider.LIVE, "sk_test_4f9a2c7e1b8d3a6f", null, EMAIL, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new SasPayProvider(SasPayProvider.SANDBOX, "sk_live_4f9a2c7e1b8d3a6f", null, EMAIL, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new SasPayProvider("pk_4f9a2c7e1b8d3a6f", null, EMAIL));
        assertThrows(IllegalArgumentException.class, () -> new SasPayProvider("sk_test_4f9a2c7e1b8d3a6f", null, "not an email"));
        assertTrue(SasPayProvider.page("https://pay.saspay.me/checkout/abc"));
        assertFalse(SasPayProvider.page("https://saspay.me.evil.example/x"));
        assertEquals("SasPayProvider[REDACTED]", provider.toString());
    }
}
