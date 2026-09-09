# Overwatch

A fraud rule engine service. Overwatch ingests a stream of categorized card
transactions, evaluates each one against a set of configurable fraud rules, scores
the result, persists any alerts, and exposes everything through a REST API and a
real-time dashboard.

Transactions are South African — ZAR amounts, local merchants and banks, SAST
timestamps — because rules can only be judged sensible against data that looks real.

> **Status:** in development. See [`docs/planning/PROJECT-PLAN.md`](docs/planning/PROJECT-PLAN.md)
> for the task list and progress.

---

## Quickstart

```bash
git clone <repo-url> overwatch
cd overwatch
docker compose up
```

That is the whole setup. The stack comes up with a seeded rule set, a simulator
already producing transactions, and Grafana dashboards already provisioned.

| Surface | URL |
|---|---|
| Dashboard | http://localhost:5173 |
| API (Swagger) | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

---

## How it works

```
transaction-simulator ──▶ Redpanda ──▶ fraud-engine ──▶ PostgreSQL
                        (transactions)      │                 ▲
                                            │                 │
                                            └──▶ fraud-alerts │
                                                              │
                              Dashboard ◀── fraud-api (BFF) ──┘
```

The simulator publishes transaction events. The engine consumes them, runs each
through the enabled rules, accumulates a risk score, and writes alerts. The BFF
reads from PostgreSQL and serves the dashboard. All three services expose Actuator
metrics that Prometheus scrapes and Grafana renders.

Splitting ingestion, detection and serving means the engine can be scaled
independently of the API, and a slow consumer never applies back-pressure to whoever
is querying alerts.

### Services

| Service | Port | Responsibility |
|---|---|---|
| `transaction-simulator` | 8081 | Generates realistic ZAR transaction events |
| `fraud-engine` | 8082 | Rule evaluation, scoring, alert persistence |
| `fraud-api` | 8080 | REST API over transactions, alerts, rules and stats |
| `overwatch-ui` | 5173 | React + Vite dashboard |

---

## The rule engine

Rules are configuration, not code paths. Each rule type is a class implementing a
single interface; each *active* rule is a row in the database whose parameters are
JSONB. Changing a threshold is an API call, not a redeploy.

```java
public interface FraudRule {
    String getRuleType();
    Optional<RuleHit> evaluate(Transaction txn, JsonNode params);
}
```

The orchestrator loads the enabled rule configurations, runs the matching
implementations, and accumulates a weighted risk score:

```java
@Component
public class RuleEngine {

    private final List<FraudRule> rules;

    public Evaluation process(Transaction txn) {
        List<RuleHit> hits = ruleConfigs.stream()
                .filter(RuleConfig::isActive)
                .map(config -> findRule(config.getType())
                        .evaluate(txn, config.getParameters()))
                .flatMap(Optional::stream)
                .toList();

        return Evaluation.of(txn, hits);   // score = Σ weights, severity from score
    }
}
```

Adding a rule type means adding one class. Spring injects it into the list; no
registry to update, no switch statement to extend.

### Shipped rules

| Rule | Trigger | Default | Weight |
|---|---|---|---|
| High Value | Amount over threshold | R50,000 | 0.40 |
| Velocity | N transactions on a card in a window | 5 in 10 min | 0.35 |
| Late Night | Timestamp in the small hours (SAST) | 01:00–04:00 | 0.20 |
| Round Amount | Suspiciously round value | ≥ R5,000, multiple of 1,000 | 0.15 |
| Cross-Border | Country differs from card's home country | home `ZA` | 0.30 |
| Category Mismatch | Merchant category on the watchlist | crypto, gambling, forex | 0.25 |

---

## Beyond the brief

Four things Overwatch does that a direct reading of the brief would not produce.
Each exists because a real fraud team needs it.

**Shadow mode.** A rule can be `ENABLED`, `DISABLED`, or `SHADOW`. A shadow rule
evaluates every transaction and records what it would have flagged, but raises no
alerts. This is how new rules are tuned in production — run them silently against
live traffic first, look at what they would have caught, then switch them on.

**Composite risk scoring.** Rules do not return true or false. Each contributes a
weighted score, and the total determines severity. A transaction tripping Late Night
and Round Amount scores 0.5 — worth a look, not worth blocking a card over. Fraud
detection is probabilistic and the model reflects that.

**Rule performance statistics.** `GET /api/rules/{id}/stats` returns fire count, share
of total alerts, mean score contribution and false-positive rate. This is what you
need to decide whether a rule is earning its keep, and it is the first thing that goes
missing in a rules engine nobody instrumented.

**Replay / what-if.** `POST /api/rules/replay` takes a candidate configuration and a
time window, replays stored transactions through it, and returns the alerts it *would*
have generated — writing nothing. "What if the high-value threshold were R30,000?"
becomes a measurement instead of an argument.

---

## Observability

Prometheus scrape targets and Grafana dashboards are committed as provisioning files
and mounted into the containers, so the dashboards exist the moment the stack starts.

The metrics are business metrics, not just request counters:

| Metric | What it tells you |
|---|---|
| `fraud_alerts_by_rule_total` | Which rules actually fire, and how often |
| `fraud_risk_score` | Distribution of risk scores (histogram) |
| `fraud_detection_latency_ms` | Ingest-to-alert latency, p50 / p95 / p99 |
| `fraud_amount_flagged_zar` | Total ZAR value under alert |
| `transactions_processed_total` | Throughput, by merchant category and channel |
| `fraud_shadow_hits_total` | What shadow rules would have caught |

Three dashboards ship with the stack: pipeline health, fraud overview, and rule
performance.

---

## Repository layout

```
overwatch/
├── docs/
│   ├── architecture/            # Architecture document and dashboard mockup
│   ├── design-system/           # i1 design system — UI kit and extracted tokens.css
│   └── planning/                # Project plan, task list, deferred decisions
├── common/                      # Shared domain records and events
├── transaction-simulator/       # Service 1 — event generation
├── fraud-engine/                # Service 2 — rule evaluation
├── fraud-api/                   # Service 3 — BFF
├── overwatch-ui/                # React + Vite dashboard
├── observability/
│   ├── prometheus/              # Scrape configuration
│   └── grafana/                 # Provisioned datasources and dashboards
├── postman/                     # Collection and environment
└── docker-compose.yml
```

---

## Tech stack

| Choice | Why |
|---|---|
| Java 21 + Spring Boot 3.3 | Records, pattern matching and virtual threads; the ecosystem's defaults for Kafka, JPA and metrics are all first-party. |
| Redpanda | Kafka API compatible, single binary, no ZooKeeper. Same code, a fraction of the container footprint. |
| PostgreSQL 16 | JSONB makes rule parameters schemaless without giving up relational integrity for everything else. |
| React 18 + Vite | Fast dev loop, no framework overhead for what is a dashboard. |
| Prometheus + Grafana | The default pairing for Micrometer, and provisioning-as-code means no manual setup. |

Specialised financial stores (TigerBeetle and similar) were considered and set aside:
they are built for double-entry ledger throughput, which is not this problem. Overwatch
stores events and alerts, and PostgreSQL does that well while staying familiar to
anyone reading the code.

---

## Documentation

| Document | Contents |
|---|---|
| [`docs/architecture/overwatch-architecture.html`](docs/architecture/overwatch-architecture.html) | Full architecture — diagram, schema, API contracts, Docker topology |
| [`docs/architecture/overwatch-dashboard-mockup.html`](docs/architecture/overwatch-dashboard-mockup.html) | Working dashboard mockup |
| [`docs/planning/PROJECT-PLAN.md`](docs/planning/PROJECT-PLAN.md) | Task list, progress, deferred decisions |
| [`docs/design-system/tokens.css`](docs/design-system/tokens.css) | Design tokens the UI imports |

---

## Known gaps

Deliberate omissions, recorded rather than hidden. The full list with reasoning is in
the project plan.

- **No authentication.** The BFF is the natural place for it; out of scope for the brief.
- **Single currency.** Everything is ZAR. The `currency` column exists so multi-currency is additive.
- **Hand-set rule weights.** A learned model would be more interesting and less verifiable.
- **Polling, not WebSockets.** 5-second polling is sufficient here and considerably simpler.
