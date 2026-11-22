// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/**
 * Native SWT class-box diagram. Geometry is deterministic and uses no remote
 * renderer. No edge is drawn unless a future CDT-backed relation is proved.
 */
public final class UmlClassCanvas extends Canvas {
    private static final int COL_WIDTH = 345;
    private static final int GAP = 24;
    private static final int TOP = 18;
    private static final int LINE = 20;
    private static final int COLUMNS = 2;
    private static final int MIN_HEIGHT = 250;

    private UmlClassModel.Snapshot snapshot = UmlClassModel.unavailable("", "No source selected.");
    private List<Card> cards = List.of();
    private Consumer<UmlClassModel.Type> onOpen = type -> {};
    private Consumer<UmlProjectGraph.Node> onOpenFunction = fn -> {};
    private UmlProjectGraph.Snapshot projectGraph = UmlProjectGraph.unavailable("", "");
    private List<FunctionCard> functionCards = List.of();

    private record Card(UmlClassModel.Type type, Rectangle bounds) {}
    private record FunctionCard(UmlProjectGraph.Node node, Rectangle bounds) {}

    public UmlClassCanvas(Composite parent, int style) {
        super(parent, style | SWT.DOUBLE_BUFFERED);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        addPaintListener(event -> paint(event.gc));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDoubleClick(MouseEvent e) {
                for (FunctionCard card : functionCards) {
                    if (card.bounds().contains(e.x, e.y)) {
                        onOpenFunction.accept(card.node());
                        return;
                    }
                }
                for (Card card : cards) {
                    if (card.bounds().contains(e.x, e.y)) {
                        onOpen.accept(card.type());
                        return;
                    }
                }
            }
        });
        show(snapshot);
    }

    public void onOpen(Consumer<UmlClassModel.Type> consumer) {
        onOpen = consumer == null ? type -> {} : consumer;
    }

    public void onOpenFunction(Consumer<UmlProjectGraph.Node> consumer) {
        onOpenFunction = consumer == null ? fn -> {} : consumer;
    }

    public int cardCount() {
        return cards.size() + functionCards.size();
    }

    public void show(UmlClassModel.Snapshot next) {
        functionCards = List.of();
        projectGraph = UmlProjectGraph.unavailable("", "");
        snapshot = next == null ? UmlClassModel.unavailable("", "No source selected.") : next;
        List<Card> nextCards = new ArrayList<>();
        int y = TOP;
        List<UmlClassModel.Type> types = snapshot.types();
        for (int start = 0; start < types.size(); start += COLUMNS) {
            int maxHeight = 0;
            for (int col = 0; col < COLUMNS && start + col < types.size(); col++)
                maxHeight = Math.max(maxHeight, cardHeight(types.get(start + col)));
            for (int col = 0; col < COLUMNS && start + col < types.size(); col++) {
                UmlClassModel.Type type = types.get(start + col);
                nextCards.add(new Card(type,
                        new Rectangle(18 + col * (COL_WIDTH + GAP), y,
                                COL_WIDTH, cardHeight(type))));
            }
            y += maxHeight + GAP;
        }
        cards = List.copyOf(nextCards);
        setSize(18 + COLUMNS * (COL_WIDTH + GAP), Math.max(MIN_HEIGHT, y + TOP));
        redraw();
    }

    /** Renders exact direct-call edges; graph cards navigate to their source definitions. */
    public void showProject(UmlProjectGraph.Snapshot graph) {
        cards = List.of();
        projectGraph = graph == null ? UmlProjectGraph.unavailable("", "") : graph;
        List<FunctionCard> next = new ArrayList<>();
        final int width = 244, height = 80, gapX = 96, gapY = 32, columns = 3;
        for (int i = 0; i < projectGraph.nodes().size(); i++) {
            int col = i % columns, row = i / columns;
            next.add(new FunctionCard(projectGraph.nodes().get(i),
                    new Rectangle(24 + col * (width + gapX),
                            24 + row * (height + gapY), width, height)));
        }
        functionCards = List.copyOf(next);
        setSize(24 + columns * (width + gapX),
                Math.max(MIN_HEIGHT, 45 + ((next.size() + columns - 1) / columns)
                        * (height + gapY)));
        redraw();
    }

    private static int cardHeight(UmlClassModel.Type type) {
        int sections = type.operations().isEmpty() ? 0 : 16;
        return 52 + type.attributes().size() * LINE + sections
                + type.operations().size() * LINE + 16;
    }

    private void paint(GC gc) {
        if (!functionCards.isEmpty()) {
            drawProject(gc);
            return;
        }
        if (cards.isEmpty()) {
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            gc.drawText("Select a saved .c/.h/.cpp/.hpp file to generate a diagram from CDT.",
                    22, 30, true);
            return;
        }
        for (Card card : cards) drawCard(gc, card);
    }

    private void drawProject(GC gc) {
        Map<String, Rectangle> positions = new HashMap<>();
        for (FunctionCard card : functionCards)
            positions.put(card.node().id(), card.bounds());
        gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE));
        gc.setLineWidth(2);
        for (UmlProjectGraph.Edge edge : projectGraph.edges()) {
            Rectangle sourceRect = positions.get(edge.from());
            Rectangle targetRect = positions.get(edge.to());
            if (sourceRect == null || targetRect == null) continue;
            boolean toRight = targetRect.x > sourceRect.x;
            int sx = toRight ? sourceRect.x + sourceRect.width : sourceRect.x;
            int tx = toRight ? targetRect.x : targetRect.x + targetRect.width;
            int sy = sourceRect.y + sourceRect.height / 2;
            int ty = targetRect.y + targetRect.height / 2;
            if (sourceRect.x == targetRect.x) {
                sx = sourceRect.x + sourceRect.width / 2;
                tx = targetRect.x + targetRect.width / 2;
                sy = sourceRect.y + sourceRect.height;
                ty = targetRect.y;
            }
            gc.drawLine(sx, sy, tx, ty);
            int arrow = 6;
            if (sourceRect.x == targetRect.x) {
                int dir = ty >= sy ? 1 : -1;
                gc.drawLine(tx, ty, tx - arrow, ty - dir * arrow);
                gc.drawLine(tx, ty, tx + arrow, ty - dir * arrow);
            } else {
                int dir = toRight ? -1 : 1;
                gc.drawLine(tx, ty, tx + dir * arrow, ty - arrow);
                gc.drawLine(tx, ty, tx + dir * arrow, ty + arrow);
            }
        }
        gc.setLineWidth(1);
        for (FunctionCard card : functionCards) {
            Rectangle rect = card.bounds();
            gc.setBackground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_BLUE));
            gc.fillRoundRectangle(rect.x, rect.y, rect.width, rect.height, 10, 10);
            gc.drawRoundRectangle(rect.x, rect.y, rect.width, rect.height, 10, 10);
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            drawFitted(gc, card.node().label(), rect.x + 12, rect.y + 13, rect.width - 24);
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            drawFitted(gc, card.node().file(), rect.x + 12, rect.y + 44, rect.width - 24);
        }
    }

    private void drawCard(GC gc, Card card) {
        Rectangle rect = card.bounds();
        var display = getDisplay();
        gc.setBackground(display.getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        gc.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
        gc.fillRoundRectangle(rect.x, rect.y, rect.width, rect.height, 9, 9);
        gc.drawRoundRectangle(rect.x, rect.y, rect.width, rect.height, 9, 9);
        gc.setBackground(display.getSystemColor(SWT.COLOR_TITLE_BACKGROUND));
        gc.fillRectangle(rect.x + 1, rect.y + 1, rect.width - 2, 39);
        gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        drawFitted(gc, "<<"+card.type().kind()+">>  "+card.type().name(),
                rect.x + 12, rect.y + 12, rect.width - 24);
        int y = rect.y + 50;
        for (String field : card.type().attributes()) {
            drawFitted(gc, "- " + field, rect.x + 12, y, rect.width - 24);
            y += LINE;
        }
        if (!card.type().operations().isEmpty()) {
            gc.setForeground(display.getSystemColor(SWT.COLOR_GRAY));
            gc.drawLine(rect.x + 8, y + 3, rect.x + rect.width - 8, y + 3);
            gc.setForeground(display.getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
            y += 16;
            for (String operation : card.type().operations()) {
                drawFitted(gc, "+ " + operation, rect.x + 12, y, rect.width - 24);
                y += LINE;
            }
        }
    }

    private static void drawFitted(GC gc, String value, int x, int y, int maxWidth) {
        String shown = value;
        if (gc.textExtent(shown).x > maxWidth) {
            while (!shown.isEmpty() && gc.textExtent(shown + "…").x > maxWidth)
                shown = shown.substring(0, shown.length() - 1);
            shown += "…";
        }
        gc.drawText(shown, x, y, true);
    }
}
