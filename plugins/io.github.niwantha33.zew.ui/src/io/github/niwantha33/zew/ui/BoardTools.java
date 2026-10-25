// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.*;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Serialize automatic board dependencies with the existing managed-install lock. */
final class BoardTools {
    // OSGi FileLocator.toFileURL() extracts ONLY the requested entry from a
    // packed plug-in. Python must see all sibling helpers in the same directory.
    // Keep this inventory in sync with scripts/verify_site.py.
    private static final List<String> PYTHON_TOOLS = List.of(
        "board_dependencies.py", "board_tools.py", "hardware_catalog.py", "target_metadata.py",
        "setup.py", "managed_sitecustomize.py",
        "sdk-toolchains-windows-1.0.1.json", "esp32s3-python-lock.json",
        "mcuboot-python-lock.json"
    );

    private static java.nio.file.Path stageTools(java.nio.file.Path root) throws Exception {
        var bundle = Activator.getDefault().getBundle();
        // A version-specific directory preserves previous tool sets and avoids
        // mixing a newer Python front-end with older sibling files.
        var parent = root.resolve(".zew-board-tools");
        var directory = parent.resolve(bundle.getVersion().toString());
        if (Files.isSymbolicLink(parent) || Files.isSymbolicLink(directory))
            throw new java.io.IOException("Managed board-tools directory is a symlink; refusing to overwrite it.");
        Files.createDirectories(directory);
        for (String name : PYTHON_TOOLS) {
            var resource = bundle.getEntry("tools/" + name);
            if (resource == null) throw new java.io.IOException("Installed Workbench bundle is missing tools/" + name);
            var target = directory.resolve(name);
            if (Files.isSymbolicLink(target))
                throw new java.io.IOException("Managed board-tools file is a symlink: " + target);
            // Read directly from the live OSGi bundle. Never rely on the URL
            // extraction cache to contain imports or neighbouring JSON locks.
            byte[] verified;
            try (var input = resource.openStream()) {
                verified = input.readNBytes(8 * 1024 * 1024 + 1);
            }
            if (verified.length == 0 || verified.length > 8 * 1024 * 1024)
                throw new java.io.IOException("Invalid bundled board-tool size: " + name);
            if (Files.isRegularFile(target) && Arrays.equals(verified, Files.readAllBytes(target)))
                continue;
            var temporary = directory.resolve(name + ".partial");
            if (Files.isSymbolicLink(temporary))
                throw new java.io.IOException("Managed temporary board-tool file is a symlink: " + temporary);
            Files.write(temporary, verified, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return directory.resolve("board_dependencies.py");
    }

    private static java.nio.channels.FileLock acquireInstallLock(FileChannel channel, IProgressMonitor monitor,
            Consumer<String> output) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(45).toNanos(); boolean announced = false;
        while (true) {
            if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Dependency preparation cancelled while waiting for shared tools");
            try { var lock = channel.tryLock(); if (lock != null) return lock; }
            catch (java.nio.channels.OverlappingFileLockException busy) { /* Another project in this Eclipse owns it. */ }
            if (!announced) { output.accept("Another project is preparing this tool environment; waiting automatically. Cancel in Progress if needed."); announced = true; }
            monitor.subTask("Waiting for shared Zephyr tool preparation");
            if (System.nanoTime() >= deadline) throw new java.io.IOException("Timed out waiting for shared Zephyr tool preparation");
            Thread.sleep(200);
        }
    }

    static void prepare(Properties settings, Map<String, String> environment, IProgressMonitor monitor, Consumer<String> output) throws Exception {
        prepareFor(settings, environment, monitor, output, null);
    }

    static void prepareHostTool(Properties settings, Map<String, String> environment, IProgressMonitor monitor,
            Consumer<String> output, String tool) throws Exception {
        if (!Set.of("IMGTOOL", "ESPTOOL").contains(tool)) throw new java.io.IOException("Unsupported required build host tool: " + tool);
        prepareFor(settings, environment, monitor, output, tool);
    }

    private static void prepareFor(Properties settings, Map<String, String> environment, IProgressMonitor monitor,
            Consumer<String> output, String requiredHostTool) throws Exception {
        if (!Platform.OS_WIN32.equals(Platform.getOS())) {
            if (requiredHostTool != null) throw new java.io.IOException("Managed host-tool provisioning requires Windows x64");
            return;
        }
        String board = settings.getProperty("board");
        if (board == null || board.isBlank()) throw new java.io.IOException("Set an exact project board target in Project Properties > Zephyr.");
        if (board.equals("qemu_x86/atom") && requiredHostTool == null) return; // Existing minimal starter, unless an actual generated rule requires a tool.
        java.nio.file.Path root = java.nio.file.Path.of(settings.getProperty("zew.setupRoot"));
        try (var channel = FileChannel.open(root.resolve(".zew-install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = acquireInstallLock(channel, monitor, output)) {
            if (lock == null) throw new java.io.IOException("Another project is preparing this tool environment. Wait for it to finish, then build again.");
            var helper = stageTools(root);
            output.accept("Resolving board, SoC, CPU, required compiler and pinned West modules. Existing application, SDK tools and modules are preserved.");
            var command = new ArrayList<>(List.of(settings.getProperty("python"), "-u", helper.toString(), "--root", root.toString(),
                "--sdk", settings.getProperty("tools.sdk"), "--workspace", settings.getProperty("workspace"),
                "--git", settings.getProperty("tools.git"), "--board", board,
                "--application", settings.getProperty("application")));
            if (requiredHostTool != null) command.addAll(List.of("--require-host-tool", requiredHostTool));
            new ProcessRunner().run(command, root, Duration.ofMinutes(40), monitor::isCanceled,
                line -> { monitor.subTask(line); output.accept(line); }, environment).requireSuccess();
        }
    }
}

