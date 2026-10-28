package com.waka.identity.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Tests MVC en memoire ; aucun serveur reseau, compte, SMS ni connexion MySQL. */
class PhoneFormatControllerTest {
    private static final String ROUTE = "/api/v1/identity/phone-format";
    private static final String NATIONAL = "6" + "0".repeat(8);
    private static final String BODY = "{\"phoneNumber\":\"" + NATIONAL + "\"}";
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new PhoneFormatController())
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters((request, response, chain) -> {
                    if ("chunked".equals(((HttpServletRequest) request).getHeader("Transfer-Encoding"))) {
                        chain.doFilter(new HttpServletRequestWrapper((HttpServletRequest) request) {
                            @Override public int getContentLength() { return -1; }
                            @Override public long getContentLengthLong() { return -1; }
                        }, response);
                    } else {
                        chain.doFilter(request, response);
                    }
                }, new RequestDiagnosticsFilter()).build();
    }

    @Test
    void validationHasNoAccountOwnershipOrContactAndNeedsNoIdempotencyKey() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult result = mvc.perform(json(BODY)).andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().json("{\"formatValid\":true,\"ownershipVerified\":false,\"country\":\"CM\"}",
                            org.springframework.test.json.JsonCompareMode.STRICT))
                    .andReturn();
            assertFalse(result.getResponse().getContentAsString().contains(NATIONAL));
            assertFalse(result.getResponse().containsHeader("Set-Cookie"));
        }
    }

    @Test
    void malformedDuplicateUnknownAndWrongTypedInputsFailWithoutEcho() throws Exception {
        for (String body : List.of("", "null", "[]", "{}", "{", BODY + "{}", BODY + "garbage",
                "{\"phoneNumber\":null}", "{\"phoneNumber\":123}", "{\"phoneNumber\":true}",
                "{\"phoneNumber\":[]}", "{\"phoneNumber\":{}}", "{\"other\":\"" + NATIONAL + "\"}",
                BODY.substring(0, BODY.length() - 1) + ",\"other\":true}",
                BODY.substring(0, BODY.length() - 1) + ",\"phoneNumber\":\"" + NATIONAL + "\"}",
                "{\"phoneNumber\":\"" + NATIONAL + "EXT\"}", "{\"phoneNumber\":\"6\\t" + "0".repeat(8) + "\"}",
                "{\"phoneNumber\":\"" + " ".repeat(32) + NATIONAL + "\"}")) {
            MvcResult result = mvc.perform(json(body)).andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andReturn();
            assertFalse(result.getResponse().getContentAsString().contains(NATIONAL));
        }
    }

    @Test
    void enforcesActualBodyByteLimitIncludingUnknownLengthChunkedRequests() throws Exception {
        String atLimit = BODY + " ".repeat(512 - BODY.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(json(atLimit)).andExpect(status().isOk());
        mvc.perform(json(atLimit + " ")).andExpect(status().isContentTooLarge());
        // Filtre de test : longueur inconnue. Aucun decodage reseau chunked reel dans MockMvc.
        mvc.perform(json(atLimit + " ").header("Transfer-Encoding", "chunked")
                        .with(request -> {
                            request.removeHeader("Content-Length");
                            return request;
                        }))
                .andExpect(status().isContentTooLarge())
                .andExpect(header().string("Cache-Control", "no-store"));
        // Character count below 512 but UTF-8 byte count exceeds it.
        mvc.perform(json(BODY + "\u00a0".repeat(250))).andExpect(status().isContentTooLarge());
    }

    @Test
    void acceptsDocumentedSpacingAndRejectsNonJson() throws Exception {
        for (String separator : List.of("\u00a0", "\u202f")) {
            mvc.perform(json("{\"phoneNumber\":\"+237" + separator + NATIONAL + "\"}"))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(ROUTE).contentType(MediaType.TEXT_PLAIN).content(BODY))
                .andExpect(status().isUnsupportedMediaType());
    }

    private MockHttpServletRequestBuilder json(String body) {
        return post(ROUTE).contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
    }
}
