// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Execute a verified bundled CTF helper under both standard CPython and the
 * managed Windows embedded python313._pth interpreter.
 *
 * The embedded runtime's isolated sys.path does not include the invoked
 * script directory, even when the script is an absolute path. Add only the
 * already staged, trusted helper directory for this one process; do not
 * disable _pth isolation, depend on cwd/PYTHONPATH, or allow user source
 * directories into the import path.
 *
 * Use this exact launch contract for both --help preflight and live capture,
 * so validation cannot pass with a different import environment.
 */
final class TracePythonLaunch {
    static final String BOOTSTRAP =
            "import os,sys,runpy;"
            + "source=sys.argv.pop(1);"
            + "sys.path.insert(0,os.path.dirname(source));"
            + "runpy.run_path(source,run_name='__main__')";

    private TracePythonLaunch() {}

    static List<String> command(String python, Path stagedScript, String... arguments)
            throws IOException {
        if (python == null || python.isBlank() || stagedScript == null
                || !Files.isRegularFile(stagedScript))
            throw new IOException("Managed Python or a staged CTF helper is missing.");
        ArrayList<String> command = new ArrayList<>();
        command.add(python);
        command.add("-I");
        command.add("-X");
        command.add("utf8");
        command.add("-c");
        command.add(BOOTSTRAP);
        command.add(stagedScript.toAbsolutePath().normalize().toString());
        for (String argument : arguments) {
            if (argument == null) throw new IOException("Invalid CTF helper argument.");
            command.add(argument);
        }
        return List.copyOf(command);
    }
}
