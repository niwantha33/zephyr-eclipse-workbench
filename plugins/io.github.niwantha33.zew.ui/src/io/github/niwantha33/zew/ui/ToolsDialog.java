// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ProgressBar;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;

import io.github.niwantha33.zew.core.ProcessRunner;

/** Inventory/reuse slice. External package downloads are deliberately not enabled here. */
public final class ToolsDialog extends TitleAreaDialog {
    private static final AtomicReference<Job> CURRENT = new AtomicReference<>();
    private final Map<String, Text> fields = new LinkedHashMap<>();
    private final Map<String, String> bindings = new LinkedHashMap<>();
    private final List<Control> inputs = new ArrayList<>();
    private Table table;
    private Text log;
    private ProgressBar progress;
    private Label phase;
    private boolean scanned;
    public static boolean isOperationActive() { return CURRENT.get() != null; }

    public ToolsDialog(Shell shell) { super(shell); setShellStyle(getShellStyle() | SWT.RESIZE); }
    @Override protected Point getInitialSize() { return new Point(1080, 820); }
    @Override protected Control createDialogArea(Composite parent) {
        var area = (Composite) super.createDialogArea(parent);
        setTitle("Install / Manage Zephyr Tools");
        setMessage("Detect and reuse existing tools. Create an isolated Python environment if needed. Package downloads are coming in the next installer slice.");
        var scroll = new ScrolledComposite(area, SWT.V_SCROLL | SWT.H_SCROLL);
        scroll.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroll.setExpandHorizontal(true); scroll.setExpandVertical(true);
        var body = new Composite(scroll, SWT.NONE); body.setLayout(new GridLayout(3, false));
        addPath(body, "workspace", "West workspace", true, WorkbenchPreferences.WORKSPACE);
        addPath(body, "python", "Python executable", false, WorkbenchPreferences.PYTHON);
        addPath(body, "sdk", "Zephyr SDK (blank: discover)", true, "tools.sdk");
        for (String name : new String[] {"cmake", "ninja", "git", "dtc"})
            addPath(body, name, name + " executable (blank: discover)", false, "tools." + name);
        var actions = new Composite(body, SWT.NONE); actions.setLayout(new GridLayout(3, false));
        actions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        var scan = new Button(actions, SWT.PUSH); scan.setText("Detect / Refresh");
        scan.addListener(SWT.Selection, e -> scan()); inputs.add(scan);
        var environment = new Button(actions, SWT.PUSH); environment.setText("Create Isolated Python Environment...");
        environment.addListener(SWT.Selection, e -> createEnvironment()); inputs.add(environment);
        var cancel = new Button(actions, SWT.PUSH); cancel.setText("Cancel Operation");
        cancel.addListener(SWT.Selection, e -> { var job = CURRENT.get(); if (job != null) job.cancel(); });
        table = new Table(body, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true); table.setLinesVisible(true);
        var tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1); tableData.heightHint = 230; table.setLayoutData(tableData);
        String[] names = {"Component", "Requirement", "Installed version", "Status", "Location", "Action / details"};
        int[] widths = {155, 190, 210, 190, 260, 420};
        for (int i = 0; i < names.length; i++) { var column = new TableColumn(table, SWT.NONE); column.setText(names[i]); column.setWidth(widths[i]); }
        phase = new Label(body, SWT.WRAP); phase.setText("Select the existing workspace and its .venv Python, then click Detect / Refresh.");
        phase.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        progress = new ProgressBar(body, SWT.INDETERMINATE); progress.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)); progress.setVisible(false);
        log = new Text(body, SWT.MULTI | SWT.READ_ONLY | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        var logData = new GridData(SWT.FILL, SWT.FILL, true, true, 3, 1); logData.heightHint = 95; log.setLayoutData(logData);
        scroll.setContent(body); scroll.setMinSize(body.computeSize(SWT.DEFAULT, SWT.DEFAULT));
        return area;
    }
    private void addPath(Composite body, String name, String label, boolean directory, String preference) {
        new Label(body, SWT.NONE).setText(label + ":");
        var text = new Text(body, SWT.BORDER); text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setText(Activator.getDefault().getPreferenceStore().getString(preference));
        fields.put(name, text); inputs.add(text);
        text.addModifyListener(e -> { scanned = false; if (getButton(IDialogConstants.OK_ID) != null) getButton(IDialogConstants.OK_ID).setEnabled(false); });
        var browse = new Button(body, SWT.PUSH); browse.setText("Browse..."); inputs.add(browse);
        browse.addListener(SWT.Selection, e -> {
            String selected;
            if (directory) { var dialog = new DirectoryDialog(getShell()); dialog.setText(label); dialog.setFilterPath(text.getText()); selected = dialog.open(); }
            else { var dialog = new FileDialog(getShell(), SWT.OPEN); dialog.setText(label); selected = dialog.open(); }
            if (selected != null) {
                text.setText(selected);
                if (name.equals("workspace") && fields.containsKey("python")) {
                    Path root = Path.of(selected);
                    Path python = root.resolve(System.getProperty("os.name").startsWith("Windows") ? ".venv/Scripts/python.exe" : ".venv/bin/python");
                    if (Files.isRegularFile(python)) fields.get("python").setText(python.toString());
                }
            }
        });
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Use Detected Tools", true).setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Close", false);
    }
    private Map<String, String> snapshot() {
        var values = new LinkedHashMap<String, String>();
        fields.forEach((key, text) -> values.put(key, text.getText().trim()));
        return Map.copyOf(values);
    }
    private void scan() {
        var values = snapshot();
        if (!validPython(values)) return;
        try {
            if (values.get("workspace").isBlank() || !Files.isDirectory(Path.of(values.get("workspace")))) { setErrorMessage("Select an existing West workspace directory."); return; }
        } catch (RuntimeException error) { setErrorMessage("Select a valid workspace directory."); return; }
        table.removeAll(); bindings.clear(); scanned = false;
        start("Detect Zephyr development tools", monitor -> {
            Path script = Activator.getDefault().getStateLocation().toFile().toPath().resolve("tools/inventory.py");
            Files.createDirectories(script.getParent());
            try (var input = Activator.getDefault().getBundle().getEntry("tools/inventory.py").openStream()) {
                Files.copy(input, script, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            var command = new ArrayList<String>(List.of(values.get("python"), "-u", script.toString()));
            for (String key : new String[] {"workspace", "sdk", "cmake", "ninja", "git", "dtc"}) command.addAll(List.of("--" + key, values.get(key)));
            var found = new LinkedHashMap<String, String>();
            var rows = new ArrayList<String[]>();
            new ProcessRunner().run(command, script.getParent(), Duration.ofMinutes(10), monitor::isCanceled, line -> {
                var parts = line.split("\t", -1);
                if (parts.length < 2) { append(line); return; }
                String[] decoded = new String[parts.length - 1];
                try { for (int i = 1; i < parts.length; i++) decoded[i - 1] = new String(Base64.getDecoder().decode(parts[i]), StandardCharsets.UTF_8); }
                catch (IllegalArgumentException error) { append(line); return; }
                if (parts[0].equals("ROW") && decoded.length == 6) { rows.add(decoded); async(() -> new TableItem(table, SWT.NONE).setText(decoded)); }
                else if (parts[0].equals("BIND") && decoded.length == 2) found.put(decoded[0], decoded[1]);
                else if ((parts[0].equals("PHASE") || parts[0].equals("DONE")) && decoded.length == 1) {
                    monitor.subTask(decoded[0]); async(() -> phase.setText(decoded[0])); append(decoded[0]);
                } else if (parts[0].equals("ERROR")) append("FAILED: " + String.join(" ", decoded));
            }).requireSuccess();
            if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Cancelled");
            async(() -> {
                bindings.putAll(found);
                scanned = rows.stream().anyMatch(row -> row[0].equals("Python") && row[3].equals("Ready"))
                        && rows.stream().anyMatch(row -> row[0].equals("Python environment") && row[3].equals("Ready"))
                        && List.of("West", "cmake", "ninja", "git").stream().allMatch(name -> rows.stream().anyMatch(row -> row[0].equals(name) && row[3].equals("Ready")))
                        && rows.stream().anyMatch(row -> row[0].equals("West workspace") && row[3].equals("Ready"));
                getButton(IDialogConstants.OK_ID).setEnabled(scanned);
                if (!scanned) setErrorMessage("Python/venv, West, workspace, CMake, Ninja and Git must pass before binding tools. Review the table; installations are not changed by detection.");
            });
        });
    }
    private boolean validPython(Map<String, String> values) {
        try {
            if (!values.get("python").isBlank() && Files.isRegularFile(Path.of(values.get("python")))) return true;
        } catch (RuntimeException error) { /* Report as a field error. */ }
        setErrorMessage("Browse to an installed Python executable (.venv Python to reuse it, or base Python to create an environment)."); return false;
    }
    private void createEnvironment() {
        var values = snapshot(); if (!validPython(values)) return;
        var picker = new DirectoryDialog(getShell()); picker.setText("Select a parent folder for a NEW isolated Python environment");
        picker.setFilterPath(values.get("workspace")); String parent = picker.open(); if (parent == null) return;
        Path destination = Path.of(parent).resolve("zew-venv-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        if (!MessageDialog.openConfirm(getShell(), "Create isolated environment", "Create an isolated Python environment at:\n" + destination
                + "\n\nThis installs Python's bundled pip only. West and Zephyr packages will still need installing. Your existing environment will be preserved.")) return;
        start("Create isolated Zephyr Python environment", monitor -> {
            // Atomic directory reservation prevents an existing environment being overwritten.
            Files.createDirectory(destination);
            Files.writeString(destination.resolve(".zew-incomplete"), "Environment creation has not completed.\n");
            new ProcessRunner().run(List.of(values.get("python"), "-m", "venv", destination.toString()), Path.of(parent),
                    Duration.ofMinutes(5), monitor::isCanceled, this::append).requireSuccess();
            Path python = destination.resolve(System.getProperty("os.name").startsWith("Windows") ? "Scripts/python.exe" : "bin/python");
            new ProcessRunner().run(List.of(python.toString(), "-c", "import sys; assert sys.prefix != sys.base_prefix; print(sys.version)"), destination,
                    Duration.ofSeconds(30), monitor::isCanceled, this::append).requireSuccess();
            if (monitor.isCanceled()) throw new java.io.InterruptedIOException("Cancelled");
            Files.delete(destination.resolve(".zew-incomplete"));
            append("Environment created and verified: " + destination);
            async(() -> { fields.get("python").setText(python.toString()); phase.setText("Environment created. Click Detect / Refresh to see missing packages. Use Detected Tools saves it only after scanning."); });
        });
    }
    @FunctionalInterface private interface Operation { void run(IProgressMonitor monitor) throws Exception; }
    private void start(String name, Operation action) {
        if (WorkbenchHandler.isOperationActive() || CURRENT.get() != null) { setErrorMessage("Finish the current build/tools operation first."); return; }
        var job = new Job(name) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                monitor.beginTask(name, IProgressMonitor.UNKNOWN);
                try { action.run(monitor); return Status.OK_STATUS; }
                catch (java.io.InterruptedIOException error) { append("Cancelled. Any incomplete environment remains marked .zew-incomplete; existing tools were preserved."); async(() -> phase.setText("Cancelled; run Detect / Refresh before using these tools.")); return Status.CANCEL_STATUS; }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); return Status.CANCEL_STATUS; }
                catch (Exception error) { append("FAILED: " + error.getMessage()); async(() -> { phase.setText("Failed. See the log and retry."); setErrorMessage(error.getMessage()); }); return new Status(IStatus.ERROR, Activator.ID, error.getMessage()); }
                finally { monitor.done(); CURRENT.compareAndSet(this, null); async(() -> { if (CURRENT.get() == null) busy(false); }); }
            }
        };
        if (!CURRENT.compareAndSet(null, job)) return;
        job.addJobChangeListener(new org.eclipse.core.runtime.jobs.JobChangeAdapter() {
            @Override public void done(org.eclipse.core.runtime.jobs.IJobChangeEvent event) {
                CURRENT.compareAndSet(job, null); async(() -> { if (CURRENT.get() == null) busy(false); });
            }
        });
        setErrorMessage(null); scanned = false; busy(true); phase.setText(name); append(name); job.setUser(true); job.schedule();
    }
    private void busy(boolean value) {
        inputs.forEach(control -> control.setEnabled(!value)); progress.setVisible(value);
        getButton(IDialogConstants.OK_ID).setEnabled(!value && scanned);
    }
    private void append(String text) { async(() -> { if (log.getCharCount() > 150_000) log.setText(log.getText().substring(75_000)); log.append(text + "\n"); }); }
    private void async(Runnable action) {
        var shell = getShell(); if (shell == null || shell.isDisposed()) return;
        shell.getDisplay().asyncExec(() -> { if (!shell.isDisposed()) action.run(); });
    }
    @Override protected void okPressed() {
        if (!scanned || CURRENT.get() != null) return;
        var store = Activator.getDefault().getPreferenceStore();
        store.setValue(WorkbenchPreferences.WORKSPACE, fields.get("workspace").getText().trim());
        store.setValue(WorkbenchPreferences.PYTHON, fields.get("python").getText().trim());
        for (String name : new String[] {"sdk", "cmake", "ninja", "git", "dtc"}) store.setValue("tools." + name, bindings.getOrDefault(name, ""));
        super.okPressed();
    }
    @Override public boolean close() { var job = CURRENT.get(); if (job != null) job.cancel(); return super.close(); }
}
