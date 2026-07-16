# ---- Build stage -----------------------------------------------------------
# Uses a JDK 21 image so the build matches the pinned Gradle toolchain.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Cache dependencies first: copy only build scripts + wrapper, then resolve.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true

# Copy sources and build the executable jar (tests run in CI, skipped here).
COPY src ./src
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- Runtime stage ---------------------------------------------------------
# Slim JRE image for a smaller, safer runtime footprint.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /workspace/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
