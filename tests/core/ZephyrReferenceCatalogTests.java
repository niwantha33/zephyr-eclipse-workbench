// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Files;
import io.github.niwantha33.zew.core.ZephyrReferenceCatalog;

public final class ZephyrReferenceCatalogTests {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] ignored) throws Exception {
        var root = Files.createTempDirectory("zew-doc-release");
        try {
            Files.createDirectories(root.resolve("zephyr"));
            var version = root.resolve("zephyr/VERSION");
            Files.writeString(version, """
                    VERSION_MAJOR = 4
                    VERSION_MINOR = 4
                    PATCHLEVEL = 0
                    VERSION_TWEAK = 0
                    EXTRAVERSION =
                    """);
            check("4.4.0".equals(ZephyrReferenceCatalog.releaseFromWorkspace(root)),
                    "exact local release required");
            var ti = ZephyrReferenceCatalog.forTarget("lp_mspm0g3507/mspm0g3507", "4.4.0");
            check(ti.stream().anyMatch(link -> link.url().equals(
                    "https://www.ti.com/lit/pdf/slau846")), "TI TRM for exact board");
            check(ti.stream().anyMatch(link -> link.url().startsWith(
                    "https://docs.zephyrproject.org/4.4.0/")), "version-specific Zephyr links");
            check(ZephyrReferenceCatalog.forTarget("some_other_board", "4.4.0").stream()
                    .noneMatch(link -> link.url().contains("ti.com")), "no guessed vendor matches");
            check(ZephyrReferenceCatalog.forTarget("lp_mspm0g3507_fake", "4.4.0").stream()
                    .noneMatch(link -> link.url().contains("ti.com")), "no board-prefix collision");
            check(ZephyrReferenceCatalog.forTarget("", "../../bad").stream()
                    .allMatch(link -> !link.url().contains("..")), "no URL path injection");
            Files.writeString(version, "VERSION_MAJOR = 4\nVERSION_MINOR = 5\nPATCHLEVEL = 99\n");
            check(ZephyrReferenceCatalog.releaseFromWorkspace(root).isEmpty(),
                    "development snapshot does not claim a stable version");
            Files.writeString(version, "VERSION_MAJOR = 4\nVERSION_MINOR = 4\nPATCHLEVEL = 0\nEXTRAVERSION = rc1\n");
            check(ZephyrReferenceCatalog.releaseFromWorkspace(root).isEmpty(),
                    "prerelease does not claim a stable version");
            System.out.println("ZephyrReferenceCatalogTests OK");
        } finally {
            Files.deleteIfExists(root.resolve("zephyr/VERSION"));
            Files.deleteIfExists(root.resolve("zephyr"));
            Files.deleteIfExists(root);
        }
    }
}
