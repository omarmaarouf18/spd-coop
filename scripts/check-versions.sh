#!/usr/bin/env bash
#
# scripts/check-versions.sh — version drift guard (WF-01).
#
# The canonical versions are in tools/versions.env; nothing here is
# hard-coded. Every file below must agree with them (files that do not exist
# yet are skipped):
#   - server/go.mod: `go <major.minor>[.patch]` with the major.minor of
#     GO_VERSION, and `toolchain go<GO_VERSION>` if a toolchain line exists
#   - server/Dockerfile: every `FROM golang:<version>[-variant]` uses GO_VERSION
#   - .github/workflows/*.yml: a literal `go-version:` must be GO_VERSION and a
#     literal `java-version:` must be JDK_VERSION. Workflows should read
#     tools/versions.env instead (`${{ ... }}` expressions are not checked).
# Run by .githooks/pre-push (server / actions scope) and by ci.yml.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"
# shellcheck source=tools/versions.env
. tools/versions.env
[ -n "${GO_VERSION:-}" ] || { echo "BLOCKED: GO_VERSION is not set in tools/versions.env"; exit 1; }
[ -n "${JDK_VERSION:-}" ] || { echo "BLOCKED: JDK_VERSION is not set in tools/versions.env"; exit 1; }

GO_MINOR="$(cut -d. -f1-2 <<<"$GO_VERSION")"
FAILED=0
block() {
	echo "BLOCKED: $*"
	FAILED=1
}

if [ -f server/go.mod ]; then
	v="$(awk '$1 == "go" { print $2; exit }' server/go.mod)"
	case "$v" in
	"$GO_MINOR" | "$GO_MINOR".*) ;;
	*) block "Go language version drift in server/go.mod: expected ${GO_MINOR}[.x], found '${v:-NONE}'" ;;
	esac
	tc="$(awk '$1 == "toolchain" { print $2; exit }' server/go.mod)"
	if [ -n "$tc" ] && [ "$tc" != "go$GO_VERSION" ]; then
		block "Go toolchain drift in server/go.mod: expected go$GO_VERSION, found '$tc'"
	fi
fi

if [ -f server/Dockerfile ]; then
	while read -r img; do
		tag="${img#golang:}"
		case "$tag" in
		"$GO_VERSION" | "$GO_VERSION"-*) ;;
		*) block "Go version drift in server/Dockerfile: expected golang:${GO_VERSION}[-variant], found '$img'" ;;
		esac
	done < <(grep -oiE '^FROM[[:space:]]+golang:[^[:space:]]+' server/Dockerfile | awk '{ print $2 }')
fi

shopt -s nullglob
for wf in .github/workflows/*.yml .github/workflows/*.yaml; do
	while IFS=: read -r line v; do
		[ "$v" = "$GO_VERSION" ] || block "Go version drift in $wf:$line: expected $GO_VERSION, found '$v' (read tools/versions.env instead)"
	done < <(grep -noE "go-version:[[:space:]]*['\"]?[0-9][0-9.]*" "$wf" | sed -E "s/^([0-9]+):go-version:[[:space:]]*['\"]?/\1:/" || true)
	while IFS=: read -r line v; do
		[ "$v" = "$JDK_VERSION" ] || block "JDK version drift in $wf:$line: expected $JDK_VERSION, found '$v' (read tools/versions.env instead)"
	done < <(grep -noE "java-version:[[:space:]]*['\"]?[0-9][0-9.]*" "$wf" | sed -E "s/^([0-9]+):java-version:[[:space:]]*['\"]?/\1:/" || true)
done

if [ "$FAILED" -ne 0 ]; then
	exit 1
fi
echo "version consistency: OK (GO_VERSION=$GO_VERSION, JDK_VERSION=$JDK_VERSION from tools/versions.env)"
