# Multi-stage build for Meta Catalog Spring Boot application

# Stage 1: Build
FROM maven:3-eclipse-temurin-25 AS builder

WORKDIR /build

# Copy pom files first for better caching
COPY pom.xml .
COPY metacatalog-core/pom.xml metacatalog-core/
COPY metacatalog-functions/pom.xml metacatalog-functions/
COPY metacatalog-openapi/pom.xml metacatalog-openapi/
COPY metacatalog-application/pom.xml metacatalog-application/

# Download dependencies (this layer will be cached if pom files don't change)
RUN mvn dependency:go-offline -B || true

# Copy source code
COPY metacatalog-core/src metacatalog-core/src
COPY metacatalog-functions/src metacatalog-functions/src
COPY metacatalog-openapi/src metacatalog-openapi/src
COPY metacatalog-application/src metacatalog-application/src

# Build the application (skip tests as they require database)
RUN mvn clean package -DskipTests -B

# Stage 2: Runtime
FROM eclipse-temurin:25-jre-alpine

WORKDIR /app

# Create non-root user for security
RUN addgroup -g 1000 metacatalog && \
    adduser -u 1000 -G metacatalog -s /bin/sh -D metacatalog

# Copy the built artifact
COPY --from=builder /build/metacatalog-application/target/*.jar app.jar

# Change ownership
RUN chown -R metacatalog:metacatalog /app

# Switch to non-root user
USER metacatalog

# Spring Boot configuration
ENV JAVA_OPTS="-Xms256m -Xmx768m"
ENV SPRING_PROFILES_ACTIVE="docker"

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
