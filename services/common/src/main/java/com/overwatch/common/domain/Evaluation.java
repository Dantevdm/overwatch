package com.overwatch.common.domain;

import java.util.List;

/**
 * The outcome of running a transaction through the full rule set.
 *
 * <p>Every transaction produces an evaluation, including the overwhelming majority
 * that trip nothing. That matters for observability: "how many transactions did we
 * clear" is as much a question as "how many did we flag", and a pipeline that only
 * records its alerts cannot answer the first one.
 *
 * <p>Shadow hits are kept separate from scoring hits. A rule under evaluation must
 * not move the risk score of a live transaction — that is the entire point of
 * shadow mode.
 */
public record Evaluation(
        Transaction transaction,
        List<RuleHit> scoringHits,
        List<RuleHit> shadowHits,
        double riskScore,
        Severity severity
) {

    /** Score at or above which an evaluation becomes an alert. */
    public static final double ALERT_THRESHOLD = 0.30;

    public static Evaluation of(Transaction txn, List<RuleHit> allHits) {
        List<RuleHit> scoring = allHits.stream().filter(h -> !h.shadow()).toList();
        List<RuleHit> shadow = allHits.stream().filter(RuleHit::shadow).toList();

        double score = Math.min(1.0, scoring.stream().mapToDouble(RuleHit::weight).sum());

        return new Evaluation(txn, scoring, shadow, score, Severity.fromScore(score));
    }

    /** Whether this evaluation should be persisted as an alert. */
    public boolean isAlert() {
        return riskScore >= ALERT_THRESHOLD;
    }
}
