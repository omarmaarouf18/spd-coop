# Worklog: SRV-03 — server: shared mob kill registry and persisted terrain record

## Task and scope
- Task ID: SRV-03 (D-023, PROTOCOL.md §5.11; task 2 of PRO-03 + SRV-03)
- Lane (allowed paths): `server/**` except `server/internal/protocol/**`, `docs/worklog/SRV-03.md`, `docs/coop/architecture.md` §4–§5.
- Hot paths granted: none used (no `go.mod`/`go.sum`, no Makefile, no protocol package change).
- Branch: `agent/SRV-03-connected-worlds-server`, stacked on `agent/PRO-03-connected-worlds-protocol` (`make worktree-new ... BASE=agent/PRO-03-connected-worlds-protocol`).

## Decisions taken
- **Where:** `server/internal/lobby/connected.go`, in the room actor like items (no locks). `game` gets two memory maps, `kills` (mobNetId → first kill) and `terrain` (floor → cell → tile); both are loaded from the store when a run is rebuilt after a restart (`restoreGame` → `loadConnected`, also when no `run_state` was ever saved) and written to the store on every change (like `putItem`), not in the `run_state` JSON.
- **Store schema v4:** `run_kills(run_id, mob_net_id, killer, depth, branch, PK(run_id, mob_net_id))` with `ON CONFLICT DO NOTHING` (the first kill wins in SQL too) and `run_terrain(run_id, depth, branch, cell, tile, PK(run_id, depth, branch, cell))` with last-write-wins upsert.
- **MOB_KILLED:** the id's floor must be the envelope floor, else `DENY{invalid_state}` (the unseen-`CLAIM` rule). Known id → silent ignore (no DENY, no MOB_DEAD). New → stored, `MOB_DEAD{mobNetId, by}` broadcast reliably to both, whatever the ally's floor or connection. Downed/out senders are not special-cased (a kill is a fact of the sender's world).
- **MOB_KILLS / TERRAINS:** sorted (by id / by cell), ≤ 512 (kills truncated with a warn log; the terrain record never exceeds 512 cells per floor), `ack` = the request's seq, `[]` (never `null`) for an untouched floor. Best-effort, like `CLAIMS`.
- **TERRAIN:** `protocol.IsTerrainTile` replaces the v1.1 `IsDoorTile` check (other tiles dropped, debug log). Without `persist` → unchanged v1.1 behaviour: relayed best-effort only to a **connected** ally on the same floor. With `persist:true` → relayed **reliably** (`sendReliableEnv`, with `from` + floor, persist kept) to the ally whose last floor is the sender's, online or not (an ally that dropped on that floor gets it on resume — the DROP_SHARE pattern); and stored unless the tile is `OPEN_DOOR` (`IsPersistTile`). A full floor record (512 cells) still relays a change to a new cell but doesn't store it (warn log); a known cell always updates.
- **Resume without replay** (`complete:false`, e.g. after a restart): the registries are **not** re-sent as events (unbounded lists); the client asks `MOB_KILLS_REQ`/`TERRAIN_REQ` for its floor, which it does on every floor entry anyway. Documented in architecture §5.
- **worldprobe instead of extending missionprobe:** the restart scenario needs to stop and start the server, which missionprobe (a client against an external server) can't. `cmd/worldprobe` builds `./cmd/server`, runs it with a temp `DB_PATH` on port 18093, and restarts it with SIGTERM. So it needs no Docker and no Makefile grant (`go run ./cmd/worldprobe` from `server/`).
- **Out-of-feature fix (own commit):** `TestTwinWindow` (SRV-02) failed once in `make check`. The switch part synced the wrong connection twice, so the server could see A's press before B's release (or before A's own release) and complete group 9 early. Two `collect()` calls added; 3000 runs green afterwards (`-count=300` × 10). No engine change.

## Changes per commit (subject only — NO SHAs)
1. `SRV-03: fix two ordering races in TestTwinWindow`
2. `SRV-03: add store schema v4 (mob kill registry, terrain record)`
3. `SRV-03: add the shared mob kill registry and the persisted terrain record`
4. `SRV-03: add worldprobe (connected worlds, with a server restart)`
5. `SRV-03: README and architecture §4–§5 for the connected worlds`
6. `SRV-03: worklog`

## Verification
Literal outputs are in the chat report.
- `cd server && gofmt -l . ; go vet ./... ; go test -race -count=1 ./...` → gofmt empty, vet clean, `ok` for config, httpapi, lobby, protocol, store.
- `go test -race -count=10 ./internal/lobby/` → `ok`; the new tests alone `-race -count=20` → `ok`.
- New tests: store (`TestMigrateV3ToV4KeepsItems`, `TestKillsAndTerrain`: first kill wins / last terrain write wins across a reopen; `TestMigrateV2ToCurrentKeepsRuns`); lobby `connected_test.go`: `TestMobKillRaceFirstWins` (10 ids, both players at once from two goroutines: exactly one `MOB_DEAD` each, one killer, no DENY; wrong floor → `invalid_state`; a later report ignored), `TestMobKillLateArrival` (ally on another floor still gets `MOB_DEAD`; offline → replayed on resume; `MOB_KILLS` sorted; `[]` for an untouched floor), `TestTerrainPersistAndLateArrival` (every persist tile stored, `OPEN_DOOR` and relay-only changes not, a wall dropped, nothing relayed to another floor, `TERRAINS` on arrival, a lock overwriting an unlock, a reliable persisted relay replayed to an ally that dropped while a best-effort one is lost), `TestTerrainRecordCap`, `TestConnectedWorldsSurviveRestart`.
- `go run ./cmd/worldprobe` → `PASS: all 3 scenarios matched`, exit 0 (kill race; late arrival gets `MOB_KILLS [4:0:2]` + `TERRAINS [{208 5} {431 19}]`; after a SIGTERM restart `TERRAINS [{1022 22}]`, `MOB_KILLS [5:0:1]`, and a repeat kill report is ignored).
- `make check` → `== gate: all checks passed (scope: java server protocol docs secrets) ==` (govulncheck `No vulnerabilities found.`, contract 235 fixtures). The scope includes java/protocol because the branch also carries PRO-03.
- Single-player impact: none (server only). Determinism impact: none.

## Unverified
- With real game clients: MOB-01 and LINK-02 don't exist yet; only the tests and the probe speak these messages.
- A hard crash (kill -9) between a relay and its store write: each change is written synchronously inside the actor before the broadcast for kills, and before the relay for terrain, so a crash loses at most the in-flight request (not run against the binary).
- The Docker image path (`make mission-probe`-style): worldprobe runs the binary directly; the image build itself is covered by the gate's `docker` stage only when `server/Dockerfile`/`deploy/**` change (they didn't).

## What MOB-01 and LINK-02 need (hand-off)
**MOB-01 (shared monsters, §6A.9):**
- Mint a `mobNetId` = `"<depth>:<branch>:<mobIndex>"` for every mob co-op level gen places, in an order identical in both worlds (like DET-01's `CoopItemIds`), `mobIndex` from 0, no leading zeros. Persist it with the mob (`coop_*` bundle key) so it survives save/load. Mobs spawned during play get none.
- On death of a mob with an id (any cause, in a co-op run): `CoopSession.mobKilled(depth, branch, mobNetId)` — the envelope floor must be the mob's floor (else `DENY{invalid_state}`). Sending it twice or racing the ally is fine (ignored).
- On `MOB_DEAD{mobNetId, by}` (via `Listener.onServerEvent`, reliable, also replayed): if `by` is the ally and the floor is the current one, remove the mob from this world without drops (loot stays per-player) and grant the XP; if `by` is us, ignore (already dead here). XP rule for an ally kill on another floor: game-design question (§6A.9 says both get the XP) — decide whether it's granted immediately or on arrival.
- On every floor entry **and after a resume with `complete:false`**: `mobKillsReq(depth, branch)`; on `MOB_KILLS{depth, branch, mobNetIds}` matching the floor, remove those mobs (no XP twice: track which kills already paid out, e.g. in a `coop_*` set).
- The per-player placement overlay (§6A.9) is client-only; the server never sees positions.

**LINK-02 (locked doors + traps, §6A.10–§6A.11):**
- Send `CoopSession.terrain(depth, branch, cell, tile, true)` for permanent changes: iron key `LOCKED_DOOR→DOOR` (5), Skeleton Key unlock `HERO_LKD_DR→DOOR` (5), lock `→HERO_LKD_DR` (38), crystal key `CRYSTAL_DOOR→EMPTY` (1), worn key `LOCKED_EXIT→UNLOCKED_EXIT` (22), trap triggered `→INACTIVE_TRAP` (19, the ally's trap must not fire), trap revealed `SECRET_TRAP→TRAP` (18), and LINK-01's burned door `→EMBERS` (9) if the owner keeps it in the persist set. Hooks: `Hero` unlock code, `SkeletonKey`, `Level.press`/trap `trigger`, `Level.discover`.
- Keep LINK-01's open/close as the 4-arg `terrain(...)` (no persist).
- Apply received `TERRAIN`/`TERRAINS` only via `Protocol.terrainTransition(currentTile, tile)` (forward-only table, PROTOCOL §5.11.2), with `Level.set` + `GameScene.updateMap` and never through the hooks (no echo); a consumed trap must not fire in the receiving world. Never mirror co-op tiles (200–255), the Binding Trap or The Cell's sealed doors.
- On every floor entry and after a resume with `complete:false`: `terrainReq(depth, branch)`, then apply `TERRAINS` for the matching floor.
- `ITM-02` (keys as shared loot) is separate; LINK-02 only needs the unlock to happen in one world.

## Follow-ups / out-of-lane findings
- `make world-probe` (Makefile hot path) could wrap `cd server && go run ./cmd/worldprobe` like `mission-probe`; not added (no grant).
- MOB-01/BAL: the co-op mob HP ×1.2 (D-021) assumed solo fights; D-023 notes it should be revisited now that kills are shared.

## Open questions
- (none blocking) PRO-03's open question stands: is `EMBERS` (burned door) in the persist set OK? The server stores it today.
