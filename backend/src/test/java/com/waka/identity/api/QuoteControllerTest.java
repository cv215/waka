package com.waka.identity.api;

import com.waka.geo.GeoPoint;
import com.waka.mobility.quote.PersistentQuoteService;
import com.waka.mobility.quote.QuoteException;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
import java.math.BigDecimal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of A08 (K20); persistence, replay and prices are covered by the opt-in MySQL test. */
class QuoteControllerTest {
    private final String client = UUID.randomUUID().toString(), token = "A".repeat(43);
    private final UUID key = UUID.randomUUID();
    private static final String BODY = "{\"mode\":\"vip\",\"passengerCount\":2,"
            + "\"origin\":{\"latitude\":3.848,\"longitude\":11.502},\"destination\":{\"latitude\":3.87,\"longitude\":11.52}}";

    @Test void noAssemblyFailsClosed() throws Exception {
        mvc(null).perform(quote(BODY)).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void clientPricesDistancesAndLooseNumbersAreRefusedBeforeTheService() throws Exception {
        var service = mock(PersistentQuoteService.class);
        MockMvc mvc = mvc(service);
        String point = "{\"latitude\":3.848,\"longitude\":11.502}";
        for (String body : new String[] {
                BODY.replace("}}", "},\"totalXaf\":100}"),
                BODY.replace("\"vip\"", "\"VIP\""),
                BODY.replace("\"passengerCount\":2", "\"passengerCount\":6"),
                BODY.replace("\"passengerCount\":2", "\"passengerCount\":\"2\""),
                BODY.replace("\"passengerCount\":2", "\"passengerCount\":2.0"),
                BODY.replace("3.848", "3.8481234"),
                BODY.replace("3.848", "3.848e0"),
                BODY.replace("3.848", "\"3.848\""),
                BODY.replace("3.848", "91"),
                BODY.replace("11.502", "-181"),
                BODY.replace("\"longitude\":11.502", "\"longitude\":11.502,\"distanceMetres\":10"),
                "{\"mode\":\"shared\",\"passengerCount\":1,\"origin\":" + point + "}",
                "[]" }) {
            mvc.perform(quote(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(withClient(post("/api/v1/quotes")).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/quotes").header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test void newQuoteIs201ReplayIs200AndPointsAreNotEchoed() throws Exception {
        var service = mock(PersistentQuoteService.class);
        Instant now = Instant.parse("2026-10-29T10:00:00Z");
        UUID id = UUID.randomUUID();
        var fresh = new PersistentQuoteService.QuoteView(id, "vip", 2, 7480, "launch-v1", "B09", 600, 7500, "XAF",
                now, now.plusSeconds(300), false, "_p~iF~ps|U_ulLnnqC");
        var replay = new PersistentQuoteService.QuoteView(id, "vip", 2, 7480, "launch-v1", "B09", 600, 7500, "XAF",
                now, now.plusSeconds(300), true, null);
        var origin = new GeoPoint(new BigDecimal("3.848"), new BigDecimal("11.502"));
        when(service.create(any(), eq(client), eq(key), eq(PersistentQuoteService.Mode.VIP), eq(2), eq(origin), any()))
                .thenReturn(fresh).thenReturn(replay);
        MockMvc mvc = mvc(service);
        mvc.perform(quote(BODY)).andExpect(status().isCreated()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.totalXaf").value(7500)).andExpect(jsonPath("$.placePriceXaf").value(600))
                .andExpect(jsonPath("$.origin").doesNotExist()).andExpect(jsonPath("$.route").value("_p~iF~ps|U_ulLnnqC"));
        mvc.perform(quote(BODY)).andExpect(status().isOk());
    }

    @Test void refusalsUseSafeDistinctCodes() throws Exception {
        var service = mock(PersistentQuoteService.class);
        when(service.create(any(), eq(client), eq(key), any(), anyInt(), any(), any()))
                .thenThrow(new QuoteException(QuoteException.Code.OUTSIDE_SERVICE_AREA))
                .thenThrow(new QuoteException(QuoteException.Code.DISTANCE_OUT_OF_RANGE))
                .thenThrow(new QuoteException(QuoteException.Code.ROUTE_UNAVAILABLE))
                .thenThrow(new QuoteException(QuoteException.Code.IDEMPOTENCY_KEY_REUSED));
        MockMvc mvc = mvc(service);
        mvc.perform(quote(BODY)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("OUTSIDE_SERVICE_AREA"));
        mvc.perform(quote(BODY)).andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("DISTANCE_OUT_OF_RANGE"));
        mvc.perform(quote(BODY)).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ROUTE_UNAVAILABLE"));
        mvc.perform(quote(BODY)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    private MockHttpServletRequestBuilder quote(String body) {
        return withClient(post("/api/v1/quotes")).header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }
    private MockHttpServletRequestBuilder withClient(MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(PersistentQuoteService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("quotes", service);
        return MockMvcBuilders.standaloneSetup(new QuoteController(beans.getBeanProvider(PersistentQuoteService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
