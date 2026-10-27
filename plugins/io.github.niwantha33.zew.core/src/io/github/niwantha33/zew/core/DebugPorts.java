// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/** Select distinct available endpoints without connecting to another GDB server. */
public record DebugPorts(int gdb, int tcl, int telnet) {
    public DebugPorts {
        if (gdb < 1024 || gdb > 65535 || tcl < 1024 || tcl > 65535 || telnet < 1024 || telnet > 65535
                || gdb == tcl || gdb == telnet || tcl == telnet)
            throw new IllegalArgumentException("Debug endpoints must be distinct TCP ports in 1024..65535.");
    }
    public static DebugPorts select(int preferredGdb) throws IOException {
        if (preferredGdb < 1024 || preferredGdb > 65535)
            throw new IOException("GDB TCP port must be 1024..65535.");
        var held = new ArrayList<ServerSocket>();
        try {
            int gdb = bind(preferredGdb, held);
            int tcl = bind(6333, held);
            int telnet = bind(4444, held);
            return new DebugPorts(gdb, tcl, telnet);
        } finally {
            for (var socket : held) socket.close();
        }
    }
    private static int bind(int preferred, List<ServerSocket> held) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        try { socket.bind(new InetSocketAddress(preferred)); }
        catch (IOException busy) {
            socket.close();
            socket = new ServerSocket();
            socket.setReuseAddress(false);
            try { socket.bind(new InetSocketAddress(0)); }
            catch (IOException error) { socket.close(); throw error; }
        }
        held.add(socket);
        return socket.getLocalPort();
    }
    public TargetOperations.Plan apply(TargetOperations.Plan plan) throws IOException {
        if (!plan.action().equals("debugserver")) throw new IOException("Debug endpoints require debugserver.");
        return apply(plan, "openocd");
    }
    public TargetOperations.Plan apply(TargetOperations.Plan plan, String runner) throws IOException {
        if (!plan.action().equals("debugserver")) throw new IOException("Debug endpoints require debugserver.");
        var args = new ArrayList<>(plan.westArguments());
        args.addAll(List.of("--gdb-port", Integer.toString(gdb)));
        switch (runner) {
            case "openocd" -> args.addAll(List.of("--tcl-port", Integer.toString(tcl), "--telnet-port", Integer.toString(telnet)));
            case "jlink" -> args.addAll(List.of("--rtt-port", Integer.toString(telnet)));
            case "pyocd" -> args.addAll(List.of("--telnet-port", Integer.toString(telnet)));
            default -> throw new IOException("Unsupported native debug runner: " + runner);
        }
        return new TargetOperations.Plan(plan.action(), plan.board(), plan.buildDirectory(), args);
    }
}
