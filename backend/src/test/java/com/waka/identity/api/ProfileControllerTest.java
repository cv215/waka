package com.waka.identity.api;

import com.waka.identity.profile.PersistentProfileService;
import com.waka.identity.profile.ProfileException;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A05; persistence, sessions and replay are covered by the opt-in MySQL test. */
class ProfileControllerTest {
    private static final String READ = "/api/v1/me/profile";
    private static final String WRITE = "/api/v1/me";
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43);
    private final UUID account = UUID.randomUUID();

    @Test void noAssemblyFailsClosedWithNoStore() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(read()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(write("{\"expectedVersion\":1,\"displayName\":null}")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(READ)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test void strictBodyIsRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentProfileService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] {
                "", "[]", "{}", "{\"expectedVersion\":1}", "{\"displayName\":\"A\"}",
                "{\"expectedVersion\":0,\"displayName\":\"A\"}",
                "{\"expectedVersion\":-1,\"displayName\":\"A\"}",
                "{\"expectedVersion\":01,\"displayName\":\"A\"}",
                "{\"expectedVersion\":1.0,\"displayName\":\"A\"}",
                "{\"expectedVersion\":1e0,\"displayName\":\"A\"}",
                "{\"expectedVersion\":\"1\",\"displayName\":\"A\"}",
                "{\"expectedVersion\":9007199254740992,\"displayName\":\"A\"}",
                "{\"expectedVersion\":1,\"displayName\":1}",
                "{\"expectedVersion\":1,\"displayName\":[\"A\"]}",
                "{\"expectedVersion\":1,\"displayName\":\"A\",\"marketingChoice\":true}",
                "{\"expectedVersion\":1,\"displayName\":\"A\",\"accountId\":\"" + account + "\"}",
                "{\"expectedVersion\":1,\"expectedVersion\":1,\"displayName\":\"A\"}",
                "{\"expectedVersion\":1,\"displayName\":\"A\",\"displayName\":\"B\"}",
                "{\"expectedVersion\":1,\"displayName\":\"A\"} {}",
                "{\"expectedVersion\":1,\"displayName\":\"A\"",
        }) {
            mvc.perform(write(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"));
        }
        String valid = "{\"expectedVersion\":1,\"displayName\":null}";
        mvc.perform(write(valid).queryParam("accountId", account.toString())).andExpect(status().isBadRequest());
        mvc.perform(patch(WRITE).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)
                .contentType(MediaType.APPLICATION_JSON).content(valid)).andExpect(status().isBadRequest());
        mvc.perform(write(valid).header("Idempotency-Key", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(write(valid).header("Content-Encoding", "gzip")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(write(valid).contentType(MediaType.TEXT_PLAIN)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(write("{\"expectedVersion\":1,\"displayName\":\"" + "A".repeat(2100) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
        mvc.perform(read().content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void successReturnsExactlySixFieldsWithoutCaching() throws Exception {
        var service = mock(PersistentProfileService.class);
        Instant now = Instant.parse("2026-10-28T10:00:00.123456Z");
        when(service.read(any(), eq(client))).thenReturn(
                new PersistentProfileService.ProfileView(account, 1, null, null, now, false));
        when(service.patch(any(), eq(client), any(UUID.class), eq(1L), eq("Élodie"))).thenReturn(
                new PersistentProfileService.ProfileView(account, 2, "Élodie", now, now, false));
        MockMvc mvc = mvc(service);
        mvc.perform(read()).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.accountId").value(account.toString()))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.displayName").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isEmpty())
                .andExpect(jsonPath("$.serverTime").value("2026-10-28T10:00:00.123456Z"))
                .andExpect(jsonPath("$.idempotentReplay").value(false));
        mvc.perform(write("{\"expectedVersion\":1,\"displayName\":\"Élodie\"}")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.displayName").value("Élodie"))
                .andExpect(jsonPath("$.updatedAt").value("2026-10-28T10:00:00.123456Z"));
    }

    @Test void nullClearsAndRefusalsKeepDistinctCodes() throws Exception {
        var service = mock(PersistentProfileService.class);
        MockMvc mvc = mvc(service);
        when(service.patch(any(), anyString(), any(UUID.class), anyLong(), isNull()))
                .thenThrow(new ProfileException(ProfileException.Code.VERSION_CONFLICT))
                .thenThrow(new ProfileException(ProfileException.Code.IDEMPOTENCY_KEY_REUSED))
                .thenThrow(new ProfileException(ProfileException.Code.REPLAY_EXPIRED))
                .thenThrow(new ProfileException(ProfileException.Code.INVALID_REQUEST))
                .thenThrow(new ProfileException(ProfileException.Code.SERVICE_UNAVAILABLE))
                .thenThrow(new SessionException(SessionException.Code.INVALID_SESSION))
                .thenThrow(new SessionException(SessionException.Code.SERVICE_UNAVAILABLE));
        String clear = "{\"expectedVersion\":3,\"displayName\":null}";
        mvc.perform(write(clear)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(write(clear)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(write(clear)).andExpect(status().isGone()).andExpect(jsonPath("$.code").value("REPLAY_EXPIRED"));
        mvc.perform(write(clear)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(write(clear)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mvc.perform(write(clear)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(write(clear)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(service, times(7)).patch(any(), eq(client), any(UUID.class), eq(3L), isNull());
    }

    private MockHttpServletRequestBuilder read() {
        return get(READ).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }

    private MockHttpServletRequestBuilder write(String body) {
        return patch(WRITE).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body);
    }

    private static MockMvc mvc(PersistentProfileService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("profile", service);
        return MockMvcBuilders.standaloneSetup(new ProfileController(beans.getBeanProvider(PersistentProfileService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
