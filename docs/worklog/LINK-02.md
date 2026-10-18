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
