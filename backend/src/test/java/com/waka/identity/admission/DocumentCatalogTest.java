package com.waka.identity.admission;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Catalogue v2 in force (founder decision of 29 October 2026); v1 is kept unchanged as history. */
class DocumentCatalogTest {
    private static List<String> codes(String category, String usageRight) {
        return DocumentCatalog.applicable(category, usageRight).stream().map(DocumentCatalog.Requirement::code).toList();
    }

    @Test void taxiAsksItsVehicleTitlesNeverPersonalTaximanProof() {
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "TECHNICAL_INSPECTION",
                "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD"), codes("taxi", "holder"));
        assertTrue(codes("taxi", "authorized_use").contains("USE_AUTHORIZATION"));
        // The operator's titles are vehicle documents, not titles the driver must personally hold.
        for (String code : List.of("TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD", "TECHNICAL_INSPECTION")) {
            assertEquals(DocumentCatalog.Scope.VEHICLE, DocumentCatalog.byCode(code).scope());
        }
        assertTrue(DocumentCatalog.byCode("TRANSPORT_LICENCE").document().contains("première catégorie"));
    }

    @Test void personalCarAsksNoTaxiTitleNoInspectionAndNoTransportAuthorization() {
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE"), codes("private", "holder"));
        assertEquals(List.of("IDENTITY", "DRIVING_LICENCE", "REGISTRATION_CERTIFICATE", "INSURANCE", "USE_AUTHORIZATION"),
                codes("private", "authorized_use"));
        assertTrue(DocumentCatalog.CURRENT.stream().noneMatch(r -> r.code().equals("PAID_TRANSPORT_AUTHORIZATION")),
                "the authorisation is WAKA's own obligation as a platform, not a driver document");
    }

    @Test void insuranceWordingDependsOnTheRoute() {
        var insurance = DocumentCatalog.byCode("INSURANCE");
        assertTrue(insurance.documentFor("taxi").contains("transport de passagers"));
        assertFalse(insurance.documentFor("private").contains("transport de passagers"), "ordinary private insurance accepted");
        assertTrue(insurance.explanationFor("private").endsWith("."));
    }

    @Test void everythingIsEstablishedAndOnlyVehicleDocumentsMayBeWaived() {
        assertTrue(DocumentCatalog.CURRENT.stream().allMatch(r -> r.status() == DocumentCatalog.Status.ESTABLISHED));
        assertEquals(Set.of("IDENTITY", "DRIVING_LICENCE"), DocumentCatalog.CURRENT.stream()
                .filter(r -> !r.waivable()).map(DocumentCatalog.Requirement::code).collect(Collectors.toSet()));
        for (var requirement : DocumentCatalog.CURRENT) {
            assertFalse(requirement.document().isBlank());
            assertTrue(requirement.explanation().endsWith("."), "one simple sentence: " + requirement.code());
        }
        assertEquals(DocumentCatalog.CURRENT.size(), DocumentCatalog.CURRENT.stream().map(DocumentCatalog.Requirement::code).distinct().count());
    }

    @Test void versionOneIsKeptAsHistory() {
        assertEquals(Set.of("TECHNICAL_INSPECTION", "TRANSPORT_LICENCE", "PUBLIC_TRANSPORT_CARD", "PAID_TRANSPORT_AUTHORIZATION"),
                DocumentCatalog.V1.stream().filter(r -> r.status() == DocumentCatalog.Status.TO_CONFIRM)
                        .map(DocumentCatalog.Requirement::code).collect(Collectors.toSet()));
        assertNotNull(DocumentCatalog.byCode("PAID_TRANSPORT_AUTHORIZATION"), "an earlier code still routes to its owner");
    }
}
