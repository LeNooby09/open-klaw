# ─────────────────────────────────────────────────────────
# Open-Klaw — Sandboxed Docker Build
# ─────────────────────────────────────────────────────────
# Stage 1: Build the application
FROM eclipse-temurin:21.0.6_7-jdk-noble AS builder

WORKDIR /build
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Cache dependencies
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src/ src/
RUN ./gradlew installDist --no-daemon

# ─────────────────────────────────────────────────────────
# Stage 2: Minimal runtime image with sandbox restrictions
FROM eclipse-temurin:21.0.6_7-jre-noble

# Install minimal tools the shell/filesystem tools may need
RUN apt-get update && apt-get install -y --no-install-recommends \
    bash coreutils curl git && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user for the application
RUN groupadd -r openklaw && useradd -r -g openklaw -m -d /home/openklaw -s /bin/bash openklaw

# Create workspace directory where file/shell tools operate (sandboxed)
RUN mkdir -p /workspace /data/conversations && \
    chown -R openklaw:openklaw /workspace /data

# Copy the built application
COPY --from=builder /build/build/install/open-klaw /opt/open-klaw
RUN chown -R openklaw:openklaw /opt/open-klaw

# Mark the container so the application can detect it is sandboxed
ENV OPENKLAW_SANDBOXED=true

# Default tool settings — scope file operations to /workspace
ENV OPENKLAW_FILE_BASE_DIR=/workspace
ENV OPENKLAW_BIND=0.0.0.0
ENV OPENKLAW_PORT=8080

# Conversation archive storage
ENV OPENKLAW_DATA_DIR=/data

EXPOSE 8080

USER openklaw
WORKDIR /workspace

ENTRYPOINT ["/opt/open-klaw/bin/open-klaw"]
