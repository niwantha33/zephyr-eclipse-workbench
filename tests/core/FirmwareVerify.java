// SPDX-License-Identifier: Apache-2.0
import java.nio.file.Path;
import io.github.niwantha33.zew.core.BuildArtifacts;
/** Integration-test entry point; uses the exact verifier called by Eclipse. */
public class FirmwareVerify {
    public static void main(String[] args) throws Exception {
        Path build = Path.of(args[0]);
        BuildArtifacts.verifyArchitecture(build);
        BuildArtifacts.verify(build);
        System.out.println("Firmware artifacts verified: " + BuildArtifacts.required(build));
    }
}
