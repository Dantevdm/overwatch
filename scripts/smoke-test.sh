#!/usr/bin/env bash
#
# Overwatch smoke test.
#
# Verifies that a running stack is actually wired together — not just that the
# containers are up, but that the services are healthy, Prometheus is scraping
# all three, Grafana provisioned its datasource and dashboard, and the broker
# and database are reachable.
#
#   docker compose up --build -d
#   ./scripts/smoke-test.sh
#
# Exits non-zero if anything fails, so it works in CI as well as by hand.

set -uo pipefail

# preflight.sh writes any reassigned host ports to .env, and docker compose
# reads that file automatically — but this script is not compose, so without
# sourcing it every probe below would go to the default port and report a
# healthy stack as entirely broken. Already-exported values win, so CI can
# override without editing the file.
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  . ./.env
  set +a
fi

PASS=0
FAIL=0
SKIP=0

if [[ -t 1 ]]; then
  G=$'\033[32m'; R=$'\033[31m'; Y=$'\033[33m'; B=$'\033[1m'; N=$'\033[0m'
else
  G=""; R=""; Y=""; B=""; N=""
fi

section() { printf '\n%s%s%s\n' "$B" "$1" "$N"; }

check() { # check <description> <command...>
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then
    printf '  %sPASS%s  %s\n' "$G" "$N" "$desc"; PASS=$((PASS + 1))
  else
    printf '  %sFAIL%s  %s\n' "$R" "$N" "$desc"; FAIL=$((FAIL + 1))
  fi
}

skip() {
  printf '  %sSKIP%s  %s\n' "$Y" "$N" "$1"; SKIP=$((SKIP + 1))
}

# Wait for an HTTP endpoint to answer 200, up to N seconds.
wait_http() { # wait_http <url> <seconds>
  local url="$1" deadline=$(( SECONDS + ${2:-90} ))
  while (( SECONDS < deadline )); do
    if curl -fsS -o /dev/null "$url" 2>/dev/null; then return 0; fi
    sleep 3
  done
  return 1
}

http_ok()      { curl -fsS -o /dev/null "$1"; }

# The engine and simulator are not published to the host, so probe them from
# inside the compose network. That is also the truer test: it exercises exactly
# the path Prometheus uses to scrape them.
#
# Both helpers capture the response and then match it, rather than piping into
# `grep -q`. The pipe version is subtly wrong: grep -q exits at its first match,
# and if the writer upstream still has data to push it dies of EPIPE — the
# docker CLI exits 255, curl exits 23 — which `set -o pipefail` then reports as
# a failed pipeline even though the match succeeded. It only bites once a
# response outgrows the 64KB pipe buffer, so /actuator/health always passed and
# /actuator/prometheus failed intermittently, which read as a broken stack
# rather than a broken test.
internal_matches() { # internal_matches <service> <port> <path> <pattern>
  local body
  body=$(docker compose exec -T "$1" wget -qO- "http://localhost:$2$3" 2>/dev/null) || return 1
  grep -q "$4" <<<"$body"
}
body_matches() { # body_matches <url> <pattern>
  local body
  body=$(curl -fsS "$1" 2>/dev/null) || return 1
  grep -q "$2" <<<"$body"
}

# True when the response does NOT carry a header matching the pattern. Used for
# the framing check below, where the interesting outcome is an absent header.
header_absent() { # header_absent <url> <pattern>
  local headers
  headers=$(curl -fsSI "$1" 2>/dev/null) || return 1
  ! grep -qi "$2" <<<"$headers"
}

have_docker=0
if command -v docker >/dev/null 2>&1 && docker compose ps >/dev/null 2>&1; then
  have_docker=1
fi

printf '%sOverwatch smoke test%s\n' "$B" "$N"

# ---------------------------------------------------------------------------
section "Waiting for services to become ready (up to 3 min)"
# ---------------------------------------------------------------------------
API_BASE="http://localhost:${OW_API_PORT:-8080}"

if wait_http "$API_BASE/actuator/health" 180; then
  printf '  %sREADY%s fraud-api\n' "$G" "$N"
else
  printf '  %sTIMEOUT%s fraud-api — check: docker compose logs fraud-api\n' "$R" "$N"
fi

# ---------------------------------------------------------------------------
section "Service health"
# ---------------------------------------------------------------------------
check "fraud-api reports UP"            body_matches "$API_BASE/actuator/health" '"status":"UP"'
# A datasource misconfiguration shows up here before it becomes a confusing
# failure somewhere else.
check "fraud-api database component UP" body_matches "$API_BASE/actuator/health" '"db"'

if [[ "$have_docker" -eq 1 ]]; then
  check "fraud-engine reports UP"            internal_matches fraud-engine 8082 /actuator/health '"status":"UP"'
  check "fraud-engine database component UP" internal_matches fraud-engine 8082 /actuator/health '"db"'
  check "transaction-simulator reports UP"   internal_matches transaction-simulator 8081 /actuator/health '"status":"UP"'
else
  skip "fraud-engine health (needs docker compose)"
  skip "transaction-simulator health (needs docker compose)"
fi

# ---------------------------------------------------------------------------
section "Simulator control (proxied through the API)"
# ---------------------------------------------------------------------------
# The simulator publishes no host port. If these pass, the proxy hop works and the
# dashboard's Simulator screen has something to talk to.
check "simulator status reachable via /api" body_matches "$API_BASE/api/simulator/status" '"running"'
check "status advertises fraud patterns"    body_matches "$API_BASE/api/simulator/status" 'COMPOUND'

# ---------------------------------------------------------------------------
section "Dashboard aggregations"
# ---------------------------------------------------------------------------
check "dashboard stats include the severity series" \
  body_matches "$API_BASE/api/stats/dashboard" 'severityOverTime'

# The charts' time filter. bucketSeconds is what the client labels its axis from,
# so a response without it renders a chart that lies about its own buckets.
check "dashboard stats report the bucket width" \
  body_matches "$API_BASE/api/stats/dashboard" 'bucketSeconds'
# 5 minutes must come back as 10-second buckets, not silently as hours: an hourly
# bucket over a five-minute window is one data point.
check "a 5-minute range buckets to 10 seconds" \
  body_matches "$API_BASE/api/stats/dashboard?rangeMinutes=5" '"bucketSeconds":10'
# Out-of-range input is clamped rather than rejected or honoured literally.
check "an over-long range is clamped to 7 days" \
  body_matches "$API_BASE/api/stats/dashboard?rangeMinutes=999999" '"rangeMinutes":10080'

# The demo reset. Asserted through the OpenAPI document rather than by calling it:
# a smoke test that empties the store would destroy the data of anyone who ran it
# against a stack they were demonstrating. This proves the route is registered and
# reachable, which is the part that breaks.
check "data reset endpoint is registered" \
  body_matches "$API_BASE/v3/api-docs" '/api/admin/reset'

# ---------------------------------------------------------------------------
section "Metrics endpoints (Micrometer -> Prometheus)"
# ---------------------------------------------------------------------------
check "fraud-api exposes /actuator/prometheus" body_matches "$API_BASE/actuator/prometheus" "jvm_memory_used_bytes"
check "application tag is present"             body_matches "$API_BASE/actuator/prometheus" 'application="fraud-api"'

if [[ "$have_docker" -eq 1 ]]; then
  check "fraud-engine exposes /actuator/prometheus" internal_matches fraud-engine 8082 /actuator/prometheus "jvm_memory_used_bytes"
  check "simulator exposes /actuator/prometheus"    internal_matches transaction-simulator 8081 /actuator/prometheus "jvm_memory_used_bytes"

  # The distributions must be exported as histograms, not summaries. Grafana
  # reads them with histogram_quantile(), which needs _bucket series; a summary
  # exports only _count, _sum and a rolling _max, and the panels then render
  # "No data" with every metric name still spelled correctly. That is exactly
  # how six panels were silently blank, so the bucket series are asserted by
  # name rather than trusted.
  check "detection latency exports histogram buckets" \
    internal_matches fraud-engine 8082 /actuator/prometheus "fraud_detection_latency_seconds_bucket"
  check "risk score exports histogram buckets" \
    internal_matches fraud-engine 8082 /actuator/prometheus "fraud_risk_score_bucket"
  check "flagged amount exports histogram buckets" \
    internal_matches fraud-engine 8082 /actuator/prometheus "fraud_amount_flagged_zar_bucket"
  # The severity bands break at 0.5 and 0.75, so those edges have to exist for
  # the risk-score panels to line up with the bands they are read against.
  check "risk score buckets include the severity edges" \
    internal_matches fraud-engine 8082 /actuator/prometheus 'fraud_risk_score_bucket.*le="0.75"'
  check "HTTP timings export histogram buckets" \
    internal_matches fraud-engine 8082 /actuator/prometheus "http_server_requests_seconds_bucket"
else
  skip "engine metrics (needs docker compose)"
  skip "simulator metrics (needs docker compose)"
  skip "histogram bucket checks (needs docker compose)"
fi

# ---------------------------------------------------------------------------
section "Prometheus"
# ---------------------------------------------------------------------------
check "Prometheus is up" http_ok "http://localhost:${OW_PROMETHEUS_PORT:-9090}/-/healthy"

if curl -fsS "http://localhost:${OW_PROMETHEUS_PORT:-9090}/api/v1/targets" >/dev/null 2>&1; then
  up_count=$(curl -fsS "http://localhost:${OW_PROMETHEUS_PORT:-9090}/api/v1/targets" 2>/dev/null \
    | grep -o '"health":"up"' | wc -l | tr -d ' ')
  # 3 services + prometheus scraping itself
  if [[ "${up_count:-0}" -ge 4 ]]; then
    printf '  %sPASS%s  all scrape targets healthy (%s up)\n' "$G" "$N" "$up_count"; PASS=$((PASS + 1))
  else
    printf '  %sFAIL%s  expected >=4 healthy targets, got %s — see http://localhost:${OW_PROMETHEUS_PORT:-9090}/targets\n' \
      "$R" "$N" "${up_count:-0}"; FAIL=$((FAIL + 1))
  fi
else
  skip "target enumeration (Prometheus API unreachable)"
fi

# Consumer lag is the one number that says whether detection is keeping pace, so
# its absence matters. It is polled rather than asserted once: the per-partition
# gauge is only created after the consumer has been assigned a partition and
# completed a fetch, which happens tens of seconds *after* the engine reports
# itself healthy. Asserted directly it would be flaky on a cold stack, which is
# worse than not checking it at all.
lag_series=""
for _ in $(seq 1 20); do
  lag_series=$(curl -fsG "http://localhost:${OW_PROMETHEUS_PORT:-9090}/api/v1/query" \
    --data-urlencode 'query=kafka_consumer_fetch_manager_records_lag' 2>/dev/null \
    | grep -o '"metric"' | wc -l | tr -d ' ')
  [[ "${lag_series:-0}" -gt 0 ]] && break
  sleep 3
done
if [[ "${lag_series:-0}" -gt 0 ]]; then
  printf '  %sPASS%s  consumer lag is being scraped (%s partition series)\n' "$G" "$N" "$lag_series"; PASS=$((PASS + 1))
else
  printf '  %sFAIL%s  no kafka_consumer_fetch_manager_records_lag series after 60s — '\
'the engine may not be consuming\n' "$R" "$N"; FAIL=$((FAIL + 1))
fi

# ---------------------------------------------------------------------------
section "Grafana provisioning"
# ---------------------------------------------------------------------------
check "Grafana is up"                    body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/health" '"database"'
check "Prometheus datasource provisioned" body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/datasources" 'prometheus'
# Every panel names this uid explicitly rather than relying on isDefault, so if
# the uid ever drifts the dashboards fail as a set — worth catching here rather
# than as three screens of "Datasource prometheus was not found".
check "datasource uid is the one panels reference" \
  body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/datasources" '"uid":"prometheus"'
check "pipeline-health dashboard present" body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/search?query=Pipeline" 'Pipeline Health'
# The UI's Metrics page frames these dashboards. Grafana sends
# "X-Frame-Options: deny" unless GF_SECURITY_ALLOW_EMBEDDING is set, and the
# failure is invisible from the server side — the page loads, the frames stay
# blank, and nothing is logged. Assert the header is gone.
check "Grafana permits framing (Metrics page)" \
  header_absent "http://localhost:${OW_GRAFANA_PORT:-3000}/api/health" 'x-frame-options'
# The three UIDs the Metrics page frames. A renamed dashboard keeps its UID; a
# re-created one may not, and then one tab frames a Grafana 404.
for uid in overwatch-pipeline overwatch-fraud overwatch-rules; do
  check "dashboard $uid resolves by UID" \
    body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/dashboards/uid/$uid" '"uid"'
done

# ---------------------------------------------------------------------------
section "Dashboard UI"
# ---------------------------------------------------------------------------
check "UI serves the app shell" body_matches "http://localhost:${OW_UI_PORT:-5173}" "root"

# ---------------------------------------------------------------------------
section "Infrastructure"
# ---------------------------------------------------------------------------
if [[ "$have_docker" -eq 1 ]]; then
  check "PostgreSQL accepting connections" \
    docker compose exec -T postgres pg_isready -U overwatch -d overwatch
  check "Redpanda cluster healthy" \
    sh -c "docker compose exec -T redpanda rpk cluster health | grep -q 'Healthy:.*true'"
else
  skip "PostgreSQL check (docker compose unavailable from here)"
  skip "Redpanda check (docker compose unavailable from here)"
fi

# ---------------------------------------------------------------------------
printf '\n%s────────────────────────────────────────%s\n' "$B" "$N"
printf '%s%d passed%s' "$G" "$PASS" "$N"
[[ "$FAIL" -gt 0 ]] && printf ', %s%d failed%s' "$R" "$FAIL" "$N"
[[ "$SKIP" -gt 0 ]] && printf ', %s%d skipped%s' "$Y" "$SKIP" "$N"
printf '\n'

if [[ "$FAIL" -gt 0 ]]; then
  printf '\nInvestigate with:\n  docker compose ps\n  docker compose logs --tail=50 <service>\n'
  exit 1
fi
printf '\nStack is healthy.\n'
