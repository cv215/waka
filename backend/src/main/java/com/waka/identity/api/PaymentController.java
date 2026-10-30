package com.waka.identity.api;

import com.waka.finance.PaymentException;
import com.waka.finance.PaymentService;
import com.waka.identity.session.SessionException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
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
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** R14 (K33): payments, the WAKA credit, withdrawals and the provider's signed notifications. Amounts of services are the server's. */
@RestController
public final class PaymentController {
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_NOTIFICATION = 65536;
    private final ObjectProvider<PaymentService> services;
    public PaymentController(ObjectProvider<PaymentService> services) { this.services = services; }

    /** Body {purpose, targetId?, method, phone?, amountXaf?}; Idempotency-Key. */
    @PostMapping(path = "/api/v1/payments", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentService.PaymentView> pay(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        Map<String, Object> body = read(request, Set.of("purpose", "method"), Set.of("targetId", "phone", "amountXaf"));
        UUID target = body.containsKey("targetId") ? LoginRequestReader.uuid((String) body.get("targetId")) : null;
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().pay(token, client, key, (String) body.get("purpose"), target, (String) body.get("method"),
                    (String) body.get("phone"), (Integer) body.get("amountXaf")));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/payments/{paymentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentService.PaymentView> payment(@PathVariable String paymentId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(paymentId);
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().read(token, client, id));
    }

    @GetMapping(path = "/api/v1/wallet", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentService.WalletView> wallet(HttpServletRequest request) throws IOException {
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().wallet(token, client));
    }

    /** Body {pocket: credit|earnings, amountXaf, method, phone}: only to the account's own verified number; Idempotency-Key. */
    @PostMapping(path = "/api/v1/withdrawals", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentService.WithdrawalView> withdraw(HttpServletRequest request) throws IOException {
        UUID key = LoginRequestReader.key(request);
        Map<String, Object> body = read(request, Set.of("pocket", "amountXaf", "method", "phone"), Set.of());
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            var view = invoke(() -> service().withdraw(token, client, key, (String) body.get("pocket"), (Integer) body.get("amountXaf"), (String) body.get("method"),
                    (String) body.get("phone")));
            return ResponseEntity.status(view.idempotentReplay() ? HttpStatus.OK : HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(view);
        } finally { Arrays.fill(token, '\0'); }
    }

    @GetMapping(path = "/api/v1/withdrawals/{withdrawalId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PaymentService.WithdrawalView> withdrawal(@PathVariable String withdrawalId, HttpServletRequest request) throws IOException {
        UUID id = LoginRequestReader.uuid(withdrawalId);
        MeRequestReader.emptyGet(request);
        return call(request, (token, client) -> service().readWithdrawal(token, client, id));
    }

    /** From the provider, proved by its own header (Fapshi: x-wh-secret); no session. The body is only a reason to look the status up again. */
    @PostMapping(path = "/api/v1/payment-notifications")
    public ResponseEntity<Void> notification(HttpServletRequest request) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] body = request.getInputStream().readNBytes(MAX_NOTIFICATION + 1);
        if (body.length > MAX_NOTIFICATION) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);

        invoke(() -> { service().notification(body, request::getHeader); return null; });
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private interface Call<T> { T apply(char[] token, String client); }
    private <T> ResponseEntity<T> call(HttpServletRequest request, Call<T> call) {
        char[] token = MeRequestReader.bearer(request);
        try {
            String client = MeRequestReader.client(request);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(invoke(() -> call.apply(token, client)));
        } finally { Arrays.fill(token, '\0'); }
    }

    /** Exact fields: strings, and amountXaf as a plain positive integer of at most seven digits. */
    private static Map<String, Object> read(HttpServletRequest request, Set<String> required, Set<String> optional) throws IOException {
        if (request.getQueryString() != null) throw LoginRequestReader.invalid();
        byte[] bytes = request.getInputStream().readNBytes(1025);
        try {
            if (bytes.length > 1024) throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE);
            try (JsonParser parser = JSON.createParser(new InputStreamReader(new ByteArrayInputStream(bytes),
                    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)))) {
                if (parser.nextToken() != JsonToken.START_OBJECT) throw LoginRequestReader.invalid();
                Map<String, Object> fields = new HashMap<>();
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String name = parser.getString();
                    JsonToken value = parser.nextToken();
                    if (!(required.contains(name) || optional.contains(name)) || fields.containsKey(name)) throw LoginRequestReader.invalid();
                    if ("amountXaf".equals(name)) {
                        if (value != JsonToken.VALUE_NUMBER_INT || !parser.getText().matches("[1-9][0-9]{0,6}")) throw LoginRequestReader.invalid();
                        fields.put(name, Integer.valueOf(parser.getText()));
                    } else if (value == JsonToken.VALUE_STRING && parser.getString().length() <= 64) {
                        fields.put(name, parser.getString());
                    } else {
                        throw LoginRequestReader.invalid();
                    }
                }
                if (!fields.keySet().containsAll(required) || parser.nextToken() != null) throw LoginRequestReader.invalid();
                return fields;
            } catch (JacksonException hidden) {
                throw LoginRequestReader.invalid();
            }
        } finally { Arrays.fill(bytes, (byte) 0); }
    }

    private PaymentService service() {
        var service = services.getIfAvailable();
        if (service == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return service;
    }
    private static <T> T invoke(Supplier<T> operation) {
        try { return operation.get(); }
        catch (SessionException error) {
            throw new ResponseStatusException(error.code() == SessionException.Code.SERVICE_UNAVAILABLE
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNAUTHORIZED);
        } catch (PaymentException error) {
            HttpStatus status = switch (error.code()) {
                case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
                case NOT_FOUND -> HttpStatus.NOT_FOUND;
                case NOT_PAYABLE, INSUFFICIENT_CREDIT, CREDIT_LIMIT -> HttpStatus.CONFLICT;
                case PHONE_NOT_ACCOUNT -> HttpStatus.UNPROCESSABLE_CONTENT;
                case NOT_AUTHENTIC -> HttpStatus.UNAUTHORIZED;
                case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            throw new ProfileHttpException(status, error.code() == PaymentException.Code.NOT_FOUND ? "RESOURCE_NOT_FOUND" : error.code().name());
        }
    }
}
