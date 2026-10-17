> **ARCHIVED** — review of the old v3.3.8 prototype (`../shattered-pixel-dungeon`). Reference only; superseded by the docs in this folder. Note: line numbers/files refer to v3.3.8.

# Co-op Multiplayer ("Logic Link PD") — Foundation Doc

> Status snapshot: **2026-10-16**. Last code activity: commits on 2026-05-05, plus uncommitted work last compiled 2026-06-06.
> Base: Shattered Pixel Dungeon **v3.3.8** (upstream commit `7b8b845a7`).
> Purpose of this doc: get anyone (including future-you) back up to speed, record what exists, what's broken, and what to do next.

---

## 1. TL;DR

- The online feature is a **2-player co-op prototype**. Each player runs their **own full single-player simulation** of the same seeded dungeon. A tiny Node.js WebSocket relay forwards events between them. The ally is shown as a translucent "ghost" sprite.
- Roughly **1,450 lines** were added over upstream, across ~30 files, plus a 90-line server.
- **What works (in principle):** lobby (host/join with room code), shared seed, ghost sprite following the ally, ally compass, door open/close sync, shared pickup of floor items, boss damage sharing, downed state.
- **What's not production-ready:** packets are not scoped to a floor, there are connect races, the revive flow can corrupt save files, co-op content leaks into single-player, and several features are wired up but never reachable (Super Sync, ghost attacks).
- **Verdict:** the architecture choice is sensible for a turn-based roguelike MVP. The implementation is a working spike, not a foundation yet. Before adding features: do **Phase 0 + Phase 1** in §9 (hygiene + protocol), then fix the critical bugs in §7.

---

## 2. Repository layout (relevant parts)

```
shattered-pixel-dungeon/
├── SPD-classes/            # engine (noosa/watabou) — Game.version hard-coded here
├── core/                   # game logic — almost all co-op code lives here
│   └── src/main/java/com/shatteredpixel/shatteredpixeldungeon/
│       ├── network/        # NetworkManager.java, Packet.java   ← NEW
│       ├── windows/WndMultiplayer.java                          ← NEW
│       ├── actors/hero/GhostPlayer.java                         ← NEW
│       ├── sprites/GhostPlayerSprite.java                       ← NEW
│       ├── actors/buffs/{DownedBuff,SuperSyncBuff}.java         ← NEW
│       ├── items/potions/SuperSyncPotion.java                   ← NEW
│       ├── items/scrolls/ScrollOfRecall.java                    ← NEW
│       ├── levels/rooms/special/CoopChallengeRoom.java          ← NEW
│       └── levels/traps/CoopSwitchTrap.java                     ← NEW
├── desktop/                # launcher (null-safety edits, dist assets)
├── server/                 # Node relay server                  ← NEW
│   ├── index.js
│   └── package.json        # deps: ws ^8.16, uuid ^9
└── docs/                   # this file
```

Git: `origin = github.com/omarmaarouf18/game-patch`, branch `main`.

---

## 3. Architecture

### 3.1 Model: "parallel worlds + event relay"

```
 ┌──────────── Client A ────────────┐        ┌──────── Node relay ────────┐        ┌──────────── Client B ────────────┐
 │ full SPD simulation (seed S)     │  ws    │ rooms[roomId] = {host,     │  ws    │ full SPD simulation (seed S)     │
 │ Hero A (real)                    │ ─────▶ │   client, seed}            │ ─────▶ │ Hero B (real)                    │
 │ GhostPlayer = B (visual only)    │ ◀───── │ forwards any unknown type  │ ◀───── │ GhostPlayer = A (visual only)    │
 └──────────────────────────────────┘        │ to the other socket        │        └──────────────────────────────────┘
                                             └────────────────────────────┘
```

- **No authority.** Neither client nor server owns the truth. Each client applies whatever the other sends.
- **Determinism is the glue.** Both clients generate the same levels from the shared seed. Anything that depends on local state (like `isConnected()` during level generation) has to be identical on both sides, or the worlds drift apart.
- **Mobs are not shared.** Each player fights their own copy of the mobs. Only bosses get damage mirrored (`BOSS_HIT`).
- **Floor items are shared:** seeded loot and player drops. Mob drops are deliberately per-player (`Item.Origin.MOB_DROP` is not synced).

This model is the right call for an MVP of a turn-based game, because there's no lockstep and no shared turn clock. Its limits need to be documented and accepted, though (see §8).

### 3.2 Client: `NetworkManager` (singleton)

- Wraps `org.java_websocket` (`Java-WebSocket:1.5.3`, added in `core/build.gradle`).
- `onMessage` runs on the socket thread. It parses JSON (libGDX `Json`), then hops to the render thread with `Gdx.app.postRunnable`. All game mutation happens on the GL thread, which is good.
- It holds co-op session state as loose public fields: `ghostPlayer`, `ghostSprite`, `coopSeed`, `localCoopSwitchTurn`, `roomId`, `allyId`, `localId`.
- It has one big `switch(packet.type)` that holds all gameplay handling. This should be split up (see §9).

### 3.3 Server: `server/index.js`

- `ws` server on hard-coded port **8080**, in-memory `rooms` map.
- Handles `HOST_ROOM`, `JOIN_ROOM`, `START_GAME`. **Any other type is blindly forwarded** to the other socket in the room. It never echoes back to the sender.
- When either player disconnects, the other gets `PLAYER_DISCONNECTED` and **the room is deleted**. There's no reconnect.

### 3.4 Session flow

```
Host: Title → Multiplayer → CREATE ROOM → HOST_ROOM → ROOM_CREATED{roomId} (shown in window)
Join: Title → Multiplayer → enter code → JOIN_ROOM → ROOM_JOINED  /  host gets PLAYER_JOINED
Host auto-sends START_GAME(null) on PLAYER_JOINED → server picks random seed (0..999999)
Both: GAME_STARTED{seed} → coopSeed = seed, reset ghost, curSlot = 1 → HeroSelectScene
HeroSelectScene start: Dungeon.initSeed() then override Dungeon.seed = coopSeed (+ customSeedText)
In game: every Hero.move/attack/die → GHOST_UPDATE; other events as below
```

---

## 4. How to run (dev)

**Server**
```bash
cd server
npm install        # node_modules is currently committed — see §7 hygiene
npm start          # ws://localhost:8080
```

**Client (desktop)**
```bash
./gradlew desktop:debug
```
- Gradle 9.4 wrapper → needs **JDK 17+** to run Gradle (the game bytecode targets Java 11).
- `gradlew` may have lost its executable bit (`chmod +x gradlew`).
- Two instances on one machine will **share the same save folder**, and co-op forces slot 1. Expect them to overwrite each other. Add a profile/base-path override before serious local testing (§9).
- In the Multiplayer window, set the server to `ws://<host-ip>:8080`.

---

## 5. Protocol reference (current, v0 / unversioned)

Envelope: `{ "type": String, "payload": { roomId, seed, id, message, itemClass, itemBundle, senderId, damage, pos, tier } }`
⚠️ Fields get reused for other meanings (`message` = pos, `damage` = tile/turn). This is documented below, and the plan is to replace it in protocol v1.

| Type | Dir | Payload actually used | Effect on receiver |
|---|---|---|---|
| `HOST_ROOM` | C→S | – | server creates room, replies `ROOM_CREATED{roomId}` |
| `JOIN_ROOM` | C→S | `roomId` | `ROOM_JOINED` to joiner, `PLAYER_JOINED{id}` to host, or `ERROR{message}` |
| `START_GAME` | host→S | `seed` (nullable) | server broadcasts `GAME_STARTED{seed}` to both |
| `GAME_STARTED` | S→C | `seed` | store `coopSeed`, reset ghost, slot 1, go to HeroSelect |
| `PLAYER_DISCONNECTED` | S→C | – | `listener.onDisconnected()` (listener is never set → no-op today) |
| `GHOST_UPDATE` | C↔C | `pos`, `id`=HeroClass, `tier`, `message`=state(`run`/`attack`/`die`/`downed`/`idle`), `senderId` | create/move ghost sprite; alpha 1.0 if both are in a `CoopChallengeRoom`, else 0.5 |
| `LEVEL_UPDATE` | C↔C | `pos`, `damage`=terrain id, `senderId` | `level.set` + `updateMap` (*uncommitted*; sent from `Door.enter/leave`) |
| `CLAIM_ITEM` | C↔C | `message`=pos | remove the **first** item from the heap at pos |
| `DROP_ITEM` | C↔C | `message`=pos, `itemClass`, `itemBundle` (Bundle JSON) | reflect + restore the item, drop it at pos |
| `BOSS_HIT` | C↔C | `message`=boss simple class name, `damage` | `mob.damage(dmg, "NETWORK")` on the first matching mob |
| `PLAYER_AT_DOOR` | C↔C | `pos` | if the local hero is adjacent → set `OPEN_DOOR` locally |
| `COOP_SWITCH` | C↔C | `pos`, `damage`=sender's `Statistics.duration` | if \|local−ally\| ≤ 3 → unlock `LOCKED_CHEST` in the co-op room |
| `USE_RECALL` | C↔C | – | if downed: revive at HT/4, travel to "merchant floor", reply `PLAYER_REVIVED` |
| `PLAYER_REVIVED` | C↔C | – | log message |
| `ACTIVATE_SUPER_SYNC` / `DEACTIVATE_SUPER_SYNC` | C↔C | – | ghost `superSyncActive`, alpha, particle fx |
| `GHOST_ATTACK` | C↔C | `pos` | damage the local mob at pos (**nobody sends this**) |

---

## 6. Feature inventory

| Feature | Where | Status |
|---|---|---|
| Lobby window (server URL, room code, host/join) | `WndMultiplayer`, `TitleScene` | 🟡 works, but has a connect race (§7 C2) |
| Shared seed | `NetworkManager.GAME_STARTED`, `HeroSelectScene` | 🟢 uncommitted fix replaces the old `SPDSettings.customSeed` approach, which was persistent and bad |
| Ghost ally sprite | `GhostPlayer`, `GhostPlayerSprite`, `GameScene.addGhostSprite` | 🟡 visible; lags one step behind (§7 H6); not floor-aware |
| Ally compass (cyan) | `StatusPane`, `Compass.setTarget` | 🟡 points at ally pos even when the ally is on another floor |
| Door open/close sync | `Door`, `LEVEL_UPDATE` | 🟡 uncommitted; not floor-aware |
| Shared floor loot / drops | `Item.Origin`, `Heap.pickUp`, `Item.doDrop/onThrow` | 🟡 race-prone; removes "first" item, not a specific one |
| Boss damage sharing | `Mob.damage`, `BOSS_HIT` | 🟡 naive; no phase/HP reconciliation |
| Downed / revive | `Hero.die`, `DownedBuff`, `ScrollOfRecall`, `PotionOfHealing` | 🔴 timeout never kills; revive travel is unsafe |
| Scroll of Recall spawn | `RegularLevel.createItems` (floor 2–4 chest, co-op only) | 🟢 |
| Super Sync potion | `SuperSyncPotion`, `SuperSyncBuff`, `GhostPlayer.act` | 🔴 never spawns; ghost not in actor loop; dead code |
| Co-op challenge room (2 switches, co-op doors, artifact chest) | `CoopChallengeRoom`, `CoopSwitchTrap`, `Terrain.COOP_DOOR` | 🔴 added to **every** level incl. single-player; switch timing compares unrelated clocks; door tile has no visual |
| Co-op balance | `Mob` HP ×1.2, `Generator` 30% food, FOOD probs ↑ | 🟠 FOOD probs change applies to single-player too |
| Rebrand ("LOGIC LINK PD", "ONLINE BETA") | `TitleScene`, `Game.version` | 🟢 cosmetic |
| Desktop launcher null-safety | `DesktopLauncher` | 🟡 versionCode fallback = 1 is dangerous (§7 H8) |

---

## 7. Known issues (prioritized)

### 🔴 Critical — must fix before building more features

**C1. Packets are not scoped to a floor.** `CLAIM_ITEM`, `DROP_ITEM`, `LEVEL_UPDATE`, `BOSS_HIT`, `PLAYER_AT_DOOR`, `GHOST_UPDATE` and `COOP_SWITCH` carry no `depth`/`branch`. If the players are on different floors, A picking up an item at cell 512 deletes whatever sits at cell 512 on B's floor, doors flip on the wrong map, and so on. **Fix:** put `depth` + `branch` in the envelope. Receivers ignore (or queue) packets for a different floor. The ghost and compass should hide when the ally is on another floor.

**C2. Connect race in the lobby.** `WndMultiplayer.connectAndAction` calls `connect()` (async) and then `hostRoom()`/`joinRoom()` right away. `send()` silently drops packets while the socket isn't open, so the first click usually does nothing, and the status still says "Room created!". **Fix:** queue outgoing packets until `onOpen`, or trigger host/join from `onOpen`. Show real status from server replies.

**C3. Revive (`USE_RECALL`) can corrupt saves or crash.** The handler sets `Dungeon.depth = merchantFloor` and then switches to `InterlevelScene` with `Mode.DESCEND`. `descend()` calls `Dungeon.saveAll()`, which now saves the *current* level under the *merchant floor's* depth. It then reads `curTransition.destDepth`, which is stale or null (NPE). **Fix:** use the existing `InterlevelScene.Mode.RETURN` with `returnDepth`/`returnBranch`/`returnPos`, and don't touch `Dungeon.depth` yourself.

**C4. Downed state never resolves.** When `DownedBuff` expires, `detach()` just clears `isDowned`. The hero gets up at 1 HP instead of dying. Also, `Hero.die()` sends `GHOST_UPDATE "die"` *before* the Ankh/downed checks, so the ally sees a death animation even when the player is saved. `"downed"` itself is not handled on the receiver. **Fix:** on timeout call the real death path (`Hero.reallyDie`). Send `die` only from the real death path, and add `downed` visuals.

**C5. Co-op content leaks into single-player.** `RegularLevel` adds `CoopChallengeRoom` to *every* level unconditionally, and `Generator.FOOD.defaultProbs` was changed globally. This changes vanilla seeds and gameplay, and the room is unsolvable alone (its artifact is locked forever). **Fix:** gate all co-op levelgen and balance on a persisted "this run is co-op" flag, not on `isConnected()` (see C6).

**C6. Gameplay is gated on `isConnected()` (live socket state).** `Mob` HP, `Generator.random`, `RegularLevel.createItems` and the downed logic all check whether a socket is open *right now*. Disconnect mid-run, or load a co-op save offline, and levelgen and rules change: the worlds diverge, and saved co-op runs behave as solo. **Fix:** add `Dungeon.coop` (or a `CoopSession`), saved in the game bundle, and use it for rules. Use `isConnected()` only for the network.

**C7. Co-op overwrites save slot 1.** `GAME_STARTED` forces `GamesInProgress.curSlot = 1`, silently replacing the player's solo run in slot 1. **Fix:** use `GamesInProgress.firstEmpty()`, or a dedicated co-op slot range.

### 🟠 High

**H1. Ghost is not an actor.** `GhostPlayer` is never `Actor.add`-ed, so `act()` (Super Sync auto-attack) never runs, `Actor.findChar(pos)` never returns it, and throwing a Healing potion at the ally (the `PotionOfHealing → USE_RECALL` branch) can't trigger. Decide: either the ghost is purely visual (delete that code), or register it as a non-blocking actor.

**H2. Super Sync is unreachable.** `SuperSyncPotion` isn't spawned anywhere, `GHOST_ATTACK` is never sent, and `SuperSyncBuff.left` isn't saved in the bundle.

**H3. Co-op switch timing is meaningless.** It compares `Statistics.duration` from two independent simulations, and those clocks drift apart freely. **Fix:** have the server timestamp switch presses (wall-clock window, e.g. 3s), or use a "both standing on a switch right now" state that both sides confirm.

**H4. Co-op door opens on one side only.** `PLAYER_AT_DOOR` opens the door on the receiver's map only. The sender stays blocked, and nothing is sent back. It should send `LEVEL_UPDATE` (floor-scoped) both ways.

**H5. Shared loot races and duplication.** `CLAIM_ITEM` removes `items.removeFirst()`, not the specific item. If both players pick up at the same time, both keep it. Thrown items (`onThrow`) are mirrored as drops, so check how `MissileWeapon` handles this (stuck/dropped ammo can be duplicated). **Fix:** give every shared item a stable network ID (seeded items: depth + generation index; drops: sender UUID + counter). Make the claim authoritative: first claim wins, decided by the host or the server.

**H6. Ghost lags one tile behind.** `Hero.move` calls `sendGhostUpdate("run")` *before* `super.move(step)`, so it sends the old `pos`. Send after the move, or send `step` explicitly.

**H7. Boss sync is naive.** It matches the boss by class name, sends every hit (even ones the local boss is invulnerable to, because it's sent before the invulnerability check), and doesn't reconcile HP or phase. Boss state machines (Goo, DM-300, Dwarf King…) can desync. Short term: send `HP after damage` and clamp. Long term: one side owns boss HP.

**H8. `versionCode` fallback = 1.** If the launcher can't read the manifest or system props, `Game.versionCode = 1`. Saves are then written with version 1 and later rejected as `< v2_5_4` (unloadable). Use the real `appVersionCode` from `build.gradle` (or `ShatteredPixelDungeon.v3_3_8`) as the fallback.

**H9. `Terrain.COOP_DOOR` (39) has no tile visuals.** It's missing from `DungeonTileSheet` (directVisuals, flat visuals, door tiles, raised walls, chasm stitch), so it renders wrong. Its flags also mix `PASSABLE | SOLID`. Either map it to the locked-door visuals, or reuse `LOCKED_DOOR` with a co-op marker.

### 🟡 Medium / tech debt

- **Server:** no heartbeat/ping, no reconnect grace period, no protocol/game version check, no message validation or size limit, port hard-coded, plain `ws://` (Android blocks cleartext unless allowed, and production needs `wss://`). The host auto-starts the game as soon as someone joins, so there's no ready check.
- **Self-echo filter** (`senderId == localId`) is unnecessary, because the server never echoes. It's harmless, but it gives a false sense of safety.
- **`NetworkListener` is never set**, so `onDisconnected`, `onError` etc. do nothing. Disconnects are invisible to the player.
- **Hard-coded English strings** in new items, buffs, windows and logs instead of `Messages`/`.properties`. That breaks translation.
- **Placeholder sprites** (`POTION_AZURE`, `SCROLL_HOLDER`) and borrowed buff icons.
- **Debug `System.out.println` traces** (`GHOST_TRACE`, `NETWORK_TRACE`, …) everywhere. Replace with `Gdx.app.debug` behind a flag.
- **Fully-qualified class names inline** throughout. Use imports (upstream style).
- **`server/node_modules` is committed** (97 files). Add `server/node_modules/` to `.gitignore` and `git rm -r --cached server/node_modules`.
- **Field reuse in `Packet.Payload`** (`damage` = tile/turn, `message` = pos). Make the payload typed per message.
- **`Dungeon.customSeedText` is set** for co-op, so runs count as custom-seed (no rankings/badges). That may be intended; it's documented here.
- **No tests**, and no headless way to run two clients.

---

## 8. Design decisions to make explicitly

1. **World model:** keep *parallel worlds* (cheap, matches the current code, tolerant of latency) **or** move to a *host-authoritative shared world* (host simulates, client is a thin view; a huge refactor of SPD's actor loop). **Recommendation:** keep parallel worlds for v1. Formalize the "shared objects" (floor items, doors, bosses, co-op rooms) with network IDs and a single owner.
2. **Same floor or free roam?** Many bugs disappear if both players must be on the same floor (e.g. stairs need both, or the ally gets pulled along). Decide this early.
3. **Authority for contested actions:** server-arbitrated (simple, the server becomes slightly smarter) vs host-arbitrated (no server logic). Recommended: **server arbitrates claims and timers**, because it's already the meeting point.
4. **Where the co-op flag lives:** persisted in the run (`Dungeon` bundle), not in socket state.
5. **Reconnect story:** room survives N seconds after a disconnect; on rejoin, exchange a state digest (depth, claimed item IDs, door states).

---

## 9. Roadmap

**Phase 0 — Hygiene (½ day)**
- Commit the current uncommitted work (Door sync, seed override fix, ghost sprite refactor) on a branch, e.g. `coop/foundation`.
- Untrack `server/node_modules`, `chmod +x gradlew`, and remove the debug printlns or route them through a logger.
- Add a `-Dspd.profile=<name>` (or similar) desktop arg so two local instances use separate save dirs.

**Phase 1 — Protocol & session (2–3 days)**
- `CoopSession` object: roomId, localId, allyId, allyHeroClass, `isCoopRun`, connection state, outbound queue.
- Envelope v1: `{v:1, type, seq, senderId, depth, branch, payload}`, with typed payload classes per message.
- Handshake: `HELLO{protocolVersion, gameVersionCode}`, then the server rejects mismatches. Add a ready check before `START_GAME`.
- Server: heartbeat, 60s reconnect window, configurable port (`PORT` env), size limits, and logging per room.
- Dispatcher: split the big `switch` into per-type handlers (`network/handlers/*`).

**Phase 2 — Fix criticals (3–5 days):** C1–C7, then H6 and H8 (quick wins).

**Phase 3 — Shared objects done right:** network item IDs + authoritative claims (H5), symmetric co-op doors (H4), server-timed switches (H3), boss HP ownership (H7).

**Phase 4 — Content & polish:** gate content to co-op runs, COOP_DOOR visuals, real sprites, `Messages` strings, decide on the ghost-as-actor question and Super Sync (H1/H2), disconnect UI.

**Phase 5 — Ship:** `wss://` behind a reverse proxy, Android cleartext config for dev, deploy the server, and a QA checklist (below).

### Manual QA checklist (until automated)
- [ ] Host and join on the first click; wrong code → error shown
- [ ] Both clients get the same seed; floor 1 layout is identical
- [ ] Ghost moves in real time on the same floor; hidden when on different floors
- [ ] Doors sync both ways; picking up an item removes it for the ally; simultaneous pickup → only one gets it
- [ ] Ally on a different floor: no cross-floor side effects
- [ ] Downed → ally reads Recall → revive at merchant floor; downed timeout → real death
- [ ] Disconnect mid-run → both players notified; the solo save is not corrupted; slot 1 is untouched
- [ ] Single-player run with the same seed is identical to vanilla v3.3.8

---

## 10. Change map vs upstream v3.3.8

| File | Change |
|---|---|
| `SPD-classes/.../noosa/Game.java` | default `version="1.5.0-ONLINE"`, `versionCode=100` |
| `core/build.gradle` | + `org.java-websocket:Java-WebSocket:1.5.3` |
| `actors/hero/Hero.java` | `isDowned`, `setAnkhRevive` (unused); ghost updates in `move/attack/die`; downed instead of death when connected; COOP_DOOR unlock action |
| `actors/mobs/Mob.java` | HP ×1.2 when connected; `BOSS_HIT` on boss damage; null-safe `src`; loot tagged `MOB_DROP` |
| `items/Item.java` | `Origin` enum (+ bundle); drop/throw → `DROP_ITEM` |
| `items/Heap.java` | pickup → `CLAIM_ITEM`; `removeSharedItem()` |
| `items/Generator.java` | FOOD probs 4/1 → 5.2/1.3 (**global**); 30% food when connected |
| `items/potions/PotionOfHealing.java` | clears downed; on ghost → `USE_RECALL` |
| `levels/RegularLevel.java` | + CoopChallengeRoom (**always**); Recall chest on floor 2–4 when connected |
| `levels/Terrain.java` | `COOP_DOOR = 39` |
| `levels/features/Door.java` | *(uncommitted)* `LEVEL_UPDATE` on open/close |
| `scenes/TitleScene.java` | Multiplayer button, rebrand, layout |
| `scenes/HeroSelectScene.java` | *(uncommitted)* seed override |
| `scenes/GameScene.java` | `addGhostSprite` |
| `sprites/CharSprite.java` | `move()` guard if no parent |
| `sprites/HeroSprite.java` | `update()` safe for non-Hero |
| `sprites/GhostPlayerSprite.java` | *(uncommitted)* now extends `CharSprite`, so it no longer hijacks `Dungeon.hero.sprite` |
| `ui/Compass.java`, `ui/StatusPane.java` | ally compass |
| `desktop/build.gradle`, `DesktopLauncher.java` | dist assets; null-safe title/version/vendor |
| new files | see §2 |
