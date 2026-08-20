package com.llmcopilot.chat;

/**
 * Width arithmetic for chat bubbles.
 *
 * <p>Separated from the Swing components so the sizing rules can be tested: a bubble
 * takes a share of whatever width the tool window currently has, floored so it stays
 * readable when docked narrow and capped so lines do not run too long when docked wide.
 */
final class BubbleMetrics {

    /** Share of the available width a bubble may occupy. */
    static final double WIDTH_FRACTION = 0.86;
    /** Below this the bubble stops shrinking and the tool window scrolls instead. */
    static final int    MIN_WIDTH      = 140;
    /** Beyond this lines get uncomfortably long to read. */
    static final int    MAX_WIDTH      = 700;

    private BubbleMetrics() {}

    /**
     * The width a bubble should render at inside {@code availableWidth}.
     *
     * <p>A non-positive available width means layout has not run yet; the floor is
     * returned so the first paint is sane rather than zero-width.
     */
    static int bubbleWidth(int availableWidth, double fraction, int minWidth, int maxWidth) {
        if (availableWidth <= 0) return minWidth;

        int target = (int) Math.round(availableWidth * fraction);
        target = Math.min(target, maxWidth);
        target = Math.max(target, minWidth);

        // Never exceed what there is, even if that breaks the floor - overflowing the
        // viewport would bring back the horizontal scrollbar this sizing exists to avoid.
        return Math.min(target, availableWidth);
    }

    /** Convenience overload using the shared defaults. */
    static int bubbleWidth(int availableWidth) {
        return bubbleWidth(availableWidth, WIDTH_FRACTION, MIN_WIDTH, MAX_WIDTH);
    }
}
