package com.waka.identity.application;

import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minimal account view derived from one authenticated session transaction. */
public final class MeService {
    private final PersistentSessionService sessions;

    public MeService(PersistentSessionService sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    public MeView read(char[] token, String client) {
        var session = sessions.authenticate(token, client);
        // CLIENT is the base right of an ACTIVE account, not a D02 grant or driver admission.
        return new MeView(session.accountId(), session.sessionId(), session.absoluteExpiresAt(),
                session.idleExpiresAt(), "ACTIVE", List.of("CLIENT"), session.serverTime());
    }

    @Override public String toString() { return "MeService[REDACTED]"; }

    public record MeView(UUID accountId, UUID sessionId, Instant absoluteExpiresAt,
            Instant idleExpiresAt, String accountState, List<String> roles, Instant serverTime) { }
}
