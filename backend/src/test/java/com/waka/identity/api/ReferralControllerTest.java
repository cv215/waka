package com.waka.identity.api;

import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import com.waka.referrals.PersistentReferralService;
import com.waka.referrals.ReferralException;
import java.time.Instant;
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

/** HTTP boundary of A34 (K22); the R05 rules themselves are covered by the opt-in MySQL test. */
class ReferralControllerTest {
    private final String client = UUID.randomUUID().toString(), token = "A".repeat(43);
    private final Instant now = Instant.parse("2026-10-29T10:00:00Z");

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(withClient(get("/api/v1/me/referral"))).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(link("{\"referralCode\":\"ABCDEFGH\"}")).andExpect(status().isServiceUnavailable());
    }

    @Test void malformedCodesAndExtraFieldsAreRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentReferralService.class);
        MockMvc mvc = mvc(service);
        for (String body : new String[] { "{\"referralCode\":\"abcdefgh\"}", "{\"referralCode\":\"ABCDEFG0\"}",
                "{\"referralCode\":\"ABCDEFGHJ\"}", "{\"referralCode\":\"ABCDEFGH\",\"referrerAccountId\":\"x\"}", "{}" }) {
            mvc.perform(link(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/me/referral")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void firstAttachmentIs201SameCodeAgainIs200AndRefusalsAreDistinct() throws Exception {
        var service = mock(PersistentReferralService.class);
        when(service.attach(any(), eq(client), eq("ABCDEFGH")))
                .thenReturn(new PersistentReferralService.LinkageView(now, true))
                .thenReturn(new PersistentReferralService.LinkageView(now, false))
                .thenThrow(new ReferralException(ReferralException.Code.SELF_REFERRAL))
                .thenThrow(new ReferralException(ReferralException.Code.CODE_NOT_RECOGNIZED))
                .thenThrow(new ReferralException(ReferralException.Code.REFERRER_ALREADY_SET));
        when(service.mine(any(), eq(client))).thenReturn(new PersistentReferralService.ReferralView("ABCDEFGH", false, null));
        MockMvc mvc = mvc(service);
        String body = "{\"referralCode\":\"ABCDEFGH\"}";
        mvc.perform(link(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.referrerAccountId").doesNotExist());
        mvc.perform(link(body)).andExpect(status().isOk());
        mvc.perform(link(body)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("SELF_REFERRAL"));
        mvc.perform(link(body)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("CODE_NOT_RECOGNIZED"));
        mvc.perform(link(body)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REFERRER_ALREADY_SET"));
        mvc.perform(withClient(get("/api/v1/me/referral"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.referralCode").value("ABCDEFGH")).andExpect(jsonPath("$.referrerAttached").value(false));
    }

    private MockHttpServletRequestBuilder link(String body) {
        return withClient(post("/api/v1/me/referral-linkages")).contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(PersistentReferralService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("referrals", service);
        return MockMvcBuilders.standaloneSetup(new ReferralController(beans.getBeanProvider(PersistentReferralService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
