// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.SvdDownloadCatalog;
import io.github.niwantha33.zew.core.GitHubSvdProvisioner;
import io.github.niwantha33.zew.core.VendorSvdProvisioner;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SvdDownloadCatalogTests {
    private static void check(boolean yes, String msg) { if (!yes) throw new AssertionError(msg); }
    private static void reject(String json) throws Exception {
        try {
            SvdDownloadCatalog.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("Catalog accepted invalid input");
        } catch (IllegalArgumentException expected) { /* fail closed */ }
    }
    public static void main(String[] args) throws Exception {
        var entries = SvdDownloadCatalog.entries();
        check(entries.size() == 9, "original eight Espressif pins and disabled TI pack retained");
        var esp = SvdDownloadCatalog.forBoard("esp32s3_devkitc/esp32s3/appcpu");
        check(esp != null && esp.pinned() && "svd".equals(esp.type())
                && esp.sha256().equals("d7629249eba2ad660a960530d124966e9274c8228341380dd4102f251e879e17"),
                "Espressif reviewed digest unchanged");
        check(GitHubSvdProvisioner.supports("esp32s3_devkitc/esp32s3/appcpu"),
                "pinned Espressif download remains available");
        var ti = SvdDownloadCatalog.forBoard("lp_mspm0g3507/mspm0g3507");
        check(ti != null && !ti.pinned() && "pack".equals(ti.type())
                && !VendorSvdProvisioner.supports("lp_mspm0g3507/mspm0g3507"),
                "legacy TI URL recorded but cannot access network without trusted digest");
        check(!GitHubSvdProvisioner.supports("board/esp32c5"), "missing pins refuse network access");
        reject("[{\"part\":\"esp32\",\"url\":\"http://example.com/evil.svd\","
                + "\"sha256\":\"\",\"type\":\"svd\"}]");
        reject("[{\"part\":\"esp32\",\"url\":\"https://raw.githubusercontent.com/evil.svd\","
                + "\"sha256\":\"\",\"type\":\"svd\",\"extra\":\"unsafe\"}]");
        reject("[{\"part\":\"esp32\",\"url\":\"https://raw.githubusercontent.com/evil.svd\","
                + "\"sha256\":\"\",\"type\":\"svd\"},"
                + "{\"part\":\"esp32\",\"url\":\"https://raw.githubusercontent.com/evil.svd\","
                + "\"sha256\":\"\",\"type\":\"svd\"}]");
        System.out.println("SvdDownloadCatalogTests PASS");
    }
}
