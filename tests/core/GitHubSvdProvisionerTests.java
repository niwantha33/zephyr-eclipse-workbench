// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.GitHubSvdProvisioner;
import java.nio.charset.StandardCharsets;

/** SVD identity safety checks without internet or hardware. */
public final class GitHubSvdProvisionerTests {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    private static byte[] svd(String chip) {
        return ("<device><name>" + chip + "</name><size>32</size>"
                + "<peripherals><peripheral><name>GPIO</name><baseAddress>0x60004000</baseAddress>"
                + "<registers><register><name>OUT</name><addressOffset>0x4</addressOffset>"
                + "</register></registers></peripheral></peripherals></device>")
                .getBytes(StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        check(GitHubSvdProvisioner.supports("esp32s3_devkitc/esp32s3/appcpu"),
                "ESP32-S3 board selected exactly");
        check(GitHubSvdProvisioner.supports("esp32c3_devkitm/esp32c3"),
                "ESP32-C3 supported");
        check(!GitHubSvdProvisioner.supports("qemu_x86/atom"),
                "virtual CPU cannot be mapped to hardware");
        check(!GitHubSvdProvisioner.supports("esp32c5_devkitc/esp32c5"),
                "missing vendor description must remain unsupported");
        check(GitHubSvdProvisioner.exact(svd("ESP32-S3"), "esp32s3"),
                "exact vendor device loaded");
        // Part-name validation alone is not proof of trusted vendor bytes.
        check(!GitHubSvdProvisioner.trusted(svd("ESP32-S3"), "esp32s3"),
                "forged SVD with exact chip identity must fail vendor checksum");
        check(!GitHubSvdProvisioner.trusted(svd("ESP32-C3"), "esp32s3"),
                "wrong SVD bytes fail checksum before XML semantics");
        check(!GitHubSvdProvisioner.trusted(svd("ESP32-S3"), "not-supported"),
                "no unpinned part is allowed");
        check(GitHubSvdProvisioner.pinnedSha256("esp32s3").equals(
                "d7629249eba2ad660a960530d124966e9274c8228341380dd4102f251e879e17"),
                "ESP32-S3 vendor-reviewed blob checksum pinned");
        for (String part : java.util.List.of("esp32", "esp32s2", "esp32s3", "esp32c2",
                "esp32c3", "esp32c6", "esp32h2", "esp32p4")) {
            String pin = GitHubSvdProvisioner.pinnedSha256(part);
            check(pin != null && pin.matches("[a-f0-9]{64}"), "every supported part has a SHA-256: " + part);
        }

        check(!GitHubSvdProvisioner.exact(svd("ESP32-C3"), "esp32s3"),
                "wrong chip must be rejected");
        check(!GitHubSvdProvisioner.exact("<html>not an svd</html>".getBytes(StandardCharsets.UTF_8),
                "esp32s3"), "HTML/download errors rejected");
        System.out.println("GitHubSvdProvisionerTests PASS");
    }
}
