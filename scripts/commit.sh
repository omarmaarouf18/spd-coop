#!/usr/bin/env bash
#
# scripts/commit.sh — implementation of `make commit` (WF-01; ported from the
# SaaS prototype).
#
#   make commit FILES="<paths>" MSG="<message>"
#   make commit FILES="<paths>" MSG_FILE=<path>     # multi-line messages
#
# Inputs come from the environment (make exports command-line variables), never
# from make text substitution, so quotes, backquotes and newlines in MSG are safe.
#
# - FILES is required: no `git add -A`, so untracked files (including .env*)
#   are never swept into a commit. Paths are whitespace-separated (paths that
#   contain spaces are not supported).
# - Exactly one of MSG / MSG_FILE is required.
# - A path in FILES may be a staged deletion (already `git rm`ed, or the old
#   side of a `git mv`). A path that exists nowhere — not on disk, not in the
#   index, not in HEAD — is an error.
# - ONLY the FILES paths are committed, as staged (content and mode). Other
#   paths that were already staged stay staged and uncommitted; a warning
#   lists them.
# - The subject (first line) must follow docs/coop/WORKFLOW.md §3:
#   `<TASK-ID>: <summary>`, `Merge <TASK-ID>: <summary>` or `docs: <summary>`.
# - Pass stamp: refuses unless a passing `make check` (or `make ci` /
#   `make ci-full`) stamped exactly the tree being committed — plus the
#   untracked files left out of FILES, which were present during the check —
#   with a scope that covers this commit's changed paths (see
#   scripts/gate-stamp.sh). Nothing is staged when it refuses.
set -euo pipefail

die() {
	printf 'ERROR: %s\n' "$*" >&2
	exit 1
}

USAGE='make commit FILES="<paths>" MSG="<message>"  (or MSG_FILE=<path> instead of MSG)'

[ -n "${FILES:-}" ] || die "FILES is required and must be non-empty: $USAGE"
if [ -n "${MSG:-}" ] && [ -n "${MSG_FILE:-}" ]; then
	die "MSG and MSG_FILE are mutually exclusive; pass exactly one: $USAGE"
fi
if [ -z "${MSG:-}" ] && [ -z "${MSG_FILE:-}" ]; then
	die "MSG or MSG_FILE is required: $USAGE"
fi
if [ -n "${MSG_FILE:-}" ] && [ ! -r "$MSG_FILE" ]; then
	die "MSG_FILE '$MSG_FILE' does not exist or is not readable"
fi

if [ -n "${MSG_FILE:-}" ]; then
	subject="$(sed -n '1p' "$MSG_FILE")"
else
	subject="$(printf '%s\n' "$MSG" | sed -n '1p')"
fi
SUBJECT_RE='^(Merge )?[A-Z]+-[0-9]+[a-z]?: [^ ]|^docs: [^ ]'
if ! grep -qE "$SUBJECT_RE" <<<"$subject"; then
	die "commit subject '$subject' does not match '<TASK-ID>: <summary>' (docs/coop/WORKFLOW.md §3)"
fi

read -r -a paths <<<"$FILES"
[ "${#paths[@]}" -gt 0 ] || die "FILES is required and must be non-empty: $USAGE"

has_head=0
if git rev-parse --verify --quiet HEAD >/dev/null; then
	has_head=1
fi

to_add=()
for p in "${paths[@]}"; do
	if [ -e "$p" ] || [ -L "$p" ]; then
		# On disk: new or modified content.
		to_add+=("$p")
	elif [ -n "$(git ls-files --cached -- "$p")" ]; then
		# In the index but gone from disk: `git add` stages the deletion.
		to_add+=("$p")
	elif [ "$has_head" -eq 1 ] && git cat-file -e "HEAD:$p" 2>/dev/null; then
		# Only in HEAD: an already-staged deletion (git rm / old side of git mv).
		# Nothing to add; it is still part of the commit pathspec below.
		:
	else
		die "path '$p' does not exist on disk, in the index, or in HEAD (typo?)"
	fi
done

# Pass stamp, verified BEFORE touching the real index: build the exact
# tree this commit will produce in a scratch copy of the index (the same
# steps as below), then look for a passing stamp.
stamp_tmp="$(mktemp -d)"
tmp_index="$(mktemp)"
trap 'rm -rf "$stamp_tmp"; rm -f "$tmp_index"' EXIT
real_index="$(git rev-parse --path-format=absolute --git-path index)"
if [ -f "$real_index" ]; then
	cp "$real_index" "$stamp_tmp/index"
fi
if [ "${#to_add[@]}" -gt 0 ]; then
	GIT_INDEX_FILE="$stamp_tmp/index" git add -- "${to_add[@]}"
fi
if [ "$has_head" -eq 1 ]; then
	GIT_INDEX_FILE="$stamp_tmp/commit" git read-tree HEAD
else
	GIT_INDEX_FILE="$stamp_tmp/commit" git read-tree --empty
fi
GIT_INDEX_FILE="$stamp_tmp/commit" git rm -r -q --cached --ignore-unmatch -- "${paths[@]}"
GIT_INDEX_FILE="$stamp_tmp/index" git ls-files --stage -- "${paths[@]}" |
	GIT_INDEX_FILE="$stamp_tmp/commit" git update-index --index-info
commit_tree="$(GIT_INDEX_FILE="$stamp_tmp/commit" git write-tree)"
# The checked tree also held the untracked files that stay out of this commit.
cp "$stamp_tmp/commit" "$stamp_tmp/view"
(cd "$(git rev-parse --show-toplevel)" &&
	GIT_INDEX_FILE="$stamp_tmp/index" git ls-files -z --others --exclude-standard |
	GIT_INDEX_FILE="$stamp_tmp/view" xargs -0 -r git add --)
view_tree="$(GIT_INDEX_FILE="$stamp_tmp/view" git write-tree)"
need_key=full
if [ "$has_head" -eq 1 ]; then
	scope_out="$(git diff --no-renames --name-only HEAD "$commit_tree" |
		bash scripts/changed-scope.sh --paths 2>/dev/null)"
	eval "$scope_out" # SCOPE_KEY=... (shell-quoted)
	need_key="$SCOPE_KEY"
fi
if ! bash scripts/gate-stamp.sh covers "$view_tree" "$need_key"; then
	echo "ERROR: no passing gate stamp for the tree being committed: run make check first" >&2
	echo "  this commit needs scope: $need_key" >&2
	others_changed="$(comm -23 \
		<({ git diff --no-renames --name-only; git diff --no-renames --name-only --cached; } | sort -u) \
		<(printf '%s\n' "${paths[@]}" | sort -u))"
	if [ -n "$others_changed" ]; then
		echo "  tracked changes NOT in FILES (the check ran with them; commit them too, or" >&2
		echo "  'git stash push -- <paths>' them and run make check again):" >&2
		printf '%s\n' "$others_changed" | sed 's/^/    /' >&2
	fi
	exit 1
fi
echo "gate stamp OK for this commit (scope: $need_key)"

if [ "${#to_add[@]}" -gt 0 ]; then
	git add -- "${to_add[@]}"
fi

# Staged paths that are NOT covered by FILES: they stay staged, uncommitted.
all_staged="$(git diff --cached --no-renames --name-only)"
ours_staged="$(git diff --cached --no-renames --name-only -- "${paths[@]}")"
others="$(comm -23 <(printf '%s\n' "$all_staged" | sed '/^$/d' | sort -u) \
	<(printf '%s\n' "$ours_staged" | sed '/^$/d' | sort -u))"
if [ -n "$others" ]; then
	echo "WARNING: these staged paths are NOT in FILES; they stay staged and are NOT committed:" >&2
	printf '%s\n' "$others" | sed 's/^/  /' >&2
fi

# Commit ONLY the FILES paths, exactly as they are staged in the real index
# (content AND mode — with core.fileMode=false, `git update-index --chmod=+x`
# lives only in the index, and `git commit --only` would drop it for new
# files). Build a temporary index = HEAD + the real index entries under FILES,
# and commit that. The real index is left alone, so other staged paths stay
# staged; its FILES entries already match the new HEAD.
if [ "$has_head" -eq 1 ]; then
	GIT_INDEX_FILE="$tmp_index" git read-tree HEAD
else
	GIT_INDEX_FILE="$tmp_index" git read-tree --empty
fi
GIT_INDEX_FILE="$tmp_index" git rm -r -q --cached --ignore-unmatch -- "${paths[@]}"
git ls-files --stage -- "${paths[@]}" | GIT_INDEX_FILE="$tmp_index" git update-index --index-info

[ "$(GIT_INDEX_FILE="$tmp_index" git write-tree)" = "$commit_tree" ] ||
	die "internal: the tree to commit differs from the stamped tree (index changed meanwhile?)"

if [ -n "${MSG_FILE:-}" ]; then
	GIT_INDEX_FILE="$tmp_index" git commit -F "$MSG_FILE"
else
	GIT_INDEX_FILE="$tmp_index" git commit -m "$MSG"
fi
