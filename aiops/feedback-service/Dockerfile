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

RUN mvn -B -pl feedback-service -am dependency:go-offline

COPY feedback-service/src ./feedback-service/src

RUN mvn -B -DskipTests -pl feedback-service -am package

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -S aiops && adduser -S aiops -G aiops
USER aiops

COPY --from=builder /build/feedback-service/target/*.jar app.jar

EXPOSE 9006

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
