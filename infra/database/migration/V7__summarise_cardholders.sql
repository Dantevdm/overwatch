-- The cardholder directory, precomputed.
--
-- The screen aggregates every transaction in the table on every request:
-- GROUP BY customer_id with a COUNT(DISTINCT card_id), plus a second pass to
-- count the groups for the pager. V6's covering index took that from 268ms to
-- 92ms, which is a constant factor and not a fix -- the work still grows with
-- the table, and it measurably did: 92ms at 450 000 transactions, 124ms at
-- 530 000, an hour apart.
--
-- A materialised view computes it once per refresh instead of once per reader.
-- The whole cost of the screen becomes independent of how many people have it
-- open, which is the property that was missing.
--
-- The trade is staleness, and it is a real one: a cardholder who appears
-- between refreshes is not in the directory until the next one. That is
-- acceptable here and would not be everywhere -- the directory is a way in to a
-- profile, and the profile itself reads the transactions table directly and is
-- always current. Nobody navigates to a person they have not heard of yet.
--
-- Not a trigger-maintained summary table. That is always current, and it puts a
-- second write in the path of every transaction the engine persists -- a second
-- thing to keep consistent, in a system that already documents its dual write
-- to Kafka as a known gap. Refreshing on a schedule keeps the write path exactly
-- as it is.
CREATE MATERIALIZED VIEW cardholder_summary AS
SELECT t.customer_id                    AS customer_id,
       MAX(t.customer_name)             AS customer_name,
       COUNT(*)                         AS transactions,
       SUM(t.amount)                    AS total_spend,
       MAX(t.occurred_at)               AS last_seen,
       COUNT(DISTINCT t.card_id)        AS cards
  FROM transactions t
 WHERE t.customer_id IS NOT NULL
 GROUP BY t.customer_id;

-- REFRESH MATERIALIZED VIEW CONCURRENTLY requires a unique index, and without
-- CONCURRENTLY a refresh takes an ACCESS EXCLUSIVE lock -- every reader of the
-- directory blocks for the length of the refresh. The index is therefore not an
-- optimisation, it is what makes refreshing survivable.
CREATE UNIQUE INDEX uq_cardholder_summary ON cardholder_summary (customer_id);

-- The directory's default order. Reading the first page becomes an index scan of
-- ten rows rather than a sort of every cardholder.
CREATE INDEX idx_cardholder_summary_activity
    ON cardholder_summary (transactions DESC);

-- Search, same trigram treatment V6 gave the underlying table, for the same
-- reason: people type a surname, so the query has a leading wildcard.
CREATE INDEX idx_cardholder_summary_name_trgm
    ON cardholder_summary USING gin (LOWER(customer_name) gin_trgm_ops);
CREATE INDEX idx_cardholder_summary_id_trgm
    ON cardholder_summary USING gin (LOWER(customer_id) gin_trgm_ops);
