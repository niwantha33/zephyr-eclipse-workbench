// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt;
import io.github.niwantha33.zew.core.ConfigurationEvidenceReceipt.State;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** CI integration probe over real Zephyr-generated files; no target device is used. */
public final class ConfigurationEvidenceRealBuildProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            throw new IllegalArgumentException("Expected base64 application/build paths, exact board and profile.");
        // The Windows Java native launcher can corrupt non-ACP Unicode argv
        // even though Java's Path API itself supports the original characters.
        // CI transports only UTF-8 bytes encoded as ASCII, then decodes here.
        Path application = Path.of(new String(Base64.getUrlDecoder().decode(args[0]),
                StandardCharsets.UTF_8));
        Path build = Path.of(new String(Base64.getUrlDecoder().decode(args[1]),
                StandardCharsets.UTF_8));
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
