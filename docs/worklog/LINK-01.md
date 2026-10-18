# Worklog: LINK-01 — door mirroring

## Task and scope
- Task ID: LINK-01 (D-021; game-design §6A.6; PROTOCOL.md §5.10 `TERRAIN`)
- Lane (allowed paths): `core/.../coop/links/**` (new, facade `coop.links.CoopLinks`), co-op tests, `docs/worklog/LINK-01.md`, `docs/coop/game-design.md` §6A.6.
- Hot paths granted: one-line `// COOP:` hooks in `levels/features/Door.java` (`enter`, `leave`), the single place a door gets destroyed/burnt (`levels/Level.java` `destroy`, see below), and `coop/CoopConnection.java` (only `CoopLinks.onServerEvent( m )` in the existing `onServerEvent`). Not `Coop.java`, not `coop/net/**`, not `server/**`.
- Branch: `agent/LINK-01-door-mirroring`, from `dev`.

## Design (what the code does)
- **Send** (`CoopLinks.onDoor`, `CoopLinks.onDestroyed`): `TERRAIN{cell, tile}` with the current floor's `depth`+`branch`, only in a co-op run, only while the ally is online on the same floor (GHO-01's `AllyState`, like ITM-01) and the session is `RUNNING`, and only for a regular door: `OPEN_DOOR` (6) from `Door.enter`, `DOOR` (5) from `Door.leave`, `EMBERS` (9) from `Level.destroy` when the cell was `DOOR`/`OPEN_DOOR` before. The send is posted to the GL thread (the hooks run on the actor or loading thread).
- **Who triggers it:** `Door.enter/leave` don't know who moved, so anyone counts: hero, mobs, allies. A mob passing a door in my world opens and closes it in the ally's world too. That matches §6A.6 ("opening/closing a door in your world"). See open question 1.
- **The burn hook: `Level.destroy(pos)`** is the single place a door is burnt. `Fire`, `Inferno`, `Bomb`, `Eye`, `Tengu`, `YogDzewa`, `VaultLaser`, `SuperNovaTracker`, `WandOfFireblast` and `WandOfDisintegration` all call it, and `SewerLevel.destroy` calls `super`. It turns any flammable tile (`DOOR` and `OPEN_DOOR` are `FLAMABLE`) into `EMBERS`. The only other code that writes `EMBERS` is `FireImbue`, and only on `GRASS`. The hook goes after the `if` because it needs the tile from before (`terr`).
- **Receive** (`CoopLinks.onServerEvent`, GL thread): `TERRAIN` only, `from` not us, same `depth`+`branch` as `Dungeon`, a `GameScene` showing (not mid floor switch), cell in range, tile in the §5.10 whitelist. It applies only if the cell is a regular door here too (`DOOR`/`OPEN_DOOR`) and differs. So a locked door, a sealed Cell door, or a door we already burnt never changes, and embers are final. A close (`DOOR`) is skipped when a char (`Actor.findChar`) or a heap is on the cell, the same rule as `Door.leave`. The door stays open, and closes again the next time someone walks out of it. Apply = `Level.set` (cell flags follow) + `GameScene.updateMap(cell)`, then, like vanilla `Door`, `Dungeon.observe()` + the open sound if the hero sees that cell.
- **No echo:** an applied change goes through `Level.set`, never `Door`/`destroy`, so no hook fires. The hooks also ignore anything while `applying` is set. The ghost is not an Actor (GHO-01), so the ally's ghost in the doorway doesn't block a close.
- **Locked / sealed doors are not mirrored:** locked doors never go through `Door.enter/leave` (they aren't passable). Unlocking (`Hero`, `SkeletonKey`), `CRYSTAL_DOOR`, secret doors, and doors forced open by `ElementalBlast` / `WandOfFireblast` / `PotionOfDragonsBreath` / `AscensionChallenge` / `VaultTokenDoor` use `Level.set` directly and aren't sent. The fire that then burns such a door *is* sent.
- **Network:** `TERRAIN` is best-effort (not reliable, PROTOCOL §5.10). The server checks the whitelist and the ally's floor again. Nothing new on the wire, and no new strings.

## Decisions taken
- Mirror door changes by any char, not only the hero (forced by the hook shape: `Door.enter(pos)` has no char; also matches §6A.6). Owner: confirm (open question 1).
- Drop a received `TERRAIN` while no `GameScene` is up. During a floor switch, `Dungeon.level`/`Dungeon.depth` can be mid-change, and the message is best-effort anyway. The door states can then differ until the next change on that door.
- Burning applies even on an occupied cell (only closing is blocked by occupancy).
- **MEC-01 coordination (no code dependency):** the receive side relies on the Cell's sealed doors *not* being plain `DOOR`/`OPEN_DOOR` tiles while sealed (game-design §4.1: "its doors lock, like SPD's locked-room events", i.e. `LOCKED_DOOR` or a co-op tile in 200–255). If MEC-01 seals a door some other way and keeps the tile `DOOR`, the ally's door changes would reach it. Then MEC-01 needs a predicate here (out of my lane to guess).

## Changes per commit (subject only — NO SHAs)
1. `LINK-01: mirror door open/close/burn to the ally's world (TERRAIN)` (facade, the three hooks, `CoopLinksTest`)
2. `LINK-01: document door mirroring (game-design §6A.6)`
3. `LINK-01: add a live two-client door mirroring test`
4. `LINK-01: worklog`

## Verification
Literal outputs are in the final chat report.
- `CoopLinksTest` (6 tests, coop floor 1 of seed 123456789, the real `Door`/`Level` code paths, frames through `Protocol.encode`/`decode`):
  - send: open/close/burn → 3 `TERRAIN` (6, 5, 9) posted to the GL thread;
  - send filter: ally off the floor → nothing (doors still work locally); locked door → nothing; burnt non-door terrain → nothing; a door held open by a heap sends no close;
  - receive + no echo: open/close/burn applied (`losBlocking` follows), redraw called, zero sends/posts; embers are final; after a received open, our own close goes out once;
  - receive filter: own `from`, other depth, other branch, non-door tile, world not ready, locked door here, non-door cell here, another message type;
  - occupied-cell skip: hero in the doorway → stays open; heap in the doorway → stays open; burning still applies;
  - vanilla: `Door.enter/leave/destroy` work as before, no sends, no posts, no ally checks, a received `TERRAIN` changes nothing.
- Mutations (each reverted, `diff -q` against a backup afterwards): M1 no occupancy skip → 1 test fails; M2 no own-`from` filter → 1; M3 no ally-on-floor filter → 1; M4 no current-tile check → 2.
- `CoopLinksLiveTest` (skipped unless `LLPD_SERVER_URL`): two real `CoopClient`+`CoopSession` players against `make server-dev`. They create/join/ready/start, both send a depth-1 ghost, A's `Door.enter` → B gets the relayed `TERRAIN` from A → `CoopLinks.onServerEvent` opens B's door; A's `Door.leave` → B's door closes; A gets no `TERRAIN` back within 1 s. One JVM holds both worlds: the door is reset to B's previous state before B applies.
- `make check` green for each commit (scope java [+docs] + secrets; includes `VanillaGoldensTest`, `CoopGoldensTest`, `CoopDeterminismTest`).
- Single-player impact: none intended. Every hook returns at once outside co-op runs (`Coop.isCoopRun()`), and vanilla `Door`/`destroy` behaviour is unchanged (the vanilla test checks it). `VanillaGoldensTest` is green.
- Determinism impact: none. The hooks draw no RNG and only run on runtime door changes. Level gen never calls `Door.enter/leave` or `Level.destroy`.

## Unverified
- **GUI E2E with two desktop clients: not run by me** (no way to drive two game windows from this session). The live test above covers the server relay + the facade, not rendering, FOV refresh or the sound. Steps for the owner: `make server-dev`; client A `./gradlew desktop:debug`; client B `LLPD_PROFILE=b ./gradlew desktop:debug` (`JAVA_HOME=/usr/lib/jvm/java-25-openjdk`); same room, start, both stay on depth 1; A walks into a door → it opens on B's screen; A walks out → it's closed for both; B stands in a door while A walks through it → B's door stays open.
- The `LIVE` link (`CoopGhosts.ally()` floor check, `GameScene.updateMap`, `Dungeon.observe`, sound) only runs in the real game.
- Android/iOS: not run.

## Follow-ups / out-of-lane findings
- **MEC-01:** keep sealed Cell doors off plain `DOOR`/`OPEN_DOOR` tiles while sealed (see Decisions). Then LINK-01 ignores them on both sides with no extra code.
- **SYN-01 (Super Sync):** if the host world's doors change during a session, `SYNC_VIEW.terrainDiff` covers the drinker. `TERRAIN` is unrelated.
- `architecture.md` (not in lane): mention that `TERRAIN` is sent from `Door.enter/leave` and `Level.destroy`.

## Open questions
1. Should mobs' door traffic be mirrored (current behaviour, matches §6A.6's wording), or only the hero's? Hero-only needs the moving char, which means changing the hook shape at the `Door` call sites (`Char.move`, `Level.occupyCell/pressCell`), not one line in `Door`.
