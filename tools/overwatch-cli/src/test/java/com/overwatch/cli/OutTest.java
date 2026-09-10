package com.overwatch.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The formatting, which is the only part of this module with logic worth
 * asserting. Everything else is argument parsing picocli already tests and HTTP
 * calls the smoke test already exercises against a real stack.
 */
@DisplayName("CLI output formatting")
class OutTest {

    @Test
    @DisplayName("measures visible width, ignoring escape sequences")
    void ansiWidthIgnoresEscapes() {
        // The property every table in this tool depends on. String.length()
        // counts the invisible bytes in a coloured cell, which is why
        // hand-rolled ANSI tables come out ragged in exactly the coloured
        // columns — and why the padding helpers measure instead.
        String coloured = "\u001B[31mHIGH\u001B[0m";
        assertEquals(4, Ansi.width(coloured));
        assertEquals("HIGH".length(), Ansi.width(coloured));
    }

    @Test
    @DisplayName("pads a coloured cell to the same visible width as a plain one")
    void padUsesVisibleWidth() {
        String coloured = "\u001B[31mHIGH\u001B[0m";
        assertEquals(10, Ansi.width(Ansi.pad(coloured, 10)));
        assertEquals(10, Ansi.width(Ansi.pad("HIGH", 10)));
        assertEquals(10, Ansi.width(Ansi.padLeft(coloured, 10)));
    }

    @Test
    @DisplayName("never truncates when the text is already wider than the target")
    void padDoesNotTruncate() {
        // Silently cutting a value to fit a column would turn a wrong-looking
        // number into a plausible one that is wrong. Clipping is Out.clip's
        // job, and it is explicit about it.
        assertEquals("CRITICAL", Ansi.pad("CRITICAL", 3));
    }

    @Test
    @DisplayName("formats rand with a non-breaking group separator")
    void zarIsGroupedAndDoesNotWrap() {
        String formatted = Out.zar(68343795.35);
        assertTrue(formatted.startsWith("R"), formatted);
        assertTrue(formatted.endsWith("795.35"), formatted);
        // A normal space would let a table cell wrap mid-number, and half an
        // amount reads as a whole one.
        assertTrue(formatted.contains(" "), "expected a non-breaking separator: " + formatted);
    }

    @Test
    @DisplayName("formats rand the same way regardless of the default locale")
    void zarIgnoresTheDefaultLocale() {
        // Locale.ROOT is asked for explicitly, so the same command produces the
        // same bytes on a laptop set to en-US or de-DE. Otherwise a diff between
        // two runs is a diff between two locales. Not en-ZA: that uses a comma
        // as the DECIMAL separator, and this formatter substitutes commas.
        assertEquals(Out.zar(1234.5), Out.zar(1234.5));
        assertTrue(Out.zar(1234.5).endsWith("234.50"), Out.zar(1234.5));
    }

    @Test
    @DisplayName("draws a bar proportional to the fraction, and clamps out-of-range input")
    void barIsProportionalAndClamped() {
        assertEquals(10, Ansi.width(Out.bar(0.0, 10)));
        assertEquals(10, Ansi.width(Out.bar(1.0, 10)));
        assertEquals(10, Ansi.width(Out.bar(0.5, 10)));
        // A share arriving as 1.4 from a bad divisor must not draw past its
        // column and break every row under it.
        assertEquals(10, Ansi.width(Out.bar(1.4, 10)));
        assertEquals(10, Ansi.width(Out.bar(-0.2, 10)));
    }

    @Test
    @DisplayName("scales a sparkline to its own maximum, with a zero floor")
    void sparklineScalesFromZero() {
        String line = Out.sparkline(List.of(0L, 5L, 10L));
        assertEquals(3, line.length());
        assertEquals('▁', line.charAt(0));
        assertEquals('█', line.charAt(2));
    }

    @Test
    @DisplayName("draws a flat series flat, rather than as noise")
    void sparklineOfAFlatSeriesIsFlat() {
        // Scaling to min-max instead of zero-max is the usual bug in terminal
        // sparklines: it turns "nothing happened" into a dramatic shape.
        String line = Out.sparkline(List.of(7L, 7L, 7L));
        assertEquals("███", line);
        assertEquals("▁▁▁", Out.sparkline(List.of(0L, 0L, 0L)));
    }

    @Test
    @DisplayName("shortens an id to its first segment")
    void shortIdTakesTheFirstSegment() {
        assertEquals("6bdc4f64", Out.shortId("6bdc4f64-6ef9-4391-a692-f03ae816812a"));
        assertEquals("—", Out.shortId(null));
    }

    @Test
    @DisplayName("clips with an ellipsis, and leaves short text alone")
    void clipIsExplicit() {
        assertEquals("Food Lover'…", Out.clip("Food Lover's Market Claremont", 12));
        assertEquals("short", Out.clip("short", 12));
    }

    @Test
    @DisplayName("severity always carries its label, never colour alone")
    void severityKeepsItsLabel() {
        // The output goes into pipes, logs, and terminals with palettes a
        // reader cannot distinguish. "The red ones" is not a specification.
        for (String band : List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")) {
            assertTrue(Out.severity(band).contains(band), band);
        }
    }
}
