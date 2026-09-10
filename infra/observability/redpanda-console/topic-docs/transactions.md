# transactions

Every card transaction entering the system. This is the pipeline's front door:
`transaction-simulator` produces here, `fraud-engine` is the only consumer.

In production this topic would be fed by the acquiring switch rather than a
simulator. Nothing downstream knows the difference, which is the point of the
seam being a topic.

## Contract

| | |
|---|---|
| **Key** | `cardId` (string, e.g. `card-01034`) |
| **Value** | JSON — `com.overwatch.common.domain.Transaction` |
| **Producer** | `transaction-simulator` (idempotent producer) |
| **Consumers** | `fraud-engine`, consumer group `fraud-engine` |
| **Partitions** | 1 in this deployment |
| **Retention** | 7 days, `cleanup.policy=delete` |

### Why the key is the card, not the transaction

Kafka guarantees ordering within a partition, and keying by `cardId` puts all of
one card's traffic on the same partition in the order it happened. The velocity
rule asks "how many transactions on this card in the last ten minutes?" — a
question that is only answerable correctly if the answer cannot be split across
partitions consumed at different speeds.

It also means partition count is bounded by how evenly cards distribute, not by
throughput. A single card cannot be parallelised, which is the correct
trade-off: a card is exactly the unit whose history must stay ordered.

## Payload

```json
{
  "id": "8935a597-5dd2-4bc4-8578-cab2c0a23f0f",
  "cardId": "card-01034",
  "amount": 636.55,
  "currency": "ZAR",
  "merchantName": "Mr Price Eastgate",
  "merchantCategory": "retail",
  "countryCode": "ZA",
  "channel": "POS",
  "timestamp": 1789027079.205087466,
  "metadata": { "issuingBank": "African Bank", "city": "Bloemfontein" }
}
```

| Field | Type | Notes |
|---|---|---|
| `id` | UUID | **The idempotency key.** See below. |
| `cardId` | string | Also the partition key |
| `amount` | decimal | Always positive — a DB `CHECK` enforces it |
| `currency` | string(3) | Always `ZAR` today; the column exists so multi-currency is additive |
| `merchantName` | string | Real South African merchants |
| `merchantCategory` | string | `groceries`, `fuel`, `retail`, … `crypto`, `gambling`, `forex` are watchlisted |
| `countryCode` | string(2) | Compared against the card's home country (`ZA`) by the cross-border rule |
| `channel` | enum | `POS`, `ONLINE`, `ATM`, `MOBILE` |
| `timestamp` | number | **Epoch seconds with a nanosecond fraction, not ISO-8601** |
| `metadata` | object | Free-form; stored as JSONB |

### Two things that surprise people

**`timestamp` is a float, not a string.** Jackson serialises `java.time.Instant`
as epoch-seconds-with-fraction by default and that has been left alone rather
than papered over, so anything consuming this topic must parse a number. If you
are writing a new consumer, that is the field that will catch you.

**`id` is assigned by the producer, and the engine deduplicates on it.** Kafka
delivers at least once, so on a rebalance or a crash between processing and the
offset commit this topic will hand you the same transaction twice. The engine
treats `id` as an idempotency key and skips a repeat — visible as
`transactions_redelivered_total`. A new consumer must do the same or it will
double-count; the topic makes no promise of exactly-once.

## Traffic shape

Not a flat line, deliberately. Volume follows a 24-point diurnal curve
interpolated to the minute in SAST and normalised to average 1.0, so the
configured rate is the daily *mean*. Batch sizes are drawn from a Poisson
distribution, and merchant categories from a weighted draw (groceries 22, fuel
14, retail and restaurant 11, down to liquor 3) rather than uniformly.

A share of traffic — 8% by default — is deliberately shaped to trip rules. You
can also inject one specific pattern on demand:
`POST /api/simulator/inject/COMPOUND`.
