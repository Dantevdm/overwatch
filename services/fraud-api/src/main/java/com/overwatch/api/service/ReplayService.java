package com.overwatch.api.service;

import com.overwatch.api.dto.Dtos.*;
import com.overwatch.api.repository.TransactionReadRepository;
import com.overwatch.common.domain.Channel;
import com.overwatch.common.domain.Transaction;
import com.overwatch.common.persistence.TransactionEntity;
import com.overwatch.engine.rule.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Answers "what would this rule have caught?" without changing anything.
 *
 * <p>Replays stored transactions through a candidate configuration and reports the
 * alerts it <em>would</em> have produced. Nothing is written and no rule is
 * altered, which is the entire point: "should we drop the high-value threshold to
 * R30 000?" becomes a measurement rather than an argument.
 *
 * <p>This is also the natural companion to shadow mode. Shadow evaluates a rule
 * forward against live traffic; replay evaluates it backward against history. Use
 * replay to pick a starting threshold, shadow to confirm it, then promote.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final int MAX_HOURS = 24 * 7;
    private static final int SAMPLE_LIMIT = 20;

    private final TransactionReadRepository transactions;
    private final Map<String, FraudRule> rulesByType;

    public ReplayService(TransactionReadRepository transactions, List<FraudRule> rules) {
        this.transactions = transactions;
        this.rulesByType = new HashMap<>();
        rules.forEach(r -> rulesByType.put(r.ruleType(), r));
    }

    public Set<String> availableRuleTypes() {
        return Collections.unmodifiableSet(rulesByType.keySet());
    }

    @Transactional(readOnly = true)
    public ReplayResult replay(ReplayRequest request) {
        FraudRule rule = rulesByType.get(request.ruleType());
        if (rule == null) {
            throw new IllegalArgumentException(
                    "Unknown rule type '" + request.ruleType() + "'. Available: " + rulesByType.keySet());
        }

        int hours = Math.max(1, Math.min(request.hours() <= 0 ? 24 : request.hours(), MAX_HOURS));
        Instant since = Instant.now().minus(hours, ChronoUnit.HOURS);

        RuleParameters params = RuleParameters.of(
                request.parameters() == null ? Map.of() : request.parameters());

        // Replay is read-only, so history lookups are deliberately inert. A rule
        // that needs history (velocity, deviation) reports nothing rather than
        // silently producing a misleading answer from the wrong baseline.
        TransactionHistory noHistory = new TransactionHistory() {
            @Override public long countRecent(String cardId, Duration window) { return 0; }
            @Override public Optional<BigDecimal> averageAmount(String c, int m) { return Optional.empty(); }
        };

        long evaluated = 0;
        long fired = 0;
        List<ReplaySample> samples = new ArrayList<>();

        for (TransactionEntity entity : transactions.findAll()) {
            if (entity.getOccurredAt() == null || entity.getOccurredAt().isBefore(since)) {
                continue;
            }
            evaluated++;
            Optional<RuleFinding> finding =
                    rule.evaluate(new RuleContext(toDomain(entity), params, noHistory));
            if (finding.isPresent()) {
                fired++;
                if (samples.size() < SAMPLE_LIMIT) {
                    samples.add(new ReplaySample(entity.getId(), entity.getMerchantName(),
                            entity.getAmount(), finding.get().reason()));
                }
            }
        }

        double pct = evaluated == 0 ? 0 : (double) fired / evaluated * 100;
        log.info("Replay of {} over {}h: {} of {} transactions would have fired ({}%)",
                request.ruleType(), hours, fired, evaluated, String.format("%.2f", pct));

        return new ReplayResult(request.ruleType(), request.parameters(), hours,
                evaluated, fired, pct, samples);
    }

    private static Transaction toDomain(TransactionEntity e) {
        Channel channel;
        try {
            channel = Channel.valueOf(e.getChannel());
        } catch (IllegalArgumentException ex) {
            channel = Channel.ONLINE;
        }
        return new Transaction(e.getId(), e.getCardId(),
                e.getCustomerId(), e.getCustomerName(),
                e.getAmount(), e.getCurrency(),
                e.getMerchantName(), e.getMerchantCategory(), e.getCountryCode(),
                channel, e.getOccurredAt(), e.getMetadata());
    }
}
