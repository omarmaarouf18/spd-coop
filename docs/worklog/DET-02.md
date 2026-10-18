# Worklog: DET-02 — per-player loot overlay

## Task and scope
- Task ID: DET-02 (follow-up "DET-01 side effects" in `AI_CONTEXT.md`; D-019, D-021)
- Lane (allowed paths): `core/.../coop/levelgen/overlay/**` (new, facade `CoopOverlay`), `coop/CoopRun.java` (persist the overlay state only), co-op tests (`core/src/test/.../coop/levelgen/overlay/**`), `docs/worklog/DET-02.md`, a short note in `docs/coop/game-design.md` (new §6A.8).
- Hot paths granted: one-line `// COOP:` hooks in `Dungeon.java` (first entry of this player to a co-op floor, next to ITM-01's `switchLevel` hook) and at the specific spots for the shop stock, the Vault Mirror reward and the trinket effects on loot.
- Hooks actually used (2):
  - `Dungeon.switchLevel`: `CoopOverlay.onLevelEntered( level )`, right **before** ITM-01's `CoopItems.onLevelEntered`. Reason: the floor is in place and the actors are initialized, nothing is saved or shown yet (the `saveAll()` right after persists the overlay with the floor). Before ITM-01 so that ITM-01's floor-entry snapshot (stack quantities) sees a converted duplicate artifact instance, and so that an artifact slot the ally already took is removed whatever this world turned it into.
  - `levels/rooms/standard/ImpShopRoom.spawnShop`: `CoopOverlay.onImpShop( itemsToSpawn )`, between `placeShopkeeper` and `placeItems`. Reason: the Imp's shop (depth 20 boss floor, depth 21) is generated during level gen but **placed at runtime** (after the boss, or on a later load), so the only per-player moment for its sand bags / bag is while its stock is still a list.
  - Not needed (done from the floor-entry hook, no upstream edit): normal shops (6/11/16: their stock is already on the floor and FOR_SALE is per world), the Vault Mirror (`VaultMirror.reward` is public and `createReward(cls)` re-runs on its own sub-generator), trinket loot (spawned like the other extras).
  - Each hook file also got its `import` line (AGENTS.md rule 12), as in DET-01/ITM-01.
- Branch: cloud session branch `claude/happy-carson-i356no`, reset to `origin/dev` (`1c0e074`) before any change (it had no own commits). No worktree: this is a cloud container (see "Unverified"). A local `dev` branch was created from `origin/dev` because the gate diffs against `dev`.

## Design (what the code does)
`coop/levelgen/overlay/`:
- `CoopOverlay` (facade). `onLevelEntered(level)`: co-op runs only; the floor key `depth_branch` is added to `OverlayState.floors`; if it was already there, nothing happens (no double spawn after a reload or a revisit). Then, in this order: `Duplicates`, `PlayerShop`, `PlayerLoot`. `onImpShop(stock)`: duplicates + shop stock on the Imp's list.
- **Seeds:** every effect runs on its own pushed generator, `seed(run seed, player, depth, branch, part)` = splitmix64 over FNV-1a of the strings (device-independent). `player` = `CoopRun.playerId` (stable for the run), or `slot<n>` before the first WELCOME. One generator per part, so one effect never shifts another. Level-gen RNG and the floor's own generators are never touched.
- **Local:** every item the overlay adds is `CoopItems.markLocal` (never claimed, never shared, ITM-01). The only exception is the duplicate-artifact conversion (see Decisions).
- `OverlayState` (in `CoopRun`, key `coop_overlay`): `floors` (entered floors), `bags` (bags this player's shops offered). Ordered sets only. A run saved before DET-02 has no key → `legacy`: on the first floor entry after loading, every floor in `Dungeon.generatedLevels` counts as entered (the first `switchLevel` after a load is always the saved floor, so the current floor is included), and only floors generated afterwards get the overlay.
- `PlayerLoot` — vanilla's rules from `RegularLevel.createItems` / `VaultTokensRoom`, run on floors that use `RegularLevel.createItems` (not `LastShopLevel`, `MiningLevel`, `VaultLevel`, which override it without these drops):
  - Dried Rose petals: identified, uncursed rose + `Ghost.Quest.completed()`: `ceil((depth/2 - droppedPetals)/3)` petals, max 11, `rose.droppedPetals++` (as vanilla).
  - Cached Rations: `CACHED_RATIONS` talent, floors 2/4/7 rule, a `SupplyRation` CHEST in a special room (not secret/shop, not EMPTY_SP/WATER/PEDESTAL), `CachedRationsDropped.countUp(2)` (as vanilla).
  - Cracked Spyglass: `(int)(Float + extraLootChance())` hidden `Generator.randomUsingDefaults()` heaps.
  - Mimic Tooth: `ebonyMimicChance()` → an `EbonyMimic` on a loose heap / the exit / a door (vanilla's candidate rules), its prize LOCAL; added to `level.mobs` and `Actor.add` (the floor's actors are already initialized at the hook).
  - Trap Mechanism: the floor's hidden traps (sorted by cell), every 1/chance-th one is revealed (vanilla's painter accumulator, no RNG), `Level.set(…, Terrain.discover(…))` + `trap.reveal()`. **Only top-level classes of `levels.traps`**, so a co-op trap (MEC-01's Binding Trap) or a quest/vault trap is never revealed.
  - Vault Mirror: on a floor with a `VaultMirror` whose reward is still there, `createReward(hero.heroClass)` on this player's generator; the reward is LOCAL. (Level gen made the neutral warrior's.)
  - Drop cells: a copy of `RegularLevel.randomDropCell(roomType)` (not the entrance room, passable, not solid, not the exit, no heap, `canPlaceItem`, no item-destroying trap), on this player's generator, **without reshuffling the level's room list** (upstream's `randomRoom` shuffles `rooms` in place), and never under a character or the hero. High/furrowed grass on a drop cell becomes grass (vanilla does the same at gen).
- `PlayerShop`:
  - Sand bags: identified, uncursed hourglass: `ceil((5 - sandBags) * share)` with 0.20/0.25/0.50/0.80 on 6/11/16/20–21, `hourglass.sandBags += n` (as vanilla). Normal shops: placed on free `EMPTY_SP` cells of the shop room (ShopRoom reserves 4 spare cells for sand bags in every shop). Imp shop: appended to the stock list before placement.
  - Bag: vanilla's `ChooseBag` counts backpack items each bag can hold; the velvet pouch is never sold (`HeroClass.initHero` drops it for every hero), so the candidates are holster/bandolier/scroll holder minus `OverlayState.bags`. The shop's (gen) bag is replaced in this world by the player's best candidate; a tie keeps the shop's bag, other ties go by class name. Each player still sees each bag once. Level gen still decides *which shops* have a bag (6/11/16 on v4.0.2, see test); only *which* bag is per player.
- `Duplicates` (artifact de-dup, see Decisions).

## Decisions taken
- **Artifact duplicates (local-only fix, implemented):** an artifact on the floor (heap, chest, shop, Mimic loot, Imp stock) whose class this player already owns turns, in this world only, into an artifact from this player's **runtime** deck (`Generator.random(ARTIFACT)` on the overlay generator; a ring once the deck is empty, as Generator does). The runtime deck already excludes what this player was given or saw generated (DET-01 merges gen artifacts into it), and up to 20 draws skip owned classes. **The converted item keeps the slot's itemNetId** (copied through `CoopItems.storeId/restoreId`, the public ITM-01 API): the slot stays one shared item, first claim wins, the loser's copy vanishes as for any claimed item; only the content differs per world. Nothing new goes on the wire. This is deliberately not `markLocal`: marking it local would give both players an artifact from one slot. See open question 1.
- **Shop bag ties** keep the shop's bag; otherwise class-name order (vanilla ties follow `HashMap` order, which DET-01 removed).
- **Trinket effects not restored** (they would change the shared floor itself, i.e. what both worlds see/claim): Mossy Clump and Trap Mechanism level feelings (terrain), Mimic Tooth turning chests/treasury/suspicious chests/crystal vault items into mimics, Exotic Crystals in secret lab/library/crystal path rooms, Rat Skull in the crystal vault, Parchment Scrap on the Sad Ghost's reward (generated at gen). Their runtime effects (mob drops, grass, runtime spawns, the Blacksmith's rewards made later, Eye of Newt, etc.) are untouched and still apply. Documented in game-design §6A.8.
- **Guide/lore pages** (also a neutral-profile side effect) are not part of this task's list and stay as they are.
- `CoopRun.java`: one field (`overlay`), one key constant, one `put`, and the restore block with the legacy flag. Nothing else.
- No new strings (nothing player-facing is logged; the extras just appear like vanilla's).

## Changes per commit (subject only — NO SHAs)
1. `DET-02: add the per-player loot overlay on first floor entry and on the Imp's shop stock`
2. `DET-02: document the per-player overlay (game-design §6A.8)`
3. `DET-02: worklog`

## Verification
All runs with `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` (this container has no JDK 25).
- `./gradlew :core:test --tests '*CoopOverlayTest'`: `BUILD SUCCESSFUL in 39s`, `tests="13" skipped="0" failures="0" errors="0"`.
- `CoopOverlayTest` (13):
  - qualifies vs not: petals (no rose → 0; rose but ghost quest not done → 0; both → exactly 1 `DriedRose$Petal` on depth 4, `droppedPetals` 1); Cached Rations (warrior → 0; rogue + talent → 1 `SupplyRation` CHEST on depth 2, counter 2); shop on depth 6 (empty backpack, no hourglass → the gen bag `ScrollHolder` with its shared id, no sand bag; hourglass + 10 potions → 1 LOCAL sand bag, `sandBags` 1, the bag becomes a LOCAL `PotionBandolier`, `OverlayState.bags` records it); each player sees each of the 3 sellable bags once, never the pouch; the Imp's stock list gets 4 sand bags on depth 20 and the bag the potions want, all LOCAL; Vault Mirror on a MAGE run: gen reward `BrokenSeal` → `MagesStaff`, LOCAL; the rogue's starting cloak placed on the floor with id `1:0:900` becomes another artifact/ring with id `1:0:900`, a chalice (not owned) stays; trinkets +3 on depths 2–9 (not 5): ≥7 hidden spyglass heaps, ≥1 Ebony Mimic with LOCAL loot, some but ≤ half of the hidden traps revealed.
  - local only: `theSharedFloorStaysAsLevelGenMadeIt` (rogue with talent, rose + ghost, hourglass, spyglass/tooth/mechanism +3, scrolls; depths 1–8): every shared id is still there (only an owned artifact may change class), no new shared id, terrain changes only SECRET_TRAP→TRAP or trampled grass under a new heap, the only new mobs are Ebony Mimics, and something was added. `nothingHappensOutsideCoopRuns`: vanilla run, both hooks leave the floor/stock as they were (no id on the stock).
  - persistence/seeds: `noDoubleSpawnAcrossSaveAndLoad` (CoopRun and the Level through Bundle text, enter again → still 1 petal, `droppedPetals` 1; entering again in the same session → still 1); `aRunSavedBeforeTheOverlayDoesntGetItOnOldFloors` (no `coop_overlay` → legacy → no petal on an already generated floor, floors 1..4 marked); `spawnsDependOnSeedPlayerAndFloorOnly` (two fresh runs, same player → identical petal cells on 2–8; the seed differs per player, branch and part).
- Mutations (each reverted by copying a backup of the overlay package back; `diff -r` against the backup printed nothing, then `restored`):
  - M1 floor drops not `markLocal`: 6 tests fail (`noDoubleSpawnAcrossSaveAndLoad`, `spawnsDependOnSeedPlayerAndFloorOnly`, `theSharedFloorStaysAsLevelGenMadeIt`, `petalsOnly…`, `trinketsAdd…`, `cachedRations…`).
  - M2 `OverlayState.floors` not saved: `noDoubleSpawnAcrossSaveAndLoad` fails.
  - M3 `Coop.isCoopRun()` check removed from `onLevelEntered`: **not detected** — outside co-op runs `Coop.run()` is already null and the next condition returns. The check is a redundant guard, not an untested path.
  - M4 first-entry check removed: `noDoubleSpawnAcrossSaveAndLoad` and `aRunSavedBeforeTheOverlay…` fail.
- `make check` on the final tree: `== gate: all checks passed (scope: java docs secrets) ==`, `gate stamp written for tree 91ec991e6f9a (scope: java docs secrets)`; java stage `BUILD SUCCESSFUL in 1m 6s` (with the gate's warning `no Android SDK`). JUnit XML summed: `total 149 skipped 1 failures 0 errors 0` (skipped: `LiveServerTest`); suites include `CoopOverlayTest 13`, `CoopDeterminismTest 5`, `CoopHashOrderTest 1`, `CoopGoldensTest 1`, `VanillaGoldensTest 1`, `CoopItemsTest 17`.
- Goldens: `md5sum`: `bfb60761f9326751466f6540552954d5  coop-v4.0.2.json` (DET-01's), `d04a9d2507689d942ab38690b9ece9d4  vanilla-v4.0.2.json` (TST-01's); `git diff --stat dev -- core/src/test/resources/` prints nothing.
- Single-player impact: none intended. Both hooks return at once outside co-op runs (`nothingHappensOutsideCoopRuns`); `VanillaGoldensTest` green, vanilla golden md5 unchanged.
- Determinism impact: none on shared floors. The overlay never runs inside `Dungeon.newLevel` / `Coop.generate` (only in `switchLevel` and the Imp shop's runtime placement), draws only from its own pushed generators, and `CoopDeterminismTest` / `CoopGoldensTest` / `CoopHashOrderTest` are unchanged and green with the unchanged co-op golden. The per-player part is deterministic per (seed, player, floor) and persisted.

## Unverified
- **E2E (`make server-dev` + two clients) not run:** cloud container without a display/Docker session for two desktop clients. Not seen in a running game: petals/rations/spyglass loot appearing, the Ebony Mimic sprite (it's added to `level.mobs` before the scene is built, so GameScene should create its sprite like any mob), the Imp's shop with sand bags, the converted artifact being claimed by the other player.
- The real `Dungeon.switchLevel` → `saveAll()` path is not run in tests: the test calls the facade the way the hook does and round-trips `CoopRun` and the `Level` through Bundle text.
- Vault Mirror: covered on seed 123456789 / 42 only where a vault floor with a mirror is generated (the test fails if none is found).
- Android (ART) / iOS: not run.
- Not run in a git worktree via `make worktree-new` (cloud session branch instead, see Task and scope).

## What can still differ / known limits
- An artifact obtained at runtime **after** entering a floor isn't converted on that floor (the check runs on first entry only).
- A shop whose free cells are all taken can't place all sand bags; the hourglass still counts them (vanilla reserves 4 spare cells, so this shouldn't happen).
- Ebony Mimics and their loot exist in one world only (like respawned mobs); runtime mobs don't get D-021's HT ×1.2 (DET-01 follow-up).
- A run saved before DET-02 doesn't get the overlay on floors it had already generated (by design: nothing spawns retroactively).

## Follow-ups / out-of-lane findings
- MEC-01 (Binding Trap): keep the trap class outside `levels.traps` (in `coop/**`) so Trap Mechanism's local reveal never touches it (`PlayerLoot.upstreamTrap`).
- AI_CONTEXT follow-up "DET-01 side effects" can be closed except guide/lore pages and mob-held ids (ITM-01 already fixed those).

## Open questions
1. **Duplicate artifacts:** the converted artifact keeps the slot's shared itemNetId (one shared slot, contents per world). The alternative is `markLocal` (one line in `Duplicates.keepId`), which lets both players get an artifact from that slot. Keep the shared slot?
2. Shop bag ties keep the gen bag. OK, or should an empty backpack always get a fixed bag?
