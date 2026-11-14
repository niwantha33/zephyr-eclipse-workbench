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
        if (!((Label) statusField.get(view)).getText().contains("verified chip SVD"))
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
            var detailsField = type.getDeclaredField("registerDetails");
            detailsField.setAccessible(true);
            var details = (Text) detailsField.get(view);
            if (details == null || !details.getText().contains("Select a register"))
                throw new AssertionError("Simplified register detail inspector is missing");
            // SVD parsing runs in a background Job. Process SWT events
            // until its UI hand-off completes, then expand the lazy tree.
            long loadDeadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
            while (tree.getItemCount() == 0 && System.nanoTime() < loadDeadline) {
                if (!tree.getDisplay().readAndDispatch()) Thread.sleep(10);
            }
            if (tree.getItemCount() != 1)
                throw new AssertionError("SoC peripheral tree did not load");
            var peripheral = tree.getItem(0);
            peripheral.setExpanded(true);
            var peripheralEvent = new org.eclipse.swt.widgets.Event();
            peripheralEvent.item = peripheral;
            tree.notifyListeners(org.eclipse.swt.SWT.Expand, peripheralEvent);
            if (peripheral.getItemCount() != 1)
                throw new AssertionError("Lazy peripheral registers did not load");
            var register = peripheral.getItem(0);
            register.setExpanded(true);
            var registerEvent = new org.eclipse.swt.widgets.Event();
            registerEvent.item = register;
            tree.notifyListeners(org.eclipse.swt.SWT.Expand, registerEvent);
            if (!register.getText(0).equals("CTRL") || !register.getText(1).equals("—")
                    || !register.getText(2).equals("Not sampled")
                    || register.getItemCount() != 1
                    || !register.getItem(0).getText().startsWith("EN [0:0]"))
                throw new AssertionError("Compact register/value/status and lazy field rendering");
            tree.setSelection(register);
            var selectionEvent = new org.eclipse.swt.widgets.Event();
            selectionEvent.item = register;
            tree.notifyListeners(org.eclipse.swt.SWT.Selection, selectionEvent);
            var fieldTableRef = type.getDeclaredField("fieldTable");
            fieldTableRef.setAccessible(true);
            var fieldTable = (org.eclipse.swt.widgets.Table) fieldTableRef.get(view);
            if (!details.getText().contains("GPIO  /  CTRL")
                    || !details.getText().contains("0x40000004")
                    || !details.getText().contains("Reset value")
                    || fieldTable.getItemCount() != 1
                    || !fieldTable.getItem(0).getText(0).equals("EN [0:0]"))
                throw new AssertionError("Plain-language register inspector and structured fields");
            if (tree.getColumnCount() != 3 || fieldTable.getColumnCount() != 3)
                throw new AssertionError("Compact register and field tables must use three columns");
            var contextInfoField = type.getDeclaredField("contextInfo");
            contextInfoField.setAccessible(true);
            var pathStatus = (Label) contextInfoField.get(view);
            if (!pathStatus.getText().contains("Project")
                    || !pathStatus.getText().contains("GDB"))
                throw new AssertionError("Register read evidence chain is missing");
            var location = type.getDeclaredMethod(
                    "vendorPackLocation", String.class, java.nio.file.Path.class);
            location.setAccessible(true);
            var projectRoot = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "zew-svd-smoke");
            var fallback = (java.nio.file.Path) location.invoke(null, null, projectRoot);
            if (!fallback.equals(projectRoot.resolve(".zew/vendor-svd")))
                throw new AssertionError("Missing setupRoot must use local project vendor pack directory");
            var managed = (java.nio.file.Path) location.invoke(null, projectRoot.toString(), projectRoot);
            if (!managed.equals(projectRoot.resolve("vendor-svd")))
                throw new AssertionError("Configured managed setupRoot must remain supported");
            // Board-only Zephyr projects must use CONFIG_SOC from the
            // generated build, not reject the correct vendor SVD as unknown.
            var evidencePart = type.getDeclaredMethod("verifiedSvdPart",
                    String.class, String.class);
            evidencePart.setAccessible(true);
            if (!"nrf52832".equals(evidencePart.invoke(null,
                    "thingy52", "thingy52/nrf52832"))
                    || !"mspm0g3507".equals(evidencePart.invoke(null,
                            "lp_mspm0g3507/mspm0g3507", null))
                    || !"".equals(evidencePart.invoke(null, "thingy52", null)))
                throw new AssertionError("SVD model identity must use exact build evidence");
            // Absent or malformed optional SDK roots cannot break discovery.
            var optionalRoot = type.getDeclaredMethod("optionalRoot", String.class);
            optionalRoot.setAccessible(true);
            if (optionalRoot.invoke(null, new Object[] {null}) != null
                    || optionalRoot.invoke(null, "") != null
                    || optionalRoot.invoke(null, "\0") != null)
                throw new AssertionError("Invalid optional SVD search root must be ignored");
            var boardField = type.getDeclaredField("selectedBoard");
            boardField.setAccessible(true);
            boardField.set(view, "lp_mspm0g3507/mspm0g3507");
            load.invoke(view, fixture.toString());
            // SVD parsing now runs asynchronously. Wait for the UI hand-off,
            // not the immediate "Loading…" state, and verify fail-closed
            // behaviour without discarding the previously inspected model.
            long rejectionDeadline = System.nanoTime()
                    + java.time.Duration.ofSeconds(15).toNanos();
            var status = (Label) statusField.get(view);
            while (status.getText().startsWith("Loading register definitions")
                    && System.nanoTime() < rejectionDeadline) {
                if (!tree.getDisplay().readAndDispatch()) Thread.sleep(10);
            }
            if (!status.getText().contains("Rejected SVD model")
                    || !status.getText().contains("Previous definitions were kept")
                    || tree.getItemCount() != 1
                    || tree.getItem(0) != peripheral)
                throw new AssertionError("Wrong-SoC SVD rejection must retain the previous model: "
                        + status.getText());
            boardField.set(view, null);
            tree.setSelection(register);
            refresh.invoke(view);
            if (!((Label) statusField.get(view)).getText().contains("Halt"))
                throw new AssertionError("No-session read was not rejected");
            var watchesField = type.getDeclaredField("watches"); watchesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var watches = (java.util.Set<String>) watchesField.get(view);
            var toggleWatch = type.getDeclaredMethod("toggleWatch",
                    io.github.niwantha33.zew.core.SvdDevice.Register.class);
            toggleWatch.setAccessible(true);
            var reg = register.getData();
            toggleWatch.invoke(view, reg);
            var watchTableField = type.getDeclaredField("watchTable");
            watchTableField.setAccessible(true);
            var watchTable = (org.eclipse.swt.widgets.Table) watchTableField.get(view);
            if (!watches.contains("GPIO.CTRL") || watchTable.getItemCount() != 1
                    || !watchTable.getVisible()
                    || !watchTable.getItem(0).getText(0).equals("GPIO.CTRL"))
                throw new AssertionError("Context-menu watch must open independent Watch table");
            var periodField = type.getDeclaredField("watchInterval"); periodField.setAccessible(true);
            var period = (org.eclipse.swt.widgets.Spinner) periodField.get(view);
            if (period.getMinimum() > 250 || period.getMaximum() < 60000
                    || period.getSelection() != 1500)
                throw new AssertionError("Watch polling interval must be adjustable");
            var autoField = type.getDeclaredField("autoReadOnHalt"); autoField.setAccessible(true);
            var onHalt = (org.eclipse.swt.widgets.Button) autoField.get(view);
            if (!onHalt.getSelection())
                throw new AssertionError("Safe snapshot on GDB halt must be enabled by default");
            if (tree.getMenu() == null || watchTable.getMenu() == null)
                throw new AssertionError("Register and Watch right-click actions are missing");
            var write = type.getDeclaredMethod("submitRegisterWrite",
                    io.github.niwantha33.zew.core.SvdDevice.Register.class,
                    java.math.BigInteger.class);
            write.setAccessible(true);
            write.invoke(view, reg, java.math.BigInteger.ONE);
            if (!((Label) statusField.get(view)).getText().contains("no unique matching native GDB"))
                throw new AssertionError("No-target register write must fail before any command");
            toggleWatch.invoke(view, reg);
            if (!watches.isEmpty() || watchTable.getItemCount() != 0
                    || watchTable.getVisible())
                throw new AssertionError("Unwatch must collapse empty Watch section");
            var searchField = type.getDeclaredField("search"); searchField.setAccessible(true);
            var search = (Text) searchField.get(view);
            search.setText("not-present");
            if (tree.getItemCount() != 0) throw new AssertionError("Search failed to filter");
            search.setText("EN");
            if (tree.getItemCount() != 1 || !tree.getItem(0).getExpanded()
                    || tree.getItem(0).getItemCount() != 1)
                throw new AssertionError("Field search must automatically expand matched register");
            var valuesField = type.getDeclaredField("values"); valuesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            var values = (java.util.Map<String, java.math.BigInteger>) valuesField.get(view);
            values.put("stale", java.math.BigInteger.ONE);
            var contextChanged = type.getDeclaredMethod("contextChanged"); contextChanged.setAccessible(true);
            contextChanged.invoke(view);
            if (!values.containsKey("stale"))
                throw new AssertionError("Unchanged debug selection must not discard valid snapshots");
            values.clear();
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
