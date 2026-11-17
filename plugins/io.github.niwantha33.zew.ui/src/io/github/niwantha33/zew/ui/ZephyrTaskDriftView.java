// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import io.github.niwantha33.zew.core.ProcessRunner;

/**
 * Saved-CTF Task Drift preview. User action only: no live target, GDB, timers,
 * console hijacking, automatic firmware patch or polling.
 */
public final class ZephyrTaskDriftView extends ViewPart {
    public static final String ID = Activator.ID + ".taskDrift";
    private static final int MAX_VISIBLE_CYCLES = 500;
    private static final String[] TOOLS = {
            "offline_trace_json.py", "semantic_trace.py", "zephyr_ctf_binary.py",
            "cpu_analysis.py", "contention_detector.py", "thread_timeline.py",
            "synchronization_analysis.py", "causal_drift.py"
    };
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Job loadJob;
    private IProject contextProject;
    private Path selectedTrace;
    private Text tag, period, threadId, anchor, deadline;
    private Label status;
    private Table table;

    @Override public void createPartControl(Composite parent) {
        parent.setLayout(new GridLayout(1, false));
        Label description = new Label(parent, SWT.WRAP);
        description.setText("OFFLINE preview: requires application zew_cycle_* markers. "
                + "A switch-in is not a task cycle. No automatic root-cause or gate attribution.");
        description.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite controls = new Composite(parent, SWT.NONE);
        controls.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        controls.setLayout(new GridLayout(6, false));
        Button open = new Button(controls, SWT.PUSH);
        open.setText("Open saved CTF...");
        open.addListener(SWT.Selection, ignored -> {
            Path choice = SavedCtfFilePicker.choose(getSite().getShell());
            if (choice != null) {
                selectedTrace = choice;
                status.setText("Selected: " + choice.getFileName() + ". Select an imported project, then Analyze.");
            }
        });
        Button analyze = new Button(controls, SWT.PUSH);
        analyze.setText("Analyze");
        analyze.addListener(SWT.Selection, ignored -> load());
        tag = field(controls, "Tag (uint32)", "7");
        period = field(controls, "Period (us)", "10000");
        threadId = field(controls, "Thread ID (optional)", "");
        anchor = field(controls, "Anchor ns (optional)", "");
        deadline = field(controls, "Deadline us (optional)", "");

        status = new Label(parent, SWT.WRAP);
        status.setText("No trace loaded. Select a Zephyr project and a saved CTF directory.");
        status.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        table = new Table(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        table.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        String[] headings = {"Sequence", "Phase (us)", "Lateness (us)", "Release to start (us)",
                "Finish (ns)", "Deadline miss", "Switch interruptions", "Paired mutex blocks",
                "Intervening threads"};
        int[] widths = {90, 115, 130, 150, 150, 115, 155, 280, 280};
        for (int i = 0; i < headings.length; i++) {
            TableColumn column = new TableColumn(table, SWT.LEFT);
            column.setText(headings[i]);
            column.setWidth(widths[i]);
        }
    }

    private static Text field(Composite parent, String name, String value) {
        Composite column = new Composite(parent, SWT.NONE);
        column.setLayout(new GridLayout(1, false));
        new Label(column, SWT.NONE).setText(name);
        Text input = new Text(column, SWT.BORDER);
        input.setLayoutData(new GridData(145, SWT.DEFAULT));
        input.setText(value);
        return input;
    }

    /** Explicit command selection, not a background discovery or target probe. */
    public void displayProject(IProject project) {
        if (project != null && project.isAccessible()) contextProject = project;
    }

    private IProject selectedProject() {
        var selection = getSite().getWorkbenchWindow().getSelectionService().getSelection();
        if (selection instanceof IStructuredSelection selected && selected.size() == 1) {
            IProject current = SelectedProject.importedProject(selected.getFirstElement());
            if (current != null && current.isAccessible()) contextProject = current;
        }
        return contextProject != null && contextProject.isAccessible() ? contextProject : null;
    }

    private record Request(long tag, long periodUs, String threadId, Long anchorNs, Long deadlineUs) {}
    private record Result(String status, String reason, String reference, String phaseTrend,
                          List<String[]> cycles, int count) {}

    private Request readInput() {
        long taskTag = Long.parseLong(tag.getText().strip());
        long periodValue = Long.parseLong(period.getText().strip());
        if (taskTag < 0 || taskTag > 0xFFFFFFFFL || periodValue <= 0 || periodValue > 86_400_000_000L)
            throw new IllegalArgumentException("Tag must be uint32 and period 1..86400000000 us.");
        Long origin = optionalLong(anchor, false);
        Long deadlineValue = optionalLong(deadline, true);
        if (deadlineValue != null && deadlineValue > 86_400_000_000L)
            throw new IllegalArgumentException("Deadline exceeds one day.");
        return new Request(taskTag, periodValue, threadId.getText().strip(), origin, deadlineValue);
    }

    private static Long optionalLong(Text control, boolean positive) {
        String raw = control.getText().strip();
        if (raw.isEmpty()) return null;
        long result = Long.parseLong(raw);
        if (result < 0 || (positive && result == 0))
            throw new IllegalArgumentException("Optional timing fields must be nonnegative (deadline positive).");
        return result;
    }

    private void load() {
        if (selectedTrace == null) {
            status.setText("Open a saved CTF directory first.");
            return;
        }
        IProject project = selectedProject();
        if (project == null) {
            status.setText("Select an imported Zephyr project in Project Explorer.");
            return;
        }
        final Request request;
        final Properties settings;
        try {
            request = readInput();
            settings = ProjectContext.settings(project);
        } catch (Exception error) {
            status.setText("Invalid task configuration: " + safe(error.getMessage()));
            return;
        }
        final Path source = selectedTrace.toAbsolutePath().normalize();
        int ticket = generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        table.removeAll();
        status.setText("Analyzing saved CTF evidence (no target access)...");
        loadJob = new Job("Analyze periodic RTOS task drift (saved CTF)") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try {
                    Result result = parse(invoke(project, settings, source, request));
                    if (!monitor.isCanceled()) present(ticket, result, null);
                } catch (Exception error) {
                    if (!monitor.isCanceled()) present(ticket, null, safe(error.getMessage()));
                }
                return Status.OK_STATUS;
            }
        };
        loadJob.setUser(true);
        loadJob.schedule();
    }

    private static String invoke(IProject project, Properties settings, Path source, Request request)
            throws Exception {
        Path ctf = Files.isDirectory(source.resolve("ctf")) ? source.resolve("ctf") : source;
        if (Files.isSymbolicLink(ctf) || !Files.isDirectory(ctf)
                || !Files.isRegularFile(ctf.resolve("metadata"), LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(ctf.resolve("channel0_0"), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Select a saved CTF folder with metadata and channel0_0.");
        String interpreter = settings.getProperty("python", "");
        if (interpreter.isBlank() || !Files.isRegularFile(Path.of(interpreter)))
            throw new IOException("Configure managed Python in Project Properties > Zephyr.");
        Path temporary = Files.createTempDirectory("zew-causal-drift-");
        try {
            for (String filename : TOOLS) {
                var entry = Activator.getDefault().getBundle().getEntry("tools/rtos_analysis/" + filename);
                if (entry == null) throw new IOException("Missing installed analysis tool: " + filename);
                try (var in = entry.openStream()) {
                    Files.copy(in, temporary.resolve(filename));
                }
            }
            var args = new ArrayList<>(TracePythonLaunch.command(interpreter,
                    temporary.resolve("offline_trace_json.py"), "--mode", "causal_drift",
                    "--ctf", ctf.toString(), "--tag", Long.toString(request.tag()),
                    "--period-us", Long.toString(request.periodUs())));
            if (!request.threadId().isEmpty())
                args.addAll(List.of("--thread-id", request.threadId()));
            if (request.anchorNs() != null)
                args.addAll(List.of("--anchor-ns", request.anchorNs().toString()));
            if (request.deadlineUs() != null)
                args.addAll(List.of("--deadline-us", request.deadlineUs().toString()));
            return new ProcessRunner().run(args, project.getLocation().toFile().toPath(),
                    Duration.ofSeconds(45), () -> Thread.currentThread().isInterrupted(),
                    ignored -> {}, ProjectContext.environment(settings)).requireCompleteOutput();
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private static Result parse(String json) {
        Map<String, String> values = new HashMap<>();
        for (MemoryProfilerJson.Row row : MemoryProfilerJson.parseFields(json))
            if (values.putIfAbsent(row.field(), row.rawValue()) != null)
                throw new IllegalArgumentException("Duplicate report field.");
        if (!"1".equals(values.get("schema_version"))
                || !"causal_drift".equals(string(values, "kind")))
            throw new IllegalArgumentException("Unsupported task drift report.");
        String state = string(values, "status");
        if (!List.of("ok", "no_markers", "incomplete").contains(state))
            throw new IllegalArgumentException("Unknown evidence status.");
        String reference = string(values, "reference");
        int count = Integer.parseInt(values.getOrDefault("measured_cycles", "-1"));
        if (count < 0 || count > 10000) throw new IllegalArgumentException("Invalid cycle count.");
        var rows = new ArrayList<String[]>();
        if ("ok".equals(state)) {
            for (int i = 0; i < Math.min(count, MAX_VISIBLE_CYCLES); i++) {
                String key = "cycles[" + i + "].";
                String seq = values.get(key + "sequence");
                String start = values.get(key + "start_ns");
                if (seq == null || start == null || Long.parseLong(start) < 0)
                    throw new IllegalArgumentException("Missing cycle timestamp.");
                var objects = new ArrayList<String>();
                for (int j = 0; j < 100; j++) {
                    String prefix = key + "paired_mutex_blocks[" + j + "].";
                    if (!values.containsKey(prefix + "object")) break;
                    objects.add(string(values, prefix + "object") + ": "
                            + micros(values.get(prefix + "blocked_ns")) + " us");
                }
                var intervening = new ArrayList<String>();
                for (int j = 0; j < 100; j++) {
                    String raw = values.get(key + "intervening_threads[" + j + "]");
                    if (raw == null) break;
                    intervening.add(MemoryProfilerJson.unquote(raw));
                }
                rows.add(new String[] {seq, micros(values.get(key + "relative_phase_ns")),
                        micros(values.get(key + "start_lateness_ns")),
                        micros(values.get(key + "release_to_start_ns")),
                        nullable(values.get(key + "finish_ns")),
                        nullable(values.get(key + "deadline_missed")),
                        nullable(values.get(key + "scheduler_interruptions_observed")),
                        String.join(", ", objects), String.join(", ", intervening)});
            }
        }
        return new Result(state, string(values, "reason"), reference,
                micros(values.get("phase_trend_ns")), rows, count);
    }

    private static String string(Map<String, String> values, String name) {
        String raw = values.get(name);
        if (raw == null || "null".equals(raw)) throw new IllegalArgumentException("Missing " + name);
        return MemoryProfilerJson.unquote(raw);
    }

    private static String nullable(String raw) {
        return raw == null || "null".equals(raw) ? "unknown" : raw;
    }

    private static String micros(String raw) {
        if (raw == null || "null".equals(raw)) return "unknown";
        return new BigDecimal(raw).divide(BigDecimal.valueOf(1000)).toPlainString();
    }

    private static String safe(String message) {
        if (message == null || message.isBlank()) return "analysis failed";
        String flat = message.replaceAll("[\\p{Cntrl}]", " ").strip();
        return flat.length() > 220 ? flat.substring(0, 220) : flat;
    }

    private void present(int ticket, Result result, String failure) {
        org.eclipse.swt.widgets.Display.getDefault().asyncExec(() -> {
            if (generation.get() != ticket || table == null || table.isDisposed()) return;
            table.removeAll();
            if (result == null) {
                status.setText("Analysis unavailable: " + failure);
                return;
            }
            if (!"ok".equals(result.status())) {
                status.setText(result.status() + ": " + result.reason());
                return;
            }
            for (String[] fields : result.cycles()) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(fields);
            }
            status.setText(result.count() + " observed cycles | reference: " + result.reference()
                    + " | phase trend: " + result.phaseTrend() + " us"
                    + (result.count() > MAX_VISIBLE_CYCLES ? " | only first 500 cycles shown" : "")
                    + " | interruptions are not proven preemption causes");
        });
    }

    @Override public void setFocus() {
        if (tag != null && !tag.isDisposed()) tag.setFocus();
    }

    @Override public void dispose() {
        generation.incrementAndGet();
        if (loadJob != null) loadJob.cancel();
        super.dispose();
    }
}
