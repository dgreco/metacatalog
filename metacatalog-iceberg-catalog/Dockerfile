# AOT stage: extract the jar and produce an ahead-of-time cache. Must run on the
# same image and path (/app) as the runtime stage — the cache is only accepted by
# the exact JVM build and classpath it was trained with. The training run boots to
# context refresh with everything database-touching disabled, then exits
# (spring.context.exit=onRefresh returns before the ApplicationRunners); the JVM
# writes app.aot on the way out. A cache the runtime JVM deems stale is ignored
# with a warning, never an error (AOTMode=auto). See the root Dockerfile.
FROM eclipse-temurin:26-jre-alpine AS aot
WORKDIR /app
COPY target/*.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /app
# AOTClassLinking off: with it on, the production JVM dies loading aot-linked classes
# (Temurin 26, alpine and glibc alike); see the root Dockerfile.
RUN JDK_AOT_VM_OPTIONS="-XX:-AOTClassLinking" \
    java -XX:AOTCacheOutput=app.aot \
    -Dspring.context.exit=onRefresh \
    -Dspring.flyway.enabled=false \
    -Dspring.jpa.properties.hibernate.boot.allow_jdbc_metadata_access=false \
    -Dspring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect \
    -Dapplication.config.automaticEntitiesMapping=false \
    -jar app.jar

FROM eclipse-temurin:26-jre-alpine
RUN addgroup -S -g 1000 appgroup && adduser -S -u 1000 -G appgroup appuser
USER 1000:1000
WORKDIR /app
COPY --from=aot /app/ ./
EXPOSE 8181
ENTRYPOINT ["java", "-XX:AOTCache=app.aot", "-jar", "app.jar"]
