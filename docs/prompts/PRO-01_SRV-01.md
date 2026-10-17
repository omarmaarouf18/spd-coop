# Prompt: PRO-01 + SRV-01 — protocol v1 and the server lobby (server side of the first playable)

> **How to use:** start an agent with its working directory set to the main repo
> `/home/omarmaarouf/CS tools/Antigravity/game dev/spd-upstream` and paste everything below the line.
> **This can run in parallel with `TST-01`.** The lanes don't overlap: TST-01 owns `core/src/test/**` + `core/build.gradle`, and this prompt owns `server/**`, `docs/coop/protocol/**`, and its granted hot paths. No game Java code is touched here.

---

You are working on **Logic Link PD** (`omarmaarouf18/spd-coop`), an online 2-player co-op mod of Shattered Pixel Dungeon v4.0.2, with a Go server (`server/`) built in `SRV-00`.

**Goal of this prompt:** the **server side of the first playable milestone**. Two clients can connect, pass a version gate, create/join a room, both mark ready, receive the **same run seed**, relay ghost presence to each other, and survive a disconnect + resume. The game client (`NET-01`) and the ghost (`GHO-01`) come right after this, so the protocol you define here is what they'll implement.

## Read first and follow strictly
1. `AGENTS.md` (especially §3.6 Networking)
2. `AI_CONTEXT.md`
3. `docs/coop/WORKFLOW.md`: lanes, hot paths, `make check` / `make commit`, worklog, report format, **literal outputs only**
4. `docs/coop/architecture.md` §3 (protocol sketch), §4 (server), §5 (reliability)
5. `docs/coop/game-design.md` §2–§3 (roles A/B, ghost, ping/quick messages)
6. Existing server code: `server/internal/{config,httpapi,store}`, `server/cmd/{server,wsprobe}`

Two tasks, in order, each ending at a **STOP POINT** (final report → wait for "continue"). Never push or merge.

---

## Task 1 — `PRO-01`: protocol v1 spec, fixtures, Go protocol package, contract tests

`make worktree-new TASK=PRO-01 SLUG=protocol-v1` → `../spd-wt/PRO-01`. Worklog `docs/worklog/PRO-01.md`.

**Lane:** `docs/coop/protocol/**`, `server/internal/protocol/**`, `docs/worklog/PRO-01.md`, `docs/coop/architecture.md` §3 (update it to match the spec and point to it).
**Hot paths granted:** `scripts/changed-scope.sh` + `.githooks/pre-push` (**only** to wire the `protocol` stage, which currently blocks when fixtures exist), `.github/workflows/ci.yml` (**only** to add a `protocol-contract` job).

### 1.1 Spec: `docs/coop/protocol/PROTOCOL.md` (the single source of truth for Java and Go)
Write it precisely enough that the Java client can be implemented from it alone. Cover:
- **Transport:** JSON text frames over websocket `/ws` (`wss://` in prod via Caddy, `ws://localhost:8080/ws` in dev). Max frame 64 KiB (already enforced). UTF-8.
- **Envelope:** `{ "v":1, "t":"TYPE", "seq":N, "ack":M, "from":"<playerId>", "depth":D, "branch":B, "p":{…} }`.
  - Define which fields are required per direction.
  - `from` is **set by the server** when relaying (a client-supplied `from` is ignored/overwritten).
  - `seq`/`ack` semantics: the per-connection outbound counter, and the last-received counter used for resume/replay.
- **Error model:** `ERROR{code, msg, ref_seq?}` with a closed list of codes (e.g. `BAD_MESSAGE`, `UNKNOWN_TYPE`, `VERSION_MISMATCH`, `NOT_IN_ROOM`, `ROOM_NOT_FOUND`, `ROOM_FULL`, `ALREADY_IN_ROOM`, `RATE_LIMITED`, `INVALID_STATE`, `RESUME_FAILED`).
- **v1 message set** (types, payloads, direction, who may send them in which state):
  - **Session:** `HELLO{protocol, gameVersionCode, platform: desktop|android|ios, resumeToken?}` → `WELCOME{playerId, resumeToken, serverTime, resumed: bool, room?: RoomState}` or `REJECT{code, msg, minGameVersionCode?}` (then close). `PING{t}` / `PONG{t, serverTime}`.
  - **Lobby:** `ROOM_CREATE{}` → `ROOM_STATE`. `ROOM_JOIN{code}` → `ROOM_STATE` to both. `ROOM_LEAVE{}`. `READY{heroClass, ready: bool}` → `ROOM_STATE`. `ROOM_STATE{code, players:[{playerId, slot:0|1, heroClass?, ready, connected, platform}], phase: lobby|running}`.
  - **Run start:** when both are ready, the server sends `RUN_START{runId, seed, roleSeed, players:[…]}` to both, and the room becomes `running`. **`seed` must be a valid SPD seed:** `0 ≤ seed < DungeonSeed.TOTAL_SEEDS (5429503678976 = 26^9)`, generated with a CSPRNG.
  - **Presence relay (best-effort, not logged):** `GHOST{pos, anim, hpPct, heroClass, tier}` (needs `depth/branch`), `MARK{cell, kind}` (needs `depth/branch`), `QUICK{phraseId}`. The server validates the shape and relays to the ally with `from` set. It doesn't interpret them otherwise.
  - **Ally status:** `ALLY_STATUS{playerId, connected, paused, lastSeen}`, sent on connect/disconnect/resume.
  - **Reserved, not implemented in v1:** list the §3.4 mission/sync/transfer types as reserved names, so unknown-but-reserved gets `ERROR{UNKNOWN_TYPE}` consistently.
- **State machine:** connection states (`new → hello'd → in_lobby → running`) and which messages are legal in each. Illegal → `ERROR{INVALID_STATE}` (the connection is not closed).
- **Resume:** reconnect with `HELLO{resumeToken}` → `WELCOME{resumed:true, room}`. Then the server **replays the reliable server→client events** the client missed (after its `ack`). Presence isn't replayed. Define which types are "reliable" (lobby/run/ally events).
- **Versioning rules:** `protocol` must equal 1. `gameVersionCode` must be in `ALLOWED_VERSIONS` when that's set. How v2 will be introduced later.
- **Limits:** rate limit per connection (e.g. 30 msg/s, burst 60) → `ERROR{RATE_LIMITED}`, and close on sustained abuse. Heartbeat: the client sends `PING` every 10 s, the server closes after 30 s of silence.

### 1.2 Fixtures: `docs/coop/protocol/fixtures/*.json`
- One file per case: `{ "name", "direction": "c2s"|"s2c", "valid": true|false, "reason"?: "...", "message": { … } }`.
- At least one **valid** fixture per message type, plus **invalid** ones (missing required field, wrong types, `v != 1`, oversized string, GHOST without depth, seed out of range, unknown type).
- These fixtures are the **contract**: Go tests use them now, and the Java client (`NET-01`) must pass the same files.

### 1.3 Go package `server/internal/protocol`
- Typed structs per message, `Decode([]byte) (Envelope, Payload, error)` with strict validation (unknown fields: decide and document), `Encode`, and the error codes as constants.
- Contract tests: every fixture → valid ones decode + re-encode to equal JSON (semantic equality). Invalid ones fail with the documented reason.

### 1.4 Gate + CI
- `protocol` gate stage: run the Go contract tests (and later the Java ones; leave a clearly marked hook). It **no longer blocks** just because fixtures exist.
- `ci.yml`: add a `protocol-contract` job (Go side now; document where the Java side plugs in for NET-01).

**Verify (literal):** `go test ./internal/protocol/...` (count of fixtures exercised), `make check` (scope must include `protocol`), `actionlint`.
**STOP POINT 1.**

---

## Task 2 — `SRV-01`: lobby, run start, presence relay, resume

`make worktree-new TASK=SRV-01 SLUG=lobby BASE=agent/PRO-01-protocol-v1` (stacked on PRO-01. Say so in the worklog; the owner merges PRO-01 first). Worklog `docs/worklog/SRV-01.md`.

**Lane:** `server/**` (except `server/internal/protocol/**`. If the spec needs a change, STOP and ask, because the protocol is a hot path), `docs/worklog/SRV-01.md`, `docs/coop/architecture.md` §4–§5 (only to match the implementation).
**Hot paths granted:** `server/go.mod`, `server/go.sum` (only if a dependency is really needed; prefer the stdlib).

### 2.1 Architecture (architecture §4.2)
- **One goroutine per room owns its state** (actor model). Connections send commands over channels, and there are no locks around room state.
- A hub maps `playerId → connection`, `code → room`, `runId → room`.
- Codes: 6 chars from an unambiguous alphabet (no `0/O/1/I/L`), unique among live rooms.

### 2.2 Persistence (SQLite, schema v2 migration from v1)
- `players(player_id, token_hash, created_at, last_seen)`: **store only a hash of the resume token** (SHA-256 of a 32-byte random token, constant-time compare).
- `runs(run_id, seed, role_seed, created_at, phase)`, `run_players(run_id, player_id, slot, hero_class, platform)`.
- Rooms in the lobby phase can live in memory only. **Running runs must survive a server restart:** after a restart, a client that `HELLO`s with its token is put back into its run (an "ally offline" status until the other one returns).
- Migration test: a v1 database upgrades to v2. A database newer than the binary is still refused.

### 2.3 Behavior
- Version gate per spec. `REJECT` + close.
- Lobby flow per spec. A creator is slot 0 (role A at run start), the joiner is slot 1. `ROOM_LEAVE` in the lobby frees the slot. In `running` there's no leave in v1 (document this).
- `RUN_START` exactly once per room, when both are `ready` with a heroClass set.
- Presence relay only within a `running` room, to the ally, with `from` set by the server. It's dropped silently if the ally is offline (best-effort).
- **Disconnect:** mark the player `connected:false`, and send `ALLY_STATUS` to the ally. Lobby rooms are deleted after 10 min empty. Runs are kept (persisted).
- **Resume:** per spec, including replaying missed reliable events (keep a bounded per-room event log, e.g. the last 256 events).
- Heartbeat + rate limit per spec.
- Structured logs (slog) for connect/hello/room/run/resume/disconnect, with **no tokens in logs**.

### 2.4 A probe the owner can use locally
- `server/cmd/lobbyprobe`: a CLI that simulates **two clients** against a given URL (default `ws://localhost:8080/ws`): hello → create → join → ready ×2 → prints `RUN_START` (both seeds equal) → exchanges a `GHOST` each way → kills client B → shows A got `ALLY_STATUS{connected:false}` → B resumes with its token → shows `ALLY_STATUS{connected:true}`. Exit code 0 only if every step matched.
- Document it in `deploy/` **only if allowed**. Otherwise put the usage in the worklog and in `server/README.md` (create it, it's in the lane).

### 2.5 Tests (Go, `httptest` + real websocket clients)
- The full happy path (two clients) through `RUN_START` (same seed, `0 ≤ seed < 26^9`).
- Version reject. Room not found / full. `INVALID_STATE` cases. Rate limit. Heartbeat timeout (use an injectable clock/short timeouts in tests).
- Presence relay + `from` overwrite (a client can't spoof `from`).
- Resume after disconnect, with replay of missed reliable events.
- **Restart survival:** run started → close the server (same DB) → new server → client resumes into the run.
- The race detector must be clean: `go test -race ./...`.

**Verify (literal):**
- `cd server && gofmt -l . && go vet ./... && go test -race ./...`
- `make check` (server + docker scopes)
- `docker compose -f deploy/docker-compose.dev.yml up -d --build`, then `go run ./server/cmd/lobbyprobe` against it (full output), then `docker compose ... restart server`, then a second probe run that resumes a running run (show it), then `down`.
- If Docker isn't available, mark those as **Unverified**.

**STOP POINT 2.** In the report, list exactly what the Java client (`NET-01`) must implement from `PROTOCOL.md`, and any part of the spec you think should change before the client is written.
