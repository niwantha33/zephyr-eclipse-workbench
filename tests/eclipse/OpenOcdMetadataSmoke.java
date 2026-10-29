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
        verifyDebugServerCleanup(ui);
    }
    private static void verifyDebugServerCleanup(org.osgi.framework.Bundle ui) throws Exception {
        var guard = ui.loadClass("io.github.niwantha33.zew.ui.TargetOperationGuard");
        var acquire = guard.getDeclaredMethod("acquire", String.class); acquire.setAccessible(true);
        var serverClass = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrNativeDebugDelegate$ManagedServer");
        var constructor = serverClass.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var lease = (AutoCloseable) acquire.invoke(null, "debugserver");
        var launch = new org.eclipse.debug.core.Launch(null, "debug", null);
        var console = new org.eclipse.ui.console.MessageConsole("owned server cleanup test", null);
        // The parent waits for a quiet child which owns a real TCP endpoint.
        // No log traffic is required for termination to discover that child.
        String child = "import socket,time;s=socket.socket();s.bind(('127.0.0.1',0));s.listen();"
                + "print('PORT',s.getsockname()[1],flush=True);time.sleep(30)";
        Process process = new ProcessBuilder(System.getenv("ZEW_TEST_PYTHON"), "-u", "-c",
                "import subprocess,sys;p=subprocess.Popen([sys.executable,'-u','-c',sys.argv[1]]);"
                        + "print('PID',p.pid,flush=True);p.wait()", child).start();
        AutoCloseable owned = null;
        ProcessHandle descendant = null;
        try {
            var lines = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            int port = 0;
            for (int i = 0; i < 2; i++) {
                String[] value = lines.readLine().split(" ");
                if (value[0].equals("PORT")) port = Integer.parseInt(value[1]);
                else if (value[0].equals("PID")) descendant = ProcessHandle.of(Long.parseLong(value[1])).orElseThrow();
            }
            if (port == 0 || descendant == null) throw new AssertionError("Owned server fixture did not start");
            owned = (AutoCloseable) constructor.newInstance(process, console.newMessageStream(), lease, launch, port, "openocd");
            long start = System.nanoTime();
            ((org.eclipse.debug.core.ILaunchesListener2) owned).launchesTerminated(new org.eclipse.debug.core.ILaunch[] {launch});
            if (System.nanoTime() - start > java.util.concurrent.TimeUnit.SECONDS.toNanos(1))
                throw new AssertionError("Terminate callback blocked Eclipse event dispatch");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while ((process.isAlive() || descendant.isAlive()) && System.nanoTime() < deadline) Thread.sleep(50);
            if (process.isAlive() || descendant.isAlive()) throw new AssertionError("Owned server process tree survived termination");
            try (var available = new java.net.ServerSocket()) { available.bind(new java.net.InetSocketAddress("127.0.0.1", port)); }
            // Release is part of cleanup, and can happen just after process exit.
            AutoCloseable next = null;
            while (next == null && System.nanoTime() < deadline) {
                try { next = (AutoCloseable) acquire.invoke(null, "debugserver"); }
                catch (java.lang.reflect.InvocationTargetException busy) {
                    if (!(busy.getCause() instanceof java.io.IOException)) throw busy;
                    Thread.sleep(50);
                }
            }
            if (next == null) throw new AssertionError("Debug operation lease was not released");
            next.close();
            owned.close(); // repeat cleanup is harmless
            System.out.println("PASS debug lifecycle: asynchronous stop reaps quiet child, releases TCP port and operation lease");
        } finally {
            if (owned != null) owned.close();
            else lease.close();
            if (descendant != null && descendant.isAlive()) descendant.destroyForcibly();
            if (process.isAlive()) process.destroyForcibly();
            console.destroy();
        }
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
        Process interrupted = new ProcessBuilder(System.getenv("ZEW_TEST_PYTHON"), "-c", "import time;time.sleep(10)").start();
        try {
            Thread.currentThread().interrupt();
            await.invoke(null, interrupted, new org.eclipse.core.runtime.NullProgressMonitor());
            throw new AssertionError("Interrupted Flash reported success");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof InterruptedException)) throw expected;
            Thread.interrupted();
            if (!interrupted.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Interrupted programmer still running");
        } finally { Thread.interrupted(); if (interrupted.isAlive()) interrupted.destroyForcibly(); }
        System.out.println("PASS flash lifecycle: wait for success, propagate programmer failure, cancel and interrupt owned process");
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
