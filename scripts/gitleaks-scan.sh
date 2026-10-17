#!/usr/bin/env bash
#
# scripts/gitleaks-scan.sh — secret scan of a change, never of all history (WF-01;
# ported from the SaaS prototype).
#
#   GITLEAKS_BIN=<path> scripts/gitleaks-scan.sh range <from> <to>
#       commits <from>..<to>; <from> may be empty or all zeros (new ref /
#       unknown base): then only the commit <to> itself is scanned.
#   GITLEAKS_BIN=<path> scripts/gitleaks-scan.sh worktree <base>
#       commits <base>..HEAD, plus staged and unstaged changes, plus untracked
#       (not ignored) files.
#
# Always runs with --redact and the repo's .gitleaks.toml (default rules,
# extended; see that file for the allowlists). Exit 0 = clean, 1 = BLOCKED.
#
# Only ranges are scanned on purpose: upstream SPD history (thousands of
# commits we do not own) is not ours to gate, and scanning it on every push
# would be slow. A secret value is never printed: gitleaks redacts it, and
# this script prints only rule, file, line and commit.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
: "${GITLEAKS_BIN:?GITLEAKS_BIN must be set (scripts/install-tools.sh prints it)}"
CONFIG="$ROOT/.gitleaks.toml"
ZERO_SHA_RE='^0*$'

REPORT_DIR="$(mktemp -d)"
trap 'rm -rf "$REPORT_DIR"' EXIT
FOUND=0
N=0

# run_gitleaks <label> <gitleaks args...>: exit 1 from gitleaks = findings.
run_gitleaks() {
	local label="$1" report rc=0
	shift
	N=$((N + 1))
	report="$REPORT_DIR/$N.json"
	"$GITLEAKS_BIN" "$@" --config "$CONFIG" --redact --no-banner --log-level error \
		--exit-code 1 --report-format json --report-path "$report" >/dev/null 2>"$REPORT_DIR/$N.err" || rc=$?
	if [ "$rc" -eq 0 ]; then
		return 0
	fi
	if [ -s "$report" ] && jq -e 'length > 0' "$report" >/dev/null 2>&1; then
		echo "BLOCKED: gitleaks found secrets in $label (values redacted):"
		jq -r '.[] | "  \(.RuleID)  \(.File):\(.StartLine)\(if .Commit != "" then "  commit \(.Commit[0:10])" else "" end)"' "$report"
		FOUND=1
	else
		echo "BLOCKED: gitleaks failed on $label (exit $rc):"
		sed 's/^/  /' "$REPORT_DIR/$N.err"
		FOUND=1
	fi
}

case "${1:-}" in
range)
	from="${2-}"
	to="${3:?usage: gitleaks-scan.sh range <from> <to>}"
	if [[ "$from" =~ $ZERO_SHA_RE ]] || ! git cat-file -e "$from^{commit}" 2>/dev/null; then
		echo "-- gitleaks: no usable base, scanning commit $(git rev-parse --short "$to") only --"
		run_gitleaks "commit $(git rev-parse --short "$to")" git --log-opts="-1 $to" .
	else
		echo "-- gitleaks: scanning $(git rev-parse --short "$from")..$(git rev-parse --short "$to") --"
		if [ -n "$(git rev-list -1 "$from..$to")" ]; then
			run_gitleaks "$(git rev-parse --short "$from")..$(git rev-parse --short "$to")" git --log-opts="$from..$to" .
		fi
	fi
	;;
worktree)
	base="${2:?usage: gitleaks-scan.sh worktree <base>}"
	mb="$(git merge-base HEAD "$base")"
	echo "-- gitleaks: scanning $(git rev-parse --short "$mb")..HEAD + staged + unstaged + untracked --"
	if [ -n "$(git rev-list -1 "$mb..HEAD")" ]; then
		run_gitleaks "commits $(git rev-parse --short "$mb")..HEAD" git --log-opts="$mb..HEAD" .
	fi
	if ! git diff --cached --quiet; then
		run_gitleaks "staged changes" git --pre-commit --staged .
	fi
	if ! git diff --quiet; then
		run_gitleaks "unstaged changes" git --pre-commit .
	fi
	while IFS= read -r -d '' f; do
		[ -f "$f" ] || continue
		run_gitleaks "untracked file $f" dir "$f"
	done < <(git ls-files -z --others --exclude-standard)
	;;
*)
	echo "usage: scripts/gitleaks-scan.sh range <from> <to> | worktree <base>" >&2
	exit 2
	;;
esac

if [ "$FOUND" -eq 0 ]; then
	echo "gitleaks: no secrets found"
fi
exit "$FOUND"
