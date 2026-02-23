#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────
# Open-Klaw Launcher
#
# By default, runs in a Docker container for sandboxed execution.
# Use --bare-metal to run directly on the host (requires JDK 21+).
# ─────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
	echo "Usage: $0 [OPTIONS]"
	echo ""
	echo "Options:"
	echo "  --bare-metal    Run directly on the host instead of Docker (unsafe)"
	echo "  --build         Force rebuild the Docker image before starting"
	echo "  --detach, -d    Run Docker container in detached mode"
	echo "  --help, -h      Show this help message"
	echo ""
	echo "Environment variables:"
	echo "  OPENAI_API_KEY       OpenAI API key"
	echo "  ANTHROPIC_API_KEY    Anthropic API key"
	echo "  OPENROUTER_API_KEY   OpenRouter API key"
	echo "  OPENKLAW_PORT        Server port (default: 8080)"
}

BARE_METAL=false
FORCE_BUILD=false
DETACH=""

while [[ $# -gt 0 ]]; do
	case "$1" in
		--bare-metal)
			BARE_METAL=true
			shift
			;;
		--build)
			FORCE_BUILD=true
			shift
			;;
		--detach|-d)
			DETACH="-d"
			shift
			;;
		--help|-h)
			usage
			exit 0
			;;
		*)
			echo "Unknown option: $1"
			usage
			exit 1
			;;
	esac
done

if [ "$BARE_METAL" = true ]; then
	echo "╔══════════════════════════════════════════════════════════╗"
	echo "║  ⚠  WARNING: Running in bare-metal mode.               ║"
	echo "║  Shell and filesystem tools have UNRESTRICTED access    ║"
	echo "║  to the host system. Use Docker for safer execution.    ║"
	echo "╚══════════════════════════════════════════════════════════╝"
	echo ""

	cd "$SCRIPT_DIR"

	if [ ! -f gradlew ]; then
		echo "Error: gradlew not found in $SCRIPT_DIR"
		exit 1
	fi

	chmod +x gradlew
	export OPENKLAW_BARE_METAL=true
	exec ./gradlew run
else
	echo "Starting Open-Klaw in Docker (sandboxed)..."

	cd "$SCRIPT_DIR"

	if ! command -v docker &> /dev/null; then
		echo "Error: Docker is not installed. Install Docker or use --bare-metal to run directly."
		exit 1
	fi

	if [ ! -f "$SCRIPT_DIR/docker-compose.yml" ]; then
		echo "Error: docker-compose.yml not found in $SCRIPT_DIR"
		exit 1
	fi

	# Ensure config.yaml exists so the bind mount works (Docker would create a directory otherwise)
	if [ ! -f "$SCRIPT_DIR/config.yaml" ]; then
		touch "$SCRIPT_DIR/config.yaml"
	fi

	if [ "$FORCE_BUILD" = true ]; then
		echo "Building Docker image..."
		docker compose build
	fi

	exec docker compose up $DETACH
fi
