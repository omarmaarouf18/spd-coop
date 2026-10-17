# Worklog: PRO-02 — protocol v1.1: mission/item/revive/sync/boss mechanics (Go + Java + gate)

## Task and scope
- Task ID: PRO-02 (prompt `docs/prompts/PRO-02_SRV-02.md`, task 1; SRV-02 is task 2, stacked worktree)
- Lane: `docs/coop/protocol/**`, `server/internal/protocol/**`, `core/.../coop/net/**` (Protocol.java, ProtocolJson.java, CoopSession.java), `core/src/test/**` (protocol + session tests), `docs/worklog/PRO-02.md`, `docs/coop/architecture.md` §3
- Hot paths granted: `.githooks/pre-push` (protocol stage: Java contract), `scripts/changed-scope.sh` (coop/net/** mapping), `.github/workflows/ci.yml` (Java step in the `protocol-contract` job only)
- Branch: agent/PRO-02-protocol-mechanics, from dev (1de066f75)

## D-021 mid-task scope addition
D-021 (owner docs; arrived while PRO-02 was in progress) grew the protocol. This task implements its protocol part; the game side lands with the client tasks (MEC-*, ITM-01, SYN-01) later:
- Shared floor loot: `CLAIM` / `CLAIMED{itemNetId, by}` / `CLAIMS_REQ{depth,branch}` / `CLAIMS{…}` / `DENY{reason:"taken"}`, first claim wins (game-design §6A.1).
- Same-floor item exchange: `DROP_SHARE`, relayed both directions; the server records the transfer in its per-run item ownership (set up in SRV-02 for the double-claim protections—that rule covers the dropped item too).
- Shared boss HP: `BOSS_ENGAGE` / `BOSS_HIT` / `BOSS_HP` / `BOSS_DEAD`, one pool (round(`ht` × `COOP_BOSS_HP_SCALE`, default 1.8)) that only exists while both players are on the boss floor; without a pool and after death the events get `DENY{no_pool}` / are ignored. HP is a percentage in the protocol; each client maps its own damage formula into it (e.g. `max(1, base/16)`, ×multipliers).
- Twin pressed-levers/plates: `TWIN_ACT` / `TWIN_DONE{groupId}`, both presses inside `COOP_TWIN_WINDOW` (default 2 s). For doors the twin pair carries `objectId` of the far door; `TWIN_DONE` echoes that `objectId` as `groupId` (`valueDep`, also for switches).
- Terrain mirrors: `TERRAIN` / relayed, doors only, same floor only. Tile ids fixed to SPD v4.0.2: `DOOR=5`, `OPEN_DOOR=6`, `EMBERS=9` (the broken/burned door; flagged for the room key task). Other values are dropped server-side. This fixes the D-020 prototype mistake (`COOP_DOOR=39` collided with upstream `CUSTOM_DECO_WTR`).
- Revive via Recall: `USE_RECALL` / `REVIVE{hpPct:25}`. `via:"recall"` = region start (§5.2), `via:"healing"` = ally Medicine 3+, target on the same floor, in place. Both finish `ALLY_DOWNED`; expiry (`DOWNED_EXPIRED`) is the real vanilla death path, never a free revive (game-design §5.2).
- Downed-system extension: the window runs on the server clock and is idempotent (`DOWNED` repeats while the window is open are ignored); `DOWNED_EXPIRED` is reliable, so both clients learn the real death even if a player was offline right then — it never revives for free.

## Decisions taken
- **Two new message classes.** `ServerEvent()` (server-authored s2c types) and `Relayed()` (client→server→client with `from` + floor copied: DROP_SHARE, SYNC_VIEW, SYNC_CMD, TERRAIN, plus the presence three). All `MISSION_*` s2c stays server-authored — a client sending it gets `forbidden_field`. This keeps the check cheap and mirrors what SRV-02 will actually send.
- **`CLAIMS_REQ` deliberately has NO envelope floor** (payload `depth`/`branch` instead): it is a "give me everything for floor F" query that surfaces before the client has set `Dungeon.depth`, the same trick as `ALLY_STATUS`. When answers arrive late, the receiver checks `p.branch`, not the arrival-time `Dungeon.depth`.
- **`itemNetId` format fixed: `"<pool>:<n>"`.** Pool = co-op floor index 1..255 for DET-01 gen loot (assigned by the client that spawns it — no extra wire field), or playerId (16 hex; runtime items). `n` 1..9999999999 with no leading zeros (a trailing-zero pool is legal: `"5:100"`). Regex `^(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9][0-9]?|[0-9a-f]{16}):[1-9][0-9]{0,9}$`.
- **`opaque` blobs bounded at 16384 bytes (`len` in Go, UTF-8 length in Java)**, not runes/symbols — it is a wire limit, not a text limit. The oversized GIVE fixture carries one realistic item bundle that crosses the limit, so a future bundled-item format change still hits the same wall.
- **MISSION payload shapes from the game end** (PROTOCOL.md §5.6): `MISSION_START` carries prisoner and rescuer (two player ids, must differ), `trapCell`, `roomId`, `keySeed`, `startsAt`/`deadline`/`now` (server clock, epoch-relative), `waveIntervalMs`; `missionLevelSeed` stays in the payload (it is the fallback seed for the mission subspace if the run seed doesn't cover it). `MISSION_END` result = `success`/`failed`/`aborted`; clock expiry never fires the server no-op `MISSION_UPDATE`; success = all tasks done before the deadline; deadline reached with tasks open = `failed` + penalty `{regionStartDepth, goldPct}`.
- **The penalty is a percentage, not absolute gold** (`goldPct` 0..100): the server never sees gold amounts (they live in the client), and both clients already know the base.
- **Reliable set is now "shared state"** — ROOM_STATE, RUN_START, ALLY_STATUS, MISSION_*, CLAIMED/TRANSFER/DROP_SHARE, ALLY_DOWNED/REVIVE/DOWNED_EXPIRED, SYNC_START/END, BOSS_HP/BOSS_DEAD, TWIN_DONE. Best-effort (replay-skippable, per-side rates): DENY, CLAIMS, TERRAIN, SYNC_VIEW, SYNC_CMD. §9 adds a SYNC_VIEW cap (≤10 frames/s silently dropped).
- **Boss pool semantics specified for SRV-02:** the pool is *created* when the first `BOSS_ENGAGE` arrives while the co-op status says both players are on the boss floor; a hit without a pool → `DENY{no_pool}`; after `BOSS_DEAD` further ENGAGE/HIT are ignored (no DENY spam). The pool is a plain hp count (`round(ht × COOP_BOSS_HP_SCALE)`, default scale 1.8); `BOSS_HIT.dmg` subtracts from it and each `BOSS_HP.hp` reports the remaining pool (server clamps at 0 → `BOSS_DEAD`).
- **TWIN_ACT semantic detail**: for `kind:"door"` the twin pair is identified by the far door's `objectId`, and `TWIN_DONE{groupId}` echoes that `objectId` (matching how the server groups door presses in SRV-02); `kind:"switch"` carries the optional `groupId` (a switch group id). `TWIN_DONE.groupId` is the same int range (1..MaxSafeInt) as objectId/roomId/bossId.
- **`TERRAIN` carries one changed cell; the schema also accepts a `diff` list** (flat `[cell, tile]` int pairs, ≤512, checked server-side) so a batch variant stays possible without a format change. The client sends as soon as a door changes.
- **§2.1 documents pre-WELCOME numbering**: s2c `seq` is per epoch; `WELCOME` reports the live next seq; replayed events keep their *original* seq (may be lower than `WELCOME.seq`). Additive rule: a server may add `v:1` types and optional `v:1` fields at any time; clients tolerate unknown types (and unknown fields on s2c) at any times so old clients keep working.
- **Fixtures = 186 files** (81 valid, 105 invalid; was 97/31/66). Every new type has a valid fixture plus targeted invalid ones (regex edges, range edges, all conditional pairs, blocking `forbidden_field` cases for envelope floor / `from`, oversized blobs, `CLAIMS` length 257, terrain non-door tiles). `c2s-reserved-type.json` now uses `TASK_DONE` (still reserved after D-021 landed — TASK_DONE, COOP_ROOM_ACT, COOP_ROOM_STATE remain; the reserved list shrank from 14 to 3).
- **Java side mirrors the Go schema with the same helpers** (`chain`, `valueDep`, `prisonersDiffer`, `pairList`, `opaque`, `ITEM_NET_ID` `Pattern`) so both decoders implement the same rules without a shared lib. `CoopSession` gains `onServerEvent(Event)` (default no-op keeps `onPresence` implementors unaffected) + 16 request helpers; everything else (messages shape) is unchanged. No server-authored/relayed split needed client-side — the client validates the same envelope rules, just without writing them.
- **Java contract test wired into the gate/CI/hook (hot paths):** the pre-push hook protocol stage runs both types (had a marked NET-01 TODO comment, now filled); `scripts/changed-scope.sh` maps `coop/net/**` (the package, not just Protocol.java) → protocol+java+server so any future session/protocol change triggers the contract tests too; `ci.yml` adds a Java step in `protocol-contract` (setup-java with the gate-config `jdk`, `./gradlew :core:test --tests '*ProtocolContract*'`).
- architecture.md §3.4 rewritten from "reserved names" to the implemented map (PRO-01 left it stale), §3.5 itemNetId format updated to the pool notation.

## Changes per commit (subject only — NO SHAs)
1. `PRO-02: wire the Java contract test into the gate and CI` (pre-push, changed-scope, ci.yml)
2. `PRO-02: specify protocol v1.1 gameplay (missions, items, revive, sync, boss, terrain)` (PROTOCOL.md, architecture §3)
3. `PRO-02: add v1.1 contract fixtures and mirror them in Go and Java` (fixtures, server/internal/protocol, core coop/net + tests)
4. `PRO-02: worklog`

## Verification
- `GOTOOLCHAIN=auto go test ./internal/protocol/`: 186 fixtures exercised (81 valid, 105 invalid).
- `JAVA_HOME=$HOME/jdk17 ./gradlew :core:test`: full core suite BUILD SUCCESSFUL; ProtocolContractTest output `contract fixtures exercised: 186 (81 valid, 105 invalid)` — identical on both sides.
- `make check` — see the chat report (scope + stage outputs; single-player impact none — pure protocol/spec/test files, no game logic touched; determinism none).
- `actionlint` on the modified workflow — clean.
- Mutation sanity check: `c2s-claim-ok.json` mutated (`n` → 0) makes the Go contract fail (output in the chat report; file restored after).
- Single-player impact: none (no game code). Determinism impact: none (protocol-level only; floor gating trusts the server's seed — same rule as presence).

## Unverified
- The `protocol-contract` GitHub job's new Java step has never run on CI (it runs after the owner pushes).
- TWIN/BOSS frames during real co-op play — the client side that produces them is a future task (MEC-01/02); the contract only proves decode validity, not gameplay balance.
- `make server-probe` style end-to-end message flows — SRV-02, next task.

## Follow-ups / out-of-lane findings
- The old prototype's door tile constant (`COOP_DOOR=39`) is obsolete; the reserved co-op tile range is 200–255 (AGENTS.md rule 14). DET-01 should confirm its mapping list matches §5.10 (doors 5/6/9 only) — the protocol already accepts exactly those three.
- If presence `MARK` ever needs a rate cap like SYNC_VIEW's (§9), it can join the same rate-limiter build (no conflict with this work).
- Out-of-lane one-line fix (reported): `server/internal/lobby/lobby_test.go` used `MISSION_TRIGGER` as its "unknown type" fixture; after PRO-02 that is a real type, so the raw-frame test now uses the still-reserved `TASK_DONE`. Without it the lobby suite cannot compile its expectations against the new schema. Everything else in `internal/lobby` is untouched.
- `docs/coop/WORKFLOW.md`'s protocol stage description may mention both decoders now (minor doc fold, owner).

## Open questions
- (none blocking PRO-02; SRV-02 implements the engine against these types)
