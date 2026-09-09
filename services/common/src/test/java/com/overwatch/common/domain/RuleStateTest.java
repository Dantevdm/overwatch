package com.overwatch.common.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RuleState")
class RuleStateTest {

    @Test
    @DisplayName("ENABLED is evaluated and raises alerts")
    void enabledEvaluatesAndAlerts() {
        assertTrue(RuleState.ENABLED.isEvaluated());
        assertTrue(RuleState.ENABLED.raisesAlerts());
    }

    @Test
    @DisplayName("SHADOW is evaluated but raises no alerts")
    void shadowEvaluatesWithoutAlerting() {
        // The whole point of shadow mode: the rule runs against live traffic so
        // its hits can be reviewed, but it cannot page anyone.
        assertTrue(RuleState.SHADOW.isEvaluated());
        assertFalse(RuleState.SHADOW.raisesAlerts());
    }

    @Test
    @DisplayName("DISABLED is not evaluated at all")
    void disabledIsSkipped() {
        assertFalse(RuleState.DISABLED.isEvaluated());
        assertFalse(RuleState.DISABLED.raisesAlerts());
    }

    @Test
    @DisplayName("no state raises alerts without also being evaluated")
    void alertingImpliesEvaluation() {
        // Guards against a future state being added that alerts without running.
        for (RuleState state : RuleState.values()) {
            if (state.raisesAlerts()) {
                assertTrue(state.isEvaluated(),
                        state + " raises alerts but is not evaluated");
            }
        }
    }
}
