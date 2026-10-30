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
    public enum Mode { PACKAGE, SIGNALS, CONNECTIONS }
    private Mode mode = Mode.SIGNALS;
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
        if (mode == Mode.PACKAGE) {
            paintPackageUnavailable(gc, area);
            return;
        }
        if (mode == Mode.CONNECTIONS) {
            paintConnections(gc, area);
            return;
        }
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

    private void paintPackageUnavailable(org.eclipse.swt.graphics.GC gc, Rectangle area) {
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("PACKAGE / PINOUT", 16, 15, true);
        gc.drawText("Physical package pads have NOT been resolved for this target.", 16, 41, true);
        int width = Math.min(360, Math.max(170, area.width - 64));
        int height = Math.min(270, Math.max(160, area.height - 150));
        int left = Math.max(20, (area.width - width) / 2);
        int top = 80;
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        gc.fillRoundRectangle(left, top, width, height, 18, 18);
        gc.drawRoundRectangle(left, top, width, height, 18, 18);
        gc.drawOval(left + 12, top + 12, 10, 10);
        gc.drawText("UNVERIFIED PACKAGE", left + 18, top + 60, true);
        String chip = controller.substring(controller.lastIndexOf('/') + 1);
        gc.drawText(shorten(gc, chip, width - 36), left + 18, top + 90, true);
        gc.drawText("No pad numbers shown", left + 18, top + 120, true);
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("Choose Logical signals for actual built pinctrl / GPIO values.", 16, top + height + 20, true);
        gc.drawText("Supply verified, licence-cleared MCU part and package mapping to enable a pinout.", 16, top + height + 44, true);
        hitAreas.clear();
    }

    private void paintConnections(org.eclipse.swt.graphics.GC gc, Rectangle area) {
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("CONNECTIONS  /  BUILT LOGICAL ROUTES", 14, 12, true);
        gc.drawText("These property cells are not package pads.", 14, 35, true);
        hitAreas.clear();
        for (int i = 0; i < visibleCount(); i++) {
            var route = routes.get(i);
            int y = 64 + i * 28;
            boolean highlighted = i == selected;
            gc.setForeground(display.getSystemColor(highlighted
                    ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_LIST_FOREGROUND));
            gc.setBackground(display.getSystemColor(highlighted
                    ? SWT.COLOR_LIST_SELECTION : SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRectangle(12, y, Math.max(150, area.width - 24), 26);
            gc.drawText(shorten(gc, route.property() + "  " + route.value(),
                    Math.max(80, area.width - 42)), 18, y + 4, true);
            hitAreas.add(new Rectangle(12, y, Math.max(150, area.width - 24), 26));
        }
        if (routes.isEmpty()) {
            gc.drawText("No pinctrl/GPIO connection records in this build for the selected controller.", 14, 77, true);
        }
        if (routes.size() > visibleCount()) {
            gc.drawText("Additional connections: inspect the complete parameters table.",
                    14, 70 + visibleCount() * 28, true);
        }
    }

    public void setMode(Mode requested) {
        if (requested == null || isDisposed()) return;
        mode = requested;
        int count = mode == Mode.CONNECTIONS ? visibleCount()
                : (visibleCount() + 1) / 2;
        int h = mode == Mode.CONNECTIONS ? count * 28 + 105
                : mode == Mode.PACKAGE ? 480 : count * ROUTE_ROW + 185;
        setSize(Math.max(620, getSize().x), Math.max(295, h));
        var parent = getParent();
        if (parent instanceof org.eclipse.swt.custom.ScrolledComposite scroll) {
            scroll.setMinSize(620, Math.max(295, h));
        }
        redraw();
    }

    public Mode mode() { return mode; }

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
        setMode(mode);
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
