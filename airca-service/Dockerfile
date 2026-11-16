FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN apk add --no-cache git && git config --system --add safe.directory '*'

# Run as an unprivileged user
RUN addgroup -S airca && adduser -S airca -G airca

ARG JAR_FILE=target/airca-backend-*.jar
COPY ${JAR_FILE} app.jar
RUN chown -R airca:airca /app

USER airca

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]

