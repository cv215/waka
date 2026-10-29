package com.waka.drivers;

import com.waka.identity.admission.PersistentDriverApplicationService.AccessBasis;
import com.waka.identity.admission.PersistentDriverApplicationService.RequirementStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** R02/R06 access rule on synthetic admission states; persistence is covered by the opt-in MySQL test. */
class DriverAccessServiceTest {
    private final Instant now = Instant.parse("2026-10-29T10:00:00Z");
    private final UUID id = UUID.randomUUID();

    private static RequirementStatus req(String code, String status, boolean expired, boolean satisfied) {
        return new RequirementStatus(code, "Document", "Une phrase.", status, "established".equals(status),
                UUID.randomUUID(), satisfied ? "accepted" : "received", LocalDate.of(2027, 1, 1), expired, satisfied);
    }
    private AccessBasis basis(String category, String state, RequirementStatus... requirements) {
        return new AccessBasis(id, category, state, List.of(requirements));
    }

    @Test void noFileOrNoApprovalGivesNoAccess() {
        assertEquals("none", DriverAccessService.evaluate(null, now).access());
        assertEquals(List.of("NO_APPLICATION"), DriverAccessService.evaluate(null, now).reasons());
        for (String state : List.of("draft", "submitted", "correction_requested", "refused")) {
            var view = DriverAccessService.evaluate(basis("taxi", state, req("IDENTITY", "established", false, true)), now);
            assertEquals("none", view.access(), state);
            assertEquals(List.of("NOT_APPROVED"), view.reasons());
        }
    }

    @Test void approvedTaxiIsActiveWithoutSubscriptionAndPrivateIsProvisional() {
        var taxi = DriverAccessService.evaluate(basis("taxi", "approved", req("IDENTITY", "established", false, true)), now);
        assertEquals("active", taxi.access());
        assertEquals("taxi_no_subscription", taxi.basis());
        assertNull(taxi.trial(), "no trial or subscription for a taxi");
        var personal = DriverAccessService.evaluate(basis("private", "approved", req("IDENTITY", "established", false, true)), now);
        assertEquals("provisional", personal.access());
        assertEquals("provisional_before_first_service", personal.basis());
        assertEquals("not_started", personal.trial(), "the trial starts only with a first completed paid service");
    }

    @Test void anExpiredOrInvalidEssentialDocumentBlocksNewReservations() {
        var expired = DriverAccessService.evaluate(basis("taxi", "approved", req("IDENTITY", "established", false, true),
                req("INSURANCE", "established", true, false)), now);
        assertEquals("new_reservations_blocked", expired.access());
        assertEquals(List.of("DOCUMENT_EXPIRED"), expired.reasons());
        assertEquals(List.of("INSURANCE"), expired.documentsToRenew());
        assertNull(expired.basis());
        var unqualified = DriverAccessService.evaluate(basis("private", "approved", req("IDENTITY", "established", false, true),
                req("PAID_TRANSPORT_AUTHORIZATION", "to_confirm", false, false)), now);
        assertEquals(List.of("REQUIREMENTS_NOT_QUALIFIED"), unqualified.reasons());
        var toCorrect = DriverAccessService.evaluate(basis("private", "approved", req("DRIVING_LICENCE", "established", false, false)), now);
        assertEquals(List.of("DOCUMENT_NOT_VALID"), toCorrect.reasons());
        assertEquals(List.of("DRIVING_LICENCE"), toCorrect.documentsToRenew());
    }
}
