-- Indexes, measured rather than guessed.
--
-- Every EXPLAIN below is against roughly 350 000 transactions and 200 000
-- alerts, which is what a few hours of the simulator at five per second plus a
-- seeded month of history produces. Numbers are the executor's own, not wall
-- clock, and pg_stat_user_indexes was reset and the screens driven again to
-- find which indexes the queries actually reach for.

-- ---------------------------------------------------------------------------
-- Substring search on a cardholder name
-- ---------------------------------------------------------------------------
-- idx_txn_customer_name was a btree on LOWER(customer_name), and it could
-- never have been used: people search for a surname, so the query is
-- LIKE '%nkosi%', and a btree cannot answer a leading wildcard. It measured
-- zero scans under load while the search itself fell back to scanning.
--
-- A trigram GIN index can. The worst case is a search that matches nothing --
-- every intermediate prefix while somebody types is one of those -- and it went
-- from 32ms of parallel sequential scan to 0.04ms.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DROP INDEX idx_txn_customer_name;

CREATE INDEX idx_txn_customer_name_trgm
    ON transactions USING gin (LOWER(customer_name) gin_trgm_ops);

-- The cardholder directory searches name OR reference in one predicate, and an
-- OR is only as good as its weaker side: with one trigram index the planner
-- cannot use it at all, because it still has to scan for the other half. Both
-- sides indexed makes it a bitmap OR of two index scans.
CREATE INDEX idx_txn_customer_id_trgm
    ON transactions USING gin (LOWER(customer_id) gin_trgm_ops);

-- ---------------------------------------------------------------------------
-- The cardholder directory
-- ---------------------------------------------------------------------------
-- GROUP BY customer_id with COUNT(DISTINCT card_id) over the whole table. The
-- DISTINCT is what makes it expensive: it forces a sort on
-- (customer_id, card_id), which at this size spilled 21MB to disk as an
-- external merge -- 268ms, and getting worse with every transaction.
--
-- This index is that sort, kept. The INCLUDE columns are the rest of what the
-- aggregate reads, so the scan is index-only and the GroupAggregate needs no
-- sort at all: 268ms to 86ms with nothing written to disk. 26MB, which is a
-- fair price for the screen this is.
--
-- Partial, because the aggregate only ever looks at rows that name somebody.
-- A transaction with no cardholder is one the stream did not identify -- see V4
-- -- and it is correctly absent from a directory of people.
CREATE INDEX idx_txn_customer_card
    ON transactions (customer_id, card_id)
    INCLUDE (customer_name, amount, occurred_at)
    WHERE customer_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Indexes that are now dead weight
-- ---------------------------------------------------------------------------
-- An unused index is not free. It is maintained on every insert, and inserts
-- are this system's hot path -- five transactions a second, each one a row in
-- transactions and possibly rows in fraud_alerts and alert_rule_hits. These
-- four measured zero scans while the whole UI was driven, and each has a
-- specific reason it can never be reached again.

-- V5 moved every window and every ordering over alerts onto occurred_at.
-- created_at is still stored and still returned by the API -- it is the other
-- end of the detection lag -- but nothing filters or sorts by it. 9.6MB.
DROP INDEX idx_alert_created;

-- (severity, created_at DESC), superseded by the (severity, occurred_at DESC)
-- that V5 added beside it. 13MB.
DROP INDEX idx_alert_severity;

-- uq_shadow_transaction_rule is (transaction_id, rule_id) and leads with
-- transaction_id, so it already answers everything this did, including backing
-- the ON DELETE CASCADE from transactions.
DROP INDEX idx_shadow_txn;

-- Nothing queries shadow hits by time. They are read per-rule, to total what a
-- shadow rule would have caught.
DROP INDEX idx_shadow_created;

-- fraud_rules holds seven rows. Any index on it is slower than reading the
-- table, and the planner knows it: zero scans, while the primary key took
-- 39 000.
DROP INDEX idx_rules_state;

-- Deliberately kept, though they also measured zero scans in that sample:
-- idx_hits_rule and idx_shadow_rule back the foreign keys to fraud_rules, and
-- idx_alert_status backs a count the dashboard runs every five seconds. The
-- planner sequentially scans for that count because 99% of alerts are OPEN, so
-- the partial index covers almost the whole table -- but that ratio is a
-- property of this demo's data, not of the schema, and on a store where most
-- alerts have been dispositioned the index is the right plan.
