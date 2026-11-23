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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real local HTTP against a stand-in of the MoneyFusion API (no call leaves the machine). */
class MoneyFusionProviderTest {
    private static final String KEY = "mf-private-0123456789abcdef";
    private static final String PAGE = "https://payin.moneyfusion.net/payment/Tk8aZ31q/Marchand";
    private HttpServer server;
    private MoneyFusionProvider provider;
    private URI base;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();
    private final String ours = UUID.randomUUID().toString();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(route + " key=" + exchange.getRequestHeaders().getFirst("moneyfusion-private-key") + " " + body);
            byte[] out = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codes.getOrDefault(route, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        provider = new MoneyFusionProvider(base.resolve("/app/pay/"), base, KEY, "https://api.waka.example/api/v1/payment-notifications");
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void collectReturnsTheTokenAndTheProviderPageWithoutTheKey() throws Exception {
        answers.put("POST /app/pay/", "{\"statut\":true,\"token\":\"Tk8aZ31q\",\"message\":\"paiement en cours\",\"url\":\"" + PAGE + "\"}");
        var started = provider.collect(ours, 5000, "cm.mtn", "+237670000000", "Recharge WAKA");
        assertEquals("Tk8aZ31q", started.reference());
        assertEquals(PAGE, started.checkoutUrl());
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /app/pay/ key=null "), "the payin address needs no key: " + call);
        assertTrue(call.contains("\"totalPrice\":5000") && call.contains("\"numeroSend\":\"670000000\"")
                && call.contains("\"wakaPayment\":\"" + ours + "\"") && call.contains("\"webhook_url\":\"https://api.waka.example/"), call);
    }

    @Test void refusalsCreateNothingAndGarbledAnswersAreUnknown() throws Exception {
        answers.put("POST /app/pay/", "{\"statut\":false,\"message\":\"IP non autorisée ou application non approuvée\"}");
        assertNull(provider.collect(ours, 500, "cm.mtn", "+237670000000", "x").reference());
        codes.put("POST /app/pay/", 403);
        assertNull(provider.collect(ours, 500, "cm.mtn", "+237670000000", "x").reference());
        codes.put("POST /app/pay/", 502);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect(ours, 500, "cm.mtn", "+237670000000", "x"));
        codes.remove("POST /app/pay/");
        answers.put("POST /app/pay/", "{\"statut\":true,\"token\":\"Tk8aZ31q\",\"url\":\"https://evil.example/pay\"}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect(ours, 500, "cm.mtn", "+237670000000", "x"),
                "a page outside moneyfusion.net is never handed to the app");
        assertThrows(IllegalArgumentException.class, () -> provider.collect("not-ours", 500, "cm.mtn", "+237670000000", "x"));
        assertThrows(IllegalArgumentException.class, () -> provider.collect(ours, 500, "cm.mtn", "670000000", "x"));
    }

    @Test void paymentStatusesAreMoneyFusionWords() throws Exception {
        String route = "GET /paiementNotif/Tk8aZ31q";
        answers.put(route, "{\"statut\":true,\"data\":{\"tokenPay\":\"Tk8aZ31q\",\"Montant\":5000,\"frais\":150,\"statut\":\"paid\",\"moyen\":\"orange\"}}");
        var paid = provider.payment("Tk8aZ31q");
        assertEquals(PaymentProvider.State.SUCCEEDED, paid.state());
        assertEquals(5000L, paid.amountXaf());
        assertEquals("XAF", paid.currency());
        answers.put(route, "{\"statut\":true,\"data\":{\"Montant\":5000,\"statut\":\"failure\"}}");
        assertEquals(PaymentProvider.State.FAILED, provider.payment("Tk8aZ31q").state());
        answers.put(route, "{\"statut\":true,\"data\":{\"Montant\":5000,\"statut\":\"no paid\"}}");
        assertEquals(PaymentProvider.State.PENDING, provider.payment("Tk8aZ31q").state(), "the page may simply not be used yet");
        answers.put(route, "{\"statut\":true,\"data\":{\"Montant\":5000.5,\"statut\":\"paid\"}}");
        assertNull(provider.payment("Tk8aZ31q").amountXaf(), "a decimal amount is not trusted");
        answers.put(route, "{\"statut\":true,\"data\":{\"statut\":\"refunded\"}}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.payment("Tk8aZ31q"));
        codes.put(route, 404);
        assertEquals(PaymentProvider.State.PENDING, provider.payment("Tk8aZ31q").state());
    }

    @Test void aLostCreationAnswerMeansNoPageWasShownSoTheTopUpFailed() throws Exception {
        assertEquals(PaymentProvider.State.FAILED, provider.payment(ours).state());
        assertTrue(seen.isEmpty(), "nothing to ask: MoneyFusion has no lookup by WAKA's id");
    }

    @Test void withdrawalsUseThePrivateKeyAndTheCameroonCodes() throws Exception {
        answers.put("POST /api/v1/withdraw", "{\"tokenPay\":\"D0h6fjyjkCfi\",\"statut\":true,\"message\":\"Retrait soumis avec succès\"}");
        assertEquals("D0h6fjyjkCfi", provider.send(ours, 2000, "cm.orange", "+237690000000", "Retrait WAKA").reference());
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /api/v1/withdraw key=" + KEY + " "), call);
        assertTrue(call.contains("\"countryCode\":\"cm\"") && call.contains("\"phone\":\"690000000\"") && call.contains("\"amount\":2000")
                && call.contains("\"withdraw_mode\":\"orange-cm\""), call);
        provider.send(UUID.randomUUID().toString(), 2000, "cm.mtn", "+237670000000", "x");
        assertTrue(seen.get(1).contains("\"withdraw_mode\":\"mtn-cm\""));

        String status = "GET /api/v1/payout/check-status/D0h6fjyjkCfi";
        answers.put(status, "{\"success\":true,\"data\":{\"tokenPay\":\"D0h6fjyjkCfi\",\"status\":\"success\",\"amount\":2000}}");
        var done = provider.transfer("D0h6fjyjkCfi");
        assertEquals(PaymentProvider.State.SUCCEEDED, done.state());
        assertEquals(2000L, done.amountXaf());
        assertTrue(seen.getLast().startsWith(status + " key=" + KEY));
        answers.put(status, "{\"success\":true,\"data\":{\"status\":\"failed\",\"amount\":2000}}");
        assertEquals(PaymentProvider.State.FAILED, provider.transfer("D0h6fjyjkCfi").state());
        answers.put(status, "{\"success\":false,\"message\":\"Withdrawal not found\"}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.transfer("D0h6fjyjkCfi"));
    }

    @Test void aLostWithdrawalIsNeverAFailure() throws Exception {
        codes.put("POST /api/v1/withdraw", 504);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.send(ours, 2000, "cm.mtn", "+237670000000", "x"));
        assertEquals(PaymentProvider.State.PENDING, provider.transfer(ours).state(), "money may have left: finance reconciles");
        codes.put("POST /api/v1/withdraw", 400);
        assertNull(provider.send(UUID.randomUUID().toString(), 2000, "cm.mtn", "+237670000000", "x").reference());
    }

    @Test void withoutThePrivateKeyNothingIsSent() throws Exception {
        var noKey = new MoneyFusionProvider(base.resolve("/app/pay/"), base, null, null);
        assertNull(noKey.send(ours, 2000, "cm.mtn", "+237670000000", "x").reference());
        assertTrue(seen.isEmpty());
    }

    @Test void notificationsAreOnlyHintsAboutTokens() {
        byte[] event = "{\"event\":\"payin.session.completed\",\"tokenPay\":\"Tk8aZ31q\",\"Montant\":5000}".getBytes(StandardCharsets.UTF_8);
        assertTrue(provider.authentic(event, name -> null));
        assertEquals(Set.of("Tk8aZ31q"), provider.references(event));
        assertFalse(provider.authentic("{\"event\":\"other\",\"tokenPay\":\"Tk8aZ31q\"}".getBytes(StandardCharsets.UTF_8), name -> null));
        assertFalse(provider.authentic("{\"event\":\"payin.session.completed\"}".getBytes(StandardCharsets.UTF_8), name -> null));
        assertFalse(provider.authentic("not json".getBytes(StandardCharsets.UTF_8), name -> null));
        assertEquals(Set.of(), provider.references("{\"tokenPay\":\"../x\"}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void onlyMoneyFusionAddressesAreAccepted() {
        new MoneyFusionProvider("https://www.pay.moneyfusion.net/WAKA/abc123/pay/", null, null);
        assertThrows(IllegalArgumentException.class, () -> new MoneyFusionProvider("https://pay.moneyfusion.net.evil.example/pay/", null, null));
        assertThrows(IllegalArgumentException.class, () -> new MoneyFusionProvider("http://pay.moneyfusion.net/pay/", null, null));
        assertThrows(IllegalArgumentException.class, () -> new MoneyFusionProvider("https://pay.moneyfusion.net/pay/?x=1", null, null));
        assertThrows(IllegalArgumentException.class, () -> new MoneyFusionProvider("https://pay.moneyfusion.net/pay/", "short", null));
        assertTrue(MoneyFusionProvider.page(PAGE));
        assertFalse(MoneyFusionProvider.page("https://moneyfusion.net.evil.example/x"));
        assertFalse(MoneyFusionProvider.page("javascript:alert(1)"));
        assertEquals("MoneyFusionProvider[REDACTED]", provider.toString());
    }
}
