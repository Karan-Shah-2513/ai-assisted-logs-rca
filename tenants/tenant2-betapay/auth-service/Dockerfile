FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG JAR_FILE=tenant2-betapay/auth-service/target/auth-service-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]

