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
├── Coop.java            # facade: isCoopRun(), role(), hooks called from upstream files
├── CoopRun.java         # persisted run state (Bundlable): coop flag, roomCode, playerId, role history
├── net/
│   ├── CoopClient.java  # websocket, outbound queue (until open), reconnect w/ backoff
│   ├── Protocol.java    # envelope + message types, version constants
│   └── Dispatcher.java  # routes events to handlers on GL thread
├── ghost/               # GhostAlly (visual), GhostSprite, ally compass/status
├── mission/             # Mission state mirror, CellMission, timers UI, forced-turn controller
├── rooms/               # CoopRoom base + MirrorLock, PressureBridge, Cell, ...
├── items/               # ScrollOfRecall, PotionOfSuperSync, CellKey
└── ui/                  # lobby window, ping wheel, mission HUD
```

Hooks in upstream files (each marked `// COOP:`). The expected list, to keep minimal:
- `RegularLevel` room list → `Coop.addCoopRooms(rooms)` (seeded, co-op runs only)
- `Hero.die` → `Coop.onHeroWouldDie(hero)` (returns true if downed)
- `Hero.move/attack` → `Coop.onHeroAction(...)` (ghost updates)
- `Actor` / `GameScene` input wait → `Coop.forcedTurnTick()` during missions
- `GameScene` → add ghost layer + mission HUD
- `TitleScene` → multiplayer entry
- `Dungeon` save/load → `CoopRun` bundle

## 3. Protocol (v1, JSON over wss)

### 3.1 Envelope
```json
{ "v": 1, "t": "MSG_TYPE", "seq": 42, "ack": 17, "from": "<playerId>",
  "depth": 7, "branch": 0, "p": { ... typed payload ... } }
```
- `seq`: per-sender increasing number. `ack`: last seq received (for resend on reconnect).
- `depth/branch`: required for gameplay messages. Receivers ignore wrong-floor messages unless the type is marked cross-floor.
- `p`: a typed payload per message. **No field reuse** (the prototype reused `damage` for tile IDs).

### 3.2 Handshake & session
| Msg | Dir | Purpose |
|---|---|---|
| `HELLO{protocol, gameVersionCode, platform, playerId?, resumeToken?}` | C→S | version gate; resume if a token is given |
| `WELCOME{playerId, resumeToken}` / `REJECT{reason}` | S→C | |
| `ROOM_CREATE` → `ROOM_CREATED{code}` | | 6-char code |
| `ROOM_JOIN{code}` → `ROOM_JOINED{...}` / `ERROR` | | |
| `READY{heroClass}` | C→S | both ready → server starts the run |
| `RUN_START{seed, roleSeed, runId}` | S→C | client creates `CoopRun` in a co-op save slot |
| `PING`/`PONG` | both | heartbeat every 10 s, timeout 30 s |
| `ALLY_STATUS{connected, paused}` | S→C | |

### 3.3 Presence (high frequency, best-effort)
| Msg | Cross-floor | Notes |
|---|---|---|
| `GHOST{pos, anim, hpPct, heroClass, tier}` | yes (the receiver hides the ghost if the depth differs) | sent **after** the move resolves |
| `MARK{cell, kind}` | no | pings |
| `QUICK{phraseId}` | yes | quick messages |

### 3.4 Shared state (server-authoritative)
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

### 4.2 Shape
- One goroutine per room owns its state (actor model: no locks on room state). Connections feed a channel into the room loop.
- Config via env vars: `PORT`, `DB_PATH`, `ALLOWED_VERSIONS`, `LOG_LEVEL`.
- Library candidates: `github.com/coder/websocket` or `gorilla/websocket` (decide in `decisions.md`).
- Limits: max message size, rate limit per connection, room TTL when empty.

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

- **Outbound queue:** clients queue messages until the socket is open and resend un-acked ones after a reconnect.
- **Resume:** a client reconnects with `resumeToken`; the server replays events after the client's last `ack`.
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
