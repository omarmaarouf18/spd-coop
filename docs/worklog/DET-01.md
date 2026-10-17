# Worklog: DET-01 — co-op level gen depends only on seed + depth (+ branch)

## Task and scope
- Task ID: DET-01 (D-019; extra scope from the owner mid-task: D-021 `itemNetId` + co-op balance)
- Lane (allowed paths): `core/src/main/java/.../coop/levelgen/**`; in `coop/Coop.java` only the body of `generate(Level)`; `coop/CoopRun.java` (persist the level-gen state only); `core/src/test/**` (new co-op tests/goldens only); `docs/worklog/DET-01.md`; `docs/coop/WORKFLOW.md` §6; `docs/coop/architecture.md` (short co-op level gen note)
- Hot paths granted: one-line `// COOP:` hooks in `levels/rooms/secret/SecretLaboratoryRoom.java`, `levels/rooms/secret/SecretLibraryRoom.java`, `levels/rooms/special/ShopRoom.java` (ChooseBag), `levels/VaultLevel.java`
- Branch: `agent/DET-01-coop-levelgen-determinism`, from `dev`

## Audit: run state that vanilla level gen reads
Traced from `Dungeon.newLevel()` → `Coop.generate` → `level.create()` (`Level.create`, `RegularLevel` rooms/painters/items/mobs, special/secret/quest rooms, `MiningLevel`, `VaultLevel`, boss levels, `Generator`, item `random()`, trinkets, quest `spawn` methods).

| State | Read during gen by | Gameplay changes it? | Co-op decision |
|---|---|---|---|
| `Generator` (`usingFirstDeck`, general deck, per-category `probs`/`using2ndProbs`/`seed`/`dropped`) | `Level.create` (food), `RegularLevel.createItems`, special rooms, shop, quest rewards, mimics/statues | **yes**: mob drops, Ring of Wealth, transmutation, thief, cursed wand, quest windows (`undoDrop`, `generateRewards(false)`), bones (`removeArtifact`) | **level-gen copy** (`LevelGenState`), saved in `CoopRun`; runtime copy put back after gen. Gen → runtime: artifacts gen placed are removed from the runtime deck (uniqueness). Other decks stay independent |
| `Dungeon.LimitedDrops` (all counters) | `Level.create` (STR/SoU/stylus/ench/int stones/catalyst), `SpecialRoom` (LAB_ROOM), `RegularLevel` (LORE_*), `ShopRoom.ChooseBag` (bags) | **yes** for the mob/alchemy counters (SWARM_HP…, COOKING_HP, SLIME_WEP…); gen-only counters aren't changed by gameplay in vanilla | **level-gen copy**; gen's increments are added to the runtime counters (so gameplay sees what gen spent, as in vanilla). Runtime → gen never |
| `SpecialRoom.runSpecials`, `pitNeededDepth`; `SecretRoom.runSecrets`, `regionSecretsThisRun` | `RegularLevel.initRooms` | `SpecialRoom.resetPitRoom` (InterlevelScene `RESET`, never set in v4.0.2) | **level-gen copy** |
| Quests (`Ghost`, `Wandmaker`, `Blacksmith`, `Imp`) | `spawn*` read only `spawned` (+ Wandmaker `type`, per-gen `questRoomSpawned`); rewards drawn from the (gen) Generator | yes, but only `given`/`processed`/`started`/`boss_beaten`/`completed`/reward nulling, which gen never reads | **not swapped**: shared run state whose gen-relevant fields only gen writes. Covered by `HeavyPlay` (all flags set) |
| `Dungeon.hero` belongings | `ShopRoom` (bag choice by backpack contents, Timekeeper's Hourglass sand bags), `RegularLevel` (Dried Rose petals + `Ghost.Quest.completed()`), `Trinket.trinketLevel` (mossy clump, trap mechanism, exotic crystals, mimic tooth, rat skull, petrified seed, parchment scrap, …) | yes | **neutral hero** during gen (`new Hero()`, warrior, nothing held) |
| `Dungeon.hero` talents/buffs | `RegularLevel` Cached Rations (`hasTalent`, `Buff.affect` on the hero) | yes | **neutral hero** |
| `Dungeon.hero.heroClass` | `VaultTokensRoom` → `VaultMirror.createReward(cls)` (own sub-generator) | per player | **neutral hero** (always the warrior's reward; see follow-ups) |
| Profile: `Document` pages, `SPDSettings.intro()`; remains | `EntranceRoom`, `RegularLevel`, `LaboratoryRoom`, `RegularPainter`; `Bones.get` | yes | already neutral (`CoopProfile`, `Bones` hook, NET-01c) |
| `Statistics.amuletObtained` | `RegularLevel.mobLimit` only for depth ≤ 1 (generated before the amulet can exist); other reads are music/visuals/runtime | yes | not swapped (unreachable during gen) |
| `Statistics.spawnersAlive` | written (not read) by `DemonSpawnerRoom` | yes | not swapped: gameplay needs gen's write |
| `Dungeon.challenges`, `mobsToChampion` | everywhere / champion rolls only with the challenge | no / no | co-op has no challenges (NET-01c) |
| `Dungeon.generatedLevels`, `Statistics.deepestFloor`/`qualifiedFor*` | written by `newLevel`, not read by gen | — | untouched |
| `Notes` | `RegularLevel` key records: exploration check (runtime), not gen | — | untouched |
| Potion/scroll identification (`handler` known) | not read by gen (verified: `HeavyPlay` identifies, floors match) | yes | untouched; `CoopFingerprint` is blind to it |
| Gameplay RNG (base generator) | not used: `Level.create` pushes `seedCurDepth()` | yes | untouched |
| `VaultLevel.T2SolveItems`/`T3SolveItems` (static, shuffled in place, never reset or saved) | `findT2/T3SolveItem` | process history (earlier vault floors, a reload) | hook: `CoopLevelGen.stableOrder` before the shuffle |
| `Collections.shuffle(list)` (JDK's own unseeded Random) | `VaultLevel.setupConsumables` ×4 | — | hook: `CoopLevelGen.shuffle` → `Random.shuffle` (seeded) in co-op |
| `HashMap` identity-hash iteration | `SecretLaboratoryRoom`, `SecretLibraryRoom` (`Random.chances(HashMap)`), `ShopRoom.ChooseBag` (ties) | — | hook: `CoopLevelGen.stable(map)` → `LinkedHashMap` sorted by class name in co-op |
| `MissileWeapon.setID` (`new SecureRandom()` per instance, stack identity) | every generated thrown weapon | — | co-op gen derives it from seed + `itemNetId` (darts keep upstream's 0). Found by the A/B test |
| `VaultTreasureRoom.treasuresToSpawn`, `ArmoryRoom.prizeCats`, `Wandmaker.questRoomSpawned` | per floor | — | rebuilt per floor; nothing to do |

## Decisions taken
- **Isolation** (`coop/levelgen/CoopLevelGen`): before `level.create()` the runtime snapshot (`LevelGenState`: Generator + LimitedDrops + special/secret room lists, built with each system's own store/restore) is taken and the level-gen snapshot applied; after it, the advanced level-gen snapshot is saved and the runtime one restored, plus gen → runtime merges (LimitedDrops deltas, used artifacts). `Generator.restoreFromBundle` calls `fullReset()`, which draws RNG; it runs under a throw-away pushed generator so no live stream moves.
- **Persistence**: the level-gen bundle lives in `CoopRun.levelGen` (`coop_levelgen` key, absent = first floor/old save). The first co-op floor initializes it from the run's starting state (right after `Dungeon.init`, which is seed-only and class-independent).
- **Quest branches**: when a main floor with a branch entrance is generated, the branch floor gets a fork of the level-gen state; branch floors never write into the main chain. Measured on v4.0.2: mine and vault floors don't touch Generator/LimitedDrops/room lists at all (probe on the 3 test seeds), so this is a guard against future upstream changes; `branchFloorsDontDependOnWhenTheyAreEntered` checks the property itself.
- **Neutral hero** during gen: `new Hero()` with class `WARRIOR`, no items/talents/buffs/trinkets.
- **Hooks**: helpers return/call the exact upstream object/call outside co-op runs (`stable` returns the same map, `shuffle` calls `Collections.shuffle(list)`, `stableOrder` is a no-op). `VanillaGoldensTest` unchanged and green; vanilla golden md5 unchanged.
- **D-021 co-op balance** (`CoopBalance`, inside co-op gen only): mob HT ×1.2 for gen-placed enemy mobs (non-NPC, HP keeps its ratio; bosses placed at gen included); FOOD deck 5.2/1.3/0; "30% of random rolls → food" is implemented **without a Generator hook** as food cards added to both general decks so food is 30% of `Generator.random()` draws in expectation (vanilla's general deck has no food). The literal per-roll `Random.Float() < 0.3` needs a one-line hook in `items/Generator.java` (not granted) — see open questions. Measured on seed 123456789, main floors 1–26: 61 food items co-op vs 31 vanilla.
- **D-021 itemNetId** (`CoopItemIds`): `depth:branch:genIndex`, numbered after gen: heaps by cell (items in heap order), then mobs by (cell, class) with carried items (Mimic loot, Statue weapon, ArmoredStatue armor). Ids are kept per Item instance (weak map) and in the co-op save as one table per floor; `CoopItemIds.rebind(level, table, …)` re-attaches heap items still on their generated cell after a load (ITM-01 wires the call). Included in the co-op fingerprint.
- **Coop.java**: besides the `generate` body, one `import` line for `CoopLevelGen` (rule 12: imports, not inline qualified names).
- **Co-op goldens**: `core/src/test/resources/goldens/coop-v4.0.2.json`, the 9 vanilla seeds as co-op runs (warrior, plays nothing), `CoopFingerprint` (TST-01 components + full item state, full mob state, net ids; no `isIdentified()`, exact bag classes). Regenerated with `./gradlew :core:test --tests '*CoopGoldensTest' -Dgoldens.regen=true` because `regenGoldens` filters on `VanillaGoldensTest` and `core/build.gradle` is not in this lane.
- **Default-hash check** without touching `core/build.gradle`: `CoopHashOrderTest` forks two JVMs (`java.home`, the test classpath, `--add-opens` only, no `-XX:hashCode=2`) with different allocation warm-ups.

## Changes per commit (subject only — NO SHAs)
1. `DET-01: run co-op level gen on its own state, with a neutral hero and order-stable hooks`
2. `DET-01: add co-op determinism tests and co-op goldens`
3. `DET-01: document co-op level gen (WORKFLOW §6, architecture)`
4. `DET-01: worklog`

## Verification
- `./gradlew :core:test --rerun-tasks` (JDK 17): `BUILD SUCCESSFUL in 24s`, `real 0m24.867s`, 60 tests, 0 failures, 1 skipped (`LiveServerTest`, needs a server). Suites: CoopLevelGenTest 7, CoopBalanceAndIdsTest 6, CoopDeterminismTest 5, CoopHashOrderTest 1, CoopStableIterationTest 3, BackoffTest 5, CoopClientTest 6, CoopSessionTest 17, LiveServerTest 1 (skipped), ProtocolContractTest 2, CoopGoldensTest 1, IsolationTest 1, JsonTest 3, RepeatabilityTest 1, VanillaGoldensTest 1.
- `CoopDeterminismTest.bothPlayersGetIdenticalFloors`: seeds 123456789, 42, `"logic link pd"`: A (warrior, nothing) == B (mage, `HeavyPlay`) on all floors incl. branches 12-1/13-1/17-1/18-1/19-1.
- Control `withoutIsolationHeavyPlayChangesTheFloors`: `floors that differ without isolation: 21 of 28` (e.g. B gets `DriedRose$Petal` heaps on 16-0 and 21-0).
- `saveAndLoadMidRunContinuesIdentically`: save after depth 9 (CoopRun + the systems `Dungeon.saveGame` persists, through Bundle text), dirty every static with another co-op run, reload, continue to 26: identical to the uninterrupted run.
- `CoopHashOrderTest`: `fork 1: [--add-opens=java.base/java.util=ALL-UNNAMED] warmup=0`, `fork 2: … warmup=200000`, `co-op floors identical across pinned JVM, 2 default-hash forks, A and B: 28 floors`, and for the record `vanilla, same seed, fork 1 vs fork 2: DIFFERENT (2 floors)` (upstream's HashMap order, which the co-op hooks remove).
- Goldens: two separate regenerations → same md5 `bfb60761f9326751466f6540552954d5` (252 floors = 9 seeds × 28). Vanilla golden md5 still `d04a9d2507689d942ab38690b9ece9d4` (TST-01's).
- Mutation checks (temporary, each reverted; `diff -q` against a backup afterwards):
  - M1 `stable()` returns the HashMap: `CoopHashOrderTest` (A and B differ with default hashing) + `CoopGoldensTest` fail.
  - M2 `shuffle()` uses `Collections.shuffle`: `bothPlayersGetIdenticalFloors`, `saveAndLoad…`, `CoopHashOrderTest`, `CoopGoldensTest` fail.
  - M3 `stableOrder()` no-op: not reached by the 3 seeds (needs a vault with both a blink stone and an invisibility potion to spawn), so `CoopStableIterationTest.vaultSolveItemsDontDependOnProcessHistory` was added; it fails under M3.
  - M4 no neutral hero: `everyHeroClassGetsTheSameFloors`, `bothPlayers…`, `saveAndLoad…`, `CoopHashOrderTest`, `CoopGoldensTest` fail.
  - M5 no HT ×1.2: `coopMobsHave20PercentMoreHT` (`1-0 Snake HT expected 5 but was 4`) + `CoopGoldensTest` fail.
  - M6 no derived set ids: `everyHeroClass…`, `bothPlayers…`, `saveAndLoad…`, `CoopHashOrderTest`, `CoopGoldensTest` fail.
  - M7 branch floors on the main chain: not detected — on v4.0.2 branch floors don't change the gen state (see decisions).
- `make check`: see the final report (run on the final tree).
- Single-player impact: none intended. Upstream hooks are no-ops / the exact upstream call outside co-op runs; `VanillaGoldensTest`, `IsolationTest`, `RepeatabilityTest`, `CoopLevelGenTest` unchanged and green.
- Determinism impact: vanilla goldens unchanged; new co-op goldens.

## Unverified
- Android (ART) / iOS (RoboVM): not run. The default-hash forks are HotSpot JVMs with default identity hashing, the closest local stand-in; the hooks remove the HashMap/JDK-shuffle dependencies by construction.
- A real `Dungeon.saveGame`/`loadGame` round trip in a running game: the test emulates what those persist (`loadGame` would reconnect a socket via `Coop.restoreFromBundle`). Two real clients playing to depth 2+ was not run.
- `CoopItemIds.rebind` is not called by the game yet (no hook in my lane; ITM-01).

## What can still differ between the players, and why
- **Mob-carried items after a reload** (Mimic loot, Statue gear): ids are bound at gen and in memory; after a save/load only heap items are rebound from the table. A 2-line `Item` bundle hook (`coop_net_id`) would make every id travel with its item.
- **Floors regenerated** by `InterlevelScene.Mode.RESET` would advance the gen state again: unreachable in v4.0.2 (nothing sets that mode).
- **Duplicate artifacts**: runtime → gen never flows, so an artifact a player got from a mob/transmutation can still be generated later (vanilla would not). Gen → runtime is merged.
- **Runtime state**: anything not generated (mob drops, respawns, gameplay RNG, mob HT of runtime-spawned mobs — only gen-placed mobs get ×1.2) differs per world by design.
- Co-op loses on purpose (neutral hero): trinket effects on generated floors (their runtime effects still apply), Timekeeper's Hourglass sand bags in shops, Dried Rose petals, Cached Rations talent drops, inventory-based shop bag choice (now: first remaining bag in class-name order), class-specific Vault Mirror reward (always the warrior's), guide/lore pages (NET-01c).

## Follow-ups / out-of-lane findings
- **ITM-01 needs**: `CoopItemIds.idOf(item)` for claims; a hook after a level loads that calls `CoopItemIds.rebind(level, CoopLevelGen.itemIdTable(run.levelGen, depth, branch), depth, branch)`; ideally an `Item.storeInBundle/restoreFromBundle` hook so ids survive pickup/drop/reload for every item (then the table is only a cross-check). Runtime items use `playerId:counter` (architecture §3.5).
- Vault Mirror: create the reward for the interacting player's class at interaction time in co-op (it already uses its own sub-generator, so no other RNG moves).
- Lost per-player features above: a per-player "personal loot pass" after shared gen (rose petals, cached rations, sand bags), outside the shared fingerprint and without itemNetIds.
- Mob HT ×1.2 for runtime-spawned mobs needs a `Mob` hook (prototype did it in `Mob.onAdd`); out of this lane.
- `core/build.gradle`: add `CoopGoldensTest` to the `regenGoldens` filter and point it at the co-op file too; optionally a test task without `-XX:hashCode=2` (today `CoopHashOrderTest` forks instead).

## Open questions
- **Literal "30% of random rolls → food"**: implemented as deck weight (30% in expectation, no upstream hook). The prototype's literal per-roll check needs one `// COOP:` line in `items/Generator.random()`. Grant it, or keep the deck version?
- **itemNetId persisted *with* the item** (D-021 wording): needs a 2-line hook in `items/Item.java` (store/restore a `coop_net_id` key). Not granted here; the id table in the co-op save is the in-lane substitute. Grant for ITM-01?
