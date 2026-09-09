# ---- build stage -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25-alpine AS build
WORKDIR /build

# Copy only the POMs first and resolve dependencies as their own layer. A change to
# source code then rebuilds in seconds instead of re-downloading the world.
COPY pom.xml .
COPY common/pom.xml common/
COPY transaction-simulator/pom.xml transaction-simulator/
COPY fraud-engine/pom.xml fraud-engine/
COPY fraud-api/pom.xml fraud-api/
RUN mvn -B -q dependency:go-offline -pl common,fraud-engine -am

COPY common/src common/src
COPY fraud-engine/src fraud-engine/src
RUN mvn -B -q clean package -pl common,fraud-engine -am -DskipTests

# ---- runtime stage -----------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

# Run unprivileged. A container that does not need root should not have it.
RUN addgroup -S overwatch && adduser -S overwatch -G overwatch
COPY --from=build /build/fraud-engine/target/*.jar app.jar
RUN chown -R overwatch:overwatch /app
USER overwatch

EXPOSE 8082

# Container-aware heap sizing, and a fast-exit on OOM so the orchestrator can
# restart rather than leaving a wedged JVM behind.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
