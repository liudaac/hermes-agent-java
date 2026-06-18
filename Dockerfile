# Hermes Agent Java — Docker Build
# Multi-stage build for smaller production image

## Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src

# Build the shaded JAR (skip tests for Docker build; CI runs tests separately)
RUN mvn -B -q -DskipTests package

## Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="hermes-team"
LABEL description="Hermes Agent Java — Business Agent Team Platform"

# Install basic utilities for health checks and debugging
RUN apk add --no-cache curl ca-certificates

WORKDIR /app

# Copy the shaded JAR from builder
COPY --from=builder /build/target/hermes-agent-java-0.1.0-SNAPSHOT.jar hermes.jar

# Create data volume mount point
RUN mkdir -p /data && chmod 755 /data

# Environment variables with sensible defaults
ENV HERMES_HOME=/data
ENV HERMES_PORT=8080
ENV HERMES_HOST=0.0.0.0
ENV HERMES_PROFILE=production
ENV JAVA_OPTS="-Xms256m -Xmx1g -XX:+UseG1GC"

# Expose the dashboard / API port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -sf http://localhost:8080/health || exit 1

# Run the dashboard by default (can be overridden for other commands)
ENTRYPOINT ["sh", "-c"]
CMD ["exec java $JAVA_OPTS -jar hermes.jar dashboard --port $HERMES_PORT --host $HERMES_HOST"]
