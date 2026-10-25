// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Validated transport for the managed Windows path adapter; original Eclipse paths stay intact. */
public record BuildPaths(String application, String build, String workspace, String sdk, boolean freshConfiguration) {
    public static BuildPaths parse(String output) throws IOException {
        String[] lines = output.strip().split("\\R");
        if (lines.length != 1) throw new IOException("Unexpected build path adapter output");
        String[] cells = lines[0].split("\\t", -1);
        if (cells.length != 6 || !cells[0].equals("ZEW_PATHS_1")) throw new IOException("Invalid build path adapter protocol");
        try {
            for (int i=1; i<cells.length; i++) {
                cells[i] = new String(Base64.getDecoder().decode(cells[i]), StandardCharsets.UTF_8);
                if (cells[i].isBlank() || cells[i].contains("\n") || cells[i].contains("\r") || cells[i].contains("\0"))
                    throw new IllegalArgumentException("Invalid path field");
            }
            if (!cells[5].equals("true") && !cells[5].equals("false")) throw new IllegalArgumentException("Invalid refresh flag");
            return new BuildPaths(cells[1], cells[2], cells[3], cells[4], Boolean.parseBoolean(cells[5]));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid build path adapter value", error); }
    }
}
