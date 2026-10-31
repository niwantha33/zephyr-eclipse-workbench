// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.FlashDebugBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class FlashDebugBuildTests {
    private static int checks;
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; }
    private static void rejects(Action action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); }
        catch (IOException expected) { checks++; }
    }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void fixture(Path build) throws Exception {
        Path zephyr = Files.createDirectories(build.resolve("zephyr"));
        Files.write(zephyr.resolve("zephyr.elf"), new byte[] {0x7f, 'E', 'L', 'F', 1, 2, 3, 4});
        Files.writeString(zephyr.resolve("runners.yaml"), "config:\n  elf_file: zephyr.elf\nflash-runner: openocd\n");
        Files.writeString(zephyr.resolve(".config"), "CONFIG_DEBUG_THREAD_INFO=y\n");
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew-flash-debug Ω ");
        try {
            Path build = root.resolve("build");
            fixture(build);
            var identity = FlashDebugBuild.capture(build, "lp_mspm0g3507/mspm0g3507", "Debug");
            identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug");
            checks++;
            var attributes = identity.attributes();
            FlashDebugBuild.verifyAttributes(attributes, build, "lp_mspm0g3507/mspm0g3507", "Debug");
            checks++;
            var tampered = new java.util.HashMap<>(attributes);
            tampered.put("elf", "/untrusted/external.elf");
            rejects(() -> FlashDebugBuild.verifyAttributes(tampered, build, "lp_mspm0g3507/mspm0g3507", "Debug"), "imported identities are compared, never followed as paths");
            check(identity.debugElf().equals(build.resolve("zephyr/zephyr.elf").toRealPath()), "snapshot uses runner ELF");
            identity.verify(build.resolve(".." ).resolve("build"), "lp_mspm0g3507/mspm0g3507", "Debug");
            checks++;
            rejects(() -> identity.verify(build, "different/board", "Debug"), "board change blocks Debug");
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Release"), "profile change blocks Debug");
            Path other = root.resolve("other"); fixture(other);
            rejects(() -> identity.verify(other, "lp_mspm0g3507/mspm0g3507", "Debug"), "identical bytes in different build cannot substitute");
            // Same-size binary mutation must be detected by content, not output timestamps or path alone.
            Path elf = build.resolve("zephyr/zephyr.elf");
            var stamp = Files.getLastModifiedTime(elf);
            Files.write(elf, new byte[] {0x7f, 'E', 'L', 'F', 4, 3, 2, 1});
            Files.setLastModifiedTime(elf, stamp);
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug"), "same-size rebuilt ELF blocks Debug");
            rejects(() -> FlashDebugBuild.verifyAttributes(attributes, build, "lp_mspm0g3507/mspm0g3507", "Debug"), "queued launch cannot use an ELF rebuilt after Flash");
            fixture(build);
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_DEBUG_THREAD_INFO=n\n");
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug"), "generated Kconfig change blocks Debug");
            fixture(build);
            Files.copy(elf, build.resolve("zephyr/other.elf"));
            Files.writeString(build.resolve("zephyr/runners.yaml"), "config:\n  elf_file: other.elf\nflash-runner: openocd\n");
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug"), "runner ELF selection change blocks Debug even with identical ELF bytes");
            fixture(build);
            Files.writeString(build.resolve("zephyr/runners.yaml"), "config:\n  elf_file: zephyr.elf\nflash-runner: jlink\n");
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug"), "runner change blocks Debug");
            fixture(build);
            Files.delete(elf);
            rejects(() -> identity.verify(build, "lp_mspm0g3507/mspm0g3507", "Debug"), "deleted ELF blocks Debug");
            fixture(build);
            Files.writeString(build.resolve("zephyr/.config"), "");
            rejects(() -> FlashDebugBuild.capture(build, "board", "Debug"), "empty configuration has no identity");
            fixture(build);
            Files.writeString(build.resolve("zephyr/runners.yaml"), " ".repeat(1024 * 1024 + 1));
            rejects(() -> FlashDebugBuild.capture(build, "board", "Debug"), "oversized metadata bounded");
            fixture(build);
            rejects(() -> FlashDebugBuild.capture(build, "", "Debug"), "missing board rejected");
            rejects(() -> FlashDebugBuild.capture(build, "board", "Debug\n"), "invalid profile rejected");
            Path external = root.resolve("external.config");
            Files.writeString(external, "CONFIG_DEBUG_THREAD_INFO=y\n");
            try {
                Files.delete(build.resolve("zephyr/.config"));
                Files.createSymbolicLink(build.resolve("zephyr/.config"), external);
                rejects(() -> FlashDebugBuild.capture(build, "board", "Debug"), "external config symlink rejected");
            } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
                System.out.println("SKIP config symlink fixture: " + unavailable.getMessage());
            }
            System.out.println("FlashDebugBuildTests PASS: " + checks + " checks");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path file : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
