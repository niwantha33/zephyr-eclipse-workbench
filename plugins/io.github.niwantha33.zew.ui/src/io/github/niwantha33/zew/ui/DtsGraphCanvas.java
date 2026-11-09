// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;
import java.util.function.Consumer;

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

/** Native, evidence-only DTS graph with typed connections and focused navigation. */
public final class DtsGraphCanvas extends Canvas {
    private DtsGraphModel.Layout layout = DtsGraphModel.focus(null, null);
    private DtsGraphModel.Snapshot snapshot;
    private Consumer<DtsGraphModel.Node> onInspect = ignored -> {};
    private Consumer<DtsGraphModel.Node> onOpen = ignored -> {};
    private int zoom = 100, offsetX, offsetY;
    private DtsGraphModel.Node lastClicked;
    private String emptyTitle = "Build the selected Zephyr board to inspect its Devicetree.";
    private final Color background, surface, foreground, muted, line, reference;
    private final Color enabled, disabled, selectedColor;
    private final Font heading;

    public DtsGraphCanvas(Composite parent, int style) {
        super(parent, style | SWT.DOUBLE_BUFFERED | SWT.H_SCROLL | SWT.V_SCROLL);
        background = new Color(getDisplay(), 244, 248, 252);
        surface = new Color(getDisplay(), 255, 255, 255);
        foreground = new Color(getDisplay(), 20, 34, 53);
        muted = new Color(getDisplay(), 90, 110, 131);
        line = new Color(getDisplay(), 163, 178, 195);
        reference = new Color(getDisplay(), 43, 112, 206);
        enabled = new Color(getDisplay(), 29, 145, 94);
        disabled = new Color(getDisplay(), 145, 153, 164);
        selectedColor = new Color(getDisplay(), 232, 139, 40);
        FontData[] data = getFont().getFontData();
        for (FontData datum : data) datum.setStyle(SWT.BOLD);
        heading = new Font(getDisplay(), data);
        addDisposeListener(event -> {
            for (Color color : List.of(background, surface, foreground, muted, line,
                    reference, enabled, disabled, selectedColor)) color.dispose();
            heading.dispose();
        });
        getHorizontalBar().addListener(SWT.Selection, event -> {
            offsetX = getHorizontalBar().getSelection(); redraw();
        });
        getVerticalBar().addListener(SWT.Selection, event -> {
            offsetY = getVerticalBar().getSelection(); redraw();
        });
        addListener(SWT.Resize, event -> scrollbars());
        addPaintListener(event -> paint(event.gc));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent event) {
                DtsGraphModel.Card card = hit(event.x, event.y);
                if (card == null || snapshot == null) return;
                lastClicked = card.node();
                onInspect.accept(card.node());
                if (event.button == 1) focus(card.node().path());
            }
            @Override public void mouseDoubleClick(MouseEvent event) {
                // A first click refocuses and moves cards. Keep the identity
                // from the click rather than hit-testing the changed layout.
                if (lastClicked != null) onOpen.accept(lastClicked);
                else {
                    DtsGraphModel.Card card = hit(event.x, event.y);
                    if (card != null) onOpen.accept(card.node());
                }
            }
        });
        addMouseMoveListener(event -> {
            DtsGraphModel.Card card = hit(event.x, event.y);
            setCursor(getDisplay().getSystemCursor(card == null ? SWT.CURSOR_ARROW : SWT.CURSOR_HAND));
            setToolTipText(card == null ? "Solid: DTS hierarchy; dashed: resolved phandle."
                    : card.node().path() + "\n" + card.node().compatible()
                    + "\nDTS: " + card.node().status() + ", line " + card.node().line());
        });
        addListener(SWT.MouseWheel, event -> {
            if ((event.stateMask & SWT.CTRL) != 0) {
                zoom(zoom + (event.count > 0 ? 10 : -10));
            } else {
                getVerticalBar().setSelection(Math.max(0, Math.min(scrollLimit(false),
                        offsetY - event.count * 40)));
                offsetY = getVerticalBar().getSelection();
                redraw();
            }
            event.doit = false;
        });
    }

    public void onInspect(Consumer<DtsGraphModel.Node> callback) {
        onInspect = callback == null ? ignored -> {} : callback;
    }
    public void onOpen(Consumer<DtsGraphModel.Node> callback) {
        onOpen = callback == null ? ignored -> {} : callback;
    }
    public void show(DtsGraphModel.Snapshot snapshot, String selected) {
        if (isDisposed()) return;
        this.snapshot = snapshot;
        layout = DtsGraphModel.focus(snapshot, selected);
        emptyTitle = layout.message();
        offsetX = offsetY = 0;
        scrollbars();
        redraw();
    }
    public void message(String title) {
        show(null, null);
        emptyTitle = title;
        redraw();
    }
    public void focus(String path) {
        if (snapshot != null && snapshot.observed())
            show(snapshot, path);
    }
    public DtsGraphModel.Layout layoutSnapshot() { return layout; }
    public int zoomPercent() { return zoom; }
    public void zoom(int percent) {
        zoom = Math.max(60, Math.min(160, percent));
        scrollbars();
        redraw();
    }
    private int scale(int value) { return (int) Math.round(value * (zoom / 100.0)); }
    private int x(int raw) { return scale(raw) - offsetX; }
    private int y(int raw) { return scale(raw) - offsetY; }
    private int scrollLimit(boolean horizontal) {
        Rectangle r = getClientArea();
        return Math.max(0, scale(horizontal ? layout.width() : layout.height())
                - (horizontal ? r.width : r.height));
    }
    private void sync(ScrollBar bar, int size, int view, boolean horizontal) {
        bar.setMaximum(Math.max(size, Math.max(1, view)));
        bar.setThumb(Math.max(1, view));
        bar.setIncrement(40);
        bar.setPageIncrement(Math.max(50, view - 60));
        int value = Math.min(horizontal ? offsetX : offsetY, Math.max(0, size - view));
        bar.setSelection(value);
        if (horizontal) offsetX = value;
        else offsetY = value;
    }
    private void scrollbars() {
        if (isDisposed()) return;
        Rectangle r = getClientArea();
        sync(getHorizontalBar(), scale(layout.width()), r.width, true);
        sync(getVerticalBar(), scale(layout.height()), r.height, false);
    }
    private DtsGraphModel.Card hit(int px, int py) {
        for (DtsGraphModel.Card card : layout.cards()) {
            int bx = x(card.x()), by = y(card.y());
            if (px >= bx && px <= bx + scale(DtsGraphModel.CARD_WIDTH)
                    && py >= by && py <= by + scale(DtsGraphModel.CARD_HEIGHT)) return card;
        }
        return null;
    }
    private void paint(GC gc) {
        Rectangle area = getClientArea();
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(background);
        gc.fillRectangle(area);
        if (layout.cards().isEmpty()) {
            gc.setForeground(muted);
            gc.drawText(emptyTitle, 22, 28, true);
            return;
        }
        java.util.Map<String, DtsGraphModel.Card> cards = new java.util.HashMap<>();
        for (DtsGraphModel.Card card : layout.cards()) cards.put(card.node().path(), card);
        for (DtsGraphModel.Edge edge : layout.edges()) {
            DtsGraphModel.Card a = cards.get(edge.source());
            DtsGraphModel.Card b = cards.get(edge.target());
            if (a != null && b != null) drawEdge(gc, a, b, edge);
        }
        for (DtsGraphModel.Card card : layout.cards()) drawCard(gc, card);
    }
    private void drawEdge(GC gc, DtsGraphModel.Card a, DtsGraphModel.Card b,
                          DtsGraphModel.Edge edge) {
        boolean hierarchy = edge.kind().equals("hierarchy");
        int x1 = x(a.x() + DtsGraphModel.CARD_WIDTH), y1 = y(a.y() + DtsGraphModel.CARD_HEIGHT / 2);
        int x2 = x(b.x()), y2 = y(b.y() + DtsGraphModel.CARD_HEIGHT / 2);
        gc.setForeground(hierarchy ? line : reference);
        gc.setLineWidth(Math.max(1, scale(2)));
        gc.setLineStyle(hierarchy ? SWT.LINE_SOLID : SWT.LINE_DASH);
        Path curve = new Path(getDisplay());
        try {
            int bend = (x2 - x1) / 2;
            curve.moveTo(x1, y1);
            curve.cubicTo(x1 + bend, y1, x2 - bend, y2, x2, y2);
            gc.drawPath(curve);
        } finally {
            curve.dispose();
            gc.setLineStyle(SWT.LINE_SOLID);
        }
        // Reference edge labels remain in tooltips/selection inspector so
        // overlapping multi-edges do not become unreadable in busy graphs.
    }
    private void drawCard(GC gc, DtsGraphModel.Card card) {
        int bx = x(card.x()), by = y(card.y());
        int width = scale(DtsGraphModel.CARD_WIDTH);
        int height = scale(DtsGraphModel.CARD_HEIGHT);
        Rectangle viewport = getClientArea();
        if (bx + width < 0 || bx > viewport.width || by + height < 0 || by > viewport.height) return;
        Color accent = card.node().status().equals("disabled") ? disabled : enabled;
        if (card.node().path().equals(layout.focus())) accent = selectedColor;
        gc.setBackground(surface);
        gc.fillRoundRectangle(bx, by, width, height, 14, 14);
        gc.setForeground(accent);
        gc.setLineWidth(card.node().path().equals(layout.focus()) ? 3 : 2);
        gc.drawRoundRectangle(bx, by, width, height, 14, 14);
        gc.setFont(heading);
        gc.setForeground(foreground);
        fit(gc, card.node().name(), bx + scale(12), by + scale(8), width - scale(22));
        gc.setFont(getFont());
        gc.setForeground(muted);
        String compat = card.node().compatible().isBlank() ? "no compatible" : card.node().compatible();
        fit(gc, compat, bx + scale(12), by + scale(34), width - scale(22));
        fit(gc, "DTS " + card.node().status() + " · line " + card.node().line(),
                bx + scale(12), by + scale(58), width - scale(22));
    }
    private void fit(GC gc, String text, int x, int y, int available) {
        if (available <= 0) return;
        String value = text == null ? "" : text.replace('\n', ' ').replace('\r', ' ');
        if (gc.textExtent(value).x > available) {
            while (!value.isEmpty() && gc.textExtent(value + "…").x > available)
                value = value.substring(0, value.offsetByCodePoints(value.length(), -1));
            value += "…";
        }
        gc.drawText(value, x, y, true);
    }
}
