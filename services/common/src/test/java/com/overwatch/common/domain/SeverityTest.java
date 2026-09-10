package com.overwatch.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Severity")
class SeverityTest {

    @DisplayName("maps a risk score to a band")
    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({
            "0.00, LOW",
            "0.30, LOW",
            "0.44, LOW",
            "0.45, MEDIUM",
            "0.59, MEDIUM",
            "0.60, HIGH",
            "0.79, HIGH",
            "0.80, CRITICAL",
            "1.00, CRITICAL"
    })
    void mapsScoreToBand(double score, Severity expected) {
        assertEquals(expected, Severity.fromScore(score));
    }

    @Test
    @DisplayName("boundaries are inclusive at the lower edge of each band")
    void boundariesAreInclusive() {
        // A score sitting exactly on a threshold belongs to the higher band.
        // Getting this backwards silently downgrades every borderline alert.
        assertEquals(Severity.MEDIUM, Severity.fromScore(0.45));
        assertEquals(Severity.HIGH, Severity.fromScore(0.60));
        assertEquals(Severity.CRITICAL, Severity.fromScore(0.80));
    }

    @Test
    @DisplayName("clamps gracefully outside the nominal range")
    void handlesOutOfRange() {
        assertEquals(Severity.LOW, Severity.fromScore(-0.5));
        assertEquals(Severity.CRITICAL, Severity.fromScore(4.2));
    }

    @Test
    @DisplayName("every band is reachable by a score that actually alerts")
    void everyBandIsReachableAboveTheAlertThreshold() {
        // The regression this guards: MEDIUM once began at exactly
        // Evaluation.ALERT_THRESHOLD, so no persisted alert could ever be LOW.
        // Nothing below the threshold is stored, so a band that only exists
        // below it is a band the product does not have.
        Set<Severity> reachable = Arrays.stream(scoresFromThresholdToOne())
                .mapToObj(Severity::fromScore)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Severity.class)));
        assertEquals(EnumSet.allOf(Severity.class), reachable,
                "a severity unreachable at or above the alert threshold can never be stored");
    }

    private static double[] scoresFromThresholdToOne() {
        int steps = (int) Math.round((1.0 - Evaluation.ALERT_THRESHOLD) * 100) + 1;
        double[] scores = new double[steps];
        for (int i = 0; i < steps; i++) {
            scores[i] = Evaluation.ALERT_THRESHOLD + i / 100.0;
        }
        return scores;
    }
}
