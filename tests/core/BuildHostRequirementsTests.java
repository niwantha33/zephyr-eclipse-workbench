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
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL")),
                    "Must detect imgtool used in a generated command");
            Files.writeString(ninja, "rule BUILD\n  command = IMGTOOL-NOTFOUND gperf-NOTFOUND GPERF-NOTFOUND\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL", "GPERF")),
                    "Must detect every distinct needed host executable");
            Files.writeString(ninja, "# IMGTOOL-NOTFOUND is a comment\nrule BUILD\n  command = echo READY\n  description = GPERF-NOTFOUND optional probe\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).isEmpty(),"Comments and descriptions are not executable requirements");
            Files.createDirectories(temp.resolve("CMakeFiles"));
            Files.writeString(temp.resolve("CMakeFiles/rules.ninja"),"rule CUSTOM\n  command = $PRE_LINK && $COMMAND\n");
            Files.writeString(ninja,"include CMakeFiles/rules.ninja\n  PRE_LINK = python IMGTOOL-NOTFOUND sign\n  COMMAND = ESPTOOL-NOTFOUND write-flash\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL","ESPTOOL")),"Included command rules and referenced variable bindings must be inspected");
            Files.writeString(ninja,"rule CUSTOM\n  command = python $\n    IMGTOOL-NOTFOUND sign\n");
            check(BuildHostRequirements.missingInGeneratedBuild(temp).equals(Set.of("IMGTOOL")),"Ninja line continuations must not hide missing commands");
            Files.writeString(ninja,"include ../outside.ninja\n");
            boolean includeRejected=false;try{BuildHostRequirements.missingInGeneratedBuild(temp);}catch(java.io.IOException e){includeRejected=true;}
            check(includeRejected,"Missing or out-of-build Ninja files fail closed");
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

