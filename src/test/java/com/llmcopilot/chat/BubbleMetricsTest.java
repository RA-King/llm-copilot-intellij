package com.llmcopilot.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for how a chat bubble sizes itself against the current tool-window width. */
class BubbleMetricsTest {

    @Nested
    @DisplayName("sizing against the available width")
    class Sizing {

        @Test
        void aBubbleTakesItsShareOfTheWidth() {
            assertEquals(430, BubbleMetrics.bubbleWidth(500, 0.86, 140, 700));
        }

        @Test
        void aWideWindowIsCappedSoLinesStayReadable() {
            assertEquals(700, BubbleMetrics.bubbleWidth(2000, 0.86, 140, 700));
        }

        @Test
        void aNarrowWindowStopsShrinkingAtTheFloor() {
            assertEquals(140, BubbleMetrics.bubbleWidth(150, 0.86, 140, 700));
        }

        /** The floor must never win over the viewport, or the scrollbar comes back. */
        @Test
        void theFloorNeverExceedsWhatIsActuallyAvailable() {
            assertEquals(90, BubbleMetrics.bubbleWidth(90, 0.86, 140, 700));
        }
    }

    @Nested
    @DisplayName("before layout has run")
    class Unlaid {

        @Test
        void anUnmeasuredParentFallsBackToTheFloor() {
            assertEquals(140, BubbleMetrics.bubbleWidth(0, 0.86, 140, 700));
        }

        @Test
        void aNegativeWidthIsTreatedTheSameWay() {
            assertEquals(140, BubbleMetrics.bubbleWidth(-20, 0.86, 140, 700));
        }
    }

    @Nested
    @DisplayName("resize behaviour")
    class Resizing {

        @Test
        void wideningTheWindowWidensTheBubbleUntilTheCap() {
            int narrow = BubbleMetrics.bubbleWidth(400, 0.86, 140, 700);
            int medium = BubbleMetrics.bubbleWidth(600, 0.86, 140, 700);
            int wide   = BubbleMetrics.bubbleWidth(900, 0.86, 140, 700);

            assertTrue(narrow < medium, narrow + " < " + medium);
            assertTrue(medium < wide,   medium + " < " + wide);
            assertEquals(700, BubbleMetrics.bubbleWidth(1200, 0.86, 140, 700));
        }

        @Test
        void theBubbleAlwaysFitsInsideTheViewport() {
            for (int available = 1; available <= 1500; available += 7) {
                assertTrue(BubbleMetrics.bubbleWidth(available) <= available,
                    "overflowed at available=" + available);
            }
        }
    }

    @Test
    void theDefaultsMatchTheSharedConstants() {
        assertEquals(
            BubbleMetrics.bubbleWidth(800, BubbleMetrics.WIDTH_FRACTION,
                                      BubbleMetrics.MIN_WIDTH, BubbleMetrics.MAX_WIDTH),
            BubbleMetrics.bubbleWidth(800));
    }
}
