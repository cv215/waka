package com.waka.identity.api;

import com.waka.identity.files.FileException;
import com.waka.identity.files.VehiclePhotoService;
import com.waka.operations.api.ApiExceptionHandler;
import com.waka.operations.api.RequestDiagnosticsFilter;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP boundary of K16; storage, isolation and replay are covered by the opt-in MySQL test. */
class VehiclePhotoControllerTest {
    private final String client = UUID.randomUUID().toString();
    private final String token = "A".repeat(43);
    private final UUID application = UUID.randomUUID(), file = UUID.randomUUID();
    private final byte[] body = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3 };

    @Test void noAssemblyFailsClosed() throws Exception {
        MockMvc mvc = mvc(null);
        mvc.perform(upload("PH01", "image/jpeg")).andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(read("/api/v1/private-files/" + file + "/content")).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/private-files/" + file + "/content")).andExpect(status().isUnauthorized());
    }

    @Test void boundaryRefusalsHappenBeforeTheService() throws Exception {
        var service = mock(VehiclePhotoService.class);
        MockMvc mvc = mvc(service);
        mvc.perform(upload("PH07", "image/jpeg")).andExpect(status().isBadRequest());
        mvc.perform(upload("ph01", "image/jpeg")).andExpect(status().isBadRequest());
        mvc.perform(upload("PH01", "image/gif")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(upload("PH01", "application/octet-stream")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(upload("PH01", "image/jpeg").header("Content-Encoding", "gzip")).andExpect(status().isUnsupportedMediaType());
        mvc.perform(upload("PH01", "image/jpeg").queryParam("accountId", UUID.randomUUID().toString())).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/driver-applications/" + application + "/photos/PH01").contentType("image/jpeg").content(body)
                .header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)).andExpect(status().isBadRequest());
        mvc.perform(upload("PH01", "image/jpeg").content(new byte[10 * 1024 * 1024 + 1])).andExpect(status().isPayloadTooLarge());
        mvc.perform(read("/api/v1/private-files/not-a-uuid/content")).andExpect(status().isBadRequest());
        mvc.perform(read("/api/v1/driver-applications/" + application + "/photos").content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void contentIsServedPrivatelyAndRefusalsAreSafe() throws Exception {
        var service = mock(VehiclePhotoService.class);
        when(service.content(any(), eq(client), eq(file))).thenReturn(new VehiclePhotoService.Content(body))
                .thenThrow(new FileException(FileException.Code.NOT_FOUND));
        when(service.upload(any(), eq(client), eq(application), eq("PH02"), any(UUID.class), eq("image/png"), any()))
                .thenThrow(new FileException(FileException.Code.INVALID_FILE))
                .thenThrow(new FileException(FileException.Code.IDEMPOTENCY_KEY_REUSED))
                .thenThrow(new FileException(FileException.Code.NOT_EDITABLE));
        MockMvc mvc = mvc(service);
        mvc.perform(read("/api/v1/private-files/" + file + "/content")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(content().bytes(body));
        mvc.perform(read("/api/v1/private-files/" + file + "/content")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mvc.perform(upload("PH02", "image/png")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_REJECTED"));
        mvc.perform(upload("PH02", "image/png")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(upload("PH02", "image/png")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOT_EDITABLE"));
    }

    private MockHttpServletRequestBuilder upload(String view, String type) {
        return post("/api/v1/driver-applications/" + application + "/photos/" + view).contentType(type).content(body)
                .header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client)
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }
    private MockHttpServletRequestBuilder read(String path) {
        return get(path).header("Authorization", "Bearer " + token).header("X-Waka-Client-Instance", client);
    }
    private static MockMvc mvc(VehiclePhotoService service) {
        var beans = new DefaultListableBeanFactory();
        if (service != null) beans.registerSingleton("photos", service);
        return MockMvcBuilders.standaloneSetup(new VehiclePhotoController(beans.getBeanProvider(VehiclePhotoService.class)))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new RequestDiagnosticsFilter()).build();
    }
}
