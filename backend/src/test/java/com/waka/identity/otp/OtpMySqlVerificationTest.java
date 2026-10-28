package com.waka.identity.otp;

import static org.junit.jupiter.api.Assertions.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real MySQL only. Existing schema must already have V3; no runtime/schema edits here. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class OtpMySqlVerificationTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private OtpCrypto crypto;
    private MutableClock clock;
    private CapturingGateway gateway;
    private PersistentOtpService service;
    private String globalGate;
    private final String client = UUID.randomUUID().toString();
    private final String contact = "+" + "237" + "6" + "0".repeat(8);

    @BeforeEach void prepare() {
        var dataSource = new DriverManagerDataSource();
        String url = required("WAKA_DB_URL");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:3306/waka_test?")
                || !url.contains("sslMode=VERIFY_CA") || url.contains("user=") || url.contains("password=")) {
            throw new IllegalStateException("LOCAL_TEST_DATABASE_REQUIRED");
        }
        dataSource.setUrl(url);
        dataSource.setUsername(required("WAKA_DB_USER"));
        dataSource.setPassword(required("WAKA_DB_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource); manager = new DataSourceTransactionManager(dataSource);
        SecureRandom random = new SecureRandom(); byte[] mac = new byte[32], enc = new byte[32];
        random.nextBytes(mac); random.nextBytes(enc); crypto = new OtpCrypto(mac, enc, random);
        Arrays.fill(mac, (byte) 0); Arrays.fill(enc, (byte) 0);
        globalGate = crypto.fingerprint("otp-global-gate-v1");
        clock = new MutableClock(); gateway = new CapturingGateway();
        service = service(100);
    }

    @AfterEach void cleanOnlyThisTestsRandomNamespace() {
        if (globalGate == null) return;
        jdbc.update("DELETE FROM otp_verification WHERE global_gate=?", globalGate);
        jdbc.update("DELETE FROM otp_rate_event WHERE global_gate=?", globalGate);
        jdbc.update("DELETE FROM otp_challenge WHERE global_gate=?", globalGate);
        jdbc.update("DELETE FROM otp_gate WHERE gate_hash=?", globalGate);
        if (gateway.code != null) Arrays.fill(gateway.code, '\0');
    }

    @Test void concurrentRequestAndVerificationAreExactlyOnceAndReplayKeepsExpiry() throws Exception {
        UUID requestKey = UUID.randomUUID();
        List<PersistentOtpService.Challenge> challenges = concurrent(12,
                () -> service.request(client, requestKey, contact));
        UUID id = challenges.getFirst().id();
        for (var challenge : challenges) assertEquals(id, challenge.id());
        assertEquals(1, gateway.calls.get()); assertFalse(gateway.insideTransaction);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM otp_challenge WHERE global_gate=?", Integer.class, globalGate));
        char[] code = gateway.code.clone(); UUID verifyKey = UUID.randomUUID();
        List<PersistentOtpService.Proof> proofs = concurrent(12, () -> service.verify(id, client, verifyKey, code));
        for (var proof : proofs) assertEquals(proofs.getFirst(), proof);
        assertNull(jdbc.queryForObject("SELECT code_mac FROM otp_challenge WHERE id=?", String.class, id.toString()));
        assertNull(jdbc.queryForObject("SELECT destination_cipher FROM otp_challenge WHERE id=?", byte[].class, id.toString()));
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(id, client, UUID.randomUUID(), code));
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(id, UUID.randomUUID().toString(), verifyKey, code));
        clock.advance(400);
        assertEquals(proofs.getFirst(), service.verify(id, client, verifyKey, code));
        assertTrue(proofs.getFirst().expiresAt().isBefore(clock.instant()));
        assertEquals(0, proofs.getFirst().expiresAt().getNano() % 1_000);
    }

    @Test void failuresCommitAndIdempotentWrongRetryDoesNotSpendAnotherAttempt() {
        var challenge = service.request(client, UUID.randomUUID(), contact);
        char[] correct = gateway.code.clone(), wrong = wrong(correct);
        UUID key = UUID.randomUUID();
        for (int i = 0; i < 3; i++) assertFailure(OtpException.Code.VERIFICATION_INVALID,
                () -> service.verify(challenge.id(), client, key, wrong));
        assertEquals(1, attempts(challenge.id()));
        assertFailure(OtpException.Code.IDEMPOTENCY_KEY_REUSED, () -> service.verify(challenge.id(), client, key, correct));
        for (int i = 0; i < 4; i++) assertFailure(OtpException.Code.VERIFICATION_INVALID,
                () -> service.verify(challenge.id(), client, UUID.randomUUID(), wrong));
        assertEquals(5, attempts(challenge.id()));
        assertNull(jdbc.queryForObject("SELECT code_mac FROM otp_challenge WHERE id=?", String.class, challenge.id().toString()));
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(challenge.id(), client, UUID.randomUUID(), correct));
        clock.advance(61);
        var second = service.request(client, UUID.randomUUID(), contact);
        char[] secondWrong = wrong(gateway.code);
        for (int i = 0; i < 5; i++) assertFailure(OtpException.Code.VERIFICATION_INVALID,
                () -> service.verify(second.id(), client, UUID.randomUUID(), secondWrong));
        clock.advance(61);
        assertFailure(OtpException.Code.RATE_LIMITED, () -> service.request(client, UUID.randomUUID(), contact));
    }

    @Test void unknownAndCrashSendingBlockResendAndKeepReservation() {
        gateway.throwUncertain = true;
        UUID key = UUID.randomUUID();
        var challenge = service.request(client, key, contact);
        assertEquals(PersistentOtpService.Submission.UNKNOWN, challenge.submission());
        assertEquals(challenge, service.request(client, key, contact));
        clock.advance(61);
        assertFailure(OtpException.Code.RATE_LIMITED, () -> service.request(client, UUID.randomUUID(), contact));
        assertEquals(1, gateway.calls.get());
        jdbc.update("UPDATE otp_challenge SET submission='SENDING' WHERE id=?", challenge.id().toString());
        assertFailure(OtpException.Code.RATE_LIMITED, () -> service.request(client, UUID.randomUUID(), contact));
        clock.advance(240); gateway.throwUncertain = false;
        var next = service.request(client, UUID.randomUUID(), contact);
        assertNotEquals(challenge.id(), next.id()); assertEquals(2, gateway.calls.get());
        assertNull(jdbc.queryForObject("SELECT code_mac FROM otp_challenge WHERE id=?", String.class, challenge.id().toString()));
    }

    @Test void cooldownSupersessionQuotaConflictAndClockMicroseconds() {
        UUID key = UUID.randomUUID();
        var first = service.request(client, key, contact); char[] oldCode = gateway.code.clone();
        assertEquals(123456000, first.expiresAt().getNano());
        assertFailure(OtpException.Code.IDEMPOTENCY_KEY_REUSED,
                () -> service.request(client, key, contact.substring(0, contact.length() - 1) + '1'));
        assertFailure(OtpException.Code.RATE_LIMITED, () -> service.request(client, UUID.randomUUID(), contact));
        clock.advance(60);
        service.request(client, UUID.randomUUID(), contact);
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(first.id(), client, UUID.randomUUID(), oldCode));
        clock.advance(60); service.request(client, UUID.randomUUID(), contact);
        clock.advance(60);
        assertFailure(OtpException.Code.RATE_LIMITED, () -> service.request(client, UUID.randomUUID(), contact));
        clock.advance(901); service.request(client, UUID.randomUUID(), contact);
    }

    @Test void globalBudgetIsDurableAcrossServiceInstancesAndZeroForbidsSending() throws Exception {
        service = service(1);
        var other = service(1);
        AtomicInteger instanceChoice = new AtomicInteger();
        List<Boolean> successes = concurrent(12, () -> {
            try {
                var instance = instanceChoice.getAndIncrement() % 2 == 0 ? service : other;
                instance.request(UUID.randomUUID().toString(), UUID.randomUUID(), contact);
                return true;
            }
            catch (OtpException expected) { assertEquals(OtpException.Code.BUDGET_EXHAUSTED, expected.code()); return false; }
        });
        assertEquals(1, successes.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, gateway.calls.get());
        clock.advance(3_601);
        assertFailure(OtpException.Code.BUDGET_EXHAUSTED, () -> service(0).request(client, UUID.randomUUID(), contact));
        service(1).request(client, UUID.randomUUID(), contact);
        assertEquals(2, gateway.calls.get());
    }

    @Test void acceptedCodeExpiresAtExactBoundaryAndRejectedDoesNotRefundBudget() {
        var first = service.request(client, UUID.randomUUID(), contact); char[] code = gateway.code.clone();
        clock.advance(300); service.eraseExpiredSecrets();
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(first.id(), client, UUID.randomUUID(), code));
        assertNull(jdbc.queryForObject("SELECT code_mac FROM otp_challenge WHERE id=?", String.class, first.id().toString()));
        gateway.result = SmsGateway.Submission.REJECTED;
        var rejected = service(2).request(client, UUID.randomUUID(), contact);
        assertEquals(PersistentOtpService.Submission.REJECTED, rejected.submission());
        clock.advance(61);
        assertFailure(OtpException.Code.BUDGET_EXHAUSTED, () -> service(2).request(client, UUID.randomUUID(), contact));
    }

    @Test void verificationStorageIsBoundedAndUncertainReplacementNeverRestoresOldCode() {
        var first = service.request(client, UUID.randomUUID(), contact); char[] oldCode = gateway.code.clone();
        for (int i = 0; i < 12; i++) {
            assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(UUID.randomUUID(), client, UUID.randomUUID(), oldCode));
            assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(first.id(), UUID.randomUUID().toString(), UUID.randomUUID(), oldCode));
        }
        assertEquals(0, verificationRows());
        clock.advance(60); gateway.result = SmsGateway.Submission.UNKNOWN;
        var replacement = service.request(client, UUID.randomUUID(), contact);
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(first.id(), client, UUID.randomUUID(), oldCode));
        assertEquals(0, verificationRows());
        char[] wrong = wrong(gateway.code);
        for (int i = 0; i < 12; i++) assertFailure(OtpException.Code.VERIFICATION_INVALID,
                () -> service.verify(replacement.id(), client, UUID.randomUUID(), wrong));
        assertEquals(5, verificationRows()); assertEquals(5, attempts(replacement.id()));
        clock.advance(300); gateway.result = SmsGateway.Submission.REJECTED;
        service.request(client, UUID.randomUUID(), contact);
        assertFailure(OtpException.Code.VERIFICATION_INVALID, () -> service.verify(first.id(), client, UUID.randomUUID(), oldCode));
        assertEquals(5, verificationRows());
    }

    private PersistentOtpService service(int budget) { return new PersistentOtpService(jdbc, manager, crypto, new OtpPolicy(budget), gateway, clock); }
    private int attempts(UUID id) { return jdbc.queryForObject("SELECT wrong_attempts FROM otp_challenge WHERE id=?", Integer.class, id.toString()); }
    private int verificationRows() { return jdbc.queryForObject("SELECT COUNT(*) FROM otp_verification WHERE global_gate=?", Integer.class, globalGate); }
    private static char[] wrong(char[] correct) { char[] wrong = correct.clone(); wrong[0] = wrong[0] == '9' ? '0' : (char) (wrong[0] + 1); return wrong; }
    private static void assertFailure(OtpException.Code code, Runnable operation) {
        var exception = assertThrows(OtpException.class, operation::run);
        assertEquals(code, exception.code()); assertNull(exception.getCause());
    }
    private static <T> List<T> concurrent(int count, Callable<T> action) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<T>> calls = new ArrayList<>(); for (int i = 0; i < count; i++) calls.add(action);
            List<T> results = new ArrayList<>(); for (var future : executor.invokeAll(calls)) results.add(future.get());
            return results;
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("MYSQL_TEST_CONFIGURATION_REQUIRED");
        return value;
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-15T12:00:00.123456789Z"));
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
    }
    private static final class CapturingGateway implements SmsGateway {
        final AtomicInteger calls = new AtomicInteger();
        volatile char[] code;
        volatile boolean insideTransaction;
        volatile boolean throwUncertain;
        volatile Submission result = Submission.ACCEPTED;
        @Override public Submission send(SmsMessage message) throws Exception {
            calls.incrementAndGet(); insideTransaction = TransactionSynchronizationManager.isActualTransactionActive();
            if (code != null) Arrays.fill(code, '\0'); code = message.code();
            if (throwUncertain) throw new Exception("SYNTHETIC_PROVIDER_EXCEPTION_MUST_BE_MASKED");
            return result;
        }
    }
}
