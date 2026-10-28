package com.waka.identity.sms.orange;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.waka.identity.otp.SmsGateway.Submission;
import com.waka.identity.otp.SmsMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Vrai HTTP boucle locale seulement : aucun compte Orange, credential reel ou SMS. */
class OrangeSmsGatewayTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private URI base;
    private final AtomicInteger oauthCalls = new AtomicInteger();
    private final AtomicInteger smsCalls = new AtomicInteger();
    private final AtomicInteger unexpectedCalls = new AtomicInteger();
    private volatile int tokenStatus = 200;
    private volatile int smsStatus = 201;
    private volatile String tokenBody = "{\"token_type\":\"Bearer\",\"access_token\":\"test-local-token\",\"expires_in\":\"3600\"}";
    private volatile String smsBody = "{\"outboundSMSMessageRequest\":{}}";
    private volatile boolean slowSms;
    private volatile String sentPayload;
    private volatile String sentAuthorization;
    private volatile String tokenAuthorization;
    private volatile String tokenPayload;
    private final MutableClock clock = new MutableClock();

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/oauth/v3/token", exchange -> {
            oauthCalls.incrementAndGet();
            tokenAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            tokenPayload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, tokenStatus, tokenBody, false);
        });
        server.createContext("/smsmessaging/v1/outbound/", exchange -> {
            smsCalls.incrementAndGet();
            sentAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            sentPayload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, smsStatus, smsBody, slowSms);
        });
        server.createContext("/unexpected", exchange -> {
            unexpectedCalls.incrementAndGet();
            reply(exchange, 201, smsBody, false);
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static void reply(HttpExchange exchange, int status, String body, boolean slow) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.getResponseHeaders().set("Location", "/unexpected");
            exchange.sendResponseHeaders(status, bytes.length);
            if (slow) {
                try { Thread.sleep(1500); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
            exchange.getResponseBody().write(bytes);
        }
    }

    private OrangeSmsGateway gateway(Duration deadline) {
        return new OrangeSmsGateway(base, "local-test-id".toCharArray(), "local-test-only-secret".toCharArray(),
                "WAKA", clock, deadline);
    }

    private static SmsMessage message() {
        char[] code = new char[6];
        SecureRandom random = new SecureRandom();
        for (int i = 0; i < code.length; i++) { code[i] = (char) ('0' + random.nextInt(10)); }
        return new SmsMessage(UUID.randomUUID(), ("+2376" + "0".repeat(8)).toCharArray(), code);
    }

    @Test void correctProtocolCachesTokenWithoutClaimingDelivery() {
        try (var gateway = gateway(Duration.ofSeconds(2)); var first = message(); var second = message()) {
            assertEquals(Submission.ACCEPTED, gateway.send(first));
            assertEquals(Submission.ACCEPTED, gateway.send(second));
            assertEquals(1, oauthCalls.get());
            assertEquals(2, smsCalls.get());
            assertEquals("grant_type=client_credentials", tokenPayload);
            assertTrue(tokenAuthorization.startsWith("Basic "));
            assertEquals("Bearer test-local-token", sentAuthorization);
            assertTrue(sentPayload.contains("\"senderName\":\"WAKA\""));
            assertTrue(sentPayload.contains("\"senderAddress\":\"tel:+2370000\""));
            assertTrue(sentPayload.contains("Code WAKA : " + new String(second.code())));
            assertFalse(gateway.toString().contains("local-test-only-secret"));
            assertFalse(gateway.toString().contains("test-local-token"));
        }
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 403, 408, 409, 429})
    void clientErrorAfterPostDoesNotProveNonDeliveryAndNeverRetriesSms(int status) {
        smsStatus = status;
        try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message));
            assertEquals(1, smsCalls.get());
            assertEquals(0, unexpectedCalls.get());
        }
    }

    @Test void defaultSenderOmitsNameRatherThanInventingOrCopyingAnUnapprovedCustomName() {
        try (var gateway = new OrangeSmsGateway(base, "local-test-id".toCharArray(),
                "local-test-only-secret".toCharArray(), null, clock, Duration.ofSeconds(2));
             var message = message()) {
            assertEquals(Submission.ACCEPTED, gateway.send(message));
            assertEquals(1, smsCalls.get());
            assertFalse(sentPayload.contains("senderName"));
            assertTrue(sentPayload.contains("\"senderAddress\":\"tel:+2370000\""));
        }
    }

    @Test void customConstructorDoesNotSilentlySelectDefaultAndFactoriesHaveNoNetworkSideEffect() {
        assertEquals("INVALID_ORANGE_CONFIGURATION", assertThrows(IllegalArgumentException.class, () ->
                new OrangeSmsGateway("local-test-id".toCharArray(), "local-test-only-secret".toCharArray(),
                        null)).getMessage());
        try (var unused = OrangeSmsGateway.usingDefaultSender("local-test-id".toCharArray(),
                "local-test-only-secret".toCharArray())) {
            // Construction ne demande aucun jeton et ne consomme aucune unite.
            assertEquals(0, oauthCalls.get());
            assertEquals(0, smsCalls.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {302, 500, 502, 504})
    void uncertainResponseNeverRetriesOrFollowsRedirect(int status) {
        smsStatus = status;
        try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message));
            assertEquals(1, smsCalls.get());
            assertEquals(0, unexpectedCalls.get());
        }
    }

    @Test void noSmsWhenOAuthFailsRedirectsOrContainsDuplicateFields() {
        for (String body : List.of("{}", "[]", "{", tokenBody + "{}",
                "{\"token_type\":\"Bearer\",\"access_token\":\"a\",\"access_token\":\"b\",\"expires_in\":3600}")) {
            tokenBody = body;
            try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
                assertEquals(Submission.REJECTED, gateway.send(message));
            }
        }
        tokenStatus = 302;
        try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
            assertEquals(Submission.REJECTED, gateway.send(message));
        }
        assertEquals(0, smsCalls.get());
        assertEquals(0, unexpectedCalls.get());
    }

    @Test void tokenExpiresAndUnauthorizedDoesNotTriggerSmsReplay() {
        try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
            assertEquals(Submission.ACCEPTED, gateway.send(message));
            clock.now = clock.now.plusSeconds(3570); // Cache expires at this exact instant.
            smsStatus = 401;
            assertEquals(Submission.UNKNOWN, gateway.send(message));
            assertEquals(2, oauthCalls.get());
            assertEquals(2, smsCalls.get());
            smsStatus = 201;
            assertEquals(Submission.ACCEPTED, gateway.send(message));
            assertEquals(3, oauthCalls.get());
            assertEquals(3, smsCalls.get());
        }
    }

    @Test void malformedOrOversizedCreatedIsUncertain() {
        for (String body : List.of("{}", "[]", "{", smsBody + "{}", " ".repeat(8193))) {
            smsBody = body;
            try (var gateway = gateway(Duration.ofSeconds(2)); var message = message()) {
                assertEquals(Submission.UNKNOWN, gateway.send(message));
            }
        }
        assertEquals(5, smsCalls.get());
    }

    @Test void totalDeadlineAlsoCoversSlowResponseBody() {
        slowSms = true;
        try (var gateway = gateway(Duration.ofMillis(500)); var message = message()) {
            long start = System.nanoTime();
            assertEquals(Submission.UNKNOWN, gateway.send(message));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals(1, smsCalls.get());
        }
    }

    @Test void closedGatewayAndInvalidDestinationNeverCallProvider() {
        var gateway = gateway(Duration.ofSeconds(2));
        gateway.close();
        try (var message = message()) { assertEquals(Submission.REJECTED, gateway.send(message)); }
        assertEquals(0, oauthCalls.get());
        assertEquals(0, smsCalls.get());
        for (URI invalid : List.of(URI.create("http://api.orange.com"), URI.create("http://localhost:80"),
                URI.create("http://127.0.0.1:80/path"), URI.create("http://a@127.0.0.1:80"))) {
            assertEquals("INVALID_ORANGE_CONFIGURATION", assertThrows(IllegalArgumentException.class, () ->
                    new OrangeSmsGateway(invalid, "i".toCharArray(), "s".toCharArray(), "WAKA", clock,
                            Duration.ofSeconds(1))).getMessage());
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
