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
            socket.setSoTimeout(8000);
            // Listening ports are announced before the runner's reset/halt has
            // necessarily completed. Do not compare flash while startup is pending.
            String halted = exchange(socket, "wait_halt 5000");
            if (!halted.isBlank())
                throw new IOException("OpenOCD target was not confirmed halted before metadata verification: "
                        + printable(halted) + ". Check reset/halt and probe access; firmware identity was not assessed.");
            var first = readRegions(socket, regions);
            int mismatchRegion = -1, mismatchByte = -1;
            for (int r = 0; r < regions.size() && mismatchRegion < 0; r++) {
                for (int i = 0; i < regions.get(r).bytes().size(); i++) {
                    if (!first.get(r).get(i).equals(regions.get(r).bytes().get(i))) {
                        mismatchRegion = r; mismatchByte = i; break;
                    }
                }
            }
            if (mismatchRegion >= 0) {
                var region = regions.get(mismatchRegion);
                String detail = "Zephyr thread metadata differs from the selected zephyr.elf: " + region.symbol()
                        + " at 0x" + Long.toHexString(region.address() + mismatchByte)
                        + " expected byte " + region.bytes().get(mismatchByte)
                        + ", target byte " + first.get(mismatchRegion).get(mismatchByte);
                String repeated;
                try {
                    var second = readRegions(socket, regions);
                    repeated = first.equals(second)
                            ? " The mismatch persisted across two halted reads. A different flashed image or repeatable probe/read error remains possible."
                            : " Target metadata changed between two halted reads. Probe/target reads are inconsistent; reflashing alone is not a confirmed remedy.";
                } catch (IOException error) {
                    repeated = " The diagnostic repeat read failed: " + error.getMessage();
                }
                var summary = new StringBuilder();
                for (int r = 0; r < regions.size(); r++) {
                    summary.append(" ").append(regions.get(r).symbol()).append("@0x")
                            .append(Long.toHexString(regions.get(r).address())).append(" expected=")
                            .append(regions.get(r).bytes()).append(" observed=").append(first.get(r)).append(';');
                }
                throw new IOException(detail + "." + repeated
                        + " GDB was not attached. Terminate old sessions and use Zephyr > Flash and Debug Application for this exact board/build, or Flash then retry Debug. "
                        + "If already flashed, investigate target reads/OpenOCD compatibility. "
                        + "No firmware or metadata was written by this check. Readback:" + summary);
            }
        }
    }

    private static List<List<Integer>> readRegions(Socket socket, List<Region> regions) throws IOException {
        var result = new java.util.ArrayList<List<Integer>>();
        for (var region : regions) {
            String raw = exchange(socket, "read_memory 0x" + Long.toHexString(region.address())
                    + " 8 " + region.bytes().size()).trim();
            try {
                if (!raw.matches("(?:0[xX][0-9a-fA-F]+|[0-9]+)(?:\\s+(?:0[xX][0-9a-fA-F]+|[0-9]+))*"))
                    throw new NumberFormatException();
                var actual = Arrays.stream(raw.split("\\s+")).map(s -> s.startsWith("0x") || s.startsWith("0X")
                        ? Integer.parseInt(s.substring(2), 16) : Integer.parseInt(s)).toList();
                if (actual.size() != region.bytes().size() || actual.stream().anyMatch(b -> b < 0 || b > 255))
                    throw new NumberFormatException();
                result.add(actual);
            } catch (NumberFormatException invalid) {
                throw new IOException("OpenOCD could not read " + region.symbol() + " at 0x"
                        + Long.toHexString(region.address()) + ": " + printable(raw)
                        + ". Check probe memory access and OpenOCD compatibility.");
            }
        }
        return result;
    }

    private static String printable(String value) {
        return value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ");
    }

    private static String exchange(Socket socket, String command) throws IOException {
        socket.getOutputStream().write((command + '\u001a').getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        var response = new StringBuilder();
        int value;
        while ((value = socket.getInputStream().read()) != 26) {
            if (value < 0) throw new IOException("Owned OpenOCD Tcl endpoint closed during thread metadata verification");
            if (response.length() >= 4096) throw new IOException("Oversized OpenOCD thread metadata response");
            response.append((char) value);
        }
        return response.toString();
    }

    /** West invokes this after image readback verification, before reset/run. No writes. */
    public static String postFlashCommand(List<Region> regions) {
        if (regions.isEmpty() || regions.size() > 3) throw new IllegalArgumentException("Missing thread metadata");
        var script = new StringBuilder("namespace eval ::zew {}; proc ::zew::verify_flash_metadata {} {");
        for (var region : regions) {
            String address = "0x" + Long.toHexString(region.address());
            script.append(" set values [read_memory ").append(address).append(" 8 ").append(region.bytes().size()).append("]; ");
            script.append("if {[llength $values] != ").append(region.bytes().size()).append("} {error {Flash metadata read returned an invalid length}}; ");
            script.append("set address ").append(address).append("; foreach expected {");
            for (int b : region.bytes()) script.append(b).append(' ');
            script.append("} actual $values { if {$actual != $expected} {error [format {Flash verification failed: ")
                    .append(region.symbol()).append(" at 0x%x expected byte %d, target byte %d. Programming was not verified.} $address $expected $actual]}; incr address }; ");
        }
        return script.append("echo {Verified flashed Zephyr thread metadata against the selected ELF}; }; ::zew::verify_flash_metadata; rename ::zew::verify_flash_metadata {}").toString();
    }
}
