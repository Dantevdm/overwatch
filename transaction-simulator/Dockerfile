# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Copy only the POMs first and resolve dependencies as their own layer. A change to
# source code then rebuilds in seconds instead of re-downloading the world.
COPY pom.xml .
COPY common/pom.xml common/
COPY transaction-simulator/pom.xml transaction-simulator/
COPY fraud-engine/pom.xml fraud-engine/
COPY fraud-api/pom.xml fraud-api/
RUN mvn -B -q dependency:go-offline -pl common,transaction-simulator -am

COPY common/src common/src
COPY transaction-simulator/src transaction-simulator/src
RUN mvn -B -q clean package -pl common,transaction-simulator -am -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run unprivileged. A container that does not need root should not have it.
RUN addgroup -S overwatch && adduser -S overwatch -G overwatch
COPY --from=build /build/transaction-simulator/target/*.jar app.jar
RUN chown -R overwatch:overwatch /app
USER overwatch

EXPOSE 8081

# Container-aware heap sizing, and a fast-exit on OOM so the orchestrator can
# restart rather than leaving a wedged JVM behind.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
