// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.dnd.DND;
import org.eclipse.swt.dnd.DragSource;
import org.eclipse.swt.dnd.DragSourceAdapter;
import org.eclipse.swt.dnd.DragSourceEvent;
import org.eclipse.swt.dnd.DropTarget;
import org.eclipse.swt.dnd.DropTargetAdapter;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.console.ConsolePlugin;
import org.eclipse.ui.console.IConsole;
import org.eclipse.ui.console.MessageConsole;
import org.eclipse.ui.part.ViewPart;

/**
 * Small native Eclipse workflow editor. The execution path deliberately invokes
 * the existing Zephyr builder and native flash launch delegate, not a new runner.
 */
public final class WorkflowStudioView extends ViewPart {
    public static final String ID = Activator.ID + ".workflowStudio";
    private static final String PREFIX = "zew.workflow:";
    private final java.util.List<WorkflowDefinition.Step> steps = new ArrayList<>();
    private final java.util.List<String> states = new ArrayList<>();
    private IProject project;
    private IProject[] choices = new IProject[0];
    private Job running;
    private boolean dirty, changingProject;

    private Combo projects;
    private Text name, details;
    private org.eclipse.swt.widgets.List palette;
    private Table canvas;
    private Label information, summary;
    private Button add, remove, up, down, save, run, cancel;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(layout(1, 10));
        Label heading = new Label(parent, SWT.NONE);
        heading.setText("ZEPHYR  /  WORKFLOW STUDIO");
        heading.setLayoutData(fillHorizontal());

        Composite header = new Composite(parent, SWT.NONE);
        header.setLayoutData(fillHorizontal());
        header.setLayout(layout(5, 8));
        new Label(header, SWT.NONE).setText("Project");
        projects = new Combo(header, SWT.READ_ONLY);
        projects.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(header, SWT.NONE).setText("Workflow");
        name = new Text(header, SWT.BORDER);
        name.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button refresh = button(header, "Refresh projects", this::refreshProjects);
        refresh.setToolTipText("Update available managed Zephyr projects.");
        name.addModifyListener(event -> {
            if (!changingProject && running == null) dirty = true;
        });
        projects.addListener(SWT.Selection, event -> selectProject(projects.getSelectionIndex() - 1));

        information = new Label(parent, SWT.WRAP);
        information.setLayoutData(fillHorizontal());
        information.setText("Choose a managed Zephyr project. Nothing is flashed automatically.");

        SashForm main = new SashForm(parent, SWT.HORIZONTAL | SWT.SMOOTH);
        main.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Group left = new Group(main, SWT.NONE);
        left.setText("BLOCK LIBRARY");
        left.setLayout(layout(1, 7));
        new Label(left, SWT.WRAP).setText("Drag a block into the workflow, or double-click to add it.");
        palette = new org.eclipse.swt.widgets.List(left, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        palette.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        palette.setItems(Arrays.stream(WorkflowDefinition.Step.values())
                .map(WorkflowDefinition.Step::label).toArray(String[]::new));
        palette.select(0);
        add = button(left, "Add selected block", this::addSelected);
        add.setLayoutData(fillHorizontal());
        palette.addListener(SWT.DefaultSelection, event -> addSelected());

        Group centre = new Group(main, SWT.NONE);
        centre.setText("WORKFLOW  /  EXECUTION ORDER");
        centre.setLayout(layout(1, 7));
        canvas = new Table(centre, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        canvas.setHeaderVisible(true);
        canvas.setLinesVisible(true);
        canvas.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        column(canvas, "Step", 65);
        column(canvas, "Operation", 160);
        column(canvas, "Status", 205);
        canvas.addListener(SWT.Selection, event -> showDetails());
        Composite actions = new Composite(centre, SWT.NONE);
        actions.setLayoutData(fillHorizontal());
        actions.setLayout(layout(3, 5));
        up = button(actions, "Move up", () -> shift(-1));
        down = button(actions, "Move down", () -> shift(1));
        remove = button(actions, "Remove", this::removeSelected);

        Group right = new Group(main, SWT.NONE);
        right.setText("BLOCK DETAILS");
        right.setLayout(layout(1, 8));
        details = new Text(right, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP | SWT.MULTI | SWT.V_SCROLL);
        details.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        main.setWeights(new int[] {24, 49, 27});

        Composite footer = new Composite(parent, SWT.NONE);
        footer.setLayoutData(fillHorizontal());
        footer.setLayout(layout(4, 8));
        save = button(footer, "Save workflow", this::save);
        run = button(footer, "Run workflow", this::runWorkflow);
        cancel = button(footer, "Cancel", () -> {
            if (running != null) running.cancel();
        });
        summary = new Label(footer, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("Ready to edit.");
        installDnD();
        refreshProjects();
        render();
    }

    private static GridLayout layout(int columns, int gap) {
        var result = new GridLayout(columns, false);
        result.marginWidth = 8;
        result.marginHeight = 8;
        result.horizontalSpacing = gap;
        result.verticalSpacing = gap;
        return result;
    }
    private static GridData fillHorizontal() {
        return new GridData(SWT.FILL, SWT.CENTER, true, false);
    }
    private static Button button(Composite parent, String label, Runnable action) {
        var item = new Button(parent, SWT.PUSH);
        item.setText(label);
        item.addListener(SWT.Selection, event -> action.run());
        return item;
    }
    private static void column(Table table, String title, int width) {
        var column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
        column.setResizable(true);
    }

    private void refreshProjects() {
        if (running != null) return;
        IProject previous = project;
        choices = Arrays.stream(ResourcesPlugin.getWorkspace().getRoot().getProjects())
                .filter(item -> SelectedProject.importedProject(item) != null)
                .toArray(IProject[]::new);
        projects.removeAll();
        projects.add("Select a Zephyr project...");
        for (IProject choice : choices) projects.add(choice.getName());
        IProject selected = SelectedProject.forLaunch();
        if (selected == null || Arrays.stream(choices).noneMatch(selected::equals))
            selected = previous;
        int selectedIndex = -1;
        for (int i = 0; i < choices.length; i++)
            if (choices[i].equals(selected)) selectedIndex = i;
        projects.select(selectedIndex + 1);
        selectProject(selectedIndex);
    }

    private void selectProject(int index) {
        if (running != null) return;
        IProject next = index >= 0 && index < choices.length ? choices[index] : null;
        if (Objects.equals(next, project)) { controls(); return; }
        if (dirty && project != null && !MessageDialog.openQuestion(getSite().getShell(),
                "Unsaved workflow", "Discard unsaved changes for " + project.getName() + "?")) {
            for (int i = 0; i < choices.length; i++)
                if (choices[i].equals(project)) projects.select(i + 1);
            return;
        }
        changingProject = true;
        try {
            WorkflowDefinition definition = next == null ? WorkflowDefinition.starter()
                    : WorkflowDefinition.load(next.getLocation().toFile().toPath());
            project = next;
            name.setText(definition.name());
            steps.clear();
            steps.addAll(definition.steps());
            resetStates();
            dirty = false;
            information.setText(next == null
                    ? "Choose a managed Zephyr project to save or run this workflow."
                    : "Project: " + next.getName() + "  |  Native Zephyr builder and runner");
            summary.setText("Loaded workflow. Drag blocks to rearrange or add new actions.");
        } catch (Exception error) {
            project = null;
            steps.clear();
            resetStates();
            MessageDialog.openError(getSite().getShell(), "Workflow Studio", error.getMessage());
        } finally {
            changingProject = false;
            render();
        }
    }

    private void installDnD() {
        var text = TextTransfer.getInstance();
        DragSource fromLibrary = new DragSource(palette, DND.DROP_COPY);
        fromLibrary.setTransfer(text);
        fromLibrary.addDragListener(new DragSourceAdapter() {
            @Override public void dragStart(DragSourceEvent event) {
                event.doit = running == null && palette.getSelectionIndex() >= 0;
            }
            @Override public void dragSetData(DragSourceEvent event) {
                event.data = PREFIX + "add:" + WorkflowDefinition.Step.values()[palette.getSelectionIndex()].name();
            }
        });
        DragSource fromCanvas = new DragSource(canvas, DND.DROP_MOVE);
        fromCanvas.setTransfer(text);
        fromCanvas.addDragListener(new DragSourceAdapter() {
            @Override public void dragStart(DragSourceEvent event) {
                event.doit = running == null && canvas.getSelectionIndex() >= 0;
            }
            @Override public void dragSetData(DragSourceEvent event) {
                event.data = PREFIX + "move:" + canvas.getSelectionIndex();
            }
        });
        DropTarget target = new DropTarget(canvas, DND.DROP_COPY | DND.DROP_MOVE);
        target.setTransfer(text);
        target.addDropListener(new DropTargetAdapter() {
            @Override public void dragOver(DropTargetEvent event) {
                event.feedback = DND.FEEDBACK_INSERT_BEFORE | DND.FEEDBACK_SCROLL;
            }
            @Override public void drop(DropTargetEvent event) {
                if (running != null || !(event.data instanceof String value) || !value.startsWith(PREFIX))
                    return;
                int index = event.item instanceof TableItem item ? canvas.indexOf(item) : steps.size();
                try {
                    if (value.startsWith(PREFIX + "add:")) {
                        var step = WorkflowDefinition.Step.valueOf(value.substring((PREFIX + "add:").length()));
                        var candidate = new ArrayList<>(steps);
                        candidate.add(index, step);
                        edit(candidate, index);
                    } else if (value.startsWith(PREFIX + "move:")) {
                        int source = Integer.parseInt(value.substring((PREFIX + "move:").length()));
                        move(source, index);
                    }
                } catch (IllegalArgumentException error) {
                    MessageDialog.openInformation(getSite().getShell(), "Workflow order", error.getMessage());
                }
            }
        });
    }

    private void addSelected() {
        int index = palette.getSelectionIndex();
        if (index < 0 || running != null) return;
        var candidate = new ArrayList<>(steps);
        // A new action goes before a terminal block, not after hardware programming.
        int insertion = !candidate.isEmpty() && candidate.get(candidate.size() - 1).terminal()
                ? candidate.size() - 1 : candidate.size();
        candidate.add(insertion, WorkflowDefinition.Step.values()[index]);
        edit(candidate, insertion);
    }
    private void removeSelected() {
        int index = canvas.getSelectionIndex();
        if (index < 0 || running != null) return;
        var candidate = new ArrayList<>(steps);
        candidate.remove(index);
        edit(candidate, Math.min(index, candidate.size() - 1));
    }
    private void shift(int direction) {
        int index = canvas.getSelectionIndex();
        if (index < 0 || running != null) return;
        move(index, index + direction + (direction > 0 ? 1 : 0));
    }
    private void move(int source, int target) {
        if (source < 0 || source >= steps.size() || target < 0 || target > steps.size()) return;
        var candidate = new ArrayList<>(steps);
        var moved = candidate.remove(source);
        if (source < target) target--;
        candidate.add(target, moved);
        edit(candidate, target);
    }
    private void edit(java.util.List<WorkflowDefinition.Step> candidate, int select) {
        if (running != null) return;
        // Validate before updating the visual or saved model.
        try {
            new WorkflowDefinition(name.getText().isBlank() ? "Untitled workflow" : name.getText(), candidate);
        } catch (IllegalArgumentException error) {
            MessageDialog.openInformation(getSite().getShell(), "Invalid block order", error.getMessage());
            return;
        }
        steps.clear();
        steps.addAll(candidate);
        dirty = true;
        resetStates();
        render();
        if (select >= 0 && select < canvas.getItemCount()) canvas.select(select);
        showDetails();
    }

    private void resetStates() {
        states.clear();
        for (int i = 0; i < steps.size(); i++) states.add("Pending");
    }
    private void render() {
        if (canvas == null || canvas.isDisposed()) return;
        int selection = canvas.getSelectionIndex();
        canvas.setRedraw(false);
        try {
            canvas.removeAll();
            for (int i = 0; i < steps.size(); i++) {
                var entry = new TableItem(canvas, SWT.NONE);
                entry.setText(new String[] {String.format("%02d", i + 1),
                        steps.get(i).label(), i < states.size() ? states.get(i) : "Pending"});
            }
            if (selection >= 0 && selection < steps.size()) canvas.select(selection);
        } finally {
            canvas.setRedraw(true);
        }
        showDetails();
        controls();
    }
    private void controls() {
        if (run == null || run.isDisposed()) return;
        boolean idle = running == null;
        boolean ready = idle && project != null;
        projects.setEnabled(idle);
        name.setEnabled(idle);
        palette.setEnabled(idle);
        canvas.setEnabled(idle);
        add.setEnabled(idle);
        remove.setEnabled(idle && canvas.getSelectionIndex() >= 0);
        up.setEnabled(idle && canvas.getSelectionIndex() > 0);
        down.setEnabled(idle && canvas.getSelectionIndex() >= 0
                && canvas.getSelectionIndex() < steps.size() - 1);
        save.setEnabled(ready);
        run.setEnabled(ready && !steps.isEmpty());
        cancel.setEnabled(!idle);
    }
    private void showDetails() {
        if (details == null || details.isDisposed()) return;
        int selected = canvas.getSelectionIndex();
        if (selected < 0 || selected >= steps.size()) {
            details.setText("Select a block to see its purpose.\n\n"
                    + "Execution stops on the first failure or cancellation.\n"
                    + "Flash and Flash & Debug must be the final block.\n\n"
                    + "The native Flash launcher performs its own verified build.\n"
                    + "A preceding Build block currently causes a second build.");
            return;
        }
        var step = steps.get(selected);
        details.setText(step.label() + "\n\n" + step.description() + "\n\n"
                + (step.terminal()
                    ? "Hardware write requires confirmation. Flash must complete before the debugger is requested."
                    : "This action uses the Workbench's existing managed Zephyr builder.")
                + "\n\n" + "Step " + (selected + 1) + " of " + steps.size());
    }

    private WorkflowDefinition definition() {
        return new WorkflowDefinition(name.getText().trim(), steps);
    }
    private boolean save() {
        if (project == null || running != null) return false;
        try {
            definition().save(project.getLocation().toFile().toPath());
            dirty = false;
            summary.setText("Saved in .zew/workflows/default.properties");
            return true;
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Save workflow", error.getMessage());
            return false;
        }
    }

    private void runWorkflow() {
        if (running != null || project == null || !save()) return;
        final WorkflowDefinition definition = definition();
        final IProject selected = project;
        final String board, profile;
        final Path build;
        try {
            Properties settings = ProjectContext.settings(selected);
            board = settings.getProperty("board");
            profile = ProjectContext.profileIdentity(settings);
            build = ProjectContext.buildDirectory(selected, settings).toAbsolutePath().normalize();
            if (board == null || board.isBlank()) throw new IllegalStateException("No selected board.");
        } catch (Exception error) {
            MessageDialog.openError(getSite().getShell(), "Run workflow", error.getMessage());
            return;
        }
        resetStates();
        running = new Job("Zephyr workflow — " + selected.getName()) {
            @Override protected IStatus run(IProgressMonitor monitor) {
                var console = new MessageConsole("Zephyr workflow — " + selected.getName(), null);
                ConsolePlugin.getDefault().getConsoleManager().addConsoles(new IConsole[] {console});
                ConsolePlugin.getDefault().getConsoleManager().showConsoleView(console);
                int active = -1;
                try (var stream = console.newMessageStream()) {
                    monitor.beginTask("Workflow: " + definition.name(), definition.steps().size());
                    stream.println("Workflow: " + definition.name());
                    stream.println("Project: " + selected.getName() + " | Board: " + board
                            + " | Profile: " + profile + " | Build: " + build);
                    for (int i = 0; i < definition.steps().size(); i++) {
                        active = i;
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        var step = definition.steps().get(i);
                        verifyIdentity(selected, board, profile, build);
                        report(i, "Running...", "Running " + step.label());
                        monitor.subTask(step.label());
                        stream.println("[" + (i + 1) + "/" + definition.steps().size() + "] " + step.label());
                        switch (step) {
                            case CLEAN -> selected.build(IncrementalProjectBuilder.CLEAN_BUILD,
                                    Activator.ID + ".zephyrBuilder", Map.of(), monitor);
                            case BUILD -> selected.build(IncrementalProjectBuilder.FULL_BUILD,
                                    Activator.ID + ".zephyrBuilder", Map.of("zew.expectedBuild", build.toString()), monitor);
                            case FLASH, FLASH_DEBUG -> {
                                // The existing launcher owns flash confirmation, native runner
                                // preflight, process cleanup, and flash-to-debug integrity.
                                var configuration = ZephyrFlashShortcut.configuration(
                                        selected, step == WorkflowDefinition.Step.FLASH_DEBUG);
                                var launch = configuration.launch(ILaunchManager.RUN_MODE, monitor, false);
                                if (launch == null || launch.getProcesses().length == 0)
                                    throw new java.io.InterruptedIOException("Flash was cancelled or not started.");
                            }
                        }
                        if (monitor.isCanceled()) return Status.CANCEL_STATUS;
                        verifyIdentity(selected, board, profile, build);
                        String result = step == WorkflowDefinition.Step.FLASH_DEBUG
                                ? "Flash verified; Debug requested" : "Passed";
                        report(i, result, step.label() + ": " + result);
                        stream.println(result);
                        monitor.worked(1);
                    }
                    return Status.OK_STATUS;
                } catch (Exception error) {
                    IStatus outcome = monitor.isCanceled() || error instanceof java.io.InterruptedIOException
                            ? Status.CANCEL_STATUS
                            : new Status(IStatus.ERROR, Activator.ID, "Workflow failed: " + error.getMessage(), error);
                    if (active >= 0)
                        report(active, outcome.matches(IStatus.CANCEL) ? "Cancelled" : "Failed",
                                outcome.matches(IStatus.CANCEL) ? "Workflow cancelled."
                                        : "Stopped at step " + (active + 1) + ": " + error.getMessage());
                    return outcome;
                } finally {
                    monitor.done();
                    var display = PlatformUI.getWorkbench().getDisplay();
                    display.asyncExec(() -> {
                        if (canvas.isDisposed()) return;
                        if (monitor.isCanceled()) summary.setText("Workflow cancelled.");
                        running = null;
                        controls();
                    });
                }
            }
        };
        running.setUser(true);
        report(-1, "", "Running workflow...");
        render();
        running.schedule();
    }

    private static void verifyIdentity(IProject project, String board, String profile, Path build)
            throws Exception {
        var settings = ProjectContext.settings(project);
        if (!board.equals(settings.getProperty("board"))
                || !profile.equals(ProjectContext.profileIdentity(settings))
                || !build.equals(ProjectContext.buildDirectory(project, settings).toAbsolutePath().normalize()))
            throw new java.io.IOException("Board, profile or build output changed; workflow stopped.");
    }

    private void report(int index, String state, String message) {
        if (canvas == null || canvas.isDisposed()) return;
        var display = canvas.getDisplay();
        display.asyncExec(() -> {
            if (canvas.isDisposed()) return;
            if (index >= 0 && index < states.size()) states.set(index, state);
            summary.setText(message);
            render();
        });
    }

    @Override public void setFocus() {
        if (canvas != null && !canvas.isDisposed()) canvas.setFocus();
    }
    @Override public void dispose() {
        if (running != null) running.cancel();
        super.dispose();
    }
}
