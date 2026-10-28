# syntax=docker/dockerfile:1

# ---- build: compiles and extracts the jar into layers (dependencies change less often than code) ----
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src src
# Tests run in CI (they need Docker for Testcontainers); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && java -Djarmode=tools -jar target/activation-service-*.jar extract --layers --launcher --destination extracted

# ---- runtime: JRE only, non-root ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./

USER app

# Heap sized from the container memory limit, not the host's; crash fast on OOM so the orchestrator restarts us.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# Exec form: the JVM is PID 1 and receives SIGTERM directly, enabling graceful shutdown.
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
