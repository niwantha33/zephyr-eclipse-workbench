// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/** Explicit, offline address lookup in a user-confirmed ELF. Does not prove dump/ELF identity. */
public final class ArmElfSymbols {
    public record Image(Path path, String sha256) {}
    public record Location(String register, long capturedAddress, String function, String source, int line, String raw) {
        public String display() {
            return register + " captured 0x" + Long.toHexString(capturedAddress) + " (Thumb bit cleared for lookup)\n"
                    + function + "\n" + raw + (register.equals("LR")
                    ? "\nLR is a return address or exception-return token, not a reconstructed caller frame." : "");
        }
    }
    private static final long MAX_ELF = 512L * 1024 * 1024;
    private static final Pattern SOURCE = Pattern.compile("^(.*):([0-9]+)(?: .*)?$");
    private ArmElfSymbols() {}

    public static Image inspect(Path elf) throws IOException {
        if (!Files.isRegularFile(elf) || Files.size(elf) < 52 || Files.size(elf) > MAX_ELF)
            throw new IOException("Select an ELF32 ARM image between 52 bytes and 512 MiB.");
        byte[] h;
        String sha256;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(elf)) {
                h = in.readNBytes(52);
                digest.update(h);
                byte[] bytes = new byte[64 * 1024];
                int count;
                long total = h.length;
                while ((count = in.read(bytes)) >= 0) {
                    total += count;
                    if (total > MAX_ELF) throw new IOException("ELF exceeds the 512 MiB lookup limit.");
                    digest.update(bytes, 0, count);
                }
            }
            sha256 = HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        if (h.length < 52 || h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F' || h[4] != 1
                || (h[5] != 1 && h[5] != 2)) throw new IOException("Not a supported ELF32 image.");
        int machine = h[5] == 1 ? Byte.toUnsignedInt(h[18]) | Byte.toUnsignedInt(h[19]) << 8
                : Byte.toUnsignedInt(h[19]) | Byte.toUnsignedInt(h[18]) << 8;
        if (machine != 40) throw new IOException("The selected ELF is not ARM (e_machine=" + machine + "). Cortex-M lookup was refused.");
        return new Image(elf.toAbsolutePath().normalize(), sha256);
    }

    public static Location resolve(Image image, Path addr2line, FaultLogAnalysis.Register register,
            BooleanSupplier cancelled) throws IOException, InterruptedException {
        if (!register.name().equals("PC") && !register.name().equals("LR"))
            throw new IOException("Only captured PC and LR addresses are supported.");
        // EXC_RETURN is an architectural token, not a code address.
        if (register.name().equals("LR") && (register.value() & 0xFF000000L) == 0xFF000000L)
            throw new IOException("LR looks like an EXC_RETURN token, not a code address; no symbol lookup was attempted.");
        if (!Files.isRegularFile(addr2line)) throw new IOException("Select the addr2line executable for your trusted toolchain.");
        if (!digest(image.path()).equals(image.sha256())) throw new IOException("ELF changed after selection; select and confirm it again.");
        var result = new ProcessRunner().run(List.of(addr2line.toAbsolutePath().toString(), "-f", "-C", "-e", image.path().toString(),
                "0x" + Long.toHexString(register.value() & ~1L)), image.path().getParent(), Duration.ofSeconds(15), cancelled, ignored -> {});
        String output = result.requireCompleteOutput().strip();
        if (!digest(image.path()).equals(image.sha256())) throw new IOException("ELF changed during lookup; results were discarded.");
        String[] rows = output.split("\\R");
        if (rows.length < 2) throw new IOException("addr2line did not return a function and source location: " + output);
        var match = SOURCE.matcher(rows[1]);
        String source = "";
        int line = 0;
        if (match.matches() && !match.group(1).equals("??")) {
            source = match.group(1);
            try { line = Integer.parseInt(match.group(2)); } catch (NumberFormatException ignored) {}
        }
        return new Location(register.name(), register.value(), rows[0], source, line, rows[1]);
    }
    private static String digest(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                byte[] bytes = new byte[64 * 1024];
                int count;
                long total = 0;
                while ((count = in.read(bytes)) >= 0) {
                    total += count;
                    if (total > MAX_ELF) throw new IOException("ELF exceeds the 512 MiB lookup limit.");
                    digest.update(bytes, 0, count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
}
