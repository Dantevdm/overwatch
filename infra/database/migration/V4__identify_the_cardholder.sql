-- Give a transaction a person.
--
-- Until now the finest identity in the system was the card, which is enough to
-- run every rule but not enough to answer the question an investigator actually
-- asks: not "is this transaction odd" but "who is this, and what does their
-- spending look like". A card is an instrument. People carry several, and a
-- pattern spread across two cards belonging to one person is invisible when the
-- card is the only handle you have.
--
-- Deliberately NOT a customers table with a foreign key. This system observes a
-- payment stream; it does not own customer master data, and inventing a master
-- table here would claim an authority it has not got and cannot keep in step
-- with whatever really owns it. The stream carries the cardholder reference the
-- acquirer put on the authorisation, and the 360 view is an aggregation over
-- what was observed -- which is also why a customer with no transactions
-- correctly does not exist here.
--
-- Nullable, because it has to be. Rows already in this table were written before
-- the field existed, and a NOT NULL with a backfilled placeholder would make
-- "we did not know" indistinguishable from "this is who it was".

ALTER TABLE transactions
    ADD COLUMN customer_id   VARCHAR(64),
    ADD COLUMN customer_name VARCHAR(128);

COMMENT ON COLUMN transactions.customer_id IS
    'Tokenised cardholder reference carried on the authorisation. Never a national ID.';
COMMENT ON COLUMN transactions.customer_name IS
    'Cardholder display name as presented on the authorisation. Synthetic in this system.';

-- The 360 view's own index: every panel on it is "this customer, most recent
-- first". Without it, opening one profile sequentially scans the whole table,
-- which is fine at ten thousand rows and not fine at ten million.
CREATE INDEX idx_txn_customer_occurred ON transactions (customer_id, occurred_at DESC);

-- Customer search is a prefix match on the display name, case-insensitively.
-- The expression is what the query uses, so the index has to be on the
-- expression rather than the bare column or it will not be chosen.
CREATE INDEX idx_txn_customer_name ON transactions (LOWER(customer_name));
