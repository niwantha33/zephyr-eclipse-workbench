// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.osgi.framework.Bundle;

/**
 * Assemble an isolated Python runtime directory from exact OSGi bundle assets.
 *
 * FileLocator.toFileURL(bundle.getEntry("scripts/yaml_observe_file.py"))
 * extracts the ONE script from a JAR, not necessarily its sibling package
 * zew_evidence. Running that orphaned script produced ModuleNotFoundError,
 * hidden by the original subprocess stderr=DISCARD.
 *
 * Never execute from source checkout or use the caller's Python search path.
 * Do not copy/edit any application, Zephyr checkout or generated YAML files.
 * A fresh private folder prevents another process changing executable bytes.
 */
public final class YamlBundledScripts {
    private static final int MAX_MODULE_BYTES = 1024 * 1024;
    private static final Set<String> SCRIPTS = Set.of(
            "yaml_observe_file.py", "yaml_diagnostic_inspect.py",
            "yaml_binding_inspect.py", "yaml_hwmv2_inspect.py",
            "yaml_twister_inspect.py", "yaml_validate_patch.py");
    private static final List<String> ENTRIES = List.of(
            "yaml_observe_file.py", "yaml_diagnostic_inspect.py",
            "yaml_binding_inspect.py", "yaml_hwmv2_inspect.py",
            "yaml_twister_inspect.py", "yaml_validate_patch.py",
            "zew_evidence/__init__.py",
            "zew_evidence/yaml_classification.py",
            "zew_evidence/yaml_observation.py");

    private YamlBundledScripts() {}

    public static Stage stage(Bundle bundle) throws IOException {
        if (bundle == null) throw new IOException("YAML plugin bundle is unavailable.");
        Path root = Files.createTempDirectory("zew-bundled-yaml-");
        try {
            for (String member : ENTRIES) {
                URL entry = bundle.getEntry("scripts/" + member);
                if (entry == null)
                    throw new IOException("The installed YAML helper package is incomplete.");
                Path target = root.resolve(member).normalize();
                if (!target.startsWith(root))
                    throw new IOException("Unsafe YAML helper location in bundle.");
                Files.createDirectories(target.getParent());
                try (InputStream input = entry.openStream();
                     OutputStream output = Files.newOutputStream(target,
                             StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    int total = 0;
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_MODULE_BYTES)
                            throw new IOException("The installed YAML helper exceeds its size limit.");
                        output.write(buffer, 0, read);
                    }
                    if (total == 0)
                        throw new IOException("The installed YAML helper is empty.");
                }
            }
            return new Stage(root);
        } catch (IOException | RuntimeException error) {
            cleanup(root);
            throw error;
        }
    }

    private static void cleanup(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException locked) {
                    // Windows may delay releasing a Python process's handles.
                    path.toFile().deleteOnExit();
                }
            });
        } catch (IOException ignored) {
            root.toFile().deleteOnExit();
        }
    }

    public static final class Stage implements AutoCloseable {
        private final Path root;
        private boolean closed;

        private Stage(Path root) { this.root = root; }

        public Path script(String name) throws IOException {
            if (closed || !SCRIPTS.contains(name))
                throw new IOException("Invalid installed YAML helper request.");
            Path script = root.resolve(name);
            if (!Files.isRegularFile(script) || Files.isSymbolicLink(script))
                throw new IOException("The staged YAML helper is unavailable.");
            return script;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            cleanup(root);
        }
    }
}
