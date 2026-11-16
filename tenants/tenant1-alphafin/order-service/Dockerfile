FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ARG JAR_FILE=tenant1-alphafin/order-service/target/order-service-*.jar
COPY ${JAR_FILE} app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "app.jar"]

