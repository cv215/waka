package com.waka.operations.api;

import com.waka.identity.api.MeController;
import com.waka.identity.application.MeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AdminWebCorsConfigurationTest {
    private static final String ADMIN = "http://localhost:7357";

    @Test void closedByDefaultAndOnlyExplicitSafeOriginsAreAccepted() {
        assertEquals(java.util.List.of(), AdminWebCorsConfiguration.parse(""));
        assertEquals(java.util.List.of(ADMIN, "https://admin.example.org"),
                AdminWebCorsConfiguration.parse(ADMIN + ", https://admin.example.org"));
        for (String refused : new String[] { "*", "http://admin.example.org", "https://*.example.org", "null",
                "https://admin.example.org/path", "file://local", "http://localhost.evil.org" }) {
            assertThrows(IllegalArgumentException.class, () -> AdminWebCorsConfiguration.parse(refused), refused);
        }
    }

    @Test void preflightIsAnsweredOnlyForTheConfiguredOrigin() throws Exception {
        MockMvc open = mvc(ADMIN);
        open.perform(options("/api/v1/me").header("Origin", ADMIN).header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "authorization,x-waka-client-instance,x-waka-admin-session"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        open.perform(options("/api/v1/me").header("Origin", "http://localhost:9999").header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        open.perform(options("/api/v1/me").header("Origin", ADMIN).header("Access-Control-Request-Method", "DELETE"))
                .andExpect(status().isForbidden());
        mvc("").perform(options("/api/v1/me").header("Origin", ADMIN).header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    private static MockMvc mvc(String origins) {
        var registry = new CorsRegistry();
        new AdminWebCorsConfiguration(origins).addCorsMappings(registry);
        var beans = new DefaultListableBeanFactory();
        var source = new org.springframework.web.cors.UrlBasedCorsConfigurationSource();
        corsOf(registry).forEach(source::registerCorsConfiguration);
        return MockMvcBuilders.standaloneSetup(new MeController(beans.getBeanProvider(MeService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter(), new org.springframework.web.filter.CorsFilter(source)).build();
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, org.springframework.web.cors.CorsConfiguration> corsOf(CorsRegistry registry) {
        try {
            var method = CorsRegistry.class.getDeclaredMethod("getCorsConfigurations");
            method.setAccessible(true);
            return (java.util.Map<String, org.springframework.web.cors.CorsConfiguration>) method.invoke(registry);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
