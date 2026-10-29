package com.waka.identity.api;

import com.waka.identity.admin.AdminException;
import com.waka.identity.admin.AdminFunction;
import com.waka.identity.admin.PersistentAdminService;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of 05.05; grants, codes and sessions are covered by the opt-in MySQL test. */
class AdminAccessControllerTest {
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43), adminToken = "B".repeat(43);

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(withClient(post("/api/v1/admin/second-factor/enrollments"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(code("/api/v1/admin/sessions", "123456")).andExpect(status().isServiceUnavailable());
        mvc.perform(admin(get("/api/v1/admin/me"))).andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/v1/admin/sessions").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void malformedInputIsRefusedBeforeTheServiceAndNeverCountsAsAFailure() throws Exception {
        var service = mock(PersistentAdminService.class);
        MockMvc mvc = mvc(service);
        for (String bad : new String[] { "12345", "1234567", "12a456", " 123456" }) {
            mvc.perform(code("/api/v1/admin/sessions", bad)).andExpect(status().isBadRequest());
            mvc.perform(code("/api/v1/admin/second-factor/confirmations", bad)).andExpect(status().isBadRequest());
        }
        mvc.perform(withClient(post("/api/v1/admin/sessions")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"123456\",\"functions\":[\"FINANCE\"]}")).andExpect(status().isBadRequest());
        mvc.perform(withClient(post("/api/v1/admin/second-factor/enrollments")).content("{}")).andExpect(status().isBadRequest());
        mvc.perform(withClient(get("/api/v1/admin/me"))).andExpect(status().isUnauthorized());
        mvc.perform(withClient(get("/api/v1/admin/me")).header(AdminAccessController.ADMIN_HEADER, "short"))
                .andExpect(status().isUnauthorized());
        mvc.perform(admin(get("/api/v1/admin/me")).header(AdminAccessController.ADMIN_HEADER, adminToken))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void openedSessionAndContextAreNotCached() throws Exception {
        var service = mock(PersistentAdminService.class);
        Instant now = Instant.parse("2026-10-28T10:00:00Z");
        UUID account = UUID.randomUUID(), id = UUID.randomUUID();
        var context = new PersistentAdminService.AdminContext(account, id, List.of(AdminFunction.ADMISSION), now, now, now);
        when(service.authenticate(any(), eq(client), any())).thenReturn(context);
        MockMvc mvc = mvc(service);
        mvc.perform(admin(get("/api/v1/admin/me"))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.functions[0]").value("ADMISSION"))
                .andExpect(jsonPath("$.adminSessionId").value(id.toString()));
    }

    @Test void refusalsUseSafeDistinctCodes() throws Exception {
        var service = mock(PersistentAdminService.class);
        when(service.open(any(), eq(client), eq("123456")))
                .thenThrow(new AdminException(AdminException.Code.NOT_AUTHORIZED))
                .thenThrow(new AdminException(AdminException.Code.ENROLLMENT_REQUIRED))
                .thenThrow(new AdminException(AdminException.Code.INVALID_SECOND_FACTOR))
                .thenThrow(new AdminException(AdminException.Code.LOCKED));
        MockMvc mvc = mvc(service);
        mvc.perform(code("/api/v1/admin/sessions", "123456")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACTION_NOT_ALLOWED"));
        mvc.perform(code("/api/v1/admin/sessions", "123456")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ENROLLMENT_REQUIRED"));
        mvc.perform(code("/api/v1/admin/sessions", "123456")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SECOND_FACTOR"));
        mvc.perform(code("/api/v1/admin/sessions", "123456")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withClient(builder).header(AdminAccessController.ADMIN_HEADER, adminToken);
    }
    private MockHttpServletRequestBuilder code(String path, String value) {
        return withClient(post(path)).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + value + "\"}");
    }
    private static MockMvc mvc(PersistentAdminService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("admin", service);
        return MockMvcBuilders.standaloneSetup(new AdminAccessController(beans.getBeanProvider(PersistentAdminService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
