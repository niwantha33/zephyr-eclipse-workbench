// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;

/** SVD-bounded explicit edits. Does not access the target or authorise a debug session. */
public final class RegisterWriteValue {
    private RegisterWriteValue() {}

    public static BigInteger parse(SvdDevice.Register register, String entered) {
        return parse(register, entered, null);
    }

    public static BigInteger parse(SvdDevice.Register register, String entered,
            BigInteger previous) {
        if (register == null || !register.writeAllowed())
            throw new IllegalArgumentException("SVD does not authorise a safe register edit");
        if (entered == null) throw new IllegalArgumentException("Enter a hexadecimal or decimal value");
        String value = entered.strip().replace("_", "");
        BigInteger result;
        try {
            if (value.matches("0[xX][0-9a-fA-F]+"))
                result = new BigInteger(value.substring(2), 16);
            else if (value.matches("[0-9]+"))
                result = new BigInteger(value, 10);
            else throw new IllegalArgumentException("Use 0x... hex or unsigned decimal");
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid unsigned register value", invalid);
        }
        if (result.signum() < 0 || result.bitLength() > register.size())
            throw new IllegalArgumentException("Value exceeds the " + register.size() + "-bit register width");
        if (!register.validatesConstraint(result, previous))
            throw new IllegalArgumentException("Write violates SVD range/enum/writeAsRead constraint");
        return result;
    }

    /**
     * Special writes are *not* read-modify-write. Every affected bit must share
     * a recognised vendor semantic; preserve neighbouring bits using the
     * neutral write value (zeros for W1*, ones for W0*).
     */
    public static BigInteger specialBit(SvdDevice.Register register,
            SvdDevice.Field field) {
        if (register == null || field == null || field.width() != 1
                || register.size() > 32
                || !java.util.List.of("read-write", "write-only").contains(register.access()))
            throw new IllegalArgumentException("No safe one-bit special write for this register");
        String action = register.modifiedWriteValues();
        if (action.isBlank()) {
            if (register.fields().isEmpty())
                throw new IllegalArgumentException("Missing vendor bit semantics");
            action = field.modifiedWriteValues();
            if (action.isBlank())
                throw new IllegalArgumentException("Field has no special write semantics");
            BigInteger coverage = BigInteger.ZERO;
            for (var bitField : register.fields()) {
                if (!action.equals(bitField.modifiedWriteValues())
                        || !java.util.List.of("read-write", "write-only")
                            .contains(bitField.access()))
                    throw new IllegalArgumentException("Mixed field write semantics; refusing whole-register write");
                BigInteger mask = BigInteger.ONE.shiftLeft(bitField.width())
                        .subtract(BigInteger.ONE).shiftLeft(bitField.offset());
                if (coverage.and(mask).signum() != 0)
                    throw new IllegalArgumentException("Overlapping SVD field bits");
                coverage = coverage.or(mask);
            }
            if (!coverage.equals(BigInteger.ONE.shiftLeft(register.size()).subtract(BigInteger.ONE)))
                throw new IllegalArgumentException("SVD fields do not cover all register bits");
        } else {
            for (var bitField : register.fields()) {
                if (!bitField.modifiedWriteValues().isBlank()
                        && !action.equals(bitField.modifiedWriteValues()))
                    throw new IllegalArgumentException("Conflicting SVD write semantics");
            }
        }
        if (!register.writeConstraint().isBlank()
                || register.fields().stream().anyMatch(f -> !f.writeConstraint().isBlank()))
            throw new IllegalArgumentException("Special write has constraints; dedicated validation required");
        if (!java.util.List.of("oneToClear", "oneToSet", "oneToToggle",
                "zeroToClear", "zeroToSet", "zeroToToggle").contains(action))
            throw new IllegalArgumentException("Unsupported modifiedWriteValues: " + action);
        BigInteger bit = BigInteger.ONE.shiftLeft(field.offset());
        BigInteger mask = BigInteger.ONE.shiftLeft(register.size()).subtract(BigInteger.ONE);
        return action.startsWith("one") ? bit : mask.xor(bit);
    }

    /** A toggle is only proposed from an actual halted snapshot; never invent zero. */
    public static BigInteger toggle(SvdDevice.Register register, BigInteger snapshot, int bit) {
        if (register == null || !register.writable())
            throw new IllegalArgumentException("SVD does not authorise a safe bit edit");
        if (snapshot == null || snapshot.signum() < 0 || snapshot.bitLength() > register.size())
            throw new IllegalArgumentException("A valid halted snapshot is required for bit edits");
        if (bit < 0 || bit >= register.size())
            throw new IllegalArgumentException("Bit index outside register width");
        return snapshot.flipBit(bit);
    }
}
