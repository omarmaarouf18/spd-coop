#!/usr/bin/env bash
#
# scripts/changed-scope.sh — which gate stages a change needs (WF-01).
#
#   scripts/changed-scope.sh                # working tree: `make check`
#   scripts/changed-scope.sh --push         # pre-push: ref lines on stdin
#   scripts/changed-scope.sh --paths        # explicit path list on stdin (one per line)
#
# Changed paths:
#   (default)  committed changes since the merge-base of HEAD with SCOPE_BASE
#              (default: dev) + staged + unstaged + untracked (not ignored)
#              files.
#   --push     for every ref line git passes to pre-push
#              ("<local ref> <local sha> <remote ref> <remote sha>"): the commits
#              <remote sha>..<local sha>. A new remote ref (all-zero remote sha)
#              is diffed against its merge-base with origin/dev (or the local
#              dev). Delete lines are ignored.
#
# Stages (scope tokens), see docs/coop/WORKFLOW.md §6:
#   java      ./gradlew :core:compileJava :desktop:compileJava test
#   android   ./gradlew :android:assembleDebug
#   server    gofmt -l, go vet, go test, govulncheck (no-op until server/go.mod exists)
#   docker    docker build of server/ + compose config of deploy/*.yml
#             (no-op until server/Dockerfile / deploy/ compose files exist)
#   protocol  contract tests on docs/coop/protocol/fixtures: Go now, Java
#             from NET-01 (see the hook in .githooks/pre-push)
#   docs      markdown SHA check (scripts/md-sha-check.sh)
#   actions   actionlint on .github/workflows/*.yml and deploy/deploy.yml
#   shell     shellcheck on scripts/*.sh and .githooks/*
#   secrets   gitleaks on the changed range (always, whenever anything changed)
#
# Mapping (first match wins, per path; the scope is the union over all paths):
#   coop/net/Protocol.java, server/internal/protocol/**,
#   docs/coop/protocol/fixtures/**      -> protocol + java + server
#   docs/coop/protocol/*.md (the spec)  -> protocol + docs
#   server/Dockerfile, server/.dockerignore -> server + docker
#   server/**                           -> server
#   deploy/deploy.yml                   -> docker + actions
#   deploy/**                           -> docker
#   *.md anywhere                       -> docs
#   android/**                          -> java + android
#   core/**, SPD-classes/**, desktop/**, services/**, ios/**,
#   build.gradle, settings.gradle, gradle.properties, gradle/**, gradlew,
#   gradlew.bat                         -> java
#   Makefile, scripts/**, .githooks/**, tools/**, .gitleaks.toml,
#   .github/**                          -> GATE scope: every stage except
#                                          android (the gate itself changed;
#                                          android needs an SDK and is built
#                                          by CI and `make ci-full`)
#   LICENSE.txt, .gitignore, .gitattributes, metadata/**, docs/** (non-md)
#                                       -> secrets only
#   anything else                       -> FULL gate (fail safe)
#
# CI_SCOPE=full forces the full gate.
#
# stdout: shell-quoted KEY=value lines (eval them):
#   SCOPE_MODE   full | scoped | none
#   SCOPE_JAVA, SCOPE_ANDROID, SCOPE_SERVER, SCOPE_DOCKER, SCOPE_PROTOCOL,
#   SCOPE_DOCS, SCOPE_ACTIONS, SCOPE_SHELL, SCOPE_SECRETS   0 | 1
#   SCOPE_KEY    canonical description: "full", "none", or the tokens in
#                fixed order, e.g. "java docs secrets"
# stderr: the scope and WHY (one line per reason).
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
cd "$ROOT"

# Canonical token order (also the order of the stages in .githooks/pre-push).
ALL_TOKENS="java android server docker protocol docs actions shell secrets"
GATE_TOKENS="java server docker protocol docs actions shell secrets"
ZERO_SHA_RE='^0+$'
COOP_PROTOCOL_JAVA="core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/coop/net/Protocol.java"

say() { printf 'scope: %s\n' "$*" >&2; }

FULL_REASON=""
GATE_REASON=""
declare -A ON=()
for t in $ALL_TOKENS; do ON[$t]=0; done

on() { # on <token...>
	local t
	for t in "$@"; do ON[$t]=1; done
}

classify() { # classify <path>
	local p="$1"
	case "$p" in
	"$COOP_PROTOCOL_JAVA" | server/internal/protocol/* | docs/coop/protocol/fixtures/*)
		on protocol java server
		;;
	docs/coop/protocol/*.md)
		# The spec: its own docs check plus the contract tests it describes.
		on protocol docs
		;;
	server/Dockerfile | server/.dockerignore)
		on server docker
		;;
	server/*)
		on server
		;;
	deploy/deploy.yml)
		on docker actions
		;;
	deploy/*)
		on docker
		;;
	*.md)
		on docs
		;;
	android/*)
		on java android
		;;
	core/* | SPD-classes/* | desktop/* | services/* | ios/* | \
		build.gradle | settings.gradle | gradle.properties | gradle/* | gradlew | gradlew.bat)
		on java
		;;
	Makefile | scripts/* | .githooks/* | tools/* | .gitleaks.toml | .github/*)
		[ -n "$GATE_REASON" ] || GATE_REASON="$p (gate configuration)"
		;;
	LICENSE.txt | .gitignore | .gitattributes | metadata/* | docs/*)
		: # secrets only
		;;
	*)
		[ -n "$FULL_REASON" ] || FULL_REASON="$p (unmapped path: fail safe)"
		;;
	esac
	on secrets
}

resolve_base_ref() { # the upstream to diff against for new refs / the working tree
	local cand
	for cand in "${SCOPE_BASE:-}" origin/dev dev; do
		[ -n "$cand" ] || continue
		if git rev-parse --verify --quiet "$cand^{commit}" >/dev/null; then
			echo "$cand"
			return 0
		fi
	done
	return 1
}

MODE="${1:-worktree}"
PATHS_FILE="$(mktemp)"
trap 'rm -f "$PATHS_FILE"' EXIT

case "$MODE" in
worktree)
	base_ref="${SCOPE_BASE:-dev}"
	if ! git rev-parse --verify --quiet "$base_ref^{commit}" >/dev/null; then
		FULL_REASON="base '$base_ref' not found"
	else
		mb="$(git merge-base HEAD "$base_ref")"
		say "changed paths = committed since merge-base with $base_ref ($(git rev-parse --short "$mb")) + staged + unstaged + untracked"
		{
			git diff --no-renames --name-only "$mb" HEAD
			git diff --no-renames --name-only --cached
			git diff --no-renames --name-only
			git ls-files --others --exclude-standard
		} >>"$PATHS_FILE"
	fi
	;;
--push)
	while read -r _lref lsha _rref rsha; do
		[ -n "${_lref}${lsha}" ] || continue
		if [[ "$lsha" =~ $ZERO_SHA_RE ]]; then
			continue # delete line: publishes nothing
		fi
		if [[ "$rsha" =~ $ZERO_SHA_RE ]]; then
			if base_ref="$(resolve_base_ref)"; then
				from="$(git merge-base "$lsha" "$base_ref")"
				say "$_rref is a new remote ref: pushed range = merge-base with $base_ref ($(git rev-parse --short "$from"))..$(git rev-parse --short "$lsha")"
			else
				[ -n "$FULL_REASON" ] || FULL_REASON="$_rref is a new remote ref and there is no base to diff against"
				continue
			fi
		elif ! git cat-file -e "$rsha^{commit}" 2>/dev/null; then
			[ -n "$FULL_REASON" ] || FULL_REASON="remote sha of $_rref is not in the local repo (fetch first)"
			continue
		else
			from="$rsha"
			say "pushed range for $_rref: $(git rev-parse --short "$from")..$(git rev-parse --short "$lsha")"
		fi
		git diff --no-renames --name-only "$from" "$lsha" >>"$PATHS_FILE"
	done
	;;
--paths)
	cat >>"$PATHS_FILE"
	;;
*)
	echo "usage: scripts/changed-scope.sh [--push | --paths]" >&2
	exit 2
	;;
esac

if [ "${CI_SCOPE:-}" = "full" ]; then
	FULL_REASON="CI_SCOPE=full"
fi

NPATHS=0
while IFS= read -r p; do
	[ -n "$p" ] || continue
	NPATHS=$((NPATHS + 1))
	classify "$p"
done < <(sort -u "$PATHS_FILE")

if [ -n "$FULL_REASON" ]; then
	MODE_OUT=full
	for t in $ALL_TOKENS; do ON[$t]=1; done
	say "FULL gate: $FULL_REASON"
	KEY=full
elif [ "$NPATHS" -eq 0 ]; then
	MODE_OUT=none
	say "no changed paths: nothing to check"
	KEY=none
else
	MODE_OUT=scoped
	if [ -n "$GATE_REASON" ]; then
		for t in $GATE_TOKENS; do ON[$t]=1; done
		say "GATE scope (every stage except android): $GATE_REASON"
	fi
	KEY=""
	for t in $ALL_TOKENS; do
		[ "${ON[$t]}" -eq 0 ] || KEY="${KEY:+$KEY }$t"
	done
	say "SCOPED gate ($NPATHS changed path(s)): $KEY"
	sort -u "$PATHS_FILE" | sed -n '/^$/!p' | sed -n '1,20s/^/scope:   /p' >&2
	[ "$NPATHS" -le 20 ] || say "  ... and $((NPATHS - 20)) more"
fi

printf 'SCOPE_MODE=%q\n' "$MODE_OUT"
for t in $ALL_TOKENS; do
	printf 'SCOPE_%s=%q\n' "$(tr '[:lower:]' '[:upper:]' <<<"$t")" "${ON[$t]}"
done
printf 'SCOPE_KEY=%q\n' "$KEY"
