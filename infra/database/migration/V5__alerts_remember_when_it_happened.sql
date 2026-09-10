-- An alert had only created_at: the moment the engine wrote the row. That is a
-- fact about the pipeline, not about the fraud, and the dashboard was charting
-- it as if it were the latter. Replay a backlog -- or seed a history, which the
-- simulator now does at startup -- and tens of thousands of alerts spanning
-- weeks all land in whichever bucket the engine happened to be running in, so
-- the chart shows one spike and a flat line either side of it.
--
-- occurred_at is the transaction's own timestamp, carried onto the alert. Both
-- columns stay: created_at answers "how far behind is the pipeline", occurred_at
-- answers "when was this card being used", and the two differ by exactly the
-- detection lag, which is worth being able to measure.
--
-- Denormalised rather than joined. The alternative is a join to transactions on
-- every dashboard poll, which is correct but pays for a join five seconds apart
-- forever to read a column that can never change once written.
ALTER TABLE fraud_alerts ADD COLUMN occurred_at TIMESTAMPTZ;

UPDATE fraud_alerts a
   SET occurred_at = t.occurred_at
  FROM transactions t
 WHERE t.id = a.transaction_id;

-- Every alert has a transaction (the FK enforces it) and every transaction has
-- an occurred_at, so the backfill leaves nothing null and the column can be
-- required from here on.
ALTER TABLE fraud_alerts ALTER COLUMN occurred_at SET NOT NULL;

-- Mirrors idx_alert_created. The dashboard's time series and the alert list both
-- order and window on this column now.
CREATE INDEX idx_alert_occurred ON fraud_alerts (occurred_at DESC);
CREATE INDEX idx_alert_severity_occurred ON fraud_alerts (severity, occurred_at DESC);
