# metadata-service/Dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/metadata-service-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]