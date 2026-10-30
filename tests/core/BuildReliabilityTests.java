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
            // A signed ELF byproduct must never displace the runner-declared debug ELF.
            Path signedElf = build.resolve("zephyr/firmware.signed.elf");
            Files.write(signedElf, new byte[] {0x7f, 'E', 'L', 'F', 1});
            Files.writeString(build.resolve("CMakeCache.txt"), "BYPRODUCT_KERNEL_SIGNED_ELF_NAME:FILEPATH=" + signedElf + "\n");
            Path runners = build.resolve("zephyr/runners.yaml");
            Path debugElf = build.resolve("zephyr/firmware.elf").toRealPath();
            for (String path : List.of("firmware.elf", "'firmware.elf'", "\"firmware.elf\"", debugElf.toString().replace('\\', '/'))) {
                Files.writeString(runners, "config:\n  elf_file: " + path + "\nargs:\n  openocd: []\n");
                check(BuildArtifacts.debugElf(build).equals(debugElf), "runner ELF wins over signed byproduct; generated scalar quoting supported");
            }
            Files.writeString(runners, "config:\n  elf_file: firmware.signed.elf\n");
            check(BuildArtifacts.debugElf(build).equals(signedElf.toRealPath()), "runner explicitly selecting signed debug ELF is honoured");
            Path unicodeElf = build.resolve("zephyr/firmware Ω.elf");
            Files.copy(debugElf, unicodeElf);
            Files.writeString(runners, "config:\n  elf_file: \"firmware \\u03a9.elf\"\n");
            check(BuildArtifacts.debugElf(build).equals(unicodeElf.toRealPath()), "PyYAML escaped Unicode path is decoded");
            Path apostropheElf = build.resolve("zephyr/firmware's.elf");
            Files.copy(debugElf, apostropheElf);
            Files.writeString(runners, "config:\n  elf_file: 'firmware''s.elf'\n");
            check(BuildArtifacts.debugElf(build).equals(apostropheElf.toRealPath()), "single quoted apostrophe path is decoded");
            Path externalElf = root.resolve("external.elf");
            Files.copy(debugElf, externalElf);
            for (String yaml : List.of("config:\n  hex_file: firmware.hex\n", "config:\n  elf_file: missing.elf\n",
                    "config:\n  elf_file: firmware.elf\n  elf_file: firmware.signed.elf\n",
                    "config:\n  elf_file: firmware.elf\nconfig:\n  elf_file: firmware.signed.elf\n",
                    "config:\n  elf_file: " + externalElf.toString().replace('\\', '/') + "\n",
                    "config:\n  elf_file: firmware.elf\n  'elf_file': firmware.signed.elf\n",
                    "config:\n  elf_file: firmware.elf\nconfig: {elf_file: firmware.signed.elf}\n",
                    "config:\n  elf_file: [firmware.elf]\n", "config:\n  elf_file: &ref firmware.elf\n",
                    "config:\n  elf_file: \"firmware\\u0000.elf\"\n", "config:\n  elf_file: 'firmware's.elf'\n")) {
                Files.writeString(runners, yaml);
                rejects(() -> BuildArtifacts.debugElf(build), "missing, ambiguous, external and unsupported runner ELF declarations fail closed");
            }
            Files.writeString(build.resolve("zephyr/bad.elf"), "not ELF");
            Files.writeString(runners, "config:\n  elf_file: bad.elf\n");
            rejects(() -> BuildArtifacts.debugElf(build), "declared file must have ELF magic");
            Files.writeString(build.resolve("CMakeCache.txt"), "BYPRODUCT_KERNEL_SIGNED_BIN_NAME:FILEPATH=" + root.resolve("user.bin") + "\n");
            rejects(() -> BuildArtifacts.required(build), "byproducts cannot leave managed build");
            Files.writeString(build.resolve("CMakeCache.txt"), "CMAKE_C_COMPILER:FILEPATH=C:/sdk/riscv64-zephyr-elf-gcc.exe\n");
            rejects(() -> BuildArtifacts.verifyArchitecture(build), "wrong architecture must fail");
            Files.writeString(build.resolve("zephyr/.config"), "CONFIG_KERNEL_BIN_NAME=\"../user\"\n");
            rejects(() -> BuildArtifacts.required(build), "output traversal must fail");
            // Fatal Zephyr Kconfig warnings are application errors, not SDK/module
            // provisioning failures. Preserve the original file and line number.
            var kconfigFailure = new KconfigFailure();
            String invalidConfig = "C:/Zephyr Project/demo/prj.conf:2: warning: attempt to assign "
                    + "the value 'y' to the undefined symbol DEBUG_INFO";
            var issue = KconfigFailure.parse(invalidConfig).orElseThrow();
            check(issue.file().equals("C:/Zephyr Project/demo/prj.conf")
                    && issue.line() == 2 && issue.symbol().equals("DEBUG_INFO"),
                    "Windows drive paths and Kconfig line numbers are preserved");
            check(issue.suggestion().contains("CONFIG_DEBUG_OPTIMIZATIONS=y"),
                    "debug-info diagnostic offers a supported separate debug profile");
            kconfigFailure.accept(invalidConfig);
            kconfigFailure.accept(invalidConfig);
            check(kconfigFailure.description().split("C:/Zephyr Project/demo/prj.conf:2: CONFIG_DEBUG_INFO", -1).length == 2,
                    "duplicate warnings are reported once");
            check(KconfigFailure.parse("/tmp/app/boards/debug.conf:25: warning: attempt to assign "
                    + "the value 'y' to the undefined symbol MISSING_MODULE").isPresent(),
                    "any application configuration fragment is supported");
            check(KconfigFailure.parse("C:/app/src/main.c:3: error: undefined reference").isEmpty(),
                    "ordinary linker/compile errors are not reclassified as Kconfig");
            check(KconfigFailure.parse("not a Kconfig warning").isEmpty(),
                    "non-Kconfig output is not reclassified");
            check(KconfigFailure.parse("C:/app/prj.conf:99999999999999999999: warning: attempt "
                    + "to assign the value 'y' to the undefined symbol BAD").isEmpty(),
                    "malformed line numbers are rejected without throwing");
            check(!new KconfigFailure().hasIssues(), "successful builds carry no Kconfig advice");
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
