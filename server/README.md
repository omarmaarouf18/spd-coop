# server/ — Logic Link PD co-op server (Go)

The server is authoritative for shared co-op state (AGENTS.md §3.6). Protocol v1 is specified in
[`docs/coop/protocol/PROTOCOL.md`](../docs/coop/protocol/PROTOCOL.md); architecture in
[`docs/coop/architecture.md`](../docs/coop/architecture.md) §4–§5.

## What it does (SRV-01)

- `GET /healthz` → `{"status":"ok","version":"…"}`
- `GET /ws` → protocol v1 over websocket: handshake + version gate, rooms (create / join / leave /
  ready), `RUN_START` once per room, presence relay (`GHOST`, `MARK`, `QUICK`) to the ally with
  `from` set by the server, `ALLY_STATUS`, resume with event replay, heartbeat, rate limits.
- Running runs are stored in SQLite and survive a restart; lobby rooms are in memory only.

## Co-op engine (SRV-02)

PROTOCOL.md §5.6–§5.10, all inside the room actor with an injectable clock (`lobby.Options.Clock`):

- **The Cell:** `MISSION_TRIGGER` (ally online on the same floor, no active mission) → `MISSION_START`
  (deadline = `COOP_CELL_CAP` + `COOP_CELL_CAP_PER_REGION` × regionIndex); `GIVE{cell_key}` from the
  rescuer → `TRANSFER`, `MISSION_UPDATE{tasks.key}`, `MISSION_END{success, reward}`. A mission still open
  `COOP_DOWNED_WINDOW` after its deadline with the prisoner standing fails (server backstop).
- **Downed:** `DOWNED` → `ALLY_DOWNED{deadline}`; `USE_RECALL` (`recall` any floor, `healing` same floor)
  → `REVIVE{hpPct:25}` (in place inside a mission or with healing, else `regionStart`). Expiry outside a
  mission → `DOWNED_EXPIRED` (player out, `COOP_DEATH_MODE=player_out`); the Cell prisoner's expiry →
  `MISSION_END{failed, penalty{regionStartDepth, goldPct:25}}` only (D-022).
- **Pause (D-005):** a disconnect freezes the mission and every Downed window (`MISSION_UPDATE{paused}`)
  and interrupts a Super Sync; when both are back, the clocks continue and `ALLY_DOWNED` is re-sent with
  the new deadline. `COOP_PAUSE_GRACE` paused → `MISSION_END{aborted}` (a Downed prisoner is first
  revived in place: `REVIVE{inPlace, 25}`).
- **Items:** `CLAIM` (first wins) / `CLAIMS_REQ`, `GIVE`/`TRANSFER` from the current owner (each
  accepted `GIVE` moves ownership; the same `GIVE` again is `already_transferred`, never applied twice),
  `DROP_SHARE` (registered, relayed to the ally on the same floor). Ownership is persisted.
- **Boss pool, twin windows, door mirroring, Super Sync relay** (10 `SYNC_VIEW` and 10 `SYNC_CMD`
  frames/s per sender, extra frames dropped silently).
- **Reply order:** replies to one connection keep its request order (the reader waits for the actor,
  which queues every reply on the connection's single writer queue).
- **Persistence (schema v3):** `run_items` + `run_state` (one JSON document, saved on every change and
  every 5 s while a clock runs). After a restart every clock comes back paused at its last saved value;
  a resume without replay gets the current state re-sent (`MISSION_START`/`MISSION_UPDATE`,
  `ALLY_DOWNED`, `DOWNED_EXPIRED`, boss pools, the last `MISSION_END`/`SYNC_END`).

## Run it

```bash
# without Docker (from the repo root)
cd server && DB_PATH=./coop.db go run ./cmd/server     # listens on :8080 (the default DB_PATH is /data/coop.db)

# with Docker (dev compose: builds ../server, port 8080, no TLS)
docker compose -f deploy/docker-compose.dev.yml up -d --build
docker compose -f deploy/docker-compose.dev.yml down
```

Flags: `server -check-env` validates the configuration and exits 0/1 (the deploy pre-flight);
`server -healthcheck` probes the local `/healthz` (the compose healthcheck, distroless has no shell).

## Configuration (env vars)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | listen port |
| `DB_PATH` | `/data/coop.db` | SQLite file (the compose files mount a volume at `/data`) |
| `LOG_LEVEL` | `info` | `debug` \| `info` \| `warn` \| `error` |
| `ALLOWED_VERSIONS` | empty | comma-separated **gameVersionCodes** (the client's integer `appVersionCode`, e.g. `923,924`). Empty = no version gate. Version names like `0.1.0-coop` are rejected |
| `COOP_CELL_WAVE_INTERVAL` | `30s` | `MISSION_START.waveIntervalMs` (1s..10m) |
| `COOP_CELL_CAP` | `4m` | Cell hard cap at region 0 (10s..1h) |
| `COOP_CELL_CAP_PER_REGION` | `15s` | added per region index, `(depth-1)/5` (0..10m) |
| `COOP_DOWNED_WINDOW` | `30s` | Downed window; also the Cell's overtime backstop (1s..10m) |
| `COOP_PAUSE_GRACE` | `90s` | a mission paused this long is aborted (1s..1h) |
| `COOP_TWIN_WINDOW` | `2s` | twin door/switch pairing window (100ms..30s) |
| `COOP_BOSS_HP_SCALE` | `1.8` | shared boss pool = round(ht × scale) (0.1..10) |
| `COOP_DEATH_MODE` | `player_out` | the only v1 value |

Durations use Go syntax (`30s`, `1500ms`, `4m`). `server -check-env` validates all of them and prints the
values in effect.

Built-in limits (PROTOCOL.md §9; tests shorten them): `HELLO` within 10 s, heartbeat timeout 30 s,
empty lobby rooms deleted after 10 min, 30 msg/s with a burst of 60 (sustained abuse for 5 s closes
the connection), 256 reliable events kept per room for replay, max frame 64 KiB.

## Try the full flow: `lobbyprobe`

`cmd/lobbyprobe` simulates two clients and checks every step of the first-playable flow. It exits
0 only if every step matched (`PASS: every step matched`).

`server/go.mod` needs the Go in `tools/versions.env` (`GO_VERSION`). If your Go is older and
`GOTOOLCHAIN=local` (`go: go.mod requires go >= …`), run once `go env -w GOTOOLCHAIN=auto`, or prefix
the commands below with `GOTOOLCHAIN=go<GO_VERSION>`. The gate and `make server-probe` do this
themselves.

```bash
cd server
go run ./cmd/lobbyprobe                                   # ws://localhost:8080/ws
go run ./cmd/lobbyprobe -url ws://127.0.0.1:18081/ws      # another server
```

Steps: both `HELLO` → `ROOM_CREATE` / `ROOM_JOIN` → `READY` ×2 → `RUN_START` on both (same seed,
`0 ≤ seed < 26^9`) → `GHOST` each way (server-set `from`) → client B is killed → A gets
`ALLY_STATUS{connected:false}` → B resumes with its token → A gets `ALLY_STATUS{connected:true}`.

**Restart survival** (a running run must outlive a server restart). From the repo root, one command
runs the whole flow in Docker (compose up, probe with `-state`, restart the server, probe with
`-resume`, compose down) and prints `server-probe: PASS`:

```bash
make server-probe
```

The same steps by hand:

```bash
go run ./cmd/lobbyprobe -state /tmp/probe.json            # full flow, then save both players' resume data
docker compose -f deploy/docker-compose.dev.yml restart server   # or stop/start the binary, same DB
go run ./cmd/lobbyprobe -resume /tmp/probe.json           # both resume into the same run (new epoch)
```

The state file holds two resume tokens (secrets for those test players). It is written with mode
0600 and tokens are never printed or logged.

## Try the co-op engine: `missionprobe`

`cmd/missionprobe` runs eight scenarios against a running server, each on a fresh run, and exits 0
only if all matched (`PASS: all 8 scenarios matched`): Cell success, prisoner Downed → Recall →
revived, Downed expiry in a Cell → failed + penalty (no `DOWNED_EXPIRED`), Downed expiry outside →
`DOWNED_EXPIRED`, disconnect mid-mission → paused → resume (+ `ALLY_DOWNED` re-sent), claim race, twin
window, shared boss pool. It waits out the Downed window twice (about 70 s with the defaults). Pass
`-twin-window` / `-boss-scale` if the server runs with other `COOP_*` values.

```bash
cd server
go run ./cmd/missionprobe                                 # ws://localhost:8080/ws
```

Or the whole thing in Docker from the repo root (compose up, probe, compose down): `make mission-probe`.

## Tests

```bash
cd server && gofmt -l . && go vet ./... && go test -race ./...
```

`internal/protocol` runs the shared contract fixtures (`docs/coop/protocol/fixtures`, also the
gate's `protocol` stage and CI's `protocol-contract` job); `internal/lobby` covers the happy path,
version reject, room errors, `INVALID_STATE`, rate limit, heartbeat timeout, `from` overwrite,
resume with replay, session replacement and restart survival, and (`game_test.go`, on a fake clock)
every engine path: Cell success/denials/revive/expiry/overtime, Downed in and out of missions, pause,
resume and grace, reply order, claim race, give/drop, boss pool, twin windows, terrain, Super Sync caps,
and engine state across a (crash) restart.

## Layout

```
cmd/server      entry point (config, store, hub, HTTP)
cmd/lobbyprobe  two-client end-to-end probe
cmd/missionprobe two-client co-op engine probe (SRV-02)
cmd/wsprobe     single connection smoke test (waits for WELCOME)
internal/config env parsing and validation
internal/httpapi /healthz and /ws routing
internal/lobby  hub, per-room actors, connections, limiter (SRV-01); co-op engine (SRV-02:
                game.go, mission.go, items.go, world.go, clock.go, rules.go)
internal/protocol envelope + message types + fixture contract tests (PRO-01)
internal/store  SQLite (schema v3: players, runs, run_players, run_items, run_state; token hashes only)
```
