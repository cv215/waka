package com.waka.identity.sms.orange;

import com.waka.identity.otp.SmsGateway;
import com.waka.identity.otp.SmsMessage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.json.JsonFactory;

/** Candidat Orange CM, jamais enregistre comme bean ou fournisseur de repli. */
public final class OrangeSmsGateway implements SmsGateway, AutoCloseable {
    private static final URI PRODUCTION = URI.create("https://api.orange.com");
    private static final String SEND_PATH = "/smsmessaging/v1/outbound/tel%3A%2B2370000/requests";
    private static final JsonFactory JSON = new JsonFactory();
    private static final int MAX_RESPONSE = 8192;
    private final HttpClient http;
    private final URI base;
    private final String senderName;
    private final Clock clock;
    private final Duration deadline;
    private final char[] basic;
    private Token cached;
    private boolean closed;

    /** Le nom passe ici devra avoir ete reellement autorise avant tout cablage. */
    public OrangeSmsGateway(char[] clientId, char[] clientSecret, String approvedSenderName) {
        this(PRODUCTION, clientId, clientSecret, requireCustomSender(approvedSenderName),
                Clock.systemUTC(), Duration.ofSeconds(8));
    }

    /** Utilise le nom par defaut du compte, seulement s'il est reellement autorise au portail. */
    public static OrangeSmsGateway usingDefaultSender(char[] clientId, char[] clientSecret) {
        return new OrangeSmsGateway(PRODUCTION, clientId, clientSecret, null,
                Clock.systemUTC(), Duration.ofSeconds(8));
    }

    private static String requireCustomSender(String sender) {
        if (sender == null) { throw invalid(); }
        return sender;
    }

    // Point d'injection borne pour tests du vrai client HTTP ; aucune propriete externe ne le choisit.
    OrangeSmsGateway(URI base, char[] id, char[] secret, String sender, Clock clock, Duration deadline) {
        boolean localTest = base != null && "http".equals(base.getScheme())
                && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
        if (base == null || (!PRODUCTION.equals(base) && !localTest)
                || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || !base.getRawPath().isEmpty()
                || (sender != null && !sender.matches("[A-Za-z][A-Za-z0-9]{0,10}"))
                || clock == null || deadline == null || deadline.toMillis() < 50
                || deadline.compareTo(Duration.ofSeconds(8)) > 0) {
            throw invalid();
        }
        ascii(id, 200, true);
        ascii(secret, 500, false);
        byte[] raw = new byte[id.length + 1 + secret.length];
        byte[] encoded = null;
        try {
            for (int i = 0; i < id.length; i++) { raw[i] = (byte) id[i]; }
            raw[id.length] = ':';
            for (int i = 0; i < secret.length; i++) { raw[id.length + 1 + i] = (byte) secret[i]; }
            encoded = Base64.getEncoder().encode(raw);
            basic = new char[encoded.length];
            for (int i = 0; i < encoded.length; i++) { basic[i] = (char) encoded[i]; }
        } finally {
            Arrays.fill(raw, (byte) 0);
            if (encoded != null) { Arrays.fill(encoded, (byte) 0); }
        }
        this.base = base;
        this.senderName = sender;
        this.clock = clock;
        this.deadline = deadline;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    @Override
    public Submission send(SmsMessage message) {
        if (message == null) { return Submission.REJECTED; }
        char[] destination = null;
        char[] code = null;
        byte[] body = null;
        byte[] responseBody = null;
        boolean smsStarted = false;
        try {
            destination = message.destination();
            code = message.code();
            String phone = new String(destination);
            String digits = new String(code);
            if (!phone.matches("\\+2376[0-9]{8}") || !digits.matches("[0-9]{6}")) {
                return Submission.REJECTED;
            }
            String token = token();
            if (token == null) { return Submission.REJECTED; } // Aucun POST SMS n'a ete tente.
            // Tous les champs variables ont un alphabet ferme sans guillemet/controle.
            String payload = "{\"outboundSMSMessageRequest\":{\"address\":\"tel:" + phone
                    + "\",\"senderAddress\":\"tel:+2370000\""
                    + (senderName == null ? "" : ",\"senderName\":\"" + senderName + "\"")
                    + ",\"outboundSMSTextMessage\":{\"message\":\"Code WAKA : " + digits
                    + ". Valable 5 minutes. Ne le partagez pas.\"}}}";
            body = payload.getBytes(StandardCharsets.UTF_8);
            HttpRequest request = request(base.resolve(SEND_PATH))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            smsStarted = true;
            HttpResponse<byte[]> response = exchange(request);
            responseBody = response.body();
            if (response.statusCode() == 401) { invalidateToken(); }
            // Apres POST, ne pas assimiler un 4xx (408/409 compris) a une preuve de non-envoi.
            // En absence de preuve fournisseur definitive, conserver UNKNOWN, sans rejeu.
            if (response.statusCode() != 201 || !json(response)) { return Submission.UNKNOWN; }
            return created(responseBody) ? Submission.ACCEPTED : Submission.UNKNOWN;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return smsStarted ? Submission.UNKNOWN : Submission.REJECTED;
        } catch (Exception failure) {
            // Aucune cause, contenu, adresse, cle ou message fournisseur dans une sortie.
            return smsStarted ? Submission.UNKNOWN : Submission.REJECTED;
        } finally {
            if (destination != null) { Arrays.fill(destination, '\0'); }
            if (code != null) { Arrays.fill(code, '\0'); }
            if (body != null) { Arrays.fill(body, (byte) 0); }
            if (responseBody != null) { Arrays.fill(responseBody, (byte) 0); }
        }
    }

    private synchronized String token() throws Exception {
        if (closed) { return null; }
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.expires)) { return new String(cached.value); }
        invalidateToken();
        byte[] responseBody = null;
        try {
            HttpRequest request = request(base.resolve("/oauth/v3/token"))
                    .header("Authorization", "Basic " + new String(basic))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type=client_credentials")).build();
            HttpResponse<byte[]> response = exchange(request);
            responseBody = response.body();
            if (response.statusCode() != 200 || !json(response)) { return null; }
            String access = null;
            String type = null;
            int expires = 0;
            var fields = new HashSet<String>();
            try (JsonParser parser = JSON.createParser(responseBody)) {
                if (parser.nextToken() != JsonToken.START_OBJECT) { return null; }
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    String field = parser.getString();
                    if (!fields.add(field)) { return null; }
                    JsonToken value = parser.nextToken();
                    if ("access_token".equals(field) || "token_type".equals(field)) {
                        if (value != JsonToken.VALUE_STRING) { return null; }
                        if ("access_token".equals(field)) { access = parser.getString(); }
                        else { type = parser.getString(); }
                    } else if ("expires_in".equals(field)) {
                        if (value != JsonToken.VALUE_STRING && value != JsonToken.VALUE_NUMBER_INT) { return null; }
                        String raw = parser.getString();
                        if (raw == null || !raw.matches("[0-9]{1,4}")) { return null; }
                        expires = Integer.parseInt(raw);
                    } else { parser.skipChildren(); }
                }
                if (parser.currentToken() != JsonToken.END_OBJECT || parser.nextToken() != null) { return null; }
            }
            if (!"Bearer".equals(type) || access == null || access.length() > 4096
                    || !access.matches("[A-Za-z0-9._~+/-]+=*") || expires < 31 || expires > 3600) { return null; }
            cached = new Token(access.toCharArray(), now.plusSeconds(expires - 30));
            return access;
        } finally {
            if (responseBody != null) { Arrays.fill(responseBody, (byte) 0); }
        }
    }

    private synchronized void invalidateToken() {
        if (cached != null) { Arrays.fill(cached.value, '\0'); cached = null; }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(deadline).header("Accept", "application/json");
    }

    private HttpResponse<byte[]> exchange(HttpRequest request) throws Exception {
        var pending = http.sendAsync(request, ignored -> new BoundedBody());
        try { return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception failure) { pending.cancel(true); throw failure; }
    }

    private static boolean json(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").map(s ->
                "application/json".equalsIgnoreCase(s.split(";", 2)[0].trim())).orElse(false);
    }

    private static boolean created(byte[] body) throws IOException {
        boolean outbound = false;
        var fields = new HashSet<String>();
        try (JsonParser parser = JSON.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) { return false; }
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.getString();
                if (!fields.add(field) || "requestError".equals(field)) { return false; }
                JsonToken value = parser.nextToken();
                if ("outboundSMSMessageRequest".equals(field)) {
                    if (value != JsonToken.START_OBJECT) { return false; }
                    outbound = true;
                }
                parser.skipChildren();
            }
            return outbound && parser.currentToken() == JsonToken.END_OBJECT && parser.nextToken() == null;
        }
    }

    private static void ascii(char[] value, int max, boolean noColon) {
        if (value == null || value.length == 0 || value.length > max) { throw invalid(); }
        for (char c : value) {
            if (c < 33 || c > 126 || (noColon && c == ':')) { throw invalid(); }
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("INVALID_ORANGE_CONFIGURATION");
    }

    @Override public synchronized void close() {
        closed = true;
        Arrays.fill(basic, '\0');
        invalidateToken();
        http.shutdownNow();
    }

    @Override public String toString() { return "OrangeSmsGateway[credentials=REDACTED]"; }

    private static final class Token {
        private final char[] value;
        private final Instant expires;
        private Token(char[] value, Instant expires) { this.value = value; this.expires = expires; }
        @Override public String toString() { return "Token[REDACTED]"; }
    }

    /** Le delai couvre aussi le corps ; annule la souscription au premier octet excedentaire. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ErasableOutput output = new ErasableOutput();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (output.size() + chunk.remaining() > MAX_RESPONSE) {
                    subscription.cancel();
                    output.erase();
                    result.completeExceptionally(new IOException("SMS_RESPONSE_LIMIT"));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part);
                output.writeBytes(part);
                Arrays.fill(part, (byte) 0);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) {
            output.erase();
            result.completeExceptionally(new IOException("SMS_HTTP_FAILED"));
        }
        @Override public void onComplete() {
            byte[] bytes = output.toByteArray();
            output.erase();
            result.complete(bytes);
        }
    }

    private static final class ErasableOutput extends ByteArrayOutputStream {
        private void erase() { Arrays.fill(buf, (byte) 0); reset(); }
    }
}
