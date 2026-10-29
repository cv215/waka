package com.waka.identity.admission;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The two routes of catalogue v1: only the documents of the declared situation, nothing by precaution. */
class DocumentCatalogTest {
    private static List<String> codes(String category, String usageRight) {
        return DocumentCatalog.applicable(category, usageRight).stream().map(DocumentCatalog.Requirement::code).toList();
    }

    @Test void taxiAsksVehicleTitlesNeverPersonalTaximanProof() {
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION",
                "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD"), codes("taxi", "holder"));
        assertTrue(codes("taxi", "authorized_use").contains("USE_AUTHORIZATION"));
        assertFalse(codes("taxi", "holder").contains("PAID_TRANSPORT_AUTHORIZATION"));
        // The operator's titles are vehicle documents, not titles the driver must personally hold.
        for (String code : List.of("TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD")) {
            assertEquals(DocumentCatalog.Scope.VEHICLE, DocumentCatalog.byCode(code).scope());
        }
    }

    @Test void personalCarNeverAsksTaxiTitlesAndKeepsItsOwnAuthorizationToConfirm() {
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION",
                "PAID_TRANSPORT_AUTHORIZATION"), codes("private", "holder"));
        assertFalse(codes("private", "authorized_use").contains("TRANSPORT_LICENCE"));
        assertFalse(codes("private", "authorized_use").contains("PUBLIC_TRANSPORT_CARD"));
        assertEquals(DocumentCatalog.Status.TO_CONFIRM, DocumentCatalog.byCode("PAID_TRANSPORT_AUTHORIZATION").status());
    }

    @Test void useAuthorizationOnlyWhenTheVehicleBelongsToSomeoneElse() {
        for (String category : List.of("taxi", "private")) {
            assertFalse(codes(category, "holder").contains("USE_AUTHORIZATION"));
            assertFalse(codes(category, null).contains("USE_AUTHORIZATION"), "unknown right asks nothing extra yet");
            assertTrue(codes(category, "authorized_use").contains("USE_AUTHORIZATION"));
        }
    }

    @Test void uncertainRequirementsStayMarkedAndPersonDocumentsAreShared() {
        assertEquals(Set.of("TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD", "PAID_TRANSPORT_AUTHORIZATION"),
                DocumentCatalog.V1.stream().filter(r -> r.status() == DocumentCatalog.Status.TO_CONFIRM)
                        .map(DocumentCatalog.Requirement::code).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("IDENTITY", "DRIVING_LICENCE"), DocumentCatalog.V1.stream()
                .filter(r -> r.scope() == DocumentCatalog.Scope.PERSON).map(DocumentCatalog.Requirement::code)
                .collect(java.util.stream.Collectors.toSet()));
        for (var requirement : DocumentCatalog.V1) {
            assertFalse(requirement.document().isBlank());
            assertTrue(requirement.explanation().endsWith("."), "one simple sentence: " + requirement.code());
        }
        assertEquals(DocumentCatalog.V1.size(), DocumentCatalog.V1.stream().map(DocumentCatalog.Requirement::code).distinct().count());
    }
}
