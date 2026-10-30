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

/** Real local HTTP against a stand-in of the Fapshi API (no call leaves the machine). */
class FapshiProviderTest {
    private static final FapshiProvider.Credentials COLLECT = new FapshiProvider.Credentials("collect-user", "collect-key-123");
    private static final FapshiProvider.Credentials PAYOUT = new FapshiProvider.Credentials("payout-user", "payout-key-456");
    private static final String SECRET = "webhook-secret-0123456789";
    private HttpServer server;
    private FapshiProvider provider;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();
    private final String ours = UUID.randomUUID().toString();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(route + " user=" + exchange.getRequestHeaders().getFirst("apiuser") + " key="
                    + exchange.getRequestHeaders().getFirst("apikey") + " " + body);
            byte[] out = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codes.getOrDefault(route, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new FapshiProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), COLLECT, PAYOUT, SECRET);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void directPayUsesTheCollectionServiceAndWakaIdsWithTheNationalNumber() throws Exception {
        answers.put("POST /direct-pay", "{\"message\":\"Request successful\",\"transId\":\"Tx8Abc12\",\"dateInitiated\":\"2026-10-30\"}");
        assertEquals("Tx8Abc12", provider.collect(ours, 5000, "cm.orange", "+237690000000", "Credit WAKA").reference());
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /direct-pay user=collect-user key=collect-key-123 "));
        assertTrue(call.contains("\"amount\":5000") && call.contains("\"phone\":\"690000000\"") && call.contains("\"medium\":\"orange money\"")
                && call.contains("\"userId\":\"" + ours + "\"") && call.contains("\"externalId\":\"" + ours + "\""));
        codes.put("POST /direct-pay", 400);
        assertNull(provider.collect(ours, 50, "cm.mtn", "+237670000000", "x").reference(), "refused: nothing created");
        codes.put("POST /direct-pay", 503);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect(ours, 500, "cm.mtn", "+237670000000", "x"));
        assertThrows(IllegalArgumentException.class, () -> provider.collect("not-ours", 500, "cm.mtn", "+237670000000", "x"));
    }

    @Test void statusesAreFapshiWordsAndAmountsInXaf() throws Exception {
        answers.put("GET /payment-status/Tx1", "{\"transId\":\"Tx1\",\"status\":\"SUCCESSFUL\",\"amount\":5000,\"revenue\":4850}");
        var done = provider.payment("Tx1");
        assertEquals(PaymentProvider.State.SUCCEEDED, done.state());
        assertEquals(5000L, done.amountXaf());
        assertEquals("XAF", done.currency());
        answers.put("GET /payment-status/Tx2", "{\"status\":\"EXPIRED\"}");
        assertEquals(PaymentProvider.State.FAILED, provider.payment("Tx2").state());
        answers.put("GET /payment-status/Tx3", "{\"status\":\"CREATED\"}");
        assertEquals(PaymentProvider.State.PENDING, provider.payment("Tx3").state());
        answers.put("GET /payment-status/Tx4", "{\"status\":\"SOMETHING_NEW\"}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.payment("Tx4"), "an unknown word is never guessed");
        codes.put("GET /payment-status/Tx5", 404);
        assertEquals(PaymentProvider.State.PENDING, provider.payment("Tx5").state(), "unknown is never a failure");
        assertThrows(IllegalArgumentException.class, () -> provider.payment("../balance"));
    }

    @Test void aLostAnswerIsFoundAgainByWakaIdNeverSentTwice() throws Exception {
        answers.put("GET /transaction/" + ours, "[{\"transId\":\"Old\",\"externalId\":\"other\",\"status\":\"FAILED\"},"
                + "{\"transId\":\"TxR\",\"externalId\":\"" + ours + "\",\"status\":\"SUCCESSFUL\",\"amount\":700}]");
        var found = provider.payment(ours);
        assertEquals(PaymentProvider.State.SUCCEEDED, found.state());
        assertEquals(700L, found.amountXaf());
        assertEquals(1, seen.size());
        assertTrue(seen.getFirst().startsWith("GET /transaction/" + ours));
        answers.put("GET /transaction/" + ours, "[]");
        assertEquals(PaymentProvider.State.PENDING, provider.payment(ours).state());
    }

    @Test void payoutsUseTheSeparatePayoutService() throws Exception {
        answers.put("POST /payout", "{\"message\":\"ok\",\"transId\":\"Po9\"}");
        assertEquals("Po9", provider.send(ours, 990, "cm.mtn", "+237670000000", "Retrait WAKA").reference());
        assertTrue(seen.getFirst().startsWith("POST /payout user=payout-user key=payout-key-456 ") && seen.getFirst().contains("\"medium\":\"mobile money\""));
        answers.put("GET /payment-status/Po9", "{\"status\":\"FAILED\",\"reason\":\"x\"}");
        assertEquals(PaymentProvider.State.FAILED, provider.transfer("Po9").state());
        assertTrue(seen.getLast().contains("user=payout-user"));
    }

    @Test void onlyTheExactWebhookSecretIsAuthentic() {
        byte[] body = ("{\"transId\":\"Tx8\",\"externalId\":\"" + ours + "\",\"status\":\"SUCCESSFUL\"}").getBytes(StandardCharsets.UTF_8);
        assertTrue(provider.authentic(body, name -> "x-wh-secret".equals(name) ? SECRET : null));
        assertFalse(provider.authentic(body, name -> null));
        assertFalse(provider.authentic(body, name -> SECRET + "x"));
        assertEquals(Set.of("Tx8", ours), provider.references(body));
        assertTrue(provider.references("nope".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test void onlyTheRealAddressesOrALocalTestServer() {
        assertThrows(IllegalArgumentException.class, () -> new FapshiProvider(URI.create("https://evil.example"), COLLECT, PAYOUT, SECRET));
        assertThrows(IllegalArgumentException.class, () -> new FapshiProvider(false, COLLECT, PAYOUT, "short"));
        assertThrows(IllegalArgumentException.class, () -> new FapshiProvider.Credentials("u", "k"));
        assertFalse(COLLECT.toString().contains("collect-key"));
        assertFalse(new FapshiProvider(true, COLLECT, PAYOUT, SECRET).toString().contains("collect"));
    }
}
