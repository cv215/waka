package com.waka.drivers;

import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.session.PersistentSessionService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A32, read only: the driver's effective access, computed from admission and documents (R02, R06, K06).
 * A taxi needs no subscription. An approved private driver has provisional access until a first
 * completed paid service, then the seven-day trial, then paid periods; without one, a subscription is
 * required (R06, R12).
 * An expired essential document blocks new reservations. Not assembled by default.
 */
public final class DriverAccessService {
    public enum Access { NONE, PROVISIONAL, ACTIVE, NEW_RESERVATIONS_BLOCKED, SUBSCRIPTION_REQUIRED }
    public record AccessView(String access, String basis, UUID applicationId, String category, String admissionState,
            List<String> reasons, List<String> documentsToRenew, String trial, Instant serverTime) { }

    private final PersistentSessionService sessions;
    private final PersistentDriverApplicationService applications;

    public DriverAccessService(PersistentSessionService sessions, PersistentDriverApplicationService applications) {
        this.sessions = Objects.requireNonNull(sessions);
        this.applications = Objects.requireNonNull(applications);
    }

    public AccessView access(char[] token, String client) {
        return sessions.withAuthenticatedSession(token, client, session -> evaluate(
                applications.accessBasisLocked(session.accountId(), session.serverTime()), session.serverTime()));
    }

    public static AccessView evaluate(PersistentDriverApplicationService.AccessBasis basis, Instant now) {
        if (basis == null) return view(Access.NONE, null, null, List.of("NO_APPLICATION"), List.of(), now);
        if (!"approved".equals(basis.state())) return view(Access.NONE, null, basis, List.of("NOT_APPROVED"), List.of(), now);
        List<String> reasons = new ArrayList<>(), renew = new ArrayList<>();
        for (var requirement : basis.requirements()) {
            // A document waived at approval (catalogue v2) does not block; it stays "to provide" for the driver.
            if (basis.waivedCodes().contains(requirement.code())) continue;
            if (!"established".equals(requirement.status())) {
                if (!reasons.contains("REQUIREMENTS_NOT_QUALIFIED")) reasons.add("REQUIREMENTS_NOT_QUALIFIED");
            } else if (requirement.expired()) {
                if (!reasons.contains("DOCUMENT_EXPIRED")) reasons.add("DOCUMENT_EXPIRED");
                renew.add(requirement.code());
            } else if (!requirement.satisfied()) {
                if (!reasons.contains("DOCUMENT_NOT_VALID")) reasons.add("DOCUMENT_NOT_VALID");
                renew.add(requirement.code());
            }
        }
        if (!reasons.isEmpty()) return view(Access.NEW_RESERVATIONS_BLOCKED, null, basis, reasons, renew, now);
        if ("taxi".equals(basis.category())) return view(Access.ACTIVE, "taxi_no_subscription", basis, List.of(), List.of(), now);
        // R06: provisional until the first completed paid service, then the trial, then paid periods.
        if (basis.trialEndsAt() == null) return view(Access.PROVISIONAL, "provisional_before_first_service", basis, List.of(), List.of(), now);
        if (now.isBefore(basis.trialEndsAt())) return view(Access.ACTIVE, "trial", basis, List.of(), List.of(), now);
        if (basis.paidUntil() != null && now.isBefore(basis.paidUntil())) return view(Access.ACTIVE, "paid_period", basis, List.of(), List.of(), now);
        return view(Access.SUBSCRIPTION_REQUIRED, null, basis, List.of("SUBSCRIPTION_REQUIRED"), List.of(), now);
    }

    private static AccessView view(Access access, String basisCode, PersistentDriverApplicationService.AccessBasis basis,
            List<String> reasons, List<String> renew, Instant now) {
        boolean privateDriver = basis != null && "private".equals(basis.category());
        return new AccessView(access.name().toLowerCase(), basisCode, basis == null ? null : basis.applicationId(),
                basis == null ? null : basis.category(), basis == null ? null : basis.state(), List.copyOf(reasons),
                List.copyOf(renew), !privateDriver ? null : basis.trialEndsAt() == null ? "not_started"
                        : now.isBefore(basis.trialEndsAt()) ? "running" : "ended", now);
    }

    @Override public String toString() { return "DriverAccessService[REDACTED]"; }
}
