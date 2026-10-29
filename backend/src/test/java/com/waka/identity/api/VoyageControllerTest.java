package com.waka.identity.api;

import com.waka.mobility.voyage.PersistentVoyageService;
import com.waka.mobility.voyage.VoyageException;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of R11 listings (K25); access, seats and idempotency are covered by the opt-in MySQL test. */
class VoyageControllerTest {
    private final String client = UUID.randomUUID().toString(), token = "A".repeat(43);
    private final UUID key = UUID.randomUUID();
    private static final String BODY = "{\"stops\":[{\"place\":\"Yaoundé\",\"plannedAt\":\"2026-11-15T06:00:00Z\"},"
            + "{\"place\":\"Douala\",\"plannedAt\":\"2026-11-15T10:00:00Z\"}],\"seats\":3,\"prices\":{\"0-1\":6000}}";

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(publish(BODY)).andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(own(get("/api/v1/voyages"))).andExpect(status().isServiceUnavailable());
        mvc.perform(own(get("/api/v1/driver/voyages"))).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedListingsAreRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentVoyageService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] {
                BODY.replace("\"seats\":3", "\"seats\":6"),
                BODY.replace("\"0-1\":6000", "\"0-1\":100000"),
                BODY.replace("\"0-1\":6000", "\"0-1\":10001"),
                BODY.replace("\"0-1\":6000", "\"0-1\":\"6000\""),
                BODY.replace("\"0-1\":6000", "\"0-1\":6000.5"),
                BODY.replace("2026-11-15T06:00:00Z", "15/11/2026 06:00"),
                BODY.replace("\"seats\":3", "\"seats\":3,\"driverAccountId\":\"x\""),
                BODY.replace("\"seats\":3,", ""),
                "[]" }) {
            mvc.perform(publish(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(own(post("/api/v1/voyages")).contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/voyages")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void publishedIs201ReplayIs200AndRefusalsAreDistinct() throws Exception {
        var service = mock(PersistentVoyageService.class);
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        UUID id = UUID.randomUUID();
        var stops = List.of(new PersistentVoyageService.Stop("Yaoundé", Instant.parse("2026-11-15T06:00:00Z")),
                new PersistentVoyageService.Stop("Douala", Instant.parse("2026-11-15T10:00:00Z")));
        when(service.publish(any(), eq(client), eq(key), eq(stops), eq(3), eq(Map.of("0-1", 6000))))
                .thenReturn(new PersistentVoyageService.VoyageView(id, "taxi", 3, "published", stops, Map.of("0-1", 6000), Map.of("0-1", 3), now, false))
                .thenReturn(new PersistentVoyageService.VoyageView(id, "taxi", 3, "published", stops, Map.of("0-1", 6000), Map.of("0-1", 3), now, true))
                .thenThrow(new VoyageException(VoyageException.Code.DRIVER_NOT_ALLOWED))
                .thenThrow(new VoyageException(VoyageException.Code.TOO_MANY_SEATS));
        MockMvc mvc = mvc(service);
        mvc.perform(publish(BODY)).andExpect(status().isCreated()).andExpect(jsonPath("$.prices['0-1']").value(6000))
                .andExpect(jsonPath("$.driverAccountId").doesNotExist());
        mvc.perform(publish(BODY)).andExpect(status().isOk());
        mvc.perform(publish(BODY)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DRIVER_NOT_ALLOWED"));
        mvc.perform(publish(BODY)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("TOO_MANY_SEATS"));
        when(service.cancel(any(), eq(client), eq(id), eq("CHANGE_OF_PLAN"))).thenThrow(new VoyageException(VoyageException.Code.NOT_FOUND));
        mvc.perform(own(post("/api/v1/voyages/" + id + "/cancellations")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"CHANGE_OF_PLAN\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test void reservationBodyIsExactAndThePriceNeverComesFromTheClient() throws Exception {
        var service = mock(PersistentVoyageService.class);
        MockMvc mvc = mvc(service);
        UUID voyage = UUID.randomUUID();
        for (String body : new String[] { "{\"fromIndex\":0,\"toIndex\":1}", "{\"fromIndex\":0,\"toIndex\":6,\"seats\":1}",
                "{\"fromIndex\":0,\"toIndex\":1,\"seats\":1,\"seatPriceXaf\":1}", "{\"fromIndex\":\"0\",\"toIndex\":1,\"seats\":1}" }) {
            mvc.perform(own(post("/api/v1/voyages/" + voyage + "/reservations")).header("Idempotency-Key", key.toString())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
        when(service.reserve(any(), eq(client), eq(key), eq(voyage), eq(0), eq(2), eq(1)))
                .thenThrow(new VoyageException(VoyageException.Code.NOT_ENOUGH_SEATS))
                .thenThrow(new VoyageException(VoyageException.Code.OWN_VOYAGE));
        String ok = "{\"fromIndex\":0,\"toIndex\":2,\"seats\":1}";
        mvc.perform(own(post("/api/v1/voyages/" + voyage + "/reservations")).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOT_ENOUGH_SEATS"));
        mvc.perform(own(post("/api/v1/voyages/" + voyage + "/reservations")).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(ok)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OWN_VOYAGE"));
        mvc.perform(own(post("/api/v1/voyage-reservations/" + voyage + "/cancellations")).content("{}")).andExpect(status().isBadRequest());
    }

    private MockHttpServletRequestBuilder publish(String body) {
        return own(post("/api/v1/voyages")).header("Idempotency-Key", key.toString()).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockHttpServletRequestBuilder own(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(PersistentVoyageService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("voyages", service);
        return MockMvcBuilders.standaloneSetup(new VoyageController(beans.getBeanProvider(PersistentVoyageService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestDiagnosticsFilter()).build();
    }
}
