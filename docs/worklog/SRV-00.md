# Worklog: SRV-00 — Go server skeleton, fully Dockerized

## Task and scope
- Task ID: SRV-00
- Lane (allowed paths): `server/**`, `deploy/**`, `docs/worklog/SRV-00.md`
- Hot paths granted: `server/go.mod`, `server/go.sum`, `.github/workflows/build-and-publish.yml`, `Makefile` target `server-dev` only; during the task the owner also granted `.github/actionlint.yaml` (see Decisions)
- Branch: agent/SRV-00-server-docker, from dev (it does not depend on WF-02)

## Decisions taken
- **Proposed decision (for decisions.md): websocket library `github.com/coder/websocket` v1.8.15.** It is context-based, actively maintained, with no dependencies, has a read limit and close-status helpers, and is what architecture §4.2 listed first. `gorilla/websocket` was the alternative.
- SQLite: `modernc.org/sqlite` v1.60.1 (pure Go, so the binary is static for distroless), one connection, WAL, busy_timeout 5 s. The `meta` table holds `schema_version` = 1. A database whose schema is newer than the binary is refused.
- Module `github.com/omarmaarouf18/spd-coop/server`, `go 1.27.2` (matches GO_VERSION; no toolchain line).
- Config: `internal/config`. All errors are reported at once. `ALLOWED_VERSIONS` is optional (empty = no gate; the handshake is SRV-01).
- `/ws`: no Origin restriction beyond the library default (game clients send no Origin). 64 KiB read limit. Every message gets `{"t":"NOT_IMPLEMENTED"}`. Websockets are closed with StatusGoingAway on SIGTERM, because `http.Server.Shutdown` does not close hijacked connections.
- `cmd/wsprobe`: a connectivity probe (also used by OPS-01 for `wss://DOMAIN/ws`). `-insecure` is opt-in, only for Caddy's local CA.
- Dockerfile: `/data` is created in the image owned by nonroot (65532), so a fresh named volume inherits that ownership.
- Prod compose: the server sits only on an `internal: true` network (`backend`), so it has no egress at all. Caddy is on `backend` + `edge`. `DOMAIN` is passed to caddy via `${DOMAIN:?}` interpolation from `.env`.
- Added `deploy/deploy-repo.gitignore` → the deploy repo's `.gitignore` (`.env`), so a validation `.env` or the VPS symlink can never be committed. This file is not in the BOOTSTRAP-01 list.
- `deploy.yml`: the health step writes its `verify` function to `$RUNNER_TEMP`, so the rollback step re-verifies with exactly the same checks. The external check retries 12×5 s as well, because the first deploy has to wait for Caddy's certificate.
- `build-and-publish.yml`: secrets cannot be used in an `if`, so a first step checks `APP_ID`/`APP_PRIVATE_KEY` through env and the later steps are gated on its output (notice, not failure). The push uses the App token explicitly (`persist-credentials: false`).
- **Owner-granted hot path:** `.github/actionlint.yaml` declares the self-hosted label `spd-coop-vps`. Without it, actionlint (local gate and CI) fails on `deploy/deploy.yml` with `label "spd-coop-vps" is unknown`.

## Changes per commit (subject only — NO SHAs)
1. `SRV-00: add build-and-publish workflow, server-dev target and runner label config`. This comes first because the pass stamp refuses a commit while a tracked file (`Makefile`) has an uncommitted change.
2. `SRV-00: add Go server skeleton with health, websocket stub and SQLite store`
3. `SRV-00: add server Dockerfile and deploy reference files`
4. `SRV-00: worklog`

## Verification
- Literal outputs are in the chat report: gofmt/vet/test, docker build, dev compose up + healthz + wsprobe + down, prod compose config (CI-style substitution), `-check-env` fail/pass, actionlint, `make check`.
- Extra checks beyond the prompt: a graceful SIGTERM shutdown (exit 0, "server stopped"); `caddy validate`; the full prod stack locally with `DOMAIN=localhost` (`/healthz` 200 through Caddy, `/` and `/admin` 404, http→https 308, `wss://localhost/ws` → `NOT_IMPLEMENTED`, backend network `internal=true`). It was torn down with `down -v` afterwards.
- Single-player impact: none (no game code). Determinism impact: none.

## Unverified
- `build-and-publish.yml` and `deploy.yml` have not run on GitHub / the VPS (OPS-01).
- Real ACME certificate issuance (it needs a public DOMAIN + DNS): only Caddy's local CA was exercised.
- The rollback path of `deploy.yml` (OPS-01 rollback drill).

## Follow-ups / out-of-lane findings
- The deploy repo must have a `main` branch before the first sync (the checkout uses `ref: main`): OPS-01 step 1 seeds it.

## Open questions
- Fold the websocket library choice (`coder/websocket`) into decisions.md as a new D-entry?
