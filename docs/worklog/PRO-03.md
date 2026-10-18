# Worklog: PRO-03 — protocol v1.2: connected worlds (shared mob kills, terrain whitelist, persisted terrain)

## Task and scope
- Task ID: PRO-03 (D-023, game-design §6A.9–§6A.11; task 1 of PRO-03 + SRV-03, SRV-03 stacks on this branch)
- Lane (allowed paths): `docs/coop/protocol/**` (PROTOCOL.md + fixtures), `server/internal/protocol/**`, `core/.../coop/net/**` (`Protocol.java`, `CoopSession.java`), protocol tests (`server/internal/protocol/*_test.go`, `core/src/test/.../coop/net/CoopSessionTest.java`), `docs/worklog/PRO-03.md`, `docs/coop/architecture.md` §3.
- Hot paths granted: the protocol files above (`Protocol.java`, `server/internal/protocol/**`, `docs/coop/protocol/fixtures/**`).
- Branch: `agent/PRO-03-connected-worlds-protocol`, from dev.
- Before the worktree: `make worktree-new` refused because dev had the owner's uncommitted D-023 docs (AI_CONTEXT.md, decisions.md, game-design.md). On the owner's answer ("اعمل commit إنت") I committed exactly those three files on dev as `docs: D-023 connected worlds (game-design §6A.9–§6A.11)`, then created the worktree from it.

## Decisions taken
- **Additive within `v: 1`**: six new types (`MOB_KILLED`, `MOB_DEAD`, `MOB_KILLS_REQ`, `MOB_KILLS`, `TERRAIN_REQ`, `TERRAINS`) and one new optional field (`TERRAIN.persist`). No existing field changed meaning. PROTOCOL.md gets a revision line (v1.0 PRO-01, v1.1 PRO-02, v1.2 PRO-03) and a new §5.11.
- **`mobNetId` = `"<depth>:<branch>:<mobIndex>"`**, exactly the seeded itemNetId rules (depth 1..30, branch 0..9, index 0..9999999999, no leading zeros), and **no runtime form**: respawns/summons/Cell waves are local (§6A.9), so `"<playerId>:<n>"` is `bad_format`.
- **`MOB_KILLED` is floor-scoped** (envelope `depth`+`branch` REQUIRED). The id's floor must equal the envelope floor; the server answers a mismatch with `DENY{invalid_state}` (the same rule as an unseen `CLAIM`, SRV-02). First kill wins → `MOB_DEAD{mobNetId, by}` to both, **reliable**; a later report of a known id (either player) is silently ignored (the two worlds racing on one mob is normal). The kill is recorded even if the ally is offline / elsewhere.
- **`MOB_KILLS_REQ` / `TERRAIN_REQ` name the floor in the payload, envelope floor FORBIDDEN** — the `CLAIMS_REQ` pattern (ask before `Dungeon.depth` changes; the answer echoes the floor). Answers are best-effort (not reliable), like `CLAIMS`. Both lists are capped at **512** (`MaxFloorList` / `MAX_FLOOR_LIST`).
- **`TERRAINS.changes` are objects `{cell, tile}`**, not `SYNC_VIEW`-style flat pairs: clearer, and 512 entries stay far below the 64 KiB frame (a fixture rejects a flat pair as `wrong_type`).
- **Whitelist stays a relay rule, not a decode rule** (as in PRO-02): the decoder accepts any tile 0..255, the server drops tiles outside the list silently. The list (SPD v4.0.2 `Terrain` values, checked against `levels/Terrain.java`, `Hero.java` unlock code, `SkeletonKey.java`, `Level.discover/press`):
  - `DOOR` 5 (closed door; also `LOCKED_DOOR` 10 → door via iron key, `HERO_LKD_DR` 38 → door), `OPEN_DOOR` 6, `EMBERS` 9 (v1.1 set, unchanged);
  - `EMPTY` 1 (`CRYSTAL_DOOR` 31 → empty, what `Hero` and `SkeletonKey` set);
  - `UNLOCKED_EXIT` 22 (`LOCKED_EXIT` 21 → unlocked exit, worn key);
  - `HERO_LKD_DR` 38 (door locked with the Skeleton Key);
  - `INACTIVE_TRAP` 19 (trap triggered: consumed; from `TRAP` 18 or `SECRET_TRAP` 17);
  - `TRAP` 18 (secret trap 17 revealed).
- **Receiver transition rule** (§5.11.2 table, `Protocol.terrainTransition(from, tile)` on the client): the server never sees the map, so a client applies a `TERRAIN`/`TERRAINS` change only if its current tile is one of the row's "from" tiles. That makes the common target tiles (`EMPTY`, `DOOR`) safe: a cell can only move forward along the table, never e.g. wall → floor. LINK-02 should use the helper.
- **`persist:true`** = permanent change: relayed like any `TERRAIN`, **reliable** when persisted (logged + replayed), and stored per floor, one tile per cell, last persisted write wins (lock then unlock with the Skeleton Key ends as `DOOR`). `persist` with `OPEN_DOOR` is relayed but not stored (`IsPersistTile`). `persist:false` = absent (re-encodes as sent; `*bool` in Go). Non-persist door open/close stays relay-only, as in v1.1.
- **`EMBERS` 9 is in the persist set** although the task list doesn't name it: a burned door is permanent, and §6A.6 + "applied on arrival" for a late player only works if it is stored. Flagged here; dropping it is a one-line change (`IsPersistTile`) if the owner disagrees.
- **Reliability is payload-dependent only for `TERRAIN`**: Go `ReliableMessage(p)` / Java `Protocol.reliable(Message)`; `Reliable(t)` stays per-type (adds `MOB_DEAD`). The server currently picks `sendReliable` explicitly per call site, so SRV-03 uses it there.
- **Client callbacks:** the three new s2c types (`MOB_DEAD`, `MOB_KILLS`, `TERRAINS`) are added to `Protocol.event()`, so they reach the existing `CoopSession.Listener.onServerEvent(Message)` (the PRO-02 pattern; no new listener method, so no implementor breaks). Request methods: `mobKilled`, `mobKillsReq`, `terrainReq`, and a `terrain(depth, branch, cell, tile, persist)` overload (the 4-arg `terrain` used by LINK-01 is unchanged).
- `IsDoorTile` / `isDoorTile` stay (v1.1 door set) because `server/internal/lobby/world.go` (SRV-02) and LINK-01 use them; the server still relays door tiles only until SRV-03 switches it to `IsTerrainTile`.

## Changes per commit (subject only — NO SHAs)
1. `PRO-03: protocol v1.2 connected worlds (mob kills, terrain whitelist, persisted terrain)` (PROTOCOL.md §5.11, architecture §3, 46 fixtures, `server/internal/protocol`, `coop/net`, tests). One commit: the gate stamps the exact tree, and the spec, the fixtures and both decoders only pass together.
2. `PRO-03: worklog`

## Verification
Literal outputs are in the chat report.
- Contract: Go `go test ./internal/protocol/` → `contract fixtures exercised: 235 (97 valid, 138 invalid)`; Java `ProtocolContractTest` → `contract fixtures exercised: 235 (97 valid, 138 invalid)`. Was 189 (81/108); +46 (16 valid, 30 invalid). Coverage test: every type in each direction has a valid fixture.
- Go unit tests: `TestTerrainWhitelist` (all 256 tiles against the relay and persist sets; `IsDoorTile` unchanged), `TestReliableMessage`, `TestEncodeConnectedWorlds` (nil list encodes as `null` and is refused — senders use `[]`).
- Java: `CoopSessionTest` — the three new s2c types + persisted `TERRAIN` reach `onServerEvent`; the new requests are valid c2s frames (`*_REQ` with no envelope floor); `persist` only sent when true; whitelist/persist sets over all 256 tiles; the transition table row by row plus negative cases.
- Mutation: `mobNetIDRe = itemNetIDRe` (allow the runtime form) → `--- FAIL: TestContractFixtures/c2s-mob-killed-runtime-id`; file restored (`diff -q` clean).
- `make check` — see the chat report.
- Single-player impact: none (protocol/spec/test files only, no game code or hook).
- Determinism impact: none (no level-gen code).

## Unverified
- Server behaviour (registry, persistence, relay of the new whitelist) — SRV-03, next task.
- The client game side (MOB-01 mints `mobNetId`s and sends kills; LINK-02 sends/applies the new tiles) — not in this lane.

## Follow-ups / out-of-lane findings
- **MOB-01:** mint `mobNetId` for every mob co-op level gen places, in a stable order identical in both worlds (the same way DET-01's `CoopItemIds` numbers items), and keep it across save/load (a `coop_*` bundle key). Mob-held items (Statue weapon, Mimic loot) already lose their itemNetId after reload (AI_CONTEXT follow-up) — the same trap applies to mob ids.
- **LINK-02:** use `Protocol.isTerrainTile`/`terrainTransition`, send `persist:true` for unlocks/lock/consumed/revealed traps/burns, and ask `TERRAIN_REQ` on floor entry.
- `docs/coop/WORKFLOW.md`/`AI_CONTEXT.md`: nothing to change for the gate (fixture paths and test names are the same).

## Open questions
- (none blocking) Is `EMBERS` in the persist set OK? (see Decisions)
