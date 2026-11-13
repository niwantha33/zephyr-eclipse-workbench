// SPDX-License-Identifier: Apache-2.0
import java.util.ArrayList;
import java.util.List;

import io.github.niwantha33.zew.ui.UmlClassModel;

public final class UmlClassModelTests {
    private static void check(boolean okay, String why) {
        if (!okay) throw new AssertionError("UML: " + why);
        System.out.println("PASS UML: " + why);
    }
    public static void main(String[] args) {
        UmlClassModel.Type motor = new UmlClassModel.Type("struct", "Motor", 71,
                List.of("speed: int", "status: uint8_t"),
                List.of("start(): void", "stop(): void"));
        UmlClassModel.Snapshot snap = UmlClassModel.of("src/motor.h", List.of(motor), "CDT outline");
        check(snap.available() && snap.types().size() == 1
                && snap.types().get(0).offset() == 71, "immutable source identity and offset");
        String uml = UmlClassModel.plantUml(snap);
        check(uml.equals(UmlClassModel.plantUml(snap))
                && uml.contains("class \"Motor\" as ZEW_0 <<struct>>")
                && uml.contains("speed: int") && uml.endsWith("@enduml\n"),
                "stable, exportable PlantUML source from actual projection");
        try {
            snap.types().clear();
            throw new AssertionError("mutable UML snapshot");
        } catch (UnsupportedOperationException expected) {
            check(true, "snapshot collections cannot be mutated");
        }
        var many = new ArrayList<UmlClassModel.Type>();
        for (int i = 0; i < 40; i++)
            many.add(new UmlClassModel.Type("class", "Type" + i, i, List.of(), List.of()));
        UmlClassModel.Snapshot limited = UmlClassModel.of("test.hpp", many, "bounded");
        check(limited.types().size() == UmlClassModel.MAX_TYPES && limited.truncated(),
                "node bounds explicitly report omitted types");
        var malicious = new UmlClassModel.Type("class", "X\"\n@enduml", 0,
                List.of("a; } !include /tmp/bad"), List.of());
        String safe = UmlClassModel.plantUml(UmlClassModel.of("bad.h", List.of(malicious), ""));
        check(safe.split("@enduml", -1).length == 2
                && !safe.contains("!include") && !safe.contains("X\"\n@enduml"),
                "source identifiers cannot inject PlantUML directives");
        check(!UmlClassModel.unavailable("bad.c", "not indexed").available(),
                "unavailable source is never presented as an observed diagram");
        check(new UmlClassModel.Type("union", "U", 0,
                java.util.Collections.nCopies(30, "v"), List.of()).attributes().size()
                == UmlClassModel.MAX_MEMBERS, "members bounded");
    }
}
