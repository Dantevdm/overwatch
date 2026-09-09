# Overwatch

[![CI](https://github.com/Dantevdm/overwatch/actions/workflows/ci.yml/badge.svg)](https://github.com/Dantevdm/overwatch/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Dantevdm/overwatch/actions/workflows/codeql.yml/badge.svg)](https://github.com/Dantevdm/overwatch/actions/workflows/codeql.yml)

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
make up
```

`make up` resolves any host port conflicts first, then starts the stack. Plain
`docker compose up` works too if your ports happen to be free.

That is the whole setup. Docker is the only prerequisite — the build runs inside the
container, so no local JDK or Maven is needed.

To build outside Docker you need **JDK 25** and Maven 3.9+:

```bash
java -version      # expect 25.x
mvn clean package
```

The stack comes up with a seeded rule set, a simulator already producing
transactions, and Grafana dashboards already provisioned.

| Surface | URL |
|---|---|
| Dashboard | http://localhost:5173 |
| API (Swagger) | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |

Once it is up, `./scripts/smoke-test.sh` verifies the stack is wired together —
service health, metrics endpoints, Prometheus targets, Grafana provisioning and
broker/database connectivity.

### Ports

The stack publishes exactly four ports — the four things a person opens. Postgres,
Redpanda, the fraud engine and the simulator stay inside the compose network,
because nothing outside it needs them: Prometheus scrapes over Docker DNS. Fewer
published ports means fewer things that can collide with whatever else is running
on your machine.

`make up` runs `scripts/preflight.sh` first, which checks those four, picks a free
alternative for any that are taken, and writes the overrides to `.env`. You should
never have to think about it. To check without starting anything:

```bash
./scripts/preflight.sh          # report
./scripts/preflight.sh --write  # report and write .env
```

If you want to point external tooling at the internal services — DBeaver at
Postgres, `rpk` or Postman at Kafka — the tools overlay publishes them:

```bash
make up-tools    # or: docker compose -f docker-compose.yml -f docker-compose.tools.yml up
```

### Common tasks

| Command | What it does |
|---|---|
| `make up` | Resolve ports, build and start the stack |
| `make up-tools` | Same, plus publish Postgres, Kafka, engine and simulator |
| `make down` | Stop, keeping data |
| `make clean` | Stop and drop volumes — Flyway re-runs from scratch |
| `make smoke` | Verify a running stack is wired correctly |
| `make verify` | Full quality gate — tests, coverage, SpotBugs, PMD |
| `make logs` | Tail all service logs |

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
├── common/                      # Shared domain records, events
│   └── src/main/resources/db/migration/   # Flyway migrations — the schema
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

## Driving the demo

The simulator produces continuous South African card traffic — real merchants
(Checkers, SPAR Liquor, Takealot, Engen, Clicks), real issuing banks, amounts
skewed toward small purchases the way genuine spend is. Ordinary traffic
deliberately never lands in the late-night window and never touches a watchlisted
category, so those rules only fire on something actually unusual.

A share of traffic is shaped to trip rules, and you can also direct it:

```bash
curl -X POST localhost:8080/api/simulator/inject/COMPOUND
```

`COMPOUND` puts a large, round, foreign, small-hours crypto transaction on the
stream. A CRITICAL alert with five contributing rules appears about a second later
— which is the clearest demonstration that scoring accumulates across rules rather
than latching on the first hit.

| Endpoint | Effect |
|---|---|
| `GET /api/simulator/status` | Rate, fraud share, totals published |
| `POST /api/simulator/pause` | Stop the stream — useful for reading a single alert |
| `POST /api/simulator/start` | Resume |
| `POST /api/simulator/rate?perSecond=50` | Change throughput without a restart |
| `POST /api/simulator/inject/{pattern}` | Publish one specific fraud shape now |

Patterns: `HIGH_VALUE`, `VELOCITY_BURST`, `LATE_NIGHT`, `ROUND_AMOUNT`,
`CROSS_BORDER`, `HIGH_RISK_CATEGORY`, `COMPOUND`.

---

## Quality gates

`mvn verify` is the gate, and it fails the build rather than producing a report
nobody opens:

| Tool | What it enforces |
|---|---|
| JaCoCo | Line-coverage floor, checked at `verify` |
| SpotBugs + find-sec-bugs | Bug patterns plus ~130 security detectors — injection, crypto misuse, unsafe deserialisation |
| PMD | Cyclomatic and cognitive complexity, dead code, copy-paste detection |

Rulesets in `quality/` are tuned rather than stock. The full PMD quickstart set
argues with Spring and JPA conventions loudly enough that people stop reading the
output, and a gate nobody reads is worse than no gate. Every SpotBugs exclusion
carries its justification inline, so the exclusion file cannot quietly become the
place problems go to hide.

CI runs three jobs on every push: the quality gate above, the Flyway migrations
against a real PostgreSQL with assertions on the resulting schema, and a full
`docker compose up` followed by the smoke test. That last job is the one that
catches packaging and wiring faults no unit test will.

CodeQL scans on every push and weekly — the schedule matters because it catches
newly published advisories against code that has not changed. Dependabot handles
dependency updates, grouped so the Spring ecosystem arrives as one reviewable pull
request rather than a dozen.

An offline CVE scan is available if wanted: `mvn dependency-check:check`.

---

## Database schema

The schema is owned by **Flyway**. Migrations live in
`common/src/main/resources/db/migration` and are versioned, immutable and applied
in order:

| Migration | Contents |
|---|---|
| `V1__baseline_schema.sql` | `transactions`, `fraud_rules`, `fraud_alerts`, `alert_rule_hits`, `shadow_rule_hits` |
| `V2__seed_fraud_rules.sql` | The default rule set, tuned for ZAR |

Both services that own a datasource run Flyway against the same migrations, which
is safe: Flyway locks its schema-history table, so whichever service starts first
applies the migrations and the other waits and finds nothing to do.

Hibernate is configured `ddl-auto: validate`. It never creates or alters anything —
it only asserts that the entities match what Flyway built. If the two drift, the
service fails to start rather than quietly writing to the wrong shape.

Two design points worth naming. `alert_rule_hits` records *every* rule that
contributed to an alert rather than only the strongest, because three weak hits and
one strong hit can reach the same score while meaning very different things.
And `shadow_rule_hits` exists because a shadow rule fires without raising an alert,
so its hits have no alert row to attach to — that table is what makes "what would
this rule have caught last week?" answerable before the rule ever goes live.

To add a rule type you add a class and, if it needs new configuration, nothing at
all: rule parameters are JSONB.

---

## Tech stack

| Choice | Why |
|---|---|
| Java 25 (LTS) + Spring Boot 4.1 | Records and pattern matching carry the domain model; virtual threads suit the engine's per-transaction concurrency. Both are current LTS/supported lines rather than trailing ones. |
| Redpanda | Kafka API compatible, single binary, no ZooKeeper. Same code, a fraction of the container footprint. |
| PostgreSQL 16 | JSONB makes rule parameters schemaless without giving up relational integrity for everything else. |
| Flyway | Schema is versioned and applied identically on a fresh volume, an existing one, and in CI. Hibernate runs `ddl-auto: validate`, so a drift between entities and migrations fails at startup instead of silently corrupting data. |
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
