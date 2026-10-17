#!/usr/bin/env bash
#
# scripts/compose-validate.sh — validate the deploy/ compose files (WF-01).
#
#   scripts/compose-validate.sh
#
# Runs `docker compose config --quiet` on:
#   - deploy/docker-compose.dev.yml, in place;
#   - deploy/docker-compose.prod.yml, the way CI syncs it to the deploy repo
#     (architecture §4.3): copied with Caddyfile into a scratch dir as
#     docker-compose.yml, `:IMAGE_TAG` replaced by a dummy tag (any IMAGE_TAG
#     left over blocks), and `.env` created from deploy/.env.example. No real
#     secrets are ever needed.
# A file that does not exist yet is skipped with a note (deploy/ lands in
# SRV-00). Exit 0 = OK, 1 = BLOCKED.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

DEV="deploy/docker-compose.dev.yml"
PROD="deploy/docker-compose.prod.yml"

if [ ! -f "$DEV" ] && [ ! -f "$PROD" ]; then
	echo "compose-validate: no deploy/ compose files yet, skipped"
	exit 0
fi
if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
	echo "BLOCKED: docker with the compose plugin is required to validate $DEV / $PROD"
	exit 1
fi

if [ -f "$DEV" ]; then
	echo "-- docker compose config: $DEV --"
	docker compose -f "$DEV" config --quiet || {
		echo "BLOCKED: $DEV is not a valid compose file"
		exit 1
	}
fi

if [ -f "$PROD" ]; then
	echo "-- docker compose config: $PROD (IMAGE_TAG substituted, .env from .env.example) --"
	tmp="$(mktemp -d)"
	trap 'rm -rf "$tmp"' EXIT
	sed 's/:IMAGE_TAG/:0000000-validate/g' "$PROD" >"$tmp/docker-compose.yml"
	if grep -q 'IMAGE_TAG' "$tmp/docker-compose.yml"; then
		echo "BLOCKED: IMAGE_TAG left in $PROD after substitution (only the ':IMAGE_TAG' form is replaced)"
		exit 1
	fi
	[ ! -f deploy/Caddyfile ] || cp deploy/Caddyfile "$tmp/Caddyfile"
	if [ -f deploy/.env.example ]; then
		cp deploy/.env.example "$tmp/.env"
	else
		: >"$tmp/.env"
	fi
	(cd "$tmp" && docker compose -f docker-compose.yml config --quiet) || {
		echo "BLOCKED: $PROD is not a valid compose file after CI-style substitution"
		exit 1
	}
fi
echo "compose-validate: OK"
