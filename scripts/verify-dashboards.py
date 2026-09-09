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

    ./scripts/verify-dashboards.py                     # against localhost:9091
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
import sys
import time
import urllib.parse
import urllib.request

DASHBOARD_GLOB = "observability/grafana/dashboards/*.json"

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

# Queries whose empty result is the good outcome. Substring match on the query.
ALLOW_EMPTY = (
    'level="error"',
    "transactions_failed_total",
    "fraud_alerts_publish_failed_total",
)


def expand(expr: str) -> str:
    for name, value in VARIABLES.items():
        expr = expr.replace(name, value)
    return expr


def panels_of(dashboard: dict):
    """Flatten panels, including those nested inside collapsed rows."""
    for panel in dashboard.get("panels", []):
        yield panel
        yield from panel.get("panels", [])


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
    parser.add_argument("--url", default="http://localhost:9091",
                        help="Prometheus base URL (default: the published port)")
    parser.add_argument("--retries", type=int, default=6,
                        help="Re-check an empty query this many times before "
                             "failing it (default: 6, three seconds apart)")
    args = parser.parse_args()

    failures = 0
    checked = 0

    for path in sorted(glob.glob(DASHBOARD_GLOB)):
        dashboard = json.load(open(path))
        print(f"\n{dashboard.get('title', path)}  [{path}]")

        for panel in panels_of(dashboard):
            for target in panel.get("targets", []):
                expr = target.get("expr")
                if not expr:
                    continue
                checked += 1
                allowed = any(token in expr for token in ALLOW_EMPTY)

                # Retry the empties. On a stack that has just started, a panel
                # keyed on alerts has genuinely seen no alerts yet, and failing
                # on that race would make this check untrustworthy in CI — which
                # is the one place it has to be believed.
                ok, detail = query(args.url, expand(expr))
                attempts = 0
                while not ok and not allowed and attempts < args.retries:
                    time.sleep(3)
                    attempts += 1
                    ok, detail = query(args.url, expand(expr))
                if ok and attempts:
                    detail += f", after {attempts * 3}s"

                if ok:
                    status = "ok  "
                elif allowed:
                    status = "zero"          # empty, and that is the good news
                else:
                    status = "FAIL"
                    failures += 1

                label = f"{panel.get('title') or panel.get('type')}"
                legend = target.get("legendFormat", "")
                print(f"  [{status}] {label}"
                      + (f" — {legend}" if legend else "")
                      + f"  ({detail})")
                if status == "FAIL":
                    print(f"         {expand(expr)}")

    print(f"\n{checked} queries checked, {failures} returning no data")
    if failures:
        print("A panel with no data renders as \"No data\", which is "
              "indistinguishable from a metric that is simply quiet. Fix the "
              "query, or add it to ALLOW_EMPTY if empty is the correct answer.")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
