# syntax=docker/dockerfile:1

# ---- build -------------------------------------------------------------------------------------
# Build context is backend/ (see deploy/docker-compose.prod.yml). Tests are not run here: they need
# a database and belong in CI, not in the image build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# The Gradle download cache is a BuildKit cache mount, so dependencies are fetched once per host,
# not once per source change.
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :app:bootJar -x test \
    && cp "$(ls app/build/libs/app-*.jar | grep -v -- '-plain.jar')" /app.jar

# ---- runtime -----------------------------------------------------------------------------------
# Alpine JRE: small, and busybox wget is what the compose healthcheck uses.
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S ppob2 && adduser -S -G ppob2 ppob2
WORKDIR /opt/ppob2
COPY --from=build /app.jar app.jar
USER ppob2

# Heap is a share of the container limit, not of the host, so `mem_limit` in compose is what sizes it.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/ppob2/app.jar"]
