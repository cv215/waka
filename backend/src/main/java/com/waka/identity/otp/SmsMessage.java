package com.waka.identity.otp;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** One ephemeral provider call; the receiver must also erase its accessor copies. */
public final class SmsMessage implements AutoCloseable {
    private final UUID clientReference;
    private final char[] destination;
    private final char[] code;
    private boolean closed;

    public SmsMessage(UUID clientReference, char[] destination, char[] code) {
        this.clientReference = Objects.requireNonNull(clientReference);
        this.destination = Objects.requireNonNull(destination).clone();
        this.code = Objects.requireNonNull(code).clone();
    }

    public UUID clientReference() { return clientReference; }
    public synchronized char[] destination() { checkOpen(); return destination.clone(); }
    public synchronized char[] code() { checkOpen(); return code.clone(); }
    private void checkOpen() { if (closed) throw new IllegalStateException("MESSAGE_CLOSED"); }
    @Override public synchronized void close() {
        Arrays.fill(destination, '\0'); Arrays.fill(code, '\0'); closed = true;
    }
    @Override public String toString() { return "SmsMessage[REDACTED]"; }
}
