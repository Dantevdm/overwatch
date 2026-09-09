package com.overwatch.engine.rule;

import com.overwatch.common.domain.Evaluation;
import com.overwatch.common.domain.RuleHit;
import com.overwatch.common.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs a transaction through the configured rule set and accumulates a risk score.
 *
 * <p>Three things happen here that are worth naming.
 *
 * <p>Rules are discovered, not registered. Spring injects every {@link FraudRule}
 * implementation and they are indexed by type, so adding a detection technique is
 * one class and one database row.
 *
 * <p>Shadow rules are evaluated but their hits are marked, and {@link Evaluation}
 * keeps marked hits out of the score. A rule under evaluation therefore cannot
 * influence a live decision, which is the guarantee shadow mode has to make to be
 * worth anything.
 *
 * <p>A rule that throws is contained. One badly configured rule must not stop the
 * other five from protecting anyone, so failures are logged and counted and
 * evaluation continues. Silently swallowing them would be worse — hence the
 * counter.
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    private final Map<String, FraudRule> rulesByType;

    public RuleEngine(List<FraudRule> rules) {
        this.rulesByType = rules.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                        FraudRule::ruleType, Function.identity()));
        log.info("Rule engine started with {} rule types: {}",
                rulesByType.size(), rulesByType.keySet());
    }

    /**
     * @param configs the currently active rule configuration; the caller owns
     *                refreshing this, so the engine itself stays free of I/O
     */
    public Evaluation evaluate(Transaction txn, List<RuleConfig> configs, TransactionHistory history) {
        List<RuleHit> hits = new ArrayList<>();

        for (RuleConfig config : configs) {
            if (!config.isEvaluated()) {
                continue;
            }
            FraudRule rule = rulesByType.get(config.ruleType());
            if (rule == null) {
                // Configuration naming a rule type nobody implements. Worth a
                // warning: it means someone expects protection that is not there.
                log.warn("No implementation for rule type '{}' (rule id {}, '{}')",
                        config.ruleType(), config.id(), config.name());
                continue;
            }

            evaluateSafely(rule, config, txn, history)
                    .map(finding -> toHit(config, finding))
                    .ifPresent(hits::add);
        }

        return Evaluation.of(txn, hits);
    }

    private Optional<RuleFinding> evaluateSafely(FraudRule rule, RuleConfig config,
                                                 Transaction txn, TransactionHistory history) {
        try {
            return rule.evaluate(new RuleContext(txn, config.parameters(), history));
        } catch (RuntimeException e) {
            log.error("Rule '{}' (id {}) failed on transaction {}; continuing without it",
                    config.ruleType(), config.id(), txn.id(), e);
            return Optional.empty();
        }
    }

    private static RuleHit toHit(RuleConfig config, RuleFinding finding) {
        // Weight and shadow come from configuration, never from the rule. The same
        // implementation runs at one weight live and another in shadow.
        return new RuleHit(
                config.ruleType(),
                config.id(),
                config.weight(),
                finding.reason(),
                finding.evidence(),
                !config.raisesAlerts());
    }
}
