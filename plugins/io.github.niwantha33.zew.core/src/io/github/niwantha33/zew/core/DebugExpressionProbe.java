// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Conservative read-only GDB probe for a halted-frame C lvalue.
 * Accepts identifiers, named struct fields and bounded literal array indices;
 * never accepts calls, casts, operators, assignments or pointer dereferences.
 */
public final class DebugExpressionProbe {
    private DebugExpressionProbe() {}

    private static final Pattern LOCATION = Pattern.compile(
            "[A-Za-z_][A-Za-z_0-9]{0,127}(?:\\.[A-Za-z_][A-Za-z_0-9]{0,127}|\\[(?:0|[1-9][0-9]{0,3})\\]){0,12}");
    private static final Pattern ADDRESS = Pattern.compile(
            "(?i)0x([0-9a-f]{1,16})(?:\\s+<[^\\r\n>]{1,160}>)?");

    public static String validateSymbol(String input) {
        String name = input == null ? "" : input.strip();
        if (name.length() > 256 || !LOCATION.matcher(name).matches())
            throw new IllegalArgumentException("Enter a C variable/member path (e.g. lab_stats.heartbeat). "
                    + "Only named fields and literal indices are allowed; no calls, pointers, casts or writes.");
        // For a debugging aid, reject implausibly large literal indices rather than
        // advertising an address generated from accidental unchecked arithmetic.
        var indices = Pattern.compile("\\[([0-9]+)\\]").matcher(name);
        while (indices.find()) if (Integer.parseInt(indices.group(1)) > 1023)
            throw new IllegalArgumentException("Array indices greater than 1023 are not supported by this probe.");
        return name;
    }

    /** A proven, nonzero 32-/64-bit address printed by GDB; not a pointer value. */
    public static String confirmedAddress(String candidate) {
        String input = candidate == null ? "" : candidate.strip();
        var match = ADDRESS.matcher(input);
        if (!match.matches()) return "";
        var value = new java.math.BigInteger(match.group(1), 16);
        return value.signum() == 0 ? "" : "0x" + value.toString(16);
    }

    /** Preserve size only when GDB returned a bounded, nonnegative integer. */
    public static String confirmedSize(String candidate) {
        String raw = candidate == null ? "" : candidate.strip();
        if (!raw.matches("(?i)(0x[0-9a-f]{1,16}|[0-9]{1,20})")) return "";
        try {
            java.math.BigInteger n = raw.startsWith("0x") || raw.startsWith("0X")
                    ? new java.math.BigInteger(raw.substring(2), 16)
                    : new java.math.BigInteger(raw);
            return n.signum() >= 0 && n.bitLength() <= 32 ? n.toString() : "";
        } catch (NumberFormatException invalid) { return ""; }
    }

    public static String describe(String symbol, String value, String address,
                                  String valueError, String addressError) {
        return describe(symbol, value, address, "", valueError, addressError, "");
    }

    public static String describe(String symbol, String value, String address, String size,
                                  String valueError, String addressError, String sizeError) {
        String name = validateSymbol(symbol);
        String v = value == null ? "" : value.strip();
        String a = confirmedAddress(address);
        String sz = confirmedSize(size);
        StringBuilder result = new StringBuilder("Expression: ").append(name)
                .append("\nGDB value: ").append(v.isBlank() ? "UNAVAILABLE" : v)
                .append("\nStorage address: ").append(a.isBlank() ? "UNAVAILABLE" : a)
                .append("\nSize (sizeof, bytes): ").append(sz.isBlank() ? "UNAVAILABLE" : sz);
        if (valueError != null && !valueError.isBlank())
            result.append("\nValue evaluation: ").append(valueError.strip());
        if (addressError != null && !addressError.isBlank())
            result.append("\nAddress evaluation: ").append(addressError.strip());
        if (sizeError != null && !sizeError.isBlank())
            result.append("\nSize evaluation: ").append(sizeError.strip());
        String failure = valueError == null ? "" : valueError.toLowerCase(Locale.ROOT);
        boolean optimized = v.contains("<optimized out>") || v.contains("<unavailable>")
                || failure.contains("optimized out") || failure.contains("unavailable");
        if (optimized && !a.isEmpty()) {
            result.append("\nGDB can locate storage for this symbol but cannot provide its current value. ")
                    .append("Check ELF DWARF, exact compiler command, ELF/target identity and target read permissions.");
        } else if (optimized) {
            result.append("\nGDB could not supply the value; the address was not confirmed either. ")
                    .append("Check whether the symbol is global, emitted in this ELF and available in this frame.");
        } else if (!v.isBlank() && (valueError == null || valueError.isBlank())
                && (addressError == null || addressError.isBlank())) {
            result.append("\nGDB returned a value. It is a halted-session snapshot, not proof of physical GPIO state.");
        } else {
            result.append("\nInconclusive. This view cannot infer an absent variable's value from its C type.");
        }
        if (a.isBlank()) result.append("\nNo confirmed storage address: this value may be a register, "
                + "a compiler temporary, a bitfield, out of scope or otherwise unavailable.");
        return result.append("\nProbe is read-only. No arbitrary pointer is dereferenced or written.")
                .toString();
    }
}
