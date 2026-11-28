// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolve Zephyr 4.4 native runner options without searching host PATH. */
public final class ManagedVendorDebugTools {
    private ManagedVendorDebugTools() {}

    private static Path executable(String value, String description, String... names) throws IOException {
        if (value == null || value.isBlank())
            throw new IOException("Select " + description + " in Zephyr > Manage Shared Debug Tools.");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || Files.size(path) == 0)
                throw new IOException("Select an existing absolute executable for " + description + ".");
            Path real = path.toRealPath();
            String actual = real.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!List.of(names).contains(actual))
                throw new IOException("Unexpected executable name for " + description + ": " + actual);
            return real;
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid executable path for " + description + ".", error);
        }
    }

    public static List<Path> jlink(String selected) throws IOException {
        Path server = executable(selected, "J-Link GDB Server", "jlinkgdbservercl.exe", "jlinkgdbserver.exe");
        Path commander = executable(server.resolveSibling("JLink.exe").toString(), "matching J-Link Commander", "jlink.exe");
        if (!commander.getParent().equals(server.getParent()))
            throw new IOException("J-Link server and Commander must belong to the same installation.");
        return List.of(server, commander);
    }

    public static Path pyocd(String selected, Path root, Path python) throws IOException {
        if (selected != null && !selected.isBlank())
            return executable(selected, "pyOCD", "pyocd.exe");
        if (root == null || python == null || !root.isAbsolute() || !python.isAbsolute())
            throw new IOException("Managed pyOCD requires an absolute setup and Python path.");
        Path realRoot = root.toRealPath();
        Path interpreter = python.toRealPath();
        if (!Files.isRegularFile(interpreter) || !interpreter.startsWith(realRoot))
            throw new IOException("Python is outside the selected managed setup.");
        Path parent = interpreter.getParent();
        for (Path candidate : List.of(parent.resolve("Scripts/pyocd.exe"), parent.resolve("pyocd.exe"))) {
            if (!Files.exists(candidate)) continue;
            Path found = executable(candidate.toString(), "pyOCD", "pyocd.exe");
            if (!found.startsWith(realRoot))
                throw new IOException("Automatic pyOCD executable escapes the managed setup.");
            return found;
        }
        throw new IOException("pyOCD is not installed in the selected setup. Install compatible pyOCD "
                + "there or explicitly select a trusted installation in Zephyr > Manage Shared Debug Tools.");
    }

    public static TargetOperations.Plan bind(TargetOperations.Plan plan, String runner, String jlinkServer,
                                             String pyocdExecutable, Path root, Path python) throws IOException {
        if (!"debugserver".equals(plan.action()))
            throw new IOException("Vendor debug binding requires a debugserver plan.");
        var args = new ArrayList<>(plan.westArguments());
        int index = args.indexOf("--runner");
        if (index >= 0 && (index + 1 >= args.size() || !runner.equals(args.get(index + 1))))
            throw new IOException("Selected vendor debugger does not match the runner plan.");
        if (index < 0) args.addAll(List.of("--runner", runner));
        if ("jlink".equals(runner)) {
            var pair = jlink(jlinkServer);
            args.addAll(List.of("--gdbserver", pair.get(0).toString(), "--commander", pair.get(1).toString()));
        } else if ("pyocd".equals(runner)) {
            args.addAll(List.of("--pyocd", pyocd(pyocdExecutable, root, python).toString()));
        } else {
            throw new IOException("Unsupported vendor debug binding: " + runner);
        }
        return new TargetOperations.Plan(plan.action(), plan.board(), plan.buildDirectory(), args);
    }
}
