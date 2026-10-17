# Prompt: BOOTSTRAP-01 — repo setup, workflow, CI, Dockerized server skeleton, deploy pipeline

> **How to use:** paste everything below the line into the agent (Claude Code / Antigravity / …), with its working directory set to the repo root:
> `/home/omarmaarouf/CS tools/Antigravity/game dev/spd-upstream`
>
> **Owner: before pasting, fill in the 3 values in "Owner inputs"** (or leave them as `ASK`, and the agent will stop and ask you).

---

You are working on **Logic Link PD**, a non-commercial online 2-player co-op mod of Shattered Pixel Dungeon v4.0.2.
The repo is a fresh clone of upstream at tag `v4.0.2` with planning docs already written (untracked).

## Owner inputs
- `APP_NAME` = `ASK`          (e.g. `Logic Link PD`)
- `APP_PACKAGE` = `ASK`       (e.g. `com.logiclink.pd`; it must not be `com.shatteredpixel.*`)
- `GITHUB_REPO` = `ASK`       (**new** public repo for this fork, e.g. `https://github.com/omarmaarouf18/<game>`. The old `game-patch` repo stays untouched as the v3.3.8 prototype archive)
- `DEPLOY_REPO` = `ASK`       (**new private** deploy-only repo, e.g. `https://github.com/omarmaarouf18/<game>-deploy`. Only the bot writes to it, see D-015)
- `DOMAIN` = `ASK`            (domain/subdomain for the server, e.g. `play.example.com`; can be left `ASK` until Task 5)

## Read first (in this order) and follow them strictly
1. `AGENTS.md`: code rules (licensing, single-player stays vanilla, determinism, minimal upstream diff, IDs, networking)
2. `AI_CONTEXT.md`: project state
3. `docs/coop/WORKFLOW.md`: branches, task IDs, commits, worklogs, gate, CI. **This prompt implements it.**
4. `docs/coop/architecture.md` §4 (server + Docker) and `docs/coop/decisions.md` (D-001…D-014)
5. Reference implementation of the workflow to **port from** (read-only, never modify): `/home/omarmaarouf/CS tools/Antigravity/SaaS prototype`, specifically `Makefile`, `scripts/commit.sh`, `scripts/gate-stamp.sh`, `scripts/changed-scope.sh`, `scripts/agent-worktree.sh`, `scripts/gitleaks-scan.sh`, `.githooks/pre-push`, `.gitleaks.toml`, `.github/workflows/ci.yml`, `docs/WORKFLOW.md`, `docs/CI_CD_AND_HOOKS.md`, `tools/versions.env`, `scripts/install-tools.sh`.
   Port the *mechanics* (pass stamp, scoped gate, push guard, FILES-required commits, worktree helper). **Remove** everything specific to that project (Mongo, Redis, Flutter, microservices matrix, `logic-exploitation` branch name → our `dev`).

## Global rules for this whole prompt
- Work in the **5 tasks below, in order**. Each task ends at a **STOP POINT**: print the final report (format below) and **wait for the owner's "continue"** before starting the next task.
- **Never push** without the owner's explicit "yes, push" at a stop point. Never force-push. Never `--no-verify`. Never touch `master` except to create it from upstream.
- Never edit upstream game code in these tasks except the `build.gradle` values named in Task 1. No co-op gameplay code yet.
- If any instruction conflicts with the docs, or a decision is missing → **stop and ask**. Don't guess.
- Every report shows **literal command output** only. Never write illustrative or reconstructed output. If you didn't capture something, say so.
- Paths contain spaces (`CS tools`, `game dev`). Quote every path in scripts.
- Code, comments, docs and commit messages in **English**. Talk to the owner in **Egyptian Arabic**.

---

## Task 1 — `DOC-01`: repo bootstrap (owner-granted: remotes, branches, root `build.gradle` app values, `.gitignore`)

This task runs **directly** in the main worktree (the gate doesn't exist yet). It's the only exception to the agent-branch rule.

1. Verify the starting state and show literal output: `git status`, `git describe --tags`, `git remote -v`. Expect a clean tree except the untracked docs, at `v4.0.2`.
2. Remotes: rename `origin` (00-Evan) → `upstream`. Add `origin` = `GITHUB_REPO`. Disable pushes to upstream: `git remote set-url --push upstream DISABLED`.
3. Branches: `master` stays exactly at upstream `v4.0.2` (it mirrors upstream; never commit on it). Create `dev` from `master`.
4. On `dev`, commit in **separate logical commits** (subject format `DOC-01: <summary>`):
   a. the docs: `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, `AI_CONTEXT.md`, `docs/coop/**`, `docs/worklog/README.md`, `docs/prompts/**`;
   b. the root `build.gradle` changes `appName = APP_NAME`, `appPackageName = APP_PACKAGE`. Keep `appVersionCode` ≥ 922 (set 922) and `appVersionName = '0.1.0-coop'`. Don't change anything else;
   c. `.gitignore`: add `server/bin/`, `deploy/.env`, `*.db`, `.env*` except `.env.example`, `/spd-wt/`.
5. Create `main` from `dev`.
6. **STOP POINT 1:** report, then ask the owner: "push `master`, `dev`, `main` to origin?" Push only after a yes.

## Task 2 — `WF-01`: local gate, hooks, worktree helper (hot paths granted: `Makefile`, `scripts/**`, `.githooks/**`, `.gitleaks.toml`, `tools/**`)

From here on, use the workflow: create `agent/WF-01-local-gate` in a worktree at `../spd-wt/WF-01` (you may create the worktree by hand for this first task, since the helper doesn't exist yet). Write `docs/worklog/WF-01.md` using the template.

Deliver (ported from the SaaS prototype, adapted):
- `tools/versions.env`: pinned versions: JDK 17, Go (the current stable, same pin style as the SaaS repo), gitleaks, govulncheck.
- `scripts/install-tools.sh`: installs gitleaks/govulncheck into a local tool cache.
- `scripts/changed-scope.sh`: maps changed paths → stages exactly per `WORKFLOW.md` §6 (java / android / server / docker+deploy / protocol / md / always-gitleaks). It prints the scope and the reason. Stages for paths that don't exist yet (server, protocol) must no-op cleanly.
- `scripts/gate-stamp.sh`, `scripts/commit.sh`: the pass stamp over the exact tree. `make commit FILES=... MSG=...|MSG_FILE=...`, FILES required.
- `scripts/agent-worktree.sh`: `make worktree-new TASK=<ID> SLUG=<slug> [BASE=dev]` → branch `agent/<ID>-<slug>`, worktree `../spd-wt/<ID>`, per-worktree push block (`remote.origin.pushurl`), and `core.hooksPath .githooks`. Also `make worktree-list`.
- `.githooks/pre-push`: the agent push guard (worktrees and `agent/*` branches blocked), skipping delete-only pushes, and the gate scoped to the pushed range. It also acts as the `make check` entry point.
- A markdown check: no full 40-hex SHAs in `*.md` on `agent/*` branches.
- `.gitleaks.toml`: default rules, scanning only changed ranges.
- `Makefile` targets: `check`, `ci` (alias), `ci-full`, `commit`, `worktree-new`, `worktree-list`, `push` (refuses in worktrees), `ensure-hooks`, `tools`, `server-dev` (stub until Task 4).
- Update `docs/coop/WORKFLOW.md` only if the implementation had to differ, and say why in the worklog.

Verify, with literal outputs:
- `make check` on the branch (java scope will run the gradle compile; it needs JDK 17)
- `make commit` refusing without a stamp, then succeeding after `make check`
- `git push` from the worktree being **blocked**
- `make worktree-new TASK=TMP-00 SLUG=probe` works; then remove it with `git worktree remove` and `git branch -D agent/TMP-00-probe`

**STOP POINT 2.**

## Task 3 — `WF-02`: GitHub CI (hot path granted: `.github/**`)

Branch `agent/WF-02-ci` via `make worktree-new`. Deliver:
- `.github/workflows/ci.yml` on push/PR to `dev`, `main`, with jobs:
  - `java-build-test`: JDK 17 (temurin), gradle cache, `./gradlew :core:compileJava :desktop:compileJava test`
  - `server`: runs only if `server/` exists: gofmt, vet, test, govulncheck
  - `docker`: runs only if `server/Dockerfile` exists: build the image, `docker compose config` for both compose files
  - `secret-scan`: gitleaks on the pushed range / PR range
  - `docs`: the markdown SHA check
  - Versions read from `tools/versions.env` (single source, with a drift guard like the SaaS repo has).
- `.github/workflows/android-apk.yml`: on push to `main` + manual. It runs `./gradlew :android:assembleDebug` and uploads the APK artifact (`<APP_NAME>-<version>-<sha>.apk`).
- `.github/workflows/ios.yml`: **manual only**, `macos-latest`, attempts the RoboVM build per `docs/getting-started-ios.md`. It's allowed to fail for now, so mark it `continue-on-error` and note it in the worklog. No signing secrets.
- Don't add `build-and-publish.yml` here (Task 4 does).

Verify: `actionlint` (install it via the tools script if needed) on all workflows, with literal output. Note in the worklog that real CI runs only happen after the owner pushes.

**STOP POINT 3.**

## Task 4 — `SRV-00`: Go server skeleton, fully Dockerized (lane: `server/**`, `deploy/**`; hot paths granted: `server/go.mod`, `server/go.sum`, `.github/workflows/build-and-publish.yml`, `Makefile` target `server-dev` only)

Branch `agent/SRV-00-server-docker`. Follow `architecture.md` §4 exactly. **No game protocol yet** (that's `PRO-01`/`SRV-01`). This is infrastructure only.

`server/`:
- `go.mod` (module `github.com/<owner>/<repo>/server`), the Go version from `tools/versions.env`
- `cmd/server/main.go`: config from env (`PORT` default 8080, `DB_PATH` default `/data/coop.db`, `LOG_LEVEL`, `ALLOWED_VERSIONS`). Structured logging (`log/slog`). Graceful shutdown on SIGTERM. Two extra flag modes:
  - `-check-env`: validates the config and exits 0/1 without starting. The deploy pre-flight uses it.
  - `-healthcheck`: GETs `/healthz` and exits 0/1. Compose uses it, because distroless has no shell.
- `internal/httpapi/`: `GET /healthz` → 200 `{"status":"ok","version":...}`. `GET /ws` → websocket upgrade (pick `github.com/coder/websocket` and record the choice as a proposed decision in the worklog). It accepts the connection, logs it, and answers a JSON `{"t":"NOT_IMPLEMENTED"}` to any message, so a client can test connectivity.
- `internal/store/`: open/migrate a **pure-Go** SQLite (`modernc.org/sqlite`) at `DB_PATH` with one `meta` table (schema version). There's no game data yet.
- Tests: health handler test, ws connect test (httptest), store open/migrate test using a temp dir.
- `Dockerfile`: multi-stage. `golang:<pinned>-alpine` builds with `CGO_ENABLED=0 -trimpath -ldflags="-s -w -X main.version=$VERSION"`, then `gcr.io/distroless/static-debian12:nonroot`. `EXPOSE 8080`, `USER nonroot`. The healthcheck is done in compose (distroless has no shell, so add a tiny `-healthcheck` flag mode in the binary that GETs `/healthz`).
- `.dockerignore`

`deploy/` holds the **reference files** synced to `DEPLOY_REPO` (architecture §4.3, D-015). Follow `architecture.md` §4.3 and port the mechanics from `/home/omarmaarouf/CS tools/Antigravity/SaaS prototype/.github/workflows/build-and-publish.yml` and `/home/omarmaarouf/CS tools/Antigravity/saas-core-deploy/.github/workflows/deploy.yml` (read-only references). Drop the mTLS/certs, Mongo and multi-service parts: we have **one** service (server) plus caddy.
- `docker-compose.prod.yml`:
  - `server`: image `ghcr.io/<owner>/<game>-server:IMAGE_TAG` (the **literal placeholder `:IMAGE_TAG`**, which CI replaces with the SHA). No host ports, `read_only: true` + `tmpfs: /tmp`, healthcheck `["/server","-healthcheck"]`, `mem_limit: 256m`, json-file logs 10m×3, `restart: unless-stopped`, `env_file: .env`.
  - `caddy`: `caddy:2-alpine`, ports 80/443, `./Caddyfile`, `mem_limit: 128m`, same logs.
  - **Pinned volume names:** `name: <game>_server_data`, `<game>_caddy_data`, `<game>_caddy_config`. A private network.
- `docker-compose.dev.yml`: `server` built from `../server`, port `8080:8080`, a local volume, no caddy.
- `Caddyfile`: `{$DOMAIN}` → `reverse_proxy server:8080`. The websocket works through it. Only `/ws` and `/healthz` are reachable, everything else returns 404.
- `.env.example`: `DOMAIN`, `LOG_LEVEL`, `ALLOWED_VERSIONS` (no secrets in v1).
- `deploy.yml`: the reference copy of the deploy repo's workflow. `runs-on: [self-hosted, <game>-vps]`. Steps: checkout (`clean: false`, `fetch-depth: 2`) → symlink `.env` to `/home/deploybot/.env` (fail if missing) → `docker compose config --quiet` → **pre-flight** `docker run --rm --env-file .env <server image> -check-env` (abort and keep the old containers on failure) → save the current image tags → `docker compose pull` → `docker compose up -d --remove-orphans` → health: retry `docker compose exec server /server -healthcheck` 12×5 s, plus an external `curl -fsS https://$DOMAIN/healthz` → **rollback** on failure (`git checkout HEAD~1 -- docker-compose.yml`, up, re-verify) → log dump on failure.
- `deploy-repo-README.md`: it becomes the deploy repo's README (what the repo is, the bot-only rule, first-time VPS setup, manual deploy and rollback, logs, backup of the `server_data` volume).

Also:
- `.github/workflows/build-and-publish.yml`: on push to `main` touching `server/**` or `deploy/**`, plus manual.
  - Job 1 builds and pushes `ghcr.io/<owner>/<game>-server:<sha>` and `:latest` (`packages: write`).
  - Job 2 (`needs` job 1) gets a GitHub App token (`actions/create-github-app-token`, secrets `APP_ID`, `APP_PRIVATE_KEY`, repositories `<game>-deploy`), checks out the deploy repo with `persist-credentials: false`, then:
    - copies `docker-compose.prod.yml` → `docker-compose.yml`;
    - replaces `:IMAGE_TAG` → `:<sha>`, and **fails if any `IMAGE_TAG` remains**;
    - copies `Caddyfile`, `.env.example`, `deploy-repo-README.md` → `README.md`, and `deploy.yml` → `.github/workflows/deploy.yml`;
    - validates with a dummy env (`docker compose config --quiet`);
    - commits `deploy: sync and pin server image to <sha>` as `github-actions[bot]`, pushing only if something changed.
  - If the App secrets are missing, job 2 must **skip with a clear notice**, not fail, so CI works before Task 5.
- `Makefile` `server-dev`: `docker compose -f deploy/docker-compose.dev.yml up --build`.

Verify (literal outputs):
- `cd server && gofmt -l . && go vet ./... && go test ./...`
- `docker build -t spd-coop-server:dev server/`
- `docker compose -f deploy/docker-compose.dev.yml up -d --build`, then `curl -s localhost:8080/healthz`, then a websocket connect test (e.g. `websocat` or a tiny Go client in `server/cmd/wsprobe`) showing `NOT_IMPLEMENTED`, then `docker compose ... down`
- `docker compose -f deploy/docker-compose.prod.yml config` (with a dummy `.env` from `.env.example` and `IMAGE_TAG` substituted the same way CI does)
- `docker run --rm -e PORT=abc spd-coop-server:dev -check-env` fails, and a valid env passes
- `actionlint` on `build-and-publish.yml` and on `deploy/deploy.yml`
- `make check` scoped to server + docker

If Docker isn't available on this machine, say so, verify everything else, and list the Docker checks as **Unverified**.

**STOP POINT 4.**

## Task 5 — `OPS-01`: deploy repo, GitHub App, VPS runner (mostly **owner actions**, guided by you)

Many steps here need the owner's GitHub account, secrets, or a shell on the VPS. **You prepare and verify, the owner clicks and pastes.** Hard rules:
- **Never ask the owner to paste a secret, token, private key or password into the chat.** Never print one, echo one, or write one into any file in either repo. Secrets go straight into GitHub Secrets or `/home/deploybot/.env` on the VPS. If a secret appears in chat by mistake, tell the owner to revoke and regenerate it immediately.
- Don't create cloud resources or spend money. Only give instructions for those.

Steps (present them as a numbered checklist for the owner, then verify what you can):
1. **Deploy repo:** the owner creates `DEPLOY_REPO` (private, empty, default branch `main`). You may seed it with an initial commit containing a README, if the owner gives you push access for that one action.
2. **GitHub App** (e.g. `<game>-automation`): permissions **Contents: read & write** and **Workflows: read & write**. Install it on `DEPLOY_REPO` only. The owner adds `APP_ID` and `APP_PRIVATE_KEY` as Actions secrets in `GITHUB_REPO`.
3. **GHCR package visibility:** after the first `build-and-publish` run, the owner sets the `<game>-server` package to **Public** (the source is GPL anyway), so the VPS pulls without credentials.
4. **VPS** (owner's shell; you give exact commands):
   - Docker Engine + compose plugin installed; firewall allows 22/80/443 only.
   - Create a user `deploybot` in the `docker` group, and `/home/deploybot/.env` (mode 600) created from `.env.example` with the real `DOMAIN`.
   - DNS: an A record from `DOMAIN` to the VPS IP.
   - Install a **self-hosted Actions runner** for `DEPLOY_REPO` as `deploybot` with the label `<game>-vps`, running as a service.
5. **First deploy:** trigger `build-and-publish` (manual dispatch) → check that the deploy repo got the pinned compose → check that `deploy.yml` ran on the VPS runner → `curl -fsS https://DOMAIN/healthz` → a websocket probe to `wss://DOMAIN/ws` gets `NOT_IMPLEMENTED`.
6. **Rollback drill:** push a deliberately bad tag to the deploy repo (owner-approved), see the health check fail and the rollback restore the previous tag, then revert.
7. Add the deploy pipeline and the drill result to `docs/worklog/OPS-01.md`, and propose an ADR-style entry for `decisions.md` (the owner folds it in).

**STOP POINT 5.** In the final report, also propose the next prompts in order: `TST-01` (determinism goldens), `BRD-01` (branding/services), `PRO-01` + `SRV-01` + `NET-01` (protocol v1, lobby, client).

---

## Report format (at every stop point)
```
## <TASK-ID> report
- Branch / worktree:
- git log --oneline <base>..HEAD:   <literal>
- git diff --stat <base>...HEAD:    <literal>
- Verification: each command + literal output
- Unverified: <what + why>
- Decisions taken / proposed:
- Open questions for the owner:
```
