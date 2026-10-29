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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of 06.09 (K19); grant rules themselves are covered by the opt-in MySQL test. */
class AdminGovernanceControllerTest {
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43), adminToken = "B".repeat(43);
    private final UUID account = UUID.randomUUID(), grantId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-29T10:00:00Z");

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(admin(get("/api/v1/admin/grants"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(grantBody("ADMISSION", "STAFFING")).andExpect(status().isServiceUnavailable());
        mvc.perform(admin(get("/api/v1/admin/audit"))).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedInputIsRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentAdminService.class);
        MockMvc mvc = mvc(service);
        mvc.perform(grantBody("ROOT", "STAFFING")).andExpect(status().isBadRequest());
        mvc.perform(admin(post("/api/v1/admin/grants")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + account + "\",\"function\":\"AUDIT\",\"reasonCode\":\"X\",\"extra\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(admin(post("/api/v1/admin/grants/not-a-uuid/revocations")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"LEFT\"}")).andExpect(status().isBadRequest());
        for (String query : new String[] { "limit=0", "limit=1000", "cursor=-1", "objectType=a&objectType=b", "secret=1",
                "objectId=a%20b", "limit", "objectId=" }) {
            mvc.perform(admin(get("/api/v1/admin/audit?" + query))).andExpect(status().isBadRequest());
        }
        mvc.perform(withClient(get("/api/v1/admin/grants"))).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void newGrantIs201AndReplayIs200() throws Exception {
        var service = mock(PersistentAdminService.class);
        var fresh = new PersistentAdminService.GrantView(grantId, account, AdminFunction.ADMISSION, now, "x", "STAFFING",
                null, null, null, false);
        var replay = new PersistentAdminService.GrantView(grantId, account, AdminFunction.ADMISSION, now, "x", "STAFFING",
                null, null, null, true);
        when(service.grant(any(), eq(client), any(), eq(account), eq(AdminFunction.ADMISSION), eq("STAFFING")))
                .thenReturn(fresh).thenReturn(replay);
        MockMvc mvc = mvc(service);
        mvc.perform(grantBody("ADMISSION", "STAFFING")).andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.grantId").value(grantId.toString()));
        mvc.perform(grantBody("ADMISSION", "STAFFING")).andExpect(status().isOk());
    }

    @Test void auditPassesOnlyValidatedFilters() throws Exception {
        var service = mock(PersistentAdminService.class);
        when(service.audit(any(), eq(client), any(), eq("admin_grant"), eq(grantId.toString()), eq(42L), eq(10)))
                .thenReturn(List.of(new PersistentAdminService.AuditView(41, now, "ADMIN_GRANT", "a", "HABILITATIONS",
                        "admin_grant", grantId.toString(), "STAFFING", "APPLIED")));
        when(service.audit(any(), eq(client), any(), isNull(), isNull(), isNull(), eq(50))).thenReturn(List.of());
        MockMvc mvc = mvc(service);
        mvc.perform(admin(get("/api/v1/admin/audit?objectType=admin_grant&objectId=" + grantId + "&cursor=42&limit=10")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].eventType").value("ADMIN_GRANT"));
        mvc.perform(admin(get("/api/v1/admin/audit"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test void refusalsUseSafeDistinctCodes() throws Exception {
        var service = mock(PersistentAdminService.class);
        when(service.revoke(any(), eq(client), any(), eq(grantId), eq("LEFT")))
                .thenThrow(new AdminException(AdminException.Code.SELF_GRANT_FORBIDDEN))
                .thenThrow(new AdminException(AdminException.Code.LAST_HABILITATIONS_HOLDER))
                .thenThrow(new AdminException(AdminException.Code.NOT_FOUND))
                .thenThrow(new AdminException(AdminException.Code.NOT_AUTHORIZED));
        MockMvc mvc = mvc(service);
        mvc.perform(revoke()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SELF_GRANT_FORBIDDEN"));
        mvc.perform(revoke()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_HABILITATIONS_HOLDER"));
        mvc.perform(revoke()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(revoke()).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACTION_NOT_ALLOWED"));
    }

    private MockHttpServletRequestBuilder revoke() {
        return admin(post("/api/v1/admin/grants/" + grantId + "/revocations")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reasonCode\":\"LEFT\"}");
    }
    private MockHttpServletRequestBuilder grantBody(String function, String reason) {
        return admin(post("/api/v1/admin/grants")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"accountId\":\"" + account + "\",\"function\":\"" + function + "\",\"reasonCode\":\"" + reason + "\"}");
    }
    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return withClient(builder).header(AdminAccessController.ADMIN_HEADER, adminToken);
    }
    private static MockMvc mvc(PersistentAdminService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("admin", service);
        return MockMvcBuilders.standaloneSetup(new AdminGovernanceController(beans.getBeanProvider(PersistentAdminService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
