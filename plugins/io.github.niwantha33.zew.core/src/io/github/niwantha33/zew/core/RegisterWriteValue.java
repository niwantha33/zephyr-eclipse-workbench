// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.core;

import java.math.BigInteger;

/** SVD-bounded explicit edits. Does not access the target or authorise a debug session. */
public final class RegisterWriteValue {
    private RegisterWriteValue() {}

    public static BigInteger parse(SvdDevice.Register register, String entered) {
        if (register == null || !register.writable())
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
        return result;
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
