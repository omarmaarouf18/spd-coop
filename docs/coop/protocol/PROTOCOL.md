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
| `from` | playerId | OPTIONAL and **ignored**: the server overwrites it when relaying | REQUIRED on relayed `GHOST`/`MARK`/`QUICK`, absent otherwise |
| `depth` | int 1..30 | REQUIRED on `GHOST`, `MARK`; OPTIONAL on `QUICK`; MUST be absent on every other type | same rule, copied from the sender when relaying |
| `branch` | int 0..9 | REQUIRED together with `depth` (both or neither) | same |
| `p` | object | REQUIRED, the typed payload (`{}` when the type has no fields) | REQUIRED |

`depth` is SPD's `Dungeon.depth`, and `branch` is `Dungeon.branch`. The bounds are sanity limits, not game rules.

### 2.1 `seq` and `ack`

- **c2s `seq`** counts the client's messages **on the current connection**: the first message (`HELLO`) has `seq` 1, and each following message has a strictly larger `seq` (normally +1). A non-increasing `seq` is answered with `ERROR{BAD_MESSAGE}` and the message is not processed.
- **s2c `seq`** counts the server's messages **to this player within the server's `epoch`** (the `epoch` is a random id the server picks at start-up and reports in `WELCOME`). It keeps increasing across reconnects as long as the epoch is the same, so the client can say what it last saw.
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

`MISSION_TRIGGER`, `MISSION_START`, `TASK_DONE`, `MISSION_UPDATE`, `MISSION_END`, `GIVE`, `TRANSFER`, `DENY`, `DOWNED`, `ALLY_DOWNED`, `USE_RECALL`, `REVIVE`, `SYNC_DRINK`, `SYNC_START`, `SYNC_VIEW`, `SYNC_CMD`, `SYNC_REPORT`, `SYNC_END`, `COOP_ROOM_ACT`, `COOP_ROOM_STATE` (architecture §3.4).
A v1 server answers any of them, like any other unknown type, with `ERROR{UNKNOWN_TYPE}`. A v1 client ignores unknown s2c types.

### 5.5 Unknown fields

- **c2s:** the server rejects unknown fields, in the envelope and in payloads (`ERROR{BAD_MESSAGE}`, or `REJECT` for `HELLO`). This catches client bugs (typos) early.
- **s2c:** the client MUST ignore unknown fields. This lets the server add optional fields within v1 without breaking released clients. It MUST still reject wrong types and out-of-range values of known fields.

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
   - **same epoch:** it replays every reliable event (`ROOM_STATE`, `RUN_START`, `ALLY_STATUS`) for this player with `seq > ack`, in order, **with their original `seq`** (so they are lower than the `WELCOME`'s `seq`). `replayed` says how many follow, and `complete: true`. If the log (the last 256 events per room) no longer holds all of them, nothing is replayed and `complete: false`.
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
