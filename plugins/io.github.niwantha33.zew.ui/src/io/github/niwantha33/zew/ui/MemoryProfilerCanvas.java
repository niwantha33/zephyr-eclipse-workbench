// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Diagram;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Range;
import io.github.niwantha33.zew.ui.MemoryProfilerLayout.Region;

/** SWT-only linker-memory diagram. Row tracks are individually scaled, not one flat address space. */
public final class MemoryProfilerCanvas extends Canvas {
    public static final int WIDTH = 1100, ROW_HEIGHT = 122, HEADER_HEIGHT = 86;
    private static final int TRACK_X = 337, TRACK_WIDTH = 640;
    private Diagram data;
    private int selection = -1;
    private Consumer<Region> listener = region -> {};

    public MemoryProfilerCanvas(Composite parent) {
        super(parent, SWT.BORDER | SWT.DOUBLE_BUFFERED);
        addPaintListener(event -> paint(event.gc));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDown(MouseEvent event) {
                setFocus();
                selectIndex((event.y - HEADER_HEIGHT) / ROW_HEIGHT, event.y >= HEADER_HEIGHT);
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent event) {
                if (data == null || data.regions().isEmpty()) return;
                if (event.keyCode == SWT.ARROW_DOWN) selectIndex(Math.min(selection + 1, data.regions().size() - 1), true);
                if (event.keyCode == SWT.ARROW_UP) selectIndex(Math.max(0, selection - 1), true);
            }
        });
        setToolTipText("Each region has its own scale. VMA and explicit LMA are linker observations, not measured usage.");
    }

    public void setDiagram(Diagram incoming) {
        data = incoming;
        selection = -1;
        int count = data == null ? 0 : data.regions().size();
        setSize(WIDTH, HEADER_HEIGHT + Math.max(1, count) * ROW_HEIGHT + 12);
        redraw();
    }

    public void onSelection(Consumer<Region> callback) {
        listener = callback == null ? region -> {} : callback;
    }

    private void selectIndex(int index, boolean inside) {
        if (!inside || data == null || index < 0 || index >= data.regions().size()) return;
        selection = index;
        redraw();
        Region region = data.regions().get(index);
        setToolTipText(region.name() + " [" + MemoryProfilerLayout.hex(region.address().start())
                + ", " + MemoryProfilerLayout.hex(region.address().end()) + ")");
        listener.accept(region);
    }

    private int color(String kind) {
        return switch (kind) {
            case "flash" -> SWT.COLOR_DARK_BLUE;
            case "ram" -> SWT.COLOR_DARK_GREEN;
            default -> SWT.COLOR_DARK_GRAY;
        };
    }

    private void paint(GC gc) {
        Rectangle clip = gc.getClipping();
        gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(clip);
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        gc.drawText("LINKER MEMORY REGIONS — NOT PHYSICAL OR LIVE USAGE", 16, 12, true);
        gc.drawText("Each region uses a separate proportional scale; gaps between address spaces are not drawn.", 16, 34, true);
        gc.drawText("VMA: output-section placement    LMA: explicit load-address placement", 16, 53, true);
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        gc.drawLine(16, HEADER_HEIGHT - 4, WIDTH - 16, HEADER_HEIGHT - 4);
        if (data == null || data.regions().isEmpty()) {
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawText("No linker MEMORY ranges available in this saved report.", 16, HEADER_HEIGHT + 24, true);
            return;
        }
        int first = Math.max(0, (clip.y - HEADER_HEIGHT) / ROW_HEIGHT);
        int last = Math.min(data.regions().size(),
                2 + Math.max(0, clip.y + clip.height - HEADER_HEIGHT) / ROW_HEIGHT);
        for (int i = first; i < last; i++) paintRegion(gc, i);
    }

    private void paintRegion(GC gc, int index) {
        Region region = data.regions().get(index);
        int y = HEADER_HEIGHT + index * ROW_HEIGHT;
        boolean selected = index == selection;
        gc.setBackground(getDisplay().getSystemColor(selected ? SWT.COLOR_LIST_SELECTION :
                index % 2 == 0 ? SWT.COLOR_WIDGET_LIGHT_SHADOW : SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(0, y, WIDTH, ROW_HEIGHT - 2);
        gc.setForeground(getDisplay().getSystemColor(selected ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_WIDGET_FOREGROUND));
        gc.drawText(region.kind().toUpperCase(java.util.Locale.ROOT) + "  " + abbreviate(region.name(), 34), 16, y + 7, true);
        gc.drawText(MemoryProfilerLayout.hex(region.address().start()), 16, y + 30, true);
        gc.drawText("end exclusive " + MemoryProfilerLayout.hex(region.address().end()), 16, y + 48, true);
        gc.drawText(region.size() + " bytes · linker attr: " + abbreviate(region.attributes(), 12), 16, y + 68, true);
        gc.drawText("Kind from linker region name (heuristic)", 16, y + 91, true);

        // Kind stripe is a label; do not present it as confirmed silicon flash/RAM.
        gc.setBackground(getDisplay().getSystemColor(color(region.kind())));
        gc.fillRectangle(TRACK_X, y + 7, TRACK_WIDTH, 17);
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        gc.drawText("0%", TRACK_X, y + 26, true);
        gc.drawText("100%", TRACK_X + TRACK_WIDTH - 36, y + 26, true);
        gc.drawText("VMA", TRACK_X - 37, y + 48, true);
        gc.drawText("LMA", TRACK_X - 37, y + 69, true);
        gc.drawText("MPU", TRACK_X - 37, y + 91, true);
        drawTrack(gc, y + 49);
        drawTrack(gc, y + 70);
        drawTrack(gc, y + 92);
        for (var sec : data.sections()) {
            drawSlice(gc, region.address(), sec.vma(), y + 49, SWT.COLOR_DARK_CYAN);
            if (sec.lma() != null)
                drawSlice(gc, region.address(), sec.lma(), y + 70, SWT.COLOR_DARK_YELLOW);
        }
        if (data.hasMpuSnapshot()) {
            for (var mpu : data.mpu())
                drawSlice(gc, region.address(), mpu.address(), y + 92, SWT.COLOR_DARK_MAGENTA);
            for (Range conflict : data.conflicts(region))
                drawSlice(gc, region.address(), conflict, y + 92, SWT.COLOR_RED);
        } else {
            gc.setForeground(getDisplay().getSystemColor(selected ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_WIDGET_FOREGROUND));
            gc.drawText("Not available from map", TRACK_X + 8, y + 91, true);
        }
    }

    private void drawTrack(GC gc, int y) {
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        gc.drawRectangle(TRACK_X, y, TRACK_WIDTH, 12);
    }

    private void drawSlice(GC gc, Range container, Range item, int y, int color) {
        Range slice = container.intersect(item);
        if (slice == null) return;
        int a = MemoryProfilerLayout.pixel(slice.start().subtract(container.start()), container.length(), TRACK_WIDTH);
        int b = MemoryProfilerLayout.pixel(slice.end().subtract(container.start()), container.length(), TRACK_WIDTH);
        // Always show at least one pixel for a nonzero observed interval.
        int width = Math.min(TRACK_WIDTH - a, Math.max(1, b - a));
        if (width <= 0) return;
        gc.setBackground(getDisplay().getSystemColor(color));
        gc.fillRectangle(TRACK_X + a, y + 1, width, 11);
    }

    private static String abbreviate(String text, int limit) {
        if (text.length() <= limit) return text;
        return text.substring(0, limit - 1) + "…";
    }
}
