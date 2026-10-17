#!/usr/bin/env bash
#
# scripts/gate-stamp.sh — pass stamps for the local gate (WF-01).
#
#   scripts/gate-stamp.sh tree                    # tree hash of the working tree
#   scripts/gate-stamp.sh write <tree> <key>      # record a passing check
#   scripts/gate-stamp.sh covers <tree> <key>     # exit 0 if a stamp for <tree>
#                                                 # covers scope <key>
#
# A passing `make check` / `make ci-full` writes a stamp named after the tree
# it checked: a copy of the real index (so modes staged with
# `git update-index --chmod` count) plus every tracked change and every
# untracked, not ignored file, as on disk. `make commit` (scripts/commit.sh)
# refuses unless a stamp exists for the tree it is about to commit plus the
# untracked files it leaves out, and that stamp's scope covers the commit's
# own scope (scripts/changed-scope.sh --paths). Untracked files that are not
# committed are tolerated (they were present during the check); any other
# uncommitted change makes the trees differ, so the commit is refused.
#
# Stamps live in <git-common-dir>/gate-stamps/<tree>, shared by all
# worktrees. Each write prunes stamps older than 14 days and keeps the newest
# 200.
#
# Scope keys are the SCOPE_KEY strings of scripts/changed-scope.sh: "full",
# "none", or space-separated tokens like "java docs secrets".
set -euo pipefail

STAMP_DIR="$(git rev-parse --path-format=absolute --git-common-dir)/gate-stamps"

worktree_tree() {
	local real_index tmp
	real_index="$(git rev-parse --path-format=absolute --git-path index)"
	tmp="$(mktemp)"
	# shellcheck disable=SC2064 # expand now: the path is fixed
	trap "rm -f '$tmp'" RETURN
	if [ -f "$real_index" ]; then
		cp "$real_index" "$tmp"
	else
		rm -f "$tmp"
	fi
	(cd "$(git rev-parse --show-toplevel)" && GIT_INDEX_FILE="$tmp" git add -A -- . >/dev/null 2>&1)
	GIT_INDEX_FILE="$tmp" git write-tree
}

tokens() { # one token per line, sorted
	tr ' ' '\n' <<<"$1" | sed '/^$/d' | sort -u
}

case "${1:-}" in
tree)
	worktree_tree
	;;
write)
	tree="${2:?usage: gate-stamp.sh write <tree> <key>}"
	key="${3:?usage: gate-stamp.sh write <tree> <key>}"
	mkdir -p "$STAMP_DIR"
	printf 'scope=%s\nhead=%s\ndate=%s\n' "$key" "$(git rev-parse HEAD)" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
		>"$STAMP_DIR/$tree.tmp.$$"
	mv "$STAMP_DIR/$tree.tmp.$$" "$STAMP_DIR/$tree"
	find "$STAMP_DIR" -maxdepth 1 -type f -mtime +14 -delete 2>/dev/null || true
	# shellcheck disable=SC2012 # stamp names are hex tree hashes
	ls -1t "$STAMP_DIR" | tail -n +201 | while read -r old; do rm -f "$STAMP_DIR/$old"; done
	echo "gate stamp written for tree $(cut -c1-12 <<<"$tree") (scope: $key)"
	;;
covers)
	tree="${2:?usage: gate-stamp.sh covers <tree> <key>}"
	need="${3-}"
	stamp="$STAMP_DIR/$tree"
	[ -f "$stamp" ] || exit 1
	have="$(sed -n 's/^scope=//p' "$stamp")"
	[ "$have" != "full" ] || exit 0
	if [ -z "$need" ] || [ "$need" = "none" ]; then
		exit 0
	fi
	[ "$need" != "full" ] || exit 1
	missing="$(comm -23 <(tokens "$need") <(tokens "$have"))"
	[ -z "$missing" ]
	;;
*)
	echo "usage: scripts/gate-stamp.sh tree | write <tree> <key> | covers <tree> <key>" >&2
	exit 2
	;;
esac
