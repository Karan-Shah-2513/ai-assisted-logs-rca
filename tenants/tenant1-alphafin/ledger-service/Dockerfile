FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG JAR_FILE=tenant1-alphafin/ledger-service/target/ledger-service-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8082

ENTRYPOINT ["java", "-jar", "app.jar"]

