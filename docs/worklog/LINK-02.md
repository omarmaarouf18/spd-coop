# Worklog: LINK-02 — shared locked doors and traps

## Task and scope
- Task ID: LINK-02 (D-023; game-design §6A.10–§6A.11; PROTOCOL.md v1.2 §5.11.2 `TERRAIN.persist`, `TERRAIN_REQ`/`TERRAINS`, whitelist)
- Lane (allowed paths): `core/.../coop/links/**`, co-op tests, `docs/worklog/LINK-02.md`, `docs/coop/game-design.md` §6A.10–§6A.11 (implementation notes).
- Hot paths granted: one `// COOP:` line each (+ import): the hero's unlock path (`Hero.java`), the Skeleton Key lock path, `Trap.trigger` (or `Level.pressCell`), `Level.discover`, `CoopConnection.onServerEvent` (one dispatch line).
- Hooks added (all one line + one import per file):
  - `actors/hero/Hero.java` `onOperateComplete`, inside `if (hasKey)` (iron key → `DOOR`, crystal key → `EMPTY`, worn key → `UNLOCKED_EXIT`, a hero-locked door forced open → `DOOR`): `CoopLinks.onLockChanged( doorCell )`.
  - `items/artifacts/SkeletonKey.java`, after each of its 4 `Level.set` calls in the targeting callbacks (iron door → `DOOR`, hero-locked door → `DOOR`, crystal door → `EMPTY`, lock a door → `HERO_LKD_DR`): `CoopLinks.onLockChanged( target )`. That's 4 lines. I read "the Skeleton Key lock path" as the key's lock/unlock targeting code, because rule 1 says "unlocking with **any** key".
  - `levels/traps/Trap.java` `trigger`, before `activate()`: `CoopLinks.onTrapTriggered( this )`.
  - `levels/Level.java` `discover`, first line: `CoopLinks.onDiscover( this, cell )`. `Level.java` already imported `CoopLinks` (LINK-01).
  - `CoopConnection.onServerEvent`: **no change needed**. LINK-01's `CoopLinks.onServerEvent( m )` already gets every event, `TERRAINS` included.
- Branch: `agent/LINK-02-doors-traps`, worktree `../spd-wt/LINK-02`, from `dev` @ `cc11284` (contains PRO-03, SRV-03, LINK-01, MEC-01).

## Design (what the code does)
Everything is in `coop/links`: `CoopLinks` (LINK-01's facade, extended) and `TerrainOutbox` (new).
- **Sent, `TERRAIN{persist:true}`.** It goes out whether or not the ally is on the floor (the server stores it, PROTOCOL §5.11.2), with the current floor's `depth`+`branch`, and is posted to the GL thread.
  - `onLockChanged(cell)`: the cell's tile **after** the change, if it is `DOOR`/`EMPTY`/`UNLOCKED_EXIT`/`HERO_LKD_DR` (5/1/22/38).
  - `onTrapTriggered(trap)`: `INACTIVE_TRAP` (19), only for a floor trap (`level.traps.get(pos) == trap`; a Reclaim Trap's thrown copy isn't) that this trigger actually used up (the cell is now `INACTIVE_TRAP`).
  - `onDiscover(level, cell)`: `TRAP` (18), when the cell is `SECRET_TRAP` right before `discover` turns it into `TRAP`. A trap going off calls `discover` after `disarm`, so a consumed hidden trap sends only 19.
- **Never sent:**
  - the Binding Trap's trigger: `BindingTrap.trigger` doesn't call `super`, and the hook also skips `instanceof BindingTrap`. Revealing it sends 18, as allowed.
  - a door of the Binding Trap's room while our hero has `CellPrison`: the prisoner's iron key on a sealed door (which MEC-01 locks again) isn't sent.
  - anything while a received change is being applied (`applying`, no echo).
- **Reliable (`TerrainOutbox`).** Changes queue in order. `flush` sends until one fails (`CoopSession.send` returns false when not welcomed). A pending `TERRAIN_REQ` goes out after the changes, so the record we get back already holds them. `flush` runs on every server event, on floor entry and on resume. The queue is in memory (see Open questions 3).
- **Received** (`CoopLinks.onServerEvent`, GL thread):
  - `TERRAIN` without `persist`: unchanged LINK-01 door open/close/burn path, still limited to regular doors.
  - `TERRAIN{persist:true}` (not ours, our floor, world ready) and `TERRAINS` (payload floor == ours, world ready) → `apply(level, cell, tile)`:
    - whitelisted tile and `Protocol.terrainTransition(current, tile)` (forward-only);
    - not a sealed Cell door (`LOCKED_DOOR` + `CellPrison` + listed in a `BindingTrap.doors`);
    - never `INACTIVE_TRAP` on a `BindingTrap`;
    - never lock (`HERO_LKD_DR`) or close (`OPEN_DOOR→DOOR`) a door with a char or heap on it.
  - Then `Level.set` (cell flags follow) and the trap object: consumed → `active=false`, `visible=true`; revealed → `visible=true`. Then `redraw` (LINK-01's: `GameScene.updateMap` + `Dungeon.observe` if the hero sees it).
  - Never through the hooks (`Level.set` only), never `trap.trigger/activate`, never `Notes.remove` (no key used).
  - Idempotent: an applied tile can't apply again (`current == tile`, and the transitions only go forward).
- **Floor entry / resume:** `onLevelEntered(level)` waits (re-posting, at most 600 frames) until a scene is up, then flushes and sends `TERRAIN_REQ` for that floor. `onResumed()` does the same for the current floor. **Neither is called by the game yet**: see Open questions 1–2.

## Decisions taken
- **Traps that stay armed** (`disarmedByActivation = false`: Gateway, Flashing, Gripping) aren't sent as 19. They aren't used up in the stepper's world, so making them inactive in the ally's world would break "a triggered trap is consumed in both worlds" the other way. If hidden, they are revealed (18 via `discover`). I read rule 1's "any cause" as "whatever stepped on it".
- Persisted changes go out with the ally off the floor. Without `persist` (open/close), LINK-01's ally-on-floor rule stays.
- A received lock/close on an occupied doorway is skipped. It lands on the next `TERRAINS` (floor entry), since the record still has it.
- The sealed-door test uses only public MEC-01 state (`CellPrison` buff on the hero, `BindingTrap.doors`). `CellPrison`'s own fields are package-private, so this is a close stand-in, not MEC-01's exact "sealed now" flag.
- `CoopLinks.Link` got two `default` methods (`terrainPersist`, `terrainReq`), so LINK-01's test fake still compiles unchanged.
- Level gen untouched: the hooks only run when a level is the current `Dungeon.level` (`discover`) or on runtime actions.

## Changes per commit (subject only — NO SHAs)
1. `LINK-02: mirror unlocks, Skeleton Key locks and consumed/revealed traps as persisted TERRAIN`
2. `LINK-02: document shared doors and traps (game-design §6A.10-§6A.11)`
3. `LINK-02: worklog`

## Verification
All with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (no JDK 25 in this container). Literal outputs are in the final report.
- `CoopLinksTerrainTest` (11):
  - sending: real `Hero.onOperateComplete` for iron/worn key and a forced hero-locked door → `P 1 0 c 5`, `22`, `5` exactly once each, with the ally away; the key is used locally; no key → nothing. Skeleton Key lock/unlocks via the hook → 38/5/1 once. A consumed trap → 19 once, and triggering again sends nothing; hidden+consumed → 19 only; armed → nothing; hidden+armed → 18. `discover` → 18 once, and a secret door sends nothing.
  - exclusions: the Binding Trap's trigger → nothing; its reveal → 18; an ally's 19 on it is refused. A sealed Cell door: neither `TERRAIN{persist}` 5 nor `TERRAINS` 6 unseals it, and the prisoner's own unlock of it isn't sent.
  - receiving: 6 kinds applied (5 from LOCKED, 1, 22, 38, 19, 18), with flags following, the trap object updated, 0 fires, the iron key still there, 0 sends, empty outbox.
  - forward-only: re-arm, wrong source tile, embers → door, LOCKED → 38, own `from`, other depth/branch, world not ready, lock on a char, a co-op tile (200) → nothing.
  - entry/resume: `onLevelEntered` → `REQ 1 0`; `TERRAINS` applies 3 changes; the same record again → 0 more redraws; another floor's record is ignored; `onResumed` → `REQ 1 0`.
  - offline: 2 changes + 1 request wait (pending 3), then go out in order (`5`, `19`, then `REQ`) on the next server event, once.
  - save/load: the Level through Bundle text keeps tiles and `active`/`visible`.
  - vanilla: unlock, trigger, discover, entry, resume and incoming all leave no sends, no posts, no redraws, and vanilla behaviour holds.
- Mutations (each reverted; `diff -q` against the backup printed nothing, then `restored`): M1 no forward-only check → `onlyForwardChangesApply`; M2 no sealed-door check → the Binding/Cell test; M3 Binding Trap consumable → same test; M4 no flush on server events → the offline test; M5 no co-op gate on lock changes → the vanilla test; M6 trap object not updated → 3 tests. All failed as expected.
- **Live, against the real Go server** (`go build ./cmd/server`, run with `PORT=8080 DB_PATH=<scratch>`; no Docker, so not `make server-dev`), `LLPD_SERVER_URL=ws://localhost:8080/ws ./gradlew :core:test --tests '*CoopLinksTerrainLiveTest' --tests '*CoopLinksLiveTest'`: both 1/1. Output lines: `room WPGGCK, cells 650 651 652`, `B got TERRAINS [{cell=650, tile=5}, {cell=651, tile=19}]`, `B applied TERRAIN cell=652 tile=18 persist`. LINK-01's live test still passes (`B applied TERRAIN cell=350 tile=6` / `tile=5`). The new live test checks: nothing is relayed while B is on depth 2; B's `TERRAIN_REQ` on arrival gets both stored changes; a live reveal; no echo to A.
- `./gradlew :core:test --rerun-tasks` (before the live test file existed): `BUILD SUCCESSFUL in 1m 25s`, JUnit XML summed `total 190 skipped 2 failures 0 errors 0`.
- `make check` on the final tree (`BUILD SUCCESSFUL in 1m 20s`, `== gate: all checks passed (scope: java docs secrets) ==`); JUnit XML summed `total 191 skipped 3 failures 0 errors 0`, skipped suites `CoopLinksLiveTest`, `CoopLinksTerrainLiveTest`, `LiveServerTest` (no `LLPD_SERVER_URL` in the gate).
- Goldens: `git diff --stat dev -- core/src/test/resources/` is empty; md5 `2629e5dd7a2dfc28f5a9f1d43cb2197b coop-v4.0.2.json` (as on `dev`), `d04a9d2507689d942ab38690b9ece9d4 vanilla-v4.0.2.json`.
- Single-player impact: none intended. Every hook returns at once outside co-op runs (vanilla test). The hooks change no vanilla control flow.
- Determinism impact: none. No RNG, no level-gen path. Co-op and vanilla goldens are unchanged.

## Unverified
- **Floor entry and resume are not wired** (Open questions 1–2). In the real game, `TERRAIN_REQ` doesn't go out yet. A player arriving after the ally's unlock gets it only if they are on the floor when it is relayed.
- The crystal-door unlock through the real `Hero.onOperateComplete`: its branch plays `CellEmitter` before the hook, which needs a `GameScene`. That case is covered via the hook only.
- The Skeleton Key callbacks themselves (they need a hero sprite). Covered via the hook.
- GUI E2E with two desktop clients (`make server-dev`, `LLPD_PROFILE=b`): not run (no display/Docker here). Owner checklist:
  1. Both on depth 1. A unlocks an iron door → it's open on B's screen.
  2. A walks through a trap → B sees it inactive, and it didn't fire for B.
  3. A searches and finds a hidden trap → B sees it.
  4. B on depth 2 while A unlocks a door on depth 1; B walks up → the door is unlocked (**needs Open question 1**).
  5. A locks a door with the Skeleton Key → locked for B; B opens it → open for A.
  6. In The Cell, the prisoner's sealed doors never open from the rescuer's actions.
- Android/iOS: not run.

## Follow-ups / out-of-lane findings
- DET-02's Trap Mechanism reveal (`coop/levelgen/overlay/PlayerLoot`) uses `Level.set` + `trap.reveal()`, not `discover`, so it stays per player. Owner: keep (it's a per-player trinket effect, game-design §6A.8), or route it through `CoopLinks`?
- MEC-01: a public `CellPrison.seals(cell)` predicate would replace my stand-in (Decisions).
- `architecture.md` (not in lane): `TERRAIN{persist}` is sent from Hero/SkeletonKey/Trap.trigger/Level.discover; the client keeps an in-memory outbox.

## Open questions (stop point)
1. **Floor entry:** rule 4 needs a call on every floor entry, but no floor-entry spot is granted. Grant one line in `Dungeon.switchLevel` next to ITM-01's: `CoopLinks.onLevelEntered( level ); // COOP:`.
2. **Resume with `complete:false`:** `WELCOME.complete` isn't exposed outside `coop/net` (`CoopSession.welcome` reads only epoch/credentials/time/snapshots). Options: (a) a one-line `CoopLinks.onResumed()` on every transition to `RUNNING` in `CoopConnection`'s `onStateChanged` (harmless: the apply is idempotent); (b) expose `complete` from `CoopSession` (a `coop/net` change). Which one?
3. **Offline changes across an app restart:** the outbox is in memory. Persisting it needs a field in `CoopRun` (not in lane, like ITM-01's `coop_items`). Grant it?
4. **Traps consumed outside `Trap.trigger`:** Flock/Summoning/Distortion chain triggers, Reclaim Trap, Timekeeper's Hourglass freeze and Swiftthistle all call `trap.disarm()` directly. Moving the hook to `Trap.disarm()` (same file, one line, also covers `trigger`) catches them all. Grant?

## Round 2 — owner answers (2026-10-18)
Answers: 1 approved (one line in `Dungeon.switchLevel` next to ITM-01's); 2 option (a), with no double request on the same transition; 3 approved (`coop_terrain_outbox` in `CoopRun`); 4 approved with conditions (one hook for 19 in `Trap.disarm()`, the `Trap.trigger` hook removed).

### Hooks now (final)
- `Dungeon.switchLevel`: `CoopLinks.onLevelEntered( level ); // COOP: (LINK-02) …`, right after ITM-01's line (+ import).
- `coop/CoopConnection.onStateChanged`: `if (state == CoopSession.State.RUNNING) CoopLinks.onResumed(); // LINK-02: …` (one line; this file already imports `CoopLinks`).
- `levels/traps/Trap.java`: the `trigger` hook is removed; `disarm()` has `CoopLinks.onTrapDisarmed( this )` as its **first** line, so it sees the trap before `active = false` and the tile change. `trigger()` calls `disarm()`, so the old trigger path is covered too.
- `coop/CoopRun.java`: field `terrainOutbox` + key `coop_terrain_outbox` (store, and restore when present; an old save has none, so the outbox is empty).
- Unchanged: `Hero.java` (1), `SkeletonKey.java` (4), `Level.discover` (1).

### Design changes
- **19 from `Trap.disarm`**: `onTrapDisarmed(trap)` sends only if all of these hold:
  - a co-op run, not while applying a received change (no echo: the incoming path never calls `disarm`);
  - not a `BindingTrap`: MEC-01 disarms it in both worlds on `MISSION_START`;
  - `Dungeon.level` and `Dungeon.hero` are set and the world is on screen (`link.worldReady()`: never during level gen, a level load or bundle restore);
  - the trap is still `active`, is `Dungeon.level`'s trap at its cell, and its tile is `TRAP`/`SECRET_TRAP`.

  Permanent traps (Gateway/Flashing/Gripping) never call `disarm` when they fire. A Reclaim Trap disarms any trap it takes (a permanent one too), and that counts as used up: it sends 19.
- **In-flight `TERRAIN_REQ`**:
  - `connection` counts transitions to `RUNNING`. It is bumped **synchronously** in `onResumed()`, so a request already queued for the GL thread when the transition happens goes out on this connection.
  - `inFlight` = {depth, branch, connection} of the last request sent. `request()` skips a floor already in flight on the current connection; `TERRAINS` for the floor clears it.
  - So: entering a floor and reaching `RUNNING` together (either order) → one request. A save loaded offline → the entry's request waits in the outbox and the connection sends it once. A request lost with a dropped connection → the reconnect asks again.
- **Outbox persisted**: `TerrainOutbox` is now `Bundlable` (flat `int[]` of depth/branch/cell/tile quadruples, in order) and lives in `CoopRun`. A change leaves only after `terrainPersist` returned true. The pending floor request isn't saved (the next entry or reconnect asks again). Its methods are `synchronized`, because saving can run on the loading thread.

### Changes per commit (round 2, subject only — NO SHAs)
4. `LINK-02: wire TERRAIN_REQ on floor entry and RUNNING, persist the outbox, send 19 from Trap.disarm`
5. `LINK-02: game-design §6A.10-§6A.11 after round 2`
6. `LINK-02: worklog (round 2)`

### Verification (round 2)
JDK 21. Literal outputs are in the final report.
- `CoopLinksTerrainTest` now has 17 tests. New ones:
  - `everyWayATrapIsUsedUpSends19ExactlyOnce`: Timekeeper's Hourglass `timeFreeze.disarmPresses()` and Swiftthistle `TimeBubble.disarmPresses()` each send 19 once without firing; `ReclaimTrap.affectTarget` (by reflection, protected) on a `GatewayTrap` sends 19 once (headless, it stops after the disarm with an NPE in what follows); a second `disarm()` sends nothing.
  - `chainedTrapsSend19Once`: the real `SummoningTrap` and `DistortionTrap` `trigger()` with a test trap on all 8 neighbours. For each, fixed gameplay seeds 1..50 are tried in order until one reaches a chained trap: some rolls hit a sprite/emitter (Wraith, `ScrollOfTeleportation.appear`) before or after the chain, headless. Every trap that went off sent 19 exactly once and fired once.
  - `flocksChainStatementsSend19Once`: **a replica**. `FlockTrap.activate` draws `CellEmitter` (needs a `GameScene`) before its chain, so headless it can't reach it. The test replays Flock's chain statements (`if (disarmedByActivation) disarm(); reveal(); activate();`) on a real level: a consumed trap sends 19 once; a permanent one fires and sends nothing.
  - `disarmSendsOnlyForThePlayedFloor`: a trap object that isn't the floor's, the world not on screen, the Binding Trap's `disarm()` (MISSION_START), an applied 19 from the ally (no echo), and generating another co-op floor all send nothing.
  - `offlineChangesSurviveASaveAndLoad`: 2 changes made offline, `CoopRun` through Bundle text, still 2 pending, nothing sent; `onResumed` online → `5`, `19`, `REQ` in order; the saved outbox is empty afterwards; an old save without the key loads with an empty outbox.
  - `aRequestInFlightIsNotSentTwice`: entry + RUNNING queued together (both orders) → 1 `REQ`; entry offline, then RUNNING → 1 `REQ`; the same floor again while in flight → none; after `TERRAINS` → asks again; after a reconnect → asks again, once.
- Mutations (each reverted; `diff -q` against backups, then `restored`):
  - M8 no on-screen check in `onTrapDisarmed` → `disarmSendsOnlyForThePlayedFloor` fails;
  - M9 Binding Trap not excluded there → same test fails;
  - M10 connection bumped inside the posted runnable → `aRequestInFlightIsNotSentTwice` fails;
  - M11 no in-flight check → that test and `chainedTrapsSend19Once` fail;
  - M12 outbox not stored → `offlineChangesSurviveASaveAndLoad` fails;
  - M7 the `!trap.active` guard removed: **not detected**. A second disarm already sees `INACTIVE_TRAP` and the tile check stops it, so the guard is redundant.
- Live against the Go server (as in round 1): `CoopLinksLiveTest` 1/1 and `CoopLinksTerrainLiveTest` 1/1 (`B got TERRAINS [{cell=650, tile=5}, {cell=651, tile=19}]`, `B applied TERRAIN cell=652 tile=18 persist`). The trap there now goes `trigger` → `disarm` → hook.
- `make check` on the final tree: `== gate: all checks passed (scope: java docs secrets) ==`, `BUILD SUCCESSFUL in 1m 31s`; JUnit XML summed `total 197 skipped 3 failures 0 errors 0` (skipped: the 3 live suites).
- Goldens: `git diff --stat dev -- core/src/test/resources/` is empty; md5 unchanged (`2629e5dd…` co-op, `d04a9d25…` vanilla).

### Unverified (round 2)
- Flock's real chain (replica only, see above). Summoning/Distortion: only up to the headless visual stop. In a real game the activation continues after it.
- `CoopConnection.onStateChanged` → `onResumed` with a real reconnect: only the facade is tested (`onResumed` called directly). The live test doesn't drop a connection.
- GUI E2E with two clients: not run. The owner checklist above stands; step 4 (arrive later, door unlocked) is now wired.

### Open questions
- None. Round-1 questions 1–4 are answered and done. Still open from round 1, not blocking: the follow-ups (DET-02's Trap Mechanism reveal stays per player; a public `CellPrison.seals(cell)` for MEC-01).
