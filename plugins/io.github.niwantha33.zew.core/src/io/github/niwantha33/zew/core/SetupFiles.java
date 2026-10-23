// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Bootstrap downloads need no Python, package manager, admin rights or shell. */
public final class SetupFiles {
    private SetupFiles() {}
    public static boolean matches(Path file, long size, String digest) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) != size) return false;
        try (var input = Files.newInputStream(file)) {
            var hash = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[65536]; int count;
            while ((count = input.read(block)) != -1) hash.update(block, 0, count);
            return HexFormat.of().formatHex(hash.digest()).equals(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    public static Path download(URI uri, Path file, long size, String digest, BooleanSupplier cancel,
            Consumer<String> progress) throws Exception {
        if (!"https".equals(uri.getScheme()) || uri.getUserInfo() != null || size <= 0 || size > 536870912L
                || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid pinned bootstrap artifact");
        if (cancel.getAsBoolean()) throw new InterruptedIOException("Cancelled");
        if (matches(file, size, digest)) { progress.accept("Using verified cached " + file.getFileName()); return file; }
        Files.createDirectories(file.getParent());
        Path partial = file.resolveSibling(file.getFileName() + ".partial");
        try (var output = FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var subscriber = new HttpResponse.BodySubscriber<Void>() {
                final CompletableFuture<Void> body = new CompletableFuture<>();
                Flow.Subscription subscription; long count; long nextReport;
                @Override public java.util.concurrent.CompletionStage<Void> getBody() { return body; }
                @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
                @Override public void onNext(List<ByteBuffer> values) {
                    try {
                        for (var value : values) {
                            count += value.remaining(); if (count > size) throw new IOException("Download exceeds declared size");
                            while (value.hasRemaining()) output.write(value);
                        }
                        if (count >= nextReport) { progress.accept(file.getFileName() + ": " + count + " / " + size + " bytes"); nextReport = count + 1048576; }
                        subscription.request(1);
                    } catch (Exception error) { subscription.cancel(); body.completeExceptionally(error); }
                }
                @Override public void onError(Throwable error) { body.completeExceptionally(error); }
                @Override public void onComplete() { body.complete(null); }
            };
            try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()) {
                var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).header("User-Agent", "Zephyr-Eclipse-Workbench-Setup").GET().build();
                var future = client.sendAsync(request, response -> {
                    if (response.statusCode() != 200) throw new IllegalStateException("Download HTTP " + response.statusCode());
                    return subscriber;
                });
                long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
                try {
                    for (;;) {
                        if (cancel.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                        if (System.nanoTime() > deadline) throw new IOException("Download timed out; retry to reuse completed files");
                        try { future.get(100, TimeUnit.MILLISECONDS); break; } catch (TimeoutException waiting) { /* Remain cancellable. */ }
                    }
                } finally { if (!future.isDone()) future.cancel(true); }
            }
        } catch (Exception error) { Files.deleteIfExists(partial); throw error; }
        if (!matches(partial, size, digest)) { Files.deleteIfExists(partial); throw new IOException("Download size or SHA-256 mismatch: " + file.getFileName()); }
        Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        return file;
    }
    public static Path safePath(Path directory, String entry) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (entry.indexOf('\\') >= 0 || entry.indexOf(':') >= 0 || entry.startsWith("/")) throw new IOException("Unsafe archive entry");
        Path path = root.resolve(entry).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IOException("Unsafe archive path");
        for (Path current = path; current != null && current.startsWith(root); current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Archive path contains a symbolic link");
        return path;
    }
    public static void unzip(Path archive, Path destination, BooleanSupplier cancel) throws IOException {
        Files.createDirectories(destination);
        long total = 0; int count = 0;
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            java.util.zip.ZipEntry entry; byte[] block = new byte[65536];
            while ((entry = input.getNextEntry()) != null) {
                if (cancel.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                if (++count > 50000) throw new IOException("Archive file count exceeds limit");
                Path target = safePath(destination, entry.getName());
                if (Files.isSymbolicLink(target)) throw new IOException("Archive target is a symbolic link");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                try (var output = Files.newOutputStream(target)) {
                    int bytes;
                    while ((bytes = input.read(block)) != -1) {
                        if (cancel.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                        total += bytes; if (total > 2147483648L) throw new IOException("Archive expanded size exceeds limit");
                        output.write(block, 0, bytes);
                    }
                }
            }
        }
    }
}
