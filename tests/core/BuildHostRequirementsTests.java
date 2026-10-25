// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.BuildHostRequirements;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

public class BuildHostRequirementsTests {
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("zew-host-req-");
        try {
            Path ninja = temp.resolve("build.ninja");
            boolean missingCaught = false;
            try {
                BuildHostRequirements.missingInGeneratedBuild(temp);
            } catch (java.io.IOException expected) {
                missingCaught = true;
            }
            check(missingCaught, "missing Ninja file must be an error");
            Files.writeString(ninja, "# sample cmake target\nrule BUILD\n"
                    + "  command = python IMGTOOL-NOTFOUND sign --version 0.0.0+0\n"
                    + "  command2 = gperf-NOTFOUND\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL", "GPERF")),
                    "Must detect imgtool used in a generated command");
            Files.writeString(ninja, "rule BUILD\n  command = IMGTOOL-NOTFOUND gperf-NOTFOUND GPERF-NOTFOUND\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL", "GPERF")),
                    "Must detect every distinct needed host executable");
            Files.writeString(ninja, "# No signing or other optional missing tools\nrule BUILD\n  command = echo READY\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).isEmpty(),
                    "Optional tools absent from the generated build must not be required");
        } finally {
            try (var files = Files.walk(temp)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(file -> {
                    try { Files.deleteIfExists(file); } catch (java.io.IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
        System.out.println("BuildHostRequirementsTests PASS");
    }
}
