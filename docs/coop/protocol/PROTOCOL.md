# Logic Link PD — network protocol v1

> **Single source of truth** for the client (Java, `coop/net`, task `NET-01`) and the server (Go, `server/internal/protocol`, `SRV-01`).
> The machine-checkable contract is `docs/coop/protocol/fixtures/*.json` (§11). Both sides must pass every fixture.
> Changing this file or the fixtures is a hot-path change (WORKFLOW §5) and must keep both implementations in step.

Keywords **MUST**, **MUST NOT**, **SHOULD** and **MAY** are used as in RFC 2119.

---

## 1. Transport

- Websocket endpoint `/ws`: `ws://localhost:8080/ws` in dev (`make server-dev`), `wss://<DOMAIN>/ws` in prod (TLS ends at Caddy).
- **Text frames only**, each frame holding exactly one JSON object, UTF-8. A binary frame is answered with `ERROR{BAD_MESSAGE}`.
- **Max frame size: 64 KiB.** A larger frame makes the server close the connection with websocket status `1009` (message too big).
- All integers in the protocol fit in a JSON-safe integer (|n| < 2^53). Senders MUST NOT send fractions or exponents for integer fields (`1`, not `1.0` or `1e0`).
- JSON `null` is never a valid value. An optional field is either absent or has a value of its type.

## 2. Envelope

Every message, in both directions:

```json
{ "v": 1, "t": "TYPE", "seq": 12, "ack": 9, "from": "0123456789abcdef",
  "depth": 3, "branch": 0, "p": { } }
```

| Field | Type | c2s (client → server) | s2c (server → client) |
|---|---|---|---|
| `v` | int | REQUIRED, `1` | REQUIRED, `1` |
| `t` | string | REQUIRED, a c2s type (§5) | REQUIRED, an s2c type (§5) |
| `seq` | int ≥ 1 | REQUIRED (§2.1) | REQUIRED (§2.1) |
| `ack` | int ≥ 0 | OPTIONAL (§2.1) | OPTIONAL (§2.1) |
| `from` | playerId | OPTIONAL and **ignored**: the server overwrites it when relaying | REQUIRED on relayed types (§5.3, and `DROP_SHARE` §5.7, `SYNC_VIEW`/`SYNC_CMD` §5.9, `TERRAIN` §5.10), absent otherwise |
| `depth` | int 1..30 | REQUIRED on every floor-scoped type (§5.3, §5.6 `MISSION_TRIGGER`, §5.7, §5.8, §5.9, §5.10 c2s types); OPTIONAL on `QUICK`; MUST be absent on every other type | REQUIRED on relayed types (copied from the sender), FORBIDDEN on server-authored s2c (§5.5) |
| `branch` | int 0..9 | REQUIRED together with `depth` (both or neither) | same |
| `p` | object | REQUIRED, the typed payload (`{}` when the type has no fields) | REQUIRED |

`depth` is SPD's `Dungeon.depth`, and `branch` is `Dungeon.branch`. The bounds are sanity limits, not game rules.

### 2.1 `seq` and `ack`

- **c2s `seq`** counts the client's messages **on the current connection**: the first message (`HELLO`) has `seq` 1, and each following message has a strictly larger `seq` (normally +1). A non-increasing `seq` is answered with `ERROR{BAD_MESSAGE}` and the message is not processed.
- **s2c `seq`** counts the server's messages **to this player within the server's `epoch`** (the `epoch` is a random id the server picks at start-up and reports in `WELCOME`). It keeps increasing across reconnects as long as the epoch is the same, so the client can say what it last saw.
- Messages sent **before** a connection's first `WELCOME` (`REJECT`, and an `ERROR` on a bad first `HELLO`) are numbered **per connection from 1**. The per-epoch counter starts with `WELCOME`; only the `WELCOME`'s `seq` and later are meaningful for resume (`ack`), and everything before it is best-effort.
- **c2s `ack`** is the highest s2c `seq` the client has received in the current epoch. The server only reads it on `HELLO` with a resume token (§7). On other messages it is optional and informational.
- **s2c `ack`** is the c2s `seq` of the last message the server processed on this connection. It SHOULD be set on direct replies (`WELCOME`, `PONG`, `ERROR`, and the `ROOM_STATE` answering the player's own request).

## 3. Identifiers and value formats

| Name | Format |
|---|---|
| playerId | 16 lowercase hex chars `^[0-9a-f]{16}$` (64 random bits) |
| runId | 16 lowercase hex chars `^[0-9a-f]{16}$` |
| epoch | 16 lowercase hex chars `^[0-9a-f]{16}$` |
| resumeToken | 43 chars base64url without padding `^[A-Za-z0-9_-]{43}$` (32 random bytes). **Secret:** the client stores it with the co-op save; it is never logged, by either side |
| room code | 6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789` (no `0 O 1 I L`), uppercase; the client uppercases user input before sending |
| platform | `"desktop"` \| `"android"` \| `"ios"` |
| heroClass | `"WARRIOR"` \| `"MAGE"` \| `"ROGUE"` \| `"HUNTRESS"` \| `"DUELIST"` \| `"CLERIC"` (SPD `HeroClass` names) |
| anim | `"idle"` \| `"run"` \| `"attack"` \| `"operate"` \| `"zap"` \| `"die"` (SPD `CharSprite` animations) |
| mark kind | `"HERE"` \| `"DANGER"` \| `"ITEM"` \| `"GO"` |
| cell | int 0..65535 (a `Level` cell index, `x + y * width`) |
| time | int, Unix epoch milliseconds |
| msg (free text) | string, at most 256 chars |
| missionId, syncId | 16 lowercase hex chars `^[0-9a-f]{16}$` (like epoch, server-minted CSPRNG) |
| itemNetId | `"<depth>:<branch>:<genIndex>"` for level-gen loot — the exact id DET-01's `CoopItemIds` mints; `depth` 1..30, `branch` 0..9, `genIndex` 0..9999999999, all decimal without a leading zero — or `"<playerId16hex>:<counter>"` for runtime-minted items (counter 1..9999999999). Regex: `^(?:(?:[12][0-9]|30|[1-9]):[0-9]:(?:0|[1-9][0-9]{0,9})|[0-9a-f]{16}:(?:[1-9][0-9]{0,9}))$` |
| objectId, roomId | int 1..(2^53−1), a content-defined id minted by co-op level gen (DET-01); stable in both worlds |
| entityId | int 1..(2^53−1), a StableActor id (§5.9) |
| tile | int 0..255 (an SPD `Terrain` value) |
| opaque | string, at most 16384 UTF-8 bytes; carried verbatim (the server never parses it) |
| mission kind | `"CELL"` |
| mission result | `"success"` \| `"revived"` \| `"failed"` \| `"aborted"` |
| revive mode | `"inPlace"` \| `"regionStart"` |
| revive method | `"recall"` \| `"healing"` |
| sync action | `"move"` \| `"attack"` \| `"wait"` |
| report reason | `"expired"` \| `"died"` \| `"interrupted"` |
| item kind | `"cell_key"` \| `"item"` |
| twin kind | `"door"` \| `"switch"` |

## 4. Errors

### 4.1 `ERROR` (s2c) — the request failed, the connection stays open

```json
{ "v":1, "t":"ERROR", "seq":5, "ack":3, "p": { "code":"ROOM_NOT_FOUND", "msg":"no room with that code", "ref_seq":3 } }
```
`code` REQUIRED (closed list below), `msg` REQUIRED (≤ 256 chars, for logs, not shown raw to players), `ref_seq` OPTIONAL (the c2s `seq` it answers, when known).

### 4.2 `REJECT` (s2c) — the handshake failed, the server closes

Answer to a bad `HELLO` (or to a message with an unsupported `v`). The server closes the connection right after it, with websocket status `4001`.
`code` REQUIRED, `msg` REQUIRED, `minGameVersionCode` OPTIONAL (int, sent with `VERSION_MISMATCH` when the server has a version gate).

### 4.3 Codes (closed list)

| Code | Sent as | Meaning |
|---|---|---|
| `BAD_MESSAGE` | ERROR, REJECT | Not valid per this spec (bad JSON, missing/unknown/mistyped field, out of range, non-increasing seq, binary frame). REJECT when it is the `HELLO` |
| `UNKNOWN_TYPE` | ERROR | `t` is not a v1 c2s type, including reserved names (§5.4) and s2c-only types |
| `VERSION_MISMATCH` | REJECT | `v` ≠ 1, `protocol` ≠ 1, or `gameVersionCode` not allowed (§8) |
| `RESUME_FAILED` | REJECT | `resumeToken` unknown or malformed. Start fresh with a `HELLO` without a token |
| `INVALID_STATE` | ERROR | The message is legal but not in the current state (§6) |
| `NOT_IN_ROOM` | ERROR | Needs a room, but the player has none |
| `ALREADY_IN_ROOM` | ERROR | `ROOM_CREATE`/`ROOM_JOIN` while already in a room |
| `ROOM_NOT_FOUND` | ERROR | No live room with that code |
| `ROOM_FULL` | ERROR | The room already has two players |
| `RATE_LIMITED` | ERROR | Over the rate limit (§9); the message was dropped |
| `SESSION_REPLACED` | ERROR | Sent to an older connection when the same player resumed on a new one; then closed with status `4003` |
| `INTERNAL` | ERROR | Server-side failure; the client MAY retry later |

## 5. Message types (v1)

"Reliable" s2c messages are kept in the per-room event log and **replayed on resume** (§7). Everything else is best-effort.

### 5.1 Session

| `t` | Dir | Payload | Reliable |
|---|---|---|---|
| `HELLO` | c2s | `protocol` int (REQUIRED), `gameVersionCode` int ≥ 1 (REQUIRED, the client's `appVersionCode`), `platform` (REQUIRED), `resumeToken` (OPTIONAL), `epoch` (OPTIONAL, only together with `resumeToken`: the epoch of the last `WELCOME`) | — |
| `WELCOME` | s2c | `playerId` (REQUIRED), `resumeToken` (REQUIRED), `epoch` (REQUIRED), `serverTime` time (REQUIRED), `resumed` bool (REQUIRED), `replayed` int ≥ 0 (REQUIRED, number of replayed events that follow), `complete` bool (REQUIRED, false when missed events could not be replayed: other epoch or evicted from the log; then the `room`/`run` snapshot is the only truth), `room` RoomState (OPTIONAL, present when the player is in a room), `run` RunInfo (OPTIONAL, present when the room is `running`) | no |
| `REJECT` | s2c | §4.2 | no |
| `PING` | c2s | `t` time (REQUIRED, the client's clock) | — |
| `PONG` | s2c | `t` time (REQUIRED, echoed), `serverTime` time (REQUIRED) | no |
| `ERROR` | s2c | §4.1 | no |

### 5.2 Lobby and run start

| `t` | Dir | Payload | Reliable |
|---|---|---|---|
| `ROOM_CREATE` | c2s | `{}` | — |
| `ROOM_JOIN` | c2s | `code` room code (REQUIRED) | — |
| `ROOM_LEAVE` | c2s | `{}` | — |
| `READY` | c2s | `heroClass` (REQUIRED), `ready` bool (REQUIRED) | — |
| `ROOM_STATE` | s2c | RoomState (below) | yes |
| `RUN_START` | s2c | `runId` (REQUIRED), `seed` int (REQUIRED, `0 ≤ seed < 5429503678976` = 26^9 = SPD `DungeonSeed.TOTAL_SEEDS`), `roleSeed` int (REQUIRED, `0 ≤ roleSeed < 2^53`), `players` array of exactly 2 RunPlayer (REQUIRED) | yes |
| `ALLY_STATUS` | s2c | `playerId` (REQUIRED, the ally), `connected` bool (REQUIRED), `paused` bool (REQUIRED), `lastSeen` time (REQUIRED) | yes |

**RoomState** = `{ "code", "phase": "lobby"|"running", "players": [ RoomPlayer… ] }` with 1 or 2 players, sorted by slot.
**RoomPlayer** = `{ "playerId", "slot": 0|1, "heroClass"? , "ready": bool, "connected": bool, "platform" }`. `heroClass` is absent until the player sends `READY`.
**RunInfo** = the `RUN_START` payload (`runId`, `seed`, `roleSeed`, `players`).
**RunPlayer** = `{ "playerId", "slot": 0|1, "heroClass", "platform" }`. The two entries have different slots, sorted by slot. Slot 0 is the room creator (role A at run start), slot 1 the joiner (role B).

### 5.3 Presence (best-effort, never logged or replayed)

The client sends these only in a `running` room. The server checks the shape, sets `from` to the sender's playerId, and relays them unchanged to the ally. It does not interpret them. If the ally is offline they are dropped silently.

| `t` | Dir | Envelope | Payload |
|---|---|---|---|
| `GHOST` | both | `depth`+`branch` REQUIRED | `pos` cell, `anim`, `hpPct` int 0..100, `heroClass`, `tier` int 0..6 (SPD `Hero.tier()`): all REQUIRED |
| `MARK` | both | `depth`+`branch` REQUIRED | `cell` cell, `kind` mark kind: both REQUIRED |
| `QUICK` | both | `depth`+`branch` OPTIONAL (relayed if present) | `phraseId` int 0..63 (REQUIRED; the phrase table lives in the client's `.properties`) |

The receiver ignores `GHOST`/`MARK` for another floor except to update the ally compass (game-design §2.1).

### 5.4 Reserved type names (not implemented in v1)

`TASK_DONE`, `COOP_ROOM_ACT`, `COOP_ROOM_STATE` (architecture §3.4).
A v1 server answers any of them, like any other unknown type, with `ERROR{UNKNOWN_TYPE}`. A v1 client ignores unknown s2c types.

**Within `v: 1`, the type list only grows additively and the reserved list only shrinks.** A reserved name that becomes a defined type stays defined (that is how PRO-02 turned the PRO-01 reserved list into §5.6–§5.10), and new optional s2c fields may appear (§5.5). A decoder must therefore only treat names as: known c2s, known s2c, reserved-c2s, reserved-s2c, and unknown — never in terms of "released in patch X".

### 5.5 Unknown fields

- **c2s:** the server rejects unknown fields, in the envelope and in payloads (`ERROR{BAD_MESSAGE}`, or `REJECT` for `HELLO`). This catches client bugs (typos) early.
- **s2c:** the client MUST ignore unknown fields. This lets the server add optional fields within v1 without breaking released clients. It MUST still reject wrong types and out-of-range values of known fields.

### 5.6 Missions and The Cell

The server is authoritative for mission state: at most one active mission per run (D-020). It learns each player's current floor from that connection's last floor-scoped message (§5.3 and the `depth`+`branch` envelopes in this section); it never simulates levels. Denials use `DENY` (§5.7): `ally_offline`, `ally_other_floor` (mission trigger needs the ally online on the trigger's floor), `mission_active`.

| `t` | Dir | Envelope | Payload | Reliable |
|---|---|---|---|---|
| `MISSION_TRIGGER` | c2s | `depth`+`branch` REQUIRED | `kind` mission kind (REQUIRED, `"CELL"`), `trapCell` cell (REQUIRED), `roomId` objectId (REQUIRED) | — |
| `MISSION_START` | s2c | floor FORBIDDEN | `missionId` (REQUIRED), `kind` mission kind (REQUIRED), `prisoner` playerId (REQUIRED), `rescuer` playerId (REQUIRED), `depth` int 1..30 (REQUIRED), `branch` int 0..9 (REQUIRED), `trapCell` cell (REQUIRED), `roomId` objectId (REQUIRED), `keySeed` int 0..2^53−1 (REQUIRED), `startsAt` time (REQUIRED), `deadline` time (REQUIRED), `waveIntervalMs` int ≥ 1 (REQUIRED) | yes |
| `MISSION_UPDATE` | s2c | floor FORBIDDEN | `missionId` (REQUIRED), `paused` bool (REQUIRED), `remainingMs` int ≥ 0 (REQUIRED), `tasks` object `{ "key": bool }` (OPTIONAL; known task flags when changed) | yes |
| `MISSION_END` | s2c | floor FORBIDDEN | `missionId` (REQUIRED), `result` mission result (REQUIRED), `penalty` object (REQUIRED with result `"failed"`, forbidden otherwise), `reward` object (REQUIRED with result `"success"`, forbidden otherwise) | yes |

- `prisoner` and `rescuer` MUST be the two players of the run and different. For a `"CELL"` mission the `depth`/`branch` payload fields are the mission floor (both clients validate against their own floor).
- `penalty` = `{ "regionStartDepth": int 1..30, "goldPct": int 0..100 }`. `reward` = `{ "rewardSeed": int 0..2^53−1 }` — both clients roll deterministically from it.
- Result `success` → `reward`; `failed` → `penalty`; `revived` (the prisoner was revived mid-mission and the mission wound down) and `aborted` (server-side teardown without penalty) carry neither.
- `deadline` = `startsAt` + the mission's hard cap (for `"CELL"`: 4 min + 15 s × `regionIndex`, server config). The **server** judges the cap and failures; waves are client pacing ("the prisoner's client spawns waves every `waveIntervalMs`" — MEC-01) and knock the prisoner Downed when their window lapses (`DOWNED` §5.8), which is what makes failure server-visible.

### 5.7 Shared items

Every shared `itemNetId` has **exactly one owner** per run, recorded server-side and persisted with the run (SRV-02, store migration v3). Three flows, all gated on the co-op run flag (D-021):

- **Claim (floor loot, game-design §6A.1):** level-gen items form one shared pool per floor. The first `CLAIM` wins and is broadcast to both players; a later claim of the same id gets `DENY{taken}`, re-claiming your own is silently ignored. A claimed item vanishes from the ally's world (immediately if they are on that floor, otherwise it is absent when they arrive — ask with `CLAIMS_REQ`).
- **Give (hand to the ally):** `GIVE` hands one item over, same floor or across floors (Item Shrine, §7). `TRANSFER` announces it to both. The first `GIVE` of an unseen id mints ownership; the same id again is `DENY{already_transferred}` (owned by you) or `DENY{not_owner}` (owned by the other player).
- **Drop (same-floor trade, §6A.2):** `DROP_SHARE` relays a dropped item to the ally on the same floor and registers it as claimable-once; the ally picks it up with `CLAIM`. Items consumed on impact are never sent.

| `t` | Dir | Envelope | Payload | Reliable |
|---|---|---|---|---|
| `CLAIM` | c2s | `depth`+`branch` REQUIRED | `itemNetId` (REQUIRED) | — |
| `CLAIMED` | s2c | floor FORBIDDEN | `itemNetId` (REQUIRED), `by` playerId (REQUIRED) | yes |
| `CLAIMS_REQ` | c2s | floor FORBIDDEN | `depth` int 1..30 (REQUIRED), `branch` int 0..9 (REQUIRED) | — |
| `CLAIMS` | s2c | floor FORBIDDEN | `depth` int 1..30 (REQUIRED), `branch` int 0..9 (REQUIRED), `itemNetIds` array of itemNetId, at most 256 (REQUIRED) | no |
| `GIVE` | c2s | `depth`+`branch` REQUIRED | `itemNetId` (REQUIRED), `kind` item kind (REQUIRED), `item` opaque (REQUIRED with kind `"item"`, forbidden with `"cell_key"`), `toCell` cell (OPTIONAL, landing cell in the ally's world) | — |
| `TRANSFER` | s2c | floor FORBIDDEN | `itemNetId` (REQUIRED), `from` playerId (REQUIRED), `to` playerId (REQUIRED), `kind` item kind (REQUIRED), `item` opaque (REQUIRED with kind `"item"`, absent otherwise) | yes |
| `DROP_SHARE` | both | `depth`+`branch` REQUIRED | `cell` cell (REQUIRED), `itemNetId` (REQUIRED), `item` opaque (REQUIRED) | yes |
| `DENY` | s2c | floor FORBIDDEN | `reason` (REQUIRED, closed list below) | no |

`DENY.reason` closed list: `ally_offline`, `ally_other_floor`, `mission_active`, `invalid_state`, `not_owner`, `already_transferred`, `taken`, `no_pool`, `sync_active`, `downed`, `not_downed`. Meanings: `ally_offline` — the request needs the ally connected; `ally_other_floor` — it needs the ally on the sender's floor; `mission_active` — only one mission at a time; `invalid_state` — other contradictions (e.g. a `GIVE{kind:"cell_key"}` with no active Cell mission); `no_pool` — a `BOSS_HIT` with no shared pool (§5.10); `downed` — the sender is Downed; `not_downed` — the target is not Downed.

A `GIVE{kind:"cell_key"}` completes the mission's key task: the server answers with `TRANSFER` **and** `MISSION_UPDATE{tasks:{key:true}}` (§5.6).

### 5.8 Downed and revive

| `t` | Dir | Envelope | Payload | Reliable |
|---|---|---|---|---|
| `DOWNED` | c2s | `depth`+`branch` REQUIRED | `cause` string, at most 64 chars (OPTIONAL, free label for the ally's UI) | — |
| `ALLY_DOWNED` | s2c | floor FORBIDDEN | `player` playerId (REQUIRED), `deadline` time (REQUIRED) | yes |
| `USE_RECALL` | c2s | `depth`+`branch` REQUIRED | `target` playerId (REQUIRED), `via` revive method (REQUIRED) | — |
| `REVIVE` | s2c | floor FORBIDDEN | `target` playerId (REQUIRED), `mode` revive mode (REQUIRED), `hpPct` int 0..100 (REQUIRED) | yes |
| `DOWNED_EXPIRED` | s2c | floor FORBIDDEN | `player` playerId (REQUIRED) | yes |

- When a hero would die in a co-op run, the client sends `DOWNED`; the server starts the Downed window (`COOP_DOWNED_WINDOW`, default 30 s, frozen while either player is disconnected) and answers `ALLY_DOWNED{deadline}` to both players. Repeat `DOWNED` while already Downed is silently ignored (the window is not reset). The ally may `USE_RECALL`; revives are `REVIVE{hpPct:25}` (game-design §5.2–§5.3): `via:"recall"` — any floor, `mode:"regionStart"`; `via:"healing"` — sender's floor must equal the target's floor (`DENY ally_other_floor`), `mode:"inPlace"`. Sender Downed → `DENY{downed}`, target not Downed → `DENY{not_downed}`.
- When the window expires **outside a Cell mission** the server sends `DOWNED_EXPIRED` to both and runs no game logic itself: the expired player's client plays the **real vanilla death path** (never a free revive) and the server records the player as out for the rest of the run (MEC-02, SRV-02).
- **Inside a Cell mission (D-022)**, the prisoner's expiry is not a death: the server sends `MISSION_END{failed, penalty}` (§5.6) to both and **no** `DOWNED_EXPIRED`. The prisoner is not out; both players take the penalty, and the prisoner arrives at the region start at 25% HP.
- When a mission is **aborted** (§5.6, e.g. paused too long) while its prisoner is Downed, the server first revives the prisoner in place, `REVIVE{target: prisoner, mode:"inPlace", hpPct:25}` to both, then sends `MISSION_END{aborted}`: no penalty and no `DOWNED_EXPIRED`.
- Death outside a mission (expiry of a plain `DOWNED`) ends the run for that player only; the ally continues (single switch: `COOP_DEATH_MODE=player_out`, the default and only v1 mode — architecture §4).

### 5.9 Super Sync (Remote View)

The server relays the combat session between the two players and never simulates combat: all battle math lives in the ally's world (the host). The drinker sends the session's `snapshot`; the host streams `SYNC_VIEW{turn,…}`; the drinker steers with `SYNC_CMD` (stale `turn`s are dropped by the **host's** client); the host ends the session with `SYNC_REPORT` → `SYNC_END` to both (game-design §6). At most one session per run (`DENY sync_active`); the drinker must not be Downed (`DENY downed`). Either player disconnecting tears the session down: `SYNC_END{hpLost:0, reason:"interrupted"}` to both (D-005 pause in SRV-02).

| `t` | Dir | Envelope | Payload | Reliable |
|---|---|---|---|---|
| `SYNC_DRINK` | c2s | `depth`+`branch` REQUIRED | `snapshot` opaque (REQUIRED), `turns` int ≥ 1 (REQUIRED, the session's turn budget) | — |
| `SYNC_START` | s2c | floor FORBIDDEN | `syncId` (REQUIRED), `snapshot` opaque (REQUIRED), `turns` int ≥ 1 (REQUIRED) | yes |
| `SYNC_VIEW` | both | `depth`+`branch` REQUIRED | `syncId` (REQUIRED), `turn` int ≥ 1 (REQUIRED), `terrainDiff` array of `[cell, tile]` pairs, both values int 0..65535, at most 512 pairs (REQUIRED), `entities` array of SyncEntity, at most 64 (REQUIRED) | no |
| `SYNC_CMD` | both | `depth`+`branch` REQUIRED | `syncId` (REQUIRED), `turn` int ≥ 1 (REQUIRED), `action` sync action (REQUIRED), `cell` (REQUIRED with action `"move"`/`"attack"`, forbidden with `"wait"`) | no |
| `SYNC_REPORT` | c2s | `depth`+`branch` REQUIRED | `syncId` (REQUIRED), `hpLost` int ≥ 0 (REQUIRED), `reason` report reason (REQUIRED) | — |
| `SYNC_END` | s2c | floor FORBIDDEN | `syncId` (REQUIRED), `hpLost` int ≥ 0 (REQUIRED), `reason` report reason (REQUIRED) | yes |

SyncEntity = `{ "id": entityId, "kind": string, at most 32 chars, "cell": cell, "hpPct": int 0..100 }` — all four REQUIRED; `kind` is an opaque rendering label (`"hero"`, `"projection"`, or the mob class's simple name). `terrainDiff` pairs are flat two-element arrays, `[i]` = cell, `[i+1]` = tile; the drinker applies them to its own copy of the ally's floor.

The server rejects `SYNC_VIEW`/`SYNC_CMD` for a floor neither player is on, and drops `SYNC_CMD`/`SYNC_REPORT`/`SYNC_VIEW` for an unknown `syncId` silently (session teardown already reported them).

### 5.10 Shared boss HP, twin doors and terrain mirroring

- **Shared boss HP (§6A.3):** the first `BOSS_ENGAGE{bossId, ht}` for a floor defines the boss's `ht`. The server holds **one** HP pool for a boss while both players are on that floor: `pool = round(ht × COOP_BOSS_HP_SCALE)` (config, default `1.8`). Ally not on the floor → **no pool** (`BOSS_HIT` gets `DENY{no_pool}`; the fight is per-world). Every accepted `BOSS_HIT` broadcasts the new pool; pool `0` → `BOSS_DEAD` to both, and further hits/engages for that `bossId` are silently ignored. Both worlds drive phases and death from the broadcast pool.
- **Twin windows (§6A.4–§6A.5):** presses pair server-side within `COOP_TWIN_WINDOW` (config, default 2 s). A **door** succeeds when both players press the **same** `objectId` inside the window; **switches** when both press **different** `objectIds` of the same `groupId`. The window opens with the first press; the matching press (same floor, inside the window) completes it → `TWIN_DONE{groupId}` to both (for a door, `groupId` is the door's `objectId`). Presses after a completed group are no-ops; rooms retry until then (§7).
- **Terrain mirroring (§6A.6):** door tiles only. The server relays `TERRAIN` to the ally on the same floor; tiles outside the door whitelist are silently dropped (logged): `DOOR = 5`, `OPEN_DOOR = 6`, and `EMBERS = 9` (a burned-through door) — SPD v4.0.2 `Terrain` values; other terrain stays per-world.

| `t` | Dir | Envelope | Payload | Reliable |
|---|---|---|---|---|
| `BOSS_ENGAGE` | c2s | `depth`+`branch` REQUIRED | `bossId` int 1..2^53−1 (REQUIRED, content-defined by level gen), `ht` int ≥ 1 (REQUIRED) | — |
| `BOSS_HIT` | c2s | `depth`+`branch` REQUIRED | `bossId` int 1..2^53−1 (REQUIRED), `dmg` int ≥ 1 (REQUIRED) | — |
| `BOSS_HP` | s2c | floor FORBIDDEN | `bossId` int 1..2^53−1 (REQUIRED), `hp` int ≥ 0 (REQUIRED), `ht` int ≥ 1 (REQUIRED) | yes |
| `BOSS_DEAD` | s2c | floor FORBIDDEN | `bossId` int 1..2^53−1 (REQUIRED) | yes |
| `TWIN_ACT` | c2s | `depth`+`branch` REQUIRED | `objectId` objectId (REQUIRED), `kind` twin kind (REQUIRED), `cell` (REQUIRED), `active` bool (REQUIRED), `groupId` objectId (REQUIRED with kind `"switch"`, forbidden with kind `"door"`) | — |
| `TWIN_DONE` | s2c | floor FORBIDDEN | `groupId` int 1..2^53−1 (REQUIRED) | yes |
| `TERRAIN` | both | `depth`+`branch` REQUIRED | `cell` cell (REQUIRED), `tile` tile (REQUIRED) | no |

Floor gating trusts the `depth`+`branch` envelope (same as presence, §5.3): both worlds are seeded identically, so only real movement in the simulated run can place a player on a floor they are not on.

## 6. Connection state machine

```
new ──HELLO ok──▶ ready ──ROOM_CREATE / ROOM_JOIN──▶ in_lobby ──both READY──▶ running
 │                  ▲                                   │
 └─HELLO bad: REJECT, close        └──── ROOM_LEAVE ────┘
```
`ready` here means "hello'd, no room". `running` has no way out in v1: no `ROOM_LEAVE` and no abandon. A finished/abandoned run is a later task.

| c2s message | new | ready (no room) | in_lobby | running |
|---|---|---|---|---|
| `HELLO` | ✓ | INVALID_STATE | INVALID_STATE | INVALID_STATE |
| `PING` | INVALID_STATE | ✓ | ✓ | ✓ |
| `ROOM_CREATE`, `ROOM_JOIN` | INVALID_STATE | ✓ | ALREADY_IN_ROOM | ALREADY_IN_ROOM |
| `ROOM_LEAVE` | INVALID_STATE | NOT_IN_ROOM | ✓ | INVALID_STATE |
| `READY` | INVALID_STATE | NOT_IN_ROOM | ✓ | INVALID_STATE |
| `GHOST`, `MARK`, `QUICK` | INVALID_STATE | NOT_IN_ROOM | INVALID_STATE | ✓ |
| `MISSION_TRIGGER`, `DOWNED`, `USE_RECALL`, `CLAIM`, `CLAIMS_REQ`, `GIVE`, `DROP_SHARE`, `SYNC_DRINK`, `SYNC_REPORT`, `BOSS_ENGAGE`, `BOSS_HIT`, `TWIN_ACT`, `TERRAIN` | INVALID_STATE | NOT_IN_ROOM | INVALID_STATE | ✓ |

- An illegal message gets an `ERROR` and is not processed. **The connection stays open.**
- A connection that sends no valid `HELLO` within 10 s is closed (status `4002`).
- Lobby rules: the creator is slot 0, the joiner slot 1. `ROOM_STATE` goes to both players after every change. `ROOM_LEAVE` frees the slot (the room is deleted when the last player leaves). `READY` sets `heroClass` and `ready`.
- **Run start:** when both slots are filled, both players are `connected`, and both are `ready` with a `heroClass`, the server sends `RUN_START` to both exactly once (seed and roleSeed from a CSPRNG), then a `ROOM_STATE` with `phase: "running"`.
- A lobby player who disconnects keeps the slot, `connected` becomes false, and `ready` resets to false. An empty lobby room (both disconnected) is deleted after 10 minutes. A `running` room is never deleted by a disconnect: it is persisted and survives a server restart.

## 7. Reconnect and resume

1. The client reconnects and sends `HELLO` with `resumeToken`, the `epoch` of its last `WELCOME`, and in the envelope `ack` = the highest s2c `seq` it received.
2. Unknown or malformed token → `REJECT{RESUME_FAILED}` and close.
3. If the player has another open connection, that one gets `ERROR{SESSION_REPLACED}` and is closed (`4003`). The newest connection wins.
4. The server answers `WELCOME{resumed:true, room, run?}` with the current snapshot. Then:
   - **same epoch:** it replays every reliable event for this player with `seq > ack`, in order, **with their original `seq`** (so they are lower than the `WELCOME`'s `seq`). `replayed` says how many follow, and `complete: true`. If the log (the last 256 events per room) no longer holds all of them, nothing is replayed and `complete: false`.
     The reliable set is: `ROOM_STATE`, `RUN_START`, `ALLY_STATUS` (§5.2); `MISSION_START`, `MISSION_UPDATE`, `MISSION_END` (§5.6); `CLAIMED`, `TRANSFER`, `DROP_SHARE` (§5.7); `ALLY_DOWNED`, `REVIVE`, `DOWNED_EXPIRED` (§5.8); `SYNC_START`, `SYNC_END` (§5.9); `BOSS_HP`, `BOSS_DEAD`, `TWIN_DONE` (§5.10). Replaying the last `MISSION_UPDATE`/`ALLY_STATUS` gives a resumed client the paused state without extra machinery.
   - **other epoch** (the server restarted): no replay, `replayed: 0`, `complete: false`. The snapshot is the truth. The client resets its `ack` baseline to the new epoch.
5. The ally receives `ALLY_STATUS{connected:true}`. When a player disconnects, the ally receives `ALLY_STATUS{connected:false, lastSeen}`.
6. After a server restart, a player of a `running` run is put back into it on resume. The ally shows as `connected:false` until it also resumes.

`paused` (`ALLY_STATUS`) is reserved for the mobile-background rule (architecture §5): v1 servers always send `false`.

## 8. Versioning

- Envelope `v` MUST be `1`. A message with another `v` gets `REJECT{VERSION_MISMATCH}` and the server closes.
- `HELLO.protocol` MUST be `1`, otherwise `REJECT{VERSION_MISMATCH}`.
- `HELLO.gameVersionCode` is the client's `appVersionCode`. When the server's `ALLOWED_VERSIONS` (comma-separated **integers**) is set, the code MUST be in it, otherwise `REJECT{VERSION_MISMATCH, minGameVersionCode: min(ALLOWED_VERSIONS)}`. When it is empty, any code ≥ 1 is accepted.
- **Introducing v2:** add new types/fields under `v: 2` and a new `protocol: 2`. A server that supports v2 keeps accepting v1 connections until `ALLOWED_VERSIONS` drops the old clients. A v1-only server rejects v2 clients with `VERSION_MISMATCH`, so the client can show "update the app". Within v1 the server MAY add optional s2c fields (clients ignore unknown fields, §5.5) but MUST NOT add required ones or change meanings.

## 9. Limits and heartbeat

- **Rate limit** per connection: token bucket, 30 messages/s, burst 60. A message over the limit is dropped and answered with `ERROR{RATE_LIMITED}` (at most one such error per second). If the client stays over the limit for 5 s, the server closes with status `1008` (policy violation).
- **`SYNC_VIEW` cap:** in addition to the bucket, at most 10 `SYNC_VIEW` frames per second from one sender; frames over the cap are dropped **silently** (combat streaming is high-rate and best-effort, so it must not consume the error channel).
- **Heartbeat:** the client sends `PING` every 10 s. The server closes a connection that has sent nothing for 30 s (status `4002`). The client SHOULD treat 30 s without any server message (a `PONG` at least) as a dead connection and reconnect with resume.
- **Strings:** every string field has the format or the length limit in §3.

### 9.1 Close statuses

| Status | When |
|---|---|
| `1000` | normal close |
| `1001` | server shutting down (resume later) |
| `1008` | sustained rate-limit abuse |
| `1009` | frame over 64 KiB |
| `4001` | after `REJECT` |
| `4002` | heartbeat timeout / no `HELLO` within 10 s |
| `4003` | session replaced by a newer connection |

## 10. Example: happy path

```
A → {"v":1,"t":"HELLO","seq":1,"p":{"protocol":1,"gameVersionCode":922,"platform":"desktop"}}
A ← {"v":1,"t":"WELCOME","seq":1,"ack":1,"p":{"playerId":"a1…","resumeToken":"…","epoch":"e1…","serverTime":…,"resumed":false,"replayed":0,"complete":true}}
A → {"v":1,"t":"ROOM_CREATE","seq":2,"p":{}}
A ← ROOM_STATE{code:"K7MZQ4", phase:"lobby", players:[A slot 0]}
B → HELLO … ← WELCOME …
B → {"v":1,"t":"ROOM_JOIN","seq":2,"p":{"code":"K7MZQ4"}}
A,B ← ROOM_STATE{players:[A slot 0, B slot 1]}
A → READY{heroClass:"WARRIOR", ready:true}    A,B ← ROOM_STATE
B → READY{heroClass:"MAGE", ready:true}       A,B ← RUN_START{runId, seed, roleSeed, players}  then ROOM_STATE{phase:"running"}
A → {"v":1,"t":"GHOST","seq":5,"depth":1,"branch":0,"p":{"pos":345,"anim":"run","hpPct":100,"heroClass":"WARRIOR","tier":0}}
B ← {"v":1,"t":"GHOST","seq":7,"from":"a1…","depth":1,"branch":0,"p":{…same…}}
A → {"v":1,"t":"CLAIM","seq":6,"depth":1,"branch":0,"p":{"itemNetId":"1:0:42"}}
A ← {"v":1,"t":"CLAIMED","seq":9,"ack":6,"p":{"itemNetId":"1:0:42","by":"a1…"}}    B ← (same CLAIMED)
A → {"v":1,"t":"MISSION_TRIGGER","seq":7,"depth":3,"branch":0,"p":{"kind":"CELL","trapCell":1207,"roomId":7}}
A,B ← MISSION_START{missionId:"b1…",kind:"CELL",prisoner:"b1…",rescuer:"a1…",depth:3,branch:0,trapCell:1207,roomId:7,keySeed:…,startsAt:…,deadline:…,waveIntervalMs:30000}
```

## 11. Fixtures (the contract)

`docs/coop/protocol/fixtures/<name>.json`, one case per file:

```json
{ "name": "c2s-room-join-ok", "direction": "c2s", "valid": true,
  "message": { "v":1, "t":"ROOM_JOIN", "seq":2, "p":{ "code":"K7MZQ4" } } }
```

| Key | Meaning |
|---|---|
| `name` | equals the file name without `.json` |
| `direction` | `"c2s"` or `"s2c"`: which decoder to use (they differ in §5.5) |
| `valid` | whether a conforming decoder accepts `message` |
| `reason` | invalid only: the decode-error kind (below) |
| `field` | invalid only, OPTIONAL: the dotted path of the offending field (`p.code`, `depth`, `p.players.1.slot`) |
| `ignoredFields` | valid only, OPTIONAL: dotted paths a decoder drops (unknown s2c fields); the re-encoded message equals `message` minus these |
| `note` | OPTIONAL, free text |

`message` is usually a JSON object. For the `not_json` case it is a JSON **string** holding the raw frame text.

**Decode-error kinds** (`reason`): `not_json`, `unsupported_version` (→ `VERSION_MISMATCH`), `unknown_type` (→ `UNKNOWN_TYPE`), `missing_field`, `unknown_field`, `forbidden_field` (a known field not allowed on this type/direction, e.g. `depth` on `ROOM_JOIN`), `wrong_type`, `out_of_range`, `bad_format`, `too_long` (→ all `BAD_MESSAGE`).

A conforming implementation, for every fixture:
- **valid:** decodes it, and re-encoding the decoded message gives JSON that is semantically equal (same keys and values, any key order) to `message` minus `ignoredFields`;
- **invalid:** fails to decode, with the given `reason` (and `field` when given).

Checks that need connection state (seq ordering, the state machine, rate limits) are not decode errors and are not in the fixtures. They are covered by the server tests (`SRV-01`).
