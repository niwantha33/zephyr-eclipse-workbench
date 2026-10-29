// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;
import org.eclipse.core.runtime.IProgressMonitor;
import io.github.niwantha33.zew.core.BuildArtifacts;
import io.github.niwantha33.zew.core.OpenOcdThreadCheck;
import io.github.niwantha33.zew.core.ProcessRunner;
import io.github.niwantha33.zew.core.TargetOperations;

/** Validate the declared flash image and require metadata readback before West reports success. */
final class OpenOcdFlashPreparation {
    private OpenOcdFlashPreparation() {}
    static TargetOperations.Plan prepare(TargetOperations.Plan original, Properties settings,
            IProgressMonitor monitor, Consumer<String> log) throws Exception {
        var plan = SharedDebugTools.bindFlashTool(original, settings);
        String yaml = Files.readString(plan.buildDirectory().resolve("zephyr/runners.yaml"));
        if (!TargetOperations.usesOpenOcdFlash(plan, yaml)) return plan;
        int toolIndex = plan.westArguments().indexOf("--openocd");
        log.accept("OpenOCD Flash and Debug use the same selected tool: " + plan.westArguments().get(toolIndex + 1));
        log.accept("OpenOCD Flash requires image readback verification (--verify).");
        var config = BuildArtifacts.configuration(plan.buildDirectory());
        if (!"y".equals(config.get("CONFIG_DEBUG_THREAD_INFO"))) return plan;
        Path elf = BuildArtifacts.required(plan.buildDirectory()).stream().filter(p -> p.toString().endsWith(".elf"))
                .findFirst().orElseThrow(() -> new IOException("No ELF for flash metadata validation"));
        var regions = OpenOcdThreadMetadata.read(elf);
        var arguments = new ArrayList<>(plan.westArguments());
        // Direct-mapped Cortex-M flash lets us compare HEX addresses with the ELF's static metadata VMAs.
        // Other load/virtual address models retain the board runner's image selection and verify on-target.
        if ("y".equals(config.get("CONFIG_CPU_CORTEX_M")) && "y".equals(config.get("CONFIG_XIP"))) {
            Path script = Activator.getDefault().getStateLocation().append("tools/openocd_flash_image.py").toFile().toPath();
            Files.createDirectories(script.getParent());
            try (var source = OpenOcdFlashPreparation.class.getResourceAsStream("/tools/openocd_flash_image.py")) {
                if (source == null) throw new IOException("Missing bundled OpenOCD flash image validator");
                Files.copy(source, script, StandardCopyOption.REPLACE_EXISTING);
            }
            var command = new ArrayList<>(List.of(settings.getProperty("python"), script.toString(),
                    "--build", plan.buildDirectory().toString(), "--elf", elf.toString()));
            for (var region : regions) {
                var hex = new StringBuilder();
                for (int b : region.bytes()) hex.append(String.format("%02x", b));
                command.addAll(List.of("--region", Long.toHexString(region.address()) + ":" + hex));
            }
            String output = new ProcessRunner().run(command, Path.of(settings.getProperty("workspace")), Duration.ofSeconds(30),
                    monitor::isCanceled, line -> { if (!line.startsWith("IMAGE\t") && !line.startsWith("TYPE\t")) log.accept(line); },
                    ProjectContext.environment(settings)).requireCompleteOutput();
            String image = null, type = null;
            for (String line : output.lines().toList()) {
                if (line.startsWith("IMAGE\t")) image = new String(Base64.getDecoder().decode(line.substring(6)), StandardCharsets.UTF_8);
                if (line.startsWith("TYPE\t")) type = line.substring(5);
            }
            if (image == null || type == null || !List.of("hex", "elf", "bin").contains(type))
                throw new IOException("Incomplete flash image verification result");
            arguments.addAll(List.of("--file", image, "--file-type", type));
            log.accept("Verified declared " + type.toUpperCase(java.util.Locale.ROOT) + " flash image includes the selected ELF thread metadata: " + image);
            log.accept("Matching debug ELF: " + elf);
        }
        arguments.addAll(List.of("--cmd-post-verify", OpenOcdThreadCheck.postFlashCommand(regions)));
        log.accept("Flash will compare thread metadata with target memory before reset/run.");
        return new TargetOperations.Plan(plan.action(), plan.board(), plan.buildDirectory(), arguments);
    }
}
