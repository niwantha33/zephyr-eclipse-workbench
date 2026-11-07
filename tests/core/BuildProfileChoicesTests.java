// SPDX-License-Identifier: Apache-2.0
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.niwantha33.zew.core.BuildConfiguration;
import io.github.niwantha33.zew.core.BuildProfile;
import io.github.niwantha33.zew.core.BuildProfileChoices;

/** Quick selector must distinguish names from stable IDs, and never guess missing profiles. */
public final class BuildProfileChoicesTests {
    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
    private static void fails(BuildProfileChoices.Choice ignored) { throw new AssertionError("Expected unavailable profile to fail"); }
    public static void main(String[] args) throws Exception {
        Path app = Files.createTempDirectory("zew-profile-choices-");
        new BuildProfile("debug-rtt", "Debug RTT", BuildConfiguration.DEBUG,
                "APP_TRACE=1", "CONFIG_LOG=y\n").save(app);
        new BuildProfile("release-pre", "Release PRE", BuildConfiguration.RELEASE,
                "", "CONFIG_LOG=y\n").save(app);

        var all = BuildProfileChoices.available(app);
        check(all.size() == BuildConfiguration.values().length + 2,
                "three built-ins and two explicit named profiles");
        check(BuildProfileChoices.selected(all, null, null).identity().equals("default"),
                "legacy project without a property defaults to Application settings");
        var named = BuildProfileChoices.selected(all, "debug", "release-pre");
        check(named.named() && named.identity().equals("profile-release-pre")
                && named.builtInId().equals("release"), "named ID overrides optimisation mode");
        check(BuildProfileChoices.choose(all, "profile:debug-rtt").label().equals("Debug RTT"),
                "display label does not replace stable profile identifier");
        check(BuildProfileChoices.selected(all, "release", null).identity().equals("release"),
                "selecting a built-in clears named profile identity");
        try { fails(BuildProfileChoices.selected(all, "debug", "missing-profile")); }
        catch (IOException expected) { check(expected.getMessage().contains("missing"), "removed profile is fail-closed"); }
        try { fails(BuildProfileChoices.choose(all, "profile:../../escape")); }
        catch (IOException expected) { check(true, "profile traversal rejected"); }
        try { fails(BuildProfileChoices.selected(all, "invalid-builtin", null)); }
        catch (IOException expected) { check(true, "unknown built-in not silently substituted"); }
        BuildProfile.delete(app, "debug-rtt");
        try { fails(BuildProfileChoices.selected(BuildProfileChoices.available(app), "debug", "debug-rtt")); }
        catch (IOException expected) { check(true, "deleted profile not reused"); }
        System.out.println("PASS BuildProfileChoicesTests");
    }
}
