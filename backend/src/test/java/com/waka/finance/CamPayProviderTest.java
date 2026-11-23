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
import java.util.Base64;
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

/** Real local HTTP against a stand-in of the CamPay API (no call leaves the machine). */
class CamPayProviderTest {
    private static final String TOKEN = "a1b2c3d4e5f60718293a4b5c6d7e8f9012345678";
    private static final String WEBHOOK = "webhook-key-0123456789";
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private HttpServer server;
    private CamPayProvider provider;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private final Map<String, Integer> codes = new ConcurrentHashMap<>();
    private final String ours = UUID.randomUUID().toString();
    private final String theirs = "bcedde9b-62a7-4421-96ac-2e6179552a1a";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String route = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            seen.add(route + " auth=" + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            byte[] out = answers.getOrDefault(route, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(codes.getOrDefault(route, 200), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        provider = new CamPayProvider(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN, WEBHOOK,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void collectSendsWakaIdAsExternalReferenceWithTheInternationalNumber() throws Exception {
        answers.put("POST /api/collect/", "{\"reference\":\"" + theirs + "\",\"ussd_code\":\"*126#\",\"operator\":\"mtn\"}");
        assertEquals(theirs, provider.collect(ours, 5000, "cm.mtn", "+237670000000", "Course WAKA").reference());
        String call = seen.getFirst();
        assertTrue(call.startsWith("POST /api/collect/ auth=Token " + TOKEN + " "));
        assertTrue(call.contains("\"amount\":\"5000\"") && call.contains("\"currency\":\"XAF\"") && call.contains("\"from\":\"237670000000\"")
                && call.contains("\"external_reference\":\"" + ours + "\""), call);
        codes.put("POST /api/collect/", 400);
        assertNull(provider.collect(UUID.randomUUID().toString(), 50, "cm.mtn", "+237670000000", "x").reference(), "ER101/ER201: nothing created");
        assertThrows(IllegalArgumentException.class, () -> provider.collect("not-ours", 500, "cm.mtn", "+237670000000", "x"));
        assertThrows(IllegalArgumentException.class, () -> provider.collect(ours, 500, "cm.mtn", "670000000", "x"));
    }

    @Test void statusesAreCamPayWordsAndWholeFrancs() throws Exception {
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"SUCCESSFUL\",\"amount\":\"5000.00\",\"currency\":\"XAF\"}");
        var done = provider.payment(theirs);
        assertEquals(PaymentProvider.State.SUCCEEDED, done.state());
        assertEquals(5000L, done.amountXaf());
        assertEquals("XAF", done.currency());
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"FAILED\",\"amount\":\"0.00\",\"currency\":\"XAF\"}");
        assertEquals(PaymentProvider.State.FAILED, provider.payment(theirs).state());
        assertNull(provider.payment(theirs).amountXaf(), "0.00 is not an amount paid");
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"PENDING\",\"amount\":\"12.50\"}");
        assertEquals(PaymentProvider.State.PENDING, provider.payment(theirs).state());
        assertNull(provider.payment(theirs).amountXaf(), "a decimal amount is never rounded");
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"SOMETHING_NEW\"}");
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.payment(theirs), "an unknown word is never guessed");
        codes.put("GET /api/transaction/" + theirs + "/", 404);
        assertEquals(PaymentProvider.State.PENDING, provider.payment(theirs).state(), "unknown is never a failure");
        assertThrows(IllegalArgumentException.class, () -> provider.payment("../balance"));
    }

    @Test void aLostAnswerIsAskedAgainWithTheSameExternalReferenceNeverANewOne() throws Exception {
        codes.put("POST /api/collect/", 502);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.collect(ours, 700, "cm.orange", "+237690000000", "Course WAKA"));
        String first = seen.getFirst();
        codes.remove("POST /api/collect/");
        answers.put("POST /api/collect/", "{\"reference\":\"" + theirs + "\"}");
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"SUCCESSFUL\",\"amount\":\"700\",\"currency\":\"XAF\"}");
        var found = provider.payment(ours);
        assertEquals(PaymentProvider.State.SUCCEEDED, found.state());
        assertEquals(700L, found.amountXaf());
        assertEquals(first.substring(first.indexOf('{')), seen.get(1).substring(seen.get(1).indexOf('{')), "exactly the same request");
        provider.payment(ours);
        assertEquals(2, seen.stream().filter(s -> s.startsWith("POST")).count(), "the reference found is remembered");
    }

    @Test void aRefusedRepeatStaysPendingNeverFailed() throws Exception {
        codes.put("POST /api/withdraw/", 504);
        assertThrows(PaymentProvider.ProviderUnavailable.class, () -> provider.send(ours, 990, "cm.mtn", "+237670000000", "Retrait WAKA"));
        codes.put("POST /api/withdraw/", 400);
        assertEquals(PaymentProvider.State.PENDING, provider.transfer(ours).state(), "a duplicate refusal proves nothing: finance reconciles");
    }

    @Test void withdrawalsUseTheWithdrawEndpoint() throws Exception {
        answers.put("POST /api/withdraw/", "{\"reference\":\"" + theirs + "\",\"status\":\"PENDING\"}");
        assertEquals(theirs, provider.send(ours, 990, "cm.mtn", "+237670000000", "Retrait WAKA").reference());
        assertTrue(seen.getFirst().contains("\"to\":\"237670000000\"") && seen.getFirst().contains("\"amount\":\"990\""));
        answers.put("GET /api/transaction/" + theirs + "/", "{\"status\":\"SUCCESSFUL\",\"amount\":\"990.00\",\"currency\":\"XAF\"}");
        assertEquals(PaymentProvider.State.SUCCEEDED, provider.transfer(theirs).state());
    }

    @Test void onlyAJwtSignedWithTheWebhookKeyIsAuthentic() throws Exception {
        String good = jwt(WEBHOOK, "{\"alg\":\"HS256\",\"typ\":\"JWT\"}", "{\"exp\":" + (NOW.getEpochSecond() + 60) + "}");
        assertTrue(provider.authentic(body(good), name -> null));
        assertFalse(provider.authentic(body(jwt("another-key-0123456", "{\"alg\":\"HS256\"}", "{}")), name -> null));
        assertFalse(provider.authentic(body(jwt(WEBHOOK, "{\"alg\":\"none\"}", "{}")), name -> null));
        assertFalse(provider.authentic(body(jwt(WEBHOOK, "{\"alg\":\"HS256\"}", "{\"exp\":" + (NOW.getEpochSecond() - 1) + "}")), name -> null));
        assertFalse(provider.authentic(body("abc.def"), name -> null));
        assertFalse(provider.authentic("nope".getBytes(StandardCharsets.UTF_8), name -> null));
        assertEquals(Set.of(theirs, ours), provider.references(body(good)));
    }

    @Test void onlyTheRealAddressesOrALocalTestServer() {
        assertThrows(IllegalArgumentException.class, () -> new CamPayProvider(URI.create("https://evil.example"), TOKEN, WEBHOOK, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new CamPayProvider(false, "short", WEBHOOK));
        assertThrows(IllegalArgumentException.class, () -> new CamPayProvider(false, TOKEN, "x"));
        assertFalse(new CamPayProvider(true, TOKEN, WEBHOOK).toString().contains(TOKEN));
    }

    private byte[] body(String signature) {
        return ("{\"status\":\"SUCCESSFUL\",\"reference\":\"" + theirs + "\",\"external_reference\":\"" + ours
                + "\",\"signature\":\"" + signature + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static String jwt(String key, String head, String claims) throws Exception {
        var url = Base64.getUrlEncoder().withoutPadding();
        String signed = url.encodeToString(head.getBytes(StandardCharsets.UTF_8)) + "." + url.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return signed + "." + url.encodeToString(mac.doFinal(signed.getBytes(StandardCharsets.US_ASCII)));
    }
}
