// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdPreferredSource;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SvdPreferredSourceTests {
    private static void check(boolean b, String message) {
        if (!b) throw new AssertionError(message);
    }
    private static String xml(String chip, String address) {
        return "<device><name>" + chip + "</name><size>32</size><peripherals>"
                + "<peripheral><name>GPIO</name><baseAddress>" + address + "</baseAddress>"
                + "<registers><register><name>DATA</name><addressOffset>0</addressOffset>"
                + "</register></registers></peripheral></peripherals></device>";
    }
    public static void main(String[] args) throws Exception {
        Path file = Files.createTempFile("random-device-registers", ".xml");
        try {
            Files.writeString(file, xml("MSPM0G3507", "0x40000000"));
            var verified = SvdPreferredSource.inspect("lp_mspm0g3507/mspm0g3507", file);
            check(verified != null && verified.registers() == 1, "manual import verified irrespective of filename");
            check(SvdPreferredSource.reopen("lp_mspm0g3507/mspm0g3507",
                    file, verified.sha256()) != null, "exact source reopened on next launch");
            check(SvdPreferredSource.reopen("lp_mspm0g3507/mspm0g3506",
                    file, verified.sha256()) == null, "no cross-target reuse");
            check(SvdPreferredSource.reopen("lp_mspm0g3507/mspm0g3507",
                    file, "bad") == null, "unverified digest refused");
            Files.writeString(file, xml("MSPM0G3507", "0x50000000"));
            check(SvdPreferredSource.reopen("lp_mspm0g3507/mspm0g3507",
                    file, verified.sha256()) == null, "changed source invalidates cached approval");
            Files.writeString(file, xml("OTHERCHIP", "0x40000000"));
            check(SvdPreferredSource.inspect("lp_mspm0g3507/mspm0g3507", file) == null,
                    "another chip must never populate the explorer");
        } finally { Files.deleteIfExists(file); }
        System.out.println("SvdPreferredSourceTests PASS");
    }
}
