// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path;
import io.github.niwantha33.zew.core.BuildArtifacts;
/** Integration-test entry point; uses the exact verifier called by Eclipse. */
public class FirmwareVerify {
    public static void main(String[] args) throws Exception {
        String value = args[0].equals("--base64") ? new String(java.util.Base64.getDecoder().decode(args[1]),
            java.nio.charset.StandardCharsets.UTF_8) : args[0];
        Path build = Path.of(value);
        BuildArtifacts.verifyArchitecture(build);
        BuildArtifacts.verify(build);
        System.out.println("Firmware artifacts verified: " + BuildArtifacts.required(build));
    }
}
