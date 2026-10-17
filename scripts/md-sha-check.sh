#!/usr/bin/env bash
#
# scripts/md-sha-check.sh — markdown commit-SHA check (WF-01).
#
#   scripts/md-sha-check.sh            # mode from the branch (see below)
#   scripts/md-sha-check.sh --strict   # no full 40-hex SHAs at all
#   scripts/md-sha-check.sh --verify   # every full SHA must exist and be
#                                      # reachable from HEAD
#
# docs/coop/WORKFLOW.md §3: agent branches never cite full SHAs (a rebase
# changes them), so on an agent/* branch any 40-hex string in a tracked or
# untracked (not ignored) *.md file blocks. On other branches (dev, main: the
# owner cites real SHAs after a merge) every full SHA must be a commit that is
# reachable from HEAD, so fabricated or stale SHAs block too.
#
# The branch is HEAD's branch, or GITHUB_HEAD_REF in a pull-request CI run.
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

mode="${1:-auto}"
if [ "$mode" = auto ]; then
	branch="${GITHUB_HEAD_REF:-$(git rev-parse --abbrev-ref HEAD)}"
	case "$branch" in
	agent/*) mode=--strict ;;
	*) mode=--verify ;;
	esac
fi
case "$mode" in
--strict | --verify) ;;
*)
	echo "usage: scripts/md-sha-check.sh [--strict | --verify]" >&2
	exit 2
	;;
esac

failed=0
checked=0
while IFS= read -r -d '' file; do
	[ -f "$file" ] || continue
	checked=$((checked + 1))
	while IFS=: read -r line sha; do
		[ -n "$sha" ] || continue
		if [ "$mode" = --strict ]; then
			echo "BLOCKED: full commit SHA in $file:$line (agent branches cite short SHAs in chat only)"
			failed=1
		elif ! git cat-file -e "$sha^{commit}" 2>/dev/null; then
			echo "BLOCKED: non-existent commit SHA in $file:$line"
			failed=1
		elif ! git merge-base --is-ancestor "$sha" HEAD 2>/dev/null; then
			echo "BLOCKED: SHA in $file:$line is not reachable from HEAD"
			failed=1
		fi
	done < <(grep -noE '(^|[^0-9a-fA-F])[0-9a-f]{40}([^0-9a-fA-F]|$)' "$file" |
		sed -E 's/^([0-9]+):.*([0-9a-f]{40}).*/\1:\2/' || true)
done < <(git ls-files -z --cached --others --exclude-standard -- '*.md')

if [ "$failed" -ne 0 ]; then
	exit 1
fi
echo "md-sha-check: OK ($checked markdown file(s), mode ${mode#--})"
