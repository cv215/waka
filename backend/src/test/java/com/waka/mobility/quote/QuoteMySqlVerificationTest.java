package com.waka.mobility.quote;

import com.waka.geo.GeoPoint;
import com.waka.geo.RouteDistanceProvider;
import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.profile.ProfileCrypto;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionGrant;
import com.waka.identity.session.SessionPolicy;
import com.waka.pricing.infrastructure.JdbcFareScheduleRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V12 with the real launch-v1 grid; synthetic points and a scripted route provider. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class QuoteMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    private JdbcTemplate jdbc;
    private byte[] lookupKey, enc, mac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentQuoteService quotes;
    private final AtomicReference<BigDecimal> distance = new AtomicReference<>(new BigDecimal("7480.4"));
    private final AtomicInteger providerCalls = new AtomicInteger();
    private String gate, otpGate;
    private final String client = UUID.randomUUID().toString();
    private final String contact = "+2376" + "0".repeat(8);
    private final GeoPoint origin = new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.502"));
    private final GeoPoint destination = new GeoPoint(new BigDecimal("3.87"), new BigDecimal("11.52"));
    private final GeoPoint outside = new GeoPoint(new BigDecimal("4.05"), new BigDecimal("9.7"));

    @BeforeEach void prepare() {
        String url = required("WAKA_DB_URL");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:3306/waka_test?")
                || !url.contains("sslMode=VERIFY_CA") || url.contains("user=") || url.contains("password=")) {
            throw new IllegalStateException("LOCAL_TEST_DATABASE_REQUIRED");
        }
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(url); source.setUsername(required("WAKA_DB_USER")); source.setPassword(required("WAKA_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(5);
        var manager = new DataSourceTransactionManager(source);
        SecureRandom random = new SecureRandom();
        byte[] otpMac = new byte[32], otpEnc = new byte[32];
        lookupKey = new byte[32]; enc = new byte[32]; mac = new byte[32];
        for (byte[] key : new byte[][] { otpMac, otpEnc, lookupKey, enc, mac }) random.nextBytes(key);
        OtpCrypto otpCrypto = new OtpCrypto(otpMac, otpEnc, random);
        Arrays.fill(otpMac, (byte) 0); Arrays.fill(otpEnc, (byte) 0);
        clock = new MutableClock(); gateway = new CapturingGateway();
        otp = new PersistentOtpService(jdbc, manager, otpCrypto, new OtpPolicy(100), gateway, clock);
        sessions = new PersistentSessionService(jdbc, manager, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                new SessionCrypto(lookupKey, random), new SessionPolicy(Duration.ofSeconds(3600), Duration.ofSeconds(600)), clock);
        gate = sessions.identityGateHash();
        RouteDistanceProvider routes = (from, to) -> { providerCalls.incrementAndGet(); return distance.get(); };
        // Synthetic test territory: a box around the two test points only, not an adopted territory.
        quotes = new PersistentQuoteService(sessions, jdbc, new ProfileCrypto(enc, mac, "test-key", random),
                new JdbcFareScheduleRepository(jdbc), "launch-v1", routes,
                point -> point.latitude().compareTo(new BigDecimal("3.9")) < 0 && point.longitude().compareTo(new BigDecimal("11")) > 0,
                clock);
    }

    @AfterEach void cleanOnlyRandomNamespaces() {
        try {
            if (jdbc != null && gate != null) {
                for (String table : List.of("quote", "identity_session", "identity_account", "identity_session_gate")) {
                    jdbc.update("DELETE FROM " + table + " WHERE gate_hash=?", gate);
                }
            }
            if (jdbc != null && otpGate != null) {
                jdbc.update("DELETE FROM otp_verification WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_rate_event WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_challenge WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_gate WHERE gate_hash=?", otpGate);
            }
        } finally {
            for (byte[] key : new byte[][] { lookupKey, enc, mac }) if (key != null) Arrays.fill(key, (byte) 0);
            if (gateway != null && gateway.code != null) Arrays.fill(gateway.code, '\0');
        }
    }

    @Test void pricesComeFromTheRealGridAndTheServerDistance() {
        Login login = login(contact);
        var vip = quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.VIP, 2, origin, destination);
        assertEquals(7480, vip.distanceMetres(), "half-up to the metre before the band");
        assertEquals(600, vip.placePriceXaf());
        assertEquals(7500, vip.totalXaf(), "VIP = 12.5 seats whatever the occupants");
        assertEquals("launch-v1", vip.fareVersion());
        assertEquals(vip.createdAt().plusSeconds(300), vip.expiresAt());
        var shared = quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 3, origin, destination);
        assertEquals(1800, shared.totalXaf());
        assertEquals(1, providerCalls.get(), "Partagé and VIP side by side: the same road, one maps call");
        clock.advance(301); // the road is kept five minutes; a new distance needs a new measure
        distance.set(new BigDecimal("1000.4"));
        var small = quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.VIP, 1, origin, destination);
        assertEquals(205, small.placePriceXaf());
        assertEquals(2563, small.totalXaf(), "25 x 205 / 2 = 2562.5, rounded once at the end");
        clock.advance(301);
        distance.set(new BigDecimal("17900.5"));
        assertFailure(QuoteException.Code.DISTANCE_OUT_OF_RANGE,
                () -> quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 1, origin, destination));
        clock.advance(301);
        distance.set(null);
        assertFailure(QuoteException.Code.NO_ROUTE,
                () -> quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 1, origin, destination));
        int before = providerCalls.get();
        assertFailure(QuoteException.Code.OUTSIDE_SERVICE_AREA,
                () -> quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 1, origin, outside));
        assertEquals(before, providerCalls.get(), "no provider call outside the territory");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM quote WHERE gate_hash=?", Integer.class, gate));
    }

    @Test void sameKeySameRequestIsOneQuoteAndOneProviderCall() {
        Login login = login(contact);
        UUID key = UUID.randomUUID();
        var first = quotes.create(login.token, client, key, PersistentQuoteService.Mode.SHARED, 2, origin, destination);
        distance.set(new BigDecimal("15000"));
        clock.advance(400);
        var again = quotes.create(login.token, client, key, PersistentQuoteService.Mode.SHARED, 2, origin, destination);
        assertTrue(again.idempotentReplay());
        assertEquals(first.quoteId(), again.quoteId());
        assertEquals(first.totalXaf(), again.totalXaf(), "the stored price, not a new calculation");
        assertEquals(first.expiresAt(), again.expiresAt(), "a replay never extends the validity");
        assertEquals(1, providerCalls.get());
        assertFailure(QuoteException.Code.IDEMPOTENCY_KEY_REUSED,
                () -> quotes.create(login.token, client, key, PersistentQuoteService.Mode.SHARED, 3, origin, destination));
        assertFailure(QuoteException.Code.INVALID_REQUEST,
                () -> quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 6, origin, destination));
        assertFailure(QuoteException.Code.INVALID_REQUEST,
                () -> quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 1, origin, origin));
    }

    @Test void pointsAreEncryptedAndBoundToTheirQuote() {
        Login login = login(contact);
        var quote = quotes.create(login.token, client, UUID.randomUUID(), PersistentQuoteService.Mode.SHARED, 1, origin, destination);
        byte[] stored = jdbc.queryForObject("SELECT points_cipher FROM quote WHERE gate_hash=? AND id=?", byte[].class,
                gate, quote.quoteId().toString());
        String raw = new String(stored, StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("3.848") || raw.contains("11.502"), "no clear coordinate in the database");
    }

    private Login login(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGate = jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString());
        char[] code = gateway.code.clone();
        PersistentOtpService.Proof proof;
        try { proof = otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
        try (SessionGrant grant = sessions.open(proof, client, phone)) { return new Login(grant.token(), grant.accountId()); }
    }
    private static void assertFailure(QuoteException.Code code, Runnable operation) {
        assertEquals(code, assertThrows(QuoteException.class, operation::run).code());
    }
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("LOCAL_TEST_CONFIGURATION_REQUIRED");
        return value;
    }
    private static final class CapturingGateway implements SmsGateway {
        private char[] code;
        @Override public Submission send(SmsMessage message) {
            if (code != null) Arrays.fill(code, '\0');
            code = message.code(); return Submission.ACCEPTED;
        }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-29T10:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this;
        }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}
