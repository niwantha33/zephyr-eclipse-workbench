// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationStudioFiles;
import io.github.niwantha33.zew.core.FocusedConfigurationModel;
import io.github.niwantha33.zew.ui.ConfigurationStudioVisualRequest;
import java.nio.file.Path;
import java.util.List;

/** Pure-Java security gate for browser-driven configuration changes. */
public final class ConfigurationStudioVisualRequestTests {
    private static final FocusedConfigurationModel.Freshness CURRENT =
            FocusedConfigurationModel.Freshness.CURRENT_SNAPSHOT;
    private static void check(boolean good, String reason) {
        if (!good) throw new AssertionError(reason);
    }
    private static void rejects(String text, ConfigurationStudioFiles.Snapshot source,
                                FocusedConfigurationModel.Freshness state, boolean dirty) {
        try {
            ConfigurationStudioVisualRequest.parse(text, source, state, dirty);
            throw new AssertionError("Unexpectedly accepted visual request: " + text);
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
    public static void main(String[] args) {
        var source = new ConfigurationStudioFiles.Snapshot(List.of(
                new ConfigurationStudioFiles.Option("GPIO","y",null,0),
                new ConfigurationStudioFiles.Option("LOG","n","y",5),
                new ConfigurationStudioFiles.Option("BROKEN",null,"y",7),
                new ConfigurationStudioFiles.Option("NAME","\"hello\"","\"world\"",8),
                new ConfigurationStudioFiles.Option("TRACING","n","n",9)),
                "", "selected", Path.of("/safe/application/prj.conf"));
        var on = ConfigurationStudioVisualRequest.parse("CONFIG_GPIO=n",source,CURRENT,false);
        check(on.symbol().equals("GPIO") && on.value().equals("n")
                && on.expected().requested()==null, "resolved on/off option accepted");
        var remove = ConfigurationStudioVisualRequest.parse("CONFIG_BROKEN=remove",source,
                FocusedConfigurationModel.Freshness.NO_BUILD,false);
        check(remove.value()==null && remove.expected().requested().equals("y"),
                "existing unresolved source override can be removed for repair");
        rejects("CONFIG_GPIO=y; rm -rf",source,CURRENT,false);
        rejects("CONFIG_GPIO=remove",source,CURRENT,false);
        rejects("CONFIG_NAME=y",source,CURRENT,false);
        rejects("CONFIG_BROKEN=y",source,CURRENT,false);
        rejects("CONFIG_NONEXISTENT=y",source,CURRENT,false);
        rejects("CONFIG_LOG=y",source,CURRENT,false);
        rejects("CONFIG_GPIO=n",source,FocusedConfigurationModel.Freshness.STALE_SNAPSHOT,false);
        rejects("CONFIG_GPIO=n",source,FocusedConfigurationModel.Freshness.UNVERIFIED_SNAPSHOT,false);
        rejects("CONFIG_GPIO=n",source,FocusedConfigurationModel.Freshness.NO_BUILD,false);
        rejects("CONFIG_GPIO=n",source,CURRENT,true);
        rejects("CONFIG_LOG=remove",source,CURRENT,true);
        rejects("CONFIG_GPIO=n",null,CURRENT,false);
        rejects("CONFIG_gpio=y",source,CURRENT,false);
        check("n".equals(ConfigurationStudioVisualRequest.parse("CONFIG_LOG=n",source,CURRENT,false).value()),
                "new value different from requested is permitted subject to native review");
        System.out.println("ConfigurationStudioVisualRequestTests PASS");
    }
}
