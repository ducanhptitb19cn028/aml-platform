# ── Build stage ──────────────────────────────────────────────────────────────
FROM maven:3.9.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

COPY pom.xml ./pom.xml
COPY telemetry-collector/pom.xml ./telemetry-collector/pom.xml
COPY stream-processor/pom.xml ./stream-processor/pom.xml
COPY decision-engine/pom.xml ./decision-engine/pom.xml
COPY remediation-engine/pom.xml ./remediation-engine/pom.xml
COPY alerting-service/pom.xml ./alerting-service/pom.xml
COPY feedback-service/pom.xml ./feedback-service/pom.xml

RUN mvn -B -pl alerting-service -am dependency:go-offline

COPY alerting-service/src ./alerting-service/src

RUN mvn -B -DskipTests -pl alerting-service -am package

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S aiops && adduser -S aiops -G aiops
USER aiops

COPY --from=builder /build/alerting-service/target/*.jar app.jar

EXPOSE 9005

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
