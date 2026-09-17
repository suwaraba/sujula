#!/usr/bin/env bash
#
# Boots a server, runs the collection against it, and stops it again.
#
#     ./run.sh                  the whole collection
#     ./run.sh --folder "01 — Identity: who gets a token, and who does not"
#     ./run.sh --bail           stop at the first failure
#
# Any further arguments are passed straight to newman.
#
# The server is started fresh every time, and that is not tidiness. The seed
# contains one-shot credentials — refresh tokens that rotate on use, a phone
# challenge that is consumed when it is confirmed, stock that is decremented by
# a checkout — so a second run against a server that has already been used is a
# run against a different world. The database is in memory, so a restart IS a
# re-seed and costs about twelve seconds.
#
# Point it at something else with BASE_URL, and it will use that instead of
# starting anything:
#
#     BASE_URL=https://staging.sujula.gm ./run.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
PORT="${E2E_PORT:-8080}"
LOG="$HERE/server.log"
STARTED_SERVER=0

cd "$HERE"

if [ ! -d node_modules ]; then
  echo "==> installing newman"
  npm install --silent
fi

echo "==> building the collection"
node build.js

if [ -n "${BASE_URL:-}" ]; then
  echo "==> using the server at $BASE_URL"
else
  BASE_URL="http://localhost:$PORT"
  echo "==> starting a server on :$PORT (profile e2e, in-memory database, seed loaded)"
  ( cd "$ROOT" && mvn -o -q spring-boot:run -Dspring-boot.run.profiles=e2e ) > "$LOG" 2>&1 &
  SERVER_PID=$!
  STARTED_SERVER=1

  cleanup() {
    if [ "$STARTED_SERVER" = "1" ]; then
      echo "==> stopping the server"
      # The Maven process forks the application; killing the group gets both.
      kill -- "-$(ps -o pgid= "$SERVER_PID" | tr -d ' ')" 2>/dev/null || kill "$SERVER_PID" 2>/dev/null || true
    fi
  }
  trap cleanup EXIT

  for _ in $(seq 1 90); do
    if curl -fsS "$BASE_URL/health/readiness" >/dev/null 2>&1; then break; fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
      echo "the server exited before it was ready; last lines of $LOG:" >&2
      tail -30 "$LOG" >&2
      exit 1
    fi
    sleep 2
  done

  if ! curl -fsS "$BASE_URL/health/readiness" >/dev/null 2>&1; then
    echo "the server did not become ready within three minutes; see $LOG" >&2
    exit 1
  fi
  echo "==> server ready, seed loaded"
fi

echo "==> running the collection"
npx newman run sujula.postman_collection.json \
  --env-var "baseUrl=$BASE_URL" \
  --reporters cli \
  --reporter-cli-no-banner \
  "$@"
