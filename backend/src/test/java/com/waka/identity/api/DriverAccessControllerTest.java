package com.waka.identity.api;

import com.waka.drivers.DriverAccessService;
import com.waka.identity.session.SessionException;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A32 (K23). */
class DriverAccessControllerTest {
    private final String client = UUID.randomUUID().toString(), token = "A".repeat(43);

    @Test void unassembledUnauthenticatedAndQueriesAreRefused() throws Exception {
        mvc(null).perform(own("/api/v1/driver/access")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        var service = mock(DriverAccessService.class);
        MockMvc mvc = mvc(service);
        mvc.perform(get("/api/v1/driver/access")).andExpect(status().isUnauthorized());
        mvc.perform(own("/api/v1/driver/access?accountId=1")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void ownAccessIsReturnedWithoutCaching() throws Exception {
        var service = mock(DriverAccessService.class);
        when(service.access(any(), eq(client)))
                .thenReturn(new DriverAccessService.AccessView("active", "taxi_no_subscription", UUID.randomUUID(), "taxi",
                        "approved", List.of(), List.of(), null, Instant.parse("2026-10-29T10:00:00Z")))
                .thenThrow(new SessionException(SessionException.Code.INVALID_SESSION));
        MockMvc mvc = mvc(service);
        mvc.perform(own("/api/v1/driver/access")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.access").value("active")).andExpect(jsonPath("$.basis").value("taxi_no_subscription"));
        mvc.perform(own("/api/v1/driver/access")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder own(String path) {
        return get(path).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(DriverAccessService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("access", service);
        return MockMvcBuilders.standaloneSetup(new DriverAccessController(beans.getBeanProvider(DriverAccessService.class)))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestDiagnosticsFilter()).build();
    }
}
