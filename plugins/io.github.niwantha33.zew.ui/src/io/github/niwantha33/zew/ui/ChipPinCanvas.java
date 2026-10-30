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
import io.github.niwantha33.zew.core.GpioBankEvidence;
import io.github.niwantha33.zew.core.GpioSourceEvidence;

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
    private GpioBankEvidence.Bank bank;
    private List<GpioSourceEvidence.Call> resolvedSourceCalls = List.of();
    private final List<Rectangle> bankHitAreas = new ArrayList<>();
    private final List<Integer> bankHitBits = new ArrayList<>();
    private Consumer<Integer> bankSelection = bit -> {};
    private int selectedBit = -1;

    public ChipPinCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.BORDER);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        // Keep the canvas free of a long hover tooltip: the page already
        // explains the evidence boundary and the tooltip obscures the chip.
        getAccessible().addAccessibleListener(new org.eclipse.swt.accessibility.AccessibleAdapter() {
            @Override public void getName(org.eclipse.swt.accessibility.AccessibleEvent event) {
                event.result = bank == null
                        ? "Logical pin planner for " + controller + ". " + routes.size()
                                + " routes. Package pads unverified."
                        : "GPIO bank for " + controller + ". " + bank.bitPositions()
                                + " register bit positions, " + bank.uses().size()
                                + " observed build references. Not physical pads.";
            }
        });
        addPaintListener(event -> paintRoutes(event.gc));
        addListener(SWT.MouseDown, event -> {
            if (bank != null && mode != Mode.PACKAGE) {
                for (int i = 0; i < bankHitAreas.size(); i++) {
                    if (bankHitAreas.get(i).contains(event.x, event.y)) {
                        selectedBit = bankHitBits.get(i);
                        redraw();
                        bankSelection.accept(selectedBit);
                        setFocus();
                        return;
                    }
                }
            }
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
        bankHitAreas.clear();
        bankHitBits.clear();
        if ("Select a peripheral".equals(controller)) {
            hitAreas.clear();
            gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
            gc.drawText("SELECT A PERIPHERAL", 24, 42, true);
            gc.drawText("Choose ADC, GPIO, UART, SPI or another controller from the explorer.", 24, 76, true);
            gc.drawText("Only actual last-build values will appear here.", 24, 105, true);
            return;
        }
        if (mode == Mode.PACKAGE) {
            paintPackageUnavailable(gc, area);
            return;
        }
        if (bank != null) {
            if (mode == Mode.CONNECTIONS) paintBankConnections(gc, area);
            else paintBank(gc, area);
            return;
        }
        if (mode == Mode.CONNECTIONS) {
            paintConnections(gc, area);
            return;
        }
        gc.drawText("LOGICAL SIGNAL PLANNER", 16, 12, true);
        gc.drawText("Compiled logical routes  |  Physical pads unverified", 16, 32, true);

        int count = visibleCount();
        int rows = (count + 1) / 2;
        int center = area.width / 2;
        int chipWidth = 190;
        int left = center - chipWidth / 2;
        int top = 64;
        int chipHeight = Math.max(144, rows * ROUTE_ROW + 48);
        int sideWidth = Math.max(105, Math.min(185, (area.width - chipWidth) / 2 - 32));
        gc.setBackground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.fillRoundRectangle(left, top, chipWidth, chipHeight, 12, 12);
        gc.drawRoundRectangle(left, top, chipWidth, chipHeight, 12, 12);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
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
        int footer = top + chipHeight + 9;
        if (routes.isEmpty()) {
            gc.drawText(shorten(gc, "No direct pinctrl/GPIO routes; inspect the built properties.",
                    Math.max(80, area.width - 30)), 16, footer, true);
        } else {
            gc.drawText("Select a route to inspect its exact built property and source.", 16, footer, true);
            if (routes.size() > count)
                gc.drawText("Showing first " + count + " of " + routes.size()
                        + " routes. Full set: parameters table.", 16, footer + 23, true);
        }
    }

    /** Render logical GPIO register indexes; no unverified pad numbers. */
    private void paintBank(org.eclipse.swt.graphics.GC gc, Rectangle area) {
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("GPIO REGISTER BANK  /  LOGICAL INDEXES", 16, 12, true);
        gc.drawText("DTS references: tinted. C-call candidates: marked C. Grey: unknown.", 16, 36, true);
        if (bank.bitPositions() < 1) {
            gc.drawText("GPIO width not recorded in the selected build.", 16, 79, true);
            gc.drawText("Inspect consumers in Connections; no pin range is invented.", 16, 105, true);
            return;
        }
        int columns = 8;
        int gap = 6;
        int width = Math.max(43, Math.min(75, (area.width - 34 - gap * (columns - 1)) / columns));
        int startX = 16;
        int startY = 67;
        for (int bit = 0; bit < bank.bitPositions(); bit++) {
            int x = startX + (bit % columns) * (width + gap);
            int y = startY + (bit / columns) * 34;
            int count = 0;
            for (var usage : bank.uses()) if (usage.bit() == bit) count++;
            boolean selectedSlot = selectedBit == bit;
            boolean fromCode = false;
            for (var call : resolvedSourceCalls) {
                if (call.bit() == bit) {
                    fromCode = true;
                    break;
                }
            }
            gc.setBackground(display.getSystemColor(selectedSlot ? SWT.COLOR_LIST_SELECTION
                    : count > 0 ? SWT.COLOR_INFO_BACKGROUND : SWT.COLOR_WIDGET_BACKGROUND));
            gc.setForeground(display.getSystemColor(selectedSlot ? SWT.COLOR_LIST_SELECTION_TEXT
                    : SWT.COLOR_WIDGET_FOREGROUND));
            gc.fillRoundRectangle(x, y, width, 27, 5, 5);
            gc.drawRoundRectangle(x, y, width, 27, 5, 5);
            gc.drawText(bank.bitLabel(bit) + (fromCode ? " C" : ""), x + 6, y + 5, true);
            bankHitAreas.add(new Rectangle(x, y, width, 27));
            bankHitBits.add(bit);
        }
        int bottom = startY + ((bank.bitPositions() + columns - 1) / columns) * 34 + 6;
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText(bank.uses().size() + " built DTS references · "
                + resolvedSourceCalls.size() + " C source candidates (not executed).", 16, bottom, true);
        gc.drawText("Neither source calls nor DTS prove live direction or bonded pins.", 16, bottom + 24, true);
    }

    private void paintBankConnections(org.eclipse.swt.graphics.GC gc, Rectangle area) {
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("GPIO REFERENCES  /  DTS + C SOURCE", 16, 12, true);
        gc.drawText("Select a reference to inspect the exact consumer and its source line.", 16, 34, true);
        if (bank.uses().isEmpty()) {
            gc.drawText("No resolvable GPIO consumers for this controller in the build.", 16, 72, true);
            gc.drawText(bank.resolvedPhandle() ? "This does not mean the GPIO bank is unused at runtime."
                    : "A numeric controller phandle is missing; usages cannot be resolved.", 16, 98, true);
        }
        int row = 0;
        for (var usage : bank.uses()) {
            if (row >= 40) break;
            int y = 68 + row * 30;
            boolean isSelected = selectedBit == usage.bit();
            gc.setBackground(display.getSystemColor(isSelected ? SWT.COLOR_LIST_SELECTION
                    : SWT.COLOR_WIDGET_BACKGROUND));
            gc.setForeground(display.getSystemColor(isSelected ? SWT.COLOR_LIST_SELECTION_TEXT
                    : SWT.COLOR_WIDGET_FOREGROUND));
            gc.fillRectangle(16, y, Math.max(150, area.width - 32), 26);
            String description = bank.bitLabel(usage.bit()) + "  ←  " + usage.consumerPath()
                    + "  [" + usage.property() + "]";
            gc.drawText(shorten(gc, description, Math.max(90, area.width - 50)), 22, y + 5, true);
            bankHitAreas.add(new Rectangle(16, y, Math.max(150, area.width - 32), 26));
            bankHitBits.add(usage.bit());
            row++;
        }
        if (bank.uses().size() > 40)
            gc.drawText("Additional references in the evidence dock.", 16, 75 + row * 30, true);
        for (var call : resolvedSourceCalls) {
            if (row >= 40) break;
            int y = 68 + row * 30;
            gc.setBackground(display.getSystemColor(selectedBit == call.bit()
                    ? SWT.COLOR_LIST_SELECTION : SWT.COLOR_WIDGET_BACKGROUND));
            gc.setForeground(display.getSystemColor(selectedBit == call.bit()
                    ? SWT.COLOR_LIST_SELECTION_TEXT : SWT.COLOR_WIDGET_FOREGROUND));
            gc.fillRectangle(16, y, Math.max(150, area.width - 32), 26);
            String summary = bank.bitLabel(call.bit()) + "  C candidate:  "
                    + call.sourceFile() + ":" + call.line() + "  [" + call.flags() + "]";
            gc.drawText(shorten(gc, summary, Math.max(90, area.width - 50)), 22, y + 5, true);
            bankHitAreas.add(new Rectangle(16, y, Math.max(150, area.width - 32), 26));
            bankHitBits.add(call.bit());
            row++;
        }
    }

    private void paintPackageUnavailable(org.eclipse.swt.graphics.GC gc, Rectangle area) {
        var display = getDisplay();
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText("PACKAGE / PINOUT", 16, 15, true);
        gc.drawText("Exact part and package mapping not verified", 16, 41, true);
        int width = Math.min(360, Math.max(170, area.width - 64));
        int height = Math.min(270, Math.max(160, area.height - 150));
        int left = Math.max(20, (area.width - width) / 2);
        int top = 80;
        gc.setBackground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        gc.setForeground(display.getSystemColor(SWT.COLOR_BLACK));
        gc.fillRoundRectangle(left, top, width, height, 18, 18);
        gc.drawRoundRectangle(left, top, width, height, 18, 18);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WHITE));
        gc.drawOval(left + 12, top + 12, 10, 10);
        gc.drawText("UNVERIFIED PACKAGE", left + 18, top + 60, true);
        String chip = controller.substring(controller.lastIndexOf('/') + 1);
        gc.drawText(shorten(gc, chip, width - 36), left + 18, top + 90, true);
        gc.drawText("No pad numbers shown", left + 18, top + 120, true);
        gc.setForeground(display.getSystemColor(SWT.COLOR_LIST_FOREGROUND));
        gc.drawText(shorten(gc, "Logical signals shows built pinctrl/GPIO values.",
                Math.max(80, area.width - 24)), 16, top + height + 20, true);
        gc.drawText(shorten(gc, "Physical pad numbering requires verified part and package metadata.",
                Math.max(80, area.width - 24)), 16, top + height + 44, true);
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
        int signalHeight = Math.max(144, count * ROUTE_ROW + 48) + 94
                + (routes.size() > MAX_VISIBLE_ROUTES ? 24 : 0);
        int h = mode == Mode.PACKAGE ? 480
                : bank != null ? mode == Mode.CONNECTIONS
                    ? Math.max(190, Math.min(40, bank.uses().size() + resolvedSourceCalls.size()) * 30 + 112)
                    : Math.max(220, 67 + ((bank.bitPositions() + 7) / 8) * 34 + 67)
                : mode == Mode.CONNECTIONS ? Math.max(190, count * 28 + 105)
                : "Select a peripheral".equals(controller) ? 185 : Math.max(238, signalHeight);
        // Expanding into a larger pane must not lock in a stale wide/tall canvas.
        setSize(540, h);
        var parent = getParent();
        if (parent instanceof org.eclipse.swt.custom.ScrolledComposite scroll) {
            scroll.setMinSize(540, h);
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
        bank = null;
        resolvedSourceCalls = List.of();
        selectedBit = -1;
        controller = path;
        routes = List.copyOf(newRoutes);
        selection = onSelection;
        selected = -1;
        setMode(mode);
    }

    /** Read-only GPIO bank visualization, independent of pinctrl routing. */
    public void showGpioBank(GpioBankEvidence.Bank evidence, Consumer<Integer> onSelection) {
        if (evidence == null) return;
        bank = evidence;
        resolvedSourceCalls = List.of();
        bankSelection = onSelection == null ? bit -> {} : onSelection;
        controller = evidence.controllerPath();
        routes = List.of();
        selected = -1;
        selectedBit = -1;
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

    /** Only exact controller-name matches are marked as C candidates. */
    public void showSourceCandidates(List<GpioSourceEvidence.Call> calls) {
        if (bank == null) return;
        resolvedSourceCalls = calls == null ? List.of() : calls.stream()
                .filter(call -> bank.controllerPath().equals(call.controllerPath()))
                .filter(call -> call.bit() < bank.bitPositions())
                .toList();
        setMode(mode);
    }
    public int sourceCandidateCount() { return resolvedSourceCalls.size(); }

    public int routeCount() { return routes.size(); }
    /** Read-only evidence counts used by installed SWT integration tests. */
    public int gpioBitPositions() { return bank == null ? 0 : bank.bitPositions(); }
    public int gpioConsumerCount() { return bank == null ? 0 : bank.uses().size(); }
}
