package com.overwatch.simulator.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the scheduling mode of the emission tick.
 *
 * <p>This asserts an annotation, which is normally a poor thing to test — but the
 * distinction it guards is invisible to every other kind of test and cost this
 * system its data once already.
 *
 * <p>{@code fixedRate} schedules from the previous <em>start</em>, so a scheduler
 * thread that falls behind fires every missed tick back to back to catch up. Three
 * minutes of host starvation replayed as roughly sixteen thousand ticks: 116 000
 * transactions in three minutes against a configured five per second. Every card
 * then carried fifty-odd transactions inside the ten-minute velocity window, the
 * velocity rule fired on 99.5% of traffic, and the store filled with 111 692
 * indistinguishable MEDIUM alerts.
 *
 * <p>None of that is reachable from a unit test of {@link SimulatorService#tick()},
 * which behaves correctly in isolation either way — the fault is in when the
 * container calls it. The annotation is the only artefact that carries the
 * decision, so the annotation is what gets asserted.
 */
class TickSchedulingTest {

    @Test
    @DisplayName("the tick schedules from completion, so a stall cannot be replayed as a burst")
    void tickUsesFixedDelay() throws NoSuchMethodException {
        Method tick = SimulatorService.class.getMethod("tick");
        Scheduled scheduled = tick.getAnnotation(Scheduled.class);

        assertThat(scheduled)
                .as("tick() must stay scheduled — without this the simulator emits nothing")
                .isNotNull();

        assertThat(scheduled.fixedDelay())
                .as("""
                        tick() must use fixedDelay. fixedRate schedules from the previous \
                        start, so missed executions are fired back to back to catch up — \
                        which once turned three minutes of host starvation into 116 000 \
                        transactions and made the velocity rule fire on 99.5% of traffic.""")
                .isPositive();

        assertThat(scheduled.fixedRate())
                .as("fixedRate must stay unset; see the fixedDelay assertion above")
                .isEqualTo(-1);
    }
}
