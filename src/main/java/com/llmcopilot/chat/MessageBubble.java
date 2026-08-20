package com.llmcopilot.chat;

import javax.swing.*;
import java.awt.*;

/**
 * A chat bubble that re-wraps as the tool window resizes.
 *
 * <p>Swing's usual trouble with wrapped text in a vertical box is circular: the box asks
 * for a preferred size before it has assigned a width, and a text pane asked cold reports
 * the width of its longest unwrapped line. Both sizes here are therefore derived from the
 * width of the enclosing row, and the body is measured at that width before its height is
 * reported - so the height always matches the wrapping actually drawn.
 */
final class MessageBubble extends JPanel {

    private final JComponent body;

    MessageBubble(JComponent body) {
        super(new BorderLayout(0, 2));
        this.body = body;
    }

    /**
     * Width of the nearest ancestor that has been laid out. Walking up matters on the
     * first pass, when the row exists but has not been given a size yet and only the
     * viewport further up knows how wide the tool window is.
     */
    private int availableWidth() {
        for (Container c = getParent(); c != null; c = c.getParent()) {
            int width = c.getWidth();
            if (width <= 0) continue;
            if (c instanceof JComponent jc) {
                Insets in = jc.getInsets();
                width -= in.left + in.right;
            }
            return width;
        }
        return 0;
    }

    int targetWidth() {
        return BubbleMetrics.bubbleWidth(availableWidth());
    }

    @Override
    public Dimension getPreferredSize() {
        int width = targetWidth();
        Insets in = getInsets();
        int contentWidth = Math.max(1, width - in.left - in.right);

        // Lay the text out at the width it will actually be drawn at; the height that
        // comes back then accounts for every wrapped line.
        body.setSize(contentWidth, Short.MAX_VALUE);

        Dimension natural = super.getPreferredSize();
        return new Dimension(width, natural.height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(targetWidth(), Integer.MAX_VALUE);
    }
}
