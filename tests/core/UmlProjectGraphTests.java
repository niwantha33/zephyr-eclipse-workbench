// SPDX-License-Identifier: Apache-2.0
import java.util.ArrayList;
import java.util.List;

import io.github.niwantha33.zew.ui.UmlProjectGraph;

public final class UmlProjectGraphTests {
    private static void check(boolean yes, String why) {
        if (!yes) throw new AssertionError("Project UML: " + why);
        System.out.println("PASS Project UML: " + why);
    }

    public static void main(String[] args) {
        var main = new UmlProjectGraph.Node("main.c:25", "main", "src/main.c", 25);
        var sensor = new UmlProjectGraph.Node("sensor.c:82", "sensor_read", "src/sensor.c", 82);
        var motor = new UmlProjectGraph.Node("motor.c:41", "motor_run", "src/motor.c", 41);
        var graph = UmlProjectGraph.of("app", List.of(main, sensor, motor),
                List.of(new UmlProjectGraph.Edge(main.id(), sensor.id()),
                        new UmlProjectGraph.Edge(main.id(), motor.id()),
                        new UmlProjectGraph.Edge(main.id(), sensor.id()),
                        new UmlProjectGraph.Edge(main.id(), "external:1"),
                        new UmlProjectGraph.Edge(main.id(), main.id())),
                3, false, "direct CDT calls");
        check(graph.available() && graph.nodes().size() == 3
                && graph.edges().size() == 2 && !graph.incomplete(),
                "cross-file resolved calls kept, duplicates/unknown/self links discarded");
        String uml = UmlProjectGraph.plantUml(graph);
        check(uml.equals(UmlProjectGraph.plantUml(graph))
                && uml.contains("ZEW_FN_0 --> ZEW_FN_1 : direct call")
                && uml.contains("ZEW_FN_0 --> ZEW_FN_2 : direct call")
                && uml.contains("src/sensor.c") && uml.endsWith("@enduml\n"),
                "deterministic PlantUML links and source labels");
        try {
            graph.edges().clear();
            throw new AssertionError("mutable graph");
        } catch (UnsupportedOperationException expected) {
            check(true, "project graph snapshots are immutable");
        }
        var malicious = new UmlProjectGraph.Node("x", "x\"\n@enduml !include /tmp/evil",
                "bad.c", 0);
        String safe = UmlProjectGraph.plantUml(UmlProjectGraph.of("app",
                List.of(malicious), List.of(), 1, false, "test"));
        check(!safe.contains("!include") && safe.split("@enduml", -1).length == 2,
                "source identifiers cannot inject PlantUML directives");
        var many = new ArrayList<UmlProjectGraph.Node>();
        for (int i = 0; i < 90; i++)
            many.add(new UmlProjectGraph.Node("s:" + i, "f" + i, "src/file.c", i));
        var limited = UmlProjectGraph.of("app", many, List.of(), 24, false, "");
        check(limited.nodes().size() == UmlProjectGraph.MAX_NODES && limited.incomplete(),
                "function-node limit reports incomplete evidence");
        var empty = UmlProjectGraph.unavailable("app", "CDT not ready");
        check(!empty.available() && empty.edges().isEmpty(),
                "missing index never invents a project graph");
    }
}
