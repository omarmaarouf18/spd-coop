# Co-op Architecture

> Status: **DRAFT v0.1** (2026-10-17). It describes the target design, and nothing is implemented on v4.0.2 yet.
> For decisions and their reasons, see `decisions.md`.

## 1. Overview

```
┌──────── Client (Role A) ────────┐      wss       ┌────────── Go server ──────────┐      wss       ┌──────── Client (Role B) ────────┐
│ Full SPD simulation (seed S)    │ ◀────────────▶ │ Rooms / sessions              │ ◀────────────▶ │ Full SPD simulation (seed S)    │
│ coop.net   – socket, queue      │  requests ▶    │ Authoritative shared state:   │    ◀ events    │ coop.net                        │
│ coop.*     – missions, ghost,   │  ◀ events      │  missions, timers, transfers, │                │ coop.*                          │
│              rooms, items       │                │  co-op room state, event log  │                │                                 │
│ Save: run bundle + coop_* keys  │                │ Snapshot → SQLite/bbolt       │                │                                 │
└─────────────────────────────────┘                └───────────────────────────────┘                └─────────────────────────────────┘
```

- **Parallel worlds:** each client simulates its own world. Nothing about mobs or combat is synced, except Super Sync (where the projection exists only in the ally's world).
- **Shared state lives on the server**, and clients never mutate it directly. A client sends a **request** → the server validates it → the server broadcasts an **event** → both clients apply it.
- **Determinism** provides the shared map (same seed → same level). The server never sends level data in normal play.

## 2. Client (`coop` package)

```
coop/
├── Coop.java            # facade: isCoopRun(), every // COOP: hook calls one method here; run start/continue (NET-01c)
├── CoopRun.java         # persisted run state (Bundlable, coop_* keys): runId, seeds, slot, hero, room, credentials (NET-01c)
├── CoopConnection.java  # the one CoopSession + CoopClient of the game, 1 s tick, lobby state for the UI (NET-01c)
├── CoopProfile.java     # co-op level gen with a neutral player profile (all docs read, no intro), then restores it (NET-01c)
├── CoopSaves.java       # co-op save slots 101–106, folders coop1..coop6 (NET-01c)
├── net/
│   ├── CoopClient.java  # text frames, reconnect w/ backoff, callbacks on MainThread; FrameLink; no-queue mode for CoopSession (NET-01a/b)
│   ├── CoopTransport.java / JavaWebSocketTransport.java  # one socket per attempt; Java-WebSocket 1.6.0 (NET-01a)
│   ├── Backoff.java     # 1 s doubling to 30 s, jitter from its own java.util.Random (NET-01a)
│   ├── MainThread.java  # Gdx.app.postRunnable in game; tests substitute it (NET-01a)
│   ├── Protocol.java    # protocol v1 envelope, types, strict decoder mirroring server/internal/protocol (NET-01b)
│   ├── ProtocolJson.java # strict JSON keeping number spelling (1 vs 1.0, PROTOCOL.md §1) (NET-01b)
│   └── CoopSession.java # HELLO/resume/epoch+ack, lobby requests, PING/liveness, REJECT/SESSION_REPLACED (NET-01b)
├── ghost/               # GhostAlly (visual), GhostSprite, ally compass/status
├── mission/             # Mission state mirror, CellMission, timers UI, forced-turn controller
├── rooms/               # CoopRoom base + MirrorLock, PressureBridge, Cell, ...
├── items/               # ScrollOfRecall, PotionOfSuperSync, CellKey
└── ui/                  # WndCoop lobby window (NET-01c); later ping wheel, mission HUD
```

Hooks in upstream files (each marked `// COOP:`). The expected list, to keep minimal:
- `RegularLevel` room list → `Coop.addCoopRooms(rooms)` (seeded, co-op runs only)
- `Hero.die` → `Coop.onHeroWouldDie(hero)` (returns true if downed)
- `Hero.move/attack` → `Coop.onHeroAction(...)` (ghost updates)
- `Actor` / `GameScene` input wait → `Coop.forcedTurnTick()` during missions
- `GameScene` → add ghost layer + mission HUD
- `TitleScene` → multiplayer entry (`Coop.addTitleButton`, NET-01c)
- `Dungeon` init/newLevel/save/load → `Coop.onInit`, `Coop.generate`, `CoopRun` bundle (NET-01c)
- `GamesInProgress.gameFolder` → co-op save folders; `Bones.get` → no remains in co-op runs (NET-01c)
- `DesktopLauncher` → `LLPD_PROFILE=b` keeps a second local player's data apart (NET-01c)

## 3. Protocol (v1, JSON over wss)

> **The spec is [`docs/coop/protocol/PROTOCOL.md`](protocol/PROTOCOL.md)**, and the contract is `docs/coop/protocol/fixtures/*.json`. Both the Java client and the Go server (`server/internal/protocol`) must pass every fixture. This section is only a summary. If it disagrees with PROTOCOL.md, PROTOCOL.md wins.

### 3.1 Envelope
```json
{ "v": 1, "t": "MSG_TYPE", "seq": 42, "ack": 17, "from": "<playerId>",
  "depth": 7, "branch": 0, "p": { ... typed payload ... } }
```
- `seq`: c2s counts per connection; s2c counts per player within the server's `epoch`. `ack`: the last seq received from the other side (resume/replay, PROTOCOL.md §2.1, §7).
- `from`: set by the server on relayed presence; a client-supplied value is ignored.
- `depth/branch`: required on `GHOST`/`MARK`, optional on `QUICK`, absent elsewhere. Receivers ignore wrong-floor messages unless the type is cross-floor.
- `p`: a typed payload per message. **No field reuse** (the prototype reused `damage` for tile IDs). c2s rejects unknown fields; s2c receivers ignore them.

### 3.2 Handshake, lobby, run start (v1)
| Msg | Dir | Purpose |
|---|---|---|
| `HELLO{protocol, gameVersionCode, platform, resumeToken?, epoch?}` | C→S | version gate; resume if a token is given |
| `WELCOME{playerId, resumeToken, epoch, serverTime, resumed, replayed, complete, room?, run?}` / `REJECT{code, msg, minGameVersionCode?}` | S→C | REJECT closes the connection |
| `ROOM_CREATE` / `ROOM_JOIN{code}` / `ROOM_LEAVE` / `READY{heroClass, ready}` | C→S | lobby |
| `ROOM_STATE{code, phase, players[]}` | S→C | after every lobby change (6-char code) |
| `RUN_START{runId, seed, roleSeed, players[]}` | S→C | both ready → the client creates `CoopRun` in a co-op save slot |
| `PING{t}` / `PONG{t, serverTime}` | C→S / S→C | heartbeat every 10 s, timeout 30 s |
| `ALLY_STATUS{playerId, connected, paused, lastSeen}` | S→C | on connect/disconnect/resume |
| `ERROR{code, msg, ref_seq?}` | S→C | request failed, connection stays open |

`ROOM_STATE`, `RUN_START` and `ALLY_STATUS` are reliable (logged and replayed on resume). Everything else is best-effort.

### 3.3 Presence (high frequency, best-effort)
| Msg | Cross-floor | Notes |
|---|---|---|
| `GHOST{pos, anim, hpPct, heroClass, tier}` | yes (the receiver hides the ghost if the depth differs) | sent **after** the move resolves |
| `MARK{cell, kind}` | no | pings |
| `QUICK{phraseId}` | yes | quick messages |

### 3.4 Shared state (server-authoritative) — reserved names, not in v1

These names are reserved in PROTOCOL.md §5.4 (a v1 server answers `ERROR{UNKNOWN_TYPE}`). They get specified when their mechanics land.

| Request (C→S) | Event (S→both) | Notes |
|---|---|---|
| `MISSION_TRIGGER{roomId}` | `MISSION_START{missionId, type, roles, deadline, seed}` | server owns the clock |
| `TASK_DONE{missionId, task}` | `MISSION_UPDATE{...}` | |
| `GIVE{itemNetId, toCell}` | `TRANSFER{itemNetId, from, to}` / `DENY` | exactly-once, no dupes |
| `DOWNED{}` | `ALLY_DOWNED{deadline}` | |
| `USE_RECALL{}` | `REVIVE{target, mode}` | |
| — | `MISSION_END{result, penalty?, reward?}` | result: success / revived / failed |
| `SYNC_DRINK{snapshot}` | `SYNC_START{snapshot, turns}` / `DENY` | drinker → ally world |
| `SYNC_VIEW{turn, terrainDiff, entities[]}` (ally) | relayed to drinker | Remote View puppets; Projection-FOV only |
| `SYNC_CMD{turn, action, cell}` (drinker) | relayed to ally | stale `turn` dropped; 5 s timeout → wait |
| `SYNC_REPORT{hpLost, reason}` (ally) | `SYNC_END{hpLost, reason}` | drinker applies hpLost |
| `COOP_ROOM_ACT{roomId, action}` | `COOP_ROOM_STATE{roomId, state}` | plates, runes, totems |

### 3.5 Item network IDs
Items that can cross worlds (keys, shrine items, Recall) get a stable `itemNetId`:
- seeded items: `depth:genIndex` (assigned during level gen, identical on both clients);
- runtime items: `playerId:counter`.
The server keeps `itemNetId → owner` and rejects double transfers.

## 4. Server (Go)

### 4.1 Responsibilities
- Sessions, rooms (2 players), handshake/version gate, heartbeat.
- **Authoritative state store** ("small database") per room:
```go
type Room struct {
    Code, RunID string
    Seed, RoleSeed int64
    Players  [2]*Player          // conn, playerId, connected, lastAck, platform
    Missions map[string]*Mission // state machine + deadline (server clock)
    Items    map[string]string   // itemNetId -> ownerPlayerId
    CoopRoom map[string]RoomState
    Log      []Event             // for resend/resume
}
```
- Timers: mission clock, downed window, disconnect grace (pausing when a player is away).
- Persistence: in memory while playing. Snapshot to **SQLite or bbolt** on important events and every N s, so a server restart or player resume doesn't lose the run's shared state.

**Implemented in SRV-01** (`server/internal/lobby`, `server/internal/store`; the `Missions`/`Items`/`CoopRoom` parts above come with MEC-*/ITM-*):
- Sessions, handshake + version gate (`ALLOWED_VERSIONS` = integer gameVersionCodes, empty = no gate), rooms (create/join/leave/ready), `RUN_START` once per room (seed and roleSeed from `crypto/rand`, seed `< 26^9`), presence relay to the ally with `from` written by the server, `ALLY_STATUS`, heartbeat, rate limit, resume with replay (PROTOCOL.md §6–§9).
- SQLite schema v2 (migrated from v1): `players(player_id, token_hash, created_at, last_seen)` with only the SHA-256 of the 32-byte resume token, compared in constant time; `runs(run_id, code, seed, role_seed, created_at, phase)`; `run_players(run_id, player_id, slot, hero_class, platform)`. A database newer than the binary is refused.
- Running runs are persisted at `RUN_START` and survive a restart: a player who `HELLO`s with its token after a restart is put back into its run (the ally shows offline until it returns). Lobby rooms live in memory only and are deleted after 10 min empty. There is no `ROOM_LEAVE` in a running room in v1.
- The per-room reliable event log (last 256 events) is in memory: after a restart there is no replay (`complete:false`), the `WELCOME` snapshot is the truth.

### 4.2 Shape
- One goroutine per room owns its state (actor model: no locks on room state). Connections feed a channel into the room loop. Each websocket has a reader and a writer goroutine (buffered, 256 frames); the hub's maps (`playerId → session`, `code → room`, `runId → room`) are small locked registries.
- Room codes: 6 chars from an alphabet without `0/O/1/I/L`, unique among live rooms.
- Config via env vars: `PORT` (8080), `DB_PATH` (`/data/coop.db`), `ALLOWED_VERSIONS` (empty), `LOG_LEVEL` (info). Usage and the `lobbyprobe` end-to-end probe: `server/README.md`.
- Libraries (D-016): `github.com/coder/websocket`, `modernc.org/sqlite`.
- Limits: 64 KiB max frame (close 1009), `HELLO` within 10 s, heartbeat timeout 30 s, 30 msg/s with burst 60 (sustained abuse for 5 s closes the connection), empty lobby room TTL 10 min.
- Logs: structured `slog` for connect/hello/room/run/resume/disconnect; resume tokens never appear in logs.

### 4.3 Deployment — everything in Docker (D-014), separate deploy repo (D-015)

**Two repos** (the same pattern as `saas-core` → `saas-core-deploy`):

| Repo | Visibility | Contents | Who changes it |
|---|---|---|---|
| **`<game>`** (main) | public (GPL) | game, `server/` source, `deploy/` **reference files**, CI | humans/agents via the normal workflow |
| **`<game>-deploy`** | private | `docker-compose.yml` (image tags pinned to a commit SHA), `Caddyfile`, `.env.example`, `.github/workflows/deploy.yml`, `README.md` | **only the bot** (synced from the main repo). Never edited by hand |

```
main repo push to main
  └─ build-and-publish.yml
       1. build server image → ghcr.io/<owner>/<game>-server:<sha> + :latest   (public package → VPS needs no auth)
       2. sync job (GitHub App token): copy deploy/docker-compose.prod.yml → <game>-deploy/docker-compose.yml,
          replace ":IMAGE_TAG" → ":<sha>", copy Caddyfile + deploy.yml, validate `docker compose config`, commit "deploy: …<sha>"
<game>-deploy push to main
  └─ deploy.yml  (self-hosted runner ON the VPS)
       checkout → .env symlink to /home/deploybot/.env → compose config → PRE-FLIGHT `server -check-env`
       → pull → save current tags → up -d → health check /healthz (retries) → on failure ROLLBACK to HEAD~1 compose → log dump
```

Main repo layout for this:
```
deploy/                       # reference files (the source of truth, synced to <game>-deploy)
├── docker-compose.prod.yml   # images use the literal tag ":IMAGE_TAG" (replaced by CI)
├── docker-compose.dev.yml    # local: server built from ../server, port 8080, no TLS
├── Caddyfile                 # wss://{$DOMAIN} → server:8080 ; only /ws and /healthz reachable
├── deploy.yml                # reference copy of <game>-deploy/.github/workflows/deploy.yml
├── deploy-repo-README.md     # becomes <game>-deploy/README.md
└── .env.example
server/
└── Dockerfile                # multi-stage: golang (CGO_ENABLED=0) → distroless/static:nonroot
```

Lessons carried over from the SaaS deploy pipeline (they're real incidents there):
- **Pin volume names** (`name: <game>_server_data`). Otherwise running compose from a different directory silently creates a new, empty volume.
- **The canonical `.env` lives outside the checkout** (`/home/deploybot/.env`), and the workspace `.env` is a symlink to it.
- **Pre-flight before touching running containers:** `server -check-env` validates the config. If it fails, the old containers keep serving.
- **Health check + automatic rollback** to the previous compose (`HEAD~1`), then a log dump on failure.
- Cross-repo auth uses a **GitHub App installation token**, not a long-lived PAT. Use `persist-credentials: false` on checkout, and the app needs **Contents + Workflows** write (because the sync pushes `.github/workflows/deploy.yml`).
- Secrets are never printed, pasted in chat, or committed.
- **`server`**: the Go binary, listening on `:8080` **inside the compose network only** (no host port in prod). State goes to the volume `server-data` mounted at `/data` (`DB_PATH=/data/coop.db`). Use a **pure-Go SQLite** (`modernc.org/sqlite`) or bbolt so the image stays static/distroless. There's a `HEALTHCHECK` on `/healthz`.
- **`caddy`** (`caddy:2-alpine`): the only service with host ports (80/443). It handles automatic TLS and reverse-proxies the websocket. Volumes: `caddy-data`, `caddy-config`.
- **Hardening:** `restart: unless-stopped`, `mem_limit` per service (sized for a 1 GB VPS), `json-file` log rotation (`max-size: 10m`, `max-file: 3`), read-only root fs for `server`, and a non-root user.
- **Images:** CI builds `ghcr.io/<owner>/<game>-server:<sha>` and `:latest` on `main`. The VPS **never builds**; it only pulls pinned tags through the deploy repo.
- **Local dev:** `docker compose -f deploy/docker-compose.dev.yml up --build`, or `go run ./cmd/server` without Docker.

## 5. Reliability

- **Outbound queue:** clients queue messages until the socket is open and resend un-acked ones after a reconnect. `CoopClient` (NET-01a) implements the first half: frames sent while not open are queued (max 256; `send()` returns false when full, never a silent drop) and flushed in order on open. Frames already handed to a socket that then dies can be lost; resending un-acked ones is the protocol layer's job (seq/ack, NET-01).
- **Liveness:** websocket ping frames every 30 s (`setConnectionLostTimeout`) detect dead sockets; the JSON `PING`/`PONG` heartbeat (§3.2) is separate and belongs to the protocol layer.
- **Resume:** a client reconnects with `resumeToken`; the server replays events after the client's last `ack`. Implemented in SRV-01 (PROTOCOL.md §7): replay only within the same server `epoch` and while the 256-event room log still holds every missed event (`complete:true`); otherwise no replay and the `WELCOME` room/run snapshot is the truth (`complete:false`). A second connection with the same token replaces the first (`SESSION_REPLACED`).
- **Mobile background:** the app going to background means disconnect. The server marks the player paused and pauses mission timers (game-design §4.4).
- **Desync guard:** clients send a cheap level digest (`hash(depth, map[])`) when entering a floor. A mismatch is logged and surfaced. In parallel-worlds mode it's informational, because maps only differ through terrain changes.

## 6. Saves

- A co-op run is a normal SPD save in a **dedicated co-op slot** plus a `CoopRun` bundle (`coop_*` keys).
- Shared/mission state of record is on the **server**. After reloading mid-mission, the client asks the server (`HELLO` + resume) and re-applies it.
- Loading a co-op save offline: the game loads, the co-op rules stay on (run flag), and network features show "ally offline".

## 7. Security (proportionate)

- Friends-only, non-commercial: no accounts in v1. Room code + per-player resume token is enough.
- The server validates every request against its own state (e.g. you can only `GIVE` an item the server thinks you own).
- No personal data beyond IP addresses in transient logs. Write a short privacy note before distribution.
