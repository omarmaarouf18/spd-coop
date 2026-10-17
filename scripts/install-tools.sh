#!/usr/bin/env bash
#
# scripts/install-tools.sh — install the pinned gate tools (WF-01).
#
#   scripts/install-tools.sh [tool...]     # tools: gitleaks govulncheck actionlint
#                                          # (default: all of them)
#
# Used by .githooks/pre-push (the local gate / `make check`), `make tools` and
# .github/workflows/*.yml, so local runs and CI scan with the same versions.
# Versions come ONLY from tools/versions.env; to bump a tool, edit that file.
#
# Each tool is built once with the pinned Go toolchain into a versioned cache:
#   ${XDG_CACHE_HOME:-$HOME/.cache}/spd-coop-tools/<name>-<version>-go<GO_VERSION>/<name>
# A cached binary is reused without any network access. A failed install is
# fatal: there is no fallback to @latest and no "skip the scan" path.
#
# stdout: one KEY=<absolute path> line per tool (GITLEAKS_BIN=..., ...).
# stderr: progress messages.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSIONS_FILE="${ROOT}/tools/versions.env"
[ -f "$VERSIONS_FILE" ] || { echo "ERROR: $VERSIONS_FILE not found" >&2; exit 1; }
# shellcheck source=tools/versions.env
. "$VERSIONS_FILE"

for var in GO_VERSION GOVULNCHECK_VERSION GITLEAKS_VERSION ACTIONLINT_VERSION; do
	[ -n "${!var:-}" ] || { echo "ERROR: $var is not set in $VERSIONS_FILE" >&2; exit 1; }
done

command -v go >/dev/null 2>&1 || {
	echo "ERROR: go is not on PATH; it is needed to build the gate tools (any Go >= 1.21 works, GOTOOLCHAIN fetches go${GO_VERSION})" >&2
	exit 1
}

CACHE_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/spd-coop-tools"
mkdir -p "$CACHE_ROOT"

# Builds land in a temp dir and are moved into the cache only on success, so
# an interrupted or failed build never leaves a half-written cached binary.
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# Build outside any module of this repo, with the pinned toolchain.
export GOWORK=off
export GOTOOLCHAIN="go${GO_VERSION}"

# go_install <name> <version> <package@version> [go install flags...]
go_install() {
	local name="$1" version="$2" pkg="$3"
	shift 3
	local dir="${CACHE_ROOT}/${name}-${version}-go${GO_VERSION}"
	local bin="${dir}/${name}"
	if [ ! -x "$bin" ]; then
		echo "install-tools: building ${name} ${version} -> $bin" >&2
		(cd "$WORK_DIR" && GOBIN="$WORK_DIR/${name}-bin" go install "$@" "$pkg") >&2 || {
			echo "ERROR: failed to install ${name} ${version}" >&2
			exit 1
		}
		mkdir -p "$dir"
		mv "$WORK_DIR/${name}-bin/${name}" "$bin"
	fi
	printf '%s_BIN=%s\n' "$(tr '[:lower:]' '[:upper:]' <<<"$name")" "$bin"
}

install_one() {
	case "$1" in
	gitleaks)
		# -X sets the version string `gitleaks version` prints (release builds
		# set it the same way).
		go_install gitleaks "$GITLEAKS_VERSION" "github.com/zricethezav/gitleaks/v8@${GITLEAKS_VERSION}" \
			-ldflags "-X github.com/zricethezav/gitleaks/v8/version.Version=${GITLEAKS_VERSION}"
		;;
	govulncheck)
		go_install govulncheck "$GOVULNCHECK_VERSION" "golang.org/x/vuln/cmd/govulncheck@${GOVULNCHECK_VERSION}"
		;;
	actionlint)
		go_install actionlint "$ACTIONLINT_VERSION" "github.com/rhysd/actionlint/cmd/actionlint@${ACTIONLINT_VERSION}" \
			-ldflags "-X github.com/rhysd/actionlint.version=${ACTIONLINT_VERSION}"
		;;
	*)
		echo "ERROR: unknown tool '$1' (known: gitleaks govulncheck actionlint)" >&2
		exit 2
		;;
	esac
}

if [ $# -eq 0 ]; then
	set -- gitleaks govulncheck actionlint
fi
for tool in "$@"; do
	install_one "$tool"
done
