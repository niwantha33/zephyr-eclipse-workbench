// SPDX-License-Identifier: Apache-2.0
package zew.smoke;

import java.nio.file.Files;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;

/** Run on the workbench UI thread: real SWT view, parser and no-session guard. */
public final class SocRegistersSmoke {
    public static void run() throws Exception {
        var page = PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
        var view = page.showView("io.github.niwantha33.zew.ui.socRegisters");
        var type = view.getClass();
        var statusField = type.getDeclaredField("status"); statusField.setAccessible(true);
        var refresh = type.getDeclaredMethod("refresh"); refresh.setAccessible(true);
        refresh.invoke(view);
        if (!((Label) statusField.get(view)).getText().contains("SVD first"))
            throw new AssertionError("Missing peripheral model must explain how to populate the view");
        var load = type.getDeclaredMethod("load", String.class); load.setAccessible(true);
        var fixture = Files.createTempFile("zew-soc-registers-", ".svd");
        try {
            Files.writeString(fixture, "<device><name>SmokeChip</name><size>32</size><peripherals><peripheral>"
                    + "<name>GPIO</name><baseAddress>0x40000000</baseAddress><registers>"
                    + "<register><name>CTRL</name><addressOffset>4</addressOffset><fields>"
                    + "<field><name>EN</name><bitOffset>0</bitOffset><bitWidth>1</bitWidth></field>"
                    + "</fields></register></registers></peripheral></peripherals></device>");
            load.invoke(view, fixture.toString());
            var treeField = type.getDeclaredField("tree"); treeField.setAccessible(true);
            var tree = (Tree) treeField.get(view);
            if (tree.getItemCount() != 1 || tree.getItem(0).getItemCount() != 1)
                throw new AssertionError("SoC tree did not load");
            var register = tree.getItem(0).getItem(0);
            if (!register.getText(1).equals("0x40000004") || register.getItemCount() != 1
                    || !register.getItem(0).getText().equals("EN") || !register.getText(3).equals("—"))
                throw new AssertionError("Register address/field/undefined reset rendering");
            var boardField = type.getDeclaredField("selectedBoard");
            boardField.setAccessible(true);
            boardField.set(view, "lp_mspm0g3507/mspm0g3507");
            load.invoke(view, fixture.toString());
            if (!((Label) statusField.get(view)).getText().contains("Rejected SVD model"))
                throw new AssertionError("Wrong SoC SVD must be rejected for exact selected project");
            boardField.set(view, null);
            tree.setSelection(register);
            refresh.invoke(view);
            if (!((Label) statusField.get(view)).getText().contains("suspended"))
                throw new AssertionError("No-session read was not rejected");
            var searchField = type.getDeclaredField("search"); searchField.setAccessible(true);
            var search = (Text) searchField.get(view);
            search.setText("not-present");
            if (tree.getItemCount() != 0) throw new AssertionError("Search failed to filter");
            search.setText("EN");
            if (tree.getItemCount() != 1) throw new AssertionError("Field search did not restore register");
            var valuesField = type.getDeclaredField("values"); valuesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var values = (java.util.Map<String, java.math.BigInteger>) valuesField.get(view);
            values.put("stale", java.math.BigInteger.ONE);
            var contextChanged = type.getDeclaredMethod("contextChanged"); contextChanged.setAccessible(true);
            contextChanged.invoke(view);
            if (!values.isEmpty()) throw new AssertionError("Debug-context change must clear stale peripheral snapshots");
            checkResumeInvalidation(view, tree, (Label) statusField.get(view), values);
            org.eclipse.core.runtime.Platform.getBundle("io.github.niwantha33.zew.ui")
                    .loadClass("io.github.niwantha33.zew.ui.SocRegistersHandler")
                    .getMethod("showDebugData").invoke(null);
            var display = page.getWorkbenchWindow().getWorkbench().getDisplay();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (page.findView("org.eclipse.debug.ui.RegisterView") == null && System.nanoTime() < deadline) {
                if (!display.readAndDispatch()) Thread.sleep(10);
            }
            for (String id : new String[] {"org.eclipse.debug.ui.RegisterView", "org.eclipse.debug.ui.VariableView",
                    "org.eclipse.debug.ui.ExpressionView", "io.github.niwantha33.zew.ui.socRegisters"})
                if (page.findView(id) == null) throw new AssertionError("Native data action did not open " + id);
            System.out.println("SocRegistersSmoke PASS: actionable empty state, native data routing, SVD, safe reads, stale-context/resume invalidation");
        } finally {
            page.hideView(view);
            Files.deleteIfExists(fixture);
        }
    }

    /** Synthetic DSF event verifies UI lifecycle; this is not a physical register read. */
    private static void checkResumeInvalidation(Object view, Tree tree, Label status,
            java.util.Map<String, java.math.BigInteger> values) throws Exception {
        var executor = new org.eclipse.cdt.dsf.concurrent.DefaultDsfExecutor("peripheral-smoke");
        var session = org.eclipse.cdt.dsf.service.DsfSession.startSession(executor, "peripheral-smoke");
        try {
            var listen = view.getClass().getDeclaredMethod("listenTo", org.eclipse.cdt.dsf.service.DsfSession.class);
            listen.setAccessible(true); listen.invoke(view, session);
            // All listener registration submitted before this barrier has completed.
            executor.submit(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            Object context = java.lang.reflect.Proxy.newProxyInstance(
                    SocRegistersSmoke.class.getClassLoader(), new Class<?>[] {org.eclipse.cdt.dsf.debug.service.IRunControl.IExecutionDMContext.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getSessionId" -> session.getId();
                        case "getParents" -> new org.eclipse.cdt.dsf.datamodel.IDMContext[0];
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> null;
                    });
            var event = (org.eclipse.cdt.dsf.debug.service.IRunControl.IResumedDMEvent) java.lang.reflect.Proxy.newProxyInstance(
                    SocRegistersSmoke.class.getClassLoader(),
                    new Class<?>[] {org.eclipse.cdt.dsf.debug.service.IRunControl.IResumedDMEvent.class},
                    (proxy, method, args) -> method.getName().equals("getDMContext") ? context : null);
            values.put("stale", java.math.BigInteger.ONE);
            executor.submit(() -> session.dispatchEvent(event, null)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            var display = tree.getDisplay();
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
            while (!status.getText().startsWith("Target resumed") && System.nanoTime() < deadline) {
                if (!display.readAndDispatch()) Thread.sleep(10);
            }
            if (!values.isEmpty() || !status.getText().startsWith("Target resumed"))
                throw new AssertionError("Resuming must invalidate stale peripheral values without reading hardware");
            listen.invoke(view, new Object[] {null});
        } finally {
            executor.submit(() -> org.eclipse.cdt.dsf.service.DsfSession.endSession(session))
                    .get(5, java.util.concurrent.TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

}
