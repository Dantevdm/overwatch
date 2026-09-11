# Overwatch

[![CI](https://github.com/Dantevdm/overwatch/actions/workflows/ci.yml/badge.svg)](https://github.com/Dantevdm/overwatch/actions/workflows/ci.yml)
[![CodeQL](https://github.com/Dantevdm/overwatch/actions/workflows/codeql.yml/badge.svg)](https://github.com/Dantevdm/overwatch/actions/workflows/codeql.yml)

> The CodeQL badge reads *skipped* while this repository is private, and that is
> deliberate rather than broken. Code scanning can only accept results where it
> is enabled, which on a private repository means GitHub Advanced Security —
> without it the scan runs perfectly and then fails on upload, reporting a red
> build for a billing setting rather than a code problem. The workflow therefore
> skips on private repositories and starts running by itself if the repository
> becomes public or Advanced Security is enabled, with no edit needed.

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
| `fraud_risk_score_bucket` | Distribution of risk scores — bucket edges on tenths plus 0.45, so every severity band boundary (0.45, 0.60, 0.80) has an edge |
| `fraud_detection_latency_seconds_bucket` | Ingest-to-alert latency, as a histogram — p50 / p95 / p99 are computed in Prometheus |
| `fraud_amount_flagged_zar_bucket` | Value of each flagged transaction, in round-ZAR bands |
| `transactions_processed_total` | Throughput, by merchant category and channel |
| `fraud_shadow_hits_total` | What shadow rules would have caught |
| `kafka_consumer_fetch_manager_records_lag` | Whether detection is keeping pace with traffic |
| `simulator_diurnal_weight` | The time-of-day multiplier currently applied to the rate — explains a throughput change that is not a fault |
| `transactions_redelivered_total` | Records Kafka delivered more than once and the engine declined to score twice |
| `outbox_pending` / `outbox_oldest_pending_seconds` | Whether every alert is being announced, and how far behind. Depth alone cannot tell a busy second from a stuck poller; age can |
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
about — the 0.60 severity break, or R25 000 — instead of an arbitrary boundary.

Four dashboards ship with the stack:

| Dashboard | Answers |
|---|---|
| **Pipeline health** | Is the pipeline keeping up, and is anything falling over — consumer lag, published against processed, the outbox backlog, dropped transactions, error rate, detection latency, and the JVM, CPU and connection pool underneath |
| **Fraud overview** | What the rules are catching — alert volume and rate, severity mix, value flagged and how those amounts are distributed, and the traffic mix by category and channel |
| **Rule performance** | Which rules earn their place — per-rule contribution, shadow hits, fire rate, and where the risk scores actually land |
| **Logs** | What the pipeline was complaining about — every container's log, filtered by service and level, with Java stack traces folded into the line that threw them |

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

## Logs

Metrics say the pipeline fell behind at 14:02. Logs say why. Both are in Grafana,
so those two questions are a click apart rather than a tool apart — and neither
is `docker compose logs -f`, which interleaves eleven containers into a stream
nobody can read while presenting.

**Loki** stores them; **Grafana Alloy** collects them. Alloy rather than
Promtail: Promtail reached end of life in early 2025, and for this pipeline —
discover containers, parse lines, push — they are the same three stages, so
choosing the deprecated one would mean writing a config that is already advice
not to follow.

Four decisions in [`config.alloy`](infra/observability/alloy/config.alloy) are
what make the result readable rather than merely collected:

- **A Java stack trace is one event, not forty.** This is the stage that matters,
  and its absence is why logs-in-Grafana is usually disappointing. Unfolded, an
  exception arrives as its message followed by dozens of frames as separate
  entries, the message scrolls off the top of the panel, and you are left holding
  the middle of a trace with no idea what threw. Folding on "a new entry starts
  with a timestamp" keeps the trace attached to the line that explains it — a
  75-line `PSQLException` reads as one entry.
- **Level is a label; nothing else is.** Grafana colours by the level label, so
  ERROR lines are red without a transform. Logger and thread stay in the message:
  promoting them would look tidier in a dropdown and would multiply Loki's
  streams by every class that logs, which is the documented way to make a small
  Loki slow.
- **A missing level is `UNKNOWN`, not blank.** Postgres, Redpanda and Grafana do
  not log in Spring Boot's format. Left empty they would vanish behind a level
  filter, and the reader could not tell "nothing was logged" from "logged in a
  format nobody taught the parser about".
- **Grafana's own query log is dropped.** Grafana logs a line per datasource
  query it proxies, so opening the logs dashboard produced log lines, which the
  dashboard then displayed, which on a ten-second refresh buried the application
  logs under a running commentary of the act of reading them. Everything else
  Grafana logs is kept — a failed provisioning still has to be visible.

The application logs stay human-readable on the console rather than becoming
JSON. A JSON encoder would make all of the parsing above unnecessary and is the
right answer for logs read by machines; it is the wrong answer here, because the
console output is also what a person watches during a demo.

Two notes on how this is wired, both of which a real deployment would change:

- Alloy mounts the Docker socket read-only. Read access to that socket is
  effectively root on the host — `:ro` limits what can be asked for, not what the
  access is worth. It is here because collecting container stdout is the only way
  to get these logs without changing every service to ship its own. A real
  deployment would have the application write to a collector it is configured
  with, or run the agent as a node-level daemon under its own policy.
- Loki runs with `auth_enabled: false` and is **not** published to the host by
  the base stack, because a published port would be an unauthenticated read of
  every log line in it. `docker-compose.tools.yml` exposes it on 3100 for anyone
  who wants to curl LogQL directly.

```bash
curl -sG localhost:3100/loki/api/v1/query_range \
  --data-urlencode 'query={service="fraud-engine", level="ERROR"}'
```

---

## Traces

Metrics say something slowed down. Logs say what one service was doing. Neither
answers "where did *this* transaction go", because the answer spans three
services and a broker, and each of them logs its own half of the story with no
shared identifier between them.

**Tempo** stores the traces; the services produce them through Micrometer's
OpenTelemetry bridge. The span that matters is the one across the broker:

```
transaction-simulator   transactions send      SPAN_KIND_PRODUCER
  fraud-engine          transactions process   SPAN_KIND_CONSUMER
```

That parent-child link is the whole point, and it does not come for free. A
Kafka publish is not a call — the producer returns long before anyone consumes,
so there is no call stack to walk. It works because `observation-enabled` is set
on both the template and the listener, which writes the trace context into the
record's headers on the way out and reads it back on the way in. Without those
two flags you get two unrelated traces and no evidence that they are the same
transaction.

Three things had to line up, and each failed silently on its own:

- **Boot 4 no longer auto-configures what is merely on the classpath.** The
  tracing bridge and the OTLP exporter as raw dependencies produce zero spans and
  zero complaints. `spring-boot-starter-opentelemetry` is what brings the
  auto-configuration.
- **The OTLP properties moved.** Boot 3's `management.otlp.tracing.*` is now
  `management.opentelemetry.tracing.export.otlp.*`. The old names bind to
  nothing, which is not an error — the exporter simply keeps its defaults.
- **The default transport is HTTP.** Boot exports to `:4318` unless told
  otherwise; this Tempo listens on gRPC `:4317`, so `transport: grpc` is not
  decoration.

Sampling is `1.0`. A demo that drops nine traces in ten would have you
explaining sampling instead of the pipeline, and the volume here is a laptop's
worth.

Logs and traces join up in both directions. Boot 4 puts `[traceId-spanId]` in
every log line, and the Loki datasource turns that bracket into a **View trace**
link via a derived field; the Tempo datasource carries `tracesToLogsV2` back the
other way, so a span opens the log lines that span produced. Click either way
and the other tool is already filtered.

Like Loki, Tempo runs unauthenticated and is **not** published to the host by
the base stack. `docker-compose.tools.yml` exposes it on 3200 for direct API
access.

```bash
curl -s localhost:3200/api/search/tag/service.name/values
```

---

## Reports

Everything on the reports screen exists elsewhere in the console. What does not
exist elsewhere is a *file* — and a fraud lead's Monday ends with a document
attached to an email. Without an export that ends in a screenshot of a browser
tab, which loses the figures, the window they cover and the date it was taken.

Two formats, because they answer different questions:

- **A workbook** (`.xlsx`, five sheets) for someone who is going to work on it —
  sort the rule table, filter to CRITICAL, paste a column into a model. So every
  sheet is a real table with one record per row, figures are numbers carrying a
  cell format rather than pre-formatted strings, and the charts are **native
  Excel charts bound to those cells**: change a cell and the chart moves. A
  picture of a chart would be a screenshot with extra steps.
- **A document** (`.pdf`, three pages) for someone who is going to read it, in
  the order the questions get asked: what happened, how it moved, which rules did
  it. Charts are drawn as **vectors** rather than rasterised, so the axis labels
  survive being printed or zoomed.

Both are built by the API, not the browser. One definition of a report then
serves the console, `curl`, Postman, the CLI and a scheduler, and the numbers
come from the same service the dashboard reads — a client-side exporter would be
a second implementation of every aggregate, drifting quietly from the first.

```bash
ow report pdf --range 7d          # or the button on the Reports screen
curl -OJ "localhost:8080/api/reports/fraud-summary.xlsx?rangeMinutes=1440"
```

Three things in the output are deliberate and easy to get wrong:

- **Alerts and volume get a chart each.** Volume runs two or three orders of
  magnitude above the alert count, so on a shared axis the alert line lies flat
  on zero. That is the chart most dashboards ship and the reason nobody trusts
  it.
- **A missing false-positive rate is blank, not zero.** A rule nobody has
  reviewed reporting 0% reads as a perfect rule.
- **The generation date is on every page**, because a figure with no date on it
  is one somebody will quote six months from now.

The dependencies are Apache POI and PDFBox — the reference implementation for
each format on the JVM. There is no charting library: the report needs a bar
chart and a line chart, and a general charting dependency would bring an AWT
surface and a theming API to draw four bars.

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
│       ├── loki/                #   Log store
│       ├── alloy/               #   Log collection and parsing
│       └── grafana/             #   Provisioned datasources and dashboards
├── tools/                       # Developer tooling, not shipped or deployed
│   ├── overwatch-cli/           #   `ow` — the console as a terminal client
│   ├── postman/                 #   Collection and environment
│   └── quality/                 #   PMD ruleset, SpotBugs exclusions
├── bin/ow                       # Launcher for the CLI — picks a Java 25 JVM
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

## The command line

The same console, without the browser. `ow` talks to the same API the dashboard
does — there is no second data path, no direct database access and no privileged
endpoint that exists only for it, so anything it shows you can be checked in the
UI and anything it changes shows up there.

```bash
make cli          # builds tools/overwatch-cli into a 2.9MB shaded jar
./bin/ow status   # is the stack up, and is the engine keeping up
```

| Command | What it answers |
| --- | --- |
| `ow status` | Is everything up, and is the consumer group behind |
| `ow top` | The live pipeline, refreshing in place — `--once` for one frame |
| `ow alerts list` / `show` / `watch` | What was flagged, why, and as it happens |
| `ow txn list` | The raw stream, newest first |
| `ow cardholder list` / `show` | The directory, and one person's profile |
| `ow rules list` / `state` / `weight` | The rule set, and retuning it live |
| `ow sim start` / `pause` / `rate` / `inject` | Drive the traffic |
| `ow sweep --rule HIGH_VALUE` | Where a threshold should sit, in one pass |
| `ow report pdf` / `xlsx` | Save the window as a file, for a cron job or an email |
| `ow reset` | Clear the store. Destructive, and it asks |

Three decisions are worth calling out, because they are the difference between a
tool and a pile of curl invocations:

- **Colour is an accelerator, never the carrier.** A severity always prints its
  label. This output goes into pipes, logs and terminals with palettes a reader
  cannot distinguish, and "the red ones" is not a specification. Colour turns
  itself off for a pipe, and honours `NO_COLOR`.
- **Tables measure what they show.** Widths come from the visible width of a
  cell, ignoring escape sequences — which is why the columns line up even when
  half of them are coloured.
- **`ow reset` refuses to guess.** It asks you to type the word `clear` rather
  than to press `y`, and with no terminal to ask at it refuses outright and names
  `--yes`. The API behind it is destructive and unauthenticated; assuming consent
  from a pipe is how that ends up running in CI.

`--api` points it at another stack, or set `OVERWATCH_API`. Everything reads
from `localhost:8080` by default, which is where compose puts the API.

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
| Loki + Grafana Alloy | Logs beside the metrics, in the tool already open. Alloy rather than Promtail, which reached end of life in early 2025 — the config is the same three stages, so using the deprecated agent would mean shipping advice not to follow. |
| Tempo | Traces beside the logs and metrics, sharing Grafana's datasource plumbing so a trace id in a log line is a link rather than a copy-paste. Micrometer's OpenTelemetry bridge means the instrumentation is the same API already producing the metrics. |
| Apache POI + PDFBox | Report generation on the server, so one definition of a report serves the console, curl, Postman, the CLI and a scheduler. POI writes native Excel charts bound to the cells; PDFBox draws the PDF as vectors, with no charting library and no AWT. |
| picocli | The CLI's subcommands, help and completion come from annotations on the classes that do the work, so the help cannot drift from the behaviour. It shades to a single 2.9MB jar with no runtime on the machine but a JVM. |

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
disposition it, use replay to decide a threshold change, then take the window
away as a file. The alert id is captured automatically by the list request, so
nothing needs editing by hand. Every request goes through `{{baseUrl}}`,
including the simulator controls — `fraud-api` proxies those, so the tour runs
against a plain `make up` with no second port to publish.

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
to take the others down. The Flyway migrations were applied to a real PostgreSQL
16: 6 tables, 32 indexes, 7 seeded rules, 11 constraint assertions, cascade delete
confirmed, and the velocity lookup checked against 60,000 rows — index-only scan,
0.027 ms. The generator was run against the real rules: **0.00% false positives
across 3,000 clean transactions**. Every entity column was checked against the
migration. The chart palette was measured rather than eyeballed, which caught two
severity colours 4.1 ΔE apart. Every JSX file was parsed with esbuild.

**Also executed, on a machine with Docker and Maven.** `mvn clean verify` passes
green across all six modules — 136 tests, plus JaCoCo, SpotBugs with find-sec-bugs,
and PMD. The stack was brought up cold with `docker compose up --build`: Flyway
migrated, Hibernate's `ddl-auto: validate` accepted every entity against the
migrated schema, the simulator published, the engine consumed and scored, and
alerts landed in PostgreSQL — so Spring wiring, JPA at runtime and Kafka
serialisation are all exercised rather than assumed. `./scripts/smoke-test.sh`
reports 50 of 50, and `./scripts/verify-dashboards.py` confirms all 54 panel
queries return data against the live Prometheus and Loki. Every API endpoint was
exercised against live data, including each optional filter, and every dashboard
screen was loaded in a browser against the running stack. The headline
demonstration was confirmed end to end: `POST /api/simulator/inject/COMPOUND`
produces a CRITICAL alert with five contributing rules and a score capped at 1.0,
about a second later.

The same verification was re-run after the repository restructure, since moving
every module is exactly the kind of change that compiles and then fails to
package: `mvn clean verify` green, all four images built, every container healthy,
and both scripts green.

**The broker path is now covered by a test rather than by a script.**
`PipelineIT` starts a real Redpanda and a real PostgreSQL under Testcontainers,
publishes raw JSON to `transactions`, and asserts that an alert is persisted,
that it is announced on `fraud-alerts`, and that the outbox drains. It earned its
place immediately by finding two faults the unit tests could not: declaring the
outbox `ProducerFactory` makes Boot's Kafka auto-configuration back off entirely,
and `buildProducerProperties()` ignores `KafkaConnectionDetails`, so the outbox
producer dialled a broker that was not the one the rest of the context was using.

Distributed tracing was verified the same way — not by the exporter starting, but
by a trace in Tempo showing `transaction-simulator transactions send` as the
parent of `fraud-engine transactions process` across the broker, and by a log
line in Grafana rendering its trace id as a working link into that trace.

---

## Known gaps

Deliberate omissions, recorded rather than hidden. The full list with reasoning is in
the project plan.

- **No authentication.** The BFF is the natural seam for it. Out of scope for the brief, and half-built auth is worse than none. The sharpest edge of this gap is `POST /api/admin/reset`, which destroys data — hence the `allow-reset` flag, and hence it being first in the queue for a role check.
- **Single currency.** Everything is ZAR. The `currency` column exists so multi-currency is additive rather than a rewrite.
- **Hand-set rule weights.** A learned model would be more interesting and considerably less verifiable in the time available.
- **Polling, not WebSockets.** Five seconds is imperceptible on a dashboard and a fraction of the complexity.
- **No dead-letter topic.** A poison message is logged and counted rather than stalling the partition; a real deployment would route it somewhere.
- **Delivery is at-least-once, not exactly-once.** This is the gap the outbox traded for, and it is the better gap: an alert and its outbox row are written in one transaction, and a poller publishes from that table, so an alert can no longer exist on the topic without existing in the database. What can happen is the reverse — a publish that succeeds and a mark-as-published that does not, so the row is sent again on the next poll. Consumers must therefore be idempotent, and publication runs one 200ms poll behind the write. Both are visible: `outbox_pending` is the backlog and `outbox_oldest_pending_seconds` is how far behind it is.
- **No Schema Registry.** Both topics carry plain JSON written by Spring's `JsonSerializer`, so a registry would have nothing in it — an empty registry reads as broken where "Not Configured" reads as a decision. The contract is enforced instead by the shared `services/common` module: producer and consumer compile against the same record, so a field rename breaks the build rather than a running consumer. That trade stops working the moment a consumer outside this repo subscribes, which is when a registry and a wire format that can carry a schema id start earning their keep.
- **Replay ignores history-dependent rules.** Velocity and amount-deviation report nothing there rather than answering from a baseline that does not reflect the replayed window. A wrong answer delivered confidently is the failure mode worth avoiding in a tool meant to inform a threshold change.
