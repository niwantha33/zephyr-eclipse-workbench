// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.List;
import java.util.ArrayList;
import java.util.function.Consumer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import io.github.niwantha33.zew.core.DevicetreeParameters.PinRoute;

/** Logical chip schematic. Physical pad numbers require package-specific metadata. */
public final class ChipPinCanvas extends Canvas {
    private List<PinRoute> routes = List.of();
    private final List<Rectangle> hitAreas = new ArrayList<>();
    private String controller = "Select a peripheral";
    private Consumer<PinRoute> selection = route -> {};
    private int selected = -1;

    public ChipPinCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        setToolTipText("Logical pin assignments from the last build. Positions are schematic, not physical package pads.");
        getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = "Chip schematic, logical pin assignments for " + controller
                        + ". The same routes are available in the parameter table.";
            }
        });
        addPaintListener(event -> {
            var gc = event.gc;
            var area = getClientArea();
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            gc.drawText("Logical pin assignments · physical pads not resolved", 12, 10, true);
            int center = area.width / 2;
            int chipWidth = Math.max(100, Math.min(210, area.width / 3));
            int left = center - chipWidth / 2;
            int chipHeight = Math.max(145, ((routes.size() + 1) / 2) * 35 + 30);
            gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.fillRoundRectangle(left, 50, chipWidth, chipHeight, 14, 14);
            gc.drawRoundRectangle(left, 50, chipWidth, chipHeight, 14, 14);
            gc.drawOval(left + 10, 60, 7, 7);
            String name = controller.substring(controller.lastIndexOf('/') + 1);
            if (name.isBlank()) name = "SoC";
            gc.drawText("SoC / controller", left + 12, 90, true);
            gc.drawText(name.length() > 22 ? name.substring(0, 21) + "…" : name, left + 12, 113, true);
            gc.drawText(routes.size() + " reported routes", left + 12, 139, true);
            hitAreas.clear();
            for (int i = 0; i < routes.size(); i++) {
                var route = routes.get(i);
                boolean right = i % 2 != 0;
                int y = 66 + i / 2 * 35;
                int width = Math.max(30, Math.min(190, (area.width - chipWidth) / 2 - 24));
                int x = right ? left + chipWidth + 14 : left - width - 14;
                gc.setBackground(getDisplay().getSystemColor(i == selected ? SWT.COLOR_LIST_SELECTION : SWT.COLOR_INFO_BACKGROUND));
                gc.setForeground(getDisplay().getSystemColor(i == selected ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_LIST_FOREGROUND));
                gc.fillRoundRectangle(x, y, width, 27, 6, 6);
                gc.drawRoundRectangle(x, y, width, 27, 6, 6);
                gc.drawLine(right ? left + chipWidth : x + width, y + 13, right ? x : left, y + 13);
                String label = route.property() + " " + route.value();
                while (label.length() > 2 && gc.textExtent(label).x > width - 10) label = label.substring(0, label.length() - 2) + "…";
                gc.drawText(label, x + 5, y + 5, true);
                hitAreas.add(new Rectangle(x, y, width, 27));
            }
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            if (routes.isEmpty()) {
                gc.drawText("No pinctrl/GPIO route is reported for this selection.", 12, chipHeight + 72, true);
                gc.drawText("Build the exact board and inspect its overlay/pinctrl state.", 12, chipHeight + 95, true);
            }
        });
        addListener(SWT.MouseDown, event -> {
            for (int i = 0; i < hitAreas.size(); i++) if (hitAreas.get(i).contains(event.x, event.y)) {
                selected = i; redraw(); selection.accept(routes.get(i)); break;
            }
        });
    }
    public void showRoutes(String path, List<PinRoute> routes, Consumer<PinRoute> selection) {
        this.controller = path;
        this.routes = List.copyOf(routes);
        this.selection = selection;
        selected = -1;
        setSize(Math.max(560, getSize().x), Math.max(295, ((routes.size() + 1) / 2) * 35 + 110));
        redraw();
    }
    public int routeCount() { return routes.size(); }
}
