// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.Locale;

/** Explicit SoC-to-shared-model mapping; never infer a family wildcard. */
public final class SvdPartCompatibility {
    private SvdPartCompatibility() {}

    private static String normal(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    public static boolean matches(String part, String svdModel) {
        String chip = normal(part);
        String model = normal(svdModel);
        if (chip.isBlank() || model.isBlank()) return false;
        if (chip.equals(model)) return true;
        // TI's MSPM0G350X SVD covers the MSPM0G3507 device.
        // No general X wildcard is permitted for other devices/vendors.
        return chip.equals("mspm0g3507") && model.equals("mspm0g350x");
    }
}
