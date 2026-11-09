// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path;
import io.github.niwantha33.zew.core.ZephyrSelectionIdentity;

public final class ZephyrSelectionIdentityTests {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(String project, Path path, String node) {
        try {
            new ZephyrSelectionIdentity(project, path, node);
            throw new AssertionError("Expected invalid selection: " + node);
        } catch (IllegalArgumentException expected) {
            // Unsafe or ambiguous identity must fail closed.
        }
    }
    public static void main(String[] args) {
        var a = new ZephyrSelectionIdentity("my-app",
                Path.of("workspace/app/.zew-build/debug"), "/soc/gpio@4000");
        var same = new ZephyrSelectionIdentity("my-app",
                Path.of("workspace/app/.zew-build/debug/../debug"), "/soc/uart@5000");
        check(a.sameBuild(same), "Same selected build can follow another exact node");
        check(!a.equals(same), "Changing node changes the selection");
        check(!a.sameBuild(new ZephyrSelectionIdentity("my-app",
                Path.of("workspace/app/.zew-build/release"), a.nodePath())),
                "A different build profile must not cross-link");
        check(!a.sameBuild(new ZephyrSelectionIdentity("other-app",
                a.buildDirectory(), a.nodePath())), "Another project must not cross-link");
        check(new ZephyrSelectionIdentity("my-app", a.buildDirectory(), null)
                .nodePath().isEmpty(), "Missing node remains a build-only selection");
        rejects("", Path.of("build"), "/soc");
        rejects("my-app", Path.of("build"), "gpio@4000");
        rejects("my-app", Path.of("build"), "/soc/../other");
        rejects("my-app", Path.of("build"), "/soc//gpio");
        System.out.println("ZephyrSelectionIdentityTests passed");
    }
}
