// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdPartCompatibility;

public final class SvdPartCompatibilityTests {
    private static void check(boolean result, String reason) {
        if (!result) throw new AssertionError(reason);
    }
    public static void main(String[] args) {
        check(SvdPartCompatibility.matches("mspm0g3507", "MSPM0G350X"), "real TI family");
        check(SvdPartCompatibility.matches("mspm0g3507", "MSPM0G3507"), "exact TI");
        check(!SvdPartCompatibility.matches("mspm0g3506", "MSPM0G350X"), "wrong TI chip");
        check(!SvdPartCompatibility.matches("esp32s3", "MSPM0G350X"), "wrong vendor");
        check(!SvdPartCompatibility.matches("", "MSPM0G350X"), "missing device");
        check(!SvdPartCompatibility.matches("mspm0g3507", ""), "missing description");
        System.out.println("SvdPartCompatibilityTests PASS");
    }
}
