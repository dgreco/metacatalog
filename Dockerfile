# syntax=docker/dockerfile:1
#
# Self-contained build of the Meta Catalog application: the whole Maven reactor
# is compiled and packaged from source inside the image, so `docker compose up
# --build` needs no pre-built jar on the host. Used by docker-compose.yml.
#
# The CI pipeline uses metacatalog-application/Dockerfile instead (which copies a
# jar already built by Maven); this file is for running everything locally.

# --- Build stage: compile the reactor and package the application jar ---
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /workspace

# Copy the POMs first so dependency resolution can be cached independently of sources.
COPY pom.xml ./
COPY metacatalog-core/pom.xml metacatalog-core/
COPY metacatalog-functions/pom.xml metacatalog-functions/
COPY metacatalog-openapi/pom.xml metacatalog-openapi/
COPY metacatalog-ui/pom.xml metacatalog-ui/
COPY metacatalog-application/pom.xml metacatalog-application/
RUN mvn -B -pl metacatalog-application -am -DskipTests dependency:go-offline || true

# Copy the sources and build the application jar (tests need Docker/Testcontainers,
# so they are skipped here; run `mvn verify` on the host for the full test suite).
COPY . .
RUN mvn -B -pl metacatalog-application -am -DskipTests -Djacoco.skip=true clean package

# --- Runtime stage: slim JRE running the packaged jar as a non-root user ---
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 1000 appgroup && adduser -S -u 1000 -G appgroup appuser
USER 1000:1000
WORKDIR /app
COPY --from=build /workspace/metacatalog-application/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
