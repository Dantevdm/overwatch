package com.overwatch.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The range-to-bucket choice behind the dashboard's time filter.
 *
 * <p>Worth asserting even though this module's coverage floor is zero: the rest of
 * the module is wiring, but picking a bucket width is a decision, and getting it
 * wrong is not loud. Too wide and a five-minute window renders as one bar; too
 * narrow and a week is thousands of points. Neither throws.
 */
class StatsRangeTest {

    @Nested
    @DisplayName("bucket width")
    class BucketWidth {

        @Test
        @DisplayName("every offered range stays under the readable-point budget")
        void everyOfferedRangeIsReadable() {
            // The seven ranges the dashboard's control offers.
            for (int minutes : new int[]{5, 15, 30, 60, 720, 1440, 10080}) {
                long width = StatsService.bucketSecondsFor(minutes);
                long buckets = (long) minutes * 60 / width;

                assertTrue(buckets <= 40,
                        minutes + "m gives " + buckets + " buckets, too many to read");
                // A handful of points cannot show a shape. Two bars is not a trend.
                assertTrue(buckets >= 12,
                        minutes + "m gives only " + buckets + " buckets, too few to show a shape");
            }
        }

        @Test
        @DisplayName("picks round widths a reader can place on a clock")
        void picksRoundWidths() {
            assertEquals(10, StatsService.bucketSecondsFor(5));       // 10 seconds
            assertEquals(30, StatsService.bucketSecondsFor(15));      // 30 seconds
            assertEquals(60, StatsService.bucketSecondsFor(30));      // 1 minute
            assertEquals(120, StatsService.bucketSecondsFor(60));     // 2 minutes
            assertEquals(1800, StatsService.bucketSecondsFor(720));   // 30 minutes
            assertEquals(3600, StatsService.bucketSecondsFor(1440));   // 1 hour
            assertEquals(21600, StatsService.bucketSecondsFor(10080)); // 6 hours
        }

        @Test
        @DisplayName("width never decreases as the window grows")
        void widthIsMonotonic() {
            long previous = 0;
            for (int minutes = 1; minutes <= StatsService.MAX_RANGE_MINUTES; minutes += 7) {
                long width = StatsService.bucketSecondsFor(minutes);
                assertTrue(width >= previous,
                        "width dropped from " + previous + " to " + width + " at " + minutes + "m");
                previous = width;
            }
        }
    }

    @Nested
    @DisplayName("range clamping")
    class Clamping {

        @Test
        @DisplayName("absent, zero and negative fall back to the default")
        void degradesToDefault() {
            // Not to "all of history": an unbounded window is a table scan someone
            // triggers by deleting a query parameter.
            assertEquals(StatsService.DEFAULT_RANGE_MINUTES, StatsService.clampRange(null));
            assertEquals(StatsService.DEFAULT_RANGE_MINUTES, StatsService.clampRange(0));
            assertEquals(StatsService.DEFAULT_RANGE_MINUTES, StatsService.clampRange(-60));
        }

        @Test
        @DisplayName("an over-long window is capped, not rejected")
        void capsRatherThanRejects() {
            assertEquals(StatsService.MAX_RANGE_MINUTES, StatsService.clampRange(999_999));
        }

        @Test
        @DisplayName("a value inside the bounds passes through untouched")
        void passesThroughValidValues() {
            assertEquals(5, StatsService.clampRange(5));
            assertEquals(10080, StatsService.clampRange(10080));
        }
    }
}
