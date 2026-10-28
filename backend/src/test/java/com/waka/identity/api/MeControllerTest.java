package com.waka.identity.api;

import com.waka.identity.application.MeService;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Fail-closed HTTP boundary; successful reads use the opt-in MySQL tests. */
class MeControllerTest {
    private static final String ROUTE = "/api/v1/me";
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43);

    @Test void noAssemblyFailsClosedButMissingProofIsAuthenticationFailure() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(command()).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(get(ROUTE)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(get(ROUTE).header("X-Waka-Client-Instance", client))
                .andExpect(status().isUnauthorized());
        mvc.perform(get(ROUTE).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void queryBodyAndAmbiguousHeadersAreRefusedBeforeService() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(command().queryParam("accountId", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(command().content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(command().header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(command().header("X-Waka-Client-Instance", client))
                .andExpect(status().isBadRequest());
        mvc.perform(command().header("Content-Encoding", "gzip"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(command().header("Content-Encoding", "identity"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(command().header("Content-Encoding", "identity", "identity"))
                .andExpect(status().isBadRequest());
        mvc.perform(get(ROUTE).header("Authorization", "Bearer bad")
                .header("X-Waka-Client-Instance", client)).andExpect(status().isUnauthorized());
        mvc.perform(get(ROUTE).header("Authorization", "Bearer " + token)
                .header("X-Waka-Client-Instance", "short")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletRequestBuilder command() {
        return get(ROUTE).header("Authorization", "Bearer " + token)
                .header("X-Waka-Client-Instance", client);
    }

    private static MockMvc mvc() {
        var beans = new DefaultListableBeanFactory();
        return MockMvcBuilders.standaloneSetup(new MeController(beans.getBeanProvider(MeService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
