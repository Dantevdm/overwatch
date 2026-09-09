# Overwatch — Project Plan & Progress

A fraud rule engine service. This document tracks what we are building, what is
done, and which decisions we have deliberately postponed.

Last updated: 2026-09-09

---

## The brief

> Create a system that processes categorized transaction events and flags potential
> fraud. Apply a set of fraud rules per transaction based on different criteria and
> then store them in a data store. Allow the retrieval of this data via an API.

The brief is deliberately vague. The response is to treat it as a production
fraud-detection problem rather than a CRUD exercise, and to make the operational
qualities — configurability, observability, tuning — the visible part of the work.

---

## Guiding principles

Simple over clever. The rule engine uses one well-known pattern (Strategy) applied
consistently, rather than a framework of abstractions. Every service does one thing.
The architecture should be explainable on a whiteboard in two minutes.

Configuration over deployment. Rules live in the database, not in compiled code.
Changing a threshold is an API call, not a release.

Observable by default. If a fraud analyst cannot see which rules are firing and how
often, the system is not finished. Metrics are business metrics, not just HTTP counters.

Real data. Transactions use South African merchants, banks and ZAR amounts, because
generic `Merchant A / $100.00` sample data makes it impossible to tell whether the
rules are sensible.

---

## Architecture at a glance

Three Spring Boot services, one message broker, one database, one UI.

| Service | Port | Responsibility |
|---|---|---|
| `transaction-simulator` | 8081 | Generates realistic ZAR transaction events, publishes to Redpanda |
| `fraud-engine` | 8082 | Consumes transactions, evaluates rules, persists alerts, publishes to `fraud-alerts` |
| `fraud-api` (BFF) | 8080 | REST API over the data store — transactions, alerts, rules, stats |
| `overwatch-ui` | 5173 | React + Vite dashboard on the i1 design system |

Flow: Simulator → Redpanda `transactions` → Engine → PostgreSQL → BFF → Dashboard.
The engine also emits to a `fraud-alerts` topic so notification consumers can be
added without touching the engine.

Full detail, including the schema, API contracts and Docker topology, lives in
`docs/architecture/overwatch-architecture.html`.

---

## What makes this different

These are the features chosen specifically because they are what a real fraud team
needs and what a from-the-brief implementation would not include.

**Shadow mode.** A rule's state is `ENABLED`, `DISABLED` or `SHADOW`. In shadow mode
the rule evaluates every transaction and records what it would have flagged, without
creating real alerts. This is how production fraud systems tune new rules — you run
them silently against live traffic for a week before switching them on. One extra
enum value; a genuinely different conversation in the interview.

**Composite risk scoring.** Rules do not return a boolean. Each contributes a weighted
score, and the accumulated score determines alert severity. A transaction that trips
Late Night (0.3) and Round Amount (0.2) scores 0.5 — suspicious, not damning. Fraud is
probabilistic and the model should say so.

**Rule performance statistics.** The API exposes per-rule operational metrics: fire
count, share of total alerts, average score contribution, and — once an analyst
dispositions alerts — false-positive rate. This is the data you need to decide whether
a rule is earning its place.

**Replay / what-if endpoint.** POST a candidate rule configuration and a time window;
the engine replays stored transactions through it and returns the alerts it *would*
have produced, without writing anything. "What happens if we drop the high-value
threshold to R30,000?" becomes a question you answer in a second rather than a guess.

**Pre-provisioned observability.** Grafana dashboards and Prometheus scrape targets are
committed as provisioning files and mounted into the containers. `docker compose up`
yields working dashboards with no manual setup. Nobody clicks through a wizard.

---

## Fraud rules

| Rule | Trigger | Default parameters | Weight |
|---|---|---|---|
| High Value | Amount exceeds threshold | `threshold: 50000` ZAR | 0.40 |
| Velocity | N transactions on one card within a window | `count: 5`, `windowMinutes: 10` | 0.35 |
| Late Night | Transaction timestamp in the small hours (SAST) | `startHour: 1`, `endHour: 4` | 0.20 |
| Round Amount | Suspiciously round value above a floor | `floor: 5000`, `multipleOf: 1000` | 0.15 |
| Cross-Border | Country differs from the card's home country | `homeCountry: "ZA"` | 0.30 |
| Category Mismatch | Merchant category on the watchlist | `categories: [crypto, gambling, forex]` | 0.25 |

Rules are rows in `fraud_rules`. Parameters are JSONB, so adding a rule type means
adding one class that implements `FraudRule` — no schema migration.

---

## Task list

### Phase 0 — Planning & design
- [x] Choose the stack and justify each choice
- [x] Name the project
- [x] Architecture document with diagram, schema, API contracts
- [x] Dashboard mockup on the i1 design system
- [x] South African data model — merchants, banks, ZAR, SAST
- [x] Repository skeleton, documentation folder, README

### Phase 1 — Scaffolding
- [x] Parent Maven POM and three service modules
- [x] Shared `common` module — domain records, events, constants
- [x] Dockerfile per service (multi-stage, JRE-slim runtime, non-root)
- [x] `docker-compose.yml` — postgres, redpanda, three services, ui, prometheus, grafana
- [x] Health checks and dependency ordering so `up` works from cold
- [x] Prometheus scrape config + Grafana datasource/dashboard provisioning
- [x] React + Vite UI scaffold wired to the design tokens
- [x] Verify a cold `docker compose up` on the host

> **Verification note.** The `common` module was compiled and unit-checked
> (`javac -Xlint:all`, 22 assertions covering severity boundaries, shadow-mode
> isolation and score capping — all passing). The full Maven build and
> `docker compose up` still need one run on a machine with Docker and access to
> Maven Central; the build environment used here has neither.

### Phase 2 — Data layer
- [x] Flyway wired into both datasource-owning services
- [x] `V1__baseline_schema.sql` — transactions, fraud_rules, fraud_alerts, alert_rule_hits, shadow_rule_hits
- [x] `V2__seed_fraud_rules.sql` — default ZAR-tuned rule set, one shipped in SHADOW
- [x] Indexes for the velocity lookup and the dashboard's time-ordered queries
- [x] JPA entities and repositories
- [x] SA merchant and card reference data (moves with the simulator, Phase 4)

> **Verified.** Both migrations were applied to a real PostgreSQL 16 instance:
> 5 tables, 20 indexes, 7 seeded rules. 11 constraint assertions passed (amount,
> channel, weight bounds, rule state, name uniqueness, severity, foreign keys),
> cascade delete was confirmed to clean up alerts, hits and shadow hits, and the
> velocity query was checked against 60,000 rows — Index Only Scan, 0.027 ms.

### Phase 3 — Fraud engine
- [x] `FraudRule` interface, `RuleContext`, `RuleParameters`, `TransactionHistory` port
- [x] Seven rule implementations (six live, one shipped in SHADOW)
- [x] `RuleEngine` — score accumulation, shadow isolation, per-rule failure containment
- [x] JPA entities and repositories for all five tables
- [x] `RuleConfigProvider` — cached rule set, refreshed on a timer, survives a DB blip
- [x] Kafka consumer for `transactions`, producer for `fraud-alerts`
- [x] Micrometer business metrics — latency percentiles, alerts by rule, shadow hits
- [x] Unit tests per rule and for the orchestrator
- [ ] Integration test through a real broker (needs Testcontainers)

> **Verified.** Rule logic is deliberately free of Spring and Jackson — it depends
> only on the JDK and the `common` module — so it was compiled and executed
> directly: 28 rule assertions and 19 orchestrator assertions, all passing. These
> cover UTC-to-SAST conversion (the way a late-night rule is usually quietly
> wrong), midnight-wrapping windows, inclusive/exclusive boundaries, malformed
> parameters degrading rather than throwing, shadow isolation under a 0.90 weight,
> and a deliberately exploding rule not stopping the others. All 40 entity columns
> were checked against the migration, so `ddl-auto: validate` has been verified
> statically. Spring wiring, JPA runtime behaviour and Kafka remain unproven until
> the stack runs.

### Phase 4 — Transaction simulator
- [x] SA reference data — 43 real merchants across 12 categories, 8 banks, 14 cities
- [x] Generator with realistic spend distribution, skewed toward small amounts
- [x] Seven injectable fraud patterns, one per rule plus a compound case
- [x] Kafka producer keyed by card, so a card's transactions stay ordered
- [x] Runtime control API — pause, resume, rate, fraud share, inject
- [x] Deterministic under a seed, so test failures are reproducible

> **Verified.** The generator was run against the real rule implementations:
> **0.00% false positives across 3,000 clean transactions**, mean spend R884, and
> every injected pattern trips its intended rule. COMPOUND reaches CRITICAL with
> all five rules contributing. Normal traffic provably never enters the late-night
> window and never touches a watchlisted category — without that the corresponding
> rules would fire constantly and the alert list would carry no information.

### Phase 5 — Fraud API (BFF)
- [x] Transactions, alerts and rules controllers with filtering and pagination
- [x] Stats endpoints for the dashboard
- [x] Rule performance statistics
- [x] Replay / what-if endpoint
- [x] OpenAPI / Swagger

> **Verified.** Every endpoint was exercised against the running stack with live
> data, including each optional filter (card, category, severity, status, window)
> and the unbounded-window case. `/v3/api-docs` and Swagger UI serve. The
> checkboxes here lagged the code for several commits — the controllers were
> written in `ef79066` and this list was never updated.

### Phase 6 — Dashboard
- [x] Vite scaffold, tokens.css, app shell with routing
- [x] Dashboard, Transactions, Alerts, Rules pages
- [x] Hand-authored SVG charts with hover, direct labels and theme-token text
- [x] Validated severity ramp (see note), 5-second polling

> **Verified.** The severity palette was measured, not chosen by eye. Four status
> hues put MEDIUM and HIGH 4.1 ΔE apart under normal vision and 0.1 under
> deuteranopia — indistinguishable. Severity is ordinal, so it now uses one hue
> stepped light-to-dark, passing monotonic lightness, adjacent-step separation and
> light-end contrast in both themes independently. Every JSX file was parsed with
> esbuild; a keyed list using a shorthand fragment was caught and fixed.

### Phase 8.5 — Severity trend and simulator control

- [x] `severityOverTime` on the dashboard stats: alerts per hour split by severity,
      dense across the window so quiet hours read as zero rather than as a slope
- [x] Severity shown as four lines rather than one stacked bar
- [x] Simulator control API proxied through `fraud-api` under `/api/simulator`
- [x] **Simulator** screen — run state, throughput and fraud-share sliders, live
      counters, one inject button per fraud pattern

> **Verified.** The new time-series query was run against a real PostgreSQL 16 with
> the migrations applied and alerts seeded across six hours; it returns the three
> columns the mapper reads, and confirms the sparse-hour case the dense fill exists
> for. The whole dashboard was bundled from `main.jsx` with esbuild, and the chart
> was server-rendered against 25 hourly buckets and checked numerically: no NaN,
> every text mark inside the viewBox, all path points within the plot, and the
> direct end labels at least 13px apart after collision avoidance.
>
> **A note on the palette check.** The categorical validator fails this ramp, and
> that is the correct result for the wrong test. A four-step single hue cannot
> reach the categorical adjacent-pair floor of 15 ΔE: the usable lightness band is
> about 0.30 wide, so four steps land ~0.10 apart. Severity is ordinal, so the
> right test is monotonic lightness, which the ramp passes in both themes
> (0.715 → 0.421 light, 0.892 → 0.619 dark, hue spread 4.4°). Four thin lines
> differing only in lightness are still hard to follow where they cross, so
> severity is encoded three ways at once — lightness, stroke weight, and dash,
> from a fine dotted LOW to a solid heavy CRITICAL — with direct end labels and a
> legend on top of that. Identity never rests on hue.

### Phase 8.6 — Chart time filter and framed Grafana

- [x] `rangeMinutes` on `/api/stats/dashboard` — 5m, 15m, 30m, 1h, 12h, 24h, 7d
- [x] Bucket width derived from the range and returned as `bucketSeconds`, so the
      client labels its own axis instead of guessing
- [x] `date_bin` in place of `date_trunc`, because `date_trunc` only understands
      calendar units and an hourly bucket over a five-minute window is one point
- [x] Segmented range control on the dashboard, governing both time-series charts
- [x] **Metrics** screen — the three Grafana dashboards framed in kiosk mode, a tab
      each, with an *Open in Grafana* escape hatch and its own range control

> **Decisions.** The range governs the charts and not the four headline tiles.
> A time picker that silently rescopes "Open alerts awaiting an analyst" is
> answering a different question from the one the label asks — a work queue is not
> a windowed measurement. The tiles keep their fixed periods and say so.
>
> Bucket widths come off a fixed ladder of round numbers (10s, 15s, 30s, 1m, 2m,
> 5m … 24h), picking the narrowest that keeps the series under 40 points, rather
> than `range / n`. Round widths put boundaries where a reader expects them —
> 14:30:00, not 14:27:43 — and keep axis labels short. Bins are anchored to the
> Unix epoch, in SQL and in the Java zero-fill alike, so two calls a second apart
> return the same buckets and the dense fill lines up instead of double-counting.
>
> Grafana is framed rather than linked because a reviewer who has to find a second
> URL on a port they were not told about will not look at the metrics at all.
> Framing needed one env var: anonymous access was already a recorded decision, so
> `GF_SECURITY_ALLOW_EMBEDDING` exposes no capability that opening Grafana in a tab
> does not already grant. The embed stays the lesser view — kiosk mode drops the
> time picker and panel menus — and every tab links out for real exploration.

> **Verified.** All seven ranges exercised against the running stack: each returns
> 25–31 buckets on round boundaries with the expected width, filters and zero-fill
> intact, and clamping covers absent, zero, negative and over-long input. Six unit
> tests cover the ladder and the clamp, including that width never decreases as the
> window grows. All 22 dashboard PromQL expressions were then re-run against the
> live Prometheus and every one returns series. The embed was confirmed rendering
> in a browser on all three tabs. The smoke test grew to 27 checks, and the new
> framing assertion was itself tested against a Grafana started without
> `GF_SECURITY_ALLOW_EMBEDDING` to confirm it can actually fail.

### Phase 8.7 — Demo reset

- [x] `POST /api/admin/reset` — truncates transactions, alerts, alert rule hits and
      shadow rule hits, and reports the row counts removed
- [x] `POST /api/simulator/reset-counters`, called by the reset so the Simulator
      screen agrees with an emptied store
- [x] **Clear data** control in the top bar, behind a confirmation that names what
      goes and what stays
- [x] Gated behind `overwatch.api.allow-reset`, answering 403 when off

> **What it does not clear, and why.** Rule configuration survives. Rules are
> configuration rather than history, and the seed is a Flyway migration that will
> not re-run on an existing volume — deleting the rows would leave the engine with
> no rules and no route back short of `make clean`. Keeping them is also the more
> useful demo: tune a threshold, clear the traffic, watch the new threshold work.
>
> Micrometer counters survive too, and this one is worth being explicit about
> because it looks like a bug. Prometheus counters are monotonic by contract and
> `rate()` treats a decrease as a process restart, so zeroing them would put a
> false spike in every panel and throw away the history the dashboards exist to
> show. The visible consequence is that just after a reset the dashboard reads zero
> while Grafana still shows the full run. Both are correct — they answer different
> questions — and the confirmation dialog says so rather than leaving it to be
> discovered.
>
> **On shipping an unauthenticated destructive endpoint.** This is the sharp edge
> of the no-authentication gap, so it is a flag rather than a hardcoded `true`:
> `allow-reset` is the single line a real deployment sets to false, and this is the
> first route that should require a role when auth arrives. It ships on because the
> stack exists to be demonstrated, and a reset button that needs a configuration
> change to work is a reset button nobody has.
>
> The truncate names all four tables explicitly instead of using
> `TRUNCATE transactions CASCADE`. CASCADE would wipe whatever happens to reference
> the table, so a future migration adding a table nobody remembers would silently
> start being cleared by this endpoint; naming them means PostgreSQL refuses the
> statement instead, turning silent data loss into a loud error.

> **Verified.** Exercised against the running stack: 34,351 transactions, 9,375
> alerts, 11,248 alert hits and 600 shadow hits cleared, simulator counters zeroed,
> and all 7 rules preserved — including a weight and a state deliberately changed
> beforehand to prove configuration survives. The truncate was first rehearsed
> inside a rolled-back transaction to confirm it leaves `fraud_rules` untouched and
> needs no CASCADE. `ALLOW_RESET=false` was tested on a separate instance and
> answers 403. Driven through the UI end to end, confirming the store empties, the
> counts are reported, and the page refetches immediately rather than showing stale
> figures for a poll interval. The smoke test's new check asserts the route through
> the OpenAPI document rather than calling it — a smoke test that empties the store
> would destroy the data of anyone running it against a live demo.

### Phase 7 — Observability
- [x] Actuator and Micrometer on all services
- [x] Business metrics — latency percentiles, alerts by rule, shadow hits, ZAR flagged
- [x] Prometheus scrape configuration
- [x] Three pre-provisioned Grafana dashboards: pipeline health, fraud overview, rule performance

> Every PromQL expression was cross-checked against the meter names the engine
> actually registers, so the dashboards are not querying metrics that do not exist.
>
> **Correction, after running it.** That check covered names and not *types*, and
> the difference cost four panels. `fraud.detection.latency` was built with
> `publishPercentiles`, which exports a summary of `{quantile}` gauges, while the
> panels call `histogram_quantile()` over `_bucket` series that only a histogram
> produces — so the three detection-latency panels, the most interesting
> operational metric here, read "No data". `http.server.requests` had the same
> shape: Spring publishes it as a plain timer unless
> `percentiles-histogram` is enabled, so the HTTP latency panel was empty too.
> Both are fixed and all 22 expressions now return series. The lesson is that
> matching a metric name proves less than it appears to: a Prometheus query also
> depends on the metric's type, and nothing in the name says what that is.

### Phase 7.5 — Quality & CI
- [x] JaCoCo coverage gate bound to `verify`
- [x] SpotBugs + find-sec-bugs, tuned exclusions with justifications
- [x] PMD ruleset — complexity, dead code, CPD
- [x] Domain unit tests, including shadow-score isolation
- [x] GitHub Actions: build/quality, migrations against real Postgres, full stack + smoke test
- [x] CodeQL on push and weekly
- [x] Dependabot for Maven, npm, Docker and Actions
- [ ] First green CI run

### Phase 8 — Delivery
- [x] Postman collection — 22 requests in 6 folders, ordered as a guided tour
- [x] Environment file, with the alert id captured automatically
- [x] README with a genuine one-command quickstart
- [x] Verification pass — cold `docker compose up`, smoke test 20/20, `mvn verify` green

---

## What the first real run caught

Recorded because it is the honest argument for running the thing. Everything below
had been read, reviewed and reasoned about; none of it survived contact with a
running stack, and none of it would have been found by more reading.

**Optional timestamp filters made both list endpoints 500.** `/api/transactions`
and `/api/alerts` — the two screens the dashboard is built around — failed with
`could not determine data type of parameter $5`. The cause is that PostgreSQL
infers a parameter's type from the context it appears in, and `:since IS NULL`
offers none, so the driver sends it untyped and the server rejects the statement.
The string filters in the same query survive the identical shape only because an
untyped parameter falls back to `text`, which happens to compare correctly against
`VARCHAR`. Nothing rescues a timestamp. Fixed by making the lower bound
unconditional and expressing "no window" as `Instant.EPOCH`; the rule left behind
is never to write `:param IS NULL` for a parameter that is not a string.

**JaCoCo could not read Java 25 bytecode.** `mvn verify` failed on the first
module with "Unsupported class file major version 69". The parent POM already
pinned SpotBugs and PMD forward for exactly this reason and explained why in a
comment — JaCoCo was simply missed. 0.8.13 is the first release that handles it.

**Money was formatted wrongly on every screen.** `toLocaleString('en-ZA', …)`
followed by `.replace(/,/g, ' ')` assumed en-ZA groups thousands with commas the
way en-US does. It does not: en-ZA groups with a non-breaking space and uses a
comma as the *decimal* separator, so the replace deleted the decimal point.
R863.11 rendered as "R863 11". The formatter now groups by hand.

**The smoke test reported a healthy stack as broken, then intermittently.** Two
independent faults. It read reassigned host ports from `$OW_API_PORT` and friends,
which only ever exist in `.env` — a file docker compose reads and a shell script
does not — so 13 of 20 checks probed the wrong ports. And its helpers piped
responses into `grep -q`, which exits at the first match; the writer upstream then
dies of EPIPE (the docker CLI exits 255) and `set -o pipefail` reports the whole
pipeline as failed even though the match succeeded. That only bites once a
response outgrows the 64KB pipe buffer, which is why `/actuator/health` always
passed and `/actuator/prometheus` failed at random.

**A stale image, not a bug.** `/api/simulator/*` returned 404 because the running
containers predated the commit that added the proxy — `docker compose up` without
`--build` reuses cached images. `make up` passes `--build`; plain `docker compose
up`, which the README also offers, does not.

---

## Decisions taken

Recorded because the reasoning matters more than the choice.

**JDK 25 + Spring Boot 4.1.1** (2026-09-09). Moving to Java 25 forced a Spring Boot
upgrade: 3.3.4 predates Java 25 support, which begins at 3.5.5. That left a choice
between 3.5.16 and 4.1.1 — and 3.5.16 was announced as the final OSS release of the
3.5.x line, so the conservative option was also the end-of-life one. We took 4.1.1
because the migration cost is near zero at this point in the build (three boilerplate
application classes and some YAML, no business logic yet), and because shipping on a
supported line is easier to defend than shipping on a branch that stopped receiving
open-source releases three months ago. `springdoc-openapi` moves to 3.1.1 to match.

**Flyway owns the schema** (2026-09-09). Chosen over `schema.sql`, closing D5.
Migrations are versioned and immutable, apply identically on a fresh volume, an
existing one and in CI, and give a real upgrade path rather than a recreate-only
one. They live in the `common` module so both datasource-owning services apply one
source of truth; Flyway's schema-history lock makes concurrent startup safe.
Hibernate stays on `ddl-auto: validate`, so entity/migration drift fails fast at
startup instead of corrupting data quietly. The Postgres entrypoint init-script
mount was removed as redundant.

**Build-integrated quality gates over a SonarQube server** (2026-09-09).
SonarQube Community needs 4GB of RAM and its own database, which roughly doubles
the stack's footprint and works against the one-command startup the project is
built around. JaCoCo, SpotBugs with find-sec-bugs and PMD run inside `mvn verify`
and fail the build, which is a stronger signal than a dashboard someone ran once:
enforced, reproducible, and committed to the repository. CodeQL and Dependabot
cover security scanning and dependency CVEs natively on GitHub at no
infrastructure cost. SonarQube Cloud remains an easy addition if the repository
is made public.

---

## Deferred decisions

Decisions consciously postponed. Each is recorded with the reason, so none of them
becomes an accident.

| # | Decision | Status | Reasoning |
|---|---|---|---|
| D1 | Live updates: WebSocket vs polling | Deferred | Polling every 5s is adequate for a demo and far simpler. Revisit if the dashboard feels sluggish. |
| D2 | Authentication / authorisation | Deferred | Out of scope for the brief. The BFF is the natural seam — note it in the README as a known gap rather than half-implementing it. |
| D3 | Risk score weighting model | Deferred | Starting with hand-set weights per rule. A learned model is interesting but unverifiable in the time available. |
| D4 | Redpanda Console in the compose stack | Deferred | Useful for demonstrating the event stream, but another container. Add only if the stack stays light. |
| D6 | Multi-currency support | Deferred | Everything is ZAR. The `currency` column exists so this is additive, not a rewrite. |
| D7 | Alert disposition workflow | Deferred | Alerts have a status field. Whether analysts can transition it from the UI depends on remaining time. |
