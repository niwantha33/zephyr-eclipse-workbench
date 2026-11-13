// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import io.github.niwantha33.zew.core.GeneratedConfigDiff;
import io.github.niwantha33.zew.core.GeneratedConfigHistory;

/**
 * XML-style expandable, read-only inspection of effective generated Kconfig.
 * Section headings are comments from .config, not proof of true Kconfig menu
 * ancestry. No configuration files or project sources are edited here.
 */
final class GeneratedConfigDiffPanel {
    private static final int MAX_VISIBLE = 2500;
    private final Tree tree;
    private final Label summary;
    private final Label source;
    private final Text search;
    private final Button differencesOnly;
    private Path build;
    private Path manualBaseline;
    private GeneratedConfigDiff.Comparison comparison;

    GeneratedConfigDiffPanel(Composite root) {
        root.setLayout(new GridLayout(1, false));
        var title = new Label(root, SWT.WRAP);
        title.setText("GENERATED KCONFIG  /  EXPANDABLE TREE & VALUE DIFFERENCES");
        title.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        var toolbar = new Composite(root, SWT.NONE);
        toolbar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        toolbar.setLayout(new GridLayout(4, false));

        search = new Text(toolbar, SWT.SEARCH | SWT.ICON_SEARCH | SWT.CANCEL | SWT.BORDER);
        search.setMessage("Find CONFIG_ symbol or value");
        search.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        search.addModifyListener(event -> render());

        differencesOnly = new Button(toolbar, SWT.CHECK);
        differencesOnly.setText("Changes only");
        differencesOnly.addListener(SWT.Selection, event -> render());

        Button choose = new Button(toolbar, SWT.PUSH);
        choose.setText("Compare with older .config…");
        choose.setToolTipText("Select a saved .config file; this does not import or apply it.");
        choose.addListener(SWT.Selection, event -> selectPreviousFile(root));

        Button automatic = new Button(toolbar, SWT.PUSH);
        automatic.setText("Previous build");
        automatic.setToolTipText("Use the snapshot captured before the last successful managed build.");
        automatic.addListener(SWT.Selection, event -> {
            manualBaseline = null;
            refreshData();
        });

        summary = new Label(root, SWT.WRAP);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        summary.setText("Select a configured Zephyr project.");

        tree = new Tree(root, SWT.BORDER | SWT.FULL_SELECTION | SWT.H_SCROLL | SWT.V_SCROLL);
        tree.setHeaderVisible(true);
        tree.setLinesVisible(true);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        String[] headers = {"Section / CONFIG_ symbol", "Old value", "New value", "Change"};
        int[] widths = {340, 170, 170, 120};
        for (int index = 0; index < headers.length; index++) {
            var column = new TreeColumn(tree, SWT.LEFT);
            column.setText(headers[index]);
            column.setWidth(widths[index]);
            column.setResizable(true);
        }

        source = new Label(root, SWT.WRAP);
        source.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        source.setText("Read-only. Sections follow generated comment headings, not the full Kconfig dependency tree.");
    }

    void reload(Path selectedBuild) {
        Path normalized = selectedBuild == null ? null : selectedBuild.toAbsolutePath().normalize();
        if (build == null ? normalized != null : !build.equals(normalized))
            manualBaseline = null; // Never carry a baseline across board/profile/build selections.
        build = normalized;
        refreshData();
    }

    private void selectPreviousFile(Composite parent) {
        if (build == null) return;
        FileDialog chooser = new FileDialog(parent.getShell(), SWT.OPEN);
        chooser.setText("Compare with older generated Zephyr .config");
        chooser.setFileName(".config");
        chooser.setFilterNames(new String[] {"Kconfig files", "All files"});
        chooser.setFilterExtensions(new String[] {"*.config;*.conf;*.txt", "*.*"});
        String selected = chooser.open();
        if (selected == null) return;
        manualBaseline = Path.of(selected).toAbsolutePath().normalize();
        refreshData();
    }

    private void refreshData() {
        comparison = null;
        if (tree == null || tree.isDisposed()) return;
        if (build == null) {
            summary.setText("No selected build. Configure and build the application first.");
            source.setText("Generated .config and comparison are unavailable.");
            tree.removeAll();
            return;
        }
        Path current = build.resolve("zephyr/.config");
        if (!Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS)) {
            summary.setText("No generated zephyr/.config for this selected build.");
            source.setText("Build the selected board/profile to generate a configuration.");
            tree.removeAll();
            return;
        }
        Path previous = manualBaseline == null ? GeneratedConfigHistory.baseline(build) : manualBaseline;
        try {
            String currentText = GeneratedConfigDiff.read(current);
            String previousText = Files.isRegularFile(previous, LinkOption.NOFOLLOW_LINKS)
                    ? GeneratedConfigDiff.read(previous) : null;
            if (manualBaseline != null && previousText == null)
                throw new java.io.IOException("The selected previous file is missing or not a regular file.");
            comparison = GeneratedConfigDiff.compare(previousText, currentText);
            source.setText("Current: " + current + "\nBaseline: "
                    + (previousText == null ? "unavailable"
                    : (manualBaseline == null ? "before last verified managed build" : previous.toString()))
                    + "\nRead-only generated output. Headings are display groups, not proven menu hierarchy."
                    + " Values are generated configuration, not proof of running firmware.");
            render();
        } catch (Exception error) {
            tree.removeAll();
            summary.setText("Configuration comparison unavailable: " + error.getMessage());
            source.setText("No changes were made. Select a valid generated .config or rebuild the target.");
        }
    }

    private void render() {
        if (tree == null || tree.isDisposed()) return;
        tree.setRedraw(false);
        try {
            tree.removeAll();
            var data = comparison;
            if (data == null) return;
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            boolean differences = differencesOnly.getSelection() && data.hasBaseline();
            int shown = 0;
            for (var section : data.sections()) {
                TreeItem group = null;
                for (var item : section.entries()) {
                    if (differences && item.change() == GeneratedConfigDiff.Change.UNCHANGED)
                        continue;
                    if (!query.isEmpty() && !item.symbol().toLowerCase(Locale.ROOT).contains(query)
                            && !String.valueOf(item.previous()).toLowerCase(Locale.ROOT).contains(query)
                            && !String.valueOf(item.current()).toLowerCase(Locale.ROOT).contains(query)
                            && !section.heading().toLowerCase(Locale.ROOT).contains(query))
                        continue;
                    if (shown >= MAX_VISIBLE) break;
                    if (group == null) {
                        group = new TreeItem(tree, SWT.NONE);
                        group.setText(0, section.heading());
                    }
                    TreeItem line = new TreeItem(group, SWT.NONE);
                    line.setText(new String[] {item.symbol(), display(item.previous()),
                            display(item.current()), label(item.change(), data.hasBaseline())});
                    int color = switch (item.change()) {
                        case ADDED -> SWT.COLOR_DARK_GREEN;
                        case CHANGED -> SWT.COLOR_DARK_MAGENTA;
                        case REMOVED -> SWT.COLOR_DARK_RED;
                        case UNCHANGED -> SWT.COLOR_WIDGET_FOREGROUND;
                    };
                    if (item.change() != GeneratedConfigDiff.Change.UNCHANGED) {
                        line.setForeground(tree.getDisplay().getSystemColor(color));
                        line.setForeground(3, tree.getDisplay().getSystemColor(color));
                    }
                    shown++;
                }
                if (group != null) group.setExpanded(true);
                if (shown >= MAX_VISIBLE) break;
            }
            String baseline = data.hasBaseline()
                    ? "Added: " + data.added() + "  |  Changed: " + data.changed()
                    + "  |  Removed: " + data.removed() + "  |  Unchanged: " + data.unchanged()
                    : "No previous generated snapshot; values shown without change claims.";
            summary.setText(baseline + "   •   Displayed: " + shown
                    + (shown >= MAX_VISIBLE ? " (limit 2500: narrow your search)" : "")
                    + (differencesOnly.getSelection() && !data.hasBaseline()
                            ? "   •   Changes-only requires a baseline." : ""));
            summary.getParent().layout(true, true);
        } finally {
            tree.setRedraw(true);
        }
    }

    private static String display(String value) { return value == null ? "— (absent)" : value; }
    private static String label(GeneratedConfigDiff.Change change, boolean hasBaseline) {
        if (!hasBaseline) return "Current";
        return switch (change) {
            case ADDED -> "Added";
            case CHANGED -> "Changed";
            case REMOVED -> "Removed";
            case UNCHANGED -> "Unchanged";
        };
    }
}
