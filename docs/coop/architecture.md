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
├── levelgen/            # co-op level gen: floors depend only on seed+depth+branch (D-019, DET-01)
│   ├── CoopLevelGen.java    # Coop.generate's co-op path; stable-iteration helpers for the // COOP: hooks
│   ├── LevelGenState.java   # Generator decks, LimitedDrops, special/secret room lists as one Bundle snapshot
│   ├── CoopBalance.java     # D-021 balance inside co-op gen: mob HT x1.2, FOOD 5.2/1.3, 30% food cards
│   └── CoopItemIds.java     # itemNetId = depth:branch:genIndex for every generated item (D-021, ITM-01)
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
- `SecretLaboratoryRoom`, `SecretLibraryRoom`, `ShopRoom.ChooseBag` → `CoopLevelGen.stable(map)`; `VaultLevel` → `CoopLevelGen.shuffle(list)` / `stableOrder(array)` (DET-01)

**Co-op level gen (D-019, DET-01).** In co-op runs `Coop.generate` runs `level.create()` on a dedicated level-gen state (Generator, LimitedDrops, special/secret room lists), saved in the `CoopRun` (`coop_levelgen`), which only level generation advances; the runtime state is restored right after (plus what gen spent: LimitedDrops counters, unique artifacts). Level gen sees a neutral hero (warrior, no items/talents/trinkets) and a neutral profile. HashMap iterations and JDK shuffles in level gen are order-stable/seeded. So floor N is the same in both worlds whatever each player did before. What still differs or is lost on purpose is listed in the DET-01 worklog.

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

### 3.4 Shared state (server-authoritative) — in v1 since PRO-02

The full payload spec is PROTOCOL.md §5.6–§5.10; this is the map. Every request is floor-scoped (`depth`+`branch` envelope) and everything gates on the persisted co-op run flag, never on socket state.

| Request (C→S) | Event (S→both) | Notes |
|---|---|---|
| `MISSION_TRIGGER{kind:"CELL", trapCell, roomId}` | `MISSION_START{missionId, prisoner, rescuer, keySeed, startsAt, deadline, waveIntervalMs, …}` | server owns the clock; `DENY{mission_active/ally_offline/ally_other_floor}` |
| `GIVE{itemNetId, kind, item?, toCell?}` | `TRANSFER{itemNetId, from, to, kind, item?}` / `DENY` | exactly-once, no dupes; `cell_key` GIVE completes the mission key task |
| `CLAIM{itemNetId}` / `CLAIMS_REQ{depth,branch}` | `CLAIMED{itemNetId, by}` / `CLAIMS{depth, branch, itemNetIds[]}` / `DENY{taken}` | shared floor loot, first claim wins (D-021 §6A.1) |
| `DROP_SHARE{cell, itemNetId, item}` | relayed + registered | same-floor trade (D-021 §6A.2) |
| `DOWNED{cause?}` | `ALLY_DOWNED{player, deadline}` | 30 s window (config), server frozen while either player is away |
| `USE_RECALL{target, via:"recall"\|"healing"}` | `REVIVE{target, mode, hpPct:25}` / `DENY` | `recall` = region start (§5.2), `healing` = same floor, in place (D-021) |
| — | `MISSION_END{result, penalty?, reward?}`, `DOWNED_EXPIRED{player}` | expiry = real vanilla death path, never a free revive |
| `SYNC_DRINK{snapshot, turns}` | `SYNC_START{syncId, snapshot, turns}` / `DENY` | drinker → ally world |
| `SYNC_VIEW{turn, terrainDiff, entities[]}` (ally) | relayed to drinker | Remote View; ≤ 10 frames/s, stale-stateless |
| `SYNC_CMD{turn, action, cell?}` (drinker) | relayed to ally | stale `turn` dropped by the ally's client |
| `SYNC_REPORT{syncId, hpLost, reason}` (ally) | `SYNC_END{syncId, hpLost, reason}` | drinker applies hpLost |
| `BOSS_ENGAGE{bossId, ht}` / `BOSS_HIT{bossId, dmg}` | `BOSS_HP{bossId, hp, ht}` / `BOSS_DEAD{bossId}` | one pool × `COOP_BOSS_HP_SCALE` while both are on the floor (D-021) |
| `TWIN_ACT{objectId, kind, cell, active, groupId?}` | `TWIN_DONE{groupId}` | both presses inside `COOP_TWIN_WINDOW` (default 2 s) |
| `TERRAIN{cell, tile, persist?}` | relayed to ally | whitelist (PROTOCOL §5.11.2): doors 5/6/9, crystal door opened 1, unlocked exit 22, hero-locked door 38, revealed trap 18, consumed trap 19; same floor only (D-021, D-023). `persist:true` = permanent: reliable, and stored per floor (last write per cell wins) |
| `TERRAIN_REQ{depth,branch}` | `TERRAINS{depth, branch, changes[{cell,tile}] ≤512}` | the floor's stored permanent changes, asked on floor entry; the receiver applies only forward transitions (§5.11.2) (D-023 §6A.10–§6A.11) |
| `MOB_KILLED{mobNetId}` / `MOB_KILLS_REQ{depth,branch}` | `MOB_DEAD{mobNetId, by}` (reliable, both) / `MOB_KILLS{depth, branch, mobNetIds[] ≤512}` | shared level-gen mobs, first kill wins, a second report is silently ignored; kill registry persisted per run (D-023 §6A.9) |

Still reserved in v1 (PROTOCOL.md §5.4): `TASK_DONE`, `COOP_ROOM_ACT`, `COOP_ROOM_STATE` (co-op room puzzles, ROOM-01).

### 3.5 Item network IDs
Items that can cross worlds (keys, shrine items, Recall) get a stable `itemNetId`:
- seeded items: `depth:branch:genIndex` (assigned by co-op level gen, `CoopItemIds`, identical on both clients; DET-01);
- runtime items: `playerId:counter`.
The server keeps `itemNetId → owner` per run (persisted, SRV-02) and rejects double claims/transfers.

Shared mobs (D-023, §6A.9) get a `mobNetId` = `depth:branch:mobIndex`, minted by co-op level gen for the mobs it places (MOB-01), with the same format rules as a seeded itemNetId and no runtime form (mobs spawned during play stay local). The server keeps `mobNetId → killer` per run (persisted, SRV-03).

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

**Implemented in SRV-02** (`server/internal/lobby`: `game.go`, `mission.go`, `items.go`, `world.go`; schema v3):
- The co-op engine of PROTOCOL.md §5.6–§5.10 lives in each room actor (`room.g`), no locks. Times come from an injectable `Clock` (tests use a fake one); one wake-up timer per room is re-armed after every change, and a stale fire is harmless (it only acts on what is due).
- Players' floors are learned from every floor-scoped envelope (presence and gameplay), except `SYNC_VIEW`/`SYNC_CMD`/`SYNC_REPORT`, whose floor is the host world's.
- Cell: deadline = `COOP_CELL_CAP` + `COOP_CELL_CAP_PER_REGION` × `(depth-1)/5`; the server backstop fails a mission still open `COOP_DOWNED_WINDOW` after the deadline with the prisoner standing. The prisoner's Downed expiry → `MISSION_END{failed}` only (D-022); any revive of the prisoner → `MISSION_END{revived}`; a revive during a mission is always `inPlace`.
- Pause (D-005): a disconnect in a running room freezes the mission and every Downed window and ends a Super Sync (`interrupted`); both back → the clocks continue, `MISSION_UPDATE{paused:false}` and a fresh `ALLY_DOWNED{deadline}` per window. `COOP_PAUSE_GRACE` paused → `MISSION_END{aborted}`, after `REVIVE{inPlace, 25}` if the prisoner is Downed.
- Items: `run_items(run_id, item_net_id, owner, state claimed|given|dropped, giver, depth, branch)`; claims of unseen ids only for level-gen loot of the claimer's floor; `GIVE` only from the current owner (`not_owner`), each accepted `GIVE` moves ownership, and the sender's own last `GIVE` again is a duplicate (`already_transferred`).
- Persistence: `run_state` holds the engine document (mission, Downed windows, out players, Super Sync, boss pools, completed twin groups, last `MISSION_END`/`SYNC_END`), written on every change and every 5 s while a clock runs. A rebuilt run restores every clock paused at its last saved value (a crash loses at most 5 s); an open Super Sync is reported ended (`interrupted`). A resume without replay (`complete:false`) gets the current engine state re-sent as reliable events, because the `WELCOME` snapshot has no mission state.
- Reply order: the reader waits for the actor, and every reply is queued inside the actor closure on the connection's single FIFO writer queue, so replies to one connection keep the request order (ITM-01 relies on it; `TestRepliesKeepRequestOrder`).
- Config: `COOP_CELL_WAVE_INTERVAL`, `COOP_CELL_CAP`, `COOP_CELL_CAP_PER_REGION`, `COOP_DOWNED_WINDOW`, `COOP_PAUSE_GRACE`, `COOP_TWIN_WINDOW`, `COOP_BOSS_HP_SCALE`, `COOP_DEATH_MODE`, all validated by `server -check-env` (server/README.md). Probe: `server/cmd/missionprobe`.

**Implemented in SRV-03** (`server/internal/lobby/connected.go`; schema v4; PROTOCOL.md §5.11, D-023):
- Kill registry: `run_kills(run_id, mob_net_id, killer, depth, branch)`. `MOB_KILLED` of the sender's floor (else `DENY{invalid_state}`) → first kill wins → `MOB_DEAD{mobNetId, by}` to both (reliable, whether the ally is online or on the floor or not); a later report of a known id, by either player, is silently ignored. `MOB_KILLS_REQ{depth,branch}` → `MOB_KILLS` (sorted, ≤ 512, ack = the request's seq).
- Terrain: `TERRAIN` tiles outside the §5.11.2 whitelist are dropped (debug log). Without `persist` (door open/close) it stays relay-only and best-effort to the ally on the same floor. With `persist:true` it is relayed **reliably** to the ally whose last floor is the sender's (an ally offline right then gets it on resume) and, unless the tile is `OPEN_DOOR`, stored in `run_terrain(run_id, depth, branch, cell, tile)`, one tile per cell, last write wins, at most 512 cells per floor (further cells are relayed, not stored, warn log). `TERRAIN_REQ{depth,branch}` → `TERRAINS` (sorted by cell, ack = the request's seq).
- Both registries are written to SQLite on every change and loaded when a run is rebuilt after a restart. A resume without replay (`complete:false`) does not re-send them: the client asks `MOB_KILLS_REQ`/`TERRAIN_REQ` for its floor (it does on every floor entry anyway). Probe: `server/cmd/worldprobe` (builds and starts its own server, so it also restarts it).

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
- **Connected worlds (SRV-03):** `MOB_DEAD` and a `TERRAIN{persist:true}` are reliable (replayed); `MOB_KILLS`/`TERRAINS` are the floor's truth from the persisted registries, asked on every floor entry and after a resume with `complete:false`.
- **Resume:** a client reconnects with `resumeToken`; the server replays events after the client's last `ack`. Implemented in SRV-01 (PROTOCOL.md §7): replay only within the same server `epoch` and while the 256-event room log still holds every missed event (`complete:true`); otherwise no replay and the `WELCOME` room/run snapshot is the truth (`complete:false`). A second connection with the same token replaces the first (`SESSION_REPLACED`).
- **Mobile background:** the app going to background means disconnect. The server marks the player paused and pauses mission timers (game-design §4.4). Implemented in SRV-02 for disconnects (mission, Downed windows; Super Sync is torn down); `ALLY_STATUS.paused` stays `false` in v1.
- **Desync guard:** clients send a cheap level digest (`hash(depth, map[])`) when entering a floor. A mismatch is logged and surfaced. In parallel-worlds mode it's informational, because maps only differ through terrain changes.

## 6. Saves

- A co-op run is a normal SPD save in a **dedicated co-op slot** plus a `CoopRun` bundle (`coop_*` keys).
- Shared/mission state of record is on the **server**. After reloading mid-mission, the client asks the server (`HELLO` + resume) and re-applies it.
- Loading a co-op save offline: the game loads, the co-op rules stay on (run flag), and network features show "ally offline".

## 7. Security (proportionate)

- Friends-only, non-commercial: no accounts in v1. Room code + per-player resume token is enough.
- The server validates every request against its own state (e.g. you can only `GIVE` an item the server thinks you own).
- No personal data beyond IP addresses in transient logs. Write a short privacy note before distribution.
