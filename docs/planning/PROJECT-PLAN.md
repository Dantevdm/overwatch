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
- [ ] Verify a cold `docker compose up` on the host (needs Docker — see note)

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
- [ ] JPA entities and repositories
- [ ] SA merchant and card reference data (moves with the simulator, Phase 4)

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
- [ ] Transactions, alerts and rules controllers with filtering and pagination
- [ ] Stats endpoints for the dashboard
- [ ] Rule performance statistics
- [ ] Replay / what-if endpoint
- [ ] OpenAPI / Swagger

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

### Phase 7 — Observability
- [x] Actuator and Micrometer on all services
- [x] Business metrics — latency percentiles, alerts by rule, shadow hits, ZAR flagged
- [x] Prometheus scrape configuration
- [x] Three pre-provisioned Grafana dashboards: pipeline health, fraud overview, rule performance

> Every PromQL expression was cross-checked against the meter names the engine
> actually registers, so the dashboards are not querying metrics that do not exist.

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
- [ ] Verification pass — cold `docker compose up` on a clean machine (needs Docker)

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
