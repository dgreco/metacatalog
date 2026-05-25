FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser:appgroup
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
