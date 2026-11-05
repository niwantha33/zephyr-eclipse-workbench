// SPDX-License-Identifier: Apache-2.0
package io.github.niwantha33.zew.ui;

import java.util.function.Consumer;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

/**
 * Coloured, interactive orientation diagram for the Configuration Studio.
 *
 * Solid arrows represent the relevant Zephyr build inputs. The inspection
 * shortcut to the memory map is dashed. The DTSpec defines nodes, properties,
 * labels, includes and bus address semantics; app.overlay and Kconfig are
 * Zephyr build facilities, not parent/child relationships in the Devicetree.
 *
 * All destinations are real existing Eclipse workbench actions. This canvas
 * does not imply that a described device is wired, driver-ready or running.
 */
public final class ConfigurationFlowCanvas extends Canvas {
    public enum Block {
        SOC("SoC description", "*.dtsi  |  shared chip nodes",
                "Read the chip's inherited Devicetree source. Vendor files stay read-only."),
        BOARD("Board definition", "*.dts  |  chosen board",
                "Inspect the selected board's DTS source. It includes the SoC description."),
        OVERLAY("Your changes", "*.overlay  |  application",
                "Open or create your application's optional overlay, with confirmation."),
        GENERATED("Combined tree", "zephyr.dts  |  build result",
                "Inspect the generated Devicetree from your actual selected build."),
        MEMORY("Memory map", "RAM  |  Flash  |  registers",
                "Draw the address ranges evidenced by the generated Devicetree."),
        BINDING("Device bindings", "*.yaml  |  properties",
                "Select a hardware node and inspect its real Zephyr YAML binding."),
        KCONFIG("Driver options", "prj.conf  |  Kconfig",
                "Inspect separate Zephyr software/driver choices, not a DTS child node."),
        BUILD("Build & verify", "check the real result",
                "Run the selected project's actual Zephyr build and validation.");

        private final String title, subtitle, description;
        Block(String title, String subtitle, String description) {
            this.title = title;
            this.subtitle = subtitle;
            this.description = description;
        }
        public String title() { return title; }
        public String subtitle() { return subtitle; }
        public String description() { return description; }
    }

    private static final int INSET = 19;
    private static final int TOP = 30;
    private static final int SECOND = 211;
    private static final int HEIGHT = 104;
    public static final int DIAGRAM_HEIGHT = 338;

    private final Color[] palette;
    private final Color surface, outline, arrow, white, muted;
    private final Font headlineFont, detailFont;
    private Consumer<Block> onOpen = ignored -> {};
    private Block hover, focus = Block.SOC;

    public ConfigurationFlowCanvas(Composite parent) {
        super(parent, SWT.DOUBLE_BUFFERED | SWT.NO_BACKGROUND);
        var display = getDisplay();
        palette = new Color[] {
                new Color(display, 37, 99, 235),   // SoC: blue
                new Color(display, 13, 148, 136),  // board: teal
                new Color(display, 124, 58, 237),  // application overlay: violet
                new Color(display, 5, 150, 105),   // build-generated DTS: green
                new Color(display, 194, 65, 12),   // memory map: orange
                new Color(display, 180, 83, 9),    // bindings: ochre
                new Color(display, 71, 85, 105),   // separate Kconfig: slate
                new Color(display, 190, 24, 93)    // build: rose
        };
        surface = new Color(display, 247, 249, 253);
        outline = new Color(display, 203, 213, 225);
        arrow = new Color(display, 71, 85, 105);
        muted = new Color(display, 71, 85, 105);
        white = display.getSystemColor(SWT.COLOR_WHITE);
        FontData base = parent.getFont().getFontData()[0];
        headlineFont = new Font(display, new FontData(
                base.getName(), Math.max(base.getHeight() + 1, 10), SWT.BOLD));
        detailFont = new Font(display, new FontData(
                base.getName(), Math.max(base.getHeight() - 1, 8), SWT.NORMAL));

        setToolTipText("Click a coloured block to open its real project tool; "
                + "use arrows to understand the workflow.");
        addPaintListener(event -> paintFlow(event.gc));
        addListener(SWT.MouseMove, event -> {
            Block next = hitTest(event.x, event.y);
            if (hover != next) {
                hover = next;
                setCursor(display.getSystemCursor(next == null
                        ? SWT.CURSOR_ARROW : SWT.CURSOR_HAND));
                setToolTipText(next == null
                        ? "Solid arrows: build inputs. Dashed arrow: inspect memory map."
                        : next.description());
                redraw();
            }
        });
        addListener(SWT.MouseExit, event -> {
            hover = null;
            setCursor(display.getSystemCursor(SWT.CURSOR_ARROW));
            redraw();
        });
        addListener(SWT.MouseDown, event -> {
            if (event.button != 1) return;
            Block chosen = hitTest(event.x, event.y);
            if (chosen != null) {
                focus = chosen;
                setFocus();
                redraw();
                onOpen.accept(chosen);
            }
        });
        addListener(SWT.KeyDown, event -> {
            if (event.keyCode == SWT.ARROW_LEFT || event.keyCode == SWT.ARROW_UP)
                focus = Block.values()[(focus.ordinal() + Block.values().length - 1)
                        % Block.values().length];
            else if (event.keyCode == SWT.ARROW_RIGHT || event.keyCode == SWT.ARROW_DOWN)
                focus = Block.values()[(focus.ordinal() + 1) % Block.values().length];
            else if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR
                    || event.character == ' ') {
                onOpen.accept(focus);
                return;
            } else return;
            redraw();
        });
        addListener(SWT.Dispose, event -> {
            for (Color color : palette) color.dispose();
            surface.dispose();
            outline.dispose();
            arrow.dispose();
            muted.dispose();
            headlineFont.dispose();
            detailFont.dispose();
        });
    }

    public void onOpen(Consumer<Block> action) {
        onOpen = action == null ? ignored -> {} : action;
    }

    public int blockCount() { return Block.values().length; }

    public Block blockAt(int x, int y) { return hitTest(x, y); }

    /** Compute clickable bounds before or after SWT Paint; no paint-event race. */
    public Rectangle boundsOf(Block block) {
        Rectangle r = layoutBoxes()[block.ordinal()];
        return new Rectangle(r.x, r.y, r.width, r.height);
    }

    private Block hitTest(int x, int y) {
        var boxes = layoutBoxes();
        for (Block block : Block.values())
            if (boxes[block.ordinal()].contains(x, y)) return block;
        return null;
    }

    private Rectangle[] layoutBoxes() {
        int width = Math.max(getClientArea().width, 600);
        int gap = Math.max(20, width / 34);
        int boxWidth = Math.max(112, (width - 2 * INSET - 3 * gap) / 4);
        var boxes = new Rectangle[Block.values().length];
        for (int i = 0; i < 4; i++)
            boxes[i] = new Rectangle(INSET + i * (boxWidth + gap),
                    TOP, boxWidth, HEIGHT);
        boxes[Block.BINDING.ordinal()] =
                new Rectangle(INSET, SECOND, boxWidth, HEIGHT);
        boxes[Block.KCONFIG.ordinal()] =
                new Rectangle(INSET + boxWidth + gap, SECOND, boxWidth, HEIGHT);
        boxes[Block.BUILD.ordinal()] =
                new Rectangle(INSET + 2 * (boxWidth + gap), SECOND, boxWidth, HEIGHT);
        boxes[Block.MEMORY.ordinal()] =
                new Rectangle(INSET + 3 * (boxWidth + gap), SECOND, boxWidth, HEIGHT);
        return boxes;
    }

    private void paintFlow(GC gc) {
        int canvasWidth = Math.max(getClientArea().width, 600);
        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);
        gc.setBackground(surface);
        gc.fillRectangle(getClientArea());
        gc.setForeground(outline);
        gc.drawRoundRectangle(1, 1, canvasWidth - 3, DIAGRAM_HEIGHT - 3, 22, 22);

        Rectangle[] boxes = layoutBoxes();
        final int boxWidth = boxes[Block.SOC.ordinal()].width;

        // Draw connectors FIRST so they cannot cover any clickable text.
        gc.setForeground(arrow);
        for (int i = 0; i < 3; i++)
            drawArrow(gc, boxes[i].x + boxWidth + 3, TOP + HEIGHT / 2,
                    boxes[i + 1].x - 6, TOP + HEIGHT / 2, false);
        // Generated DTS participates in the selected build verification.
        // The arrow stays entirely within the gap between the two rows.
        drawArrow(gc, centerX(boxes[Block.GENERATED.ordinal()]), TOP + HEIGHT + 9,
                centerX(boxes[Block.BUILD.ordinal()]), SECOND - 8, false);
        // Kconfig is an independent software/driver configuration input.
        drawArrow(gc, boxes[Block.KCONFIG.ordinal()].x + boxWidth + 3,
                SECOND + HEIGHT / 2,
                boxes[Block.BUILD.ordinal()].x - 6, SECOND + HEIGHT / 2, false);
        // Memory Map merely INSPECTS what the selected build described.
        drawArrow(gc, centerX(boxes[Block.GENERATED.ordinal()]),
                TOP + HEIGHT + 9,
                centerX(boxes[Block.MEMORY.ordinal()]), SECOND - 8, true);
        // The YAML binding card is deliberately independent: compatible
        // resolves it during Zephyr processing. A binding is neither a
        // physical child node nor an included DTS file.

        for (Block b : Block.values()) {
            Rectangle box = boxes[b.ordinal()];
            Color colour = palette[b.ordinal()];
            gc.setBackground(colour);
            gc.fillRoundRectangle(box.x, box.y, box.width, box.height, 19, 19);
            if (hover == b || (isFocusControl() && focus == b)) {
                gc.setLineWidth(3);
                gc.setForeground(outline);
                gc.drawRoundRectangle(box.x - 2, box.y - 2,
                        box.width + 4, box.height + 4, 21, 21);
                gc.setLineWidth(1);
            }
            gc.setForeground(white);
            gc.setFont(headlineFont);
            gc.drawText(fit(gc, b.title(), box.width - 21),
                    box.x + 11, box.y + 22, true);
            gc.setFont(detailFont);
            gc.drawText(fit(gc, b.subtitle(), box.width - 21),
                    box.x + 11, box.y + 51, true);
            gc.drawText("Open  \u2192", box.x + 11, box.y + 79, true);
        }
        gc.setFont(detailFont);
        gc.setForeground(muted);
        gc.drawText("SOLID ARROWS = CONFIGURATION INPUTS", INSET, 10, true);
        gc.drawText("YAML BINDINGS MATCH compatible; THEY ARE NOT DTS CHILD NODES", INSET,
                DIAGRAM_HEIGHT - 18, true);
    }

    private static int centerX(Rectangle r) { return r.x + r.width / 2; }

    private static String fit(GC gc, String input, int available) {
        String text = input;
        while (text.length() > 4 && gc.textExtent(text).x > available)
            text = text.substring(0, text.length() - 2) + "\u2026";
        return text;
    }

    private static void drawArrow(GC gc, int x1, int y1, int x2, int y2,
                                  boolean dashed) {
        gc.setLineStyle(dashed ? SWT.LINE_DASH : SWT.LINE_SOLID);
        gc.setLineWidth(dashed ? 2 : 3);
        gc.drawLine(x1, y1, x2, y2);
        gc.setLineStyle(SWT.LINE_SOLID);
        double theta = Math.atan2(y2 - y1, x2 - x1);
        int length = 9;
        double spread = 0.55;
        int ax = x2 - (int) Math.round(length * Math.cos(theta - spread));
        int ay = y2 - (int) Math.round(length * Math.sin(theta - spread));
        int bx = x2 - (int) Math.round(length * Math.cos(theta + spread));
        int by = y2 - (int) Math.round(length * Math.sin(theta + spread));
        gc.drawLine(x2, y2, ax, ay);
        gc.drawLine(x2, y2, bx, by);
        gc.setLineWidth(1);
    }
}
