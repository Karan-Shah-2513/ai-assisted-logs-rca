# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Pre-fetch dependencies to leverage Docker layer caching
COPY pom.xml .
COPY tenant-common/pom.xml tenant-common/
COPY tenant1-alphafin/order-service/pom.xml tenant1-alphafin/order-service/
COPY tenant1-alphafin/ledger-service/pom.xml tenant1-alphafin/ledger-service/
COPY tenant2-betapay/auth-service/pom.xml tenant2-betapay/auth-service/
COPY tenant2-betapay/wallet-service/pom.xml tenant2-betapay/wallet-service/

RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B || true

# Copy source trees required for reactor build
COPY tenant-common ./tenant-common
COPY tenant1-alphafin/ledger-service ./tenant1-alphafin/ledger-service

# Build tenant-common and ledger-service via Maven reactor
RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -pl tenant1-alphafin/ledger-service -am -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=builder /build/tenant1-alphafin/ledger-service/target/ledger-service-*.jar app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]
