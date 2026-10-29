// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Path;
import java.util.List;
import io.github.niwantha33.zew.core.OpenOcdThreadCheck.Region;

public final class OpenOcdMetadataSmoke {
    public static void run(org.osgi.framework.Bundle ui, Path fixture) throws Exception {
        var extractor = ui.loadClass("io.github.niwantha33.zew.ui.OpenOcdThreadMetadata");
        for (String file : List.of("thread-32.elf", "thread-64.elf")) {
            @SuppressWarnings("unchecked")
            var regions = (List<Region>) extractor.getMethod("read", Path.class).invoke(null, fixture.resolve(file));
            if (regions.size() != 3 || !regions.get(0).bytes().equals(List.of(4))
                    || regions.get(2).bytes().size() != 60 || regions.stream().anyMatch(r -> r.address() <= 0))
                throw new AssertionError("ELF metadata extraction: " + file);
            System.out.println("PASS thread metadata: static symbol bytes and target addresses from " + file);
        }
        try {
            extractor.getMethod("read", Path.class).invoke(null, fixture.resolve(".zew-build/first/helper.o"));
            throw new AssertionError("ELF without Zephyr metadata accepted");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            if (!(expected.getCause() instanceof java.io.IOException)) throw expected;
            System.out.println("PASS thread metadata: reject missing Zephyr symbols");
        }
        var commands = ui.loadClass("io.github.niwantha33.zew.ui.ZephyrDebugServicesFactory$OpenOcdCommands").getConstructor().newInstance();
        var context = org.eclipse.cdt.dsf.datamodel.IDMContext.class;
        var command = commands.getClass().getMethod("createMITargetSelect", context, String.class, String.class, boolean.class)
                .invoke(commands, null, "127.0.0.1", "3333", false);
        String text = (String) command.getClass().getMethod("constructCommand").invoke(command);
        if (!text.contains("extended-remote") || !text.contains("127.0.0.1:3333")) throw new AssertionError(text);
        System.out.println("PASS thread metadata: native DSF OpenOCD connection uses extended-remote");
    }
}
