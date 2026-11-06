// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.RttTcpReader;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RttTcpReaderTests {
    private static void yes(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        try { RttTcpReader.capture(80, 2, null, () -> false, b -> {}); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { /* nonlocal/reserved ports rejected */ }
        var evidence = Files.createTempFile("zew-rtt-", ".bin");
        Files.delete(evidence);
        try (var server = new ServerSocket(0); var pool = Executors.newSingleThreadExecutor()) {
            var served = pool.submit(() -> {
                try (var peer = server.accept()) {
                    peer.getOutputStream().write(new byte[] {65, 0, 66, (byte) 255});
                    peer.getOutputStream().flush();
                } catch (IOException error) { throw new RuntimeException(error); }
            });
            AtomicInteger count = new AtomicInteger();
            var capture = RttTcpReader.capture(server.getLocalPort(), 3, evidence, () -> false,
                    bytes -> count.addAndGet(bytes.length));
            served.get(5, TimeUnit.SECONDS);
            yes(capture.bytes() == 4 && capture.peerClosed() && count.get() == 4);
            yes(java.util.Arrays.equals(Files.readAllBytes(evidence),
                    new byte[] {65, 0, 66, (byte) 255}), "raw bytes conserved");
            try { RttTcpReader.capture(server.getLocalPort(), 1, evidence, () -> false, b -> {});
                throw new AssertionError("existing output overwritten");
            } catch (IOException expected) { /* refused or exclusive output, never overwrite */ }
            try { RttTcpReader.capture(server.getLocalPort(), 1, evidence, () -> true, b -> {});
                throw new AssertionError("cancel ignored");
            } catch (java.io.InterruptedIOException expected) { /* cancelled before connect */ }
        } finally { Files.deleteIfExists(evidence); }
        System.out.println("RttTcpReaderTests PASS");
    }
}
