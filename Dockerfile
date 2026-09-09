# Overwatch — one build for the whole reactor.
#
# Previously each service had its own Dockerfile, which meant Maven resolved the
# dependency tree three separate times with no shared local repository: roughly
# 13 minutes of wall clock to produce three jars from one source tree.
#
# This builds the reactor once and each service image just takes its jar out.
# The BuildKit cache mount keeps ~/.m2 between builds, so a source-only change
# recompiles in seconds instead of re-downloading anything.
#
#   docker compose build           # uses the `target` for each service

# ---- builder (shared by all three services) -----------------------------------
FROM maven:3.9-eclipse-temurin-25-alpine AS builder
WORKDIR /build

# POMs first: the dependency layer then rebuilds only when a POM changes.
COPY pom.xml .
COPY services/common/pom.xml                services/common/
COPY services/rule-engine/pom.xml           services/rule-engine/
COPY services/transaction-simulator/pom.xml services/transaction-simulator/
COPY services/fraud-engine/pom.xml          services/fraud-engine/
COPY services/fraud-api/pom.xml             services/fraud-api/

# Retry settings for every Maven invocation here — Central drops handshakes.
COPY .mvn .mvn

# A cache warm-up, not the authoritative step. `go-offline` is deliberately not
# used: it resolves every plugin for every profile, far more than a build needs,
# and was the single largest cost here.
#
# Resolution is serial on purpose. Parallel resolution opens many simultaneous
# connections to Maven Central, which is exactly what provokes the dropped
# handshakes this step used to fail on. The package step below is authoritative
# and fetches anything missing, so a flake here must not fail the build.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:resolve \
      || echo 'Dependency warm-up incomplete — the package step will fetch the rest.'

# The schema. Copied before the build because both datasource-owning services
# package it into their own classpath (see infra/database/README.md).
COPY infra/database infra/database

COPY services/common/src                services/common/src
COPY services/rule-engine/src           services/rule-engine/src
COPY services/transaction-simulator/src services/transaction-simulator/src
COPY services/fraud-engine/src          services/fraud-engine/src
COPY services/fraud-api/src             services/fraud-api/src

# One reactor build. Tests run in CI, not here —
# an image build that runs the test suite makes `docker compose up` slow for
# everyone, every time, to re-prove what CI already proved on the commit.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q clean package -DskipTests

# Fail loudly, here, if any jar is not an executable Spring Boot archive.
# A repackaged jar contains the Boot loader; a plain library jar does not, and
# would otherwise surface at runtime as "no main manifest attribute, in app.jar".
RUN set -eu; \
    for m in transaction-simulator fraud-engine fraud-api; do \
      jar=$(ls "services/$m"/target/*.jar | head -1); \
      if ! jar tf "$jar" | grep -q 'org/springframework/boot/loader/'; then \
        echo "BUILD ERROR: $jar is not an executable Spring Boot jar."; \
        echo "The spring-boot-maven-plugin repackage goal did not run for $m."; \
        exit 1; \
      fi; \
      cp "$jar" "/build/$m.jar"; \
    done

# ---- shared runtime base ------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine AS runtime-base
WORKDIR /app
# Unprivileged: a container that does not need root should not have it.
RUN addgroup -S overwatch && adduser -S overwatch -G overwatch
# Container-aware heap sizing, and a fast exit on OOM so the orchestrator can
# restart rather than leaving a wedged JVM behind.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

# ---- service images -----------------------------------------------------------
FROM runtime-base AS fraud-engine
COPY --from=builder --chown=overwatch:overwatch /build/fraud-engine.jar app.jar
USER overwatch
EXPOSE 8082

FROM runtime-base AS fraud-api
COPY --from=builder --chown=overwatch:overwatch /build/fraud-api.jar app.jar
USER overwatch
EXPOSE 8080

FROM runtime-base AS transaction-simulator
COPY --from=builder --chown=overwatch:overwatch /build/transaction-simulator.jar app.jar
USER overwatch
EXPOSE 8081
