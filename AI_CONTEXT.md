# AI Context — current project state

> **Owner-maintained.** Agents read this first (after `AGENTS.md`) but don't edit it on agent branches. Write `docs/worklog/<TASK-ID>.md` instead.
> Last updated: 2026-10-17

## Project summary
Logic Link PD: a non-commercial online 2-player co-op mod of Shattered Pixel Dungeon v4.0.2, for Android, iOS and Desktop. It uses the Parallel Worlds model with interdependent co-op tasks, and a Go server that is authoritative for shared state.

## Phase
**Planning → Setup.** No co-op code exists on v4.0.2 yet.

## Done
- Review of the old v3.3.8 prototype → `docs/coop/legacy-prototype-review.md`
- Fresh clone of upstream v4.0.2 (this repo)
- Design draft → `docs/coop/game-design.md` (The Cell, Downed/Recall, Super Sync player-controlled, co-op rooms)
- Architecture draft → `docs/coop/architecture.md`
- Decisions D-001…D-015 → `docs/coop/decisions.md`
- Workflow/CI design → `docs/coop/WORKFLOW.md`

## Not done yet (in order)
Steps 1–3 and the deploy pipeline (`OPS-01`) are covered by the agent prompt `docs/prompts/BOOTSTRAP-01.md` (5 tasks with stop points).
1. `DOC-01` repo setup: remotes (`upstream` = 00-Evan, `origin` = our fork), branches `master`/`dev`/`main`, first commit of these docs, app name/package
2. `WF-01` local gate + hooks + worktree script; `WF-02` CI + APK workflow
3. `SRV-00` Go server skeleton, fully Dockerized + `build-and-publish.yml` (GHCR image + sync to deploy repo), then `OPS-01` deploy repo + GitHub App + VPS self-hosted runner (D-014, D-015)
4. `TST-01` determinism goldens from vanilla v4.0.2 (**before** any co-op code)
5. `BRD-01` branding (icons, banner, credits addition), disable/redirect news & updates
6. `PRO-01` protocol v1 fixtures + `SRV-01` server lobby/handshake + `NET-01` client handshake/lobby
7. `GHO-01` ghost presence → first playable milestone
8. `MEC-01` The Cell → `MEC-02` Downed/Recall → `SYN-01` Super Sync + Remote View

## Open questions (owner)
See `docs/coop/game-design.md` §11. The ones blocking implementation: mandatory gate floors, real death outside missions, app name/package name.

## Standing rules
`AGENTS.md` (code rules) + `docs/coop/WORKFLOW.md` (process). Any new agent instruction file (CODEX.md, .cursorrules, …) must be a short pointer to `AGENTS.md`, never a copy.
