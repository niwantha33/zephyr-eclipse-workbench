// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.util.List;

import org.eclipse.core.runtime.Platform;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.ui.PlatformUI;

/** Installed native SWT check for the new no-network automatic UML viewer. */
public final class UmlClassSmoke {
    private UmlClassSmoke() {}

    private static void check(boolean yes, String why) {
        if (!yes) throw new AssertionError("UML INSTALLED UI: " + why);
        System.out.println("PASS UML INSTALLED UI: " + why);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.umlClasses");
        check(view != null && view.getClass().getSimpleName().equals("UmlClassView"),
                "automatic UML view registered in installed Eclipse");
        var bundle = Platform.getBundle("io.github.niwantha33.zew.ui");
        var typeClass = bundle.loadClass("io.github.niwantha33.zew.ui.UmlClassModel$Type");
        var snapshotClass = bundle.loadClass("io.github.niwantha33.zew.ui.UmlClassModel$Snapshot");
        Object type = typeClass.getConstructor(String.class, String.class, int.class,
                List.class, List.class).newInstance("struct", "Sensor", 24,
                        List.of("value: int"), List.of("read(): int"));
        var modelClass = bundle.loadClass("io.github.niwantha33.zew.ui.UmlClassModel");
        Object model = modelClass.getMethod("of", String.class, List.class, String.class)
                .invoke(null, "include/sensor.h", List.of(type), "CDT source outline");
        view.getClass().getMethod("displayObservation", snapshotClass).invoke(view, model);
        Canvas canvas = (Canvas) field(view, "canvas");
        check(!canvas.isDisposed() && canvas.getClass().getMethod("cardCount").invoke(canvas)
                        .equals(Integer.valueOf(1)),
                "native SWT renders one observed class box");
        Button copy = (Button) field(view, "exportButton");
        check(copy.getEnabled(), "PlantUML export is enabled for a real projection");
        page.hideView(view);
        check(canvas.isDisposed(), "native UML controls dispose correctly");
    }
}
