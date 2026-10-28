package com.waka.identity.sms.orange;

import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsGateway.Submission;
import com.waka.identity.otp.SmsMessage;
import com.waka.identity.sms.orange.PersonalSmsCheck.State;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Fake transport only: these tests cannot contact Orange or send a real SMS. */
class PersonalSmsCheckTest {
    @TempDir Path directory;
    private final MutableClock clock = new MutableClock();
    private long tick;

    private PersonalSmsCheck check(SmsGateway gateway, Path gate) {
        return check(gateway, new PersonalSmsCheck.FileGate(gate));
    }

    private PersonalSmsCheck check(SmsGateway gateway, PersonalSmsCheck.Gate gate) {
        return new PersonalSmsCheck(gateway, "+237600000000".toCharArray(), clock,
                () -> tick, gate, new SecureRandom());
    }

    @Test void noProviderIoBeforeExplicitConsentAndNoSecondSend() {
        FakeGateway fake = new FakeGateway();
        Path gate = directory.resolve("personal-check.lock");
        try (PersonalSmsCheck check = check(fake, gate)) {
            assertEquals(0, fake.calls.get());
            assertEquals(State.REJECTED, check.send(false));
            assertEquals(0, fake.calls.get());
            assertFalse(Files.exists(gate));
            assertEquals(State.ACCEPTED, check.send(true));
            assertEquals(1, fake.calls.get());
            assertEquals(State.ALREADY_RESERVED, check.send(true));
            assertEquals(1, fake.calls.get());
        }
        assertEquals("v1 RESERVED\n", assertDoesNotThrow(() -> Files.readString(gate)));
    }

    @Test void concurrentAndRestartedInstancesCannotReplayReservation() throws Exception {
        Path gate = directory.resolve("nested").resolve("personal-check.lock");
        FakeGateway fake = new FakeGateway();
        try (var first = check(fake, gate); var second = check(fake, gate);
             var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Callable<State> one = () -> first.send(true);
            Callable<State> two = () -> second.send(true);
            var a = executor.submit(one);
            var b = executor.submit(two);
            assertEquals(1, java.util.List.of(a.get(), b.get()).stream().filter(State.ACCEPTED::equals).count());
            assertEquals(1, fake.calls.get());
        }
        try (var restarted = check(fake, gate)) {
            assertEquals(State.ALREADY_RESERVED, restarted.send(true));
        }
        assertEquals(1, fake.calls.get());
    }

    @Test void uncertainOrThrowingProviderStillConsumesTheOneReservation() {
        Path unknownGate = directory.resolve("unknown.lock");
        FakeGateway uncertain = new FakeGateway();
        uncertain.outcome = Submission.UNKNOWN;
        try (var first = check(uncertain, unknownGate)) {
            assertEquals(State.UNKNOWN, first.send(true));
            assertEquals(State.ALREADY_RESERVED, first.send(true));
            assertEquals(State.VERIFIED, first.verify(uncertain.code.clone()));
            assertEquals(State.CONSUMED, first.verify(uncertain.code.clone()));
        }
        try (var restarted = check(uncertain, unknownGate)) {
            assertEquals(State.ALREADY_RESERVED, restarted.send(true));
        }
        assertEquals(1, uncertain.calls.get());

        Path crashGate = directory.resolve("crash.lock");
        FakeGateway crash = new FakeGateway();
        crash.fail = true;
        try (var first = check(crash, crashGate)) {
            assertEquals(State.UNKNOWN, first.send(true));
        }
        try (var restarted = check(crash, crashGate)) {
            assertEquals(State.ALREADY_RESERVED, restarted.send(true));
        }
        assertEquals(1, crash.calls.get());
    }

    @Test void wrongAndMalformedCodesUseFiveAttemptsAndDoNotLeakCode() {
        FakeGateway fake = new FakeGateway();
        try (var check = check(fake, directory.resolve("attempts.lock"))) {
            assertEquals(State.ACCEPTED, check.send(true));
            String description = check.toString();
            assertFalse(description.contains(new String(fake.code)));
            assertEquals(State.WRONG_CODE, check.verify(null));
            assertEquals(State.WRONG_CODE, check.verify("x".toCharArray()));
            assertEquals(State.WRONG_CODE, check.verify("abcdef".toCharArray()));
            assertEquals(State.WRONG_CODE, check.verify("12345x".toCharArray()));
            assertEquals(State.LOCKED, check.verify("1234567".toCharArray()));
            assertEquals(State.LOCKED, check.verify(fake.code.clone()));
        }
    }

    @Test void expiryAndOneUseAndProviderRejection() {
        FakeGateway fake = new FakeGateway();
        try (var check = check(fake, directory.resolve("expiry.lock"))) {
            assertEquals(State.ACCEPTED, check.send(true));
            tick = java.time.Duration.ofMinutes(5).toNanos();
            assertEquals(State.EXPIRED, check.verify(fake.code.clone()));
        }
        tick = 0;
        FakeGateway accepted = new FakeGateway();
        try (var check = check(accepted, directory.resolve("success.lock"))) {
            assertEquals(State.ACCEPTED, check.send(true));
            assertEquals(State.VERIFIED, check.verify(accepted.code.clone()));
            assertEquals(State.CONSUMED, check.verify(accepted.code.clone()));
        }
        FakeGateway rejected = new FakeGateway();
        rejected.outcome = Submission.REJECTED;
        try (var check = check(rejected, directory.resolve("rejected.lock"))) {
            assertEquals(State.REJECTED, check.send(true));
            assertEquals(State.ALREADY_RESERVED, check.send(true));
            assertEquals(State.REJECTED, check.verify(rejected.code.clone()));
        }
    }

    @Test void privateProtocolHasNoNetworkBeforeSendAndRejectsMalformedJson() {
        String init = "{\"clientId\":\"local-test-id\",\"clientSecret\":\"local-only-secret\","
                + "\"phoneNumber\":\"+237600000000\"}\n";
        String output = run(init + "{\"action\":\"exit\"}\n");
        assertEquals("{\"state\":\"READY\"}\n{\"state\":\"CLOSED\"}\n", output);
        assertFalse(output.contains("local-only-secret"));
        assertFalse(output.contains("+237"));
        assertEquals("{\"state\":\"INVALID_INPUT\"}\n",
                run("{\"clientId\":\"a\",\"clientId\":\"b\"}\n"));
        assertEquals("{\"state\":\"READY\"}\n{\"state\":\"INVALID_INPUT\"}\n",
                run(init + "{\"action\":\"exit\",\"extra\":true}\n"));
        assertEquals("{\"state\":\"READY\"}\n{\"state\":\"INVALID_INPUT\"}\n",
                run(init + "{\"action\":\"exit\"}{}\n"));
    }

    @Test void authorizedSecondGateRequiresOriginalAndNeverChangesIt() throws Exception {
        FakeGateway fake = new FakeGateway();
        PersonalSmsCheck.Gate second = PersonalSmsCheckMain.retryGate(directory);
        Path original = directory.resolve("personal-check.lock");
        Path retry = directory.resolve("personal-check-retry-02.lock");
        try (var absent = check(fake, second)) {
            assertEquals(State.ERROR, absent.send(true));
            assertEquals(0, fake.calls.get());
            assertFalse(Files.exists(retry));
        }
        byte[] originalBytes = "v1 RESERVED\n".getBytes(StandardCharsets.US_ASCII);
        Files.write(original, originalBytes);
        try (var first = check(fake, second)) {
            assertEquals(State.ACCEPTED, first.send(true));
            assertEquals(State.ALREADY_RESERVED, first.send(true));
        }
        assertEquals(1, fake.calls.get());
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
        assertEquals("v1 RESERVED\n", Files.readString(retry));
        try (var restarted = check(fake, PersonalSmsCheckMain.retryGate(directory))) {
            assertEquals(State.ALREADY_RESERVED, restarted.send(true));
        }
        assertEquals(1, fake.calls.get());
        assertArrayEquals(originalBytes, Files.readAllBytes(original));
    }

    @Test void retryRejectsNonFileOriginalAndInvalidArgumentsBeforeInitialization() throws Exception {
        FakeGateway fake = new FakeGateway();
        Path original = directory.resolve("personal-check.lock");
        Files.createDirectory(original);
        try (var check = check(fake, PersonalSmsCheckMain.retryGate(directory))) {
            assertEquals(State.ERROR, check.send(true));
        }
        assertEquals(0, fake.calls.get());
        assertFalse(Files.exists(directory.resolve("personal-check-retry-02.lock")));

        String sendInput = "{\"clientId\":\"local-test-id\",\"clientSecret\":\"local-only-secret\","
                + "\"phoneNumber\":\"+237600000000\"}\n{\"action\":\"send\",\"consent\":true}\n";
        for (String[] args : new String[][] {
                {"--authorized-retry-03"}, {"--authorized-retry-02", "extra"}, {"--gate", "x"}}) {
            assertEquals("{\"state\":\"INVALID_INPUT\"}\n", run(sendInput, args));
        }
        assertEquals(0, fake.calls.get());
    }

    @Test void thirdRecipientGateRequiresBothEarlierFilesAndDoesNotChangeThem() throws Exception {
        FakeGateway fake = new FakeGateway();
        Path firstGate = directory.resolve("personal-check.lock");
        Path secondGate = directory.resolve("personal-check-retry-02.lock");
        Path thirdGate = directory.resolve("personal-check-recipient-03.lock");
        PersonalSmsCheck.Gate third = PersonalSmsCheckMain.recipient03Gate(directory);
        try (var missingBoth = check(fake, third)) {
            assertEquals(State.ERROR, missingBoth.send(true));
        }
        byte[] firstBytes = "v1 RESERVED\n".getBytes(StandardCharsets.US_ASCII);
        byte[] secondBytes = "v1 RESERVED\n".getBytes(StandardCharsets.US_ASCII);
        Files.write(secondGate, secondBytes);
        try (var missingFirst = check(fake, third)) {
            assertEquals(State.ERROR, missingFirst.send(true));
        }
        Files.delete(secondGate);
        Files.write(firstGate, firstBytes);
        try (var missingSecond = check(fake, third)) {
            assertEquals(State.ERROR, missingSecond.send(true));
        }
        assertEquals(0, fake.calls.get());
        assertFalse(Files.exists(thirdGate));
        Files.write(secondGate, secondBytes);
        try (var allowed = check(fake, third)) {
            assertEquals(State.ACCEPTED, allowed.send(true));
            assertEquals(State.ALREADY_RESERVED, allowed.send(true));
        }
        assertEquals(1, fake.calls.get());
        assertEquals("v1 RESERVED\n", Files.readString(thirdGate));
        try (var restarted = check(fake, PersonalSmsCheckMain.recipient03Gate(directory))) {
            assertEquals(State.ALREADY_RESERVED, restarted.send(true));
        }
        assertEquals(1, fake.calls.get());
        assertArrayEquals(firstBytes, Files.readAllBytes(firstGate));
        assertArrayEquals(secondBytes, Files.readAllBytes(secondGate));
    }

    @Test void thirdRecipientArgumentIsExactAndFourthIsRejectedBeforeInitialization() {
        String initAndExit = "{\"clientId\":\"local-test-id\",\"clientSecret\":\"local-only-secret\","
                + "\"phoneNumber\":\"+237699999999\"}\n{\"action\":\"exit\"}\n";
        assertEquals("{\"state\":\"READY\"}\n{\"state\":\"CLOSED\"}\n",
                run(initAndExit, new String[] {"--authorized-recipient-03"}));
        String onlyInvalid = "{\"state\":\"INVALID_INPUT\"}\n";
        assertEquals(onlyInvalid, run(initAndExit, new String[] {"--authorized-recipient-04"}));
        assertEquals(onlyInvalid, run(initAndExit, new String[] {"--authorized-recipient-03", "extra"}));
    }

    private static String run(String input) {
        return run(input, new String[0]);
    }

    private static String run(String input, String[] args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PersonalSmsCheckMain.run(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, args);
        return output.toString(StandardCharsets.US_ASCII);
    }

    private static final class FakeGateway implements SmsGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile char[] code;
        private volatile Submission outcome = Submission.ACCEPTED;
        private volatile boolean fail;
        @Override public Submission send(SmsMessage message) throws Exception {
            calls.incrementAndGet();
            code = message.code();
            if (fail) { throw new java.io.IOException("fake provider failure"); }
            return outcome;
        }
        @Override public String toString() { return "FakeGateway[REDACTED]"; }
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-10-16T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
