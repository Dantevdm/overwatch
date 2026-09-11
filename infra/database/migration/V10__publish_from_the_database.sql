-- The transactional outbox: make the alert and its publication one decision.
--
-- What was wrong. TransactionProcessor.process() is @Transactional, and inside
-- it the engine saved the alert and then called kafka.send(). Two stores, one
-- method, no shared commit -- the dual write this project has documented as a
-- known gap since V7's comment referred to it. Two failures follow from it and
-- neither is theoretical:
--
--   1. The send goes out, then the transaction rolls back. Downstream now has a
--      notification for an alert that does not exist in the database. A case
--      management system opens a case nobody can look up.
--   2. The transaction commits, then the send fails. The detection is durable
--      and the notification is lost. This one was at least visible -- the
--      fraud.alerts.publish.failed counter exists precisely because of it --
--      but a counter records the loss, it does not prevent it.
--
-- Case 1 was the dangerous one, because kafka.send() returns a future and the
-- record is handed to the producer's buffer immediately: nothing about the
-- rollback reaches back to un-send it.
--
-- What this does instead. The engine writes a row here in the same transaction
-- as the alert. One commit, both facts, atomic by construction -- if the alert
-- exists, so does its intent to publish, and if it rolls back, so does that.
-- A poller then reads unpublished rows and sends them, marking each one only
-- after the broker acknowledges it.
--
-- The trade, stated plainly, because this is not free:
--
--   * Delivery becomes at-least-once rather than at-most-once. A row can be
--     sent and the process can die before the row is marked, and the next poll
--     sends it again. Consumers must be idempotent -- which is why the alert
--     carries its own UUID as an identity, and why the engine's own consumer
--     already guards on the transaction id.
--   * Publication is no longer immediate. It is one poll interval behind, which
--     is 200ms here.
--   * It is a second write in the path of every alert. Alerts are a few percent
--     of traffic rather than all of it, so this is a much smaller cost than a
--     trigger-maintained summary would have been (see V7).
CREATE TABLE alert_outbox (
    id              BIGSERIAL    PRIMARY KEY,

    -- Which alert this is about, kept for tracing a message back to what
    -- produced it. Not a foreign key: the outbox has to survive its aggregate
    -- being deleted, and `ow reset` deletes alerts. A FK here would either
    -- block that reset or cascade away evidence of what had been published.
    aggregate_id    UUID         NOT NULL,

    -- The destination and the partition key, stored rather than derived. The
    -- poller must not have to know how a payload maps to a topic: the decision
    -- was made at write time, by the code that had the context to make it.
    topic           VARCHAR(128) NOT NULL,
    message_key     VARCHAR(128) NOT NULL,

    -- The message as it will be sent, serialised at write time. Storing the
    -- document rather than re-serialising at publish time means what is sent is
    -- what was decided when the transaction committed, even if the code that
    -- shapes the payload has been redeployed in between.
    --
    -- jsonb rather than text, with the trade stated: jsonb stores a parsed
    -- document, so whitespace and key order are not preserved and the published
    -- bytes are not byte-identical to what was written. What is preserved is the
    -- document. In exchange, anything that is not valid JSON is rejected here
    -- rather than by a consumer, and a stuck backlog can be queried.
    payload         JSONB        NOT NULL,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- NULL means "not yet on the topic". This is the whole state machine.
    published_at    TIMESTAMPTZ,

    -- Attempts and the last error, so a row that cannot be published says why
    -- instead of just sitting there. A backlog with no explanation is the
    -- failure mode of every outbox that only tracks a boolean.
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- The poller's only query: the oldest unpublished rows. Partial, so it indexes
-- the backlog and not the archive -- after a day's run the published rows vastly
-- outnumber the pending ones, and an index over both would grow without bound
-- while answering a question that is only ever asked about the small part.
CREATE INDEX idx_alert_outbox_pending
    ON alert_outbox (id)
 WHERE published_at IS NULL;

-- For pruning, and for the "how far behind is the outbox" panel.
CREATE INDEX idx_alert_outbox_published_at
    ON alert_outbox (published_at)
 WHERE published_at IS NOT NULL;

COMMENT ON TABLE alert_outbox IS
    'Transactional outbox. Rows are written in the same transaction as the alert they describe, and published to Kafka by a poller.';
COMMENT ON COLUMN alert_outbox.published_at IS
    'NULL until the broker has acknowledged the record. Set once, never cleared.';
