package com.waka.identity.api;

import com.waka.identity.admission.AdmissionException;
import com.waka.identity.admission.PersistentDriverApplicationService;
import com.waka.identity.admission.VehicleFields;
import com.waka.identity.session.SessionException;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A26-A28; persistence, isolation and replay are covered by the opt-in MySQL test. */
class DriverApplicationControllerTest {
    private static final String CREATE = "/api/v1/driver-applications";
    private static final String CURRENT = "/api/v1/me/driver-application";
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43);
    private final UUID account = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final String full = "{\"expectedVersion\":1,\"vehicleFields\":{\"brand\":\"Marque\",\"model\":\"Modele\",\"year\":2020,"
            + "\"color\":\"Bleu\",\"registration\":\"AB 12\",\"authorizedTotalCapacity\":5,\"offeredPassengerSeats\":4,\"usageRight\":\"holder\"}}";

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(create("{\"requestedCategory\":\"taxi\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(read(CURRENT)).andExpect(status().isServiceUnavailable());
        mvc.perform(read(CREATE + "/" + id)).andExpect(status().isServiceUnavailable());
        mvc.perform(update(full)).andExpect(status().isServiceUnavailable());
        mvc.perform(get(CURRENT)).andExpect(status().isUnauthorized());
    }

    @Test void strictInputIsRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentDriverApplicationService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] { "{}", "{\"requestedCategory\":\"taxi\",\"state\":\"approved\"}",
                "{\"requestedCategory\":1}", "{\"category\":\"taxi\"}" }) {
            mvc.perform(create(body)).andExpect(status().isBadRequest());
        }
        String vehicle = full.substring(full.indexOf("\"vehicleFields\""), full.length() - 1);
        for (String body : new String[] {
                "{}", "{\"expectedVersion\":1}", "{" + vehicle + "}",
                "{\"expectedVersion\":0," + vehicle + "}",
                "{\"expectedVersion\":1.0," + vehicle + "}",
                "{\"expectedVersion\":\"1\"," + vehicle + "}",
                full.replace("}}", "},\"documentRefs\":[]}"),
                full.replace("}}", "},\"photoRefs\":[]}"),
                full.replace("}}", "},\"state\":\"submitted\"}"),
                full.replace("}}", "},\"approved\":true}"),
                full.replace(",\"usageRight\":\"holder\"", ""),
                full.replace("\"usageRight\":\"holder\"", "\"usageRight\":\"holder\",\"vin\":\"X\""),
                full.replace("\"year\":2020", "\"year\":\"2020\""),
                full.replace("\"year\":2020", "\"year\":2020.0"),
                full.replace("\"year\":2020", "\"year\":-2020"),
                full.replace("\"year\":2020", "\"year\":20200"),
                full.replace("\"brand\":\"Marque\"", "\"brand\":1"),
                full.replace("\"brand\":\"Marque\"", "\"brand\":\"Marque\",\"brand\":\"Autre\""),
                full + " {}",
        }) {
            mvc.perform(update(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(update(full).header("Idempotency-Key", "bad")).andExpect(status().isBadRequest());
        mvc.perform(patch(CREATE + "/not-a-uuid").header("Authorization", "Bearer " + token)
                .header("X-Waka-Client-Instance", client).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content(full)).andExpect(status().isBadRequest());
        mvc.perform(update(full).queryParam("accountId", account.toString())).andExpect(status().isBadRequest());
        mvc.perform(update(full).header("Content-Encoding", "gzip")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(update("{\"expectedVersion\":1,\"vehicleFields\":{\"brand\":\"" + "A".repeat(4100) + "\"}}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(read(CURRENT).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(read(CURRENT).queryParam("accountId", account.toString())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void createdDraftIsExplicitAboutWhatIsMissing() throws Exception {
        var service = mock(PersistentDriverApplicationService.class);
        Instant now = Instant.parse("2026-10-28T10:00:00.123456Z");
        var draft = new PersistentDriverApplicationService.ApplicationView(id, account, "private", "draft", 1,
                VehicleFields.EMPTY, VehicleFields.EMPTY.missing(), now, now, now, false);
        when(service.create(any(), eq(client), any(UUID.class), eq("private"))).thenReturn(draft);
        when(service.readCurrent(any(), eq(client))).thenReturn(draft);
        MockMvc mvc = mvc(service);
        mvc.perform(create("{\"requestedCategory\":\"private\"}")).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(11))
                .andExpect(jsonPath("$.applicationId").value(id.toString()))
                .andExpect(jsonPath("$.state").value("draft"))
                .andExpect(jsonPath("$.requestedCategory").value("private"))
                .andExpect(jsonPath("$.vehicleFields.length()").value(8))
                .andExpect(jsonPath("$.vehicleFields.registration").isEmpty())
                .andExpect(jsonPath("$.missingVehicleFields.length()").value(8))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
        mvc.perform(read(CURRENT)).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
    }

    @Test void refusalsKeepDistinctCodesAndHideOtherCandidates() throws Exception {
        var service = mock(PersistentDriverApplicationService.class);
        when(service.updateVehicle(any(), eq(client), eq(id), any(UUID.class), anyLong(), any()))
                .thenThrow(new AdmissionException(AdmissionException.Code.VERSION_CONFLICT))
                .thenThrow(new AdmissionException(AdmissionException.Code.IDEMPOTENCY_KEY_REUSED))
                .thenThrow(new AdmissionException(AdmissionException.Code.REPLAY_EXPIRED))
                .thenThrow(new AdmissionException(AdmissionException.Code.NOT_FOUND))
                .thenThrow(new AdmissionException(AdmissionException.Code.INVALID_REQUEST))
                .thenThrow(new SessionException(SessionException.Code.INVALID_SESSION))
                .thenThrow(new SessionException(SessionException.Code.SERVICE_UNAVAILABLE));
        when(service.create(any(), eq(client), any(UUID.class), eq("taxi")))
                .thenThrow(new AdmissionException(AdmissionException.Code.APPLICATION_ALREADY_OPEN));
        MockMvc mvc = mvc(service);
        mvc.perform(update(full)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(update(full)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(update(full)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REPLAY_EXPIRED"));
        mvc.perform(update(full)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(update(full)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(update(full)).andExpect(status().isUnauthorized());
        mvc.perform(update(full)).andExpect(status().isServiceUnavailable());
        mvc.perform(create("{\"requestedCategory\":\"taxi\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_ALREADY_OPEN"));
        verify(service, times(7)).updateVehicle(any(), eq(client), eq(id), any(UUID.class), eq(1L),
                eq(new VehicleFields("Marque", "Modele", 2020, "Bleu", "AB 12", 5, 4, "holder")));
    }

    private MockHttpServletRequestBuilder create(String body) {
        return post(CREATE).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body);
    }
    private MockHttpServletRequestBuilder read(String path) {
        return get(path).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private MockHttpServletRequestBuilder update(String body) {
        return patch(CREATE + "/" + id).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body);
    }
    private static MockMvc mvc(PersistentDriverApplicationService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("applications", service);
        return MockMvcBuilders.standaloneSetup(new DriverApplicationController(beans.getBeanProvider(PersistentDriverApplicationService.class),
                        beans.getBeanProvider(com.waka.identity.admission.AdmissionReviewService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
