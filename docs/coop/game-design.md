# Co-op Game Design — "Logic Link PD"

> Status: **DRAFT v0.1** (2026-10-17). Decided items are marked ✅. Open items are marked ❓ and listed in §11.
> Every number in this doc is a **starting value for playtesting**, not final balance.

---

## 1. Pillars

1. **Two worlds, one team.** Each player is in their own copy of the same dungeon. You can't fight side by side (except via Super Sync), but you **need each other** to get through.
2. **Interdependence over convenience.** The best moments are when *my* world has the answer to *your* problem: a key, a code, a lever, a warning.
3. **Pressure without stalling.** In a turn-based game nobody should be able to freeze a co-op moment by standing still.
4. **Failure is a setback, never a dead end.** No permanent softlocks. Every co-op mechanic has a fallback.
5. **Single-player stays vanilla.** Everything here exists only in co-op runs.

## 2. Core concepts

| Concept | Definition |
|---|---|
| **World** | One player's local simulation of the dungeon. Same seed means the same map, cell for cell. |
| **Ghost** | The ally's visual presence in your world: translucent, intangible, ignored by mobs. It mirrors the ally's position (same cell coordinates). |
| **Role A / B** | Assigned per co-op room/mission (alternating, seeded). Decides who is locked/trapped and who holds the solution. |
| **Co-op Room** | A level-gen room type with asymmetric state per role. Server holds its runtime state. |
| **Mission** | A timed co-op event (e.g. The Cell). Server-run state machine. |
| **Region** | Floors 1–5 Sewers, 6–10 Prison, 11–15 Caves, 16–20 City, 21–25 Halls. |
| **Region start** | `((depth - 1) / 5) * 5 + 1`, i.e. 1 / 6 / 11 / 16 / 21. |

### 2.1 Floor rules ✅ (partially)
- **Free roam** on normal floors. Players may be on different floors.
- **Mandatory co-op gates** (rare, fixed points, e.g. the floor before a boss) require **both players on the same floor**. The stairs down don't open until the gate is solved.
- **Optional co-op rooms** appear more often and give better loot. Skipping them is allowed.
- When the ally is on another floor: the ghost is hidden, and the ally compass shows "↓3" / "↑1" instead of an arrow.

## 3. Communication

Players on mobile can't type quickly, so communication is a core system, not polish.

- **Ping:** long-press a cell (0.5 s, touch or mouse; desktop also: middle-click a cell, or `G` for the cell under the mouse) to open a picker: *Here* / *Danger* / *Item* / *Go*. The marker shows on that cell for ~5 s in both worlds (locally at once, the ally's via `MARK`), drawn over the fog, with an arrow at the screen edge while it is off-screen. Only cells you have seen can be pinged. Same floor only: a ping for another floor is ignored (it only updates the ally compass). Local rate limit: 1 per second. The ally's ping also writes a log line.
- **Quick messages:** 8 phrases ("Help!", "Wait", "Found key", "Trapped", "Coming", "Go on", "Danger", "Thanks"), all localizable; the `phraseId` (0–7) is wire format and never renumbered. Opened by tapping the ally chip, or `T` on desktop (`T`/`G` are co-op key actions, rebindable in the key bindings window). Received: a log line with the ally's name, plus a ~3 s toast.
- **Ally status chip** (under the status pane; top-left with the large UI): the ally's hero icon, HP %, depth, and connection (*online* / *offline Ns/Nm* since last seen / *reconnecting...* when our own connection is down). In-mission / Downed states come with MEC tasks.
- **Ally compass:** on the same floor, a second, tinted arrow around our portrait points at the ally; on another floor it shows "↓N" / "↑N" (floors away), or "≠" for the other branch of the same depth.
- Voice chat is out of scope (people use Discord).

## 4. Mission: **The Cell** ✅

The flagship co-op mechanic. One player gets trapped, and both have to act to free them.

### 4.1 Setup: trap-triggered (D-020)
- There is **no fixed Cell room**. The Cell is triggered by a **Binding Trap**: a hidden trap placed by co-op level gen (seeded, so it sits on the same cell in both worlds), **0–1 per eligible floor**. It can be found by searching, like any SPD hidden trap.
- **Eligible:** standard rooms only. Never on the entrance/exit room, shops, boss floors, or special/secret rooms, and never on floor 1.
- **Arming rule:** the trap only fires if the ally is **online and on the same depth+branch**. Otherwise stepping on it does nothing and it stays hidden and armed for later.
- **Who is who:** whoever steps on it is the **Prisoner**, and the other player is the **Rescuer** (the roles are not pre-assigned).
- **Prisoner:** the **room they're standing in seals** (its doors lock, like SPD's locked-room events). That room is the cell, in the prisoner's world only.
- **Rescuer:** the same room stays open in the rescuer's world. A **Cell Key** spawns at a server-chosen random cell on the rescuer's floor (from candidate cells reachable in their world), and the rescuer gets a ping toward it.
- The mission starts, and the **server** starts the timer. Both players see the timer and each other's task progress.
- **Later option (B):** a rare server-rolled "ambush" event that seals the prisoner's current room with no trap at all. Not in v1.

### 4.2 Tasks (both must act)
- **Rescuer:** find the Cell Key in their world, walk their **ghost** to the cell door (same cells), then use **Give**. The server transfers the key (exactly once), and the door unlocks in A's world.
- **Prisoner:** **survive escalating waves** spawned inside the cell (§4.4). Optional variant ❓: a lever/rune task inside the cell that shortens the timer, or that the rescuer must read out from a wall in their world.

### 4.3 Outcomes
```
Mission start (timer + waves)
│
├─ Key delivered before prisoner falls
│     → door opens, waves stop, REWARD for both (§4.6)
│
└─ Prisoner HP reaches 0
      → Prisoner is DOWNED (§5) for the downed window
      ├─ Rescuer reads Scroll of Recall in time → prisoner revives (§5.2), mission ends (no reward)
      └─ No scroll / window expires
            → BOTH players are returned to REGION START (§4.5)
```
- There is **no "timer just runs out" ending**. The waves *are* the timer: they escalate until the prisoner can't hold. A hard cap (§4.4) guarantees the mission ends.

### 4.4 Anti-stall: real time + forced turns ✅
Turn-based problem: a world's time only moves when its hero acts. If timers counted turns, a player could freeze everything by standing still. So:
- **The mission clock is real time**, held by the server.
- **Forced turns:** during a mission (and a Downed window, §5.1), if a player doesn't act within **5 s**, their game performs a *wait* turn automatically, the same as pressing rest once. This applies to **both** players, so both worlds keep moving. Implemented in MEC-00 (`coop/mission`):
  - the 5 s count starts when the hero starts waiting for input, or when the clock (re)starts if that's later: a resume never forces a turn at once;
  - it applies **while any window is open, the pause menu included**, and cancels a pending cell selection (aiming a wand) first. A pause-menu exception would let the prisoner freeze their own world, and with it the waves; real absences are covered by the pause rule below;
  - the HUD (mission bar under the boss bar, both players) shows the kind, the real-time countdown (`m:ss`), why it is paused, and a checkbox per task flag; a Downed window adds its own countdown line.
- **Pause:** the clock and forced turns pause if either player disconnects or backgrounds the app (iOS calls, etc.). The client treats its own disconnection and the ally being offline (`ALLY_STATUS`/room snapshot) as paused at once, before the server's `MISSION_UPDATE{paused}` can arrive. The server sends no new Downed deadline after a pause, so the client moves it by the time it saw the link down. After **90 s** paused, the mission resolves as a failure without the return penalty (❓).
- **Server time:** deadlines are server Unix ms; the client converts them with the offset from `WELCOME`/`PONG.serverTime` (the least-delayed of the last 6 samples). While running, `MISSION_UPDATE.remainingMs` is the truth (the server moves its deadline when it pauses).
- **Waves:** one wave every **~30 s** of mission clock, each stronger than the last. They draw from the region's mob pool, scaled +1 tier every 2 waves. After **hard cap = 4 min + 15 s × region index**, waves become lethal (guaranteed resolution).

### 4.5 Return penalty ✅
- **Both** players are sent to the **region start** floor (1/6/11/16/21), at its entrance.
- It's implemented with the vanilla `InterlevelScene.Mode.RETURN` (`returnDepth/returnBranch/returnPos`, `returnPos = -1` = the floor's entrance). **Never** set `Dungeon.depth` by hand.
- **Exploit guard:** region starts 6/11/16/21 have shops, so failing must not be a free teleport. The penalty also includes: −25% current gold for both, and the Cell's reward is lost. ❓ tune. The server sends the floor and the percentage (`MISSION_END.penalty{regionStartDepth, goldPct}`); the gold lost rounds down.
- **When (MEC-00):** each client applies it once (replays are ignored) as soon as its hero waits for input: gold first, then the travel. During an active boss fight (sealed boss floor) the travel and the gold loss wait until the fight is over. A hero who is already dead gets neither.
- Floors already explored stay explored. Stairs are marked on the map to cut down the walk back.

### 4.6 Reward
- Success gives **both** players a reward from the Cell's seeded loot: one guaranteed rare (artifact / +2 item / exotic), plus a small shared buff ("Bonded": +10% accuracy for 200 turns). ❓
- Roles come from who triggers the trap (not alternated).

### 4.7 Frequency
- Optional Cells: ~1 per 2 floors (seeded). They don't spawn on boss floors or on floor 1.
- Mandatory gate: ❓ (candidate: floor 4, 9, 14, 19, 24, the floor before each boss).

## 5. Downed & revive ✅

### 5.1 Downed
- In a co-op run, when a hero would die (and no Ankh or other vanilla save applies), they become **Downed** instead:
  - paralysed, invulnerable, ignored by mobs, shown with a downed visual for the ally;
  - the **downed window** is **30 s real time** (with forced turns, same rules as §4.4).
- If the window expires, the **real death path** runs (vanilla `Hero` death). Downed must **never** just expire into a free revive (that was a bug in the prototype).
- Outside a mission, real death means the run ends for that player ❓ (or: the surviving player can still revive at a shrine later?).

### 5.2 Scroll of Recall
- **Use:** read it while the ally is Downed (any floor). The ally revives with **25% HP** and returns to their region start via `Mode.RETURN` (outside The Cell), or stays in place with the mission ended (inside The Cell).
- **Rarity:** 1 guaranteed per region (seeded chest), plus possible shop stock. This makes it something players save up.
- **Can't be used** on yourself.

### 5.3 Revive with a Potion of Healing (owner's design, D-021)
- **Throwing** a Potion of Healing at your ally's **ghost** while the ally is Downed (same floor, ghost visible) revives them **in place** at 25% HP. Drinking one yourself while Downed isn't possible (you're paralysed).
- So **Healing** = revive here, needs you nearby. **Recall** = revive from any floor, at the region start (outside The Cell).

## 6. Potion of **Super Sync** ✅ (concept) / ❓ (control model)

An emergency potion. The drinker's ghost **materializes** in the ally's world: it becomes visible and solid, and it can **attack the ally's monsters**.

### 6.1 Rules
- **Effect:** for **15 turns of the ally's world**, the drinker's ghost becomes a **Projection** in the ally's world, standing next to the ally:
  - visible and solid; mobs can see and attack it;
  - it uses a **stat snapshot** of the drinker taken when they drink (current HP, damage range from their equipped weapon + STR, accuracy, evasion, armor). Enchant/glyph procs are out of scope for v1.
- **The drinker's own body:** stays in their world in a trance (can't act).
  - Outside missions, the drinker's world is naturally paused (turn-based: it's not acting), so the body is safe.
  - **Inside a mission**, forced turns keep running, so the body is **vulnerable** while projected. That's a real choice: as rescuer, do you project in to help the prisoner survive, or keep running for the key?
- **Damage taken** by the Projection is applied to the drinker's real HP when they return. If the Projection "dies", the effect ends early and the drinker returns at **1 HP** (never killed outright).
- **Rarity:** rare. About 1 per 2 regions, plus a possible alchemy recipe later. Only one Super Sync can be active per pair at a time. It can't be drunk while Downed.
- **Single-player:** this item never generates. If it somehow exists, it does nothing and isn't consumed.

### 6.2 Control model ✅ player-controlled (D-011)
The **drinker controls the Projection directly**: they move it and attack with it.
- **Authority:** the Projection exists only in the **ally's world** (the host world). The ally's client simulates it: it's an `Actor` whose turn **waits for remote input** from the drinker.
- **Turn order:** in the ally's world, the Projection acts in the normal actor queue (speed = 1 action per turn, like a hero). When its turn comes, the ally's world pauses until the drinker's command arrives.
- **Anti-stall:** if no command arrives within **5 s**, the Projection does a *wait* turn (same forced-turn rule as missions). On disconnect, it waits every turn, and after 3 missed turns the effect ends.
- **Commands (v1):** move to adjacent cell, melee attack adjacent mob, wait. Out of scope for v1: items, throwing, wands, abilities, picking up loot (the Projection can't take items from the ally's world).

### 6.3 What the drinker sees: "Remote View" ⚠️ the expensive part
The drinker's client **does not have the ally's world**. The map layout is identical (same seed), but the mobs, their HP, doors, and the ally's position are different. So:
- While Super Sync is active, the drinker's screen switches to **Remote View**: it renders the **same map** (local copy, plus the terrain changes the ally reports) and overlays the **dynamic entities streamed from the ally's world**: mobs in the Projection's FOV (type, pos, HP%, state), the ally hero, and the Projection itself.
- The ally's client sends a **view snapshot after every Projection turn and every time a mob moves within its FOV** (`SYNC_VIEW`). Mobs are drawn with their normal sprites, but they're just *puppets* on the drinker's side: no AI, no simulation.
- Mob info is limited to what the Projection can see (its FOV computed in the ally's world). This keeps both the message size and the fairness in check.
- Meanwhile the drinker's own world is paused outside missions; inside missions it keeps running under forced turns (§6.1), and a small picture-in-picture/HP alert warns if their real body is being hit.

### 6.4 Network sketch
```
Drinker                     Server                       Ally (host world)
SYNC_DRINK{snapshot} ─────▶ validate (potion, cooldown) ─▶ SYNC_START{snapshot, turns}
                                                          spawn Projection actor
◀──────────────────────────────────────────────────────── SYNC_VIEW{turn, terrainDiff, entities[]}
SYNC_CMD{turn, move|attack|wait, cell} ─────────────────▶ apply on Projection's turn
◀──────────────────────────────────────────────────────── SYNC_VIEW{...}   (repeat)
                                                          duration ends / Projection dies
◀─────────────────────────── SYNC_END{hpLost, reason} ◀── SYNC_REPORT{hpLost, reason}
apply hpLost, leave Remote View
```
`SYNC_CMD` carries the `turn` number it answers, so stale commands are dropped. Combat math runs only in the ally's world.

## 6A. World links (the owner's original design, carried over from the v3.3.8 prototype: D-021)

These connect the two worlds directly. The ideas are the owner's. What changes is the implementation: everything that was turn-counted or client-trusted in the prototype is now decided by the **server**, and everything is co-op-only and floor-scoped.

### 6A.1 Shared floor loot
- Items that **level gen** places on a floor (seeded, so they're identical in both worlds, each with a stable `itemNetId` from DET-01) form **one shared pool**. That includes chest/tomb contents and what a Mimic or a Statue carries. The id travels inside the item's save data, so it survives pickup, drop, throw, save/load and the mob's death.
- **Pickup is optimistic** (ITM-01): the item goes into your pack at once and a claim goes to the server (while offline, the claim waits and goes out when the connection is back). **First claim wins.** If the server says your ally got it first, the item leaves your pack again (for a stack, only the copies you took; for gold, the amount) and the log says "Your ally got it first: … vanishes from your pack." An item you already used before the answer can't be taken back.
- The item disappears from the ally's world: with a small poof right away if they're on that floor, otherwise it is absent when they get there (the client asks the server for the floor's claims on every floor entry, and also remembers what the ally took, so it works offline too).
- **Not shared:** mob drops and other runtime loot (each world has its own mobs), per-player items (DET-02), **keys** (they open doors of one world; LINK-01 links doors) and **shop stock** (each player has their own gold; both can buy the same item).

### 6A.2 Drop to your ally
- An item you **drop or throw to the floor** while your ally is **online on the same floor** also appears in the ally's world, on the same cell, as a shared item with a new id (`<yourPlayerId>:<n>`). Either player can claim it, exactly once: whoever picks it up first keeps it, and it vanishes from the other world. This is how players trade on the spot. The Item Shrine (§7) is for trading across floors.
- Items consumed on impact (shattered potions, runestones that trigger, missiles that hit and break or stick) aren't mirrored. A thrown item that ends up lying on the floor is. An item that falls into a chasm or merges into a stack already lying there isn't.
- **Never sent:** bags (and their contents), keys, bombs, the Amulet, quest items (each world runs its own quests), per-player items (DET-02), and an item whose pickup claim the server hasn't confirmed yet. The item is sent as SPD's own save data, at most 16 KiB; anything bigger stays in your world only.
- If the ally isn't on the floor when the drop arrives (they just left), it doesn't appear for them; it stays a normal item in your world.

### 6A.3 Shared boss HP
- On a boss floor, while **both** players are on it, each boss has **one HP pool held by the server**. Damage dealt in either world is reported, and the server broadcasts the pool, which both worlds apply. That keeps phases (e.g. thresholds) in sync, because they're driven by the shared HP.
- The boss's HP pool is scaled for two attackers (start: HT × 1.8, tune in BAL). If only one player is on the boss floor, the fight is solo and normal.
- The boss is dead when the pool hits 0, in both worlds.

### 6A.4 Twin Door (co-op door)
- A special door that opens only when **both players stand next to the same door cell, each in their own world, at the same time** (the server confirms both within a ~2 s window). It opens in both worlds. Used for mandatory co-op gates (§2.1) and co-op rooms.

### 6A.5 Twin Switches room
- A room with **two switches**. Each player steps on a *different* switch, and the server checks both presses land within a **~2 s real-time window** (the prototype compared two unrelated turn clocks, which was the bug). On success, the room's locked chest (an artifact) opens in both worlds.
- While both players are inside this room, the ally ghost is drawn **fully opaque** (physical co-presence).

### 6A.6 Door mirroring
- Opening/closing a door (and breaking one) in your world does the same on that cell in the ally's world, but **only when the ally is on the same floor**, and only for door tiles (validated). Other terrain stays independent.
- **What counts (LINK-01):** a regular door that **your hero** opens by stepping in or closes by stepping out (or being knocked out), and a door that **burns** to embers, whatever burnt it (fire, bomb, beam): a broken door is permanent in both worlds. Mobs opening and closing doors stay in their own world.
- **Not mirrored:** locked doors (and unlocking one), crystal doors, the Cell's sealed doors (§4, MEC-01), secret doors, and doors forced open some other way (Skeleton Key, fire spells that blow a door open; the fire that then burns it *is* mirrored). Keys stay per-world (§6A.1).
- **Applied only to a regular door:** the change lands only if that cell is a regular door in the receiving world too. A door that is locked or sealed there, or already burnt there, doesn't change. Embers are final.
- **A door never shuts on something:** if a char or an item is in the doorway in the receiving world, the close is skipped and the door stays open there (the same rule as SPD's own doors). It closes again the next time someone walks out of it.
- Best-effort: a change sent while the ally is switching floors is lost, so door states can differ until the next change. No echo: an applied change never goes back out.

### 6A.7 Co-op balance (co-op runs only, deterministic, tune in BAL)
- **Mobs: HT × 1.2.**
- **More food:** 30% of random item rolls become food, and the food category probabilities are 5.2 / 1.3 (pasty) instead of 4 / 1. This compensates for the shared loot pool. Applied inside co-op level gen (DET-01 keeps it identical in both worlds).

## 7. Co-op room catalog (puzzles)

All rooms are seeded, appear in both worlds, use role A/B asymmetry, and keep their state on the server.

| Room | Role A sees | Role B does | Fallback |
|---|---|---|---|
| **The Cell** (§4) | trapped + waves | fetches key, gives via ghost | waves → downed → Recall / region return |
| **Mirror Lock** | door locked | key is in B's world; B "gives" it via ghost | after 3 min, A can break the door for −20% HP |
| **Pressure Bridge** | chasm, no bridge | B stands on a plate, which raises the bridge in A's world while B stays | the bridge stays up 10 turns after B leaves |
| **Rune Wall** | rune keypad | B reads 4 runes on a wall only visible in B's world | wrong code ×3 → spawns mobs, code rerolls |
| **Totem Shield** (boss variant) | boss has a shield | B destroys a totem that only exists in B's world | the shield decays slowly by itself |
| **Dark Floor** | darkness | B's ghost emits light in A's world (radius 2) | normal torches still work |
| **Item Shrine** | — | either player sends 1 item to the other for a cost (gold/HP), from any floor | — |
| **Twin Door** (§6A.4) | door won't open | both stand next to it in their worlds within ~2 s | — (both must cooperate; used at gates) |
| **Twin Switches** (§6A.5) | locked artifact chest | each steps on a different switch within ~2 s | retry anytime |

New rooms must follow the template in §9.

## 8. Ghost behavior

- Translucent (alpha 0.5) and tinted cool blue, using the ally's hero class and armor tier, mirroring the ally's cell position; it moves smoothly between cells and animates attack/operate/zap/die (downed comes with MEC-02).
- Drawn above the floor and items, under mobs and your hero, and **only on cells you have visited or mapped**: it never reveals terrain you haven't seen.
- **Intangible:** it is not an Actor and not in the level's mobs: mobs ignore it, and it doesn't block cells. Exceptions: Super Sync (§6) and light (Dark Floor).
- **Offline ally:** the ghost stays at its last cell, greyed and faded, and comes back when the ally reconnects. It is restored after level changes, save/load and reconnects from the last received position (the ally resends it every ~5 s while idle).
- **Opaque mode:** drawn fully opaque while both players share a Twin Switches room (§6A.5; API ready, used by ROOM-01).
- **Scouting:** the ghost reveals nothing extra by default. ❓ Should the ally's ghost share a small FOV radius (1–2) in your world? It's strong, and it might replace the need for pings.
- Hidden when the ally is on another floor (the compass shows "↓N" / "↑N" instead).

## 9. Template for a new co-op mechanic

Copy this into §7 / a new section:

```
### <Name>
- Fantasy / why it's fun:
- Role A sees / does:
- Role B sees / does:
- Win condition (who decides: server):
- Fallback (no softlock):
- Timers (real time? forced turns?):
- Seeded elements (what level gen must place identically):
- Network messages (→ architecture.md):
- Saves (what persists if the app closes mid-way):
- Single-player behavior: (must be: doesn't exist)
- Tuning values:
```

## 10. Tuning table (v0)

| Value | Start |
|---|---|
| Forced-turn timeout (missions / downed / Super Sync Projection) | 5 s |
| Cell wave interval | 30 s |
| Cell hard cap | 4 min + 15 s × region index |
| Downed window | 30 s real time |
| Revive HP | 25% |
| Return penalty gold | −25% |
| Super Sync duration | 15 turns (ally's world) |
| Super Sync rarity | ~1 / 2 regions |
| Recall rarity | 1 guaranteed / region |
| Optional Cell frequency | ~1 / 2 floors |
| Disconnect grace before mission fails | 90 s |
| Twin door / twin switches window | 2 s real time |
| Boss shared HP scale (both present) | HT × 1.8 |
| Co-op mob HP | HT × 1.2 |
| Co-op food | 30% of random rolls → food; FOOD probs 5.2 / 1.3 |

## 11. Open questions ❓

1. Mandatory gates: which floors? (proposed: the floor before each boss)
2. Real death outside missions: does the run end for that player, or can they be revived later?
3. ~~Super Sync AI vs player-controlled~~ → **player-controlled** (D-011). Still open: should the Projection be able to use thrown weapons in v2?
4. Should the ghost share a small FOV?
5. The Cell: a prisoner-side puzzle in addition to the waves?
6. Return penalty: is −25% gold enough to stop "fail to reach the shop" abuse?
7. Do co-op runs count for rankings/badges? (Vanilla marks custom seeds as unranked.)

## 12. Deferred ideas (not v1)

- **Sub-dungeon punishment:** instead of a region return, a failed mission drops both players into an unknown sub-dungeon full of mobs. Escaping returns them to the Cell's floor. This needs a new level type, its own generation and sync. Revisit after v1.
- Super Sync v2: items/thrown weapons/abilities for the Projection.
- Alchemy recipes for Super Sync / Recall.
- More than 2 players (out of scope: the whole design assumes A/B).
