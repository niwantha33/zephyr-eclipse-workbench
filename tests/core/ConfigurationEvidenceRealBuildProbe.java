// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt.State;
import java.nio.file.Path;

/** CI integration probe over real Zephyr-generated files; no target device is used. */
public final class ConfigurationEvidenceRealBuildProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            throw new IllegalArgumentException("Expected application, build, exact board and profile.");
        Path application = Path.of(args[0]);
        Path build = Path.of(args[1]);
        String board = args[2], profile = args[3];

        var captured = ConfigurationEvidenceReceipt.capture(application, build, board, profile);
        if (captured.state() != State.CURRENT)
            throw new AssertionError("Real firmware provenance capture: "
                    + captured.state() + " — " + captured.detail());
        if (captured.sourceCount() < 2)
            throw new AssertionError("Real firmware provenance did not capture source dependencies.");
        var inspected = ConfigurationEvidenceReceipt.inspect(application, build, board, profile);
        if (inspected.state() != State.CURRENT
                || !inspected.configSha256().equals(captured.configSha256())
                || !inspected.devicetreeSha256().equals(captured.devicetreeSha256()))
            throw new AssertionError("Real firmware provenance verification: "
                    + inspected.state() + " — " + inspected.detail());
        System.out.println("PASS real Zephyr firmware provenance: " + board
                + ", recorded " + captured.sourceCount() + " source inputs");
    }
}
