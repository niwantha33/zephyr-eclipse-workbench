// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.OpenOcdThreadCheck;
import io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public final class OpenOcdThreadCheckTests {
    private static int checks;
    private static void yes(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static String exercise(List<String> replies, List<Region> regions, List<String> commands) throws Exception {
        try (var server = new ServerSocket(0); var worker = Executors.newSingleThreadExecutor()) {
            var served = worker.submit(() -> {
                try (var peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    for (String reply : replies) {
                        var command = new StringBuilder(); int b;
                        while ((b = peer.getInputStream().read()) != 26) { if (b < 0) return; command.append((char) b); }
                        commands.add(command.toString());
                        peer.getOutputStream().write((reply + '\u001a').getBytes(StandardCharsets.US_ASCII));
                        peer.getOutputStream().flush();
                    }
                    yes(peer.getInputStream().read() == -1, "owned Tcl client disconnects without GDB commands");
                } catch (Exception error) { throw new RuntimeException(error); }
            });
            String failure = "";
            try { OpenOcdThreadCheck.verify(server.getLocalPort(), regions); }
            catch (IOException error) { failure = error.getMessage(); }
            served.get(5, TimeUnit.SECONDS);
            return failure;
        }
    }
    public static void main(String[] args) throws Exception {
        var size = new Region("_kernel_thread_info_size_t_size", 0x1280, List.of(4));
        var offsets = new Region("_kernel_thread_info_offsets", 0x1300, List.of(1, 0, 0, 0));
        var commands = new ArrayList<String>();
        yes(exercise(List.of("0x04", "1 0 0 0"), List.of(size, offsets), commands).isEmpty(), "matching metadata is accepted");
        yes(commands.equals(List.of("read_memory 0x1280 8 1", "read_memory 0x1300 8 4")), "only exact static regions are read, no reset/flash/GDB connection");
        commands.clear();
        String mismatch = exercise(List.of("0"), List.of(size), commands);
        yes(mismatch.contains("expected byte 4, target byte 0") && mismatch.contains("0x1280"), "report observed address, expected and actual value");
        yes(mismatch.contains("GDB was not attached") && mismatch.contains("No firmware or metadata was written"), "mismatch prevents false thread decoding without repairing RAM");
        String error = exercise(List.of("target not examined"), List.of(size), new ArrayList<>());
        yes(error.contains("could not read") && error.contains("target not examined"), "preserve actual Tcl read error");
        yes(exercise(List.of("4 4"), List.of(size), new ArrayList<>()).contains("could not read"), "wrong response length rejected");
        yes(exercise(List.of("999999999999999999999"), List.of(size), new ArrayList<>()).contains("could not read"), "overflow rejected");
        try { new Region("bad; flash erase_address 0 1000", 1, List.of(4)); throw new AssertionError("injection accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { new Region("_kernel_openocd_offsets", 1, List.of(256)); throw new AssertionError("invalid byte accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        var diagnostics = new io.github.niwantha33.zew.core.DebugServerDiagnostics();
        diagnostics.accept("Info : Listening on port 6333 for tcl connections");
        yes(diagnostics.isTclListening(6333) && !diagnostics.isListening(6333), "only owned Tcl readiness enables metadata inspection");
        System.out.println("OpenOcdThreadCheckTests PASS: " + checks + " checks");
    }
}
