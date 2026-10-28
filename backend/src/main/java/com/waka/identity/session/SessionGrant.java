package com.waka.identity.session;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Ephemeral secret returned once; accessor copies also belong to the caller to erase. */
public final class SessionGrant implements AutoCloseable {
    private final UUID sessionId;
    private final UUID accountId;
    private final Instant absoluteExpiresAt;
    private final Instant idleExpiresAt;
    private final char[] token;
    private boolean closed;

    SessionGrant(UUID sessionId, UUID accountId, Instant absoluteExpiresAt, Instant idleExpiresAt, char[] token) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.accountId = Objects.requireNonNull(accountId);
        this.absoluteExpiresAt = Objects.requireNonNull(absoluteExpiresAt);
        this.idleExpiresAt = Objects.requireNonNull(idleExpiresAt);
        this.token = Objects.requireNonNull(token).clone();
    }
    public UUID sessionId() { return sessionId; }
    public UUID accountId() { return accountId; }
    public Instant absoluteExpiresAt() { return absoluteExpiresAt; }
    public Instant idleExpiresAt() { return idleExpiresAt; }
    public synchronized char[] token() {
        if (closed) throw new IllegalStateException("SESSION_GRANT_CLOSED");
        return token.clone();
    }
    @Override public synchronized void close() { Arrays.fill(token, '\0'); closed = true; }
    @Override public String toString() { return "SessionGrant[REDACTED]"; }
}

