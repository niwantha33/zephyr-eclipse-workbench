// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import io.github.niwantha33.zew.core.DevicetreeParameters.PinRoute;

/**
 * Read-only logical pin-route planner. Values come exclusively from generated
 * zephyr.dts. Positions are schematic: no pad numbers or alternate functions
 * may be inferred without verified exact part/package metadata.
 */
public final class ChipPinCanvas extends Canvas {
    /** Prevent a corrupt or unusually large DTS from creating an enormous canvas. */
    public static final int MAX_VISIBLE_ROUTES = 48;
    private static final int ROUTE_ROW = 36;
    private List<PinRoute> routes = List.of();
    private final List<Rectangle> hitAreas = new ArrayList<>();
    private String controller = "Select a peripheral";
    private Consumer<PinRoute> selection = route -> {};
    private int selected = -1;

    public ChipPinCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        setToolTipText("Logical routes from built zephyr.dts. These are NOT physical package pads.");
        getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = "Logical pin planner for " + controller + ". "
                        + routes.size() + " routes; package pads unverified. "
                        + "All route values remain available in the parameters table.";
            }
        });
        addPaintListener(event -> paintRoutes(event.gc));
        addListener(SWT.MouseDown, event -> {
            for (int i = 0; i < hitAreas.size(); i++) {
                if (hitAreas.get(i).contains(event.x, event.y)) {
                    setSelected(i);
                    setFocus();
                    return;
                }
            }
        });
        addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ARROW_DOWN || event.keyCode == SWT.ARROW_RIGHT)
                setSelected(Math.min(visibleCount() - 1, selected + 1));
            else if (event.keyCode == SWT.ARROW_UP || event.keyCode == SWT.ARROW_LEFT)
                setSelected(Math.max(0, selected - 1));
        });
    }

    private int visibleCount() {
        return Math.min(routes.size(), MAX_VISIBLE_ROUTES);
    }

    private void setSelected(int index) {
        if (index < 0 || index >= visibleCount()) return;
        selected = index;
        redraw();
        selection.accept(routes.get(index));
    }

    private void paintRoutes(org.eclipse.swt.graphics.GC gc) {
        Rectangle area = getClientArea();
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("LOGICAL SIGNAL PLANNER", 16, 12, true);
        gc.drawText("Built pinctrl/GPIO values   |   Package pads not verified", 16, 32, true);

        int count = visibleCount();
        int rows = (count + 1) / 2;
        int center = area.width / 2;
        int chipWidth = 190;
        int left = center - chipWidth / 2;
        int top = 64;
        int chipHeight = Math.max(166, rows * ROUTE_ROW + 48);
        int sideWidth = Math.max(105, Math.min(185, (area.width - chipWidth) / 2 - 32));
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        gc.fillRoundRectangle(left, top, chipWidth, chipHeight, 12, 12);
        gc.drawRoundRectangle(left, top, chipWidth, chipHeight, 12, 12);
        gc.drawOval(left + 10, top + 10, 8, 8);
        String name = controller.substring(controller.lastIndexOf('/') + 1);
        if (name.isBlank()) name = "SoC";
        gc.drawText("CONTROLLER", left + 14, top + 45, true);
        gc.drawText(shorten(gc, name, chipWidth - 24), left + 12, top + 71, true);
        gc.drawText(routes.size() + " logical routes", left + 12, top + 99, true);
        gc.drawText("No package map", left + 12, top + 123, true);

        hitAreas.clear();
        for (int i = 0; i < count; i++) {
            PinRoute route = routes.get(i);
            boolean right = (i & 1) != 0;
            int y = top + 18 + (i / 2) * ROUTE_ROW;
            int x = right ? left + chipWidth + 15 : left - sideWidth - 15;
            boolean highlighted = i == selected;
            gc.setBackground(display.getSystemColor(highlighted
                    ? SWT.COLOR_LIST_SELECTION : SWT.COLOR_INFO_BACKGROUND));
            gc.setForeground(display.getSystemColor(highlighted
                    ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_INFO_FOREGROUND));
            gc.fillRoundRectangle(x, y, sideWidth, 28, 6, 6);
            gc.drawRoundRectangle(x, y, sideWidth, 28, 6, 6);
            int startX = right ? left + chipWidth : x + sideWidth;
            int endX = right ? x : left;
            gc.drawLine(startX, y + 14, endX, y + 14);
            String routeText = route.property() + " " + route.value();
            gc.drawText(shorten(gc, routeText, sideWidth - 12), x + 6, y + 6, true);
            hitAreas.add(new Rectangle(x, y, sideWidth, 28));
        }
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        int footer = top + chipHeight + 14;
        gc.drawText("Select a route to inspect its exact built property and source.", 16, footer, true);
        if (routes.size() > count)
            gc.drawText("Showing first " + count + " of " + routes.size()
                    + " routes. Use the parameters table for the complete set.", 16, footer + 23, true);
        else if (routes.isEmpty())
            gc.drawText("No logical routes found for this controller in the last build.", 16, footer + 23, true);
    }

    private static String shorten(org.eclipse.swt.graphics.GC gc, String text, int width) {
        if (gc.textExtent(text).x <= width) return text;
        StringBuilder result = new StringBuilder(text);
        while (result.length() > 1 && gc.textExtent(result + "…").x > width)
            result.setLength(result.length() - 1);
        return result + "…";
    }

    public void showRoutes(String path, List<PinRoute> newRoutes, Consumer<PinRoute> onSelection) {
        controller = path;
        routes = List.copyOf(newRoutes);
        selection = onSelection;
        selected = -1;
        setSize(Math.max(620, getSize().x),
                Math.max(295, ((visibleCount() + 1) / 2) * ROUTE_ROW + 185));
        redraw();
    }

    /** Two-way synchronisation with the parameter table, without fabricating pins. */
    public void selectRoute(String path, String property) {
        int found = -1;
        for (int i = 0; i < visibleCount(); i++) {
            PinRoute route = routes.get(i);
            if (route.path().equals(path) && route.property().equals(property)) {
                found = i;
                break;
            }
        }
        if (selected != found) {
            selected = found;
            redraw();
        }
    }

    public int routeCount() { return routes.size(); }
}
