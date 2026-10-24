// SPDX-License-Identifier: Apache-2.0
import io.github.niwantha33.zew.core.EditorSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class EditorTests {
    private static int count;
    static String field(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    static String row(String type, String file, String name, String value) { return type+"\t"+field(file)+"\t"+field(name)+"\t"+field(value)+"\n"; }
    static void check(boolean value) { if (!value) throw new AssertionError(); count++; }
    static void rejects(String value) throws Exception { try { EditorSettings.parse(value); } catch(IOException expected) { count++; return; } throw new AssertionError("Malformed settings accepted"); }
    public static void main(String[] args) throws Exception {
        String source = row("F", "src/main.c", "c", "");
        String windows = row("I", "src/main.c", "C:/SDK space Ω/include", "");
        String valid = "ZEW_EDITOR_1\n"+source+windows+row("D", "src/main.c", "CONFIG_TEST", "73")+"DONE\t1\t3\n";
        var settings = EditorSettings.parse(valid);
        check(settings.sources().get("src/main.c").entries().size()==2);
        check(settings.sources().get("src/main.c").entries().getFirst().name().contains("Ω"));
        rejects(valid.replace("DONE\t1\t3", "DONE\t2\t3"));
        rejects(valid.replace("DONE\t1\t3", "DONE\t1\t2"));
        rejects("ZEW_EDITOR_1\n"+source+row("I", "src/main.c", "relative/include", "")+"DONE\t1\t2\n");
        rejects("ZEW_EDITOR_1\n"+row("F", "../main.c", "c", "")+"DONE\t1\t1\n");
        rejects("ZEW_EDITOR_1\n"+source+source+"DONE\t2\t2\n");
        rejects("ZEW_EDITOR_1\n"+windows+source+"DONE\t1\t2\n");
        rejects(valid+"extra\n"); rejects(valid.replace("ZEW_EDITOR_1", "ZEW_EDITOR_2"));
        rejects("ZEW_EDITOR_1\n"+source+row("X", "src/main.c", "x", "")+"DONE\t1\t2\n");
        System.out.println("Passed " + count + " editor protocol checks.");
    }
}
