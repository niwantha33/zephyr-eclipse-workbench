// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import java.nio.file.Path;
import java.util.List;
public final class DebugBackendTests {
    public static void main(String[] args) throws Exception {
        String yaml = "debug-runner: jlink\nargs:\n  openocd:\n  jlink:\n  pyocd:\n";
        if (!DebugBackend.select(yaml, "").equals("jlink")) throw new AssertionError("default");
        for (String runner : List.of("jlink", "pyocd", "openocd")) {
            DebugBackend.select(yaml, runner);
            var plan = new TargetOperations.Plan("debugserver", "board", Path.of("."), List.of("debugserver", "--no-rebuild"));
            var options = new DebugPorts(3333, 6333, 4444).apply(plan, runner).westArguments();
            if (options.contains("--tcl-port") != runner.equals("openocd")) throw new AssertionError("TCL contract");
            if (options.contains("--rtt-port") != runner.equals("jlink")) throw new AssertionError("RTT contract");
            var diagnostics = new DebugServerDiagnostics(3333, runner);
            diagnostics.accept("J-Link GDB server running on port 3333");
            if (diagnostics.isListening(3333)) throw new AssertionError("premature readiness");
            diagnostics.accept(switch (runner) {
                case "jlink" -> "Waiting for GDB connection...";
                case "pyocd" -> "INFO GDB server started at port 3333";
                default -> "Info : Listening on port 3333 for gdb connections";
            });
            if (!diagnostics.isListening(3333)) throw new AssertionError("readiness: " + runner);
        }
        try { DebugBackend.select("debug-runner: jlink\nargs:\n  openocd:\n", "jlink"); throw new AssertionError("unsupported board"); }
        catch (java.io.IOException expected) { }
        try { DebugBackend.select(yaml, "--erase"); throw new AssertionError("invalid runner"); }
        catch (java.io.IOException expected) { }
        System.out.println("DebugBackendTests PASS");
    }
}
