package com.waka.identity.sms.cooms;

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

/** Real HTTP on the local loopback only: no Cooms account, real key or SMS. */
class CoomsSmsGatewayTest {
    private static final char[] KEY = "coom_live_localonly0123456789abcdef".toCharArray();
    private HttpServer server;
    private ExecutorService executor;
    private URI base;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = "{\"id\":\"sms_1\",\"status\":\"queued\"}";
    private volatile String contentType = "application/json; charset=utf-8";
    private volatile boolean slow;
    private volatile String sent;
    private volatile String key;

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/v1/sms/send", exchange -> {
            calls.incrementAndGet();
            key = exchange.getRequestHeaders().getFirst("x-api-key");
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

    private CoomsSmsGateway gateway() {
        return new CoomsSmsGateway(base, KEY, Duration.ofMillis(800));
    }

    private static SmsMessage message(String phone, String code) {
        return new SmsMessage(UUID.randomUUID(), phone.toCharArray(), code.toCharArray());
    }

    @Test void sendsTheCodeAsAnOtpWithTheKeyHeader() {
        try (var gateway = gateway()) {
            assertEquals(Submission.ACCEPTED, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get());
        assertEquals(new String(KEY), key);
        assertEquals("{\"phone\":\"+237699000001\",\"message\":\"Code WAKA : 123456. Valable 5 minutes. Ne le partagez pas.\",\"priority\":1}", sent);
    }

    @ParameterizedTest @ValueSource(ints = {400, 401, 402, 403, 422, 429, 503})
    void documentedRefusalsAreRejectedSoTheUserCanAskAgain(int refused) {
        status = refused;
        body = "{\"error\":\"REFUSED\"}";
        try (var gateway = gateway()) {
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get(), "never resent");
    }

    @ParameterizedTest @ValueSource(ints = {201, 202, 500, 502, 504})
    void anythingElseAfterTheRequestStartedStaysUnknown(int other) {
        status = other;
        try (var gateway = gateway()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
        assertEquals(1, calls.get(), "no switch to another provider after a doubtful answer");
    }

    @Test void okWithoutProofStaysUnknown() {
        try (var gateway = gateway()) {
            body = "{\"success\":false}";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
            body = "{\"error\":\"INSUFFICIENT_CREDITS\"}";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
            body = "[]";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
            body = "<html>ok</html>";
            contentType = "text/html";
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
    }

    @Test void aSlowAnswerStaysUnknown() {
        slow = true;
        try (var gateway = gateway()) {
            assertEquals(Submission.UNKNOWN, gateway.send(message("+237699000001", "123456")));
        }
    }

    @Test void aMalformedNumberOrCodeIsRefusedBeforeAnyCall() {
        try (var gateway = gateway()) {
            assertEquals(Submission.REJECTED, gateway.send(message("+237599000001", "123456")));
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "12a456")));
            assertEquals(Submission.REJECTED, gateway.send(message("+237699000001\",\"priority\":3,\"x\":\"", "123456")));
        }
        assertEquals(0, calls.get());
    }

    @Test void aClosedGatewaySendsNothing() {
        var gateway = gateway();
        gateway.close();
        assertEquals(Submission.REJECTED, gateway.send(message("+237699000001", "123456")));
        assertEquals(0, calls.get());
    }

    @Test void onlyACoomsGatewayAndAWellFormedKeyAreAccepted() {
        try (var gateway = new CoomsSmsGateway(URI.create("https://gw-89.cooms.app"), KEY)) {
            assertFalse(gateway.toString().contains("coom_"));
        }
        for (String wrong : new String[] {"http://gw-89.cooms.app", "https://gw-89.cooms.app.evil.example", "https://evilcooms.app",
                "https://gw-89.cooms.app/api", "https://gw-89.cooms.app:8443", "https://user@gw-89.cooms.app"}) {
            assertThrows(IllegalArgumentException.class, () -> new CoomsSmsGateway(URI.create(wrong), KEY), wrong);
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CoomsSmsGateway(URI.create("https://gw-89.cooms.app"), "coom_live_short".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> new CoomsSmsGateway(URI.create("https://gw-89.cooms.app"), "coom_live_bad key 0123456789".toCharArray()));
    }
}
