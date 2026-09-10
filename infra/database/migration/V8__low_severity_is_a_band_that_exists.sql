-- Re-band the alerts already in the store.
--
-- MEDIUM used to begin at 0.30, which is exactly Evaluation.ALERT_THRESHOLD, so
-- LOW was arithmetically unreachable: nothing below 0.30 is ever persisted, and
-- everything at or above it was MEDIUM or higher. The severity filter had a
-- value that matched nothing and the dashboard's severity mix was missing its
-- bottom band.
--
-- Severity.fromScore now reads LOW 0.30-0.44, MEDIUM 0.45-0.59, HIGH 0.60-0.79,
-- CRITICAL 0.80+. That is a relabelling and not a change in what alerts, so the
-- history can be relabelled with it rather than left in two dialects: an
-- analyst comparing last week to this week should not find the same score
-- carrying two different words.
--
-- Derived from risk_score rather than mapped from the old label, because the old
-- MEDIUM covers 0.30-0.54 and splits across the new LOW and MEDIUM. The
-- expression is the same ladder as the Java, in the same order, and this file is
-- the place to look if the two ever disagree.
UPDATE fraud_alerts
SET severity = CASE
        WHEN risk_score >= 0.80 THEN 'CRITICAL'
        WHEN risk_score >= 0.60 THEN 'HIGH'
        WHEN risk_score >= 0.45 THEN 'MEDIUM'
        ELSE 'LOW'
    END
WHERE severity <> CASE
        WHEN risk_score >= 0.80 THEN 'CRITICAL'
        WHEN risk_score >= 0.60 THEN 'HIGH'
        WHEN risk_score >= 0.45 THEN 'MEDIUM'
        ELSE 'LOW'
    END;
