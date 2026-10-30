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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Real local HTTP against a stand-in of the Notch Pay API (no call leaves the machine). */
class NotchPayProviderTest {
    private static final String PUBLIC = "pk_test_publickey", PRIVATE = "sk_test_privatekey", HASH = "hsk_test_hashkey";
    private HttpServer server;
    private NotchPayProvider provider;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(route + " auth=" + exchange.getRequestHeaders().getFirst("Authorization") + " grant="
                    + exchange.getRequestHeaders().getFirst("X-Grant") + " " + body);
            byte[] out = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codes.getOrDefault(route, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new NotchPayProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), PUBLIC, PRIVATE, HASH);
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void collectInitializesThenPushesTheChannelToThePhone() throws Exception {
        answers.put("POST /payments", "{\"status\":\"Accepted\",\"code\":201,\"transaction\":{\"reference\":\"trx.abc123\"}}");
        codes.put("POST /payments", 201);
        answers.put("PUT /payments/trx.abc123", "{\"status\":\"Accepted\",\"code\":202}");
        codes.put("PUT /payments/trx.abc123", 202);
        assertEquals("trx.abc123", provider.collect("our-id", 5100, "cm.mtn", "+237670000000", "Credit WAKA").reference());
        assertTrue(seen.get(0).startsWith("POST /payments auth=" + PUBLIC + " grant=null "));
        assertTrue(seen.get(0).contains("\"amount\":5100") && seen.get(0).contains("\"currency\":\"XAF\"")
                && seen.get(0).contains("\"phone\":\"237670000000\"") && seen.get(0).contains("\"reference\":\"our-id\""));
        assertTrue(seen.get(1).startsWith("PUT /payments/trx.abc123") && seen.get(1).contains("\"channel\":\"cm.mtn\""));
    }

    @Test void aRefusedInitializationCreatesNothingAndAServerErrorIsUnknown() {
        codes.put("POST /payments", 422);
        assertDoesNotReturnReference(() -> provider.collect("our-id", 500, "cm.orange", "+237690000000", "x"));
        codes.put("POST /payments", 502);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect("our-id", 500, "cm.orange", "+237690000000", "x"));
    }

    @Test void statusWordsAmountAndCurrencyAreReadBack() throws Exception {
        answers.put("GET /payments/trx.1", "{\"transaction\":{\"status\":\"complete\",\"amount\":5100,\"currency\":\"XAF\"}}");
        var done = provider.payment("trx.1");
        assertEquals(PaymentProvider.State.SUCCEEDED, done.state());
        assertEquals(5100L, done.amountXaf());
        assertEquals("XAF", done.currency());
        answers.put("GET /payments/trx.2", "{\"transaction\":{\"status\":\"canceled\"}}");
        assertEquals(PaymentProvider.State.FAILED, provider.payment("trx.2").state());
        answers.put("GET /payments/trx.3", "{\"transaction\":{\"status\":\"pending\",\"amount\":\"700\"}}");
        assertEquals(PaymentProvider.State.PENDING, provider.payment("trx.3").state());
        codes.put("GET /payments/trx.4", 404);
        assertEquals(PaymentProvider.State.PENDING, provider.payment("trx.4").state(), "unknown is never a failure");
        answers.put("GET /payments/trx.5", "{\"transaction\":{}}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.payment("trx.5"));
        assertThrows(IllegalArgumentException.class, () -> provider.payment("../balance"));
    }

    @Test void transfersUseThePrivateKey() throws Exception {
        answers.put("POST /transfers", "{\"status\":\"Accepted\",\"transfer\":{\"reference\":\"tr.9\"}}");
        codes.put("POST /transfers", 201);
        assertEquals("tr.9", provider.send("w-1", 990, "cm.mtn", "+237670000000", "Retrait WAKA").reference());
        assertTrue(seen.getFirst().contains("grant=" + PRIVATE) && seen.getFirst().contains("\"recipient\":\"237670000000\""));
        answers.put("GET /transfers/tr.9", "{\"transfer\":{\"status\":\"failed\"}}");
        assertEquals(PaymentProvider.State.FAILED, provider.transfer("tr.9").state());
    }

    @Test void onlyTheHashKeySignatureOfTheRawBodyIsAuthentic() throws Exception {
        byte[] body = "{\"type\":\"payment.complete\",\"data\":{\"reference\":\"trx.abc123\",\"id\":\"x\"}}".getBytes(StandardCharsets.UTF_8);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HASH.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        String good = HexFormat.of().formatHex(mac.doFinal(body));
        assertTrue(provider.signed(body, good));
        assertTrue(provider.signed(body, good.toUpperCase()));
        assertFalse(provider.signed(body, null));
        assertFalse(provider.signed(body, "0".repeat(64)));
        body[body.length - 2] = ' ';
        assertFalse(provider.signed(body, good), "a changed body is refused");
        assertEquals(List.of("x", "trx.abc123"), List.copyOf(provider.references(
                "{\"data\":{\"reference\":\"trx.abc123\",\"id\":\"x\"}}".getBytes(StandardCharsets.UTF_8))));
        assertTrue(provider.references("not json".getBytes(StandardCharsets.UTF_8)).isEmpty());
    }

    @Test void onlyTheRealAddressOrALocalTestServer() {
        assertThrows(IllegalArgumentException.class, () -> new NotchPayProvider(URI.create("https://evil.example"), PUBLIC, PRIVATE, HASH));
        assertThrows(IllegalArgumentException.class, () -> new NotchPayProvider(PUBLIC, PRIVATE, "short"));
        assertFalse(new NotchPayProvider(PUBLIC, PRIVATE, HASH).toString().contains(PUBLIC));
    }

    private interface Start { PaymentProvider.Started run() throws Exception; }
    private static void assertDoesNotReturnReference(Start start) {
        try { assertNull(start.run().reference()); } catch (Exception unexpected) { throw new AssertionError(unexpected); }
    }
}
