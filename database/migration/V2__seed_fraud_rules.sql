-- Default rule set, tuned for South African card traffic in ZAR.
--
-- These are configuration, not test fixtures: they are the rules the engine runs
-- with out of the box. Thresholds are editable through the API afterwards, and
-- changes there are not overwritten by this migration.
--
-- Weights are hand-set and deliberately conservative. Nothing except a single
-- high-value hit clears CRITICAL (0.80) on its own; most genuine alerts come
-- from two or more rules agreeing, which is the behaviour we want.

INSERT INTO fraud_rules (rule_type, name, description, state, weight, parameters) VALUES

('HIGH_VALUE',
 'High value transaction',
 'Flags transactions above a rand threshold. The single strongest signal, but on its own it also catches legitimate large purchases, so it sits below the CRITICAL line.',
 'ENABLED', 0.40,
 '{"threshold": 50000, "currency": "ZAR"}'::JSONB),

('VELOCITY',
 'Card velocity',
 'Flags a card used more times than expected inside a short window — the classic signature of a stolen card being tested and drained quickly.',
 'ENABLED', 0.35,
 '{"maxCount": 5, "windowMinutes": 10}'::JSONB),

('CROSS_BORDER',
 'Cross-border transaction',
 'Flags acquiring countries other than the card home country. Ordinary for travellers, which is why it accompanies rather than carries an alert.',
 'ENABLED', 0.30,
 '{"homeCountry": "ZA", "allowedCountries": ["ZA"]}'::JSONB),

('CATEGORY_WATCHLIST',
 'High-risk merchant category',
 'Flags categories over-represented in confirmed fraud — crypto exchanges, gambling and forex, where funds move fast and are hard to recover.',
 'ENABLED', 0.25,
 '{"categories": ["crypto", "gambling", "forex"]}'::JSONB),

('LATE_NIGHT',
 'Late night activity',
 'Flags transactions in the small hours, South African time. Weak on its own — plenty of legitimate online spending happens at 02:00 — but meaningful alongside another signal.',
 'ENABLED', 0.20,
 '{"startHour": 1, "endHour": 4, "timezone": "Africa/Johannesburg"}'::JSONB),

('ROUND_AMOUNT',
 'Suspiciously round amount',
 'Flags large round numbers. Genuine retail spend rarely lands exactly on R10 000; card testing and cash-out attempts frequently do.',
 'ENABLED', 0.15,
 '{"floor": 5000, "multipleOf": 1000}'::JSONB),

-- Shipped in SHADOW deliberately. It evaluates every transaction and records
-- what it would have caught in shadow_rule_hits, but raises no alerts. That
-- gives the dashboard something real to show for shadow mode on day one, and
-- demonstrates the intended workflow: observe first, promote to ENABLED once the
-- false-positive rate is acceptable.
('AMOUNT_DEVIATION',
 'Amount deviation from card baseline',
 'Flags a transaction far above what this card normally spends. Under evaluation — the baseline needs enough history before this can be trusted to alert.',
 'SHADOW', 0.30,
 '{"multipleOfAverage": 5, "minimumHistory": 10}'::JSONB);
