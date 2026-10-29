// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import io.github.niwantha33.zew.core.OpenOcdRegistry;
import io.github.niwantha33.zew.core.OpenOcdRegistry.Provider;

public final class OpenOcdRegistryTests {
    private static int assertions;
    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        assertions++;
    }
    static Path fake(Path product, boolean esp, boolean ti) throws Exception {
        Path executable = Files.createDirectories(product.resolve("bin")).resolve("openocd.exe");
        Files.writeString(executable, "offline fixture");
        Path scripts = product.resolve("share/openocd/scripts");
        Files.createDirectories(scripts.resolve("interface"));
        Files.createDirectories(scripts.resolve("target"));
        Files.writeString(scripts.resolve("interface/cmsis-dap.cfg"), "# fixture");
        if (esp) {
            Files.writeString(scripts.resolve("interface/esp_usb_jtag.cfg"), "# ESP32 fixture");
            Files.writeString(scripts.resolve("target/esp32s3.cfg"), "# ESP32-S3 fixture");
        }
        if (ti) Files.writeString(scripts.resolve("target/ti_mspm0.cfg"), "# TI fixture");
        return executable;
    }
    static OpenOcdRegistry.Locations locations(Path sdk, Path home, Path idf,
            Path general, Path esp, Path ti) {
        return new OpenOcdRegistry.Locations(sdk, home, idf, general, esp, ti, null);
    }
    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("zew-shared-openocd-");
        try {
            Path sdk = temp.resolve("zephyr-sdk");
            Path general = fake(sdk.resolve("hosttools"), false, false);
            Path esp = fake(temp.resolve("user/.espressif/tools/openocd-esp32/2026/openocd-esp32"), true, false);
            Path ti = fake(temp.resolve("ti-openocd"), false, true);
            Path home = temp.resolve("user");
            var auto = locations(sdk, home, null, null, null, null);

            check(OpenOcdRegistry.providerFor("esp32s3_devkitc/esp32s3/procpu") == Provider.ESPRESSIF,
                    "ESP32-S3 requires Espressif fork");
            check(OpenOcdRegistry.providerFor("esp32c3_devkitm/esp32c3") == Provider.ESPRESSIF,
                    "ESP32-C3 uses Espressif tool");
            check(OpenOcdRegistry.providerFor("nucleo_f446re/stm32f446xx") == Provider.GENERAL,
                    "STM32 can use compatible generic OpenOCD");
            check(OpenOcdRegistry.providerFor("nrf52840dk/nrf52840") == Provider.GENERAL,
                    "Nordic can use compatible generic OpenOCD");
            check(OpenOcdRegistry.providerFor("mspm0l1306_evk") == Provider.TI,
                    "TI provider isolated from generic fallback");
            check(OpenOcdRegistry.select("nucleo_f446re", auto).orElseThrow().executable()
                    .equals(general.toRealPath()), "reuse shared Zephyr SDK OpenOCD");
            check(OpenOcdRegistry.select("esp32s3_devkitc/esp32s3/procpu", auto)
                    .orElseThrow().executable().equals(esp.toRealPath()),
                    "use Espressif installation, not generic SDK OpenOCD");
            check(OpenOcdRegistry.select("mspm0l1306_evk", auto).isEmpty(),
                    "never fall back to generic tool for TI-special target");
            var manual = locations(sdk, home, null, null, null, ti);
            check(OpenOcdRegistry.select("mspm0l1306_evk", manual).orElseThrow()
                    .executable().equals(ti.toRealPath()), "explicit TI provider");
            check(OpenOcdRegistry.fromExecutable(Provider.ESPRESSIF, general, "bad").isEmpty(),
                    "reject generic OpenOCD without ESP32 scripts");
            var selected = OpenOcdRegistry.select("esp32s3_devkitc/esp32s3/procpu", auto).orElseThrow();
            String runner = "config:\\n  openocd: " + selected.executable().toString().replace('\\', '/')
                    + "\\n  openocd_search:\\n    - "
                    + selected.scripts().toString().replace('\\', '/') + "\\n";
            runner = runner.replace("\\n", "\n");
            check(OpenOcdRegistry.recordedInRunnerYaml(selected, runner),
                    "exact tool and matching scripts in generated West config");
            check(!OpenOcdRegistry.recordedInRunnerYaml(selected,
                    runner.replace("openocd: ", "bad-openocd: ")),
                    "stale or manipulated runner fails preflight");
            check(!OpenOcdRegistry.recordedInRunnerYaml(selected,
                    runner.replace("openocd_search:", "unknown_paths:")
                          .replace("- " + selected.scripts().toString().replace('\\','/'), "- C:/wrong/scripts")),
                    "wrong scripts must block Debug");

            var oldPlan = new io.github.niwantha33.zew.core.TargetOperations.Plan(
                    "debugserver", "esp32s3_devkitc/esp32s3/procpu", temp,
                    java.util.List.of("debugserver", "--build-dir", temp.toString(), "--no-rebuild"));
            var bound = io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(oldPlan, selected);
            check(bound.westArguments().subList(4, 8).equals(java.util.List.of(
                    "--openocd", selected.executable().toString().replace('\\', '/'),
                    "--openocd-search", selected.scripts().toString().replace('\\', '/'))),
                    "old metadata overridden by explicit matching executable/scripts");
            check(bound.westArguments().contains("gdb_breakpoint_override hard"),
                    "Espressif avoids software breakpoint writes to unmapped flash");
            check(oldPlan.westArguments().size() == 4 && bound.westArguments().contains("--no-rebuild"),
                    "override preserves immutable original and no-rebuild");
            try {
                io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(oldPlan,
                        OpenOcdRegistry.select("nucleo_f446re", auto).orElseThrow());
                throw new AssertionError("incompatible provider accepted");
            } catch (java.io.IOException expected) { assertions++; }
            var flashPlan = new io.github.niwantha33.zew.core.TargetOperations.Plan("flash", oldPlan.board(), temp,
                    java.util.List.of("flash", "--no-rebuild"));
            var verifiedFlash = io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(flashPlan, selected);
            check(verifiedFlash.westArguments().contains("--verify"), "OpenOCD Flash requires readback verification");
            check(verifiedFlash.westArguments().contains(selected.executable().toString().replace('\\', '/')),
                    "Flash uses the same selected tool as Debug");
            check(!verifiedFlash.westArguments().contains("gdb_breakpoint_override hard"), "Flash receives no debug breakpoint commands");
            check(io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdFlash(flashPlan, "flash-runner: openocd\n"),
                    "default OpenOCD flash backend recognized");
            check(!io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdFlash(flashPlan, "flash-runner: jlink\ndebug-runner: openocd\n"),
                    "debug backend never replaces a different flash backend");


            for (String board : java.util.List.of("nucleo_f446re/stm32f446xx", "nrf52840dk/nrf52840",
                    "frdm_k64f/mk64f12", "ek_ra6m3/r7fa6m3ah3cfc", "sam_e70_xplained/same70q21",
                    "slstk3701a/efm32gg11b820f2048gl192", "mspm0l1306_evk")) {
                var tool = OpenOcdRegistry.select(board, manual).orElseThrow();
                var vendorPlan = new io.github.niwantha33.zew.core.TargetOperations.Plan(
                        "debugserver", board, temp, oldPlan.westArguments());
                check(io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdDebugServer(
                        vendorPlan, "debug-runner: openocd\n"), board + " OpenOCD runner recognized");
                check(io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(vendorPlan, tool)
                        .westArguments().contains(tool.executable().toString().replace('\\', '/')),
                        board + " binds matching shared provider");
                check(!io.github.niwantha33.zew.core.TargetOperations.withOpenOcd(vendorPlan, tool)
                        .westArguments().contains("gdb_breakpoint_override hard"),
                        board + " retains native breakpoint policy");
                check(!io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdDebugServer(
                        vendorPlan, "debug-runner: jlink\n"), board + " preserves alternative runner");
            }
            check(!io.github.niwantha33.zew.core.TargetOperations.usesOpenOcdDebugServer(
                    new io.github.niwantha33.zew.core.TargetOperations.Plan("flash", oldPlan.board(), temp,
                            java.util.List.of("flash")), "debug-runner: openocd\n"),
                    "flash never receives debug overrides");

            Path incomplete = fake(temp.resolve("incomplete"), false, false);
            check(OpenOcdRegistry.fromExecutable(Provider.ESPRESSIF, incomplete, "incomplete").isEmpty(),
                    "incomplete vendor fork rejected");
            Files.delete(incomplete);
            check(OpenOcdRegistry.fromExecutable(Provider.GENERAL, incomplete, "deleted").isEmpty(),
                    "missing executable rejected");
            check(OpenOcdRegistry.missing("mspm0l1306_evk").getMessage().contains("TI-compatible"),
                    "TI-specific recovery guidance");
            check(OpenOcdRegistry.missing("nucleo_f446re").getMessage().contains("one global OpenOCD"),
                    "global manager guidance");
            System.out.println("OpenOcdRegistryTests PASS " + assertions + " checks");
        } finally {
            try (var walk = Files.walk(temp)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }
}
