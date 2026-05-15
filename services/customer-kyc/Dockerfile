# syntax=docker/dockerfile:1.6
# Build context: project root  →  docker build -f services/customer-kyc/Dockerfile .
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

RUN apk add --no-cache maven=3.9.9-r0 || apk add --no-cache maven

COPY pom.xml ./pom.xml
# All three service POMs must exist so Maven can parse the full reactor.
# Source is NOT copied yet — this layer is only invalidated when a POM changes.
COPY services/case-management/pom.xml services/case-management/pom.xml
COPY services/transaction-monitoring/pom.xml services/transaction-monitoring/pom.xml
COPY services/customer-kyc/pom.xml services/customer-kyc/pom.xml

RUN mvn -B -pl services/customer-kyc -am dependency:go-offline -q

COPY services/customer-kyc/src services/customer-kyc/src

RUN mvn -B -DskipTests -pl services/customer-kyc package && \
    rm -f /workspace/services/customer-kyc/target/*-plain.jar

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /workspace/services/customer-kyc/target/customer-kyc-*.jar /app/app.jar
USER app
EXPOSE 8082
ENTRYPOINT ["java", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "-jar", "/app/app.jar"]
