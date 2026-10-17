# Worklog: SRV-01 — lobby, run start, presence relay, resume

## Task and scope
- Task ID: SRV-01 (prompt `docs/prompts/PRO-01_SRV-01.md`, Task 2)
- Lane (allowed paths): `server/**` except `server/internal/protocol/**`, `docs/worklog/SRV-01.md`, `docs/coop/architecture.md` §4–§5
- Hot paths granted: `server/go.mod`, `server/go.sum` (not touched: stdlib + existing deps only). Owner-approved extras: `deploy/.env.example` (`ALLOWED_VERSIONS=`), and `.github/workflows/ci.yml` (the PRO-01 `protocol-contract` job bumped to the node24 actions WF-03 adopted, found while merging `dev`)
- Branch: `agent/SRV-01-lobby`, **stacked on PRO-01** (`agent/PRO-01-protocol-v1`; the owner merges PRO-01 first or both together). Started from `dev` short `8caf8fe`; `dev` (short `814e55a`: WF-03, WF-04, TST-01, NET-01a) was merged in with no conflicts.
- Work split: the code was written and tested on the owner's machine; the docs, the `dev` merge, the CI fix and the non-Docker restart test were done in a cloud session after the code commit was shared on the `wip-srv01` branch.

## Decisions taken
- **Concurrency (architecture §4.2):** one goroutine per room owns all room state (closures sent via `room.do`, no locks on room state, the actor never blocks on the network). Each websocket has a reader and a writer goroutine (buffered channel, 256 frames). The hub's maps (`playerId → session`, `code → room`, `runId → room`) and each session's seq counter are small locked registries.
- **Store:** schema v2 migration from v1: `players` (SHA-256 of a 32-byte random token only, constant-time compare), `runs`, `run_players`. A database newer than the binary is refused.
- **Run start:** exactly once per room, seed and roleSeed from `crypto/rand` (`seed < 26^9`). The run is persisted at `RUN_START`, so it survives a restart; lobby rooms are memory-only (deleted after 10 min empty).
- **`from`:** always written by the server on relayed `GHOST`/`MARK`/`QUICK`; a client-sent `from` is ignored.
- **Resume:** `epoch` per server process; replay of reliable events (`ROOM_STATE`, `RUN_START`, `ALLY_STATUS`) only in the same epoch and while the 256-event room log covers them; `SESSION_REPLACED` (4003) for the older connection.
- **Event log is in memory:** after a restart there is no replay (`complete:false`); the `WELCOME` snapshot is the truth (PROTOCOL.md §7.4).
- **`ALLOWED_VERSIONS`** = comma-separated integer gameVersionCodes; names like `0.1.0-coop` fail `server -check-env`. `deploy/.env.example` now ships `ALLOWED_VERSIONS=` (no gate): the old value would have failed the deploy pre-flight.
- **No `ROOM_LEAVE` in a running room** in v1 (PROTOCOL.md §6); finishing/abandoning a run is a later task.
- **Numbering of messages sent before `WELCOME`** (`REJECT`, early `ERROR`): there is no player yet, so their s2c `seq` counts per connection. The spec doesn't say this; see Open questions.

## Changes per commit (subject only — NO SHAs)
1. `SRV-01: add lobby, run start, presence relay and resume`
2. `SRV-01: merge dev (WF-03, WF-04, TST-01, NET-01a)`
3. `SRV-01: README, env example, architecture §4–§5, protocol job on node24 actions`
4. `SRV-01: worklog`

## Verification
- Owner's machine, before the code commit: `make check` → `== gate: all checks passed (scope: java server docker protocol docs actions shell secrets) ==`, including `ok .../internal/lobby 3.267s`, `ok .../internal/protocol`, docker build + `compose-validate: OK`, and `contract fixtures exercised: 97 (31 valid, 66 invalid)`. Also `go test -race -count=5 ./internal/lobby/` → ok, and `lobbyprobe` against a local server → `PASS: every step matched`.
- A real bug fixed during testing: the rate limiter never closed a client that stayed only slightly over the limit (sustained abuse now closes with 1008 after 5 s).
- Cloud session, after merging `dev`: `gofmt -l .` (empty), `go vet ./...`, `go test -race -count=1 ./...` → `ok` for `config`, `httpapi`, `lobby`, `protocol`, `store`.
- Cloud session, restart survival **without Docker** (binary + SQLite file, port 18081): `lobbyprobe -state` → every step `ok` (same seed `441803953464` on both, `GHOST` both ways with server `from`, B killed → `ALLY_STATUS connected=false`, B resumed `replayed=0 complete=true` → `connected=true`), `PASS: every step matched`, exit 0. Server stopped (SIGTERM) and started on the same DB, then `lobbyprobe -resume` → both `WELCOME resumed=true` into the same run and seed with a new epoch (`complete=false`), `ALLY_STATUS connected=true`, `GHOST` both ways, `PASS: every step matched`, exit 0.
- Token leak check on that server log (38 lines): 0 of the 2 resume tokens found, no 43-char base64url strings at all. (The owner's earlier check found one 43-char match: the `db_path`.)
- `server -check-env`: `ALLOWED_VERSIONS=` → `configuration OK` exit 0; `ALLOWED_VERSIONS=0.1.0-coop` → `ALLOWED_VERSIONS must be comma-separated gameVersionCodes (integers >= 1)` exit 1; `ALLOWED_VERSIONS=923,924` → OK.
- Owner's machine, **Docker flow** (`deploy/docker-compose.dev.yml`, image built from this branch):
  - `lobbyprobe -state /tmp/probe.json`: every step `ok` (`RUN_START runId=0392d78260da3508 seed=2101130091279` on both, `GHOST` both ways with server `from`, B killed → `ALLY_STATUS connected=false`, B resumed `replayed=0 complete=true` → `connected=true`), `PASS: every step matched`.
  - `docker compose ... restart server`, then `lobbyprobe -resume /tmp/probe.json`: both `WELCOME resumed=true run=0392d78260da3508 seed=2101130091279` with a new epoch (`epoch=1c55a6a663df427c`, saved `ceac0cf4a678a7c7`, `complete=false`), `ALLY_STATUS connected=true`, `GHOST` both ways, `PASS: every step matched`. Then `down`.
  - Note: the host Go was 1.26.9 with `GOTOOLCHAIN=local`; the probe needs `GOTOOLCHAIN=go1.27.2 go run ...` (the gate sets that itself).
- After the CI fix every `uses:` in `.github/workflows` and `deploy/deploy.yml` is on the node24 majors.
- Single-player impact: none (server only). Determinism impact: none.

## Unverified
- `make check` for commits 3–4 ran on the owner's machine (results in the final report); the cloud session can't run the gate's `docker` stage (its proxy intercepts TLS inside `docker build`).
- The Java side of the contract tests (NET-01).

## What NET-01 (Java client) must implement from PROTOCOL.md
1. **Envelope (§2):** `v:1`, `t`, c2s `seq` starting at 1 **per connection** with `HELLO` first, optional `ack` = highest s2c `seq` seen in the current epoch; `depth`+`branch` only on `GHOST`/`MARK` (required) and `QUICK` (optional); never send `from`; integers only, never `null` (§1).
2. **Handshake (§5.1, §8):** `HELLO{protocol:1, gameVersionCode: appVersionCode, platform}`; persist `playerId`, `resumeToken` (secret: never log it) and `epoch` from `WELCOME`; on `REJECT{VERSION_MISMATCH}` stop reconnecting (`CoopClient.stop()`) and show "update the app" with `minGameVersionCode`.
3. **Resume (§7):** on every reconnect send `HELLO` with `resumeToken`, the saved `epoch` and envelope `ack`; apply the `WELCOME` `room`/`run` snapshot, then the `replayed` events; if `complete:false` trust only the snapshot and reset the `ack` baseline to the new epoch. On `SESSION_REPLACED`/close 4003 don't fight back (another device took over). On `REJECT{RESUME_FAILED}` drop the token and start fresh.
4. **Lobby (§5.2, §6):** `ROOM_CREATE`, `ROOM_JOIN{code}`, `ROOM_LEAVE` (lobby only), `READY{heroClass, ready}`; render `ROOM_STATE` (players by slot, `connected`, `ready`); handle `ERROR` codes without closing.
5. **Run start:** on `RUN_START` create the `CoopRun` (seed, roleSeed, runId, slot 0 = role A) in a co-op save slot; it can arrive again in a resume snapshot or replay, so it must be idempotent by `runId`.
6. **Presence (§5.3):** send `GHOST` after each hero move resolves, `MARK`, `QUICK`; receive them with the server's `from`; ignore other floors except for the ally compass; best-effort, no retry.
7. **`ALLY_STATUS`:** connected/lastSeen for the ally status chip; `paused` is always false in v1.
8. **Heartbeat (§9):** `PING{t}` every 10 s; treat 30 s without any server message as dead → reconnect with resume. Stay under 30 msg/s (burst 60).
9. **Strictness (§5.5):** ignore unknown s2c fields and unknown s2c types; reject wrong types/ranges of known fields.
10. **Contract tests:** run the Java side of `docs/coop/protocol/fixtures` (the `protocol-contract` CI job documents where it plugs in).
11. **Threading:** all of the above mutates game state only through `CoopClient`'s `MainThread` callbacks (rule 20).

**Interaction with NET-01a found while writing this:** `CoopClient` flushes its outbound queue in `onOpen`, *before* the protocol layer can send `HELLO`. The spec requires `HELLO` to be the first frame with `seq` 1 on every connection, so queued frames from before a reconnect would break the handshake. NET-01 must keep unsent game messages in its own protocol-level queue (stamped with `seq` only after `WELCOME`) and never call `CoopClient.send()` before the socket is open, or `CoopClient` needs a small "handshake first" hook. To be decided in NET-01's plan.

## Follow-ups / out-of-lane findings
- PRO-01 + SRV-01 should be merged together or in order (SRV-01 is stacked on PRO-01).
- Persisting the reliable event log would allow replay across a restart; not needed for v1 (the snapshot covers it).
- `paused` / mobile background (architecture §5) and finishing/abandoning a run are later tasks.

## Open questions
- **Spec line for pre-`WELCOME` numbering** (protocol hot path, not edited here): add to PROTOCOL.md §2.1 "s2c messages sent before `WELCOME` (`REJECT`, `ERROR`) are numbered per connection starting at 1; the per-epoch numbering starts with `WELCOME`." Owner to approve before NET-01 writes the client.
