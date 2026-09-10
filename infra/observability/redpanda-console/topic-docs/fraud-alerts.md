# fraud-alerts

Alerts raised by the rule engine. `fraud-engine` produces here after it has
already written the alert to PostgreSQL; nothing in this stack consumes the
topic.

That is intentional rather than unfinished. The topic is the seam where
notification, case management and anything else subscribes without the engine
knowing they exist. An alert is published only if it was persisted first, so
**the database is the record and this topic is the notification.**

## Contract

| | |
|---|---|
| **Key** | `transactionId` (UUID string) |
| **Value** | JSON — `com.overwatch.common.domain.FraudAlert` |
| **Producer** | `fraud-engine` |
| **Consumers** | none in this deployment |
| **Partitions** | 1 in this deployment |
| **Retention** | 7 days, `cleanup.policy=delete` |

Keyed by transaction, not by card: one transaction yields at most one alert (a
unique constraint enforces it), so the key is the natural identity of the event
and a redelivered alert lands on the same partition as the original.

## Payload

```json
{
  "id": "97583607-51c0-4798-bc99-a68d852f5195",
  "transactionId": "07ca360a-431c-4c98-b144-4730d50e6cc3",
  "riskScore": 0.35,
  "severity": "MEDIUM",
  "hits": [
    {
      "ruleType": "VELOCITY",
      "ruleId": 2,
      "weight": 0.35,
      "reason": "6 transactions on this card in 10 minutes, limit is 5",
      "evidence": { "count": 6, "maxCount": 5, "windowMinutes": 10 },
      "shadow": false
    }
  ],
  "status": "OPEN",
  "amount": 703.99,
  "currency": "ZAR",
  "createdAt": 1789027080.9476857
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | The alert's own identity |
| `transactionId` | UUID | Joins back to `transactions`; also the partition key |
| `riskScore` | 0.0–1.0 | Sum of contributing weights, **capped at 1.0** |
| `severity` | enum | Derived from the score, not set independently — see below |
| `hits` | array | **Every** rule that contributed, not just the strongest |
| `status` | enum | `OPEN` on publish; an analyst moves it on afterwards, and that change is *not* published here |
| `amount` / `currency` | decimal / string | Denormalised from the transaction so a consumer needs no join to triage |
| `createdAt` | number | Epoch seconds with a nanosecond fraction, as on `transactions` |

### Score and severity

Rules do not return true or false. Each contributes a weight and the total
decides severity:

| Score | Severity |
|---|---|
| ≥ 0.80 | `CRITICAL` |
| ≥ 0.60 | `HIGH` |
| ≥ 0.45 | `MEDIUM` |
| ≥ 0.30 | `LOW` |
| < 0.30 | no alert — the threshold |

The bottom band starts exactly where alerting starts. It has to: MEDIUM used to
begin at 0.30 as well, which meant no alert on this topic could ever carry
`LOW`.

So an alert always scores at least 0.30, and the sum of `hits[].weight` can
exceed `riskScore` because of the cap. A compound fraud pattern trips five rules
summing to 1.30 and reports 1.0 — if you are reconciling the numbers, that is
why they do not add up.

### Shadow hits are not here

A rule in `SHADOW` state evaluates every transaction and records what it *would*
have flagged without raising an alert. Those hits never reach this topic; they
go to the `shadow_rule_hits` table and the `fraud_shadow_hits_total` metric. A
`hits[]` entry on this topic always has `shadow: false`.

That is what makes shadow mode safe to run against live traffic: a subscriber
here cannot accidentally act on a rule that is still being evaluated.

## If you are writing a consumer

**Deduplicate on `transactionId`.** Delivery is at least once, so you will see
repeats after a rebalance.

**Do not treat absence as "no fraud".** The alert is durable in PostgreSQL
before it is published, and a failed publish is counted as
`fraud_alerts_publish_failed_total` rather than retried indefinitely — a lost
publish costs a notification, not a detection. Reconcile against the database if
you need completeness.

**A known gap worth knowing.** The publish currently happens inside the same
transaction that writes the alert, and the send is asynchronous, so a rollback
after the send is initiated can leave a message here for an alert that does not
exist in the database. The fix is a transactional outbox. Until then, the
database is authoritative and this topic can very occasionally be ahead of it.
