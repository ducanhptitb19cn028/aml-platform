# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Copy parent POM first for better layer caching
COPY pom.xml ./pom.xml
COPY telemetry-collector/pom.xml ./telemetry-collector/pom.xml
COPY stream-processor/pom.xml ./stream-processor/pom.xml
COPY decision-engine/pom.xml ./decision-engine/pom.xml
COPY remediation-engine/pom.xml ./remediation-engine/pom.xml
COPY alerting-service/pom.xml ./alerting-service/pom.xml
COPY feedback-service/pom.xml ./feedback-service/pom.xml

# Pre-fetch dependencies for the module and its parent
RUN mvn -B -pl telemetry-collector -am dependency:go-offline

# Copy source
COPY telemetry-collector/src ./telemetry-collector/src

# Package (skip tests — tests run in CI)
RUN mvn -B -DskipTests -pl telemetry-collector -am package

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S aiops && adduser -S aiops -G aiops
USER aiops

COPY --from=builder /build/telemetry-collector/target/*.jar app.jar

EXPOSE 9001

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
