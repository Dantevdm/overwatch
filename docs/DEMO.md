# Demonstrating Overwatch

A structure for showing this system in about **fifteen minutes**, and what to say
at each step. There is a five-minute cut at the end for when that is all you get.

The guide is deliberately opinionated about order. The temptation with a system
like this is to open the dashboard and start pointing at things, which produces a
tour of screens rather than an argument. What follows builds one claim at a time:
*the pipeline is real*, then *the scoring is a model rather than a switch*, then
*the system tells you whether its own rules are any good*.

Every number quoted below came off a live run of this stack. Yours will differ —
that is the point of the generator — but the shape should match.

---

## Before you start

Do this a few minutes ahead, not while someone is watching.

```bash
make up                      # resolves port conflicts, then starts the stack
./scripts/smoke-test.sh      # 54 checks — health, metrics, broker, DB, outbox, logs, reports
```

Then leave the stack running for **at least ten minutes** before you demonstrate.
This matters more than it sounds:

- Grafana's rate panels need a window of history. On a stack that started ninety
  seconds ago, half the dashboard reads "No data" and you will spend the demo
  apologising for it rather than explaining it.
- The consumer-lag gauge does not exist until the engine's consumer has been
  assigned a partition and completed a fetch — roughly thirty seconds after the
  container reports healthy. It is the panel most likely to be empty if you rush.
- The rule-performance numbers are only interesting once a few thousand
  transactions have gone through.

Check which ports you actually got, because preflight may have moved them:

```bash
make urls
```

Two things happen before the app that will surprise you if nobody warned you:

- **A sign-in screen.** It authenticates nothing — no network call, the password
  is discarded — and says so on the screen. The analyst is prefilled, so Enter
  goes straight through. It is stage dressing for the demo, and the code says so
  at length; see the question about authentication below, because someone who has
  just watched you log in *will* ask.
- **A welcome tour**, once per browser. Dismiss it beforehand, or leave it if you
  want the room to see it.

Open these tabs in this order, and leave them open:

| Tab | Why |
|---|---|
| Dashboard — `/dashboard` | Where you start and end |
| Alerts — `/alerts` | The centrepiece |
| Rules — `/rules` | Shadow mode and replay |
| Metrics — `/metrics` | Grafana, framed |
| Reports — `/reports` | The closing beat: take it away as a file |
| Redpanda Console | The proof that streaming is real |

Everything except the console is one app. Grafana, the logs dashboard,
Prometheus, the console and the API docs are all linked from **External tools** at
the foot of the sidebar, so you do not have to remember a port mid-sentence.

If you would rather drive from a terminal than a browser, `./bin/ow` is the same
console as a CLI against the same API — `ow top` is a live pipeline view that
projects well, and `ow sim inject COMPOUND` is Act II in one line.

---

## Act I — the pipeline is real (about 4 minutes)

**Open on the Dashboard.** Counters moving, both charts populated.

> "This is a card-fraud detection pipeline. Transactions arrive on a stream, every
> one is scored against a configurable rule set, and anything that scores highly
> enough becomes an alert an analyst can work. What is on screen is live — there is
> a generator producing South African card traffic behind it."

Let it move for a moment. Then pre-empt the obvious suspicion, because someone in
the room is already having it:

> "The traffic is synthetic, so the fair question is whether it is *shaped* like
> anything real or whether it is a flat line with random noise on top. Three things
> in the generator answer that, and each was added after measuring the old
> behaviour."

**Point at the throughput chart.** It should be visibly uneven, not a ruled line.

- **Volume follows a diurnal curve** — a 24-point hourly shape interpolated to the
  minute in SAST, normalised to average exactly 1.0 so the configured rate stays
  the daily *mean* rather than becoming a ceiling. The multiplier in force right
  now is exported as `simulator_diurnal_weight`, so the shape is visible in
  Grafana next to the throughput it explains.
- **Arrivals are Poisson** — each tick draws its batch size around the expected
  rate instead of publishing an identical count every time. Worth quoting the
  measurement: the coefficient of variation went from **0.0004**, which is a
  straight line, to **0.095**.
- **The category mix is weighted** — groceries 22, fuel 14, restaurant and retail
  11, down to liquor 3. It used to be a uniform draw over the merchant list, which
  meant the pie chart described how many merchants of each kind I had happened to
  type in, not South African card spend.

**Now switch to the Redpanda Console.** Open the `transactions` topic and show
actual message payloads, then `fraud-alerts`.

> "This is the part you would otherwise have to take on faith. Transactions go into
> a topic, alerts come out of another one, and here is the JSON in between. Three
> separate services, a real Kafka-API broker — not an in-memory queue with Kafka
> naming."

Show the consumer group and its lag sitting near zero.

> "And that is the number that tells you detection is keeping pace with ingestion.
> If this climbs, the engine is behind — which is a completely different problem
> from the engine being wrong, and the dashboards keep them separate."

If the room is technical, this is the moment for the two answers that usually only
come up in a code review:

> "Two services and a broker means two places a message can be lost. An alert is
> written to the database and a row is written to an outbox table in the *same*
> transaction; a poller publishes from that table and marks it sent. So an alert
> can never exist on the topic without existing in the database. The trade is
> at-least-once delivery — a publish that succeeds and a mark that does not gets
> sent again — and the backlog and its age are both on the pipeline dashboard."

> "And going the other way: a transaction the engine cannot process is retried
> three times and then published to a dead-letter topic with the failure in its
> headers, rather than logged and dropped. If it never deserialised, what lands
> there is the original bytes — so it can be replayed, or handed back to whoever
> sent it. A counter tells you that you lost something; the topic tells you what."

> "And when you want to follow one transaction across all of it, there is a trace.
> The producer's span is the parent of the consumer's span across the broker,
> because the trace context rides in the record headers. Every log line carries its
> trace id, and the logs dashboard turns that into a link into the trace."

A Kafka publish is not a call — the producer returns long before anyone consumes —
so that parent-child link is the part people assume does not work, and it is worth
showing rather than asserting.

---

## Act II — scoring is a model, not a switch (about 5 minutes)

This is the centrepiece. Do not rush it.

**Go to the Simulator screen and drop the rate to 1 or 2 per second.** Say why:

> "At fifty a second the alerts scroll past. At one a second every alert on the
> screen is one we can follow all the way back to the rule that caused it."

**Now inject the compound pattern** — the button on the Simulator screen, or:

```bash
curl -X POST localhost:8080/api/simulator/inject/COMPOUND
```

**Switch to Alerts.** A CRITICAL alert appears within a second or so. Open it.

> "One transaction. Sixty-five thousand rand, at an exact multiple of a thousand,
> acquired in China on a South African card, at half past four in the morning, at a
> forex merchant. Five rules fired."

The alert detail shows each contributing rule with its weight and its evidence.
From a real run:

| Rule | Weight | Why it fired |
|---|---|---|
| `HIGH_VALUE` | 0.40 | R65 000.00 exceeds the R50 000.00 threshold |
| `CROSS_BORDER` | 0.30 | Acquired in CN, outside the card's home country (ZA) |
| `CATEGORY_WATCHLIST` | 0.25 | Merchant category 'forex' is on the high-risk watchlist |
| `LATE_NIGHT` | 0.20 | Transaction at 04:27 SAST, inside the 01:00–04:59 window |
| `ROUND_AMOUNT` | 0.15 | R65 000.00 is an exact multiple of R1 000.00 |

Then make the point the table exists for:

> "Those weights sum to 1.30. The score is 1.0, because it is capped. And that is
> the whole design in one screen: **rules do not return true or false.** Each one
> contributes a weighted amount and the total determines severity. Fraud detection
> is probabilistic, and a system that latches on the first hit throws away the
> difference between one strong signal and five."

**Now show the other end of the range.** Go back to the alert list, filter to
`LOW`, and open one — a single-rule hit scoring 0.30 to 0.44.

> "This one tripped a single rule and scored 0.30. Worth an analyst's glance — not
> worth blocking someone's card in a supermarket queue. A boolean rule engine
> cannot express that difference, and in production that difference is most of the
> job: false positives are not free, they are a person whose card stopped working."

Optionally inject one or two single-rule patterns — `LATE_NIGHT`, `HIGH_VALUE` —
to show the low end arriving on demand. Available patterns are `HIGH_VALUE`,
`VELOCITY_BURST`, `LATE_NIGHT`, `ROUND_AMOUNT`, `CROSS_BORDER`,
`HIGH_RISK_CATEGORY` and `COMPOUND`.

---

## Act III — does the rule set actually earn its keep? (about 5 minutes)

The part most rule engines cannot answer about themselves.

**Go to Rules.** Point out that the rules are rows in a database with JSONB
parameters, not code paths.

> "Changing a threshold is an API call, not a redeploy. But the more interesting
> thing on this screen is the third state."

**Show the rule in `SHADOW`** — it ships as `AMOUNT_DEVIATION`, "Amount deviation
from card baseline".

> "A rule is `ENABLED`, `DISABLED`, or `SHADOW`. A shadow rule evaluates every
> single transaction and records what it *would* have flagged — and raises no
> alerts at all. That is how you actually tune a rule in production: run it
> silently against live traffic first, look at what it would have caught, then turn
> it on. Shipping a new rule straight to enabled means finding out how noisy it is
> from your analysts."

Shadow hits are counted separately — `fraud_shadow_hits_total`, and the
`shadow_rule_hits` table exists precisely because a shadow hit has no alert row to
attach to.

**Then the replay endpoint.** This is the strongest single moment after the
compound alert, because it turns an argument into a measurement:

```bash
curl -X POST localhost:8080/api/replay \
  -H 'Content-Type: application/json' \
  -d '{"ruleType":"HIGH_VALUE","weight":0.40,"parameters":{"threshold":30000},"hours":1}'
```

> "'Should we drop the high-value threshold from fifty thousand to thirty?' That is
> normally a meeting. Here it replays the last hour of stored transactions through
> the candidate configuration and tells you the answer, writing nothing."

From a live run, over the same 21 420 transactions:

| Threshold | Would have fired | Rate |
|---|---|---|
| R50 000 (current) | 235 | 1.10% |
| R30 000 (candidate) | 250 | 1.17% |

> "Fifteen more alerts for a forty-percent cut in the threshold. That is a real
> finding, and it is not the one you would guess — most of the value above thirty
> thousand is already above fifty. Now the threshold conversation has a number in
> it."

Be straight about the limit, because it is the first thing a careful reviewer will
probe:

> "Replay deliberately reports nothing for the history-dependent rules — velocity,
> and amount deviation, which is the one we just looked at in shadow mode. It
> *could* answer from a baseline that does not reflect the replayed window, but a
> confidently wrong answer is the worst possible failure in a tool whose entire
> purpose is informing a threshold change."

Which is also why shadow mode and replay are both here rather than either being
sufficient: replay answers instantly for stateless rules, and shadow mode is how
you evaluate the ones replay honestly cannot.

**Finish on Metrics** — the Grafana dashboards, framed one tab each.

> "Four dashboards: is the pipeline keeping up, what are the rules catching,
> which rules are earning their place, and what everything was complaining about —
> that last one is every container's log, in the same tool, with Java stack traces
> folded into the line that threw them. All provisioned as code, so they exist the
> moment the stack starts — nobody imports a JSON file."

If you want one technical detail here, make it this one:

> "The distributions are exported as Prometheus histograms rather than
> client-computed percentiles, so Prometheus can answer any percentile across any
> number of instances. Six of these panels were once silently blank because they
> ran `histogram_quantile()` against metrics exported as summaries — every metric
> name matched, nothing warned, and a blank panel looks exactly like a quiet one.
> So there is now a script that runs every panel's query against a live Prometheus
> and Loki and fails the build on any that returns no data. Fifty-four queries, and
> CI runs it on every push."

That last point tends to land better than anything about the rules, because it is
about knowing when you are wrong.

**Close on Reports.** Pick a window and press **Download .pdf**.

> "Everything I have shown you lives on a screen, and a fraud lead's Monday ends
> with a document attached to an email. So the window you are looking at leaves as
> a file — a PDF to read, or a workbook whose charts are real Excel charts bound to
> the cells, so you can re-sort the rule table and the chart follows."

Open the file. It is a good last thing to be on screen, because it is the whole
demo in three pages and the room can be handed a copy of it.

> "It is built by the API rather than the browser, which means the same document
> comes out of `curl`, out of Postman, out of `ow report pdf`, or out of a cron
> job at six on a Monday — and the figures come from the same service the dashboard
> reads, so the report cannot quietly disagree with the screen it came from."

---

## The five-minute cut

If that is all you have, do this and nothing else:

1. **Dashboard, 30s.** Live pipeline, synthetic but shaped traffic.
2. **Simulator: rate to 1/s, inject `COMPOUND`. 30s.**
3. **Alerts: open the CRITICAL. 2 min.** Five rules, weights summing to 1.30,
   capped at 1.0. Scoring is a model, not a switch. *This is the demo.*
4. **Rules: the SHADOW row, then one replay call. 90s.** The system can tell you
   whether its own rules are worth keeping.
5. **Reports: download the PDF. 30s.** They leave with the artefact rather than
   with a memory of a dashboard.

Skip the console and Grafana. Mention they exist and are linked from the sidebar.

---

## Questions you will get

**"Is any of this authenticated?"** No — and the sign-in screen you just watched
me use is the reason to be explicit about it. It authenticates nothing: no network
call, no endpoint, the password is discarded and never stored, and the screen
carries a notice saying so that cannot be dismissed. It is there so the console
looks like the thing it is imitating, not to imply the gap is closed. The gap is
deliberate rather than overlooked, and it is in the README's known gaps. The BFF is the natural seam. The
sharpest edge is `POST /api/admin/reset`, which destroys data, so it sits behind a
config flag (`ALLOW_RESET`) and is named as the first route that should demand a
role once auth exists. Half-built auth is worse than none.

**"Why Redpanda and not Kafka?"** Kafka API compatible, single binary, no
ZooKeeper. Identical client code, a fraction of the container footprint. Nothing in
the services knows the difference.

**"How do you know the rules do not just flag everything?"** The generator was run
against the real rule set: **0.00% false positives across 3 000 clean
transactions.** And the Rules screen shows each rule's actual share of alerts, so a
rule that fires on everything is visible rather than inferred.

**"What is the test coverage?"** `mvn verify` is the gate and it fails the build,
not just the report: 138 tests across six modules, a JaCoCo line-coverage floor,
SpotBugs with find-sec-bugs (~130 security detectors), and PMD. One of those tests
starts a real Redpanda and a real PostgreSQL under Testcontainers and drives a
transaction all the way to a published alert — it found two faults within an hour
of being written that no unit test could have. CI additionally applies the Flyway
migrations to a real PostgreSQL and asserts the resulting schema, then brings the
whole stack up and smoke-tests it.

**"Why three services instead of one?"** So the engine scales independently of the
API, and a slow consumer never applies back-pressure to whoever is querying alerts.
The stream is the seam that makes that true rather than aspirational.

**"Could this handle real volume?"** Not as configured — it is one broker partition
set, one engine instance, 6 hours of Prometheus retention. The shutdown path is
real, at least: all three services shut down gracefully with a 20-second drain, so
a rolling restart finishes what it is holding instead of dropping it. Nothing in the design
prevents it: the engine is a stateless consumer, so it scales by adding instances
and partitions. Worth saying plainly rather than claiming otherwise.

---

## If something goes wrong mid-demo

**A Grafana panel says "No data".** Almost always the time picker rather than a
fault. Every count and pie is scoped to the picker with `increase(...[$__range])`,
so a 5-minute window on a quiet metric is legitimately empty. Widen to 1 hour.

**Consumer lag panel is empty.** The per-partition gauge is created only after the
consumer's first completed fetch. If the engine restarted recently, wait thirty
seconds.

**Alerts stopped appearing.** Check the Simulator screen — the rate may still be
at 1/s from Act II, or paused. `POST /api/simulator/start`.

**Nothing at all is responding.** Check you are on the right ports; preflight
reassigns them when something else holds the default. `make urls`.

**You need to know why something failed, on screen.** The **Logs** link in the
sidebar opens a Grafana dashboard over every container's log, filtered by service
and level, with stack traces folded into one entry. It is a far better thing to
have open in front of a room than `docker compose logs -f`, which interleaves
twelve containers into a stream nobody can read.

**You need a clean slate.** **Clear data** in the top right empties transactions,
alerts, rule hits and the outbox, and resets the simulator's counters. It keeps rules, so a
threshold you just tuned survives — and it keeps the Prometheus counters, because
those are monotonic by contract and zeroing them writes a false spike into every
panel. So straight after a reset the dashboard reads zero while Grafana still shows
the whole run. Both are correct, and it is worth saying so out loud if anyone
notices, because it looks like a bug and is not.
