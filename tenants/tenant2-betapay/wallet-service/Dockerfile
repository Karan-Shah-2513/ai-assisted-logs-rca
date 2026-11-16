FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG JAR_FILE=tenant2-betapay/wallet-service/target/wallet-service-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]

