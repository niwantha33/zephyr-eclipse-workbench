// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Use the explicitly managed interpreter and only the installed, trusted YAML
 * helper directory. Windows embedded CPython's ._pth isolation does not reliably
 * add the invoked script's directory to sys.path. Executing a Python script
 * directly can fail importing bundled zew_evidence even when staged adjacent.
 *
 * -I ignores ambient PYTHONPATH/user site, and the -c bootstrap adds precisely
 * the trusted helper parent directory. Application and checkout paths are only
 * CLI data; they are never added to the module search path.
 */
public final class YamlPythonLaunch {
    private static final String BOOTSTRAP =
            "import os,sys,runpy;"
            + "source=sys.argv.pop(1);"
            + "sys.path.insert(0,os.path.dirname(source));"
            + "runpy.run_path(source,run_name='__main__')";

    private YamlPythonLaunch() {}

    public static List<String> command(Path python, Path script, String... args)
            throws IOException {
        if (python == null || script == null || !Files.isRegularFile(script))
            throw new IOException("Managed YAML Python interpreter or helper unavailable.");
        ArrayList<String> command = new ArrayList<>();
        command.add(python.toString());
        command.add("-I");
        command.add("-X");
        command.add("utf8");
        command.add("-c");
        command.add(BOOTSTRAP);
        command.add(script.toAbsolutePath().toString());
        if (args != null) {
            for (String arg : args) {
                if (arg == null) throw new IOException("Invalid YAML helper argument.");
                command.add(arg);
            }
        }
        return List.copyOf(command);
    }
}
