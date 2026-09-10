-- Make "one alert per transaction" true at the schema level, not just intended.
--
-- Kafka delivers at least once. On a consumer rebalance, or a crash between
-- processing a record and committing its offset, the same transaction is
-- redelivered — and nothing here stopped that producing a second alert. The
-- transaction row itself was safe, because its id is the primary key and JPA
-- merges on it, but fraud_alerts minted a fresh UUID every time and
-- shadow_rule_hits has an auto-increment key, so both would happily duplicate.
--
-- The engine now recognises a redelivery and skips it, which is the real fix.
-- These constraints are the backstop: they hold even if two engine instances
-- race the same record, where an application-level check cannot. The loser of
-- that race fails its insert, the transaction rolls back, Kafka redelivers, and
-- the second attempt sees the transaction already stored and skips it.
--
-- Both statements deduplicate before adding the constraint, so this migration
-- applies to an existing volume that already accumulated duplicates rather
-- than failing and leaving the schema half-migrated.

-- Keep the earliest alert per transaction. alert_rule_hits is ON DELETE
-- CASCADE, so the discarded alerts take their hit rows with them.
DELETE FROM fraud_alerts a
 WHERE a.id <> (
        SELECT b.id FROM fraud_alerts b
         WHERE b.transaction_id = a.transaction_id
         ORDER BY b.created_at, b.id
         LIMIT 1);

ALTER TABLE fraud_alerts
    ADD CONSTRAINT uq_alert_transaction UNIQUE (transaction_id);

-- Same for shadow hits, whose natural key is the transaction and the rule.
DELETE FROM shadow_rule_hits s
 WHERE s.id <> (
        SELECT t.id FROM shadow_rule_hits t
         WHERE t.transaction_id = s.transaction_id
           AND t.rule_id IS NOT DISTINCT FROM s.rule_id
         ORDER BY t.id
         LIMIT 1);

-- NULLS NOT DISTINCT because rule_id is nullable and the default treats every
-- NULL as unique, which would leave exactly the duplicates this is here to
-- prevent for any hit whose rule is not a database row.
ALTER TABLE shadow_rule_hits
    ADD CONSTRAINT uq_shadow_transaction_rule UNIQUE NULLS NOT DISTINCT (transaction_id, rule_id);
