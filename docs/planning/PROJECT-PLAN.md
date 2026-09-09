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
- [ ] Schema migration — transactions, fraud_rules, fraud_alerts
- [ ] JPA entities and repositories
- [ ] Seed data — SA merchants, cards, default rule set

### Phase 3 — Fraud engine
- [ ] `FraudRule` interface and the six rule implementations
- [ ] `RuleEngine` orchestrator with score accumulation
- [ ] Shadow-mode handling
- [ ] Kafka consumer for `transactions`, producer for `fraud-alerts`
- [ ] Unit tests per rule, integration test for the pipeline

### Phase 4 — Transaction simulator
- [ ] SA merchant, bank and card reference data
- [ ] Realistic generator — normal traffic plus injected fraud patterns
- [ ] Configurable rate, controllable via API

### Phase 5 — Fraud API (BFF)
- [ ] Transactions, alerts and rules controllers with filtering and pagination
- [ ] Stats endpoints for the dashboard
- [ ] Rule performance statistics
- [ ] Replay / what-if endpoint
- [ ] OpenAPI / Swagger

### Phase 6 — Dashboard
- [ ] Vite scaffold, tokens.css, app shell
- [ ] Dashboard, Transactions, Alerts, Rules, Metrics pages
- [ ] Charts on the brand-derived palette
- [ ] Live updating

### Phase 7 — Observability
- [ ] Actuator and Micrometer on all three services
- [ ] Custom business metrics
- [ ] Prometheus scrape configuration
- [ ] Pre-provisioned Grafana dashboards

### Phase 8 — Delivery
- [ ] Postman collection with an environment
- [ ] README with a genuine one-command quickstart
- [ ] Verification pass — cold `docker compose up` on a clean machine

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
| D5 | Flyway vs `schema.sql` | Deferred | `schema.sql` plus `data.sql` is enough for a single-version demo. Flyway if migrations become plural. |
| D6 | Multi-currency support | Deferred | Everything is ZAR. The `currency` column exists so this is additive, not a rewrite. |
| D7 | Alert disposition workflow | Deferred | Alerts have a status field. Whether analysts can transition it from the UI depends on remaining time. |
