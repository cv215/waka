package com.waka.identity.api;

import com.waka.identity.privacy.PersistentPrivacyService;
import com.waka.identity.privacy.PrivacyException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A06/A07 (K21); persistence and isolation are covered by the opt-in MySQL test. */
class PrivacyControllerTest {
    private final String client = UUID.randomUUID().toString(), token = "A".repeat(43);
    private final UUID key = UUID.randomUUID(), id = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-29T10:00:00Z");

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(privacy("{\"kind\":\"deletion\"}")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(withClient(get("/api/v1/me/activity"))).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedInputIsRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentPrivacyService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] { "{}", "{\"kind\":\"export\"}", "{\"kind\":\"access\",\"accountId\":\"x\"}",
                "{\"kind\":\"access\",\"kind\":\"deletion\"}", "{\"kind\":\"access\",\"details\":3}", "[]" }) {
            mvc.perform(privacy(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(withClient(post("/api/v1/me/privacy-requests")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"kind\":\"access\"}")).andExpect(status().isBadRequest());
        for (String query : new String[] { "limit=0", "limit=100", "accountId=1", "limit=5&limit=6", "cursor=" }) {
            mvc.perform(withClient(get("/api/v1/me/activity?" + query))).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/me/activity")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void newRequestIs201AndAnExistingOneIs200WithoutDetails() throws Exception {
        var service = mock(PersistentPrivacyService.class);
        when(service.request(any(), eq(client), eq(key), eq(PersistentPrivacyService.Kind.DELETION), eq("Mon compte")))
                .thenReturn(new PersistentPrivacyService.RequestView(id, "deletion", "received", now, true))
                .thenReturn(new PersistentPrivacyService.RequestView(id, "deletion", "received", now, false));
        MockMvc mvc = mvc(service);
        String body = "{\"kind\":\"deletion\",\"details\":\"Mon compte\"}";
        mvc.perform(privacy(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("received"))
                .andExpect(jsonPath("$.details").doesNotExist());
        mvc.perform(privacy(body)).andExpect(status().isOk());
    }

    @Test void activityPassesTheCursorAndRefusalsAreSafe() throws Exception {
        var service = mock(PersistentPrivacyService.class);
        String cursor = "1761732000000000_" + id;
        when(service.activity(any(), eq(client), eq(cursor), eq(10))).thenReturn(new PersistentPrivacyService.ActivityPage(
                List.of(new PersistentPrivacyService.ActivityItem("quote", id, "expired", now)), null));
        when(service.activity(any(), eq(client), isNull(), eq(20))).thenThrow(new PrivacyException(PrivacyException.Code.INVALID_REQUEST));
        when(service.request(any(), eq(client), eq(key), any(), any()))
                .thenThrow(new PrivacyException(PrivacyException.Code.IDEMPOTENCY_KEY_REUSED));
        MockMvc mvc = mvc(service);
        mvc.perform(withClient(get("/api/v1/me/activity?cursor=" + cursor + "&limit=10"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].objectType").value("quote")).andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(withClient(get("/api/v1/me/activity"))).andExpect(status().isBadRequest());
        mvc.perform(privacy("{\"kind\":\"access\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private MockHttpServletRequestBuilder privacy(String body) {
        return withClient(post("/api/v1/me/privacy-requests")).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(PersistentPrivacyService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("privacy", service);
        return MockMvcBuilders.standaloneSetup(new PrivacyController(beans.getBeanProvider(PersistentPrivacyService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
