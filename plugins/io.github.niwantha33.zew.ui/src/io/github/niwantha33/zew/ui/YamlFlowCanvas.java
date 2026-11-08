// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;
import java.util.function.BiConsumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Path;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.ScrollBar;

/** Native SWT, zoomable, scrollable cards and curved parent/child connections. */
public final class YamlFlowCanvas extends Canvas {
    private static final int RADIUS = 14;
    private YamlFlowGraph.Layout graph = YamlFlowGraph.from(null);
    private BiConsumer<Integer, Integer> navigate = (line, column) -> {};
    private int horizontalOffset;
    private int verticalOffset;
    private int zoom = 100;
    private int selected = -1;
    private String emptyTitle = "Select a YAML file";
    private String emptyDetail = "Choose a .yaml or .yml file in Project Explorer, then Inspect selected YAML.";

    private final Color background;
    private final Color cardBackground;
    private final Color ink;
    private final Color muted;
    private final Color line;
    private final Color mapping;
    private final Color sequence;
    private final Color scalar;
    private final Color selectedBorder;
    private final Font strongFont;

    public YamlFlowCanvas(Composite parent, int style) {
        super(parent, style | SWT.DOUBLE_BUFFERED | SWT.H_SCROLL | SWT.V_SCROLL);
        background = new Color(getDisplay(), 245, 248, 252);
        cardBackground = new Color(getDisplay(), 255, 255, 255);
        ink = new Color(getDisplay(), 25, 38, 60);
        muted = new Color(getDisplay(), 99, 115, 138);
        line = new Color(getDisplay(), 155, 179, 205);
        mapping = new Color(getDisplay(), 30, 103, 191);
        sequence = new Color(getDisplay(), 118, 78, 176);
        scalar = new Color(getDisplay(), 27, 139, 109);
        selectedBorder = new Color(getDisplay(), 237, 137, 34);
        FontData[] data = getFont().getFontData();
        for (FontData datum : data) datum.setStyle(SWT.BOLD);
        strongFont = new Font(getDisplay(), data);
        addDisposeListener(event -> {
            for (Color color : List.of(background, cardBackground, ink, muted, line,
                    mapping, sequence, scalar, selectedBorder)) color.dispose();
            strongFont.dispose();
        });
        getHorizontalBar().addListener(SWT.Selection, event -> {
            horizontalOffset = getHorizontalBar().getSelection();
            redraw();
        });
        getVerticalBar().addListener(SWT.Selection, event -> {
            verticalOffset = getVerticalBar().getSelection();
            redraw();
        });
        addListener(SWT.Resize, event -> updateScrollBars());
        addPaintListener(event -> draw(event.gc));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent event) {
                YamlFlowGraph.Block hit = hit(event.x, event.y);
                selected = hit == null ? -1 : hit.id();
                if (hit != null) setToolTipText(hit.name() + " — " + hit.type()
                        + " | line " + hit.line() + ":" + hit.column());
                redraw();
            }
            @Override public void mouseDoubleClick(MouseEvent event) {
                YamlFlowGraph.Block hit = hit(event.x, event.y);
                if (hit != null) navigate.accept(hit.line(), hit.column());
            }
        });
        addMouseMoveListener(event -> {
            YamlFlowGraph.Block hit = hit(event.x, event.y);
            setCursor(getDisplay().getSystemCursor(hit == null ? SWT.CURSOR_ARROW : SWT.CURSOR_HAND));
        });
        addListener(SWT.MouseWheel, event -> {
            if ((event.stateMask & SWT.CTRL) != 0) {
                setZoom(zoom + (event.count > 0 ? 10 : -10));
            } else {
                ScrollBar vertical = getVerticalBar();
                int next = verticalOffset - event.count * 35;
                vertical.setSelection(Math.max(0, Math.min(next, scrollLimit(false))));
                verticalOffset = vertical.getSelection();
                redraw();
            }
            event.doit = false;
        });
    }

    public void setNavigate(BiConsumer<Integer, Integer> callback) {
        navigate = callback == null ? (line, column) -> {} : callback;
    }

    public void setObservation(YamlDocumentModel.Snapshot snapshot) {
        if (isDisposed()) return;
        graph = YamlFlowGraph.from(snapshot);
        if (snapshot == null) {
            emptyTitle = "Select a YAML file";
            emptyDetail = "Choose a .yaml or .yml file in Project Explorer.";
        } else if (!snapshot.parsed()) {
            emptyTitle = "No diagram: YAML inspection " + snapshot.status();
            emptyDetail = "Reason: " + snapshot.reason() + ". No source changes were made.";
        } else {
            emptyTitle = "This YAML document has no nodes";
            emptyDetail = graph.reason();
        }
        selected = -1;
        horizontalOffset = 0;
        verticalOffset = 0;
        updateScrollBars();
        redraw();
    }

    /** Display real loading/errors, never misreport a selected file as absent. */
    public void setMessage(String title, String detail) {
        if (isDisposed()) return;
        graph = YamlFlowGraph.from(null);
        emptyTitle = title;
        emptyDetail = detail;
        selected = -1;
        horizontalOffset = 0;
        verticalOffset = 0;
        updateScrollBars();
        redraw();
    }

    public YamlFlowGraph.Layout layoutSnapshot() { return graph; }

    public int zoomPercent() { return zoom; }

    public void setZoom(int percent) {
        int next = Math.max(60, Math.min(160, percent));
        if (next == zoom || isDisposed()) return;
        zoom = next;
        updateScrollBars();
        redraw();
    }

    private int scale(int value) { return (int) Math.round(value * (zoom / 100.0)); }
    private int x(int value) { return scale(value) - horizontalOffset; }
    private int y(int value) { return scale(value) - verticalOffset; }

    private int scrollLimit(boolean horizontal) {
        Rectangle area = getClientArea();
        return Math.max(0, scale(horizontal ? graph.width() : graph.height())
                - (horizontal ? area.width : area.height));
    }

    private void updateScrollBars() {
        if (isDisposed()) return;
        Rectangle area = getClientArea();
        sync(getHorizontalBar(), scale(graph.width()), Math.max(1, area.width), true);
        sync(getVerticalBar(), scale(graph.height()), Math.max(1, area.height), false);
    }

    private void sync(ScrollBar bar, int content, int viewport, boolean horizontal) {
        bar.setMaximum(Math.max(content, viewport));
        bar.setThumb(viewport);
        bar.setIncrement(45);
        bar.setPageIncrement(Math.max(45, viewport - 80));
        int offset = Math.min(horizontal ? horizontalOffset : verticalOffset,
                Math.max(0, content - viewport));
        bar.setSelection(offset);
        if (horizontal) horizontalOffset = offset;
        else verticalOffset = offset;
    }

    private YamlFlowGraph.Block hit(int mouseX, int mouseY) {
        for (YamlFlowGraph.Block block : graph.blocks()) {
            int bx = x(block.x()), by = y(block.y());
            int width = scale(YamlFlowGraph.CARD_WIDTH);
            int height = scale(YamlFlowGraph.CARD_HEIGHT);
            if (mouseX >= bx && mouseX <= bx + width
                    && mouseY >= by && mouseY <= by + height) return block;
        }
        return null;
    }

    private void draw(GC gc) {
        Rectangle area = getClientArea();
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(background);
        gc.fillRectangle(area);
        if (graph.blocks().isEmpty()) {
            gc.setForeground(muted);
            gc.drawText(emptyTitle, 24, 26, true);
            gc.drawText(emptyDetail, 24, 54, true);
            return;
        }
        // Only draw edges that derive from parent/child nesting in the
        // observed YAML model. Never draw guessed Devicetree dependencies.
        for (YamlFlowGraph.Edge edge : graph.edges()) {
            YamlFlowGraph.Block parent = graph.blocks().get(edge.parent());
            YamlFlowGraph.Block child = graph.blocks().get(edge.child());
            drawConnection(gc, parent, child);
        }
        for (YamlFlowGraph.Block block : graph.blocks()) drawBlock(gc, block);
        if (graph.limited()) {
            gc.setForeground(muted);
            gc.drawText("LIMITED PREVIEW — " + graph.reason(), 14,
                    Math.max(10, area.height - 26), true);
        }
    }

    private void drawConnection(GC gc, YamlFlowGraph.Block from, YamlFlowGraph.Block to) {
        int x1 = x(from.x() + YamlFlowGraph.CARD_WIDTH);
        int y1 = y(from.y() + YamlFlowGraph.CARD_HEIGHT / 2);
        int x2 = x(to.x());
        int y2 = y(to.y() + YamlFlowGraph.CARD_HEIGHT / 2);
        Rectangle visible = getClientArea();
        if ((x1 < 0 && x2 < 0) || (x1 > visible.width && x2 > visible.width)
                || (y1 < 0 && y2 < 0) || (y1 > visible.height && y2 > visible.height)) return;
        gc.setForeground(line);
        gc.setLineWidth(Math.max(1, scale(2)));
        Path curve = new Path(getDisplay());
        try {
            int bend = Math.max(14, (x2 - x1) / 2);
            curve.moveTo(x1, y1);
            curve.cubicTo(x1 + bend, y1, x2 - bend, y2, x2, y2);
            gc.drawPath(curve);
        } finally {
            curve.dispose();
        }
        int arrow = Math.max(4, scale(5));
        gc.setBackground(line);
        gc.fillPolygon(new int[] {x2, y2, x2 - arrow, y2 - arrow,
                x2 - arrow, y2 + arrow});
    }

    private void drawBlock(GC gc, YamlFlowGraph.Block block) {
        int bx = x(block.x()), by = y(block.y());
        int width = scale(YamlFlowGraph.CARD_WIDTH);
        int height = scale(YamlFlowGraph.CARD_HEIGHT);
        Rectangle view = getClientArea();
        if (bx + width < 0 || bx > view.width || by + height < 0 || by > view.height) return;
        Color accent = switch (block.type()) {
            case "mapping" -> mapping;
            case "sequence" -> sequence;
            default -> scalar;
        };
        gc.setBackground(cardBackground);
        gc.fillRoundRectangle(bx, by, width, height, RADIUS, RADIUS);
        gc.setForeground(selected == block.id() ? selectedBorder : line);
        gc.setLineWidth(selected == block.id() ? 3 : 1);
        gc.drawRoundRectangle(bx, by, width, height, RADIUS, RADIUS);
        gc.setBackground(accent);
        gc.fillRoundRectangle(bx + 1, by + 1, scale(5), height - 2, 3, 3);
        int left = bx + scale(14);
        gc.setFont(strongFont);
        gc.setForeground(ink);
        drawFitted(gc, block.name(), left, by + scale(7), width - scale(80));
        gc.setFont(getFont());
        gc.setForeground(muted);
        String body = switch (block.type()) {
            case "mapping" -> "MAP  ·  " + block.preview();
            case "sequence" -> "LIST  ·  " + block.preview();
            default -> block.type().toUpperCase(java.util.Locale.ROOT) + "  ·  " + block.preview();
        };
        drawFitted(gc, body, left, by + scale(32), width - scale(22));
        gc.setForeground(accent);
        String location = "L" + block.line();
        Point tagSize = gc.textExtent(location);
        gc.drawText(location, bx + width - tagSize.x - scale(10), by + scale(9), true);
    }

    private void drawFitted(GC gc, String text, int left, int top, int available) {
        if (available <= 0) return;
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        if (gc.textExtent(value).x > available) {
            while (!value.isEmpty() && gc.textExtent(value + "…").x > available)
                value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
            value += "…";
        }
        gc.drawText(value, left, top, true);
    }
}
