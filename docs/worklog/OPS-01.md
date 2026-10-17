# Worklog: OPS-01 — deploy repo, GitHub App, VPS runner

## Task and scope
- Task ID: OPS-01
- Lane (allowed paths): `docs/worklog/OPS-01.md` (everything else is owner actions on GitHub / the VPS)
- Hot paths granted: none
- Branch: agent/OPS-01-deploy-pipeline, from dev

## Pipeline (as built in SRV-00)
```
spd-coop (public)  push to main touching server/** or deploy/**, or manual dispatch
  └─ build-and-publish.yml
       image             → ghcr.io/omarmaarouf18/spd-coop-server:<sha> + :latest
       sync-deploy-repo  → GitHub App token → spd-coop-deploy:
                           docker-compose.yml (:IMAGE_TAG → :<sha>), Caddyfile, .env.example,
                           .gitignore, README.md, .github/workflows/deploy.yml
                           → compose validated → commit "deploy: sync and pin server image to <sha>"
spd-coop-deploy (private, bot-only)  push to main
  └─ deploy.yml on [self-hosted, spd-coop-vps] (user deploybot on the VPS)
       .env → /home/deploybot/.env · compose config · pre-flight -check-env
       · save tags · pull · up -d · health (internal + https://DOMAIN/healthz)
       · rollback to HEAD~1 compose on health failure · log dump on failure
```

## Owner checklist (secrets never go into chat or files)
1. Deploy repo `omarmaarouf18/spd-coop-deploy`: private, default branch `main`, seeded with a README (the sync checks out `ref: main`, so the branch must exist).
2. GitHub App `spd-coop-automation`: repository permissions Contents read & write and Workflows read & write (Metadata read is automatic), webhook off, installed on `spd-coop-deploy` only. Then add the Actions secrets `APP_ID` and `APP_PRIVATE_KEY` to `omarmaarouf18/spd-coop`, from your own terminal (`gh secret set ...`). Delete the downloaded `.pem` afterwards.
3. After the first `build-and-publish` run: set the `spd-coop-server` package to Public.
4. VPS: Docker Engine + compose plugin, firewall 22/80/443 only, user `deploybot` in group `docker`, `/home/deploybot/.env` (mode 600) from `.env.example` with the real `DOMAIN`, DNS A record, self-hosted runner for `spd-coop-deploy` as `deploybot` with label `spd-coop-vps`, run as a service.
5. First deploy: fast-forward `main` to `dev` and push (or dispatch `build-and-publish`). Then check: the deploy repo has the pinned compose, deploy.yml ran on the VPS runner, `curl -fsS https://DOMAIN/healthz`, and `go run ./cmd/wsprobe -url wss://DOMAIN/ws` gets `NOT_IMPLEMENTED`.
6. Rollback drill (owner-approved hand commit to the deploy repo): see "Rollback drill" below.

## Rollback drill (plan)
A bad tag that does not exist fails the **pre-flight** (the pull fails) and never reaches the health check, so it tests the pre-flight, not the rollback. To exercise the rollback, the drill commit must pass the pre-flight and fail health:
- **Drill A (pre-flight):** change the server image tag to `:does-not-exist`. Expected: the pre-flight fails, the running containers are untouched, the site stays up.
- **Drill B (rollback):** add `PORT: "9999"` under the server's `environment`. The pre-flight passes (it only reads `.env`), the internal healthcheck passes (it also reads PORT), but Caddy proxies to `server:8080`, so `https://DOMAIN/healthz` returns 502. Expected: the health step fails, the rollback restores `HEAD~1`'s compose, re-verification passes.
- After each drill: `git revert` the drill commit in the deploy repo (or re-dispatch `build-and-publish`), so the bot-synced state is back.

## Progress (2026-10-17)
| Step | State |
|---|---|
| 1. Deploy repo | DONE: created by the agent with `gh repo create --private` (owner-approved), `main` seeded with the README (one push) |
| 2. GitHub App + secrets | DONE (owner): App `spd-coop-automation`, Contents + Workflows read & write, installed on `spd-coop-deploy` only; `APP_ID` and `APP_PRIVATE_KEY` set in `spd-coop` (names verified with `gh secret list`, values never seen) |
| 3. GHCR package public | DONE: already public (inherited); an anonymous `docker pull` of the published image works |
| 4. VPS | DEFERRED (owner decision D-017): no VPS and no DOMAIN yet |
| 5. First deploy | Main-repo half DONE: `build-and-publish` attempt 1 skipped the sync with the notice (no secrets yet). After the secrets were set, the owner re-ran it (attempt 2): image + sync green, and the deploy repo got the bot commit `deploy: sync and pin server image to <sha>`. The VPS half is DEFERRED: the deploy repo's `Deploy` run is **queued** (no runner with label `spd-coop-vps` exists yet) |
| 6. Rollback drill | DEFERRED with the VPS (D-017) |

## Decisions taken
- Deploy repo created by the agent (owner chose this), seeded with `deploy/deploy-repo-README.md` as README, so `main` exists for the sync checkout.
- `main` was fast-forwarded to `dev` and pushed by the agent from the main worktree, on the owner's explicit "yes, push main". The pre-push gate ran on the pushed range and passed.
- Rollback drill split into a pre-flight drill and a health/rollback drill (see above), because a non-existent tag never reaches the rollback.

## Verification
- First CI on dev: all 6 jobs green.
- CI on main: all 6 jobs green.
- Android APK on main: green, artifact `Shattered-Pixel-Dungeon-4.0.2-<sha7>.apk`.
- Build and publish on main: image job green. The sync job succeeded by skipping, with the notice "APP_ID / APP_PRIVATE_KEY are not set. The image was published, but spd-coop-deploy was not updated".
- Anonymous pull of `ghcr.io/omarmaarouf18/spd-coop-server:<sha>`: OK. `-check-env` on it prints `version=<sha>` (the build arg works).
- build-and-publish attempt 2: `Build and push the server image: success`, `Sync the deploy repo: success`.
- Deploy repo after the sync: 2 commits (the README seed by the owner account, then the `github-actions[bot]` sync commit). Files on main: `.env.example`, `.github/workflows/deploy.yml`, `.gitignore`, `Caddyfile`, `README.md`, `docker-compose.yml`. The server image is pinned to the main commit's full sha; no `IMAGE_TAG` is left.
- The synced files are byte-identical to the main repo's references (`cmp`): Caddyfile, .env.example, .gitignore ← deploy-repo.gitignore, README.md ← deploy-repo-README.md, deploy.yml.
- Literal outputs are in the chat report.

## Unverified
- The App's Workflows permission is proven by the sync push (it wrote `.github/workflows/deploy.yml`). Its exact permission set and installation scope were not inspected directly (that needs the App's own JWT).
- Everything on the VPS: deploy.yml, Caddy ACME, external health, wss probe, both drills.

## Follow-ups / out-of-lane findings
- CI annotations: `actions/checkout@v4`, `setup-go@v5`, `setup-java@v4` run on deprecated Node 20, and setup-java v4 is deprecated. Bump the actions (a WF task). `ubuntu-latest` moves to Ubuntu 26 from 2026-10-19: watch the first runs after that.

- The deploy repo's queued `Deploy` run will sit until GitHub drops it (no runner). It is harmless, but the owner can cancel it.

## Proposed ADR (for decisions.md, the owner folds it in)
**D-0xx — Deploy pipeline: GHCR image + bot-synced deploy repo + VPS runner (implements D-014/D-015)**
- Status: Proposed. The main-repo half was verified 2026-10-17; the VPS half is deferred (D-017).
- Decision: `build-and-publish.yml` (push to main on `server/**`/`deploy/**`, or manual) builds `ghcr.io/omarmaarouf18/spd-coop-server:<sha>` + `:latest` (public package). The GitHub App `spd-coop-automation` (Contents + Workflows write, installed on `spd-coop-deploy` only, short-lived installation tokens, `persist-credentials: false`) then syncs `deploy/` into the private, bot-only `spd-coop-deploy` with `:IMAGE_TAG` pinned to `<sha>`. The deploy repo's `deploy.yml` runs on a self-hosted runner (`spd-coop-vps`, user `deploybot`) with these steps: `.env` symlink, pre-flight `-check-env` (abort before touching containers), pull/up, internal + external health, automatic rollback to `HEAD~1`, log dump.
- Consequences: no PAT and no build on the VPS. A missing App secret skips the sync with a notice instead of failing CI. A non-existent image tag is caught by the pre-flight, not by the rollback, so the rollback drill uses a config that passes the pre-flight but fails health.

## Open questions
- Part 2 (VPS) waits for the VPS, the DOMAIN and the VPS OS (D-017).
