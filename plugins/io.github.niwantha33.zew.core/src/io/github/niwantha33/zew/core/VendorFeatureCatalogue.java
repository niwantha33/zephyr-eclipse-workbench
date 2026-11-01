// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.List;
import java.util.Locale;

/**
 * Reviewed, exact-part public silicon-feature summaries (NOT an SVD, driver,
 * Devicetree node, pinmux configuration, package pad or runtime observation).
 * Unknown parts return no catalogue; no family-based inference is permitted.
 */
public final class VendorFeatureCatalogue {
    private VendorFeatureCatalogue() {}
    public static final String TI_G3507_SOURCE = "https://www.ti.com/product/MSPM0G3507";

    public record Feature(String category, String silicon, String unit,
                          String details, String source) {}
    public record Catalogue(String part, String source, List<Feature> features) {
        public Catalogue { features = List.copyOf(features); }
    }
    public static Catalogue exactPart(String board) {
        String part = SvdAutoDiscovery.partFromBoard(board).toLowerCase(Locale.ROOT);
        if (!part.equals("mspm0g3507")) return null;
        // Counts and qualifications from TI's MSPM0G3507 product specification.
        // PWM is a CHANNEL ceiling, not a controller count; GPIO pads depend on
        // the exact package, therefore GPIO availability is not enumerated here.
        var features = List.of(
                new Feature("ADC", "2", "12-bit ADC units", "Two 4 Msps SAR ADCs; external channel availability depends on package", TI_G3507_SOURCE),
                new Feature("DAC", "1", "12-bit DAC unit", "1 Msps DAC with output buffer", TI_G3507_SOURCE),
                new Feature("UART", "4", "UART units", "One supports advanced modes including LIN; board routing is separate", TI_G3507_SOURCE),
                new Feature("I2C", "2", "I2C units", "FM+ capable; not proof of matching Zephyr DTS/driver", TI_G3507_SOURCE),
                new Feature("SPI", "2", "SPI units", "One supports up to 32 Mbit/s; pin selection requires package verification", TI_G3507_SOURCE),
                new Feature("CAN", "1", "CAN-FD controller", "CAN 2.0 A/B and CAN-FD; transceiver is external", TI_G3507_SOURCE),
                new Feature("Timers", "7", "timer units", "TI product total; not necessarily seven Zephyr device nodes", TI_G3507_SOURCE),
                new Feature("PWM", "up to 22", "PWM channels", "Channel capacity, not number of timer/PWM controllers", TI_G3507_SOURCE),
                new Feature("DMA", "1", "DMA controller", "Seven DMA channels; DTS/driver support is independent", TI_G3507_SOURCE),
                new Feature("RTC", "1", "RTC block", "Calendar/alarm capability; driver/runtime state unverified", TI_G3507_SOURCE),
                new Feature("Watchdog", "2", "window watchdog units", "Actual DTS names/status and application use are independent", TI_G3507_SOURCE)
        );
        return new Catalogue(part, TI_G3507_SOURCE, features);
    }
}
