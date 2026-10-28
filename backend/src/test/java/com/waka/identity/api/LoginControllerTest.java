package com.waka.identity.api;

import com.waka.identity.application.LoginService;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Negative HTTP-boundary tests, no configured service/database/SMS. Successes use real cores in SQL tests. */
class LoginControllerTest {
    private static final String REQUEST = "/api/v1/auth/phone-challenges";
    private final String client = UUID.randomUUID().toString();
    private final String phone = "6" + "0".repeat(8);
    private final String challenge = UUID.randomUUID().toString();
    private final String key = UUID.randomUUID().toString();
    private MockMvc mvc;

    @BeforeEach void setup() {
        var beans = new DefaultListableBeanFactory();
        mvc = MockMvcBuilders.standaloneSetup(new LoginController(beans.getBeanProvider(LoginService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters((request, response, chain) -> {
                    if ("chunked".equals(((HttpServletRequest) request).getHeader("Transfer-Encoding"))) {
                        chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                            @Override public int getContentLength() { return -1; }
                            @Override public long getContentLengthLong() { return -1; }
                        }, response);
                    } else chain.doFilter(request, response);
                }, new RequestDiagnosticsFilter()).build();
    }

    @Test void absentServiceFailsClosedForEveryCommandAndDoesNotExposePrivateInput() throws Exception {
        var verify = "{\"phoneNumber\":\"" + phone + "\",\"clientInstanceRef\":\"" + client + "\",\"code\":\"135790\"}";
        for (var command : List.of(json(REQUEST, body()), json(REQUEST + "/" + challenge + "/verifications", verify),
                post("/api/v1/auth/sessions/" + challenge + "/revocations")
                        .header("Idempotency-Key", key).header("X-Waka-Client-Instance", client)
                        .header("Authorization", "Bearer " + "A".repeat(43)))) {
            var result = mvc.perform(command).andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE")).andReturn();
            String response = result.getResponse().getContentAsString();
            assertFalse(response.contains(phone)); assertFalse(response.contains("135790"));
            assertFalse(response.contains(client)); assertFalse(response.contains("A".repeat(43)));
            assertFalse(result.getResponse().containsHeader("Set-Cookie"));
        }
    }

    @Test void ambiguousMalformedAndPrivilegeInputsAreRefusedBeforeTheService() throws Exception {
        for (String invalid : List.of("", "null", "[]", "{}", "{", body() + "{}", body() + "tail",
                body().replace("\"login\"", "\"admin\""), body().replace("\"login\"", "true"),
                body().replace("\"login\"", "null"), body().replace(phone, "invalid"),
                body().replace(client, "short"), body().replace(client, "x".repeat(129)),
                body().substring(0, body().length() - 1) + ",\"purpose\":\"login\"}",
                body().substring(0, body().length() - 1) + ",\"role\":\"ADMIN\"}")) {
            mvc.perform(json(REQUEST, invalid)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        for (String invalidCode : List.of("12345", "1234567", "12a456", "１２３４５６")) {
            mvc.perform(json(REQUEST + "/" + challenge + "/verifications",
                    "{\"phoneNumber\":\"" + phone + "\",\"clientInstanceRef\":\"" + client + "\",\"code\":\"" + invalidCode + "\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void actualBytesAreBoundedIncludingUnknownLengthAndInvalidEncoding() throws Exception {
        String boundary = body() + " ".repeat(1024 - body().getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(json(REQUEST, boundary)).andExpect(status().isServiceUnavailable());
        mvc.perform(json(REQUEST, boundary + " ")).andExpect(status().isContentTooLarge());
        mvc.perform(json(REQUEST, boundary + " ").header("Transfer-Encoding", "chunked"))
                .andExpect(status().isContentTooLarge());
        mvc.perform(json(REQUEST, body() + "é".repeat(500))).andExpect(status().isContentTooLarge());
        mvc.perform(json(REQUEST, body()).header("Content-Encoding", "gzip")).andExpect(status().isBadRequest());
        mvc.perform(post(REQUEST).contentType("application/json;charset=UTF-16").header("Idempotency-Key", key)
                .content(body().getBytes(StandardCharsets.UTF_16))).andExpect(status().isBadRequest());
        mvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content(body().getBytes(StandardCharsets.UTF_16))).andExpect(status().isBadRequest());
    }

    @Test void keysHeadersAndUrlsCannotBypassTheContract() throws Exception {
        mvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isBadRequest());
        mvc.perform(json(REQUEST, body()).header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest());
        mvc.perform(post(REQUEST).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", "1-1-1-1-1")
                .content(body())).andExpect(status().isBadRequest());
        mvc.perform(json(REQUEST, body()).queryParam("code", "135790")).andExpect(status().isBadRequest());
        mvc.perform(json(REQUEST + "/1-1-1-1-1/verifications", "{}")).andExpect(status().isBadRequest());
        mvc.perform(post(REQUEST).contentType(MediaType.TEXT_PLAIN).content(body())).andExpect(status().isUnsupportedMediaType());
        mvc.perform(get(REQUEST)).andExpect(status().isMethodNotAllowed());
        String logout = "/api/v1/auth/sessions/" + challenge + "/revocations";
        mvc.perform(post(logout).header("Idempotency-Key", key).header("X-Waka-Client-Instance", client)
                        .header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
        mvc.perform(post(logout).header("Idempotency-Key", key).header("X-Waka-Client-Instance", client)
                        .header("Authorization", "Bearer " + "A".repeat(43)).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(logout).header("Idempotency-Key", key).header("X-Waka-Client-Instance", client, client)
                        .header("Authorization", "Bearer " + "A".repeat(43)))
                .andExpect(status().isBadRequest());
    }

    private String body() {
        return "{\"phoneNumber\":\"" + phone + "\",\"purpose\":\"login\",\"clientInstanceRef\":\"" + client + "\"}";
    }
    private MockHttpServletRequestBuilder json(String route, String body) {
        return post(route).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key", key)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }
}
