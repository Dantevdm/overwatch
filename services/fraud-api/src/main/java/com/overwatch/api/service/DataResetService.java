package com.overwatch.api.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Empties the observed data so a demo can start from nothing.
 *
 * <p>What it clears and what it does not is the whole design of this class.
 *
 * <p><strong>Cleared:</strong> transactions, alerts, alert rule hits and shadow
 * rule hits — everything the pipeline <em>observed</em>.
 *
 * <p><strong>Kept:</strong> {@code fraud_rules}. Rules are configuration, not
 * history, and they are seeded by a Flyway migration — which will not re-run on
 * an existing volume, so deleting them would leave the engine with no rules at
 * all and no way back short of {@code make clean}. Any weight or state changed
 * through the API therefore survives a reset. That is also the more useful
 * behaviour: tuning a threshold and then clearing the traffic to watch the new
 * threshold work is exactly the demo this exists for.
 *
 * <p><strong>Also kept:</strong> the Micrometer counters behind Prometheus.
 * A Prometheus counter is monotonic by contract and {@code rate()} treats a
 * decrease as a process restart, so zeroing them would write a false spike into
 * every Grafana panel and throw away the history those dashboards exist to show.
 * The consequence is worth stating plainly rather than hiding: immediately after
 * a reset the dashboard reads zero while Grafana still shows the full run. Those
 * two are answering different questions — "what is in the store now" and "what
 * has this process done since it started" — and both answers are correct.
 */
@Service
public class DataResetService {

    private static final Logger log = LoggerFactory.getLogger(DataResetService.class);

    /**
     * Emptied newest-dependency-first, and every table naming a foreign key into
     * another is listed explicitly.
     *
     * <p>{@code TRUNCATE transactions CASCADE} would be shorter and is what makes
     * this kind of thing dangerous: CASCADE truncates whatever happens to
     * reference the table, so a future migration adding a table nobody remembers
     * would silently start being wiped by this endpoint. Naming all four means
     * PostgreSQL rejects the statement outright if the graph ever grows a member
     * this list does not cover, which turns a silent data loss into a loud error.
     */
    private static final String TRUNCATE = """
            TRUNCATE TABLE alert_rule_hits, shadow_rule_hits, fraud_alerts, transactions
            """;

    /**
     * Counted before the truncate, so the caller can report what it removed.
     * A list of pairs rather than a Map: {@code Map.of} has unspecified iteration
     * order, which would let the JSON keys shuffle between identical calls.
     */
    private record Counted(String jsonKey, String table) {
    }

    private static final List<Counted> COUNTED = List.of(
            new Counted("transactions", "transactions"),
            new Counted("alerts", "fraud_alerts"),
            new Counted("alertRuleHits", "alert_rule_hits"),
            new Counted("shadowRuleHits", "shadow_rule_hits"));

    @PersistenceContext
    private EntityManager em;

    /**
     * @return how many rows were removed, per table
     */
    @Transactional
    public Map<String, Long> reset() {
        Map<String, Long> removed = new LinkedHashMap<>();
        // Count first: after the truncate there is nothing left to count, and a
        // reset that cannot say what it deleted is hard to trust.
        for (Counted c : COUNTED) {
            // Table names come from the constant above, never from a caller —
            // there is no request input anywhere near this string.
            Number n = (Number) em.createNativeQuery(
                    "SELECT count(*) FROM " + c.table()).getSingleResult();
            removed.put(c.jsonKey(), n.longValue());
        }

        em.createNativeQuery(TRUNCATE).executeUpdate();

        log.warn("Data reset: cleared {} transactions, {} alerts, {} alert hits, {} shadow hits. "
                        + "Rule configuration was not touched.",
                removed.get("transactions"), removed.get("alerts"),
                removed.get("alertRuleHits"), removed.get("shadowRuleHits"));

        return removed;
    }
}
