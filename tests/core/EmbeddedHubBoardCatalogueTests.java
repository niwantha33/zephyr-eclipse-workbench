// SPDX-License-Identifier: Apache-2.0
import java.io.IOException;
import java.util.List;
import io.github.niwantha33.zew.core.EmbeddedHubBoardCatalogue;

public final class EmbeddedHubBoardCatalogueTests {
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("Board catalogue check failed");
        checks++;
    }
    private static void rejects(String line) {
        try {
            EmbeddedHubBoardCatalogue.parseLine(line);
            throw new AssertionError("Catalogue must reject: " + line);
        } catch (IOException expected) {
            checks++;
        }
    }

    public static void main(String[] args) throws Exception {
        var rows = EmbeddedHubBoardCatalogue.parseLines(List.of(
                "ti\tti\tmspm0g3507\tlp_mspm0g3507\tdefault\tmspm0g3507\tlp_mspm0g3507/mspm0g3507",
                "st\tst\tstm32f446xx\tnucleo_f446re\tdefault\tstm32f446xx\tnucleo_f446re/stm32f446xx",
                "espressif\tespressif\tesp32s3\tesp32s3_devkitc\tdefault\tesp32s3/procpu\tesp32s3_devkitc/esp32s3/procpu",
                "nordic\tnordic\tnrf52840\tnrf52840dk\t1.0.0\tnrf52840\tnrf52840dk@1.0.0/nrf52840"));
        check(rows.size() == 4);
        check(EmbeddedHubBoardCatalogue.filter(rows, "mspm0").size() == 1);
        check(EmbeddedHubBoardCatalogue.filter(rows, "PROCPU").size() == 1);
        check(EmbeddedHubBoardCatalogue.filter(rows, "nordic").size() == 1);
        check(EmbeddedHubBoardCatalogue.filter(rows, "").size() == 4);
        check(EmbeddedHubBoardCatalogue.exact(rows, "nrf52840dk@1.0.0/nrf52840").isPresent());
        check(EmbeddedHubBoardCatalogue.exact(rows, "nrf52840dk/nrf52840").isEmpty());
        check(EmbeddedHubBoardCatalogue.statusFor(rows.get(0), rows.get(0).exactTarget())
                .contains("not verified"));
        check(EmbeddedHubBoardCatalogue.statusFor(rows.get(1), rows.get(0).exactTarget())
                .contains("not verified"));
        rejects("ti\tti\tmspm0g3507\tlp_mspm0g3507\tdefault\tmspm0g3507"); // 6 columns
        rejects("ti\tti\tmspm0g3507\tlp_mspm0g3507\tdefault\tmspm0g3507\tlp_other/mspm0g3507");
        rejects("ti\tti\tmspm0g3507\t../escape\tdefault\tmspm0g3507\t../escape/mspm0g3507");
        rejects("ti\tti\tmspm0g3507\tlp_mspm0g3507\tdefault\tmspm0g3507\tlp_mspm0g3507/mspm0g3507\nmore");
        rejects("ti\tti\tmspm0g3507\tlp_mspm0g3507\tdefault\tmspm0g3507\tlp_mspm0g3507/mspm0g3507\textra");
        check(EmbeddedHubBoardCatalogue.parseLines(List.of()).isEmpty());
        System.out.println("Passed " + checks + " embedded hardware discovery checks.");
    }
}
