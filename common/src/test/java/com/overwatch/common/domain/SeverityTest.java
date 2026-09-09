package com.overwatch.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Severity")
class SeverityTest {

    @DisplayName("maps a risk score to a band")
    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({
            "0.00, LOW",
            "0.29, LOW",
            "0.30, MEDIUM",
            "0.54, MEDIUM",
            "0.55, HIGH",
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
        assertEquals(Severity.MEDIUM, Severity.fromScore(0.30));
        assertEquals(Severity.HIGH, Severity.fromScore(0.55));
        assertEquals(Severity.CRITICAL, Severity.fromScore(0.80));
    }

    @Test
    @DisplayName("clamps gracefully outside the nominal range")
    void handlesOutOfRange() {
        assertEquals(Severity.LOW, Severity.fromScore(-0.5));
        assertEquals(Severity.CRITICAL, Severity.fromScore(4.2));
    }
}
