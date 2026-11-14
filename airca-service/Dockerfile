# Build stage
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Pre-fetch dependencies to leverage Docker layer caching
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Build the application
COPY src ./src
RUN mvn clean package -DskipTests -B

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache git && git config --system --add safe.directory '*'

# Run as an unprivileged user
RUN addgroup -S airca && adduser -S airca -G airca

COPY --from=builder /build/target/airca-backend-*.jar app.jar
RUN chown -R airca:airca /app

USER airca

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
