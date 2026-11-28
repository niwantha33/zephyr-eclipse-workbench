// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
public final class DebugBackendTests {
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    private static void rejects(Throwing action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("unsafe or missing runner tool accepted");
    }
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
        Path root = Files.createTempDirectory("vendor tools Ω ").toRealPath();
        try {
            Path vendor = Files.createDirectory(root.resolve("SEGGER selected"));
            Path server = Files.writeString(vendor.resolve("JLinkGDBServerCL.exe"), "fixture");
            Path commander = Files.writeString(vendor.resolve("JLink.exe"), "fixture");
            Path setup = Files.createDirectory(root.resolve("managed setup"));
            Path python = Files.writeString(Files.createDirectory(setup.resolve("python")).resolve("python.exe"), "fixture");
            Path pyocd = Files.writeString(Files.createDirectory(python.getParent().resolve("Scripts")).resolve("pyocd.exe"), "fixture");
            var plan = new TargetOperations.Plan("debugserver", "board", root, List.of("debugserver", "--no-rebuild"));
            var jlink = ManagedVendorDebugTools.bind(plan, "jlink", server.toString(), "", setup, python).westArguments();
            if (!jlink.containsAll(List.of("--runner", "jlink", "--gdbserver", server.toRealPath().toString(),
                    "--commander", commander.toRealPath().toString()))) throw new AssertionError("explicit J-Link pair");
            var py = ManagedVendorDebugTools.bind(plan, "pyocd", "", "", setup, python).westArguments();
            if (!py.containsAll(List.of("--runner", "pyocd", "--pyocd", pyocd.toRealPath().toString())))
                throw new AssertionError("setup-local pyOCD");
            Path external = Files.writeString(vendor.resolve("pyocd.exe"), "fixture");
            if (!ManagedVendorDebugTools.pyocd(external.toString(), setup, python).equals(external.toRealPath()))
                throw new AssertionError("explicit external pyOCD");
            rejects(() -> ManagedVendorDebugTools.jlink("JLinkGDBServerCL.exe"));
            rejects(() -> ManagedVendorDebugTools.pyocd("pyocd.exe", setup, python));
            rejects(() -> ManagedVendorDebugTools.pyocd("", setup, server));
            rejects(() -> ManagedVendorDebugTools.bind(new TargetOperations.Plan("debugserver", "board", root,
                    List.of("debugserver", "--runner", "openocd")), "jlink", server.toString(), "", setup, python));
            Files.delete(commander);
            rejects(() -> ManagedVendorDebugTools.jlink(server.toString()));
            Files.delete(pyocd);
            rejects(() -> ManagedVendorDebugTools.pyocd("", setup, python));
            var host = new java.util.HashMap<>(java.util.Map.of("OPENOCD_SCRIPTS", "foreign", "PyOCD_DAPARG", "foreign", "TEMP", "keep"));
            var policy = new ProcessEnvironment(true);
            policy.put("OPENOCD_SCRIPTS", "selected");
            ProcessEnvironment.apply(host, policy);
            if (!host.equals(java.util.Map.of("OPENOCD_SCRIPTS", "selected", "TEMP", "keep")))
                throw new AssertionError("managed runner ambient variables");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("DebugBackendTests PASS");
    }
}
