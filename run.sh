#!/usr/bin/env sh
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAR="$ROOT/aft-server/target/aft-server-0.1.0-SNAPSHOT-exec.jar"
PORT="${SERVER_PORT:-51780}"

if [ ! -f "$JAR" ]; then
  "$ROOT/build.sh"
fi

printf '\nAFT Studio is starting...\nOpen: http://127.0.0.1:%s\nPress Ctrl+C to stop.\n\n' "$PORT"
exec java -jar "$JAR" "--server.port=$PORT"
