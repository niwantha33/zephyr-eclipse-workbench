// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Optional passive receiver for an ALREADY-CONFIGURED local RTT TCP endpoint.
 * Does not configure RTT, discover target memory, start OpenOCD or send commands.
 */
public final class RttTcpReader {
    private RttTcpReader() {}
    public static final int MAX_BYTES = 4 * 1024 * 1024;
    public record Capture(long bytes, boolean peerClosed) {}
    public static Capture capture(int port, int seconds, Path output, BooleanSupplier cancelled,
            Consumer<byte[]> consumer) throws IOException {
        if (port < 1024 || port > 65535 || seconds < 1 || seconds > 3600
                || output == null || cancelled == null || consumer == null)
            throw new IllegalArgumentException("Invalid local RTT capture settings");
        if (cancelled.getAsBoolean()) throw new InterruptedIOException("RTT capture cancelled before connect.");
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(250);
            try (var raw = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[4096];
                long total = 0;
                long deadline = System.nanoTime() + java.time.Duration.ofSeconds(seconds).toNanos();
                while (System.nanoTime() < deadline) {
                    if (cancelled.getAsBoolean()) throw new InterruptedIOException("RTT capture cancelled.");
                    int count;
                    try { count = socket.getInputStream().read(buffer); }
                    catch (SocketTimeoutException idle) { continue; }
                    if (count < 0) return new Capture(total, true);
                    if (count == 0) continue;
                    if (count > MAX_BYTES - total)
                        throw new IOException("RTT raw evidence exceeded bounded 4 MiB capture limit.");
                    raw.write(buffer, 0, count);
                    total += count;
                    consumer.accept(Arrays.copyOf(buffer, count));
                }
                return new Capture(total, false);
            }
        }
    }
}
