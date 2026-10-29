package com.waka.referrals;

import com.waka.identity.otp.OtpCrypto;
import com.waka.identity.otp.OtpPolicy;
import com.waka.identity.otp.PersistentOtpService;
import com.waka.identity.otp.PersistedOtpProofVerifier;
import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.session.SessionException;
import com.waka.identity.session.PersistentSessionService;
import com.waka.identity.session.SessionCrypto;
import com.waka.identity.session.SessionGrant;
import com.waka.identity.session.SessionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.*;

/** Real opt-in MySQL/V14; synthetic accounts only. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class ReferralMySqlVerificationTest {
    private record Login(char[] token, UUID account) { }
    private JdbcTemplate jdbc;
    private byte[] lookupKey, enc, mac;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService otp;
    private PersistentSessionService sessions;
    private PersistentReferralService referrals;
    private String gate;
    private final List<String> otpGates = new ArrayList<>();
    private final String client = UUID.randomUUID().toString();
    private final String contact = "+2376" + "0".repeat(8);
    private final String otherContact = "+2376" + "0".repeat(7) + "1";
    private final String thirdContact = "+2376" + "0".repeat(7) + "2";

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
        referrals = new PersistentReferralService(sessions, jdbc, random, clock);
    }

    @AfterEach void cleanOnlyRandomNamespaces() {
        try {
            if (jdbc != null && gate != null) {
                for (String table : List.of("referral_link", "referral_code", "identity_session",
                        "identity_account", "identity_session_gate")) {
                    jdbc.update("DELETE FROM " + table + " WHERE gate_hash=?", gate);
                }
            }
            for (String otpGate : otpGates) {
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

    @Test void oneReferrerOnceNeverOneselfAndCodesStayStable() {
        Login referrer = login(contact);
        var own = referrers(referrer);
        assertTrue(PersistentReferralService.CODE.matcher(own.referralCode()).matches());
        assertEquals(own.referralCode(), referrals.mine(referrer.token, client).referralCode(), "assigned once");
        assertFalse(own.referrerAttached());
        assertEquals(ReferralException.Code.SELF_REFERRAL, failure(() -> referrals.attach(referrer.token, client, own.referralCode())));
        clock.advance(61);
        Login referred = login(otherContact);
        assertEquals(ReferralException.Code.CODE_NOT_RECOGNIZED, failure(() -> referrals.attach(referred.token, client, unknownCode(own.referralCode()))));
        var first = referrals.attach(referred.token, client, own.referralCode());
        assertTrue(first.created());
        var again = referrals.attach(referred.token, client, own.referralCode());
        assertFalse(again.created());
        assertEquals(first.attachedAt(), again.attachedAt(), "the same attachment is found");
        String otherCode = referrals.mine(referred.token, client).referralCode();
        assertNotEquals(own.referralCode(), otherCode);
        assertTrue(referrals.mine(referred.token, client).referrerAttached());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM referral_link WHERE gate_hash=?", Integer.class, gate));
        assertEquals(referrer.account.toString(), jdbc.queryForObject("SELECT referrer_account_id FROM referral_link WHERE gate_hash=? AND account_id=?",
                String.class, gate, referred.account.toString()));
    }

    @Test void attachmentIsPermanentAndInactiveOwnersAreNotRecognized() {
        Login first = login(contact);
        String firstCode = referrers(first).referralCode();
        clock.advance(61);
        Login second = login(otherContact);
        String secondCode = referrers(second).referralCode();
        clock.advance(61);
        Login third = login(thirdContact);
        referrals.attach(third.token, client, firstCode);
        assertEquals(ReferralException.Code.REFERRER_ALREADY_SET, failure(() -> referrals.attach(third.token, client, secondCode)),
                "no free reassignment: a correction goes through assistance");
        jdbc.update("UPDATE identity_account SET status='DISABLED' WHERE gate_hash=? AND id=?", gate, second.account.toString());
        assertEquals(ReferralException.Code.CODE_NOT_RECOGNIZED, failure(() -> referrals.attach(first.token, client, secondCode)),
                "an inactive owner's code is answered like an unknown code");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM referral_link WHERE gate_hash=?", Integer.class, gate));
        assertThrows(SessionException.class, () -> referrals.mine(second.token, client), "a disabled account has no session use");
    }

    @Test void codesUseTheUnambiguousAlphabet() {
        var service = referrals;
        for (int i = 0; i < 2000; i++) {
            String code = service.draw();
            assertTrue(PersistentReferralService.CODE.matcher(code).matches(), code);
            assertFalse(code.matches(".*[01IO].*"));
        }
    }

    private PersistentReferralService.ReferralView referrers(Login login) { return referrals.mine(login.token, client); }
    private String unknownCode(String avoid) { return avoid.equals("AAAAAAAA") ? "BBBBBBBB" : "AAAAAAAA"; }
    private static ReferralException.Code failure(Runnable operation) {
        return assertThrows(ReferralException.class, operation::run).code();
    }

    private Login login(String phone) {
        var challenge = otp.request(client, UUID.randomUUID(), phone);
        otpGates.add(jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, challenge.id().toString()));
        char[] code = gateway.code.clone();
        PersistentOtpService.Proof proof;
        try { proof = otp.verify(challenge.id(), client, UUID.randomUUID(), code); }
        finally { Arrays.fill(code, '\0'); }
        try (SessionGrant grant = sessions.open(proof, client, phone)) { return new Login(grant.token(), grant.accountId()); }
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
