// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Read-only check against the owned OpenOCD Tcl endpoint, before GDB symbol negotiation. */
public final class OpenOcdThreadCheck {
    private OpenOcdThreadCheck() {}
    public record Region(String symbol, long address, List<Integer> bytes) {
        public Region {
            if (!symbol.matches("_kernel_(?:thread_info|openocd)_[a-z_]+") || address <= 0
                    || bytes.isEmpty() || bytes.size() > 256
                    || bytes.stream().anyMatch(b -> b == null || b < 0 || b > 255))
                throw new IllegalArgumentException("Invalid ELF thread metadata region");
            bytes = List.copyOf(bytes);
        }
    }
    public static void verify(int port, List<Region> regions) throws IOException {
        if (port < 1024 || port > 65535 || regions.isEmpty() || regions.size() > 3)
            throw new IOException("Invalid thread metadata verification request");
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(5000);
            for (var region : regions) {
                String command = "read_memory 0x" + Long.toHexString(region.address()) + " 8 " + region.bytes().size();
                socket.getOutputStream().write((command + '\u001a').getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();
                var response = new StringBuilder();
                int value;
                while ((value = socket.getInputStream().read()) != 26) {
                    if (value < 0) throw new IOException("Owned OpenOCD Tcl endpoint closed during thread metadata verification");
                    if (response.length() >= 4096) throw new IOException("Oversized OpenOCD thread metadata response");
                    response.append((char) value);
                }
                String raw = response.toString().trim();
                List<Integer> actual;
                try {
                    if (!raw.matches("(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\s+(?:0[xX][0-9a-fA-F]+|[0-9]+))*"))
                        throw new NumberFormatException();
                    actual = Arrays.stream(raw.split("\\s+")).map(s -> s.startsWith("0x") || s.startsWith("0X")
                            ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s)).toList();
                    if (actual.size() != region.bytes().size() || actual.stream().anyMatch(b -> b < 0 || b > 255))
                        throw new NumberFormatException();
                } catch (NumberFormatException invalid) {
                    throw new IOException("OpenOCD could not read " + region.symbol() + " at 0x"
                            + Long.toHexString(region.address()) + ": " + raw + ". Check probe memory access and OpenOCD compatibility.");
                }
                for (int i = 0; i < actual.size(); i++) if (!actual.get(i).equals(region.bytes().get(i)))
                    throw new IOException("Zephyr thread metadata differs from the selected zephyr.elf: " + region.symbol()
                            + " at 0x" + Long.toHexString(region.address() + i) + " expected byte " + region.bytes().get(i)
                            + ", target byte " + actual.get(i) + ". GDB was not attached. Terminate old sessions, use Flash "
                            + "on this exact board/build, then retry Debug. If already flashed, investigate target reads/OpenOCD compatibility. "
                            + "No firmware or metadata was written by this check.");
            }
        }
    }
}
