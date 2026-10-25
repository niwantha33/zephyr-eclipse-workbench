// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.*;
import java.nio.file.*;
import java.util.*;

public class BuildReliabilityTests {
    private static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    private static void rejects(BuildRecovery.Provider action, String message) throws Exception {
        try { action.prepare(); } catch (java.io.IOException expected) { return; }
        throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("zew reliability Ω ");
        try {
            String encoded = java.util.Base64.getEncoder().encodeToString("C:/project Ω".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String truth = java.util.Base64.getEncoder().encodeToString("true".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var paths = BuildPaths.parse("ZEW_PATHS_1\t" + String.join("\t", encoded, encoded, encoded, encoded, truth));
            check(paths.application().equals("C:/project Ω") && paths.freshConfiguration(), "path protocol preserves Unicode without shell parsing");
            rejects(() -> BuildPaths.parse("ZEW_PATHS_1\tinvalid"), "malformed path protocol rejected");
            Path build = root.resolve("project/.zew-build/target"); Files.createDirectories(build.resolve("zephyr"));
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_ARM=y\nCONFIG_KERNEL_BIN_NAME=\"firmware\"\nCONFIG_BUILD_OUTPUT_BIN=y\nCONFIG_BUILD_OUTPUT_HEX=y\n");
            Files.writeString(build.resolve("CMakeCache.txt"), "CMAKE_C_COMPILER:FILEPATH=C:/SDK with spaces/arm-zephyr-eabi-gcc.exe\n");
            BuildArtifacts.verifyArchitecture(build);
            Files.write(build.resolve("zephyr/firmware.elf"), new byte[] {0x7f, 'E', 'L', 'F'});
            rejects(() -> BuildArtifacts.verify(build), "ELF alone must not pass when BIN and HEX required");
            Files.writeString(build.resolve("zephyr/firmware.bin"), "bin");
            Files.writeString(build.resolve("zephyr/firmware.hex"), "hex");
            BuildArtifacts.verify(build);
            Files.writeString(build.resolve("CMakeCache.txt"), "BYPRODUCT_KERNEL_SIGNED_BIN_NAME:FILEPATH=" + build.resolve("zephyr/firmware.signed.bin") + "\n");
            rejects(() -> BuildArtifacts.verify(build), "configured signed byproduct must exist");
            Files.writeString(build.resolve("zephyr/firmware.signed.bin"), "signed-image");
            BuildArtifacts.verify(build);
            Files.writeString(build.resolve("CMakeCache.txt"), "BYPRODUCT_KERNEL_SIGNED_BIN_NAME:FILEPATH=" + root.resolve("user.bin") + "\n");
            rejects(() -> BuildArtifacts.required(build), "byproducts cannot leave managed build");
            Files.writeString(build.resolve("CMakeCache.txt"), "CMAKE_C_COMPILER:FILEPATH=C:/sdk/riscv64-zephyr-elf-gcc.exe\n");
            rejects(() -> BuildArtifacts.verifyArchitecture(build), "wrong architecture must fail");
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_KERNEL_BIN_NAME=\"../user\"\n");
            rejects(() -> BuildArtifacts.required(build), "output traversal must fail");
            int[] calls = {0};
            var recovery = new BuildRecovery(Map.of("IMGTOOL", () -> calls[0]++), 1);
            rejects(() -> recovery.repair(Set.of("IMGTOOL", "UNREVIEWED")), "unsupported tools must fail before any install");
            check(calls[0] == 0, "no partially executed recovery");
            recovery.repair(Set.of("IMGTOOL"));
            rejects(() -> recovery.repair(Set.of("IMGTOOL")), "no infinite recovery");
            check(calls[0] == 1, "one recovery only");
            try (var first = BuildSession.acquire(root)) {
                rejects(() -> BuildSession.acquire(root), "same environment must be protected");
                Path other = root.resolve("other"); Files.createDirectory(other);
                try (var independent = BuildSession.acquire(other)) { check(independent != null, "independent environment allowed"); }
            }
            rejects(() -> BuildSession.acquire(root, () -> true, java.time.Duration.ofSeconds(1), ignored -> {}),
                "cancelled waiting must stop before taking a session");
            try (var retry = BuildSession.acquire(root)) { check(retry != null, "session releases after use"); }
            check(BuildHostRequirements.missingInCommands("python IMGTOOL-NOTFOUND sign\n").equals(Set.of("IMGTOOL")), "required default command detected");
            check(BuildHostRequirements.missingInCommands("gcc main.c\n").isEmpty(), "ordinary source compile does not require repair");
            Path include = build.resolve("CMakeFiles/rules.ninja"); Files.createDirectories(include.getParent());
            Files.writeString(include, "rule IMAGE\n  command = GPERF-NOTFOUND\n");
            Files.writeString(build.resolve("build.ninja"), "# OPTIONAL-NOTFOUND\ninclude CMakeFiles/rules.ninja\n");
            check(BuildHostRequirements.missingInGeneratedBuild(build).equals(Set.of("GPERF")), "nested rules inspected; comments ignored");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("BuildReliabilityTests PASS");
    }
}
