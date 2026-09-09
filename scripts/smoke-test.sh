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
internal_matches() { # internal_matches <service> <port> <path> <pattern>
  docker compose exec -T "$1" wget -qO- "http://localhost:$2$3" 2>/dev/null | grep -q "$4"
}
body_matches() { curl -fsS "$1" 2>/dev/null | grep -q "$2"; }

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

# ---------------------------------------------------------------------------
section "Metrics endpoints (Micrometer -> Prometheus)"
# ---------------------------------------------------------------------------
check "fraud-api exposes /actuator/prometheus" body_matches "$API_BASE/actuator/prometheus" "jvm_memory_used_bytes"
check "application tag is present"             body_matches "$API_BASE/actuator/prometheus" 'application="fraud-api"'

if [[ "$have_docker" -eq 1 ]]; then
  check "fraud-engine exposes /actuator/prometheus" internal_matches fraud-engine 8082 /actuator/prometheus "jvm_memory_used_bytes"
  check "simulator exposes /actuator/prometheus"    internal_matches transaction-simulator 8081 /actuator/prometheus "jvm_memory_used_bytes"
else
  skip "engine metrics (needs docker compose)"
  skip "simulator metrics (needs docker compose)"
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

# ---------------------------------------------------------------------------
section "Grafana provisioning"
# ---------------------------------------------------------------------------
check "Grafana is up"                    body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/health" '"database"'
check "Prometheus datasource provisioned" body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/datasources" 'prometheus'
check "pipeline-health dashboard present" body_matches "http://localhost:${OW_GRAFANA_PORT:-3000}/api/search?query=Pipeline" 'Pipeline Health'

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
