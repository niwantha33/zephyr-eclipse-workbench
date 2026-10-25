// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.DebugPorts;
import io.github.niwantha33.zew.core.TargetOperations;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class DebugPortsTests {
    private static int checks;
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        try (var occupied = new ServerSocket(0)) {
            occupied.setSoTimeout(100);
            var ports = DebugPorts.select(occupied.getLocalPort());
            check(ports.gdb() != occupied.getLocalPort(), "occupied GDB port selects another endpoint");
            check(Set.of(ports.gdb(), ports.tcl(), ports.telnet()).size() == 3, "all endpoints distinct");
            try { occupied.accept().close(); throw new AssertionError("port selection connected to unknown server"); }
            catch (SocketTimeoutException expected) { checks++; }
            try (var gdb = new ServerSocket(ports.gdb()); var tcl = new ServerSocket(ports.tcl());
                 var telnet = new ServerSocket(ports.telnet())) {
                check(gdb.isBound() && tcl.isBound() && telnet.isBound(), "ports are released for OpenOCD");
            }
            var plan = new TargetOperations.Plan("debugserver", "nucleo_f446re", Path.of("."),
                    List.of("debugserver", "--no-rebuild"));
            var bound = ports.apply(plan);
            check(bound.westArguments().containsAll(List.of("--gdb-port", "" + ports.gdb(),
                    "--tcl-port", "" + ports.tcl(), "--telnet-port", "" + ports.telnet())),
                    "all server endpoint overrides passed to West");
            check(bound.westArguments().contains("--no-rebuild") && plan.westArguments().size() == 2,
                    "preserve no-rebuild and original plan");
        }
        var ports = DebugPorts.select(3333);
        var again = DebugPorts.select(ports.gdb());
        check(again.gdb() == ports.gdb(), "available preferred port retained");
        try { DebugPorts.select(80); throw new AssertionError("invalid port accepted"); }
        catch (java.io.IOException expected) { checks++; }
        try { new DebugPorts(3333, 3333, 4444); throw new AssertionError("duplicate endpoint accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("DebugPortsTests PASS: " + checks + " checks");
    }
}
