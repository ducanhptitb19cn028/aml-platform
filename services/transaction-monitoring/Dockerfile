# syntax=docker/dockerfile:1.6
# Build context: project root  →  docker build -f services/transaction-monitoring/Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache maven=3.9.9-r0 || apk add --no-cache maven

COPY pom.xml ./pom.xml
# All three service POMs must exist so Maven can parse the full reactor.
# Source is NOT copied yet — this layer is only invalidated when a POM changes.
COPY services/case-management/pom.xml services/case-management/pom.xml
COPY services/transaction-monitoring/pom.xml services/transaction-monitoring/pom.xml
COPY services/customer-kyc/pom.xml services/customer-kyc/pom.xml

RUN mvn -B -pl services/transaction-monitoring -am dependency:go-offline -q

COPY services/transaction-monitoring/src services/transaction-monitoring/src

RUN mvn -B -DskipTests -pl services/transaction-monitoring package && \
    rm -f /workspace/services/transaction-monitoring/target/*-plain.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/services/transaction-monitoring/target/transaction-monitoring-*.jar /app/app.jar
USER app
EXPOSE 8081
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "/app/app.jar"]
