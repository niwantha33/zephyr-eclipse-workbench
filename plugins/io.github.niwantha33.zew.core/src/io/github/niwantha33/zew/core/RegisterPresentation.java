// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;

/** Plain-language SVD status for the register explorer. No hardware reads. */
public final class RegisterPresentation {
    private RegisterPresentation() {}

    public static String state(SvdDevice.Register reg, BigInteger value, String error) {
        if (!reg.readable()) {
            if ("write-only".equals(reg.access())) return "Write only";
            if (!reg.readAction().isBlank()) return "Read changes hardware";
            return "Read protected";
        }
        if (error != null && !error.isBlank()) return "Read failed";
        return value == null ? "Not sampled" : "Read OK";
    }

    public static String explanation(SvdDevice.Register reg, String error) {
        if (!reg.readable()) {
            if ("write-only".equals(reg.access()))
                return (reg.name().toUpperCase(java.util.Locale.ROOT).contains("DOUT")
                        ? "This register controls output pins. "
                        : "This register accepts write commands. ")
                        + "The chip's register description says it cannot be read; "
                        + "a blank value does not mean zero. "
                        + "For pin state, look for a readable input/status register in the same peripheral.";
            if (!reg.readAction().isBlank() || reg.readBlockReason().contains("readAction"))
                return "Reading this register can change hardware state (such as clearing a flag). "
                        + "Workbench will not read it automatically.";
            return "This register contains bits that cannot safely be read together, "
                    + "or its access rules are unknown. The read is deliberately blocked.";
        }
        if (error != null && !error.isBlank())
            return "The debugger could not read this address. Check the halted target, "
                    + "peripheral clock/power, chip identity, and GDB/OpenOCD console. "
                    + "The failed value is not zero.";
        return "A value appears only after a successful read from a halted debug session. "
                + "Reset value is a chip specification, not a live reading.";
    }
}
