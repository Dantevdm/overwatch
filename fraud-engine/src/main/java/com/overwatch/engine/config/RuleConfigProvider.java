package com.overwatch.engine.config;

import com.overwatch.common.domain.RuleState;
import com.overwatch.engine.persistence.entity.FraudRuleEntity;
import com.overwatch.engine.persistence.repository.FraudRuleRepository;
import com.overwatch.engine.rule.RuleParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the active rule configuration in memory, refreshed on a timer.
 *
 * <p>Rules are data, so a threshold change has to take effect without a redeploy.
 * Reading them from the database on every transaction would be correct and far too
 * slow, so they are cached and refreshed on a cycle — a change is live within one
 * refresh interval, which is the trade this design makes explicit.
 *
 * <p>The cache is an {@link AtomicReference} to an immutable list. The consumer
 * thread reads a consistent snapshot with no locking, and a refresh swaps the whole
 * list rather than mutating it, so a rule set is never observed half-updated.
 */
@Component
public class RuleConfigProvider {

    private static final Logger log = LoggerFactory.getLogger(RuleConfigProvider.class);
    private static final List<String> EVALUABLE = List.of("ENABLED", "SHADOW");

    private final FraudRuleRepository repository;
    private final AtomicReference<List<RuleConfig>> cache = new AtomicReference<>(List.of());

    public RuleConfigProvider(FraudRuleRepository repository) {
        this.repository = repository;
    }

    /** The current rule set. Never null; empty until the first refresh completes. */
    public List<RuleConfig> active() {
        return cache.get();
    }

    @Scheduled(
            initialDelayString = "0",
            fixedDelayString = "${overwatch.engine.rule-refresh-seconds:30}000")
    @Transactional(readOnly = true)
    public void refresh() {
        try {
            List<RuleConfig> loaded = repository.findByStateIn(EVALUABLE).stream()
                    .map(RuleConfigProvider::toConfig)
                    .toList();

            List<RuleConfig> previous = cache.getAndSet(loaded);

            // Log only on change. A line every thirty seconds saying nothing
            // happened is how real signal gets lost.
            if (previous.size() != loaded.size() || previous.isEmpty()) {
                log.info("Rule configuration loaded: {} evaluable ({} shadow)",
                        loaded.size(),
                        loaded.stream().filter(c -> c.state() == RuleState.SHADOW).count());
            }
        } catch (RuntimeException e) {
            // Keep serving the last known good configuration. A database blip
            // must not leave the pipeline running with no rules at all, which
            // would silently pass fraud through.
            log.error("Rule refresh failed; continuing with {} cached rules",
                    cache.get().size(), e);
        }
    }

    private static RuleConfig toConfig(FraudRuleEntity e) {
        RuleState state;
        try {
            state = RuleState.valueOf(e.getState().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            log.warn("Rule {} has unrecognised state '{}'; treating as DISABLED",
                    e.getId(), e.getState());
            state = RuleState.DISABLED;
        }

        return new RuleConfig(
                e.getId(),
                e.getRuleType(),
                e.getName(),
                state,
                e.getWeight() == null ? 0.0 : e.getWeight().doubleValue(),
                RuleParameters.of(e.getParameters()));
    }
}
