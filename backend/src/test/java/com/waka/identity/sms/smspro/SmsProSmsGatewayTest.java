package com.waka.identity.sms.smspro;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.waka.identity.otp.SmsGateway.Submission;
import com.waka.identity.otp.SmsMessage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP on the local loopback only: no SmsPro account, real key or SMS. */
class SmsProSmsGatewayTest {
    private static final char[] KEY = "smspro_test_localonly0123456789".toCharArray();
    private HttpServer server;
    private ExecutorService executor;
    private URI base;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 201;
    private volatile String body = "{\"success\":true,\"message_id\":\"8f2c1d4e-0b1a-4c3d-9e8f-123456789abc\",\"to\":\"+237699000001\",\"status\":\"delivered\",\"segments\":1,\"test\":true}";
    private volatile String contentType = "application/json; charset=utf-8";
    private volatile boolean slow;
    private volatile String sent;
    private volatile String authorization;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/messages", exchange -> {
            calls.incrementAndGet();
            authorization = exchange.getRequestHeaders().getFirst("Authorization");
            sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange);
        });
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void reply(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (slow) {
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private SmsProSmsGateway gateway() {
        return new SmsProSmsGateway(base, KEY, "WAKA", Duration.ofMillis(800));
    }

    private static SmsMessage message(String phone, String code) {
        return new SmsMessage(UUID.randomUUID(), phone.toCharArray(), code.toCharArray());
    }

    @Test void sendsTheCodeWithTheDeclaredSenderAndTheKey() {
        try (var gateway = gateway()) {
            assertEquals(Submission.ACCEPTED, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get());
        assertEquals("Bearer " + new String(KEY), authorization);
        assertEquals("{\"to\":\"+237699000001\",\"message\":\"Code WAKA : 123456. Valable 5 minutes. Ne le partagez pas.\",\"from\":\"WAKA\"}", sent);
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 402, 404, 422, 429})
    void documentedRefusalsAreRejectedSoTheUserCanAskAgain(int refused) {
        status = refused;
        body = "{\"error\":\"refused\",\"message\":\"refused\"}";
        try (var gateway = gateway()) {
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get(), "never resent");
    }

    @ParameterizedTest @ValueSource(ints = {200, 202, 500, 502, 503})
    void anythingElseAfterTheRequestStartedStaysUnknown(int other) {
        status = other;
        try (var gateway = gateway()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get(), "no automatic retry: SmsPro has no idempotency key");
    }

    @Test void createdWithoutProofOfAcceptanceStaysUnknown() {
        try (var gateway = gateway()) {
            body = "{\"success\":false,\"message_id\":\"8f2c1d4e-0b1a-4c3d-9e8f-123456789abc\"}";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
            body = "{\"success\":true}";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
            body = "<html>proxy</html>";
            contentType = "text/html";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
    }

    @Test void aSlowAnswerStaysUnknownWithinTheDeadline() {
        slow = true;
        try (var gateway = gateway()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
    }

    @Test void aMalformedNumberOrCodeIsRefusedBeforeAnyCall() {
        try (var gateway = gateway()) {
            assertEquals(Submission.REJECTED, gateway.send(message("+237599000001", "123456")));
            assertEquals(Submission.REJECTED, gateway.send(message("+23769900000", "123456")));
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "12a456")));
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001\",\"from\":\"X", "123456")));
        }
        assertEquals(0, calls.get());
    }

    @Test void aClosedGatewaySendsNothing() {
        var gateway = gateway();
        gateway.close();
        assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "123456")));
        assertEquals(0, calls.get());
    }

    @Test void configurationIsCheckedAndNeverPrinted() {
        assertThrows(IllegalArgumentException.class, () -> new SmsProSmsGateway(KEY, "WAKA PRO"));
        assertThrows(IllegalArgumentException.class, () -> new SmsProSmsGateway(KEY, "WAKA\"x"));
        assertThrows(IllegalArgumentException.class, () -> new SmsProSmsGateway("not-a-key-0123456789".toCharArray(), "WAKA"));
        assertThrows(IllegalArgumentException.class, () -> new SmsProSmsGateway("smspro_bad key 0123456".toCharArray(), "WAKA"));
        assertThrows(IllegalArgumentException.class,
                () -> new SmsProSmsGateway(URI.create("https://example.com"), KEY, "WAKA", Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new SmsProSmsGateway(URI.create("http://127.0.0.1:9/x"), KEY, "WAKA", Duration.ofSeconds(1)));
        try (var gateway = new SmsProSmsGateway(KEY, "WAKA")) {
            assertFalse(gateway.toString().contains("smspro_"));
        }
        assertTrue(SmsProSmsGateway.isTestKey(KEY));
        assertFalse(SmsProSmsGateway.isTestKey("smspro_live0123456789abcdef".toCharArray()));
    }
}
