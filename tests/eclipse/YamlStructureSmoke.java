// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;

/**
 * Installed, real SWT/JFace smoke. No fake replacement view or UI screenshot.
 * Exercises the native registered view and fail-closed imported-report state.
 */
public final class YamlStructureSmoke {
    private YamlStructureSmoke() {}

    private static Object field(Object owner, String name) throws Exception {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new IllegalStateException("YAML installed UI: " + description);
        System.out.println("PASS YAML INSTALLED UI: " + description);
    }

    public static void run() throws Exception {
        var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        check(window != null && window.getActivePage() != null,
                "full native Eclipse workbench page is available");
        var view = window.getActivePage().showView(
                "io.github.niwantha33.zew.ui.yamlStructure");
        check(view != null && view.getClass().getName().endsWith(".YamlStructureView"),
                "real registered native YAML view opens");

        TreeViewer outline = (TreeViewer) field(view, "tree");
        TreeViewer hardware = (TreeViewer) field(view, "hardwareTree");
        TreeViewer twister = (TreeViewer) field(view, "twisterTree");
        TreeViewer diagnostics = (TreeViewer) field(view, "diagnosticTree");
        Text binding = (Text) field(view, "bindingDetails");
        TabFolder tabs = (TabFolder) binding.getParent();
        check(!outline.getTree().isDisposed() && outline.getTree().getColumnCount() == 4,
                "four-column source YAML syntax viewer exists");
        check(tabs.getItemCount() == 4
                && List.of("Devicetree binding", "Hardware Model v2",
                        "Twister tests", "Diagnostics").equals(
                                java.util.stream.IntStream.range(0, tabs.getItemCount())
                                        .mapToObj(i -> tabs.getItem(i).getText()).toList()),
                "all four evidence-aware YAML tabs install in correct order");
        check(!hardware.getTree().isDisposed() && !twister.getTree().isDisposed()
                && !diagnostics.getTree().isDisposed()
                && diagnostics.getTree().getColumnCount() == 4,
                "HWMv2, Twister and source-diagnostic JFace trees are real");

        var bundle = org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui");
        Class<?> model = bundle.loadClass("io.github.niwantha33.zew.ui.YamlDocumentModel");
        String position = "{\"line\":1,\"column\":1}";
        String range = "{\"start\":" + position + ",\"end\":{\"line\":1,\"column\":7}}";
        String valueRange = "{\"start\":{\"line\":1,\"column\":9},"
                + "\"end\":{\"line\":1,\"column\":16}}";
        String json = "{\"schema\":\"zew.yaml-observation/1\","
                + "\"status\":\"parsed\",\"reason\":\"syntax-observed\","
                + "\"classification\":{\"kind\":\"generic-yaml\","
                + "\"reason\":\"no-specific-role-established\"},"
                + "\"provenance\":{\"source_root\":\"application\","
                + "\"relative_path\":\"sample.yaml\",\"sha256\":\"" + "a".repeat(64)
                + "\",\"size_bytes\":16},"
                + "\"documents\":[{\"root\":{\"type\":\"mapping\","
                + "\"range\":" + range + ",\"entries\":[{"
                + "\"key\":\"sample\",\"key_range\":" + range + ","
                + "\"value\":{\"type\":\"scalar\",\"scalar_type\":\"str\","
                + "\"value\":\"example\",\"range\":" + valueRange + "}}]}}]}";
        Object snapshot = model.getMethod("parse", String.class).invoke(null, json);
        view.getClass().getMethod("displayObservation",
                bundle.loadClass("io.github.niwantha33.zew.ui.YamlDocumentModel$Snapshot"))
                .invoke(view, snapshot);
        check(outline.getTree().getItemCount() == 1
                && outline.getTree().getItem(0).getText(0).equals("(document)"),
                "real model is projected into the installed SWT outline");
        check(field(view, "observedFile") == null && field(view, "observedSnapshot") == null,
                "imported report cannot authenticate a writable source");

        Method edit = view.getClass().getDeclaredMethod("editSelectedScalar");
        edit.setAccessible(true);
        edit.invoke(view);
        var status = (org.eclipse.swt.widgets.Label) field(view, "status");
        check(status.getText().contains("imported JSON"),
                "attempted edit of imported snapshot fails closed without a dialog");
        check(diagnostics.getTree().getItemCount() == 0,
                "unverified imported data does not publish diagnostics");

        window.getActivePage().hideView(view);
        System.out.println("PASS YAML INSTALLED UI: native view disposed cleanly");
    }
}
