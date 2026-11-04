// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import io.github.niwantha33.zew.core.DevicetreeMemoryMap;

/**
 * Address-ordered native SWT memory map. Regions have constant row height:
 * map geometry is deliberately NOT proportional to size or silicon coverage.
 * The accompanying table preserves every decoded region and its exact values.
 */
public final class SocMemoryMapCanvas extends Canvas {
    public static final int WIDTH = 980;
    public static final int ROW_HEIGHT = 44;
    private static final int HEADER_HEIGHT = 52;
    private static final int MAX_VISIBLE_ROWS = 500;
    private List<DevicetreeMemoryMap.Region> regions = List.of();
    private Consumer<DevicetreeMemoryMap.Region> listener = ignored -> {};
    private int selectedIndex = -1;

    public SocMemoryMapCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        addPaintListener(event -> paintMap(event.gc));
        addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
            @Override public void mouseDown(org.eclipse.swt.events.MouseEvent event) {
                int index = (event.y - HEADER_HEIGHT) / ROW_HEIGHT;
                if (event.y < HEADER_HEIGHT || index < 0 || index >= visibleRowCount()) return;
                selectedIndex = index;
                redraw();
                listener.accept(regions.get(index));
            }
        });
        setToolTipText("Built Devicetree address order only; diagram rows are NOT proportional to size.");
        resizeMap();
    }

    public void setRegions(List<DevicetreeMemoryMap.Region> entries) {
        regions = entries == null ? List.of() : List.copyOf(entries);
        selectedIndex = -1;
        resizeMap();
        redraw();
    }

    public void onSelection(Consumer<DevicetreeMemoryMap.Region> action) {
        listener = action == null ? ignored -> {} : action;
    }

    public int visibleRowCount() { return Math.min(MAX_VISIBLE_ROWS, regions.size()); }
    public int totalRegionCount() { return regions.size(); }

    public void selectRegion(DevicetreeMemoryMap.Region region) {
        int index = regions.indexOf(region);
        selectedIndex = index >= 0 && index < visibleRowCount() ? index : -1;
        redraw();
    }

    private void resizeMap() {
        if (!isDisposed())
            setSize(WIDTH, HEADER_HEIGHT + Math.max(1, visibleRowCount()) * ROW_HEIGHT + 10);
    }

    private void paintMap(GC gc) {
        Rectangle clip = gc.getClipping();
        var display = getDisplay();
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.fillRectangle(clip);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        gc.drawString("CPU / BUS START", 14, 12, true);
        gc.drawString("TYPE       DEVICETREE NODE / REG-NAME", 225, 12, true);
        gc.drawString("END EXCLUSIVE", 736, 12, true);
        gc.drawString("Addresses ordered; row heights NOT proportional to memory size", 14, 31, true);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        gc.drawLine(14, HEADER_HEIGHT - 2, WIDTH - 14, HEADER_HEIGHT - 2);
        for (int i = 0; i < visibleRowCount(); i++) {
            int y = HEADER_HEIGHT + i * ROW_HEIGHT;
            if (y + ROW_HEIGHT < clip.y || y > clip.y + clip.height) continue;
            var item = regions.get(i);
            boolean selected = i == selectedIndex;
            int x = 215;
            gc.setBackground(display.getSystemColor(selected ? SWT.COLOR_LIST_SELECTION
                    : i % 2 == 0 ? SWT.COLOR_WIDGET_LIGHT_SHADOW
                    : SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(0, y, WIDTH, ROW_HEIGHT - 2);
            gc.setBackground(display.getSystemColor(item.cpuMapped() ? switch (item.kind()) {
                case RAM -> SWT.COLOR_DARK_GREEN;
                case FLASH, PARTITION -> SWT.COLOR_DARK_BLUE;
                case PERIPHERAL -> SWT.COLOR_DARK_CYAN;
                case RESERVED -> SWT.COLOR_DARK_MAGENTA;
                case OTHER -> SWT.COLOR_DARK_GRAY;
            } : SWT.COLOR_DARK_GRAY));
            gc.fillRectangle(0, y, 5, ROW_HEIGHT - 2);
            gc.setForeground(display.getSystemColor(selected
                    ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_WIDGET_FOREGROUND));
            String start = item.cpuMapped() ? item.cpuHex() : "BUS " + item.localHex();
            gc.drawString(start, 14, y + 11, true);
            gc.drawRectangle(x, y + 4, 493, ROW_HEIGHT - 11);
            String title = item.kind().name() + "  " + item.node();
            if (!item.registerName().isBlank()) title += " / " + item.registerName();
            if (title.length() > 59) title = title.substring(0, 56) + "...";
            gc.drawString(title, x + 10, y + 8, true);
            String size = item.sizeLabel()
                    + (item.status().startsWith("disabled") ? " | disabled" : "");
            if (size.length() > 65) size = size.substring(0, 62) + "...";
            gc.drawString(size, x + 10, y + 23, true);
            gc.drawString(item.cpuMapped() ? item.endHex() : "NOT MAPPED", 736, y + 9, true);
        }
        if (regions.isEmpty())
            gc.drawString("No mapped reg ranges found in the current built Devicetree.",
                    14, HEADER_HEIGHT + 13, true);
        else if (regions.size() > MAX_VISIBLE_ROWS)
            gc.drawString("Diagram limited to first " + MAX_VISIBLE_ROWS
                    + " rows. Use the complete table for all " + regions.size() + " entries.",
                    14, HEADER_HEIGHT + MAX_VISIBLE_ROWS * ROW_HEIGHT, true);
    }
}
