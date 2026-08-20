package com.llmcopilot.chat;

import javax.swing.*;
import java.awt.*;

/**
 * Full-width row holding one bubble against the left or right edge.
 *
 * <p>The row rather than the bubble is what the message list stacks. Giving the row the
 * whole width and letting BorderLayout pin the bubble to an edge keeps the two alignments
 * independent - mixing {@code alignmentX} values inside a single vertical {@code BoxLayout}
 * makes it reserve space for both edges at once, which is what pushed bubbles off-centre.
 *
 * <p>Both sizes are computed on demand rather than frozen at construction, so a resize of
 * the tool window re-flows the text instead of clipping it.
 */
final class BubbleRow extends JPanel {

    private final MessageBubble bubble;

    BubbleRow(MessageBubble bubble, boolean rightAligned) {
        super(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        this.bubble = bubble;
        add(bubble, rightAligned ? BorderLayout.EAST : BorderLayout.WEST);
    }

    @Override
    public Dimension getPreferredSize() {
        int height = bubble.getPreferredSize().height;
        return new Dimension(Math.max(getWidth(), bubble.targetWidth()), height);
    }

    /** Full width, but only as tall as the bubble - otherwise BoxLayout stretches the row. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
