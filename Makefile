# Overwatch — common tasks.
#
# `make up` is the one to remember. It resolves any host port conflicts before
# starting, so a port already in use on your machine is handled up front rather
# than surfacing as a failed container three minutes in.

.DEFAULT_GOAL := help
.PHONY: help up up-tools down clean logs ps test smoke preflight build verify urls

help: ## Show this help
	grep -hE '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[1m%-12s\033[0m %s\n", $$1, $$2}'

preflight: ## Check host ports, write .env overrides for any conflicts
	./scripts/preflight.sh --write || true

urls: ## Print the URLs for this machine, honouring any .env overrides
	@./scripts/preflight.sh >/dev/null 2>&1 || true
	@sh -c 'api=$$(grep -E "^OW_API_PORT=" .env 2>/dev/null | cut -d= -f2); api=$${api:-8080}; \
	  ui=$$(grep -E "^OW_UI_PORT=" .env 2>/dev/null | cut -d= -f2); ui=$${ui:-5173}; \
	  prom=$$(grep -E "^OW_PROMETHEUS_PORT=" .env 2>/dev/null | cut -d= -f2); prom=$${prom:-9090}; \
	  graf=$$(grep -E "^OW_GRAFANA_PORT=" .env 2>/dev/null | cut -d= -f2); graf=$${graf:-3000}; \
	  printf "\n  Dashboard    http://localhost:%s\n" "$$ui"; \
	  printf "  API docs     http://localhost:%s/swagger-ui.html\n" "$$api"; \
	  printf "  API health   http://localhost:%s/actuator/health\n" "$$api"; \
	  printf "  Grafana      http://localhost:%s\n" "$$graf"; \
	  printf "  Prometheus   http://localhost:%s/targets\n\n" "$$prom"'

up: preflight ## Resolve ports, then build and start the stack
	docker compose up --build

up-tools: preflight ## Same, but also publish Postgres, Kafka, engine and simulator
	docker compose -f docker-compose.yml -f docker-compose.tools.yml up --build

down: ## Stop the stack, keeping data
	docker compose down

clean: ## Stop the stack and drop volumes (Flyway re-runs from scratch)
	docker compose down -v

ps: ## Show container status
	docker compose ps

logs: ## Tail logs for all services
	docker compose logs -f --tail=100

smoke: ## Verify a running stack is wired correctly
	./scripts/smoke-test.sh

build: ## Compile and package without starting anything
	mvn -B clean package

verify: ## Full quality gate — tests, coverage, SpotBugs, PMD
	mvn -B clean verify

test: ## Run unit tests only
	mvn -B test
