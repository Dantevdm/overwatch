package com.overwatch.simulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The shape of the simulated day.
 *
 * <p>The curve exists because the generator used to emit exactly N transactions
 * every second, which produced a throughput chart measured at 28.0 tx/s with a
 * standard deviation of 0.01 — a perfectly straight line across every dashboard.
 */
@DisplayName("Diurnal traffic curve")
class DiurnalCurveTest {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    private static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 3, 12, hour, minute, 0, 0, SAST);
    }

    @Test
    @DisplayName("averages to 1.0 across the day, so the configured rate is a daily mean")
    void averagesToOne() {
        // This is what lets the control panel keep meaning what it says. If the
        // curve averaged to anything else, setting 20/s would quietly deliver
        // some other number of transactions per day.
        double total = 0;
        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute += 5) {
                total += SimulatorService.diurnalWeight(at(hour, minute));
            }
        }
        double mean = total / (24 * 12);
        assertEquals(1.0, mean, 0.02, "the curve must average to 1.0 over a day");
    }

    @Test
    @DisplayName("night is quiet and the evening commute is the peak")
    void hasTheShapeOfARealDay() {
        double threeAm = SimulatorService.diurnalWeight(at(3, 0));
        double lunch = SimulatorService.diurnalWeight(at(12, 30));
        double evening = SimulatorService.diurnalWeight(at(18, 0));

        assertTrue(threeAm < 0.2, "the small hours should be nearly dead, was " + threeAm);
        assertTrue(lunch > 1.3, "lunch should be busy, was " + lunch);
        assertTrue(evening > lunch, "the evening commute should be the day's peak");
        assertTrue(evening / threeAm > 10,
                "peak-to-trough should be dramatic enough to see on a chart");
    }

    @Test
    @DisplayName("slides between hours instead of stepping")
    void interpolatesBetweenHours() {
        // Twenty-four flat steps would just be a coarser straight line, with a
        // visible cliff at the top of each hour.
        double sixPm = SimulatorService.diurnalWeight(at(18, 0));
        double halfPast = SimulatorService.diurnalWeight(at(18, 30));
        double sevenPm = SimulatorService.diurnalWeight(at(19, 0));

        assertNotEquals(sixPm, halfPast, 1e-9, "the curve must move within the hour");
        assertTrue(halfPast < sixPm && halfPast > sevenPm,
                "18:30 should sit between the 18:00 and 19:00 values");
    }

    @Test
    @DisplayName("wraps from 23:00 to midnight without falling off the end")
    void wrapsAtMidnight() {
        // The interpolation reads hour + 1, so 23:xx has to wrap to index 0
        // rather than walk off the array.
        assertDoesNotThrow(() -> SimulatorService.diurnalWeight(at(23, 59)));
        assertTrue(SimulatorService.diurnalWeight(at(23, 59)) > 0);
    }
}
