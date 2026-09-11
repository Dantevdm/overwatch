# Overwatch — Project Plan & Progress

A fraud rule engine service. This document tracks what we are building, what is
done, and which decisions we have deliberately postponed.

Last updated: 2026-09-11

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

Three Spring Boot services, one message broker, one database, one UI, and a CLI.

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

> **Verified.** The migrations were applied to a real PostgreSQL 16 instance:
> 6 tables, 32 indexes, 7 seeded rules. 11 constraint assertions passed (amount,
> channel, weight bounds, rule state, name uniqueness, severity, foreign keys),
> cascade delete was confirmed to clean up alerts, hits and shadow hits, and the
> velocity query was checked against 60,000 rows — Index Only Scan, 0.027 ms.

### Phase 3 — Fraud engine
- [x] `FraudRule` interface, `RuleContext`, `RuleParameters`, `TransactionHistory` port
- [x] Seven rule implementations (six live, one shipped in SHADOW)
- [x] `RuleEngine` — score accumulation, shadow isolation, per-rule failure containment
- [x] JPA entities and repositories for all six tables
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

### Phase 8.8 — Making the dashboards answer their own questions

The three dashboards had the right subjects and the wrong contents. Rewritten, and
the gaps that made the rewrite necessary are the interesting part.

- [x] **Consumer lag**, published-against-processed, dropped-by-reason and error
      log rate — none of which existed on any panel
- [x] **Time-picker correctness**: every count and pie now uses
      `increase(...[$__range])` instead of a lifetime total
- [x] **A real risk-score distribution**, replacing a mean plotted under a
      "distribution" title
- [x] **Flagged-amount percentiles**, replacing an average of a heavily skewed
      quantity
- [x] JVM heap as a fraction of max, GC pause, CPU and Hikari pool
- [x] The unused `channel` dimension surfaced
- [x] Severity colours fixed to the UI's ramp; units, thresholds and a description
      on every panel; layout holes closed; cross-dashboard links; a service filter
- [x] `scripts/verify-dashboards.py`, wired into CI

> **A dashboard with a time picker that ignores it.** Four tiles and a pie read
> `sum(transactions_processed_total)` — a counter since process start. Selecting
> "last 5 minutes" changed nothing about them, so the dashboard confidently
> answered a question nobody had asked. This is the same trap as the reset button
> showing zero next to a full Grafana history, except here there was no honest
> reading available: the tile and the picker directly contradicted each other.
>
> **A mean under a title promising a distribution.** "Risk score distribution" was
> a `histogram` panel fed `fraud_risk_score_sum / fraud_risk_score_count`. A mean
> is the one statistic that cannot show what the panel exists to ask — whether
> scores pile up against the threshold — and no bucket series existed to plot,
> because the meter was a plain summary. Fixing the panel therefore meant fixing
> the meter, and the README had been claiming it was a histogram all along.
>
> The same applied to flagged amounts, where a live reading made the cost obvious:
> mean R14 900 against a median of R553, a factor of 27. The old panel showed only
> the mean.
>
> **`_max` is a rolling window; `_sum` and `_count` are cumulative.** Noticed
> because `fraud_amount_flagged_zar_max` read R7 995 while the lifetime mean read
> R14 942 — a maximum below the average, which is impossible for a real maximum.
> Micrometer decays the max over a short step, so a panel putting the two side by
> side compares different time semantics without saying so. Another instance of
> names agreeing while semantics do not.
>
> **The most important metric for a Kafka pipeline was absent.** "Pipeline Health"
> could not answer whether the pipeline was keeping up: no consumer lag anywhere.
> It was being scraped the whole time — `kafka_consumer_fetch_manager_records_lag`,
> reading 0 at 128 tx/s. Along with it, `logback_events_total{level="error"}`, the
> Hikari pool, GC and CPU were all already exported and all unused, while the panel
> grid had a 6×8 hole in it.

> **Verified.** All 45 panel queries return series against the live stack, checked
> by the new script rather than by eye. Both new bucket sets confirmed at their
> exact intended edges (`le="0.75"` for the severity break; round-ZAR bands). The
> Fraud Overview was read panel-by-panel in a browser to confirm rendering, units
> and values — `currencyZAR` formatting as R177M, `percentunit` as 24.89%, and the
> percentile spread above. The barcharts needed an instant vector reduced to a
> category field before they drew anything, which was proved by sampling the canvas
> (31% and 41% ink) since bar labels are painted, not DOM text. The verification
> script itself was negative-tested by injecting a typo into a panel query and
> confirming exit 1, then exit 0 once restored. Smoke test 28 → 35 checks, and its
> new bucket assertions were checked against a deliberately wrong bucket edge to
> confirm they discriminate rather than pass vacuously.
>
> One genuine transient found and documented rather than smoothed over: the
> per-partition lag gauge does not exist for roughly the first half-minute after an
> engine restart, because it is created only once the consumer has been assigned a
> partition and completed a fetch — later than the container reports itself healthy.
> The panel descriptions say so, and the smoke test polls instead of asserting once,
> because a flaky check is worse than no check.

### Phase 8.9 — A transaction stream that is not a straight line

The generator was producing traffic whose *content* was realistic and whose
*shape* was not. Three separate uniformities, each found by measuring rather than
by reading the code.

- [x] **Diurnal volume curve** — a 24-point hourly shape interpolated to the
      minute in SAST, normalised to average exactly 1.0 so the configured rate
      stays the daily mean rather than becoming a ceiling
- [x] **Poisson arrivals** (Knuth's method) replacing a fixed batch per tick
- [x] **Weighted category mix** — `CATEGORY_WEIGHTS`, drawn by cumulative weight,
      replacing a uniform draw over the merchant list
- [x] **Real timestamps** — ordinary traffic is stamped with the current instant
      rather than being scattered across a synthetic business-hours window
- [x] `Clock` injected into the generator, so the time-dependent behaviour is
      unit-testable instead of dependent on when the suite happens to run
- [x] `simulator_diurnal_weight` exported, so the curve is visible next to the
      throughput it explains
- [x] `DiurnalCurveTest` (4 assertions) and three new generator assertions

> **A flat line, quantified.** The suspicion was that the throughput chart looked
> synthetic. Measured over ten minutes it was worse than it looked: 28.0 tx/s with
> a standard deviation of 0.01 — a coefficient of variation of 0.0004, which is
> not "low variance", it is a ruled line with a rounding error. After Poisson
> arrivals plus the diurnal curve, cv is 0.095 and the observed mean tracks the
> curve's current multiplier.
>
> **The pie chart was describing the fixture file.** The category mix came out at
> exactly merchant-count-per-category / 36 — because merchants were drawn
> uniformly from a flat list, so the mix was a function of how many of each kind I
> had happened to type in. Groceries had six entries and therefore 16.7% of all
> spend. It now draws by weight, and a live 20-minute sample lands within half a
> point of the configured weights (groceries 21.5% against 22, liquor 3.0% against
> 3).
>
> **47 of 1 316 transactions were dated in the future.** The old code stamped
> ordinary traffic by picking a random hour inside a business-hours window on
> *today's* date, which is in the future for most of the day. It also produced a
> perfectly uniform 07:00–21:59 spread of `occurred_at`, meaning any query grouping
> by hour showed a rectangle. Both are gone: 0 future-dated rows and 0 rows stamped
> after their own insert, across 743 transactions from the current build.
>
> One deliberate consequence: ordinary traffic can now trip the late-night rule
> between 01:00 and 04:59 SAST, because it is genuinely late at night. A rule that
> only ever fires on injected data has not been demonstrated — but it does mean the
> alert rate is time-of-day dependent, which the README and demo guide both say.
>
> `Map.of` caps at ten pairs and there are eleven categories, so the weight map is
> `Map.ofEntries`. Worth recording only because the failure is a compile error with
> a message that does not mention the limit.

### Phase 8.10 — The broker made visible

- [x] **Redpanda Console** in the compose stack (`redpandadata/console:v2.7.2`),
      health-checked, on `OW_CONSOLE_PORT` (8090)
- [x] Added to `scripts/preflight.sh`, so it participates in conflict resolution
      like the other published ports
- [x] **External tools** group at the foot of the dashboard sidebar — Grafana,
      Prometheus, Redpanda Console, API docs
- [x] `make urls` and preflight both list it

> **Why a container for a UI nobody strictly needs.** Streaming is the one step in
> this pipeline that otherwise has to be taken on faith: transactions go into a
> topic, alerts come out of another, and there is nothing to look at in between.
> The console shows the actual JSON on both topics, the partitions, and the
> engine's consumer-group lag moving. It is read-only, and — like everything else
> here — unauthenticated, which is stated rather than implied.
>
> **Two URLs for one API, and the bug that nearly shipped.** The sidebar links are
> built at Vite build time and read by the *browser*, so they need host ports. The
> dev server's proxy target is resolved *inside* the UI container, so it needs a
> compose hostname. Reusing `VITE_API_URL` for the Swagger link would have
> overridden `http://fraud-api:8080` with `http://localhost:8081` and broken every
> API call in the app. Caught before deploying; the two are now
> `VITE_API_URL` and `VITE_API_PUBLIC_URL`, each with a comment saying which
> audience it serves and what breaks if they are confused.

### Phase 8.11 — Repository restructure

The root directory had grown to fifteen entries, five of them Maven modules sitting
beside `database/`, `observability/`, `postman/` and `quality/`. Nothing was
misplaced exactly; there was just no answer to "where does a new thing go".

- [x] `services/` — the five Maven modules
- [x] `infra/` — `database/`, `observability/`
- [x] `tools/` — `postman/`, `quality/`, and later `overwatch-cli/` (Phase 8.15)
- [x] All moves via `git mv`, so `git log --follow` still works per file
- [x] `_to_delete/` (29 stale pre-refactor files) and the unused `database/init/`
      removed
- [x] `pom.xml`, `Dockerfile`, `docker-compose.yml`, CI, preflight, the smoke test
      and `verify-dashboards.py` all updated to match

> **Verified end to end, because this is the change class that compiles and then
> fails to package.** `mvn clean verify` BUILD SUCCESS across all five modules (80
> tests), all four images rebuilt, nine containers healthy, smoke test 35 of 35,
> `verify-dashboards.py` 45 of 45.
>
> Three faults the verification caught, none of which a compile would have:
> the parent POM stopped resolving (Maven guesses `../pom.xml`; the modules are now
> two levels down, so all five needed an explicit `<relativePath>`);
> `COPY database database` in the Dockerfile — missed on the first pass because the
> grep I used to find path references required a trailing slash; and the jar-collection
> loop still globbing `"$m"/target/*.jar` instead of `"services/$m"/...`, which
> surfaced only because the build's own Boot-jar guard failed loudly rather than
> producing an image that dies at runtime with "no main manifest attribute".

### Phase 8.12 — Documentation and the demo guide

- [x] `docs/DEMO.md` — a fifteen-minute running order with what to say at each
      step, a five-minute cut, anticipated questions, and mid-demo recovery
- [x] README updated for the new layout, the generator, the console and the
      sidebar links; test count 68 → 80, smoke 20 → 35
- [x] Architecture document: stale project tree replaced, the never-implemented
      `/v1` path segment removed (9 places), and a banner naming the README as the
      as-built authority

> **Two endpoints the README documented did not exist.** Writing the demo guide
> meant running every command in it, which is how `POST /api/rules/replay` and
> `GET /api/rules/{id}/stats` were found to be 404s — the real routes are
> `POST /api/replay` and `GET /api/rules/performance`. Both had been in the README
> for weeks. Nothing catches a wrong path in prose except executing it, which is
> the argument for a demo script being made of runnable commands rather than
> screenshots.
>
> **`make urls` had drifted from preflight.** It kept its own copy of the
> port-to-URL logic and was still printing four URLs after the stack grew a fifth
> published port, so the Redpanda Console was invisible to the one command that
> exists to tell you where things are. Preflight gained a `--urls` flag and the
> Makefile now delegates, so there is one place that knows.
>
> The replay demonstration is worth keeping as a number: over the same 21 420
> transactions, a R50 000 high-value threshold would have fired 235 times and
> R30 000 would have fired 250 — fifteen more alerts for a 40% cut in the
> threshold, because most of the value above R30 000 is already above R50 000.
> That is a more interesting answer than the one a person would guess, which is
> the case for the endpoint existing.

### Phase 8.13 — The engine's own tests, and idempotent consumption

fraud-engine — the service the project is named after — had no tests at all: 0
files against 11 classes. The module POM justified a 0.00 coverage floor on the
grounds that "the service modules are wiring", which is fair for repositories
and true of the consumer's transport role, but not of the 221-line
TransactionProcessor holding every decision between the rule engine and the
outside world.

- [x] `TransactionProcessorTest` — 15 assertions, no Spring context
- [x] `TransactionConsumerTest` — 4 assertions on the two failure branches
- [x] Redelivery guard on the transaction id, with `transactions.redelivered`
      registered at zero
- [x] `V3__enforce_one_alert_per_transaction.sql` — unique constraints on
      `fraud_alerts(transaction_id)` and `shadow_rule_hits(transaction_id, rule_id)`
- [x] Coverage floor for the module raised 0.00 → 0.80 against 93.9% actual

> **The comment that gave it away.** TransactionConsumer's own javadoc says all
> the behaviour lives in TransactionProcessor, "which is what lets the pipeline
> be tested without a broker". The design was deliberately made testable and
> then not tested. TransactionProcessor is now at 100% line coverage and the
> consumer at 100%; the only uncovered class left is JpaTransactionHistory,
> seven lines of delegation to a repository, which is what "wiring" actually
> looks like.
>
> **Kafka delivers at least once, and nothing here was idempotent.** On a
> rebalance, or a crash between processing a record and committing its offset,
> the same transaction is redelivered. The transaction row was safe — its id is
> the primary key and JPA merges on it — but `persistAlert` minted a fresh UUID
> every time and `shadow_rule_hits` has an auto-increment key, and there was no
> unique constraint on either. Two alerts for one card movement double-count
> every figure an analyst reads and every rule-performance statistic used to
> decide whether a rule earns its place.
>
> It had not actually happened yet: a check of the live database found 0
> duplicates, because nothing had crashed mid-batch. A latent correctness bug
> rather than an active one, which is the kind worth fixing before a
> demonstration rather than after.
>
> **Verified by forcing the redelivery rather than reasoning about it.** With
> the producer paused, `rpk group seek fraud-engine --to start` rewound the
> consumer group from offset 5330 to 0 and the engine re-consumed the entire
> topic: 5330 redeliveries recognised and logged, `transactions_redelivered_total`
> at 5330, and 0 duplicate alerts and 0 duplicate shadow hits afterwards.
> Before the fix that run would have produced 556 duplicate alerts.
>
> The constraints are not redundant with the guard. Two engine instances can
> both pass an application-level check for the same record; only the database
> can settle that race. The loser's insert fails, its transaction rolls back,
> Kafka redelivers, and the retry takes the skip branch — so the two mechanisms
> converge rather than overlap. Both migration statements deduplicate before
> adding their constraint, so V3 applies to a volume that already accumulated
> duplicates instead of failing half-way.
>
> Negative-tested rather than assumed: removing the guard fails
> `redeliveryIsSkipped` and `redeliveryDoesNotDoubleCount`; setting the coverage
> floor to 0.99 fails the build with "lines covered ratio is 0.93"; and the
> migration was run against a real PostgreSQL 16 seeded with duplicate alerts,
> duplicate shadow hits and a NULL-rule pair, then checked that all three are
> rejected afterwards — the NULL case needs `UNIQUE NULLS NOT DISTINCT`, since
> the default treats every NULL as unique and would have left exactly the
> duplicates the constraint exists to prevent.

### Phase 8.14 — Making CI green, and what it had been hiding

CI had been red on every run. The quality gate and the migration job were always
green; the Docker stack job reported six smoke-test failures with one cause.

- [x] Health check on `transaction-simulator` — it had none
- [x] `docker compose up -d --wait` in CI
- [x] Smoke test waits for every service, not only fraud-api
- [x] Scrape-target count polled rather than asserted once
- [x] `verify-dashboards.py` verifies in waves instead of per-query retries
- [x] A warm-up allowance, separate from ALLOW_EMPTY

> **A stack seventeen seconds from ready, reported as broken.**
> transaction-simulator is the last service to become useful — it waits for the
> engine, boots Spring, then connects a producer — and with no health check
> `docker compose up -d` returned while it was still starting. In the failing
> run the smoke checks ran at 20:38:47 and the simulator served its first
> request at 20:39:04. The six failures were its health, its status through the
> API, its pattern list, its Prometheus endpoint, "3 of 4 scrape targets up",
> and consumer lag — the last one downstream of the rest, since no producer
> means no fetch and the per-partition gauge is never created.
>
> The lesson worth keeping: a service that other things wait on needs a health
> check even when nothing scrapes it from the host. The smoke test's readiness
> gate waited only for fraud-api, which is ready around forty seconds earlier.
>
> **Fixing it exposed a second failure that had been hidden behind the first**,
> because verify-dashboards had never once run in CI. "Shadow hits, by rule"
> returns nothing on a cold stack: the rule shipped in SHADOW is
> AMOUNT_DEVIATION, which needs 10 prior transactions on the same card and then
> a 5x outlier. With 2000 cards at 5/s a card is seen every 400 seconds, so ten
> of them is about 67 minutes — an hour past the end of any CI run.
>
> That is a warm-up, not a fault, so it gets its own allowance list and prints
> as `[warm]` rather than `[zero]`. ALLOW_EMPTY means "nothing has gone wrong",
> which is good news; this means "not enough has happened yet", which is
> neither. It is keyed on the correct metric name, which is what stops it
> excusing a typo — negative-tested by misspelling the metric and confirming it
> still fails.
>
> **And the verifier itself was the slowest thing in CI.** It retried each empty
> query where it stood, so a cold stack multiplied 18 seconds by the number of
> quiet panels: the step was still running after twelve minutes. The waiting is
> for the stack to warm up rather than for any one query, so it now runs a pass,
> collects the empties, and waits once between passes. 0.13s on a warm stack.

### Phase 7 — Observability
- [x] Actuator and Micrometer on all services
- [x] Business metrics — latency percentiles, alerts by rule, shadow hits, ZAR flagged
- [x] Prometheus scrape configuration
- [x] Four pre-provisioned Grafana dashboards: pipeline health, fraud overview, rule performance, logs

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
>
> **Second correction, in Phase 8.8.** "All expressions return series" was still a
> claim about a check run by hand once, which is the same weakness one level up —
> it says nothing about the next edit. It is now `scripts/verify-dashboards.py`,
> run in CI against the live stack, and the dashboards it checks are the rewritten
> ones. The panels that returned series were also, in several cases, returning the
> wrong thing: a query can be valid, non-empty and still answer a question the
> panel title does not ask.

### Phase 7.5 — Quality & CI
- [x] JaCoCo coverage gate bound to `verify`
- [x] SpotBugs + find-sec-bugs, tuned exclusions with justifications
- [x] PMD ruleset — complexity, dead code, CPD
- [x] Domain unit tests, including shadow-score isolation
- [x] GitHub Actions: build/quality, migrations against real Postgres, full stack + smoke test
- [x] CodeQL on push and weekly
- [x] Dependabot for Maven, npm, Docker and Actions
- [x] First green CI run — run 34455016849, all three jobs, 2026-09-10. It took
      three faults stacked so each hid the next; see Phase 8.14.

### Phase 8 — Delivery
- [x] Postman collection — 24 requests in 7 folders, ordered as a guided tour
- [x] Environment file, with the alert id captured automatically
- [x] README with a genuine one-command quickstart
- [x] Verification pass — cold `docker compose up`, smoke test 20/20, `mvn verify` green

> Those figures are what Phase 8 measured. The current ones, after everything
> below, are **138 tests across six modules, 54 smoke checks, 55 dashboard
> queries, twelve containers**. Every number quoted inside a phase note is what
> that phase measured and is left alone; this line is the one to read for today.

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

### Phase 8.15 — `ow`, the console as a terminal client

- [x] `tools/overwatch-cli` — a sixth Maven module, picocli, shaded to one 2.9MB jar
- [x] `bin/ow` launcher that finds a Java 25 JVM
- [x] `status`, `top`, `alerts`, `txn`, `cardholder`, `rules`, `sim`, `sweep`, `reset`
- [x] `make cli`
- [x] The CLI's POM is copied into the Docker build but never built there

> It talks to the same API the dashboard does. No second data path, no direct
> database access, no privileged endpoint that exists only for it — so anything it
> shows can be checked in the UI, and anything it changes shows up there.
>
> Three decisions carried the design. **Colour is an accelerator, never the
> carrier**: a severity always prints its label, because this output goes into
> pipes and terminals whose palettes a reader cannot distinguish, and "the red
> ones" is not a specification. **Tables measure what they show**, ignoring escape
> sequences, which is why columns line up even when half of them are coloured.
> **`ow reset` refuses to guess** — it asks for the word `clear` rather than a
> keystroke, and with no terminal to ask at it refuses outright and names `--yes`,
> because the API behind it is destructive and unauthenticated.
>
> **What the first real run caught.** Money printed as `R420 79`: the en-ZA locale
> uses a comma as the *decimal* separator, so replacing commas with spaces ate the
> decimal point. `ow rules weight` returned 400 because the client sent the fields
> as a query string and the API takes a body. And `ow sweep` offered rules that
> cannot be swept, because it looked for a key the API does not return — the API
> returns one list with a null parameter on the ones it cannot sweep.
>
> **And it broke the Docker build.** Maven reads every module named in a reactor
> before `-pl` can select a subset, so the image failed with "child module
> /build/tools/overwatch-cli does not exist". The fix is to copy the CLI's POM
> only and restrict the build to the three services — the CLI is a developer tool
> and has no business in a service image.

### Phase 8.16 — The console as the thing it is imitating

- [x] Capitec-derived theme, brand mark eye-matched rather than copied
- [x] A mock sign-in screen, and a splash
- [x] A welcome tour, once per browser

> A demo console that looks like a generic admin template invites the reader to
> evaluate the styling. One that looks like the product invites them to evaluate
> the product.
>
> The sign-in screen is the one piece of this that needed an argument rather than
> a decision, because a bank-branded login page is exactly the shape of a
> credential harvester. It makes no network call, has no endpoint behind it,
> discards the password on submit and never stores it, sets `autoComplete="off"`
> on the form and `new-password` on the field, and carries a notice that cannot be
> dismissed saying it authenticates nothing. The LICENSE carries the matching
> trademark notice: not affiliated with Capitec, and the mock sign-in must not be
> deployed where it could be mistaken for a bank's.

### Phase 8.17 — Publishing from the database

- [x] `V10__publish_from_the_database.sql` — `alert_outbox`, with partial indexes
- [x] `OutboxEntity`, `OutboxRepository`, `OutboxPublisher`, `OutboxKafkaConfig`
- [x] `FOR UPDATE SKIP LOCKED` claiming, a 200ms poll, hourly pruning
- [x] `outbox_pending`, `outbox_oldest_pending_seconds`, published/failed counters
- [x] Dashboard row, smoke checks, and the reset truncating the new table
- [x] `PipelineIT` — a real Redpanda and a real PostgreSQL under Testcontainers

> This closes a gap the plan never listed and the README did: the engine wrote an
> alert to PostgreSQL and published it to Kafka inside the same transaction, and
> the send is asynchronous, so a rollback after the send began left an alert on the
> topic that did not exist in the database.
>
> The alert and its outbox row are now written in one transaction and a poller
> publishes from that table. The trade is at-least-once delivery — a publish that
> succeeds and a mark that does not is sent again — which is the better gap, and
> both the backlog and its age are exported so it is visible rather than assumed.
>
> **The integration test earned its place immediately.** Declaring the outbox
> `ProducerFactory` makes Boot's Kafka producer auto-configuration back off
> entirely, which no unit test could have shown. And `buildProducerProperties()`
> reads only the property, ignoring `KafkaConnectionDetails` — so the outbox
> producer dialled `localhost:19092` while the rest of the context used the
> container the test had started. Alerts were persisted and none were published,
> and the fix is `ObjectProvider<KafkaConnectionDetails>`.
>
> **A comment that claimed too much.** I had written that the published bytes are
> byte-identical to what the transaction wrote. They are not: `jsonb` stores a
> parsed document, so whitespace and key order are normalised. The *document* is
> preserved and invalid JSON is rejected at write time, which is the trade `jsonb`
> makes against `text`, and six comments now say that instead.

### Phase 8.18 — Logs beside the metrics

- [x] Loki, and Grafana Alloy rather than the EOL Promtail
- [x] A Logs dashboard, with service, level and search variables
- [x] `verify-dashboards.py` extended to LogQL and Loki datasources

> Four decisions in `config.alloy` are what make the result readable rather than
> merely collected. **A Java stack trace is one event, not forty** — its absence
> is why logs-in-Grafana is usually disappointing. **Level is a label and nothing
> else is**, because promoting the logger would multiply Loki's streams by every
> class that logs. **A missing level is `UNKNOWN`, not blank**, so Postgres and
> Redpanda do not vanish behind a filter. **Grafana's own query log is dropped**,
> because opening the logs dashboard produced log lines, which the dashboard then
> displayed, which on a ten-second refresh buried the application logs under a
> running commentary of the act of reading them.
>
> Loki runs unauthenticated and is deliberately not published to the host by the
> base stack: a published port would be an unauthenticated read of every log line
> in it. Alloy's Docker socket is mounted read-only, and the README says plainly
> that read access to that socket is effectively root on the host.

### Phase 8.19 — One transaction across three services and a broker

- [x] Tempo, and `spring-boot-starter-opentelemetry`
- [x] Kafka observation on the template and the listener
- [x] Loki derived field → Tempo, and `tracesToLogsV2` back the other way

> Three Boot 4 changes each failed silently on their own. The tracing bridge and
> the OTLP exporter on the classpath auto-configure **nothing** without the
> starter — Boot 4 no longer configures what is merely present. The OTLP
> properties moved from `management.otlp.tracing.*` to
> `management.opentelemetry.tracing.export.otlp.*`, and the old names bind to
> nothing without complaint. And the default transport is HTTP on 4318 while Tempo
> listens on gRPC 4317.
>
> The span worth having is the one across the broker, and it does not come for
> free: a publish is not a call, so there is no stack to walk. It works because
> `observation-enabled` is set on both the template and the listener, which writes
> the trace context into the record headers.

### Phase 8.20 — The window as a file

- [x] `ExcelReport` (Apache POI), `PdfReport` + `PdfCanvas` + `PdfCharts` (PDFBox)
- [x] `GET /api/reports/fraud-summary.{xlsx,pdf}`, a Reports screen, `ow report`
- [x] Postman folder, smoke checks

> Everything on the reports screen exists elsewhere in the console. What does not
> exist elsewhere is a file, and without one the export is a screenshot of a
> browser tab, which loses the figures, the window they cover and the date.
>
> Built by the API rather than the browser, so one definition of a report serves
> the console, `curl`, Postman, the CLI and a scheduler, and the figures come from
> the same `StatsService` the dashboard reads. A client-side exporter would be a
> second implementation of every aggregate, drifting quietly from the first.
>
> A workbook is for someone who will work on it, so the charts are **native Excel
> charts bound to the cells** and the figures are numbers carrying a cell format
> rather than pre-formatted strings. A PDF is for someone who will read it, so the
> charts are **vectors** — no charting library, because the report needs a bar
> chart and a line chart and a general charting dependency would bring an AWT
> surface and a theming API to draw four bars.
>
> **Two faults the drawing code had to be shown to find.** Bars were drawn half a
> row below their labels, because `rect` takes a top edge and `text` a baseline.
> And the largest value in a chart — the one a reader looks for — ran off the page,
> because the bar filled the full width and its label was written past the end of
> it. Both were obvious in a rendered page and invisible in the source.
>
> **And a check that lied about where the fault was.** The smoke test piped curl
> into `head -c 4` to read a magic number. head exits at four bytes, curl dies of
> EPIPE behind it, and `pipefail` reports a failed pipeline — but only when the
> body arrives slowly enough that curl is still writing, which is true on CI
> hardware and false on every machine I could reproduce on. The check now
> downloads to a file and asserts the status and the bytes separately, so the
> next failure names its own cause.

### Phase 8.21 — Somewhere for a message to go when it cannot be processed

- [x] `transactions.DLT`, declared with 30-day retention
- [x] `DeadLetterConfig` — a `DefaultErrorHandler` with a publishing recoverer
- [x] Three attempts a second apart; unreadable records skip them
- [x] `transactions_dead_lettered_total`, registered at zero, beside the existing counter
- [x] Dashboard panel, smoke checks, and two more assertions in `PipelineIT`

> This closes the last of the gaps the README listed. The old behaviour logged,
> counted and dropped, which kept the partition moving — the important half — but
> left a lost transaction with nothing behind it but a log line and a number. A
> counter tells you that you lost something; a dead-letter topic tells you what.
>
> **The listener had to stop swallowing.** It catches, counts and rethrows now, so
> the container's error handler can retry and then recover the record. The two
> counters are kept apart deliberately: `transactions_failed_total` counts failed
> attempts including retries, and `transactions_dead_lettered_total` counts records
> given up on. Equal values mean the retries are buying nothing.
>
> **The test that was written wrong first.** The DLT test published
> `{"this":"is not a transaction"}` expecting it to fail deserialisation. It does
> not: Jackson is not configured to object to a document with none of the expected
> fields, so it parsed cleanly into a Transaction with every field null, failed
> later in processing, and was dead-lettered as the *parsed object* rather than the
> original bytes. Two lessons, one in each direction. The claim in the
> configuration's own comment — "the record published is the original one" — was
> true for one of the two failure paths and not the other, which is the same
> overstatement the outbox comments needed correcting for in Phase 8.17. And the
> two paths are genuinely different and both worth a test, so there are now two.
>
> **And a third thing the integration test found.** Both DLT tests read the same
> topic from the beginning, so whichever ran second asserted against the record the
> first one left there. Fixed by matching on content rather than taking the first
> record — which is the shape every assertion against a shared topic needs.
>
> **What the live stack then showed.** A malformed record produced a dead letter
> whose value was byte-identical to what was sent, with the original topic,
> partition, offset and the full exception in its headers — and
> `transactions_failed_total` stayed at zero. The listener's null branch never ran:
> the container routes a deserialisation failure straight to the error handler
> without invoking the listener at all. That branch is now documented as the
> backstop it is rather than as the mechanism it is not.

---

## Deferred decisions

Decisions consciously postponed. Each is recorded with the reason, so none of them
becomes an accident.

| # | Decision | Status | Reasoning |
|---|---|---|---|
| D1 | Live updates: WebSocket vs polling | Deferred | Polling every 5s is adequate for a demo and far simpler. Revisit if the dashboard feels sluggish. |
| D2 | Authentication / authorisation | Deferred | Out of scope for the brief. The BFF is the natural seam — note it in the README as a known gap rather than half-implementing it. A **mock** sign-in screen ships with the UI as stage dressing: it makes no network call, has no endpoint, discards the password, and carries a notice saying it authenticates nothing. It is deliberately not a partial implementation, because half-built auth is worse than none — see Phase 8.16. |
| D3 | Risk score weighting model | Deferred | Starting with hand-set weights per rule. A learned model is interesting but unverifiable in the time available. |
| D4 | Redpanda Console in the compose stack | **Done — Phase 8.10** | The stack stayed light enough. Shipped read-only on `OW_CONSOLE_PORT` (8090), with its git integration pointed at a read-only local mount rather than a GitHub PAT, so no credential appears in `docker-compose.yml`. |
| D6 | Multi-currency support | Deferred | Everything is ZAR. The `currency` column exists so this is additive, not a rewrite. |
| D7 | Alert disposition workflow | Deferred | Alerts have a status field. Whether analysts can transition it from the UI depends on remaining time. |
