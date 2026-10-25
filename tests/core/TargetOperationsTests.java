// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.TargetOperations;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Offline, no-hardware regression checks for safe West flash/debug-server planning. */
public final class TargetOperationsTests {
    private static int checks;
    private static void check(boolean truth, String message) {
        if (!truth) throw new AssertionError(message);
        checks++;
    }
    private static void rejects(Throwing action, String message) throws Exception {
        try { action.run(); } catch (IOException expected) { checks++; return; }
        throw new AssertionError(message);
    }
    private interface Throwing { void run() throws Exception; }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("zew flash Ω space ");
        try {
            Path build = Files.createDirectories(dir.resolve(".zew-build/exact"));
            Path zephyr = Files.createDirectories(build.resolve("zephyr"));
            String board = "esp32s3_devkitc/esp32s3/procpu";
            Files.writeString(build.resolve("CMakeCache.txt"), "BOARD:STRING=esp32s3_devkitc\n");
            Files.writeString(build.resolve(".zew-built-target"), board);
            Files.writeString(zephyr.resolve("runners.yaml"), "flash-runner: esptool\n");
            Files.writeString(zephyr.resolve(".config"), "CONFIG_XTENSA=y\nCONFIG_KERNEL_BIN_NAME=\"zephyr\"\n");
            Files.write(zephyr.resolve("zephyr.elf"), new byte[] {0x7f,'E','L','F',1});
            var flash = TargetOperations.prepare(build, board, "flash", null);
            check(flash.westArguments().equals(List.of("flash", "--build-dir", build.toRealPath().toString().replace('\\','/'), "--skip-rebuild")), "exact build and skip-rebuild");
            check(flash.command("C:/Python Ω/python.exe").subList(0,3).equals(List.of("C:/Python Ω/python.exe", "-m", "west")), "managed interpreter retained");
            check(TargetOperations.prepare(build, board, "flash", "esptool").westArguments().containsAll(List.of("--runner", "esptool")), "explicit runner argument");
            check(TargetOperations.prepare(build, board, "debugserver", "openocd").westArguments().contains("--skip-rebuild"), "debugserver uses selected build without rebuilding");
            rejects(() -> TargetOperations.prepare(build, "esp32s3_devkitc/esp32s3/appcpu", "flash", null), "wrong CPU must fail");
            rejects(() -> TargetOperations.prepare(build, board, "flash", "--erase"), "runner flag injection must fail");
            rejects(() -> TargetOperations.prepare(build, board, "flash", "esptool --erase"), "runner whitespace injection must fail");
            rejects(() -> TargetOperations.prepare(build, board, "invalid", null), "unsupported operation must fail");
            Files.writeString(zephyr.resolve(".config"), "CONFIG_XTENSA=y\nCONFIG_KERNEL_BIN_NAME=\"zephyr\"\nCONFIG_BOARD_TARGET=\"esp32s3_devkitc/esp32s3/appcpu\"\n");
            rejects(() -> TargetOperations.prepare(build, board, "flash", null), "reconfigured CPU mismatch must fail despite old receipt");
            Files.writeString(zephyr.resolve(".config"), "CONFIG_XTENSA=y\nCONFIG_KERNEL_BIN_NAME=\"zephyr\"\nCONFIG_BOARD_TARGET=\"esp32s3_devkitc/esp32s3/procpu\"\n");
            Files.delete(zephyr.resolve("zephyr.elf"));
            rejects(() -> TargetOperations.prepare(build, board, "flash", null), "deleted firmware must fail");
            Files.write(zephyr.resolve("zephyr.elf"), new byte[] {0x7f,'E','L','F',1});
            Files.delete(zephyr.resolve("runners.yaml"));
            rejects(() -> TargetOperations.prepare(build, board, "flash", null), "runner config missing must fail");
            Files.writeString(build.resolve(".zew-built-target"), "qemu_x86/atom");
            Files.writeString(zephyr.resolve(".config"), "CONFIG_X86=y\nCONFIG_BOARD_TARGET=\"qemu_x86/atom\"\n");
            check(TargetOperations.prepare(build, "qemu_x86/atom", "run", null).westArguments().contains("run"), "QEMU operation retained");
            rejects(() -> TargetOperations.prepare(build, board, "flash", null), "cross-target stale receipt rejected");
            System.out.println("TargetOperationsTests PASS: " + checks + " checks");
        } finally {
            try (var paths = Files.walk(dir)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
