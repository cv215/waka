package com.waka.identity.sms.orange;

import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One personal, local, explicitly confirmed SMS check. Never a Spring bean or HTTP endpoint. */
public final class PersonalSmsCheck implements AutoCloseable {
    public enum State {
        READY, ACCEPTED, UNKNOWN, REJECTED, ALREADY_RESERVED, VERIFIED, WRONG_CODE,
        LOCKED, EXPIRED, CONSUMED, INVALID_INPUT, ERROR, CLOSED
    }

    @FunctionalInterface interface Gate { boolean reserve() throws IOException; }

    /** A durable, process-independent one-send gate; its content carries no personal data. */
    static final class FileGate implements Gate {
        private static final byte[] RECORD = "v1 RESERVED\n".getBytes(StandardCharsets.US_ASCII);
        private final Path path;

        FileGate(Path path) { this.path = Objects.requireNonNull(path).toAbsolutePath().normalize(); }

        @Override public boolean reserve() throws IOException {
            Path parent = path.getParent();
            if (parent == null) { throw new IOException("INVALID_GATE"); }
            rejectLinks(parent);
            Files.createDirectories(parent);
            rejectLinks(parent);
            if (Files.isSymbolicLink(path)) { throw new IOException("INVALID_GATE"); }
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer record = ByteBuffer.wrap(RECORD);
                while (record.hasRemaining()) { channel.write(record); }
                channel.force(true);
                return true;
            } catch (java.nio.file.FileAlreadyExistsException alreadyReserved) {
                return false;
            }
        }

        private static void rejectLinks(Path path) throws IOException {
            Path current = path.getRoot();
            for (Path segment : path) {
                current = current.resolve(segment);
                if (Files.isSymbolicLink(current)) { throw new IOException("INVALID_GATE"); }
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("INVALID_GATE");
                }
            }
        }

        @Override public String toString() { return "FileGate[REDACTED]"; }
    }

    private static final long VALID_NANOS = Duration.ofMinutes(5).toNanos();
    private final SmsGateway gateway;
    private final Clock clock;
    private final LongSupplier monotonic;
    private final Gate gate;
    private final SecureRandom random;
    private char[] phone;
    private byte[] key;
    private byte[] digest;
    private Instant issuedAt;
    private long issuedNanos;
    private int failedAttempts;
    private boolean reserved;
    private boolean closed;
    private State terminal;

    // Injection is package-private, so only local tests can replace the production transport/gate.
    PersonalSmsCheck(SmsGateway gateway, char[] phone, Clock clock, LongSupplier monotonic,
                     Gate gate, SecureRandom random) {
        this.gateway = Objects.requireNonNull(gateway);
        this.clock = Objects.requireNonNull(clock);
        this.monotonic = Objects.requireNonNull(monotonic);
        this.gate = Objects.requireNonNull(gate);
        this.random = Objects.requireNonNull(random);
        if (phone == null || !validPhone(phone)) { throw new IllegalArgumentException("INVALID_INPUT"); }
        this.phone = phone.clone();
    }

    private static boolean validPhone(char[] phone) {
        if (phone.length != 13 || phone[0] != '+' || phone[1] != '2' || phone[2] != '3'
                || phone[3] != '7' || phone[4] != '6') { return false; }
        for (int i = 5; i < phone.length; i++) {
            if (phone[i] < '0' || phone[i] > '9') { return false; }
        }
        return true;
    }

    public synchronized State send(boolean consent) {
        if (closed) { return State.CLOSED; }
        if (reserved) { return State.ALREADY_RESERVED; }
        if (!consent) { return State.REJECTED; }
        try {
            if (!gate.reserve()) { reserved = true; return State.ALREADY_RESERVED; }
            reserved = true; // Never retry after a failed or uncertain transport call.
        } catch (IOException | RuntimeException failure) {
            return State.ERROR;
        }

        char[] code = new char[6];
        byte[] freshKey = new byte[32];
        try {
            for (int i = 0; i < code.length; i++) { code[i] = (char) ('0' + random.nextInt(10)); }
            random.nextBytes(freshKey);
            byte[] freshDigest = hmac(freshKey, code);
            issuedAt = clock.instant();
            issuedNanos = monotonic.getAsLong();
            key = freshKey.clone();
            digest = freshDigest;
            try (SmsMessage message = new SmsMessage(UUID.randomUUID(), phone, code)) {
                SmsGateway.Submission submission = gateway.send(message);
                if (submission == SmsGateway.Submission.ACCEPTED) { return State.ACCEPTED; }
                if (submission == SmsGateway.Submission.REJECTED) {
                    eraseVerification();
                    terminal = State.REJECTED;
                    return State.REJECTED;
                }
                return State.UNKNOWN;
            }
        } catch (Exception failure) {
            return State.UNKNOWN;
        } finally {
            Arrays.fill(code, '\0');
            Arrays.fill(freshKey, (byte) 0);
            erasePhone();
        }
    }

    public synchronized State verify(char[] candidate) {
        if (closed) { return State.CLOSED; }
        if (terminal == State.VERIFIED) { return State.CONSUMED; }
        if (terminal != null) { return terminal; }
        if (digest == null || key == null || issuedAt == null) { return State.INVALID_INPUT; }
        if (clock.instant().isBefore(issuedAt) || !clock.instant().isBefore(issuedAt.plusSeconds(300))
                || Long.compareUnsigned(monotonic.getAsLong() - issuedNanos, VALID_NANOS) >= 0) {
            terminal = State.EXPIRED;
            eraseVerification();
            return State.EXPIRED;
        }
        boolean valid = candidate != null && candidate.length == 6;
        if (valid) {
            for (char c : candidate) { valid &= c >= '0' && c <= '9'; }
        }
        byte[] supplied = null;
        try {
            if (valid) {
                supplied = hmac(key, candidate);
                if (MessageDigest.isEqual(digest, supplied)) {
                    terminal = State.VERIFIED;
                    eraseVerification();
                    return State.VERIFIED;
                }
            }
            failedAttempts++;
            if (failedAttempts >= 5) {
                terminal = State.LOCKED;
                eraseVerification();
                return State.LOCKED;
            }
            return State.WRONG_CODE;
        } catch (GeneralSecurityException failure) {
            terminal = State.ERROR;
            eraseVerification();
            return State.ERROR;
        } finally {
            if (supplied != null) { Arrays.fill(supplied, (byte) 0); }
        }
    }

    private static byte[] hmac(byte[] key, char[] digits) throws GeneralSecurityException {
        byte[] input = new byte[digits.length];
        try {
            for (int i = 0; i < digits.length; i++) { input[i] = (byte) digits[i]; }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input);
        } finally { Arrays.fill(input, (byte) 0); }
    }

    private void erasePhone() {
        if (phone != null) { Arrays.fill(phone, '\0'); phone = null; }
    }

    private void eraseVerification() {
        if (key != null) { Arrays.fill(key, (byte) 0); key = null; }
        if (digest != null) { Arrays.fill(digest, (byte) 0); digest = null; }
        issuedAt = null;
        issuedNanos = 0;
    }

    @Override public synchronized void close() {
        if (closed) { return; }
        closed = true;
        erasePhone();
        eraseVerification();
        if (gateway instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { /* No data in protocol output. */ }
        }
    }

    @Override public String toString() { return "PersonalSmsCheck[REDACTED]"; }
}
