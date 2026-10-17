#!/usr/bin/env bash
#
# scripts/server-probe.sh — end-to-end server check in Docker (WF-05).
#
#   make server-probe
#
# Runs the whole SRV-01 flow against the dev compose server:
#   1. docker compose -f deploy/docker-compose.dev.yml up -d --build --wait
#   2. lobbyprobe -state <tmp>   (two clients: lobby, RUN_START, GHOST, drop, resume)
#   3. docker compose ... restart server, then wait until it is healthy again
#   4. lobbyprobe -resume <tmp>  (both players back in the same run after the restart)
#   5. docker compose ... down   (always, even on failure; the data volume is kept)
# Go runs with GOTOOLCHAIN=go$GO_VERSION from tools/versions.env, so a host Go
# older than server/go.mod (or GOTOOLCHAIN=local) still works.
# The state file holds two test resume tokens: it lives in a private temp dir
# and is deleted at the end. Exit 0 = PASS, 1 = FAIL.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# shellcheck source=tools/versions.env
. tools/versions.env
export GOTOOLCHAIN="go${GO_VERSION}"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
	echo "FAIL: docker with the compose plugin is required"
	exit 1
fi
if ! command -v go >/dev/null 2>&1; then
	echo "FAIL: go is required on PATH (any Go >= 1.21; GOTOOLCHAIN fetches go${GO_VERSION})"
	exit 1
fi

COMPOSE=(docker compose -f deploy/docker-compose.dev.yml)
STATE_DIR="$(mktemp -d)"
chmod 700 "$STATE_DIR"
STATE="$STATE_DIR/probe.json"

cleanup() {
	echo "== server-probe: compose down =="
	"${COMPOSE[@]}" down || true
	rm -rf "$STATE_DIR"
}
trap cleanup EXIT

probe() {
	(cd server && go run ./cmd/lobbyprobe "$@")
}

echo "== server-probe: compose up (build, wait until healthy) =="
"${COMPOSE[@]}" up -d --build --wait

echo "== server-probe: full flow, saving resume data =="
probe -state "$STATE"

echo "== server-probe: restart the server (same volume) =="
"${COMPOSE[@]}" restart server
"${COMPOSE[@]}" up -d --wait

echo "== server-probe: resume both players into the running run =="
probe -resume "$STATE"

echo "server-probe: PASS"
