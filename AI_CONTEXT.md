# AI Context — current project state

> **Owner-maintained.** Agents read this first (after `AGENTS.md`) but don't edit it on agent branches. Write `docs/worklog/<TASK-ID>.md` instead.
> Last updated: 2026-10-17 (fold of DOC-01, WF-01, WF-02, SRV-00)

## Project summary
Logic Link PD: a non-commercial online 2-player co-op mod of Shattered Pixel Dungeon v4.0.2, for Android, iOS and Desktop. It uses the Parallel Worlds model with interdependent co-op tasks, and a Go server that is authoritative for shared state.

## Phase
**Setup (foundation).** Repo, workflow, CI and the Dockerized server skeleton are on `dev`. No co-op gameplay code exists yet.
**Testing is local-only for now** (D-017): two desktop clients + `make server-dev` (dev compose). There's no VPS/domain yet.

## Done
- `DOC-01`: repo `omarmaarouf18/spd-coop` (public). `master` = upstream v4.0.2 mirror, `dev` = integration, `main` = releases. `upstream` push disabled. Planning docs committed.
- `WF-01`: local gate (`make check` scoped, pass stamp, `make commit`), `.githooks/pre-push` (agent push guard + gate on pushed range), `make worktree-new`, gitleaks, shellcheck, actionlint, version drift guard. Pinned tools in `tools/versions.env` (JDK 17 for CI, Go 1.27.2, govulncheck v1.8.0, gitleaks v8.30.1, actionlint v1.7.12).
- `WF-02`: `ci.yml` (versions/lint, Java build+test, Go server, Docker+compose, gitleaks, markdown SHA check), `android-apk.yml` (main + manual), `ios.yml` (manual, allowed to fail). The first CI run on `dev` (short `97a6fe2`) was **6/6 green**.
- `SRV-00`: Go server skeleton (`/healthz`, `/ws` stub answering `NOT_IMPLEMENTED`, SQLite store, `-check-env`, `-healthcheck`, graceful shutdown). Distroless non-root image. `deploy/` reference files (prod compose with Caddy + internal network + pinned volumes, dev compose, Caddyfile, `deploy.yml` with pre-flight/health/rollback). `build-and-publish.yml` (GHCR image + sync to the deploy repo, skips without App secrets).
- `OPS-01` part 1 (in progress): private deploy repo `omarmaarouf18/spd-coop-deploy` seeded with `main`. GitHub App, first release to `main`, and GHCR visibility are owner actions.
- Design/architecture/decisions → `docs/coop/` (D-001…D-017).

## Not done yet (in order)
1. `OPS-01` part 1 finish (GitHub App + secrets, first `main` release, GHCR package public). **Part 2 (VPS, domain, runner, first deploy, rollback drills) is deferred** until a VPS/domain exists (D-017).
2. `TST-01` determinism goldens from vanilla v4.0.2 (**before** any co-op code)
3. `BRD-01` app name/package (`build.gradle` step 4b deferred from DOC-01), branding, disable/redirect news & updates
4. `PRO-01` protocol v1 fixtures (+ `protocol-contract` CI job, protocol gate stage) + `SRV-01` server lobby/handshake + `NET-01` client handshake/lobby
5. `GHO-01` ghost presence → first playable milestone (local: 2 desktop clients + dev compose)
6. `MEC-01` The Cell → `MEC-02` Downed/Recall → `SYN-01` Super Sync + Remote View

## Follow-ups
- Local JDK: install a non-EA JDK with `javac` (`sudo dnf install java-25-openjdk-devel`). The gate currently uses OpenJDK 27 EA.
- GitHub settings: set the default branch to `dev` (it is still `main`), and protect `main` (CI green, fast-forward only).
- CI deprecation warnings (reported after the first run). Fix in a small `WF-03`.
- Pin `caddy` to a fixed tag (currently `caddy:2-alpine`) so a rollback restores Caddy too: `OPS-01` part 2.
- `ios.yml`: the RoboVM recipe is unknown and expected to fail (a later `REL` task).

## Open questions (owner)
See `docs/coop/game-design.md` §11. The ones blocking implementation: mandatory gate floors, real death outside missions, app name/package name.

## Standing rules
`AGENTS.md` (code rules) + `docs/coop/WORKFLOW.md` (process). Any new agent instruction file (CODEX.md, .cursorrules, …) must be a short pointer to `AGENTS.md`, never a copy.
