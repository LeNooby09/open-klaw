#!/bin/bash
# ─────────────────────────────────────────────────────────
# Open-Klaw Docker Entrypoint
#
# Ensures required data directories exist before starting
# the application. Runs as the container user (openklaw).
# ─────────────────────────────────────────────────────────
set -e

# Create data subdirectories (best-effort; may fail if /data is read-only)
mkdir -p /data/logs /data/conversations /data/skills 2>/dev/null || true

exec /opt/open-klaw/bin/open-klaw "$@"
