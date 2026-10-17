# Workflow — branches, commits, worklogs, gate, CI

> Adapted from the SaaS prototype's WF-01/WF-03 system and scaled down for a 2–3 person game project.
> Status: **documented, not yet implemented** (scripts/hooks/CI land in task `WF-01`, see §7).

## 1. Branches

| Branch | Purpose | Who writes |
|---|---|---|
| `master` | **Pure mirror of upstream SPD** (00-Evan). Never commit here. | `git pull upstream master` only |
| `dev` | **Integration branch.** Every finished task is merged here. | owner only |
| `main` | **Release branch.** Builds for friends (APK/TestFlight) come from here. Fast-forward from `dev` when CI is green. | owner only |
| `agent/<TASK-ID>-<slug>` | One task = one branch = one worktree. | the agent/dev doing the task |

- Worktrees live **outside** the repo: `../spd-wt/<TASK-ID>/`.
- Agents **never push, never merge, never rebase `dev`/`main`, never `--no-verify`.** The owner reviews, merges into `dev`, and pushes.
- Upstream updates: task `UPS-<version>` merges `master` (after pulling upstream) into `dev` on its own branch. It's reviewed like any task, and the determinism goldens must be regenerated deliberately (§6).

## 2. Task IDs

Every piece of work has an ID: `<AREA>-<NN>[letter]`, e.g. `NET-01`, `MEC-03b`.

| Area | Scope |
|---|---|
| `WF` | workflow, hooks, CI, scripts |
| `DOC` | docs only |
| `BRD` | branding/fork hygiene (name, icons, credits, services) |
| `UPS` | upstream merges |
| `NET` | client networking (`coop/net`) |
| `SRV` | Go server |
| `PRO` | protocol schema/contract fixtures (both sides) |
| `GHO` | ghost & presence |
| `MEC` | missions & mechanics (The Cell, downed, Recall) |
| `ROOM` | co-op rooms / level gen |
| `ITM` | co-op items (Recall, Super Sync, keys) |
| `SYN` | Super Sync projection & Remote View |
| `UI` | lobby, HUD, ping wheel |
| `TST` | tests & test infra |
| `REL` | builds & distribution (APK, TestFlight) |
| `OPS` | deploy repo, VPS, runner, GHCR, GitHub App |

## 3. Commits

- **Subject:** `<TASK-ID>: <imperative summary>`, e.g. `MEC-01: add Cell mission state machine on server`.
- **Worklog commit:** `<TASK-ID>: worklog`.
- **Merge into dev:** `Merge <TASK-ID>: <one-line summary>`.
- **Docs fold by owner:** `docs: fold <TASK-ID> worklog`.
- **One commit per logical change.** Don't batch unrelated changes, and don't wait until the whole task is done.
- A side-effect change outside the feature (e.g. a shared UI widget or an upstream hook) is called out in its own paragraph in the commit message.
- Commit with `make commit FILES="..." MSG="..."`. `FILES` is required (no `git add -A`, so untracked files and `.env` never get swept in), and it **refuses unless `make check` passed on exactly this tree** (pass stamp).
- No full 40-character SHAs in files on agent branches (rebase changes them). Short SHAs go in chat reports only. In shared docs, the owner cites real SHAs after the merge and verifies them with `git cat-file -e`.
- Never `--amend` a commit whose hash is cited anywhere. Make a new commit instead.
- AI attribution trailers (e.g. `Co-Authored-By:`) are allowed via `MSG_FILE`.

## 4. Worklogs & shared docs

- On an agent branch, **don't edit** shared state docs: `AI_CONTEXT.md`, `docs/coop/decisions.md`, `docs/changelog/**`.
- Instead write **one file per task**: `docs/worklog/<TASK-ID>.md` (template in `docs/worklog/README.md`).
- The owner folds the worklogs into the shared docs after merging.
- Decisions an agent needs are written as **Open questions** in the worklog, and the agent **stops**. No guessing.

## 5. Task prompt contract (what every task must state)

1. Task ID (it names the branch and the worklog).
2. **Lane:** the allowed paths. Editing outside the lane means stop and ask.
3. Whether any **hot path** is granted. Hot paths: `build.gradle` files, `settings.gradle`, `gradle/**`, `.github/**`, `Makefile`, `.githooks/**`, `scripts/**`, `server/go.mod`/`go.sum`, `core/src/main/java/.../coop/net/Protocol.java`, `server/internal/protocol/**`, `docs/coop/protocol/fixtures/**`, and **any upstream (non-`coop`) Java file**. At most one running task may own each hot path.
4. Run `make check` before every commit. Record the work in the worklog. Stop on: a needed decision, an out-of-lane change, a failing gate you can't fix in lane, or a conflict with `dev`.

### Final report (every task)
- Literal `git log --oneline dev..HEAD`
- Literal `git diff --stat dev...HEAD`
- Each verification command **and its literal output**
- Anything claimed but not verified, and why
- **No illustrative or reconstructed output.** Paste what was actually captured, or say it wasn't captured.

## 6. Local gate (`make check`) — scoped

`scripts/changed-scope.sh` maps changed paths to stages and prints the scope and the reason:

| Changed paths | Stages |
|---|---|
| `core/**`, `SPD-classes/**`, `desktop/**`, `services/**`, gradle files | `./gradlew :core:compileJava :desktop:compileJava` + `./gradlew test` (unit + determinism goldens) |
| `android/**` | above + `./gradlew :android:assembleDebug` |
| `server/**` | `gofmt -l`, `go vet ./...`, `go test ./...`, `govulncheck` |
| `server/Dockerfile`, `deploy/**` | `docker build` of the server image + `docker compose -f deploy/docker-compose.yml config` and the same for `.dev.yml` |
| protocol (`Protocol.java`, `server/internal/protocol/**`, fixtures) | Java **and** Go contract tests against `docs/coop/protocol/fixtures/*.json` |
| `*.md` | markdown SHA check (no full SHAs on agent branches) |
| always | `gitleaks` on the changed range |

- `make check` → scoped gate, writes the **pass stamp** for the exact tree.
- `make ci-full` → everything (what GitHub CI runs).
- `make commit` → enforces the stamp.
- `make worktree-new TASK=<ID> SLUG=<slug>` → branch + worktree + hooks + push guard.
- `.githooks/pre-push` → blocks pushes from worktrees/`agent/*`, then runs the gate on the pushed range.

### Determinism goldens (game-specific, most important test)
- `TST`: a headless test generates floors 1–25 for a fixed set of seeds and hashes each `Level.map` + item/mob spawn lists.
  1. **Vanilla golden:** with co-op OFF, the hashes must equal the goldens recorded from upstream v4.0.2. This proves single-player stays vanilla.
  2. **Co-op golden:** with co-op ON, role A and role B generate **identical** hashes for everything except role-dependent locked/visible state. This proves both worlds match.
- Goldens are only regenerated by the `regen-goldens` workflow or a `UPS-*` task, never casually.

## 7. GitHub CI (`.github/workflows/`)

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | every push / PR to `dev`, `main` | `java-build-test` (JDK 17, gradle cache, compile + tests + goldens) · `server` (Go: gofmt, vet, test, govulncheck) · `protocol-contract` (Java+Go fixtures) · `secret-scan` (gitleaks on the range) · `docs` (markdown SHA check) |
| `android-apk.yml` | push to `main`, manual | build the debug/release APK and upload it as an artifact (this is how friends get builds) |
| `ios.yml` | manual / tag `v*` only | macOS runner, RoboVM build. TestFlight upload later (needs Apple secrets) |
| `build-and-publish.yml` | push to `main` touching `server/**` or `deploy/**`, manual | build & push the server image to GHCR (`:<sha>`, `:latest`), then **sync `deploy/` into the `<game>-deploy` repo** (`:IMAGE_TAG` → sha, compose validated) via GitHub App token. That push triggers `deploy.yml` on the VPS runner (see architecture §4.3) |
| `regen-goldens.yml` | manual | regenerate determinism goldens on a branch for review |

- `main` is protected: CI must be green, and fast-forward only.
- The repo must be public anyway (GPLv3). GitHub Actions minutes are free for public repos.

## 8. Implementation order for the workflow itself
1. `WF-01`: `Makefile`, `scripts/{commit.sh,gate-stamp.sh,changed-scope.sh,agent-worktree.sh,gitleaks-scan.sh}`, `.githooks/pre-push`, `.gitleaks.toml`, `docs/worklog/README.md`. Port from the SaaS prototype, removing Mongo/Redis/Flutter specifics.
2. `WF-02`: `ci.yml` + `android-apk.yml`.
3. `TST-01`: determinism goldens harness + vanilla goldens from v4.0.2 (before any co-op code lands!).
4. `WF-03`: `ios.yml`, `regen-goldens.yml`. (`build-and-publish.yml` comes with `SRV-00`; the deploy repo + VPS runner come with `OPS-01`.)
