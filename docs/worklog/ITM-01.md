# Worklog: ITM-01 — shared floor loot and drop-to-ally

## Task and scope
- Task ID: ITM-01 (D-021; game-design §6A.1–§6A.2; PROTOCOL.md §3, §5.7)
- Lane (allowed paths): `core/.../coop/items/**` (new, facade `coop.items.CoopItems`), co-op tests, `core/src/main/assets/messages/**` (new keys), `docs/worklog/ITM-01.md`, `docs/coop/game-design.md` §6A.1–§6A.2. Read-only use of `coop/levelgen/CoopItemIds`.
- Hot paths granted: one-line `// COOP:` hooks into `CoopItems` in `items/Item.java` (store/restore `itemNetId` in the item bundle, `doDrop`, `onThrow`), `items/Heap.java` (`pickUp`), `Dungeon.java` (after a level is entered/loaded), `coop/CoopConnection.java` (only the listener for CLAIMED/CLAIMS/DENY/DROP_SHARE/TRANSFER). Not `Coop.java`, not `coop/net/**`.
- Branch: `agent/ITM-01-shared-loot`. There is no `dev` branch in this clone, so the worktree was made with `BASE=claude/serene-hypatia-ec3w4b` (= the merged tip, `Merge GHO-01`). Every `dev` in the report below means that base.

## Design (what the code does)
- **Ids travel with items.** `Item.storeInBundle` writes `coop_net_id` (co-op runs only) and `Item.restoreFromBundle` reads it back (only co-op saves carry the key). `CoopItems.idOf(item)` = the restored/minted id, else DET-01's `CoopItemIds.idOf`. This closes the DET-01 gap: Mimic loot and Statue weapons keep their id across a reload (they're saved as item bundles inside the mob). `Item.split`/`duplicate` copy through a bundle, so a split-off part carries the same id. That is harmless: the id only matters for claims/shares, and a shared drop always gets a fresh id.
- **Runtime ids:** an item dropped to the ally gets `<playerId>:<counter>`. The counter lives in `CoopRun.items` (`CoopItemsState`, key `coop_items`).
- **Per-player items (DET-02):** `CoopItems.markLocal(item)` gives the id `"local"`. Such items are never claimed and never shared, and the marker is saved like any id. Items with no id at all (mob drops, runtime loot) are never claimed either. They *can* be dropped to the ally (trade).
- **Claim UX: optimistic pickup + `CLAIM`.** `Heap.pickUp` → `CoopItems.onPickUp`. It counts only when our hero took the item: `hero.curAction` is a `PickUp` on that cell, or the item is now in our belongings (Telekinetic Grab). Traps, wells, Char displacement etc. also go through `Heap.pickUp` and are ignored. The claim is recorded as *pending* (persisted), so offline pickups claim on the next flush after reconnecting.
- **Matching replies without an id in DENY.** `DENY{taken}` carries no `itemNetId` (v1). So every `CLAIM` is followed by a `CLAIMS_REQ` for its floor. The server answers a connection in order, so per claim the client sees `[CLAIMED(by us) | DENY | nothing]` then `CLAIMS`:
  - `DENY` → the oldest bracket without a direct reply → rollback.
  - `CLAIMED{by: us}` → confirmed (`mine`).
  - `CLAIMS` closes the bracket. A claim with no reply that `CLAIMS` lists = the server silently ignored a re-claim of our own (e.g. resent after a reconnect) → confirmed.
  - `CLAIMED{by: ally}` for a pending id → rollback. For anything else → remove it from the current floor (with a smoke poof when the scene is up).
  - If the server ever adds an optional `itemNetId` to DENY (§8 allows it), it's used directly.
  - Requests with no answer after 15 s are assumed lost with their connection and resent. Resending is idempotent server-side.
- **Rollback** (`lose`): the instance with that id leaves the pack. If it's equipped, it's unequipped even if cursed, because it was never ours. If other copies merged into it, only our quantity goes. If it merged into an existing stack, that many go from the stack. For gold, the amount is taken from `Dungeon.gold`. The log says "Your ally got it first: … vanishes from your pack." An item already used before the verdict can't be taken back.
- **Stack quantity:** `Item.merge` zeroes the picked-up instance before `Heap.pickUp` runs. So quantities of shared floor items are remembered on floor entry (heaps + Mimic loot), on our drops/throws, and on received drops.
- **Floor entry** (`Dungeon.switchLevel` → `onLevelEntered`): items the ally took (`allyTaken`, persisted) are removed at once, which also works offline. Then `CLAIMS_REQ` is sent and pending claims are flushed. `CLAIMS` adds any not-ours/not-pending id to `allyTaken` and removes those items from the floor. Runs saved before ITM-01 (no `coop_items` → `legacy`) rebind each loaded floor once from the DET-01 table (`CoopItemIds.rebind`).
- **Drop to ally** (`Item.doDrop` / `Item.onThrow` → `onDropped`): this needs the item lying in a heap on our floor (not fallen into a chasm, not merged into a stack, not consumed on impact), the ally online on the same floor (GHO-01's `AllyState`), our playerId known, and the item not local and not pending. Then the item gets a new runtime id and `DROP_SHARE{cell, itemNetId, item}` goes out with the item as SPD Bundle JSON (≤16 KiB). The receiver spawns it on that cell (if the cell is passable or `avoid`), deduped by id (`received`, persisted, because DROP_SHARE is replayed).
- **Never shared** (`ItemCodec`): bags (and contents), keys, bombs (a lit fuse lives outside the item), the Amulet, `items.quest.*`, `LostBackpack`. The receiver re-checks the same rules. It only instantiates classes in the game package that are `Bundlable`, and checks every `__className` in the frame (enchantments, glyphs, …) before `Bundle.get`, so a bad frame can't build arbitrary classes.
- **Never claimed:** keys (per-world doors; LINK-01 links doors) and shop stock (`Heap.Type.FOR_SALE`: each player has their own gold).
- **TRANSFER** (GIVE's result): an item for us goes into our pack (or at our feet), deduped by id. Nothing in ITM-01 sends GIVE; that's the Item Shrine (ROOM-01).
- **Threads:** the hooks run on the actor/loading threads and only touch `CoopItemsState` under the `CoopItems` lock. Sends and world changes (rollback, heap removal, spawning) run on the GL thread (`Game.runOnRenderThread`; server events already arrive there).

## Decisions taken
- `CoopRun.java` got one field (`items`) plus its store/restore lines. The prompt asks for the counter "persisted in `CoopRun`". `CoopRun` wasn't listed explicitly, so I'm noting it here. The change is minimal and namespaced (`coop_items`), and old saves load (`legacy`).
- `CoopItemIds` is unchanged (read-only): `CoopItems` keeps its own weak id map on top of it.
- Shops and keys are excluded from claims (see design). Owner: confirm or reverse (open question 1).
- Test fingerprint: `CoopFingerprint.state(item)` and `mobState` now strip `coop_net_id`. Ids are already the `netIds` component, and stripping keeps DET-01's golden byte-identical (md5 below), which proves nothing else in co-op gen moved.

## Changes per commit (subject only — NO SHAs)
1. `ITM-01: carry itemNetIds in item bundles and share floor loot and drops between worlds` (code, hooks, messages, tests, and the co-op fingerprint change, which has to land with the bundle hook for the goldens to stay green)
2. `ITM-01: document shared loot and drop-to-ally (game-design §6A.1-§6A.2)`
3. `ITM-01: worklog`

## Verification
See the final chat report for the literal outputs of `make check`, the test summary and `git diff --stat`.
- `CoopItemsTest` (17 tests): id survives bundle/heap reload/throw re-tag; mob-held ids survive a reload (Mimic/Statue on seed 123456789); `CoopRun` state round trip plus the legacy flag; claim race (one winner, loser rolled back, ally DENY'd); DENY without an id matched to its own claim among three; stack and gold rollback (merged darts, two merged seeded potions with one lost, gold); own claim resent after a reconnect confirmed by CLAIMS; offline pickup claimed on reconnect and lost; mob drops, local items and keys never claimed; traps moving heap items don't claim; CLAIMS on floor entry removes taken items (and the local re-application offline); the ally's CLAIMED removes the item from our floor; DROP_SHARE round trip (class, level, enchant, quantity, cursed/known flags) plus codec rejections (bag/key/bomb/foreign class/re-labelled class/oversized); a dropped item spawns in the ally's world exactly once (replay deduped); a received drop is claimable, and an ally claim removes our copy; sharing only when the ally is on the floor and only for shareable items; nothing fires in a vanilla run (no sends, no posts, no bundle key).
- All protocol traffic in the tests goes through `Protocol.encode`/`decode`: c2s DROP_SHARE frames are validated as C2S, server replies as S2C.
- Mutations (each reverted, `diff -q` against a backup afterwards): M1 `storeId` no-op → 2 id tests fail. M2 `lose` no-op → 4 claim tests fail. M3 no `CLAIMS_REQ` bracket → 3 fail. M4 no quantity snapshot → the stack test fails.
- Single-player impact: none intended. Every hook returns at once outside co-op runs. `restoreId` only acts on a key that only co-op saves contain. `VanillaGoldensTest` green, and the vanilla golden md5 is unchanged.
- Determinism impact: none. The hooks don't run inside level gen in a way that draws RNG (`storeId`/`restoreId` only read/write the id map). The co-op golden md5 is unchanged after stripping the new key from the state components.

## Unverified
- **E2E with two clients and a server: not run.** SRV-02 (claim engine) isn't merged here: the only remote branches are `main` and this cloud branch, and `server/` has no CLAIM handling. The scenarios (A picks up → gone for B; A drops a potion → B picks it up → A can't; B enters depth 2 after A looted → items absent) are covered only by the in-JVM fake server, which implements the §5.7 rules **and assumes the server answers one connection's messages in order** (that's what the CLAIMS_REQ bracket relies on).
- Android/iOS: not run.
- The smoke poof and the log lines were not seen in a running game (headless tests have no scene).
- `Hero.actPickUp` is emulated in the tests (collect + `Heap.pickUp` with a `PickUp` action), not run through the real hero action loop.

## Known limits (by design or out of lane)
- A drop arriving when the receiver already left that floor is not spawned (AGENTS.md rule 19: floor-scoped). It stays a normal item in the dropper's world.
- A missile that hits a mob and then drops (or sticks) is placed by `MissileWeapon` without `Item.onThrow`, so it isn't shared. A miss is shared.
- A claim pending when the game crashes before a save is lost locally. If the server recorded it, the next floor entry treats the id as the ally's and removes the item (nobody has it).
- Reconnect detection: there's no hook in `CoopConnection.onStateChanged` (outside the granted listener methods), so pending claims are flushed on the next pickup, floor entry or item event after a reconnect, not at the reconnect itself.
- Telekinetic Grab of a stackable that merges, or of gold, isn't claimed (the item isn't in the pack as an instance and there's no `PickUp` action).

## Follow-ups / out-of-lane findings
- **SRV-02:** please answer each connection's CLAIM/CLAIMS_REQ in order (the client depends on it). Optionally add `itemNetId` to `DENY{taken}` (an optional s2c field is allowed within v1, §8): the client already uses it when present. The server must treat the first CLAIM of a runtime id registered by DROP_SHARE as a normal first claim, also when it comes from the dropper.
- `architecture.md` §3.4/§3.5 (not in lane): document the CLAIM+CLAIMS_REQ bracket, `coop_net_id` in item bundles, and the `local` marker.
- `CoopConnection.SessionListener.onStateChanged`: one line `CoopItems.flush()` on `RUNNING` would send offline claims right at reconnect.
- `CoopConnection.onServerEvent` now exists (routing to `CoopItems`). MEC-00 / LINK-01 will route their types through the same method, so expect a trivial merge.
- **DET-02:** call `CoopItems.markLocal(item)` on every per-player item you spawn (before it can be picked up or saved).

## Open questions
1. Shop stock and keys are per-world (not claimable). Keep, or should shop items be first-buyer-wins (that needs a gold refund on DENY)?
2. Quest items are never shared by drop (each world runs its own quests). OK?
