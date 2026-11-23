package com.waka.identity.sms;

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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * The HTTP plumbing shared by the SMS providers: no proxy, no redirect, 3 s to connect, one deadline that also covers
 * the body, and a response capped at 8 KiB and erased once read. Nothing here logs a header, body or address.
 */
public final class SmsHttp implements AutoCloseable {
    public static final int MAX_RESPONSE = 8192;
    private final HttpClient http;
    private final Duration deadline;

    public SmsHttp(Duration deadline) {
        if (deadline == null || deadline.toMillis() < 50 || deadline.compareTo(Duration.ofSeconds(8)) > 0) {
            throw new IllegalArgumentException("INVALID_SMS_DEADLINE");
        }
        this.deadline = deadline;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
                }).build();
    }

    public Duration deadline() { return deadline; }

    /** A base is either the given https origin or a loopback http origin for tests; never a path, query or user. */
    public static boolean plainOrigin(URI base) {
        return base != null && base.getRawUserInfo() == null && base.getRawQuery() == null
                && base.getRawFragment() == null && base.getRawPath() != null && base.getRawPath().isEmpty();
    }

    public static boolean loopback(URI base) {
        return base != null && "http".equals(base.getScheme()) && "127.0.0.1".equals(base.getHost()) && base.getPort() > 0;
    }

    public HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(deadline).header("Accept", "application/json");
    }

    public HttpResponse<byte[]> exchange(HttpRequest request) throws Exception {
        var pending = http.sendAsync(request, ignored -> new BoundedBody());
        try { return pending.get(deadline.toMillis(), TimeUnit.MILLISECONDS); }
        catch (Exception failure) { pending.cancel(true); throw failure; }
    }

    public static boolean json(HttpResponse<?> response) {
        return response.headers().firstValue("Content-Type").map(s ->
                "application/json".equalsIgnoreCase(s.split(";", 2)[0].trim())).orElse(false);
    }

    @Override public void close() { http.shutdownNow(); }

    /** The deadline also covers the body; the subscription is cancelled at the first byte over the limit. */
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
