-- Overwatch baseline schema.
--
-- Three concerns: the transactions we observe, the rules we apply, and what
-- those rules produced. Shadow-rule output lives in its own table because a
-- shadow hit deliberately has no alert to hang off — see shadow_rule_hits.

-- Fixed-width codes use VARCHAR, not CHAR. Two reasons: Hibernate maps a
-- String @Column(length = n) to varchar and rejects bpchar under
-- ddl-auto: validate, and PostgreSQL's CHAR pads values to the declared width,
-- so a two-letter code in CHAR(3) silently becomes 'ZA ' on the way back out.

-- ---------------------------------------------------------------------------
-- Transactions
-- ---------------------------------------------------------------------------
CREATE TABLE transactions (
    id                UUID           PRIMARY KEY,
    card_id           VARCHAR(64)    NOT NULL,
    amount            NUMERIC(15, 2) NOT NULL,
    currency          VARCHAR(3)     NOT NULL DEFAULT 'ZAR',
    merchant_name     VARCHAR(255)   NOT NULL,
    merchant_category VARCHAR(64)    NOT NULL,
    country_code      VARCHAR(2)     NOT NULL,
    channel           VARCHAR(16)    NOT NULL,
    occurred_at       TIMESTAMPTZ    NOT NULL,
    metadata          JSONB          NOT NULL DEFAULT '{}'::JSONB,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_txn_amount_positive CHECK (amount > 0),
    CONSTRAINT chk_txn_channel CHECK (channel IN ('POS', 'ONLINE', 'ATM', 'MOBILE'))
);

COMMENT ON TABLE  transactions IS 'Categorized card transactions consumed from the event stream.';
COMMENT ON COLUMN transactions.card_id IS 'Tokenised card reference. Never a real PAN.';
COMMENT ON COLUMN transactions.occurred_at IS 'When the transaction happened, UTC. Rules convert to SAST where local time matters.';

-- The velocity rule asks "how many transactions on this card in the last N
-- minutes", which is exactly this index. Without it that rule table-scans on
-- every single transaction and the pipeline falls over under load.
CREATE INDEX idx_txn_card_occurred ON transactions (card_id, occurred_at DESC);

-- Dashboard and stats queries are time-ordered.
CREATE INDEX idx_txn_occurred      ON transactions (occurred_at DESC);
CREATE INDEX idx_txn_category      ON transactions (merchant_category);

-- ---------------------------------------------------------------------------
-- Rule configuration
-- ---------------------------------------------------------------------------
CREATE TABLE fraud_rules (
    id          BIGSERIAL      PRIMARY KEY,
    rule_type   VARCHAR(64)   NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    description TEXT,
    state       VARCHAR(16)   NOT NULL DEFAULT 'ENABLED',
    weight      NUMERIC(3, 2) NOT NULL,
    parameters  JSONB         NOT NULL DEFAULT '{}'::JSONB,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_rule_state  CHECK (state IN ('ENABLED', 'DISABLED', 'SHADOW')),
    CONSTRAINT chk_rule_weight CHECK (weight >= 0 AND weight <= 1),
    CONSTRAINT uq_rule_name    UNIQUE (name)
);

COMMENT ON TABLE  fraud_rules IS 'Rule configuration. Rules are data, so a threshold change is an API call rather than a redeploy.';
COMMENT ON COLUMN fraud_rules.state IS 'ENABLED evaluates and alerts; SHADOW evaluates and records without alerting; DISABLED is skipped entirely.';
COMMENT ON COLUMN fraud_rules.weight IS 'Contribution to the accumulated risk score, 0.00-1.00.';
COMMENT ON COLUMN fraud_rules.parameters IS 'Rule-specific configuration. JSONB so a new rule type needs no schema change.';

-- The engine loads evaluable rules on a refresh cycle; this covers that read.
CREATE INDEX idx_rules_state ON fraud_rules (state) WHERE state <> 'DISABLED';

-- ---------------------------------------------------------------------------
-- Alerts
-- ---------------------------------------------------------------------------
CREATE TABLE fraud_alerts (
    id             UUID           PRIMARY KEY,
    transaction_id UUID           NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    risk_score     NUMERIC(3, 2)  NOT NULL,
    severity       VARCHAR(16)    NOT NULL,
    status         VARCHAR(16)    NOT NULL DEFAULT 'OPEN',
    -- Denormalised from the transaction so alert lists need no join.
    amount         NUMERIC(15, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    resolved_at    TIMESTAMPTZ,

    CONSTRAINT chk_alert_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT chk_alert_status   CHECK (status IN ('OPEN', 'REVIEWING', 'CONFIRMED', 'CLEARED')),
    CONSTRAINT chk_alert_score    CHECK (risk_score >= 0 AND risk_score <= 1)
);

COMMENT ON COLUMN fraud_alerts.status IS 'Analyst disposition. CONFIRMED and CLEARED are what make the false-positive rate computable.';

CREATE INDEX idx_alert_created  ON fraud_alerts (created_at DESC);
CREATE INDEX idx_alert_status   ON fraud_alerts (status) WHERE status IN ('OPEN', 'REVIEWING');
CREATE INDEX idx_alert_severity ON fraud_alerts (severity, created_at DESC);
CREATE INDEX idx_alert_txn      ON fraud_alerts (transaction_id);

-- ---------------------------------------------------------------------------
-- Which rules contributed to each alert
-- ---------------------------------------------------------------------------
CREATE TABLE alert_rule_hits (
    id        BIGSERIAL      PRIMARY KEY,
    alert_id  UUID          NOT NULL REFERENCES fraud_alerts (id) ON DELETE CASCADE,
    rule_id   BIGINT        REFERENCES fraud_rules (id) ON DELETE SET NULL,
    rule_type VARCHAR(64)   NOT NULL,
    weight    NUMERIC(3, 2) NOT NULL,
    reason    TEXT          NOT NULL,
    evidence  JSONB         NOT NULL DEFAULT '{}'::JSONB
);

COMMENT ON TABLE  alert_rule_hits IS 'Every rule that fired for an alert, not just the strongest. Three weak hits tell a different story from one strong one at the same score.';
COMMENT ON COLUMN alert_rule_hits.rule_type IS 'Denormalised so hit history survives deletion of the rule row.';
COMMENT ON COLUMN alert_rule_hits.reason IS 'Human-readable, written for the analyst reading the dashboard.';

CREATE INDEX idx_hits_alert ON alert_rule_hits (alert_id);
CREATE INDEX idx_hits_rule  ON alert_rule_hits (rule_id);
CREATE INDEX idx_hits_type  ON alert_rule_hits (rule_type);

-- ---------------------------------------------------------------------------
-- Shadow-mode output
-- ---------------------------------------------------------------------------
-- A shadow rule fires without raising an alert, so its hits have no alert row to
-- attach to. They land here instead, which is what makes the question "what
-- would this rule have caught last week?" answerable before the rule goes live.
CREATE TABLE shadow_rule_hits (
    id             BIGSERIAL      PRIMARY KEY,
    transaction_id UUID          NOT NULL REFERENCES transactions (id) ON DELETE CASCADE,
    rule_id        BIGINT        REFERENCES fraud_rules (id) ON DELETE CASCADE,
    rule_type      VARCHAR(64)   NOT NULL,
    weight         NUMERIC(3, 2) NOT NULL,
    reason         TEXT          NOT NULL,
    evidence       JSONB         NOT NULL DEFAULT '{}'::JSONB,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE shadow_rule_hits IS 'Hits from rules in SHADOW state. No alert is raised; this is the evidence used to decide whether to promote the rule.';

CREATE INDEX idx_shadow_rule    ON shadow_rule_hits (rule_id, created_at DESC);
CREATE INDEX idx_shadow_txn     ON shadow_rule_hits (transaction_id);
CREATE INDEX idx_shadow_created ON shadow_rule_hits (created_at DESC);
