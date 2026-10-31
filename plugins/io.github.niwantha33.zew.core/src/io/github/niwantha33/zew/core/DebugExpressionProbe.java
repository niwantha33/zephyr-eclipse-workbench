// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

/**
 * Conservative read-only single-symbol diagnostic for a halted GDB frame.
 * No arbitrary C expressions, function calls, assignments or memory writes.
 */
public final class DebugExpressionProbe {
    private DebugExpressionProbe() {}

    public static String validateSymbol(String input) {
        String symbol = input == null ? "" : input.strip();
        if (!symbol.matches("[A-Za-z_][A-Za-z_0-9]{0,127}"))
            throw new IllegalArgumentException("Enter one C identifier only (e.g. gpio_dev). "
                    + "Function calls, expressions, casts and writes are not accepted.");
        return symbol;
    }

    public static String describe(String symbol, String value, String address,
                                  String valueError, String addressError) {
        String name = validateSymbol(symbol);
        String v = value == null ? "" : value.strip();
        String a = address == null ? "" : address.strip();
        StringBuilder result = new StringBuilder("Symbol: ").append(name)
                .append("\nGDB value: ").append(v.isBlank() ? "UNAVAILABLE" : v)
                .append("\nGDB address-of: ").append(a.isBlank() ? "UNAVAILABLE" : a);
        if (valueError != null && !valueError.isBlank())
            result.append("\nValue evaluation: ").append(valueError.strip());
        if (addressError != null && !addressError.isBlank())
            result.append("\nAddress evaluation: ").append(addressError.strip());
        String failure = valueError == null ? "" : valueError.toLowerCase(java.util.Locale.ROOT);
        boolean optimized = v.contains("<optimized out>") || v.contains("<unavailable>")
                || failure.contains("optimized out") || failure.contains("unavailable");
        boolean addressKnown = a.matches("0[xX][0-9a-fA-F]+") && !a.matches("0[xX]0+");
        if (optimized && addressKnown) {
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
        return result.append("\nProbe is read-only. No memory address is dereferenced or written.")
                .toString();
    }
}
