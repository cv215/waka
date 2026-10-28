package com.waka.identity.session;

import com.waka.identity.api.LoginController;
import com.waka.identity.api.MeController;
import com.waka.identity.application.LoginService;
import com.waka.identity.application.MeService;
import com.waka.identity.otp.*;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC in memory -> actual OTP/session cores -> opt-in MySQL TLS. Never a real SMS/provider. */
@EnabledIfEnvironmentVariable(named = "WAKA_VERIFY_MYSQL", matches = "true")
class LoginHttpMySqlVerificationTest {
    private static final String ROUTE = "/api/v1/auth/phone-challenges";
    private static final JsonMapper JSON = new JsonMapper();
    private final String client = UUID.randomUUID().toString();
    private final String phone = "+2376" + "0".repeat(8);
    private final String otherPhone = "+2376" + "0".repeat(7) + "1";
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private OtpCrypto otpCrypto;
    private SessionCrypto sessionCrypto;
    private SessionRecoveryCrypto recovery;
    private MutableClock clock;
    private Capture gateway;
    private String gate;
    private final Set<String> otpGates = new HashSet<>();

    @BeforeEach void prepare() {
        String url = required("WAKA_DB_URL");
        if (!url.startsWith("jdbc:mysql://127.0.0.1:3306/waka_test?")
                || !url.contains("sslMode=VERIFY_CA") || url.contains("user=") || url.contains("password=")) {
            throw new IllegalStateException("LOCAL_TEST_DATABASE_REQUIRED");
        }
        var source = new DriverManagerDataSource(url, required("WAKA_DB_USER"), required("WAKA_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(3);
        transactions = new DataSourceTransactionManager(source);
        var random = new SecureRandom();
        byte[] mac = new byte[32], enc = new byte[32], lookup = new byte[32], recoveryKey = new byte[32];
        random.nextBytes(mac); random.nextBytes(enc); random.nextBytes(lookup); random.nextBytes(recoveryKey);
        try {
            otpCrypto = new OtpCrypto(mac, enc, random);
            sessionCrypto = new SessionCrypto(lookup, random);
            recovery = new SessionRecoveryCrypto(recoveryKey, "test-only", random);
        } finally {
            Arrays.fill(mac, (byte) 0); Arrays.fill(enc, (byte) 0);
            Arrays.fill(lookup, (byte) 0); Arrays.fill(recoveryKey, (byte) 0);
        }
        gate = sessionCrypto.gateHash(); clock = new MutableClock(); gateway = new Capture();
    }

    @AfterEach void cleanOnlyOurRandomNamespaces() {
        try {
            if (jdbc != null && gate != null) {
                jdbc.update("DELETE FROM identity_session_recovery WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_session WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_account WHERE gate_hash=?", gate);
                jdbc.update("DELETE FROM identity_session_gate WHERE gate_hash=?", gate);
            }
            for (String otpGate : otpGates) {
                jdbc.update("DELETE FROM otp_verification WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_rate_event WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_challenge WHERE global_gate=?", otpGate);
                jdbc.update("DELETE FROM otp_gate WHERE gate_hash=?", otpGate);
            }
        } finally { if (gateway != null) gateway.close(); }
    }

    @Test void requestIsIdempotentAndDoesNotEchoCodePhoneOrGrantAccess() throws Exception {
        MockMvc mvc = mvc(); UUID key = UUID.randomUUID();
        var first = request(mvc, key, phone);
        var repeat = request(mvc, key, phone);
        assertEquals(first, repeat); assertEquals(1, gateway.calls.get());
        assertFalse(first.toString().contains(phone)); assertFalse(first.has("token")); assertFalse(first.has("code"));
        mvc.perform(requestCommand(key, otherPhone)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(1, gateway.calls.get()); assertEquals(0, count("identity_session"));
    }

    @Test void lostResponseRetriesReturnExactlyOneSessionAcrossServiceInstances() throws Exception {
        var firstMvc = mvc(); var challenge = request(firstMvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        String first = login(firstMvc, challenge, key, phone, client, code);
        String retry = login(mvc(), challenge, key, phone, client, code);
        assertEquals(first, retry); assertEquals(1, count("identity_account")); assertEquals(1, count("identity_session"));
        assertEquals(1, count("identity_session_recovery")); assertEquals(1, gateway.calls.get());
        assertEquals("Bearer", JSON.readTree(first).get("tokenType").asText());
        mvc().perform(verifyCommand(challenge, key, otherPhone, client, code)).andExpect(status().isConflict());
        mvc().perform(verifyCommand(challenge, key, phone, client, different(code))).andExpect(status().isConflict());
        mvc().perform(verifyCommand(challenge, key, phone, UUID.randomUUID().toString(), code)).andExpect(status().isUnauthorized());
        mvc().perform(verifyCommand(challenge, UUID.randomUUID(), phone, client, code)).andExpect(status().isUnauthorized());
        assertEquals(1, count("identity_session"));
    }

    @Test void concurrentIdenticalVerificationsReturnTheSameTokenWithoutAnotherSms() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<String>> commands = java.util.stream.IntStream.range(0, 6)
                    .mapToObj(i -> (Callable<String>) () -> login(mvc, challenge, key, phone, client, code)).toList();
            Set<String> responses = new HashSet<>();
            for (var future : executor.invokeAll(commands)) responses.add(future.get());
            assertEquals(1, responses.size());
        }
        assertEquals(1, gateway.calls.get()); assertEquals(1, count("identity_session"));
    }

    @Test void phoneMismatchDoesNotConsumeTheProofAndWrongCodeCannotCreateAnAccount() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        mvc.perform(verifyCommand(challenge, key, otherPhone, client, code)).andExpect(status().isUnauthorized());
        assertEquals("ACTIVE", jdbc.queryForObject("SELECT lifecycle FROM otp_challenge WHERE id=?", String.class, challenge));
        assertEquals(0, jdbc.queryForObject("SELECT wrong_attempts FROM otp_challenge WHERE id=?", Integer.class, challenge));
        for (int i = 0; i < 5; i++) {
            mvc.perform(verifyCommand(challenge, UUID.randomUUID(), phone, client, different(code)))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isUnauthorized());
        assertEquals("LOCKED", jdbc.queryForObject("SELECT lifecycle FROM otp_challenge WHERE id=?", String.class, challenge));
        assertEquals(0, count("identity_session")); assertEquals(0, count("identity_account"));
    }

    @Test void correctPhoneAfterInvalidInputCanVerifyAndNormalizedInputReplays() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        mvc.perform(verifyCommand(challenge, key, otherPhone, client, code)).andExpect(status().isUnauthorized());
        String first = login(mvc, challenge, key, phone, client, code);
        String retry = login(mvc(), challenge, key, "00237 " + phone.substring(4), client, code);
        assertEquals(first, retry);
        mvc.perform(verifyCommand(challenge, key, otherPhone, client, code)).andExpect(status().isConflict());
        assertEquals(1, count("identity_session"));
    }

    @Test void logoutChecksTargetAndContextAndReplaysOnlyItsOwnCommand() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID verification = UUID.randomUUID(); String code = gateway.code();
        var first = JSON.readTree(login(mvc, challenge, verification, phone, client, code));
        String id = first.get("sessionId").asText(), token = first.get("token").asText();
        UUID key = UUID.randomUUID();
        mvc.perform(logout(UUID.randomUUID().toString(), key, token, client)).andExpect(status().isUnauthorized());
        mvc.perform(logout(id, key, token, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        mvc.perform(logout(id, key, token, client)).andExpect(status().isNoContent());
        mvc().perform(logout(id, key, token, client)).andExpect(status().isNoContent());
        mvc.perform(logout(id, UUID.randomUUID(), token, client)).andExpect(status().isUnauthorized());
        mvc.perform(verifyCommand(challenge, verification, phone, client, code)).andExpect(status().isUnauthorized());
        String nextChallenge = request(mvc, UUID.randomUUID(), otherPhone).get("challengeId").asText();
        var second = JSON.readTree(login(mvc, nextChallenge, UUID.randomUUID(), otherPhone, client, gateway.code()));
        mvc.perform(logout(second.get("sessionId").asText(), key, second.get("token").asText(), client))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM identity_session WHERE gate_hash=? AND ended_at IS NULL", Integer.class, gate));
    }

    @Test void restoredMeReadUsesCurrentSessionAndNeverInheritsAnotherAccount() throws Exception {
        MockMvc first = mvc();
        String challenge = request(first, UUID.randomUUID(), phone).get("challengeId").asText();
        var login = JSON.readTree(login(first, challenge, UUID.randomUUID(), phone, client, gateway.code()));
        String token = login.get("token").asText();
        var restored = mvc();
        var profile = restored.perform(me(token, client)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.accountId").value(login.get("accountId").asText()))
                .andExpect(jsonPath("$.sessionId").value(login.get("sessionId").asText()))
                .andExpect(jsonPath("$.accountState").value("ACTIVE"))
                .andExpect(jsonPath("$.roles[0]").value("CLIENT"))
                .andExpect(jsonPath("$.roles.length()").value(1)).andReturn().getResponse().getContentAsString();
        assertFalse(profile.contains(token)); assertFalse(profile.contains(phone));
        restored.perform(me(token, UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        restored.perform(me(token, client).queryParam("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());

        String otherChallenge = request(first, UUID.randomUUID(), otherPhone).get("challengeId").asText();
        var other = JSON.readTree(login(first, otherChallenge, UUID.randomUUID(), otherPhone, client, gateway.code()));
        assertNotEquals(login.get("accountId").asText(), other.get("accountId").asText());
        restored.perform(me(other.get("token").asText(), client)).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(other.get("accountId").asText()));
        restored.perform(logout(login.get("sessionId").asText(), UUID.randomUUID(), token, client))
                .andExpect(status().isNoContent());
        mvc().perform(me(token, client)).andExpect(status().isUnauthorized());
        mvc().perform(me(other.get("token").asText(), client)).andExpect(status().isOk());
    }

    @Test void meReadRenewsOnlyIdleDeadlineAndRejectsDisabledOrExpiredSession() throws Exception {
        MockMvc mvc = mvc();
        String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        var login = JSON.readTree(login(mvc, challenge, UUID.randomUUID(), phone, client, gateway.code()));
        String token = login.get("token").asText();
        clock.advance(300);
        mvc.perform(me(token, client)).andExpect(status().isOk())
                .andExpect(jsonPath("$.idleExpiresAt").value("2026-10-17T12:15:00Z"))
                .andExpect(jsonPath("$.absoluteExpiresAt").value("2026-10-17T12:30:00Z"));
        jdbc.update("UPDATE identity_account SET status='DISABLED' WHERE id=? AND gate_hash=?",
                login.get("accountId").asText(), gate);
        mvc.perform(me(token, client)).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE identity_account SET status='ACTIVE' WHERE id=? AND gate_hash=?",
                login.get("accountId").asText(), gate);
        clock.advance(601);
        mvc.perform(me(token, client)).andExpect(status().isUnauthorized());
    }

    @Test void unknownTokensDoNotTakeTheSharedGateButExistingCandidatesAreStillRechecked() throws Exception {
        MockMvc mvc = mvc();
        String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        var login = JSON.readTree(login(mvc, challenge, UUID.randomUUID(), phone, client, gateway.code()));
        var before = jdbc.queryForObject("SELECT last_operation_at FROM identity_session_gate WHERE gate_hash=?",
                java.time.LocalDateTime.class, gate);
        clock.advance(30);
        char[] unknown = sessionCrypto.newToken();
        try {
            var error = assertThrows(SessionException.class, () -> sessions().authenticate(unknown, client));
            assertEquals(SessionException.Code.INVALID_SESSION, error.code());
        } finally { Arrays.fill(unknown, '\0'); }
        assertEquals(before, jdbc.queryForObject("SELECT last_operation_at FROM identity_session_gate WHERE gate_hash=?",
                java.time.LocalDateTime.class, gate));
        mvc.perform(me(login.get("token").asText(), client)).andExpect(status().isOk());
        assertTrue(jdbc.queryForObject("SELECT last_operation_at FROM identity_session_gate WHERE gate_hash=?",
                java.time.LocalDateTime.class, gate).isAfter(before));
    }

    @Test void concurrentMeReadsAndRevocationLeaveNoAccessAfterLogout() throws Exception {
        MockMvc mvc = mvc();
        String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        var login = JSON.readTree(login(mvc, challenge, UUID.randomUUID(), phone, client, gateway.code()));
        String token = login.get("token").asText();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Integer>> commands = new ArrayList<>();
            for (int i = 0; i < 5; i++) commands.add(() -> {
                start.await(); return mvc.perform(me(token, client)).andReturn().getResponse().getStatus();
            });
            commands.add(() -> {
                start.await(); return mvc.perform(logout(login.get("sessionId").asText(),
                        UUID.randomUUID(), token, client)).andReturn().getResponse().getStatus();
            });
            var futures = commands.stream().map(executor::submit).toList();
            start.countDown();
            for (int i = 0; i < 5; i++) assertTrue(Set.of(200, 401).contains(futures.get(i).get()));
            assertEquals(204, futures.get(5).get());
        }
        mvc().perform(me(token, client)).andExpect(status().isUnauthorized());
    }

    @Test void recoveryWindowAndCurrentAccountStatusAreEnforcedWithoutExtendingSession() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        var result = JSON.readTree(login(mvc, challenge, key, phone, client, code));
        clock.advance(91);
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isUnauthorized());
        char[] token = result.get("token").asText().toCharArray();
        try {
            assertEquals(Instant.parse(result.get("absoluteExpiresAt").asText()), sessions().authenticate(token, client).absoluteExpiresAt());
            jdbc.update("UPDATE identity_account SET status='DISABLED' WHERE gate_hash=?", gate);
            assertThrows(SessionException.class, () -> sessions().authenticate(token, client));
        } finally { Arrays.fill(token, '\0'); }
    }

    private PersistentSessionService sessions() {
        return new PersistentSessionService(jdbc, transactions, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                sessionCrypto, new SessionPolicy(Duration.ofMinutes(30), Duration.ofMinutes(10)), clock,
                new SessionRecoveryPolicy(Duration.ofSeconds(90)), recovery);
    }

    @Test void tamperedEncryptedResponseCannotIssueOrReplaceASession() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        login(mvc, challenge, key, phone, client, code);
        byte[] encrypted = jdbc.queryForObject("SELECT token_cipher FROM identity_session_recovery WHERE gate_hash=?", byte[].class, gate);
        byte[] tampered = encrypted.clone(); tampered[25] ^= 1;
        jdbc.update("UPDATE identity_session_recovery SET token_cipher=? WHERE gate_hash=?", tampered, gate);
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isServiceUnavailable());
        assertEquals(1, count("identity_session"));
        jdbc.update("UPDATE identity_session_recovery SET token_cipher=? WHERE gate_hash=?", encrypted, gate);
        login(mvc, challenge, key, phone, client, code);
    }

    @Test void disabledAccountErasesRecoveryAndCannotBeRevivedByTheOldResponse() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        login(mvc, challenge, key, phone, client, code);
        jdbc.update("UPDATE identity_account SET status='DISABLED' WHERE gate_hash=?", gate);
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isUnauthorized());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM identity_session_recovery WHERE gate_hash=? AND token_cipher IS NOT NULL", Integer.class, gate));
        jdbc.update("UPDATE identity_account SET status='ACTIVE' WHERE gate_hash=?", gate);
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isUnauthorized());
        assertEquals(1, count("identity_session"));
    }

    @Test void explicitMaintenanceErasesExpiredCipherButKeepsAntiReplayMarker() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        login(mvc, challenge, key, phone, client, code);
        clock.advance(90);
        assertEquals(1, sessions().eraseExpiredRecoverySecrets());
        assertEquals(0, sessions().eraseExpiredRecoverySecrets());
        assertEquals(1, count("identity_session_recovery"));
        mvc.perform(verifyCommand(challenge, key, phone, client, code)).andExpect(status().isUnauthorized());
        assertEquals(1, count("identity_session"));
    }

    @Test void failureAfterSessionInsertRollsBackAndTheSameVerifiedCodeCanResume() throws Exception {
        var mvc = mvc(); String challenge = request(mvc, UUID.randomUUID(), phone).get("challengeId").asText();
        UUID key = UUID.randomUUID(); String code = gateway.code();
        var otp = new PersistentOtpService(jdbc, transactions, otpCrypto, new OtpPolicy(100), gateway, clock);
        var proof = otp.verifyBound(UUID.fromString(challenge), client, key, phone, code.toCharArray());
        byte[] temporaryKey = new byte[32]; new SecureRandom().nextBytes(temporaryKey);
        var failing = new SessionRecoveryCrypto(temporaryKey, "test-failure", new SecureRandom() {
            @Override public void nextBytes(byte[] bytes) { throw new IllegalStateException("TECHNICAL_FAILURE"); }
        });
        Arrays.fill(temporaryKey, (byte) 0);
        var service = new PersistentSessionService(jdbc, transactions, new PersistedOtpProofVerifier(jdbc, otpCrypto, clock),
                sessionCrypto, new SessionPolicy(Duration.ofMinutes(30), Duration.ofMinutes(10)), clock,
                new SessionRecoveryPolicy(Duration.ofSeconds(90)), failing);
        var error = assertThrows(SessionException.class, () -> service.openOrReplay(proof, client, phone, key));
        assertEquals(SessionException.Code.SERVICE_UNAVAILABLE, error.code());
        assertEquals(0, count("identity_account")); assertEquals(0, count("identity_session")); assertEquals(0, count("identity_session_recovery"));
        login(mvc, challenge, key, phone, client, code);
        assertEquals(1, count("identity_session")); assertEquals(1, gateway.calls.get());
    }
    private MockMvc mvc() {
        var otp = new PersistentOtpService(jdbc, transactions, otpCrypto, new OtpPolicy(100), gateway, clock);
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("login", new LoginService(otp, sessions()));
        beans.registerSingleton("me", new MeService(sessions()));
        return MockMvcBuilders.standaloneSetup(new LoginController(beans.getBeanProvider(LoginService.class)),
                        new MeController(beans.getBeanProvider(MeService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestDiagnosticsFilter()).build();
    }
    private tools.jackson.databind.JsonNode request(MockMvc mvc, UUID key, String target) throws Exception {
        var result = mvc.perform(requestCommand(key, target)).andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var node = JSON.readTree(result.getResponse().getContentAsString());
        otpGates.add(jdbc.queryForObject("SELECT global_gate FROM otp_challenge WHERE id=?", String.class, node.get("challengeId").asText()));
        return node;
    }
    private MockHttpServletRequestBuilder requestCommand(UUID key, String target) {
        return post(ROUTE).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content("{\"phoneNumber\":\"" + target + "\",\"purpose\":\"login\",\"clientInstanceRef\":\"" + client + "\"}");
    }
    private MockHttpServletRequestBuilder verifyCommand(String challenge, UUID key, String target, String context, String code) {
        return post(ROUTE + "/" + challenge + "/verifications").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", key).content("{\"phoneNumber\":\"" + target + "\",\"clientInstanceRef\":\""
                        + context + "\",\"code\":\"" + code + "\"}");
    }
    private String login(MockMvc mvc, String challenge, UUID key, String target, String context, String code) throws Exception {
        return mvc.perform(verifyCommand(challenge, key, target, context, code)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
    }
    private MockHttpServletRequestBuilder logout(String id, UUID key, String token, String context) {
        return post("/api/v1/auth/sessions/" + id + "/revocations").header("Idempotency-Key", key)
                .header("X-Waka-Client-Instance", context).header("Authorization", "Bearer " + token);
    }
    private MockHttpServletRequestBuilder me(String token, String context) {
        return get("/api/v1/me").header("Authorization", "Bearer " + token)
                .header("X-Waka-Client-Instance", context);
    }
    private int count(String table) {
        if (!Set.of("identity_account", "identity_session", "identity_session_recovery").contains(table)) throw new IllegalArgumentException();
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE gate_hash=?", Integer.class, gate);
    }
    private static String different(String code) { return (code.charAt(0) == '0' ? "1" : "0") + code.substring(1); }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("LOCAL_TEST_CONFIGURATION_REQUIRED");
        return value;
    }
    private static final class Capture implements SmsGateway, AutoCloseable {
        private char[] lastCode;
        final AtomicInteger calls = new AtomicInteger();
        @Override public Submission send(SmsMessage message) {
            close(); lastCode = message.code(); calls.incrementAndGet(); return Submission.ACCEPTED;
        }
        String code() { return new String(lastCode); }
        @Override public void close() { if (lastCode != null) Arrays.fill(lastCode, '\0'); }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-17T12:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException(); return this; }
        @Override public Instant instant() { return time.get(); }
        void advance(long seconds) { time.updateAndGet(value -> value.plusSeconds(seconds)); }
    }
}
