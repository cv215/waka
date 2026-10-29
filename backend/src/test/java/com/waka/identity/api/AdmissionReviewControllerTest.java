package com.waka.identity.api;

import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.AdmissionReviewService;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A29, A37 and A38; rules and isolation are covered by the opt-in MySQL test. */
class AdmissionReviewControllerTest {
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43), adminToken = "B".repeat(43);
    private final UUID app = UUID.randomUUID(), photo = UUID.randomUUID();
    private final String decision = "{\"expectedVersion\":3,\"action\":\"request_correction\",\"reasonCode\":\"PHOTO_BLURRED\","
            + "\"details\":\"Plaque illisible\",\"photoDecisions\":[{\"view\":\"PH02\",\"fileId\":\"" + photo + "\",\"result\":\"to_correct\",\"reasonCode\":\"PLATE_UNREADABLE\"}]}";

    /** The decision body with one document marked to correct; the agent never sends a document type. */
    private String withDocument(String documentId) {
        return decision.substring(0, decision.length() - 1)
                + ",\"documentDecisions\":[{\"documentId\":\"" + documentId + "\",\"result\":\"to_correct\",\"reasonCode\":\"DOCUMENT_UNREADABLE\"}]}";
    }

    @Test void noAssemblyFailsClosedAndScopeIsNeverFree() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(admin(get("/api/v1/admin/work-items").queryParam("category", "admission"))).andExpect(status().isServiceUnavailable());
        mvc.perform(admin(get("/api/v1/admin/work-items"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get("/api/v1/admin/work-items").queryParam("category", "finance"))).andExpect(status().isBadRequest());
        mvc.perform(admin(get("/api/v1/admin/driver-applications/" + app))).andExpect(status().isServiceUnavailable());
        mvc.perform(withClient(get("/api/v1/admin/driver-applications/" + app))).andExpect(status().isUnauthorized());
        mvc.perform(decide(decision)).andExpect(status().isServiceUnavailable());
    }

    @Test void decisionBodyIsStrict() throws Exception {
        var service = mock(AdmissionReviewService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] {
                "{}", "{\"expectedVersion\":3,\"action\":\"refuse\"}",
                "{\"expectedVersion\":\"3\",\"action\":\"refuse\",\"reasonCode\":\"X_Y\"}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"approved\":true}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"state\":\"approved\"}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"documentApprovals\":[]}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"documentDecisions\":[{\"documentId\":\"x\",\"result\":\"acceptable\"}]}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"documentDecisions\":[{\"result\":\"acceptable\"}]}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"photoDecisions\":[{\"view\":\"PH01\"}]}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"photoDecisions\":[{\"view\":\"PH01\",\"fileId\":\"x\",\"result\":\"acceptable\"}]}",
                "{\"expectedVersion\":3,\"action\":\"refuse\",\"reasonCode\":\"X_Y\",\"action\":\"approve\"}",
        }) {
            mvc.perform(decide(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test void decisionsAndRefusalsUseSafeCodes() throws Exception {
        var service = mock(AdmissionReviewService.class);
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        UUID document = UUID.randomUUID();
        var documents = List.of(new AdmissionReviewService.DocumentReview(document, null, "to_correct", "DOCUMENT_UNREADABLE"));
        var created = new AdmissionReviewService.Decision(UUID.randomUUID(), app, 3, "request_correction", "PHOTO_BLURRED",
                "Plaque illisible", List.of(new AdmissionReviewService.PhotoReview("PH02", photo, "to_correct", "PLATE_UNREADABLE")),
                List.of(new AdmissionReviewService.DocumentReview(document, "INSURANCE", "to_correct", "DOCUMENT_UNREADABLE")), List.of(), now, false);
        when(service.decide(any(), eq(client), any(), eq(app), any(UUID.class), eq(3L), eq("request_correction"),
                eq("PHOTO_BLURRED"), eq("Plaque illisible"), eq(created.photoReviews()), eq(documents), eq(List.of()))).thenReturn(created);
        when(service.decide(any(), eq(client), any(), eq(app), any(UUID.class), anyLong(), eq("approve"), any(), any(), any(), any(), any()))
                .thenThrow(new AdmissionException(AdmissionException.Code.APPROVAL_NOT_AVAILABLE));
        when(service.detail(any(), eq(client), any(), eq(app)))
                .thenThrow(new AdmissionException(AdmissionException.Code.CONFLICT_OF_INTEREST))
                .thenThrow(new AdmissionException(AdmissionException.Code.NOT_AUTHORIZED));
        MockMvc mvc = mvc(service);
        mvc.perform(decide(withDocument(document.toString()))).andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.action").value("request_correction"))
                .andExpect(jsonPath("$.photoReviews[0].result").value("to_correct"))
                .andExpect(jsonPath("$.documentReviews[0].documentType").value("INSURANCE"));
        mvc.perform(decide(withDocument(document.toString()).replace("\"documentId\"", "\"documentType\":\"INSURANCE\",\"documentId\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(decide("{\"expectedVersion\":3,\"action\":\"approve\",\"reasonCode\":\"ALL_GOOD\",\"waivers\":[{\"code\":\"INSURANCE\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(decide("{\"expectedVersion\":3,\"action\":\"approve\",\"reasonCode\":\"ALL_GOOD\",\"waivers\":[{\"code\":\"INSURANCE\",\"reasonCode\":\"X_Y\",\"extra\":\"1\"}]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(decide("{\"expectedVersion\":3,\"action\":\"approve\",\"reasonCode\":\"ALL_GOOD\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPROVAL_NOT_AVAILABLE"));
        mvc.perform(admin(get("/api/v1/admin/driver-applications/" + app))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CONFLICT_OF_INTEREST"));
        mvc.perform(admin(get("/api/v1/admin/driver-applications/" + app))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_ALLOWED"));
    }

    @Test void candidateSubmissionBodyIsExactAndNotQualifiedIsExplicit() throws Exception {
        var applications = mock(PersistentDriverApplicationService.class);
        when(applications.submit(any(), eq(client), eq(app), any(UUID.class), eq(2L)))
                .thenThrow(new AdmissionException(AdmissionException.Code.REQUIREMENTS_NOT_QUALIFIED))
                .thenThrow(new AdmissionException(AdmissionException.Code.INCOMPLETE));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("applications", applications);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DriverApplicationController(
                        beans.getBeanProvider(PersistentDriverApplicationService.class), beans.getBeanProvider(AdmissionReviewService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestDiagnosticsFilter()).build();
        for (String body : new String[] { "{}", "{\"expectedVersion\":2,\"state\":\"submitted\"}", "{\"expectedVersion\":0}", "[]" }) {
            mvc.perform(submit(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(submit("{\"expectedVersion\":2}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUIREMENTS_NOT_QUALIFIED"));
        mvc.perform(submit("{\"expectedVersion\":2}")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INCOMPLETE"));
        mvc.perform(withClient(get("/api/v1/driver-applications/" + app + "/decisions"))).andExpect(status().isServiceUnavailable());
    }

    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withClient(builder).header(AdminAccessController.ADMIN_HEADER, adminToken);
    }
    private MockHttpServletRequestBuilder decide(String body) {
        return admin(post("/api/v1/admin/driver-applications/" + app + "/decisions")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockHttpServletRequestBuilder submit(String body) {
        return withClient(post("/api/v1/driver-applications/" + app + "/submissions")).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private static MockMvc mvc(AdmissionReviewService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("reviews", service);
        return MockMvcBuilders.standaloneSetup(new AdmissionReviewController(beans.getBeanProvider(AdmissionReviewService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
