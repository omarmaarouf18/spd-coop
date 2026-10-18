# Worklog: MOB-01 — shared monsters in different places

## Task and scope
- Task ID: MOB-01 (D-023, game-design §6A.9; protocol v1.2 `MOB_*` from PRO-03, server registry from SRV-03)
- Lane (allowed paths): `core/.../coop/mobs/**` (new), `core/.../coop/levelgen/**` (only to assign mob ids), co-op tests, `core/src/test/resources/goldens/coop-v4.0.2.json` (justified below), `docs/worklog/MOB-01.md`, `docs/coop/game-design.md` §6A.9 (implementation notes).
- Hot paths granted (task prompt): `actors/mobs/Mob.java` (one death-path line + 2 bundle lines), `CoopConnection.onServerEvent` (one dispatch line), `CoopRun` (one `coop_*` key).
- Extra grants asked for and approved by the owner mid-task:
  - `Dungeon.java`: one floor-entry line after ITM-01's.
  - `actors/buffs/AllyBuff.java`: one line in `affectAndLoot`, so corruption and enthrallment count as kills.
  - `CoopConnection.onRunStart`: one line, so a resume re-asks the floor's kills.
- Branch: `agent/MOB-01-shared-mobs`, from dev (with PRO-03 + SRV-03 merged).

## Decisions taken
- **Ids (`levelgen/CoopMobIds`)**:
  - Assigned in `CoopLevelGen.generate` right after the item ids.
  - Order: `level.mobs` sorted by (cell, class name), the same order `CoopItemIds` uses, so both worlds number identically. `mobIndex` starts at 0.
  - The id lives in a `WeakHashMap<Mob,String>` (`CoopMobs.idOf`) and travels in the mob bundle as `coop_mob_id`, the ITM-01 pattern. No table is kept.
- **Not shared** (no id, local to each world):
  - Bosses and minibosses (BOSS-01 owns them).
  - `NPC`s.
  - The Wandmaker's rot garden (`RotHeart`/`RotLasher`): killing the heart is a per-world quest step.
  - **Every mob on a quest branch floor** (mine, vault): their mobs are the quests.
  - Everything spawned at runtime (respawns, splits, summons, waves, quest spawns, the DET-02 Ebony Mimic): those never pass through level gen.
  - **The quest exclusions are my reading of "NPCs/quest spawns excluded"; see Open questions.**
- **Which mobs move (`mobs/MobPlacement`)**: there is no hook between `build()` (where rooms paint their own mobs) and `createMobs()`, so "placed by createMobs" is decided by where the mob stands:
  - It moves if it is in a `StandardRoom` that isn't the entrance room, the aquarium, the Imp's shop or the suspicious chest room, and it isn't a `Mimic`, `Statue` or `Piranha`.
  - Special, secret and quest rooms aren't standard rooms, so their mobs (statues, sentries, honeypot bees, the rat king, the mass grave, …) keep their cell. `VaultLevel` and non-`RegularLevel` floors (boss floors, …) are skipped.
- **New cells** follow `createMobs`' rules:
  - Standard rooms, weighted by `mobSpawnWeight`.
  - Not within an 8-tile FOV or 8-tile walk of the entrance, and also not in an 8-tile FOV of where the hero arrived (falls, teleports).
  - Passable and not solid, `canPlaceCharacter`, not the exit.
  - Not a door, trap, plant or heap, and no mob/char on the cell. LARGE mobs need `openSpace`.
  - 10 rooms × 30 cells per mob; a mob that finds nothing keeps its generated cell.
- **The relocation generator**:
  - `Random.pushGenerator( mix( mix( Dungeon.seedForDepth(depth,branch) ^ SALT ) ^ fnv1a(playerId) ) )` around the whole pass, mobs taken in id order. The level RNG and any active generator are untouched (tested).
  - playerId is `CoopRun.playerId`, or `"slot<n>"` before the first WELCOME (DET-02's rule).
- **First entry** is tracked by `MobState.entered` in `CoopRun` (`coop_mob_kills`). New positions are saved with the level, so a reload never relocates again. A save from before MOB-01 marks its generated floors as entered (DET-02's legacy rule); its mobs have no ids anyway.
- **Kill (this world)**:
  - The `Mob.die` hook sits right before `super.die(cause)`. A Ghoul's fake death returns before `Mob.die`, so it is not a kill (tested).
  - `AllyBuff.affectAndLoot` covers Wand of Corruption, Siren's Song, Elemental Strike and Ratmogrify's enthrallment (owner's choice).
  - Each kill is reported once (`MobState.dead`) and kept in `MobState.unsent` (saved) until the server's `MOB_DEAD` or `MOB_KILLS` confirms it. It is re-sent on floor entry, resume, and after every `MOB_KILLS`; `MOB_KILLED` is idempotent on the server.
  - A kill this hero earned vanilla XP for (enemy death with the hero alive, or `affectAndLoot`) goes into `MobState.xp`.
- **The ally's kill in our world**:
  - Removal: `HP = 0`, `Actor.remove`, `level.mobs.remove`, a smoke puff only when a GameScene shows the floor, and `sprite.killAndErase`. It never calls `die()`/`destroy()`, so no loot, no death effects, no on-kill procs and no vanilla XP.
  - XP: `hero.lvl <= maxLvl ? EXP : 0` via `hero.earnExp`, the vanilla rule. It is granted for a live `MOB_DEAD` on the current floor, or for a floor not entered yet (`pendingXp`, granted on arrival). It is never granted for a floor already left, and never twice (`xp` set, saved).
  - A `MOB_KILLS` snapshot gives XP for unknown kills on the current floor unless this player has left that floor since entering it (so a resume during the first stay still pays out, and a revisit doesn't).
  - A mob this player turned into an ally is never removed by the ally's report.
  - "Current floor" means `Dungeon.level` is on that depth/branch **and** a GameScene is showing. Events that arrive mid floor switch are applied on the next floor entry.
- **Hidden mimics** are NEUTRAL until they reveal themselves, so "still ours to remove" means "not an ally", not "is an enemy".
- **Resume**: `CoopSession` doesn't expose `WELCOME.complete`, so every WELCOME snapshot into the running run (`onRunStart(fromSnapshot)`) flushes unsent kills and sends `MOB_KILLS_REQ` for the current floor. Applying the answer is idempotent.
- **Goldens**: `CoopFingerprint` gets a new `mobIds` component (id = cell:class per shared mob), and `mobState` strips `coop_mob_id` the same way it strips `coop_net_id`. Regenerated with `./gradlew :core:test --tests '*CoopGoldensTest' -Dgoldens.regen=true`.
  - Before the regen, the test reported exactly `differs in [all]` for all 252 floors: every existing component (map, mobs, items, mobState, netIds, …) is unchanged.
  - Diff: 252 × `all` changed, 252 × `mobIds` added, and the 252 `netIds` lines only gained a trailing comma (value multisets compared: identical).
  - `vanilla-v4.0.2.json` is unchanged.
  - **MEC-02 coordination:** whoever merges second rebases and regenerates `coop-v4.0.2.json`.
- **No new strings.**

## Changes per commit (subject only — NO SHAs)
1. `MOB-01: shared mobs: ids, per-player places, kill sync and XP` (coop/mobs, coop/levelgen/CoopMobIds + CoopLevelGen call, CoopRun key, the hooks in Mob/AllyBuff/Dungeon/CoopConnection, CoopFingerprint `mobIds`, regenerated co-op goldens, CoopMobsTest, CoopMobsLiveTest). One commit: the gate stamps the exact tree, and the ids, the goldens and the fingerprint only pass together.
2. `MOB-01: document shared mobs (game-design §6A.9 implementation notes)`
3. `MOB-01: worklog`

## Verification
Literal outputs are in the chat report.
- `CoopMobsTest`: 18 tests, all green:
  - Ids: the same for WARRIOR and MAGE runs over floors 1–5; floor 1 has `1:0:0..7`; Goo has no id; every id is a valid wire id; the id survives a bundle reload; a runtime Rat has no id and sends nothing.
  - Places: relocation differs between two playerIds with the same ids and classes; the same player gets the same cells again; cells are valid (unique, passable, no trap, not in the entrance FOV); room-anchored mobs keep their cell (floors 1–4); a reloaded floor is not moved again; the active generator is untouched.
  - Kills: a real death sends `MOB_KILLED` once (valid c2s frame, depth 1); an unsent kill survives a save and goes out on resume; corruption counts, and our corrupted ally stays through `MOB_KILLS` and a re-entry; a Ghoul's fake death sends nothing.
  - The ally's kills: on our floor → removed + XP once (repeat `MOB_DEAD` and `MOB_KILLS` give no more); our own kill then the ally's report → no second XP; a floor not entered yet → XP on arrival; a floor we left → removed on revisit without XP; `MOB_KILLS` on entry and after a resume are idempotent; the granted/dead/entered sets survive a save.
  - Vanilla: no ids, no sends, no reaction to events.
- Mutations (each reverted and re-run): no `Mob.die` hook → 2 tests fail; no `AllyBuff` hook → 1; relocation seed without the player → 1; XP on entry without `pendingXp` → 2; double XP on `MOB_DEAD` → 1; removing our own allied mob on entry → 1 (after adding the re-entry assert; the first run let this mutant survive).
- `CoopMobsLiveTest` (skipped unless `LLPD_SERVER_URL`): ran against a local `go run ./cmd/server` on port 18095. A's world kills `1:0:0` → `MOB_DEAD by=A` to both → B's world (same floor, B's places) removes it with +1 XP. Both report `1:0:1` at once → one `MOB_DEAD` each, no DENY. `MOB_KILLS [1:0:0, 1:0:1]` applied in B's world: +1 XP, not again for `1:0:0`.
- `./gradlew :core:test` → `TOTAL suites=33 tests=197 skipped=2 failures=0 errors=0` (includes `VanillaGoldensTest`, `CoopGoldensTest`, `CoopDeterminismTest`, `CoopHashOrderTest`).
- `make check` → see the chat report.
- Single-player impact: none intended.
  - Every hook returns at once outside co-op runs (`Coop.isCoopRun()`); `CoopMobs.restore` only reads a key that co-op saves write.
  - No id is assigned outside `CoopLevelGen.generate`, which only runs for co-op floors.
  - `VanillaGoldensTest` green, vanilla golden file unchanged.
- Determinism impact: level gen is unchanged (all pre-existing components identical). Ids depend only on the generated floor. Relocation runs at runtime on its own pushed generator.

## Unverified
- **Two real desktop clients (GUI)**: not run. I can't drive two game windows from this session; the live test covers the server path and the CoopMobs logic, not rendering. Owner checklist:
  1. `make server-dev`. Client A: `./gradlew desktop:debug`. Client B: `LLPD_PROFILE=b ./gradlew desktop:debug` (`export JAVA_HOME=/usr/lib/jvm/java-25-openjdk`). Same room, start.
  2. Both on depth 1: the same 8 monster types are there, but in different places (compare the two screens).
  3. A kills a rat: it vanishes in B's world with a puff, with no corpse loot, and B's XP bar moves.
  4. A corrupts a mob (Wand of Corruption): it vanishes for B.
  5. B goes down to depth 2 first and kills mobs there. A arrives later: those mobs are missing and A's XP rises on arrival.
  6. A goes back up to depth 1 after B killed something there meanwhile: it's missing, and no XP.
  7. Save and quit, reload: no mob jumps to a new place, and no XP is granted again.
  8. A disconnects (kill the window), B kills a mob on A's floor, A resumes: the mob is gone for A.
- The hero-FOV rule for arrivals away from the entrance (falls) is implemented but not covered by a test.
- Android/iOS: not run.

## Follow-ups / out-of-lane findings
- **Ratmogrify transmogrify** (`Ratmogrify` turning a mob into a TransmogRat, not the enthrall path) and **Elixir of Honeyed Healing** on rats make a shared mob leave or turn ally without `Mob.die`/`affectAndLoot`. They aren't reported, so the mob stays in the ally's world. A hook in those two places, or an alignment watcher, would close it.
- **Kill XP on another floor:** an ally kill on a floor you haven't reached is paid at arrival with your hero level then (vanilla rule at grant time).
- BAL: the D-021 ×1.2 mob HP assumed solo fights (D-023 note); with shared kills, revisit.
- `CoopSession` could expose `WELCOME.complete` (coop/net, PRO lane), to skip the re-ask after a complete resume (harmless today).

## Open questions
- ~~**Quest mobs:** should the rot garden and the mine/vault branch-floor mobs stay per-world (no id)?~~ Answered by the owner: yes, they stay per-world as implemented.
- None open.
