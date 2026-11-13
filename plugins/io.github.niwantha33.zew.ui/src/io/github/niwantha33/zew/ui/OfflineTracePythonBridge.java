// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import org.eclipse.core.resources.IProject;
import io.github.niwantha33.zew.core.ProcessRunner;

/** Existing ProcessRunner bridge, adapted to the bounded offline JSON v1 protocol. */
final class OfflineTracePythonBridge implements OfflineTraceAnalysisService.Bridge {
    private final IProject project;
    private final Properties settings;

    OfflineTracePythonBridge(IProject project, Properties settings) {
        this.project = project;
        this.settings = settings;
    }

    @Override public String invoke(OfflineTraceAnalysisService.Request request) throws Exception {
        String interpreter = settings.getProperty("python", "");
        if (interpreter.isBlank() || !Files.isRegularFile(Path.of(interpreter)))
            throw new IOException("Missing Python interpreter: " + interpreter
                    + ". Configure the managed Python in Project Properties > Zephyr.");
        Path temporary = Files.createTempDirectory("zew-offline-trace-");
        try {
            for (String name : List.of("offline_trace_json.py", "semantic_trace.py", "cpu_analysis.py",
                    "contention_detector.py", "thread_timeline.py", "synchronization_analysis.py")) {
                var entry = Activator.getDefault().getBundle().getEntry("tools/rtos_analysis/" + name);
                if (entry == null) throw new IOException("Offline trace tool missing: " + name);
                try (var input = entry.openStream()) {
                    Files.copy(input, temporary.resolve(name));
                }
            }
            var args = new java.util.ArrayList<>(TracePythonLaunch.command(interpreter,
                    temporary.resolve("offline_trace_json.py"),
                    "--mode", request.mode(), "--ctf", request.trace().toString()));
            if (request.startNs() != null) {
                args.addAll(List.of("--start-ns", Long.toString(request.startNs()),
                        "--end-ns", Long.toString(request.endNs())));
            }
            if ("contention".equals(request.mode()))
                args.addAll(List.of("--threshold-us", Long.toString(request.thresholdUs())));
            // ProcessRunner is the repository's existing bounded, cancellable bridge.
            // Its merged output includes stderr on exit failure; do not parse it as JSON.
            return new ProcessRunner().run(args, project.getLocation().toFile().toPath(),
                    Duration.ofSeconds(45), () -> Thread.currentThread().isInterrupted(),
                    ignored -> {}, ProjectContext.environment(settings)).requireCompleteOutput();
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(p);
            }
        }
    }
}
