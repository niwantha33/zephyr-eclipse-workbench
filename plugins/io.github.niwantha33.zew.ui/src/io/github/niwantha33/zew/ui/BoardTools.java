// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import org.eclipse.core.runtime.*;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Serialize automatic profile additions with the existing setup install lock. */
final class BoardTools {
    static void prepare(Properties settings, Map<String, String> environment, IProgressMonitor monitor, Consumer<String> output) throws Exception {
        String[] target = settings.getProperty("board").split("/");
        if (target.length < 2 || !target[1].equals("esp32s3")) return;
        if (!Platform.OS_WIN32.equals(Platform.getOS())) throw new java.io.IOException("Automatic ESP32-S3 tool setup currently supports the managed Windows x64 environment.");
        java.nio.file.Path root = java.nio.file.Path.of(settings.getProperty("zew.setupRoot"));
        try (var channel = FileChannel.open(root.resolve(".zew-install.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var lock = channel.tryLock()) {
            if (lock == null) throw new java.io.IOException("Another project is preparing this tool environment. Wait for it to finish, then build again.");
            var helper = java.nio.file.Path.of(FileLocator.toFileURL(Activator.getDefault().getBundle().getEntry("tools/board_tools.py")).toURI());
            output.accept("Preparing ESP32-S3 compiler, esptool and pinned HAL source. Existing application and x86 compiler are preserved.");
            new ProcessRunner().run(List.of(settings.getProperty("python"), "-u", helper.toString(), "--root", root.toString(),
                "--sdk", settings.getProperty("tools.sdk"), "--workspace", settings.getProperty("workspace"),
                "--git", settings.getProperty("tools.git"), "--board", settings.getProperty("board")), root, Duration.ofMinutes(40), monitor::isCanceled,
                line -> { monitor.subTask(line); output.accept(line); }, environment).requireSuccess();
        }
    }
}
