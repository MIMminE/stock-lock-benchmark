#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-runtime/docker-compose.yml}"
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-stock-lock-runtime-smoke}"

cleanup() {
  docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" down -v --remove-orphans
}

trap cleanup EXIT

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" up -d --build

for attempt in $(seq 1 60); do
  if curl -fsS http://localhost:18081/actuator/health >/dev/null; then
    curl -fsS http://localhost:18081 >/dev/null
    echo "Runtime smoke test passed."
    exit 0
  fi
  sleep 5
done

docker compose -p "$PROJECT_NAME" -f "$COMPOSE_FILE" logs
echo "Runtime smoke test failed." >&2
exit 1
