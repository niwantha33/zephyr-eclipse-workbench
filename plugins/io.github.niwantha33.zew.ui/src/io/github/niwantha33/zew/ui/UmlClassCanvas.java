// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.ArrayList;
import java.util.List;
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

    private record Card(UmlClassModel.Type type, Rectangle bounds) {}

    public UmlClassCanvas(Composite parent, int style) {
        super(parent, style | SWT.DOUBLE_BUFFERED);
        setBackground(getDisplay().getSystemColor(SWT.COLOR_LIST_BACKGROUND));
        addPaintListener(event -> paint(event.gc));
        addMouseListener(new MouseAdapter() {
            @Override public void mouseDoubleClick(MouseEvent e) {
                for (Card card : cards) {
                    if (card.bounds().contains(e.x, e.y)) {
                        onOpen.accept(card.type());
                        break;
                    }
                }
            }
        });
        show(snapshot);
    }

    public void onOpen(Consumer<UmlClassModel.Type> consumer) {
        onOpen = consumer == null ? type -> {} : consumer;
    }

    public int cardCount() {
        return cards.size();
    }

    public void show(UmlClassModel.Snapshot next) {
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

    private static int cardHeight(UmlClassModel.Type type) {
        int sections = type.operations().isEmpty() ? 0 : 16;
        return 52 + type.attributes().size() * LINE + sections
                + type.operations().size() * LINE + 16;
    }

    private void paint(GC gc) {
        if (cards.isEmpty()) {
            gc.setForeground(getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY));
            gc.drawText("Select a saved .c/.h/.cpp/.hpp file to generate a diagram from CDT.",
                    22, 30, true);
            return;
        }
        for (Card card : cards) drawCard(gc, card);
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
