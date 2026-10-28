package com.waka.operations.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.RequestDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC et filtre en memoire : aucun socket HTTP, aucune connexion MySQL. */
class ApiErrorsTest {
    private static final String MARKER = "UNIT_TEST_ONLY_PRIVATE_MARKER";
    private MockMvc mvc;

    @BeforeAll
    static void useActualApplicationLoggingConfiguration() throws Exception {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.reset();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(ApiErrorsTest.class.getResource("/logback-spring.xml"));
    }

    @BeforeEach
    void prepareMvc() {
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(), new ProbeController(),
                        new SafeErrorController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter())
                .build();
    }

    @Test
    void missingResourceDoesNotReflectPathQueryHeadersOrCallerRequestId() throws Exception {
        MvcResult result = mvc.perform(get("/missing/" + MARKER).queryParam("contact", MARKER)
                        .header("X-Request-Id", MARKER).header("Authorization", MARKER))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.instance").doesNotExist())
                .andReturn();
        verifySafeBody(result);
    }

    @Test
    void methodRefusalRetainsAllowWithoutReflectingPayload() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/health").content(MARKER))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andReturn();
        verifySafeBody(result);
    }

    @Test
    void invalidNumberAndMalformedJsonAre400WithNoBindingDetails() throws Exception {
        MvcResult number = mvc.perform(get("/unit-only/number").queryParam("value", MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
        verifySafeBody(number);
        MvcResult json = mvc.perform(post("/unit-only/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"" + MARKER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
        verifySafeBody(json);
    }

    @Test
    void unexpectedExceptionGives500WithoutClassMessageOrStack() throws Exception {
        MvcResult result = mvc.perform(get("/unit-only/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andReturn();
        verifySafeBody(result);
        assertFalse(result.getResponse().getContentAsString().contains("IllegalStateException"));
    }

    @Test
    void containerErrorDispatchAndHtmlAcceptUseSameSafeEnvelope() throws Exception {
        MvcResult result = mvc.perform(get("/error").accept(MediaType.TEXT_HTML)
                        .requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
                        .requestAttr(RequestDispatcher.ERROR_MESSAGE, MARKER)
                        .requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(MARKER))
                        .requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/" + MARKER))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).andReturn();
        verifySafeBody(result);
    }

    @Test
    void successContractIsPreservedAndRequestIdsAreUniqueServerValues() throws Exception {
        MvcResult first = mvc.perform(get("/api/v1/health").header("X-Request-Id", MARKER))
                .andExpect(status().isOk()).andExpect(content().json("{\"status\":\"UP\"}")).andReturn();
        MvcResult second = mvc.perform(get("/api/v1/health")).andExpect(status().isOk()).andReturn();
        String firstId = first.getResponse().getHeader("X-Request-Id");
        assertNotNull(firstId);
        UUID.fromString(firstId);
        assertNotEquals(firstId, second.getResponse().getHeader("X-Request-Id"));
        assertNotEquals(MARKER, firstId);
    }

    @Test
    void filterSafelyHandlesFailureOutsideMvcAndLogsOnlyApprovedFields() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("com.waka.operational");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
        try {
            MockHttpServletRequest request = new MockHttpServletRequest(MARKER, "/" + MARKER);
            request.setQueryString("token=" + MARKER);
            request.addHeader("Authorization", MARKER);
            request.setContent(MARKER.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            MockHttpServletResponse response = new MockHttpServletResponse();
            new RequestDiagnosticsFilter().doFilter(request, response, (req, res) -> {
                throw new IllegalStateException(MARKER);
            });
            assertEquals(500, response.getStatus());
            assertEquals("no-store", response.getHeader("Cache-Control"));
            assertFalse(response.getContentAsString().contains(MARKER));
            assertTrue(response.getContentAsString().contains("INTERNAL_ERROR"));
            assertFalse(captured.list.isEmpty());
            for (ILoggingEvent event : captured.list) {
                assertTrue(event.getFormattedMessage().matches("HTTP_COMPLETED requestId=[0-9a-f-]{36}"
                        + " method=OTHER status=500 durationMs=[0-9]+"));
                assertFalse(event.getFormattedMessage().contains(MARKER));
                assertNull(event.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    @Test
    void technicalDatabaseDownContractIsPreserved() throws Exception {
        // Substitution confinee au test, sans JDBC ni agent d'instrumentation.
        com.waka.operations.application.DatabaseHealthService database =
                new com.waka.operations.application.DatabaseHealthService(null) {
                    @Override
                    public boolean isAvailable() {
                        return false;
                    }
                };
        MockMvc databaseMvc = MockMvcBuilders.standaloneSetup(new DatabaseHealthController(database))
                .setControllerAdvice(new ApiExceptionHandler()).addFilters(new RequestDiagnosticsFilter()).build();
        databaseMvc.perform(get("/api/v1/health/database")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"status\":\"DOWN\"}"));
    }

    private void verifySafeBody(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains(MARKER));
        for (String forbidden : List.of("\"path\"", "\"trace\"", "\"exception\"", "\"errors\"")) {
            assertFalse(body.contains(forbidden));
        }
        String id = result.getResponse().getHeader("X-Request-Id");
        assertNotNull(id);
        UUID.fromString(id);
        assertTrue(body.contains("\"requestId\":\"" + id + "\""));
        String time = body.split("\"serverTime\":\"")[1].split("\"")[0];
        Instant.parse(time);
        assertTrue(time.endsWith("Z"));
    }

    @RestController
    static class ProbeController {
        @GetMapping("/unit-only/number")
        Map<String, Integer> number(@RequestParam("value") int value) {
            return Map.of("value", value);
        }

        @PostMapping("/unit-only/body")
        Map<String, Object> body(@RequestBody Map<String, Object> body) {
            return body;
        }

        @GetMapping("/unit-only/failure")
        void failure() {
            throw new IllegalStateException(MARKER);
        }
    }
}
