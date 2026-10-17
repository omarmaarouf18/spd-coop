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

## Tests

```bash
cd server && gofmt -l . && go vet ./... && go test -race ./...
```

`internal/protocol` runs the shared contract fixtures (`docs/coop/protocol/fixtures`, also the
gate's `protocol` stage and CI's `protocol-contract` job); `internal/lobby` covers the happy path,
version reject, room errors, `INVALID_STATE`, rate limit, heartbeat timeout, `from` overwrite,
resume with replay, session replacement and restart survival.

## Layout

```
cmd/server      entry point (config, store, hub, HTTP)
cmd/lobbyprobe  two-client end-to-end probe
cmd/wsprobe     single connection smoke test (waits for WELCOME)
internal/config env parsing and validation
internal/httpapi /healthz and /ws routing
internal/lobby  hub, per-room actors, connections, limiter (SRV-01)
internal/protocol envelope + message types + fixture contract tests (PRO-01)
internal/store  SQLite (schema v2: players, runs, run_players; token hashes only)
```
