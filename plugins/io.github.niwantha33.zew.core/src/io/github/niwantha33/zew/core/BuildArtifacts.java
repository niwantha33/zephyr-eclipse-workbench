// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies the outputs requested by the resolved Kconfig, after all build steps exit successfully. */
public final class BuildArtifacts {
    private BuildArtifacts() {}

    public static Map<String, String> configuration(Path build) throws IOException {
        Path config = build.resolve("zephyr/.config");
        if (!Files.isRegularFile(config) || Files.size(config) > 8L * 1024 * 1024)
            throw new IOException("Missing or oversized generated Kconfig: " + config + ". Reconfigure this target.");
        var values = new LinkedHashMap<String, String>();
        for (String line : Files.readAllLines(config)) {
            if (line.startsWith("CONFIG_") && line.contains("=")) {
                int separator = line.indexOf('=');
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return Map.copyOf(values);
    }

    public static Set<Path> required(Path build) throws IOException {
        var config = configuration(build);
        String name = config.getOrDefault("CONFIG_KERNEL_BIN_NAME", "\"zephyr\"");
        if (name.startsWith("\"") && name.endsWith("\"")) name = name.substring(1, name.length() - 1);
        if (!name.matches("[A-Za-z0-9_.-]+") || name.equals(".") || name.equals(".."))
            throw new IOException("Unsupported kernel output name; refusing output outside managed build: " + name);
        var outputs = new LinkedHashSet<Path>();
        outputs.add(build.resolve("zephyr/" + name + ".elf"));
        for (var output : Map.of("BIN", ".bin", "HEX", ".hex", "S19", ".s19", "UF2", ".uf2").entrySet())
            if ("y".equals(config.get("CONFIG_BUILD_OUTPUT_" + output.getKey())))
                outputs.add(build.resolve("zephyr/" + name + output.getValue()));
        // Zephyr's image-generation CMake publishes mandatory signing
        // byproducts only when the corresponding post-build step is enabled.
        Path cache = build.resolve("CMakeCache.txt");
        if (Files.isRegularFile(cache) && Files.size(cache) <= 8L * 1024 * 1024) {
            for (String line : Files.readAllLines(cache)) {
                if (!line.matches("BYPRODUCT_KERNEL_[A-Z0-9_]+_NAME:FILEPATH=.+")) continue;
                String value = line.substring(line.indexOf('=') + 1);
                Path output = Path.of(value);
                if (!output.isAbsolute()) output = build.resolve(output);
                output = output.toAbsolutePath().normalize();
                // CMake may record verified Windows short aliases. Compare
                // filesystem identities, not different spellings of one path.
                output = output.getParent().toRealPath().resolve(output.getFileName());
                if (!output.startsWith(build.toRealPath()))
                    throw new IOException("Configured image byproduct escapes this build: " + value);
                outputs.add(output);
            }
        }
        return Set.copyOf(outputs);
    }

    public static void verify(Path build) throws IOException {
        Path realBuild = build.toRealPath();
        for (Path output : required(build)) {
            if (!Files.isRegularFile(output) || Files.size(output) == 0)
                throw new IOException("Mandatory firmware output missing or empty: " + output + ". Inspect image generation/signing diagnostics.");
            if (output.getFileName().toString().endsWith(".elf")) {
                try (var input = Files.newInputStream(output)) {
                    if (!java.util.Arrays.equals(input.readNBytes(4), new byte[] {0x7f, 'E', 'L', 'F'}))
                        throw new IOException("Firmware ELF header is invalid: " + output);
                }
            }
            if (!output.toRealPath().startsWith(realBuild))
                throw new IOException("Firmware output escapes managed build directory: " + output);
        }
    }

    public static void verifyArchitecture(Path build) throws IOException {
        var config = configuration(build);
        var families = new LinkedHashSet<String>();
        for (String key : List.of("ARM", "ARM64", "RISCV", "XTENSA", "X86", "ARC"))
            if ("y".equals(config.get("CONFIG_" + key))) families.add(key);
        if (families.contains("ARM64")) families.remove("ARM");
        if (families.size() != 1) throw new IOException("Cannot verify the configured CPU architecture: " + families);
        String arch = families.iterator().next();
        String prefix = Map.of("ARM", "arm-zephyr-eabi", "ARM64", "aarch64-zephyr-elf",
            "RISCV", "riscv64-zephyr-elf", "XTENSA", "xtensa-", "X86", "x86_64-zephyr-elf", "ARC", "arc").get(arch);
        Path cache = build.resolve("CMakeCache.txt");
        if (!Files.isRegularFile(cache) || Files.size(cache) > 8L * 1024 * 1024)
            throw new IOException("Missing or oversized CMake cache during compiler verification.");
        String compiler = Files.readAllLines(cache).stream().filter(line -> line.startsWith("CMAKE_C_COMPILER:") && line.contains("="))
            .map(line -> line.substring(line.indexOf('=') + 1)).findFirst().orElse("").replace('\\', '/');
        String executable = compiler.substring(compiler.lastIndexOf('/') + 1);
        if (!executable.startsWith(prefix) || !(executable.endsWith("gcc") || executable.endsWith("gcc.exe")))
            throw new IOException("Configured CPU " + arch + " conflicts with managed compiler " + compiler + ". Check the exact board/CPU selection.");
    }
}
