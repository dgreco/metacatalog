FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 1000 appgroup && adduser -S -u 1000 -G appgroup appuser
USER 1000:1000
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
