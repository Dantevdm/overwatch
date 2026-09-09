# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /build

# Copy only the POMs first and resolve dependencies as their own layer. A change to
# source code then rebuilds in seconds instead of re-downloading the world.
COPY pom.xml .
COPY common/pom.xml common/
COPY rule-engine/pom.xml rule-engine/
COPY transaction-simulator/pom.xml transaction-simulator/
COPY fraud-engine/pom.xml fraud-engine/
COPY fraud-api/pom.xml fraud-api/
RUN mvn -B -q dependency:go-offline -pl common,transaction-simulator -am

COPY common/src common/src
COPY transaction-simulator/src transaction-simulator/src
RUN mvn -B -q clean package -pl common,transaction-simulator -am -DskipTests

# Fail here, loudly, if the jar is not an executable Spring Boot archive.
# A repackaged jar contains the Boot loader; a plain library jar does not,
# and would otherwise only reveal itself at runtime as the unhelpful
# "no main manifest attribute, in app.jar".
RUN set -eu; \
    jar=$(ls transaction-simulator/target/*.jar | head -1); \
    if ! jar tf "$jar" | grep -q 'org/springframework/boot/loader/'; then \
        echo "BUILD ERROR: $jar is not an executable Spring Boot jar."; \
        echo "The spring-boot-maven-plugin repackage goal did not run."; \
        exit 1; \
    fi; \
    cp "$jar" /build/app.jar

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run unprivileged. A container that does not need root should not have it.
RUN addgroup -S overwatch && adduser -S overwatch -G overwatch
COPY --from=build /build/app.jar app.jar
RUN chown -R overwatch:overwatch /app
USER overwatch

EXPOSE 8081

# Container-aware heap sizing, and a fast-exit on OOM so the orchestrator can
# restart rather than leaving a wedged JVM behind.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
