// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Validated, vendor-neutral invocation plan for the pinned West build output.
 * Planning has no side effects and never probes or alters physical hardware.
 */
public final class TargetOperations {
    private static final Set<String> ACTIONS = Set.of("run", "flash", "debugserver");
    private TargetOperations() {}

    public record Plan(String action, String board, Path buildDirectory, List<String> westArguments) {
        public Plan { westArguments = List.copyOf(westArguments); }
        public List<String> command(String python) {
            if (python == null || python.isBlank()) throw new IllegalArgumentException("Missing managed Python executable.");
            var command = new ArrayList<>(List.of(python, "-m", "west"));
            command.addAll(westArguments);
            return List.copyOf(command);
        }
    }

    /** Other debug runners retain their own tool and transport configuration. */
    public static boolean usesOpenOcdDebugServer(Plan plan, String yaml) {
        if (!"debugserver".equals(plan.action()) || yaml == null) return false;
        int explicit = plan.westArguments().indexOf("--runner");
        if (explicit >= 0) return explicit + 1 < plan.westArguments().size()
                && "openocd".equals(plan.westArguments().get(explicit + 1));
        return java.util.regex.Pattern.compile("(?m)^debug-runner:[ \t]*['\"]?openocd['\"]?[ \t]*$")
                .matcher(yaml).find();
    }

    /** Bind a compatible shared tool at launch time, including builds made before tool selection. */
    public static Plan withOpenOcd(Plan plan, OpenOcdRegistry.Installation tool) throws IOException {
        if (!plan.action().equals("debugserver"))
            throw new IOException("OpenOCD debug override requires a debugserver plan.");
        if (tool.provider() != OpenOcdRegistry.providerFor(plan.board()))
            throw new IOException("OpenOCD provider does not match the selected board.");
        var verified = OpenOcdRegistry.fromExecutable(tool.provider(), tool.executable(), tool.origin());
        if (verified.isEmpty() || !verified.get().scripts().equals(tool.scripts()))
            throw new IOException("Selected OpenOCD executable or matching scripts are no longer available.");
        var args = new ArrayList<>(plan.westArguments());
        args.addAll(List.of("--openocd", WestCommands.cmakePath(tool.executable().toString()),
                "--openocd-search", WestCommands.cmakePath(tool.scripts().toString())));
        return new Plan(plan.action(), plan.board(), plan.buildDirectory(), args);
    }

    public static Plan prepare(Path directory, String exactBoard, String action, String selectedRunner) throws IOException {
        if (directory == null || exactBoard == null || exactBoard.isBlank() || action == null || !ACTIONS.contains(action))
            throw new IOException("Select a valid imported target and supported runner action.");
        String runner = selectedRunner == null ? "" : selectedRunner.trim();
        if (!runner.isEmpty() && !runner.matches("[a-zA-Z][a-zA-Z0-9_+.-]{0,63}"))
            throw new IOException("Invalid West runner name; refusing command-option injection.");
        if (action.equals("run") && !runner.isEmpty())
            throw new IOException("QEMU run does not accept a physical flash/debug runner.");
        Path build = directory.toRealPath();
        Path cache = build.resolve("CMakeCache.txt");
        Path receipt = build.resolve(".zew-built-target");
        if (!Files.isRegularFile(cache) || !Files.isRegularFile(receipt) || Files.size(receipt) > 256)
            throw new IOException("Missing verified build receipt or CMake configuration. Build this exact target first.");
        if (!Files.readString(receipt, StandardCharsets.UTF_8).equals(exactBoard))
            throw new IOException("Build receipt does not match the selected exact board/CPU: " + exactBoard);
        // When generated Kconfig exposes the canonical qualified target, cross-check
        // it as well as the receipt. A failed reconfigure can leave an old receipt
        // beside a newer CMake configuration; such a build must never be flashed.
        String configuredTarget = BuildArtifacts.configuration(build).getOrDefault("CONFIG_BOARD_TARGET", "");
        if (configuredTarget.startsWith("\"") && configuredTarget.endsWith("\"") && configuredTarget.length() >= 2)
            configuredTarget = configuredTarget.substring(1, configuredTarget.length() - 1);
        if (!configuredTarget.isBlank() && !configuredTarget.equals(exactBoard))
            throw new IOException("Configured Zephyr board target conflicts with build receipt: " + configuredTarget);
        if (action.equals("run") && !exactBoard.startsWith("qemu_"))
            throw new IOException("Run in Emulator requires a QEMU target. Use Flash for physical hardware.");
        if (!action.equals("run")) {
            Path runners = build.resolve("zephyr/runners.yaml");
            if (!Files.isRegularFile(runners) || Files.size(runners) == 0 || !runners.toRealPath().startsWith(build))
                throw new IOException("Missing generated Zephyr runner configuration. Rebuild this board first.");
            // A successful old build receipt alone is insufficient: mandatory image files
            // could have been deleted or replaced since compilation.
            BuildArtifacts.verify(build);
        }
        var args = new ArrayList<String>();
        if (action.equals("run")) {
            args.addAll(List.of("build", "--build-dir", WestCommands.cmakePath(build.toString()), "--target", "run"));
        } else {
            args.addAll(List.of(action, "--build-dir", WestCommands.cmakePath(build.toString()), "--no-rebuild"));
            if (!runner.isBlank()) args.addAll(List.of("--runner", runner));
        }
        return new Plan(action, exactBoard, build, args);
    }
}
