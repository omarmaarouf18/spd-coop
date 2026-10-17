#!/usr/bin/env bash
#
# scripts/agent-worktree.sh — parallel-agent git worktree helper (WF-01;
# ported from the SaaS prototype).
#
# Worktrees for agent tasks live OUTSIDE the repository, in one sibling folder
# next to the repo directory: ../spd-wt/<TASK-ID>. They must never live inside
# the repo: Gradle, `gofmt -l`, gitleaks and the markdown-SHA scan walk the
# tree from the repo root, and a nested worktree would be scanned twice.
#
# Agents never push. Every worktree created here gets a per-worktree
# remote.origin.pushurl that makes `git push origin` fail (push-block Layer A),
# and .githooks/pre-push refuses any real push made from a linked worktree or
# an agent/* branch (push-block Layer B). The owner merges each agent branch
# into dev and pushes from the main worktree. See docs/coop/WORKFLOW.md.
#
# NOTE: paths in this repo contain spaces ("CS tools", "game dev"); every path
# below is quoted.
set -euo pipefail

# The integration branch agents branch off and the owner merges into.
INTEGRATION_BRANCH="dev"
# One sibling folder holds every agent worktree (the repo dir name itself is
# NOT used on purpose, so the location is stable even if the repo is renamed).
WT_PARENT_DIR_NAME="spd-wt"
# Push-block Layer A value: an invalid remote URL, so `git push origin` from an
# agent worktree fails before any network access.
DISABLED_PUSHURL="DISABLED--agent-worktrees-never-push"

die() {
	printf 'ERROR: %s\n' "$*" >&2
	exit 1
}

usage() {
	cat <<'EOF'
Usage:
  scripts/agent-worktree.sh new <TASK-ID> <slug> [base]
      Create branch agent/<TASK-ID>-<slug> from [base] (default: the local
      integration branch) and a worktree for it at ../spd-wt/<TASK-ID>.
      Fails if the main worktree has uncommitted changes to tracked files
      (long-lived untracked scratch dirs do not block).
  scripts/agent-worktree.sh list
      List worktrees with branch, ahead/behind vs the integration branch,
      and dirty state (tracked + untracked files).
  scripts/agent-worktree.sh remove <TASK-ID> [--force]
      Remove the worktree and delete its branch. Refuses if the branch is not
      merged into the integration branch, unless --force is given.
EOF
}

# Path of the main worktree (first entry of `git worktree list --porcelain`).
main_worktree_path() {
	git worktree list --porcelain | sed -n 's/^worktree //p' | head -n 1
}

valid_task_id() {
	local name_re='^[A-Za-z0-9][A-Za-z0-9._-]*$'
	[[ "$1" =~ $name_re ]]
}

cmd_new() {
	if [ $# -lt 2 ] || [ $# -gt 3 ]; then usage >&2; exit 1; fi
	local task_id="$1" slug="$2" base="${3:-$INTEGRATION_BRANCH}"

	valid_task_id "$task_id" ||
		die "invalid TASK-ID '$task_id' (allowed: letters, digits, '.', '_', '-'; no slashes or spaces)"
	valid_task_id "$slug" ||
		die "invalid slug '$slug' (allowed: letters, digits, '.', '_', '-'; no slashes or spaces)"
	git rev-parse --verify --quiet "${base}^{commit}" >/dev/null ||
		die "base '$base' does not resolve to a commit"

	local main_wt branch wt_root wt_path
	main_wt="$(main_worktree_path)"
	if [ -n "$(git -C "$main_wt" status --porcelain --untracked-files=no)" ]; then
		die "main worktree ($main_wt) has uncommitted changes to tracked files — commit them first (untracked scratch dirs do not block; see docs/coop/WORKFLOW.md)"
	fi

	branch="agent/${task_id}-${slug}"
	if git show-ref --verify --quiet "refs/heads/${branch}"; then
		die "branch '${branch}' already exists"
	fi
	wt_root="$(dirname "$main_wt")/${WT_PARENT_DIR_NAME}"
	wt_path="${wt_root}/${task_id}"
	if [ -e "$wt_path" ]; then
		die "worktree path already exists: $wt_path (remove it first: scripts/agent-worktree.sh remove ${task_id})"
	fi

	# Per-worktree config must be enabled before the first per-worktree setting
	# (idempotent).
	git config extensions.worktreeConfig true
	mkdir -p "$wt_root"
	git worktree add -b "$branch" "$wt_path" "$base"

	# Push-block Layer A: make `git push origin` fail from THIS worktree only.
	git -C "$wt_path" config --worktree remote.origin.pushurl "$DISABLED_PUSHURL"
	# Same hooks as the main worktree (writes the shared local config; same
	# effect as `make ensure-hooks`).
	git -C "$wt_path" config core.hooksPath .githooks

	printf 'worktree: %s\n' "$wt_path"
	printf 'branch:   %s (from %s @ %s)\n' "$branch" "$base" "$(git -C "$wt_path" rev-parse --short HEAD)"
	printf 'push:     DISABLED in this worktree (Layer A) — agents never push; the owner merges and pushes from the main worktree\n'
	printf "next:     cd '%s'\n" "$wt_path"
}

print_list_row() {
	local have_integration="$1" wt_path="$2" branch="$3"
	local dirty ahead behind
	if [ -z "$branch" ]; then
		branch="(detached)"
	fi
	if [ -d "$wt_path" ]; then
		dirty="$(git -C "$wt_path" status --porcelain | wc -l | tr -d ' ')"
	else
		dirty="?"
	fi
	ahead="-"
	behind="-"
	if [ "$have_integration" -eq 1 ] &&
		git show-ref --verify --quiet "refs/heads/${branch}"; then
		# Left = commits only in the integration branch (= behind),
		# right = commits only in the worktree branch (= ahead).
		{ read -r behind ahead; } < <(
			git rev-list --left-right --count \
				"refs/heads/${INTEGRATION_BRANCH}...refs/heads/${branch}"
		)
	fi
	printf '%-40s %-6s %-7s %-6s %s\n' "$branch" "$ahead" "$behind" "$dirty" "$wt_path"
}

cmd_list() {
	[ $# -eq 0 ] || { usage >&2; exit 1; }
	local have_integration=0
	if git show-ref --verify --quiet "refs/heads/${INTEGRATION_BRANCH}"; then
		have_integration=1
	fi

	local wt_path="" branch=""
	printf '%-40s %-6s %-7s %-6s %s\n' 'BRANCH' 'AHEAD' 'BEHIND' 'DIRTY' 'WORKTREE'
	while IFS= read -r line; do
		case "$line" in
			worktree\ *) wt_path="${line#worktree }" ;;
			branch\ refs/heads/*) branch="${line#branch refs/heads/}" ;;
			'')
				if [ -n "$wt_path" ]; then
					print_list_row "$have_integration" "$wt_path" "$branch"
				fi
				wt_path=""
				branch="" ;;
		esac
	done < <(git worktree list --porcelain)
	if [ -n "$wt_path" ]; then
		print_list_row "$have_integration" "$wt_path" "$branch"
	fi
}

cmd_remove() {
	if [ $# -lt 1 ] || [ $# -gt 2 ]; then usage >&2; exit 1; fi
	local task_id="$1" force=0
	if [ $# -eq 2 ]; then
		case "$2" in
			--force) force=1 ;;
			*) usage >&2; exit 1 ;;
		esac
	fi
	valid_task_id "$task_id" ||
		die "invalid TASK-ID '$task_id' (allowed: letters, digits, '.', '_', '-'; no slashes or spaces)"

	local main_wt wt_root wt_path branch
	main_wt="$(main_worktree_path)"
	wt_root="$(dirname "$main_wt")/${WT_PARENT_DIR_NAME}"
	wt_path="${wt_root}/${task_id}"

	# Find the branch checked out at that worktree (empty when detached).
	branch="$(git worktree list --porcelain | awk -v p="$wt_path" '
		$1 == "worktree" { in_wt = ($0 == "worktree " p); next }
		in_wt && $1 == "branch" { sub(/^branch refs\/heads\//, ""); print; exit }')"
	if [ -z "$branch" ]; then
		die "no branch found checked out at $wt_path (no worktree for '${task_id}'? run 'scripts/agent-worktree.sh list')"
	fi

	if [ "$force" -eq 0 ] &&
		! git merge-base --is-ancestor "refs/heads/${branch}" "refs/heads/${INTEGRATION_BRANCH}"; then
		die "branch '${branch}' is not merged into ${INTEGRATION_BRANCH}; refusing to remove (use --force to override)"
	fi

	if [ -d "$wt_path" ]; then
		if [ "$force" -eq 1 ]; then
			git worktree remove --force "$wt_path"
		else
			git worktree remove "$wt_path"
		fi
	else
		# Stale registration: the directory is already gone. Remove only this
		# registration; `git worktree prune` would also unregister every other
		# stale worktree.
		git worktree remove --force "$wt_path"
	fi

	if [ "$force" -eq 1 ]; then
		git branch -D "$branch"
	else
		git branch -d "$branch"
	fi
	printf 'removed worktree %s and branch %s\n' "$wt_path" "$branch"
}

main() {
	[ $# -ge 1 ] || { usage >&2; exit 1; }
	local cmd="$1"
	shift
	case "$cmd" in
		new) cmd_new "$@" ;;
		list) cmd_list "$@" ;;
		remove) cmd_remove "$@" ;;
		-h | --help | help) usage ;;
		*) usage >&2; exit 1 ;;
	esac
}

main "$@"
