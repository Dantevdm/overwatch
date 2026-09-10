-- Give cardholder_summary the column types its entity declares.
--
-- V7 let every column's type fall out of whatever its aggregate happened to
-- produce, which is not the same as choosing them:
--
--   MAX(customer_name)        varchar(128) -> text
--   SUM(amount)               numeric(12,2) -> numeric      (unconstrained)
--   COUNT(DISTINCT card_id)   -> bigint                     (always fits an int)
--
-- CardholderSummaryEntity declares varchar(128), numeric(15,2) and int, so the
-- entity and the view disagreed about all three. Hibernate's ddl-auto: validate
-- tolerates it and the screen works, which is exactly why it went unnoticed —
-- CI's schema-mapping check found it the moment that check learned to look at
-- materialised views at all.
--
-- Fixed in the view rather than by loosening the entity, because a derived
-- column's type should be a decision. "The number of cards a person holds is an
-- int" and "a total is money to two decimal places" are statements about the
-- domain; inheriting bigint and unconstrained numeric from the aggregate
-- functions is an accident of SQL, and one that would quietly propagate into
-- any consumer generated from this schema.
--
-- A materialised view's column types cannot be altered in place, so this drops
-- and recreates it, indexes and all. The view holds no source data — it is
-- rebuilt from transactions — so there is nothing to preserve, and the
-- scheduled refresher repopulates it within thirty seconds either way. Created
-- WITH DATA so it is correct immediately rather than empty until then.
DROP MATERIALIZED VIEW IF EXISTS cardholder_summary;

CREATE MATERIALIZED VIEW cardholder_summary AS
SELECT t.customer_id                                        AS customer_id,
       CAST(MAX(t.customer_name) AS VARCHAR(128))           AS customer_name,
       COUNT(*)                                             AS transactions,
       CAST(SUM(t.amount) AS NUMERIC(15, 2))                AS total_spend,
       MAX(t.occurred_at)                                   AS last_seen,
       -- COUNT returns bigint and nothing can make it return int, so the cast
       -- is the narrowing itself. Safe by construction: this counts the
       -- distinct cards held by one person.
       CAST(COUNT(DISTINCT t.card_id) AS INTEGER)           AS cards
  FROM transactions t
 WHERE t.customer_id IS NOT NULL
 GROUP BY t.customer_id
WITH DATA;

-- Recreated verbatim from V7. REFRESH MATERIALIZED VIEW CONCURRENTLY requires
-- the unique index, and without CONCURRENTLY a refresh takes an ACCESS
-- EXCLUSIVE lock and every reader of the directory blocks for its duration —
-- so this index is not an optimisation, it is what makes refreshing survivable.
CREATE UNIQUE INDEX uq_cardholder_summary ON cardholder_summary (customer_id);

CREATE INDEX idx_cardholder_summary_activity
    ON cardholder_summary (transactions DESC);

CREATE INDEX idx_cardholder_summary_name_trgm
    ON cardholder_summary USING gin (LOWER(customer_name) gin_trgm_ops);
CREATE INDEX idx_cardholder_summary_id_trgm
    ON cardholder_summary USING gin (LOWER(customer_id) gin_trgm_ops);
