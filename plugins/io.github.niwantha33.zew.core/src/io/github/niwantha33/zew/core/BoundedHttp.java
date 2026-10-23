// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Bounded asynchronous transfer. Credentials are never forwarded across redirects. */
public final class BoundedHttp {
    private final HttpClient client;
    public BoundedHttp() {
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(15)).build();
    }
    public byte[] get(URI uri, String token, int limit, Duration timeout, BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (limit < 1 || limit > 8_388_608 || timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Invalid transfer bounds.");
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("Update cancelled.");
        if (token != null && token.chars().anyMatch(c -> c <= 32 || c >= 127)) throw new IOException("Invalid update credential format. Reconfigure the connection.");
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Accept", "application/vnd.github.raw+json")
                .header("User-Agent", "Zephyr-Eclipse-Workbench");
        if (token != null && !token.isBlank()) request.header("Authorization", "Bearer " + token);
        var future = client.sendAsync(request.GET().build(), info -> new LimitedBody(limit));
        long start = System.nanoTime();
        try {
            while (true) {
                if (cancelled.getAsBoolean()) throw new InterruptedIOException("Update cancelled.");
                if (System.nanoTime() - start > timeout.toNanos()) throw new IOException("Update download timed out.");
                try {
                    var response = future.get(100, TimeUnit.MILLISECONDS);
                    return switch (response.statusCode()) {
                        case 200 -> response.body();
                        case 401 -> throw new IOException("GitHub rejected the update credential. Configure a valid repository read-only token.");
                        case 403 -> throw new IOException("GitHub denied update access or its request limit was reached. Check repository permissions and retry later.");
                        case 404 -> throw new IOException("Private update channel is unavailable. Check repository read access and whether a release has been published.");
                        default -> throw new IOException("Update server returned HTTP " + response.statusCode() + ". No redirect or error content was installed.");
                    };
                } catch (TimeoutException pending) { /* Keep checking cancellation while headers/body arrive. */ }
                catch (ExecutionException error) { throw new IOException("Update transfer failed. Check connectivity, certificates and the configured proxy.", error.getCause()); }
            }
        } finally { if (!future.isDone()) future.cancel(true); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Download exceeds its size limit.")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
