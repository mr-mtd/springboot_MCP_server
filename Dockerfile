# syntax=docker/dockerfile:1

# ---- build stage: compile and package the jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Resolve dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Tests need Docker (Testcontainers), so they run in CI with `mvn verify`, not inside the image build.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package \
    && cp target/transport-mcp-server-*.jar /src/app.jar

# ---- runtime stage: JRE only ----
FROM eclipse-temurin:21-jre
WORKDIR /app

# Amazon RDS CA bundle, so DB_URL can use sslmode=verify-full&sslrootcert=/app/certs/global-bundle.pem
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /app/certs/global-bundle.pem

RUN groupadd --system app && useradd --system --gid app app \
    && chmod 644 /app/certs/global-bundle.pem
COPY --from=build /src/app.jar /app/app.jar
USER app

ENV PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
