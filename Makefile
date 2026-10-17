# Makefile — workflow entry points (WF-01). Process: docs/coop/WORKFLOW.md.
#
# NOTE: the repo path contains spaces ("CS tools", "game dev"); recipes only
# use paths relative to the repo root.

.PHONY: help ensure-hooks check ci ci-full commit push tools worktree-new worktree-list worktree-remove server-dev

.DEFAULT_GOAL := help

# Push-block message, shared with .githooks/pre-push (Layer B); Layer A is the
# per-worktree remote.origin.pushurl set by scripts/agent-worktree.sh.
BLOCKED_MSG = "BLOCKED: agents never push; the owner merges and pushes from the main worktree"

help:
	@echo "make check          scoped local gate; a pass writes the stamp make commit needs"
	@echo "make ci             same as make check"
	@echo "make ci-full        every stage (what GitHub CI runs; needs an Android SDK)"
	@echo "make commit FILES=\"<paths>\" MSG=\"<TASK-ID>: <summary>\"   (or MSG_FILE=<path>)"
	@echo "make worktree-new TASK=<TASK-ID> SLUG=<slug> [BASE=dev]"
	@echo "make worktree-list | make worktree-remove TASK=<TASK-ID> [FORCE=1]"
	@echo "make push           owner only, main worktree only"
	@echo "make tools          install the pinned gate tools (tools/versions.env)"
	@echo "make server-dev     run the Go server locally in Docker"

ensure-hooks:
	@if [ "$$(git config --get core.hooksPath 2>/dev/null)" != ".githooks" ]; then \
		git config core.hooksPath .githooks; \
		echo "[MAKE] Configured git core.hooksPath to .githooks"; \
	fi

# The gate is .githooks/pre-push run WITHOUT arguments: the agent push guard
# inside it only fires for real git push invocations, so checks run in any
# worktree. GitHub CI (.github/workflows/ci.yml) is the full gate.
check: ensure-hooks
	@./.githooks/pre-push

ci: check

ci-full: ensure-hooks
	@CI_SCOPE=full ./.githooks/pre-push

# No `git add -A`: FILES is required, so untracked files (including .env*)
# are never swept into a commit. scripts/commit.sh reads FILES / MSG /
# MSG_FILE from the environment (make exports command-line variables), so
# quotes and multi-line messages are safe; it refuses without a pass stamp.
commit: ensure-hooks
	@bash scripts/commit.sh

# Refuses from a linked worktree or an agent/* branch; the owner uses it from
# the main worktree. The pre-push hook runs the gate on the pushed range.
push: ensure-hooks
	@PUSH_GITDIR=$$(git rev-parse --git-dir); \
	PUSH_BRANCH=$$(git rev-parse --abbrev-ref HEAD); \
	case "$$PUSH_GITDIR" in */worktrees/*) \
		echo $(BLOCKED_MSG); \
		exit 1 ;; esac; \
	case "$$PUSH_BRANCH" in agent/*) \
		echo $(BLOCKED_MSG); \
		exit 1 ;; esac; \
	LOCAL=$$(git rev-parse HEAD); \
	git push origin "$$PUSH_BRANCH" || exit 1; \
	REMOTE=$$(git ls-remote origin "refs/heads/$$PUSH_BRANCH" | awk '{print $$1}'); \
	if [ "$$LOCAL" = "$$REMOTE" ]; then \
		echo "PUSH_VERIFIED: $$PUSH_BRANCH @ $$(git rev-parse --short HEAD)"; \
	else \
		echo "PUSH_MISMATCH: local=$$LOCAL remote=$$REMOTE"; \
		exit 1; \
	fi

tools:
	@bash scripts/install-tools.sh

worktree-new:
	@if [ -z "$(TASK)" ] || [ -z "$(SLUG)" ]; then \
		echo "Usage: make worktree-new TASK=<TASK-ID> SLUG=<slug> [BASE=<base-branch>]"; \
		exit 1; \
	fi
	@./scripts/agent-worktree.sh new "$(TASK)" "$(SLUG)" $(BASE)

worktree-list:
	@./scripts/agent-worktree.sh list

worktree-remove:
	@if [ -z "$(TASK)" ]; then \
		echo "Usage: make worktree-remove TASK=<TASK-ID> [FORCE=1]"; \
		exit 1; \
	fi
	@./scripts/agent-worktree.sh remove "$(TASK)" $(if $(FORCE),--force,)

# Local server on :8080 (no TLS); see deploy/docker-compose.dev.yml.
server-dev:
	docker compose -f deploy/docker-compose.dev.yml up --build
