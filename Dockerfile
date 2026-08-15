# syntax=docker/dockerfile:1
#
# Self-contained build of the Meta Catalog application: the whole Maven reactor
# is compiled and packaged from source inside the image, so `docker compose up
# --build` needs no pre-built jar on the host. Used by docker-compose.yml.
#
# The CI pipeline uses metacatalog-application/Dockerfile instead (which copies a
# jar already built by Maven); this file is for running everything locally.
#
# Build performance:
#   * The Maven local repository (~/.m2) is a BuildKit cache mount, so downloaded
#     dependencies survive across builds without being baked into an image layer.
#   * All module POMs are copied and `dependency:go-offline` is run BEFORE the
#     sources are copied, so the dependency-resolution layer is cached and only
#     invalidated when a POM changes — not on every source edit.
#   * Requires BuildKit (default on Docker 23.0+ / buildx).

# --- Build stage: compile the reactor and package the application jar ---
FROM maven:3-eclipse-temurin-26 AS build

# Which deployable module this image runs: metacatalog-application (default) or
# metacatalog-iceberg-catalog. docker-compose.yml passes it per service.
ARG MODULE=metacatalog-application
WORKDIR /workspace

# Copy every module POM (and the root POM) first so dependency resolution can be
# cached independently of sources. The reactor has 9 modules — copying all of them
# (not just the app's upstream) lets `go-offline` resolve the full project graph
# in the cache layer below.
COPY pom.xml ./
COPY metacatalog-core/pom.xml          metacatalog-core/
COPY metacatalog-functions/pom.xml     metacatalog-functions/
COPY metacatalog-functions-provisioning-tasks/pom.xml metacatalog-functions-provisioning-tasks/
COPY metacatalog-openapi/pom.xml       metacatalog-openapi/
COPY metacatalog-ui/pom.xml            metacatalog-ui/
COPY metacatalog-security/pom.xml     metacatalog-security/
COPY metacatalog-sparql/pom.xml        metacatalog-sparql/
COPY metacatalog-application/pom.xml    metacatalog-application/
COPY metacatalog-iceberg-catalog/pom.xml metacatalog-iceberg-catalog/
# Pre-populate the Maven repo for the full reactor. --mount=type=cache keeps the
# repo across builds (outside any image layer); a fresh build reuses whatever the
# previous build already downloaded. `dependency:go-offline` can't fully resolve
# reactor-internal artifacts (their jars don't exist yet), so it's allowed to
# fail on those and still produce a usable cache of external dependencies.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -DskipTests dependency:go-offline || true

# Copy the sources and build the application jar (tests need Docker/Testcontainers,
# so they are skipped here; run `mvn verify` on the host for the full test suite).
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -pl ${MODULE} -am -DskipTests -Djacoco.skip=true clean package \
    && cp ${MODULE}/target/*.jar /workspace/app.jar

# --- Runtime stage: slim JRE running the packaged jar as a non-root user ---
FROM eclipse-temurin:26-jre-alpine
RUN addgroup -S -g 1000 appgroup && adduser -S -u 1000 -G appgroup appuser
USER 1000:1000
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
# 8080 = metacatalog-application, 8181 = metacatalog-iceberg-catalog
EXPOSE 8080 8181
ENTRYPOINT ["java", "-jar", "app.jar"]
