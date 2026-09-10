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
`docker compose up --build` works too if your ports happen to be free — keep the
`--build`, because without it compose reuses whatever image it cached last time,
and a stale image looks like missing features rather than an old build.

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
| Metrics (Grafana, framed in the dashboard) | http://localhost:5173/metrics |
| API (Swagger) | http://localhost:8080/swagger-ui.html |
| Grafana | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Redpanda Console | http://localhost:8090 |

Every one of these is also linked from **External tools** at the foot of the
dashboard's sidebar, following whatever ports preflight assigned — so a reviewer
never has to be told which port Grafana landed on.

Once it is up, `./scripts/smoke-test.sh` verifies the stack is wired together —
service health, metrics endpoints, Prometheus targets, Grafana provisioning and
broker/database connectivity.

### Ports

The stack publishes exactly five ports — the five things a person opens: the
dashboard, the API, Grafana, Prometheus and the Redpanda Console. Postgres, the
Kafka listener, the fraud engine and the simulator stay inside the compose
network, because nothing outside it needs them: Prometheus scrapes over Docker
DNS. Fewer published ports means fewer things that can collide with whatever else
is running on your machine.

`make up` runs `scripts/preflight.sh` first, which checks those five, picks a free
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

Alongside them the stack runs PostgreSQL, Redpanda, Prometheus, Grafana and the
**Redpanda Console** — the broker's own web UI, on 8090. It is worth a container
because streaming is the one step you otherwise have to take on faith: without it,
transactions go into a topic and alerts come out with nothing to look at in
between. The console shows the actual JSON on `transactions` and `fraud-alerts`,
the partitions, and the engine's consumer-group lag moving in real time. It is
read-only and, like everything else here, unauthenticated — a window onto the
broker for a demonstration, not an admin console.

---

## The rule engine

Rules are configuration, not code paths. Each rule type is a class implementing a
single interface; each *active* rule is a row in the database whose parameters are
JSONB. Changing a threshold is an API call, not a redeploy.

```java
public interface FraudRule {
    String ruleType();
    Optional<RuleFinding> evaluate(RuleContext context);
}
```

A rule returns a *finding* — a reason and its evidence — and nothing else. Weight
and whether it alerts at all are applied afterwards from configuration, so the
same implementation runs live at one weight and in shadow at another without
knowing which. That separation is what makes shadow mode possible.

The rules depend on nothing but the JDK: parameters arrive as a plain map rather
than a Jackson node, and card history through a narrow port rather than a
repository. Every rule is therefore a pure function of its context, unit-testable
with a hand-written fake and no Spring, no database, no broker.

The orchestrator loads the enabled rule configurations, runs the matching
implementations, and accumulates a weighted risk score:

The orchestrator does three things worth naming. It discovers rules rather than
registering them, so adding a detection technique is one class and one database
row. It keeps shadow hits out of the score, so a rule under evaluation provably
cannot raise an alert. And it contains a rule that throws — logged and counted,
never silently swallowed — because one misconfigured rule must not stop the other
six from protecting anyone.

### Shipped rules

| Rule | Trigger | Default | Weight |
|---|---|---|---|
| High Value | Amount over threshold | R50,000 | 0.40 |
| Velocity | N transactions on a card in a window | 5 in 10 min | 0.35 |
| Late Night | Timestamp in the small hours (SAST) | 01:00–04:59 | 0.20 |
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

**Rule performance statistics.** `GET /api/rules/performance` returns, for every
rule, its fire count, share of total alerts, shadow hits and false-positive rate. This is what you
need to decide whether a rule is earning its keep, and it is the first thing that goes
missing in a rules engine nobody instrumented.

**Selectable chart window.** `GET /api/stats/dashboard?rangeMinutes=N` drives the
dashboard's two time-series charts over anything from 5 minutes to 7 days. Bucket
width is the server's choice, not the client's — it comes off a ladder of round
widths (10s, 30s, 1m, 2m, 30m, 1h, 6h), picking the narrowest that keeps the series
under 40 points, and is returned as `bucketSeconds` so the chart can title itself
honestly: the same chart is "alerts per 10 seconds" over five minutes and "alerts
per 6 hours" over a week. Bins are anchored to the Unix epoch so boundaries land on
round times and stay stable between polls. The range governs the charts only; the
headline figures keep their own fixed periods, because silently rescoping "open
alerts awaiting an analyst" to five minutes answers a different question from the
one the label asks.

**Idempotent consumption.** Kafka delivers at least once. On a consumer
rebalance, or a crash between processing a record and committing its offset, the
same transaction arrives again — and a second alert for one card movement is not
cosmetic, it double-counts every figure an analyst reads and every rule-performance
statistic used to decide whether a rule earns its place. The transaction id is the
idempotency key: the engine recognises a redelivery and skips it, counting it as
`transactions_redelivered_total`. Two unique constraints back that up at the schema
level, because two engine instances racing the same record can both pass an
application check and only the database can settle it. Verified by rewinding the
consumer group to the start of the topic and re-consuming all 5,330 records: 5,330
redeliveries recognised, zero duplicate alerts.

**Replay / what-if.** `POST /api/replay` takes a candidate configuration and a
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
| `fraud_risk_score_bucket` | Distribution of risk scores — bucket edges on tenths plus 0.5 and 0.75, where the severity bands break |
| `fraud_detection_latency_seconds_bucket` | Ingest-to-alert latency, as a histogram — p50 / p95 / p99 are computed in Prometheus |
| `fraud_amount_flagged_zar_bucket` | Value of each flagged transaction, in round-ZAR bands |
| `transactions_processed_total` | Throughput, by merchant category and channel |
| `fraud_shadow_hits_total` | What shadow rules would have caught |
| `kafka_consumer_fetch_manager_records_lag` | Whether detection is keeping pace with traffic |
| `simulator_diurnal_weight` | The time-of-day multiplier currently applied to the rate — explains a throughput change that is not a fault |
| `transactions_redelivered_total` | Records Kafka delivered more than once and the engine declined to score twice |
| `logback_events_total{level="error"}` | Error rate, without reading logs |

All three distributions are exported as Prometheus **histograms** rather than as
client-computed percentiles. Micrometer's `publishPercentiles` would emit
p50/p95/p99 as gauges from inside one JVM, and those cannot be aggregated — the
mean of two instances' p95 is not the p95, and you can never ask for a percentile
you did not configure up front. Buckets let Prometheus answer any percentile
across any set of instances, which is what `histogram_quantile()` in the
dashboards needs.

Risk score and flagged amount use **chosen** bucket edges rather than Micrometer's
automatic ones. The automatic buckets are generated for timers and land on a
power-of-ten ladder; both of these quantities have meaningful edges of their own,
and picking them means a heatmap row corresponds to something a person reasons
about — the 0.75 severity break, or R25 000 — instead of an arbitrary boundary.

Three dashboards ship with the stack:

| Dashboard | Answers |
|---|---|
| **Pipeline health** | Is the pipeline keeping up, and is anything falling over — consumer lag, published against processed, dropped transactions, error rate, detection latency, and the JVM, CPU and connection pool underneath |
| **Fraud overview** | What the rules are catching — alert volume and rate, severity mix, value flagged and how those amounts are distributed, and the traffic mix by category and channel |
| **Rule performance** | Which rules earn their place — per-rule contribution, shadow hits, fire rate, and where the risk scores actually land |

Every count and pie is scoped to the dashboard's time picker via
`increase(...[$__range])`, so changing the range changes the numbers. Lifetime
totals would read identically at 5 minutes and 7 days, which on a dashboard with
a time picker is not a simplification but a wrong answer. Severity colours are
fixed to the same ramp the UI uses, so CRITICAL is the same crimson in both
places, and every panel carries a description explaining what to conclude from it.

They are also **framed directly in the dashboard** under **Metrics**, one tab
each, so the metrics sit next to the alerts they explain rather than behind a port
nobody mentioned. The frames run in Grafana's kiosk mode, and each carries an
*Open in Grafana* link for the full time picker and panel inspection — the embed
is deliberately the lesser view.

Panel queries are verified against a live Prometheus rather than by eye:

```bash
./scripts/verify-dashboards.py
```

It runs every panel's query, expands the Grafana variables, and fails on any that
returns no series. This exists because a blank panel is indistinguishable from a
quiet metric: six panels once used `histogram_quantile()` against metrics exported
as summaries, so every metric name matched, nothing warned, and the panels were
simply empty. Matching names is what made them look verified. CI runs it against
the live stack.

Error counters are registered at zero on startup rather than on first failure.
Micrometer creates a counter when it is first incremented, so a healthy pipeline
would expose no `transactions_failed_total` at all, and a panel asking "how many
records have we dropped?" would answer "No data" — indistinguishable from a panel
whose query is broken.

---

## Repository layout

```
overwatch/
├── services/                    # Every Maven module — the JVM side of the system
│   ├── common/                  #   Shared domain records, events, JPA entities
│   ├── rule-engine/             #   The rules and the orchestrator (a library)
│   ├── transaction-simulator/   #   Service 1 — event generation
│   ├── fraud-engine/            #   Service 2 — rule evaluation
│   └── fraud-api/               #   Service 3 — BFF
├── overwatch-ui/                # React + Vite dashboard
├── infra/                       # What the services run on, as code
│   ├── database/migration/      #   Flyway migrations — the schema
│   └── observability/
│       ├── prometheus/          #   Scrape configuration
│       └── grafana/             #   Provisioned datasources and dashboards
├── tools/                       # Developer tooling, not shipped or deployed
│   ├── postman/                 #   Collection and environment
│   └── quality/                 #   PMD ruleset, SpotBugs exclusions
├── scripts/                     # preflight, smoke test, verification scripts
├── docs/
│   ├── architecture/            #   Architecture document and dashboard mockup
│   ├── design-system/           #   i1 design system — UI kit and tokens.css
│   └── planning/                #   Project plan, task list, deferred decisions
├── Dockerfile                   # One multi-stage build for all three services
├── docker-compose.yml
├── Makefile
└── pom.xml                      # The reactor
```

Each top-level directory answers a different question. `services/` and
`overwatch-ui/` are the product; `infra/` is what it runs on; `tools/` is what a
developer uses on it; `scripts/` is what you run; `docs/` is what you read.
Everything that used to sit loose at the root — five Maven modules, `database/`,
`observability/`, `postman/`, `quality/` — is now under whichever of those it
belongs to, so the root lists what the project *is* rather than everything it
contains.

The moves were made with `git mv`, so `git log --follow` still works on every file.
One consequence worth knowing if you add a module: each module POM carries an
explicit `<relativePath>../../pom.xml</relativePath>`, because Maven's default
guess is `../pom.xml` and the modules are now two levels down.

---

## Driving the demo

> For a structured walkthrough — a fifteen-minute running order with what to say
> at each step, a five-minute cut, the questions you will be asked and what to do
> when a panel is empty — see [`docs/DEMO.md`](docs/DEMO.md).

The simulator produces continuous South African card traffic — real merchants
(Checkers, SPAR Liquor, Takealot, Engen, Clicks), real issuing banks, amounts
skewed toward small purchases the way genuine spend is. Ordinary traffic never
touches a watchlisted category, so the category rule only fires on something
actually unusual.

**The stream is shaped, not flat.** Three things stop it looking synthetic, and
each was added after measuring the old behaviour rather than by guessing:

*Volume follows a diurnal curve.* A 24-point hourly shape — quiet from midnight to
five, a morning build, a lunch bump, an evening commute peak, then decline — is
interpolated to the minute in SAST and multiplies the configured rate. The curve is
normalised to average exactly 1.0, so "50 per second" remains the daily mean rather
than becoming a ceiling. The current multiplier is exported as
`simulator_diurnal_weight`, so the shape is visible in Grafana next to the
throughput it explains.

*Arrivals are Poisson, not fixed.* Each tick draws its batch size from a Poisson
distribution around the expected rate instead of publishing the same count every
time. Real card traffic arrives independently; a fixed count per tick produces a
throughput line so flat it is obviously generated. Measured over ten minutes, the
coefficient of variation went from 0.0004 — a straight line — to 0.095.

*The category mix is weighted.* Merchants are drawn by a weighted choice over
`CATEGORY_WEIGHTS` (groceries 22, fuel 14, restaurant and retail 11, down to
liquor 3), not uniformly from the merchant list. Uniform selection made the mix a
function of how many merchants of each kind happened to be listed, which meant the
pie chart described the fixture file rather than South African card spend.

One consequence worth stating: ordinary traffic is now stamped with the real
current time, so between 01:00 and 04:59 SAST the late-night rule fires on genuine
traffic. That is the correct behaviour — a rule that only ever triggers on injected
data has not been demonstrated — but it means the alert rate is time-of-day
dependent, and the small hours are the interesting time to watch.

A share of traffic is shaped to trip rules, and you can also direct it — from the
**Simulator** screen in the dashboard, or over the API:

```bash
curl -X POST localhost:8080/api/simulator/inject/COMPOUND
```

The Simulator screen puts the same controls behind sliders and buttons: run state,
throughput, fraud share, live published counters, and one button per pattern with a
line saying which rule it trips. Drop the rate to one or two per second and every
alert on the dashboard is one you can follow to its rule.

`COMPOUND` puts a large, round, foreign, small-hours crypto transaction on the
stream. A CRITICAL alert with five contributing rules appears about a second later
— which is the clearest demonstration that scoring accumulates across rules rather
than latching on the first hit.

### Starting from a clean slate

**Clear data** in the top right of the dashboard empties the store so a run starts
from nothing — every transaction, alert and rule hit, plus the simulator's session
counters. It sits behind a confirmation that names what goes and what stays, and
the equivalent call is:

```bash
curl -X POST localhost:8080/api/admin/reset
```

Two things it deliberately does *not* clear.

**Rules keep their configuration** — states, weights and thresholds included. Rules
are configuration rather than history, and they are seeded by a Flyway migration
that will not re-run on an existing volume, so deleting them would leave the engine
with no rules and no way back short of `make clean`. It is also the more useful
behaviour: tune a threshold, clear the traffic, and watch the new threshold work.

**Prometheus counters keep counting.** A Prometheus counter is monotonic by
contract and `rate()` reads any decrease as a process restart, so zeroing them
would write a false spike into every Grafana panel and discard the history the
dashboards exist to show. Straight after a reset the dashboard therefore reads zero
while Grafana still shows the whole run. Both are right — one answers "what is in
the store now", the other "what has this process done since it started". For
genuinely untouched metrics too, `make clean` drops the volumes.

The endpoint destroys data and nothing in this system authenticates, so it is
behind `overwatch.api.allow-reset` (`ALLOW_RESET`), which ships on because a reset
button needing a config change to work is a reset button nobody has. Setting it
false answers 403 — that one line is what a real deployment changes, and this is
the first route that should demand a role once the BFF grows authentication.

| Endpoint | Effect |
|---|---|
| `GET /api/simulator/status` | Rate, fraud share, totals published |
| `POST /api/simulator/pause` | Stop the stream — useful for reading a single alert |
| `POST /api/simulator/start` | Resume |
| `POST /api/simulator/rate?perSecond=50` | Change throughput without a restart |
| `POST /api/simulator/fraud-rate?rate=0.2` | Change the share of traffic shaped as fraud |
| `POST /api/simulator/inject/{pattern}` | Publish one specific fraud shape now |
| `POST /api/admin/reset` | Clear transactions, alerts and hits; keep rules |

These are served by `fraud-api` on 8080 and forwarded to the simulator over the
compose network, so the dashboard stays on one origin and the simulator needs no
published port of its own.

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

Rulesets in `tools/quality/` are tuned rather than stock. The full PMD quickstart set
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
`infra/database/migration` and are versioned, immutable and applied
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
| [`docs/DEMO.md`](docs/DEMO.md) | How to run a demonstration of this system, and what to say |
| [`docs/design-system/tokens.css`](docs/design-system/tokens.css) | Design tokens the UI imports |

---

## Postman

`tools/postman/` holds a collection and a local environment. The folders are ordered as a
guided tour: check health, inject a fraud pattern, watch the alert appear,
disposition it, then use replay to decide a threshold change. The alert id is
captured automatically by the list request, so nothing needs editing by hand.

```
tools/postman/Overwatch.postman_collection.json
tools/postman/Overwatch-Local.postman_environment.json
```

If preflight moved a port, `make urls` prints the values to put in the environment.
The simulator's control endpoints are reached through `fraud-api` on 8080 like the
rest of the API — no `make up-tools` needed.

---

## Verification status

Being straight about what has and has not been executed, because "it compiles" and
"it works" are different claims.

**Executed and passing.** The domain model and the rule layer are free of Spring by
design, so they were compiled and run directly: 22 domain assertions, 28 rule
assertions and 19 orchestrator assertions. Those cover the UTC-to-SAST conversion
that a late-night rule usually gets quietly wrong, midnight-wrapping windows,
inclusive boundaries, malformed parameters degrading rather than throwing, shadow
isolation under a crushing 0.90 weight, and a deliberately exploding rule failing
to take the others down. Both Flyway migrations were applied to a real PostgreSQL
16: 5 tables, 20 indexes, 7 seeded rules, 11 constraint assertions, cascade delete
confirmed, and the velocity lookup checked against 60,000 rows — index-only scan,
0.027 ms. The generator was run against the real rules: **0.00% false positives
across 3,000 clean transactions**. Every entity column was checked against the
migration. The chart palette was measured rather than eyeballed, which caught two
severity colours 4.1 ΔE apart. Every JSX file was parsed with esbuild.

**Also executed, on a machine with Docker and Maven.** `mvn clean verify` passes
green across all five modules — 99 tests, plus JaCoCo, SpotBugs with find-sec-bugs,
and PMD. The stack was brought up cold with `docker compose up --build`: Flyway
migrated, Hibernate's `ddl-auto: validate` accepted every entity against the
migrated schema, the simulator published, the engine consumed and scored, and
alerts landed in PostgreSQL — so Spring wiring, JPA at runtime and Kafka
serialisation are all exercised rather than assumed. `./scripts/smoke-test.sh`
reports 35 of 35, and `./scripts/verify-dashboards.py` confirms all 45 panel
queries return series against the live Prometheus. Every API endpoint was exercised against live data, including
each optional filter, and all five dashboard screens were loaded in a browser
against the running stack. The headline demonstration was confirmed end to end:
`POST /api/simulator/inject/COMPOUND` produces a CRITICAL alert with five
contributing rules and a score capped at 1.0, about a second later.

The same verification was re-run after the repository restructure, since moving
every module is exactly the kind of change that compiles and then fails to
package: `mvn clean verify` green, all four images built, nine containers healthy,
smoke test 35 of 35, dashboards 45 of 45.

**Still not executed.** There is no automated integration test through a real
broker; the broker path is covered by the compose stack and the smoke test rather
than by Testcontainers.

---

## Known gaps

Deliberate omissions, recorded rather than hidden. The full list with reasoning is in
the project plan.

- **No authentication.** The BFF is the natural seam for it. Out of scope for the brief, and half-built auth is worse than none. The sharpest edge of this gap is `POST /api/admin/reset`, which destroys data — hence the `allow-reset` flag, and hence it being first in the queue for a role check.
- **Single currency.** Everything is ZAR. The `currency` column exists so multi-currency is additive rather than a rewrite.
- **Hand-set rule weights.** A learned model would be more interesting and considerably less verifiable in the time available.
- **Polling, not WebSockets.** Five seconds is imperceptible on a dashboard and a fraction of the complexity.
- **No dead-letter topic.** A poison message is logged and counted rather than stalling the partition; a real deployment would route it somewhere.
- **Alerts are written to PostgreSQL and published to Kafka in the same transaction.** The send is asynchronous, so a rollback after the send is initiated leaves an alert on the topic that does not exist in the database. The durable record is the database one and the topic is a notification, which bounds the damage — but the honest fix is a transactional outbox, or publishing after commit rather than inside it.
- **Replay ignores history-dependent rules.** Velocity and amount-deviation report nothing there rather than answering from a baseline that does not reflect the replayed window. A wrong answer delivered confidently is the failure mode worth avoiding in a tool meant to inform a threshold change.
