package com.waka.identity.api;

import com.waka.finance.FinanceAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** K34: FINANCE reads the ledger totals and settles, after checking the provider, the cases a machine cannot. */
@RestController
public final class AdminFinanceController {
    private final ObjectProvider<FinanceAdminService> finance;
    public AdminFinanceController(ObjectProvider<FinanceAdminService> finance) { this.finance = finance; }

    @GetMapping(path = "/api/v1/admin/finance/overview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinanceAdminService.Overview> overview(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        return call(request, (token, client, admin) -> service().overview(token, client, admin));
    }

    @GetMapping(path = "/api/v1/admin/finance/cases", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<FinanceAdminService.Case>> cases(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        return call(request, (token, client, admin) -> service().cases(token, client, admin));
    }

    /** Body {outcome: received|not_received, receivedXaf: digits as text ("0" when not received), reasonCode}. */
    @PostMapping(path = "/api/v1/admin/payments/{paymentId}/resolutions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinanceAdminService.Case> resolvePayment(@PathVariable String paymentId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(paymentId);
        Map<String, String> body = LoginRequestReader.body(request, Set.of("outcome", "receivedXaf", "reasonCode"));
        String received = body.get("receivedXaf");
        if (received == null || !received.matches("0|[1-9][0-9]{0,6}")) throw LoginRequestReader.invalid();
        return call(request, (token, client, admin) -> service().resolvePayment(token, client, admin, id, body.get("outcome"),
                Long.parseLong(received), body.get("reasonCode")));
    }

    /** Body {outcome: sent|not_sent, reasonCode}. */
    @PostMapping(path = "/api/v1/admin/withdrawals/{withdrawalId}/resolutions", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<FinanceAdminService.Case> resolveWithdrawal(@PathVariable String withdrawalId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(withdrawalId);
        Map<String, String> body = LoginRequestReader.body(request, Set.of("outcome", "reasonCode"));
        return call(request, (token, client, admin) -> service().resolveWithdrawal(token, client, admin, id, body.get("outcome"), body.get("reasonCode")));
    }

    private interface Call<T> { T apply(char[] token, String client, char[] adminToken); }
    private static <T> ResponseEntity<T> call(HttpServletRequest request, Call<T> call) {
        char[] token = MeRequestReader.bearer(request), adminToken = AdminAccessController.adminToken(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(AdminAccessController.invoke(() -> call.apply(token, client, adminToken)));
        } finally { Arrays.fill(token, '\0'); Arrays.fill(adminToken, '\0'); }
    }
    private FinanceAdminService service() {
        var service = finance.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
}
