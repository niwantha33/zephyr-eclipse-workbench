// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region;

public final class OpenOcdMetadataSmoke {
    public static void run(org.osgi.framework.Bundle ui, Path fixture) throws Exception {
        var extractor = ui.loadClass("io.github.niwantha33.zew.ui.OpenOcdThreadMetadata");
        for (String file : List.of("thread-32.elf", "thread-64.elf")) {
            @SuppressWarnings("unchecked")
            var regions = (List<Region>) extractor.getMethod("read", Path.class).invoke(null, fixture.resolve(file));
            if (regions.size() != 3 || !regions.get(0).bytes().equals(List.of(4))
                    || regions.get(2).bytes().size() != 60 || regions.stream().anyMatch(r -> r.address() <= 0))
                throw new AssertionError("ELF metadata extraction: " + file);
            System.out.println("PASS thread metadata: static symbol bytes and target addresses from " + file);
            verifyFlashImage(ui, fixture, file, regions);
        }
        try {
            extractor.getMethod("read", Path.class).invoke(null, fixture.resolve(".zew-build/first/helper.o"));
            throw new AssertionError("ELF without Zephyr metadata accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof java.io.IOException)) throw expected;
            System.out.println("PASS thread metadata: reject missing Zephyr symbols");
        }
        var commands = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrDebugServicesFactory$OpenOcdCommands").getConstructor().newInstance();
        var context = org.eclipse.cdt.dsf.datamodel.IDMContext.class;
        var controlContext = ui.loadClass("org.eclipse.cdt.dsf.mi.service.command.MIControlDMContext")
                .getConstructor(String.class, String.class).newInstance("zew-smoke", "owned-openocd");
        var command = commands.getClass().getMethod("createMITargetSelect", context, String.class, String.class, boolean.class)
                .invoke(commands, controlContext, "127.0.0.1", "3333", false);
        String text = (String) command.getClass().getMethod("constructCommand").invoke(command);
        if (!text.contains("extended-remote") || !text.contains("127.0.0.1:3333")) throw new AssertionError(text);
        System.out.println("PASS thread metadata: native DSF OpenOCD connection uses extended-remote");
        verifyFlashCompletion(ui);
    }
    private static void verifyFlashCompletion(org.osgi.framework.Bundle ui) throws Exception {
        var await = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrLaunchDelegate")
                .getDeclaredMethod("awaitFlash", Process.class, org.eclipse.core.runtime.IProgressMonitor.class);
        await.setAccessible(true);
        for (int code : List.of(0, 7)) {
            Process process = new ProcessBuilder(System.getenv("ZEW_TEST_PYTHON"), "-c", "import sys;sys.exit(" + code + ")").start();
            try {
                await.invoke(null, process, new org.eclipse.core.runtime.NullProgressMonitor());
                if (code != 0) throw new AssertionError("Programmer failure reported success");
            } catch (java.lang.reflect.InvocationTargetException failure) {
                if (code == 0 || !(failure.getCause() instanceof java.io.IOException)
                        || !failure.getCause().getMessage().contains("West exit 7")) throw failure;
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
        Process waiting = new ProcessBuilder(System.getenv("ZEW_TEST_PYTHON"), "-c", "import time;time.sleep(10)").start();
        var monitor = new org.eclipse.core.runtime.NullProgressMonitor(); monitor.setCanceled(true);
        try {
            await.invoke(null, waiting, monitor);
            throw new AssertionError("Cancelled Flash reported success");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof java.io.InterruptedIOException)) throw expected;
        } finally { if (waiting.isAlive()) waiting.destroyForcibly(); }
        System.out.println("PASS flash lifecycle: wait for success, propagate programmer failure, cancel owned process");
    }
    private static void verifyFlashImage(org.osgi.framework.Bundle ui, Path fixture, String name, List<Region> regions) throws Exception {
        Path build = Files.createTempDirectory(fixture, "flash-metadata-");
        Path zephyr = Files.createDirectory(build.resolve("zephyr"));
        Path elf = Files.copy(fixture.resolve(name), zephyr.resolve("zephyr.elf"));
        Path hex = Files.copy(fixture.resolve(name.replace(".elf", ".hex")), zephyr.resolve("zephyr.hex"));
        Files.writeString(zephyr.resolve("runners.yaml"), "flash-runner: openocd\nconfig:\n  elf_file: zephyr.elf\n  hex_file: zephyr.hex\nargs:\n  openocd: []\n");
        Path script = build.resolve("openocd_flash_image.py");
        try (var source = ui.getEntry("tools/openocd_flash_image.py").openStream()) { Files.copy(source, script); }
        var command = new ArrayList<>(List.of(System.getenv("ZEW_TEST_PYTHON"), script.toString(), "--build", build.toString(), "--elf", elf.toString()));
        for (var region : regions) {
            var bytes = new StringBuilder();
            for (int b : region.bytes()) bytes.append(String.format("%02x", b));
            command.addAll(List.of("--region", Long.toHexString(region.address()) + ":" + bytes));
        }
        var runner = new io.github.niwantha33.zew.core.ProcessRunner();
        String output = runner.run(command, build, java.time.Duration.ofSeconds(20), () -> false, line -> {}).requireCompleteOutput();
        if (!output.contains("TYPE\thex")) throw new AssertionError(output);
        System.out.println("PASS flash image: compiler/objcopy HEX includes CDT-extracted " + name + " metadata");
        Files.writeString(hex, ":00000001FF\n");
        var invalid = runner.run(command, build, java.time.Duration.ofSeconds(20), () -> false, line -> {});
        if (invalid.exitCode() == 0 || !invalid.output().contains("omits ELF thread metadata")) throw new AssertionError(invalid.output());
        System.out.println("PASS flash image: declared HEX missing metadata is rejected before programming");
    }
}
