#!/usr/bin/env python3
"""
Run every Grafana panel query against a live Prometheus and report the empty ones.

A Grafana panel that returns nothing renders as "No data", which looks exactly
like a healthy panel for a thing that has not happened yet. That ambiguity has
already cost this project real time: six panels used `histogram_quantile()` over
metrics that were exported as summaries rather than histograms, so the metric
names all matched, nothing logged a warning, and the panels were simply blank.
Matching *names* is what made it look verified; the types were wrong.

This closes that gap. It reads the provisioned dashboard JSON, expands the
Grafana template variables, asks Prometheus each query, and fails on any that
comes back with no series.

    ./scripts/verify-dashboards.py                     # resolves the port itself
    ./scripts/verify-dashboards.py --url http://prometheus:9090

Panels that are legitimately empty on a healthy stack — error counters, dropped
transactions — are listed in ALLOW_EMPTY below, because "no errors" is the
correct answer there and a check that fails on good news gets ignored.

Exits non-zero if any other query is empty, so it works as a CI gate.
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
import pathlib
import urllib.parse
import urllib.request

DASHBOARD_GLOB = "infra/observability/grafana/dashboards/*.json"


def prometheus_url() -> str:
    """Where Prometheus is published, resolved the way smoke-test.sh does it.

    This used to be hardcoded to localhost:9091, which is not the default — it
    is whatever preflight happened to reassign on the machine where the script
    was written, because 9090 was busy there. So it passed locally and, in CI
    where the stack sits on the documented default, every single query failed
    with "connection refused" and the retry loop turned a refused connection
    into a twelve-minute step. A checking script that cannot find the thing it
    is checking has to say so loudly, and it must not need a flag to look in
    the ordinary place.

    Order: an explicit environment variable, then .env (which preflight writes
    when it has to move a port), then the compose default.
    """
    port = os.environ.get("OW_PROMETHEUS_PORT")
    if not port:
        try:
            for line in pathlib.Path(".env").read_text().splitlines():
                match = re.match(r"^\s*OW_PROMETHEUS_PORT\s*=\s*(\d+)", line)
                if match:
                    port = match.group(1)
                    break
        except OSError:
            pass
    return f"http://localhost:{port or '9090'}"

# Grafana expands these server-side; Prometheus has never heard of them. The
# substitutions only need to be *valid* and roughly representative — this checks
# that a query returns series, not that a particular window is the right one.
VARIABLES = {
    "$__rate_interval": "1m",
    "$__interval": "1m",
    "$__range": "30m",
    "$service": ".*",
    "$severity": ".*",
}

# LogQL needs its own expansions. A Loki label matcher is not a PromQL one: an
# empty-compatible regex like ".*" is rejected outright unless some other
# matcher is non-empty, so these use ".+" — which is also what the dashboard's
# own "All" value is set to, so this checks the query the reader actually runs.
LOKI_VARIABLES = dict(VARIABLES, **{
    "$service": ".+",
    "$level": ".+",
    "$search": "",
})

# Queries whose empty result is the good outcome. Substring match on the query.
ALLOW_EMPTY = (
    'level="error"',
    "transactions_failed_total",
    # Renamed when the outbox took over publishing: the engine now counts
    # alerts it could not even queue, and the broker-side failures are
    # outbox_publish_failed_total.
    "fraud_alerts_enqueue_failed_total",
    "outbox_publish_failed_total",
    # The logs dashboard's error panels. A stack with no ERROR lines in the
    # window is the outcome to hope for, and a check that fails on good news
    # gets ignored.
    'level="ERROR"',
    'level="WARN"',
)

# Queries that need more history than a CI run has. Deliberately a separate list
# from ALLOW_EMPTY: there, empty means "nothing has gone wrong", which is good
# news. Here it means "not enough traffic has happened yet", which is neither
# good nor bad, and reporting the two the same way would be a small lie in the
# output of a script whose whole purpose is not being lied to by a dashboard.
#
# The only current entry is the shadow-hit panel. The rule shipped in SHADOW is
# AMOUNT_DEVIATION, which needs 10 prior transactions on the *same card* before
# it will evaluate at all, and then a 5x outlier on top. With 2000 cards at 5/s
# a given card is seen every 400 seconds, so ten of them is roughly 67 minutes —
# an hour past the end of any CI run. On a stack that has been up a while the
# panel fills in, which is why this is a warm-up allowance and not a fix.
ALLOW_EMPTY_UNTIL_WARM = (
    "fraud_shadow_hits_total",
)


def expand(expr: str, source: str = "prometheus") -> str:
    for name, value in (LOKI_VARIABLES if source == "loki" else VARIABLES).items():
        expr = expr.replace(name, value)
    return expr


def panels_of(dashboard: dict):
    """Flatten panels, including those nested inside collapsed rows."""
    for panel in dashboard.get("panels", []):
        yield panel
        yield from panel.get("panels", [])


def datasource_of(panel: dict, target: dict) -> str:
    """Which datasource a query is for: "loki" or "prometheus".

    Read from the panel rather than assumed, because sending LogQL to Prometheus
    fails with a parse error that reads exactly like a broken panel — and a
    checking script that reports false failures gets switched off, which costs
    more than the panels it would have caught.
    """
    for source in (target.get("datasource"), panel.get("datasource")):
        if isinstance(source, dict) and source.get("type"):
            return source["type"]
    return "prometheus"


def loki_query(url: str | None, expr: str) -> tuple[bool, str]:
    """Ask Loki, over HTTP if it is published and through compose if it is not.

    Loki is deliberately not bound to a host port by the base stack — it runs
    with auth_enabled false, so a published port is an unauthenticated read of
    every log line in it. That is the right default and it means this script
    cannot always reach it the way it reaches Prometheus, so it falls back to
    the same route smoke-test.sh takes.
    """
    # query_range, not query. Loki refuses a log selector as an instant query —
    # "log queries are not supported as an instant query type" — and the logs
    # panel is exactly such a selector, so the endpoint that works for both
    # kinds is the range one.
    now = int(time.time())
    path = "/loki/api/v1/query_range?" + urllib.parse.urlencode({
        "query": expr,
        "limit": "5",
        "start": f"{now - 1800}000000000",
        "end": f"{now}000000000",
    })

    if url:
        try:
            with urllib.request.urlopen(url + path, timeout=10) as response:
                return _loki_result(json.load(response))
        except Exception:  # noqa: BLE001 - fall through to compose
            pass

    if not shutil.which("docker"):
        return False, "loki is not reachable and docker is not on PATH"
    try:
        out = subprocess.run(
            ["docker", "compose", "exec", "-T", "loki",
             "wget", "-qO-", "http://localhost:3100" + path],
            capture_output=True, timeout=30, check=True)
        return _loki_result(json.loads(out.stdout))
    except Exception as exc:  # noqa: BLE001 - reported, not handled
        return False, f"request failed: {exc}"


def _loki_result(body: dict) -> tuple[bool, str]:
    if body.get("status") != "success":
        return False, f"{body.get('errorType', 'error')}: {body.get('error', '')}"
    result = body["data"]["result"]
    return len(result) > 0, f"{len(result)} streams"


def query(url: str, expr: str) -> tuple[bool, str]:
    endpoint = f"{url}/api/v1/query?" + urllib.parse.urlencode({"query": expr})
    try:
        with urllib.request.urlopen(endpoint, timeout=10) as response:
            body = json.load(response)
    except Exception as exc:  # noqa: BLE001 - reported, not handled
        return False, f"request failed: {exc}"

    if body.get("status") != "success":
        return False, f"{body.get('errorType', 'error')}: {body.get('error', '')}"
    count = len(body["data"]["result"])
    return count > 0, f"{count} series"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=None,
                        help="Prometheus base URL (default: resolved from "
                             "OW_PROMETHEUS_PORT, then .env, then 9090)")
    parser.add_argument("--loki-url", default=None,
                        help="Loki base URL. Optional: without it, Loki panels "
                             "are queried through `docker compose exec`, which "
                             "is how the base stack keeps Loki off the host.")
    parser.add_argument("--retries", type=int, default=6,
                        help="Re-check the empty queries this many times before "
                             "failing them (default: 6, three seconds apart)")
    args = parser.parse_args()
    base_url = args.url or prometheus_url()

    # Fail immediately and clearly if Prometheus is not there at all. Every
    # query would otherwise report "no data" for what is really "wrong port",
    # which is the same class of confusion this script exists to prevent.
    reachable, why = query(base_url, "up")
    if not reachable:
        print(f"Cannot reach Prometheus at {base_url} — {why}")
        print("Set OW_PROMETHEUS_PORT, or pass --url. Nothing was checked.")
        return 2

    print(f"Checking dashboard queries against {base_url}")

    # Collect every check first, then verify in waves. The earlier version
    # retried each query where it stood, which on a cold stack multiplied the
    # wait by the number of quiet panels: 45 queries each sleeping 18s is a
    # thirteen-minute step, and it was the slowest thing in CI by an order of
    # magnitude. The waiting is for the *stack* to warm up, not for one query,
    # so one shared wait between passes is both faster and more accurate.
    checks: list[dict] = []
    for path in sorted(glob.glob(DASHBOARD_GLOB)):
        dashboard = json.load(open(path))
        for panel in panels_of(dashboard):
            for target in panel.get("targets", []):
                expr = target.get("expr")
                if not expr:
                    continue
                checks.append({
                    "file": path,
                    "dashboard": dashboard.get("title", path),
                    "label": panel.get("title") or panel.get("type"),
                    "legend": target.get("legendFormat", ""),
                    "expr": expr,
                    "source": datasource_of(panel, target),
                    "allowed": any(tok in expr for tok in ALLOW_EMPTY),
                    "warming": any(tok in expr for tok in ALLOW_EMPTY_UNTIL_WARM),
                })

    # An expected-empty check is asked once: waiting cannot change its verdict.
    # It still records the real answer, though — the failure counters are
    # registered at zero, so they legitimately return a series, and reporting
    # that as "zero" would hide the difference between a counter that exists
    # reading nought and a query that matches nothing at all.
    pending = [c for c in checks if not (c["allowed"] or c["warming"])]
    def ask(check: dict) -> tuple[bool, str]:
        expr = expand(check["expr"], check["source"])
        if check["source"] == "loki":
            return loki_query(args.loki_url, expr)
        return query(base_url, expr)

    for c in checks:
        if c["allowed"] or c["warming"]:
            c["ok"], c["detail"] = ask(c)

    waited = 0
    for attempt in range(args.retries + 1):
        still: list[dict] = []
        for c in pending:
            c["ok"], c["detail"] = ask(c)
            if not c["ok"]:
                still.append(c)
        if not still or attempt == args.retries:
            pending = still
            break
        time.sleep(3)
        waited += 3
        pending = still

    if waited:
        print(f"(waited {waited}s in total for the stack to warm up)")

    failures = 0
    current = None
    for c in checks:
        if c["dashboard"] != current:
            current = c["dashboard"]
            print(f"\n{current}  [{c['file']}]")

        if c["ok"]:
            status = "ok  "
        elif c["allowed"]:
            status = "zero"          # empty, and that is the good news
        elif c["warming"]:
            status = "warm"          # empty because the stack is young
        else:
            status = "FAIL"
            failures += 1

        print(f"  [{status}] {c['label']}"
              + (f" — {c['legend']}" if c["legend"] else "")
              + f"  ({c['detail']})")
        if status == "FAIL":
            print(f"         {expand(c['expr'])}")

    print(f"\n{len(checks)} queries checked, {failures} returning no data")
    if failures:
        print("A panel with no data renders as \"No data\", which is "
              "indistinguishable from a metric that is simply quiet. Fix the "
              "query, or add it to ALLOW_EMPTY if empty is the correct answer.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
