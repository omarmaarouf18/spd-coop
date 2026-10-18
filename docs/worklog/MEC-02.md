# Worklog: MEC-02 — Downed, Scroll of Recall, Healing-potion revive

## Task and scope
- Task ID: MEC-02 (game-design §5, D-021, D-022; the server side is SRV-02's)
- Lane (allowed paths): `core/.../coop/mission/downed/**` (new), `core/.../coop/items/**` only for the Scroll of Recall, `core/.../coop/levelgen/**` only to place Recall, co-op tests, `core/src/test/resources/goldens/coop-v4.0.2.json` (deliberate regen), `core/src/main/assets/messages/**` (append), `docs/worklog/MEC-02.md`, game-design §5
- Hot paths granted (one `// COOP:` line + import each), all used:
  - `actors/hero/Hero.java` `die()`: `if (CoopDowned.onHeroDying( this, cause )) return;` right after the Ankh block, before `Actor.fixTime()` / `super.die`.
  - `items/potions/Potion.java` `onThrow()`: `if (CoopDowned.onPotionThrown( this, cell )) return;` as its first line (it acts only on `PotionOfHealing`).
  - `coop/Coop.java`: `CoopCell.install();` (in a `static {}` block, see decisions).
  - `coop/CoopConnection.java` `onServerEvent`: `CoopDowned.onServerEvent( m );` (first line).
- Co-op gen: one call `RecallLayout.place( level );` in `CoopLevelGen.generate`, after MEC-01's `placeTrap`.
- Branch: `agent/MEC-02-downed-revive` in worktree `../spd-wt/MEC-02`, from dev (cc11284).

## Decisions taken
- **Downed state** = a `Downed` buff (saved with the hero) + vanilla `Invulnerability` for 1e6 turns. `Downed.attachTo` adds to `paralysed` and `invisible`, and `detach` takes them away, so a restored save gets the counters back. `Char.buff()` matches exact classes, so a subclass of `Invulnerability` would not count; the real buff is used instead.
- **Ignored by mobs:** `invisible > 0`, which is what vanilla mob AI checks (`enemy.invisible <= 0`).
- **Turn pacing while Downed (deviation worth reviewing):** a paralysed hero calls `spendAndNext` every turn, so the world would race through hundreds of turns per second for the whole 30 s window. `Downed.act()` therefore waits on the actor thread for `ForcedTurns.WAIT_MS` (5 s) of running clock per turn (`DownedPace`, 100 ms sleeps). Waiting stops at once on revive, expiry, link down (the count restarts) or a scene switch (the interrupt is handed back to `Actor.process`). `act()` runs outside every `Actor` lock, the same way a sprite wait does. MEC-00's forced turns don't apply to a paralysed hero (it never becomes `ready`), so this gives the same pace.
- **DOWNED:** sent when going down (posted to the GL thread), then again every 5 s only until the server's `ALLY_DOWNED` for us is in the mirror. That covers a send lost to a reconnect; the server ignores repeats. Cause = the killer's class simple name (≤ 64 chars).
- **Already Downed:** any further `die()` is swallowed (HP stays 1), except our own expiry death (`dying` flag).
- **Revive target:** "the Downed ally" = the first id in the mirror's `downed()` that isn't us and isn't out. A Scroll of Recall can't target us by construction.
- **Scroll of Recall** (`coop/items/ScrollOfRecall`, plain `Item`, stackable, BERKANAN scroll sprite, 60 gold): READ → `USE_RECALL{via:"recall"}`. The scroll goes into a `RecallPending` buff on the hero (saved, item and its itemNetId included). `REVIVE` of the target uses it up. It comes back on:
  - a DENY with reason `ally_offline`, `ally_other_floor`, `invalid_state`, `downed` or `not_downed` (DENY names no request, so any of these while one is pending; the reasons that belong to other features, like `taken`, are ignored);
  - `DOWNED_EXPIRED` of the target;
  - `MISSION_END{failed}` for the target;
  - the target no longer Downed in the mirror;
  - 30 s of running link with no answer.

  With no Downed ally, or one already pending: a hint, nothing sent, the scroll stays.
- **Healing throw:** `onPotionThrown` takes it only for `PotionOfHealing` (exact class), with a Downed ally, `GhostAlly.isTarget( cell )` and the ally online on our floor. Then it sends `USE_RECALL{via:"healing"}` and returns true: no shatter, no `pressCell`, and the item is already out of the pack (consumed). A miss or any other case is the vanilla throw. A DENY after a hit doesn't give the potion back (the spec says it's consumed).
- **REVIVE for us:** stand up, HP = round(HT × hpPct / 100). With `regionStart`, `Mode.RETURN` to `((depth−1)/5)×5+1`, entrance, branch 0. This is my own copy of `MissionPenalty.returnTo`, which is package-private in `coop/mission` (not my lane). It is held in place while `MissionPenalty.travelBlocked()` (sealed boss fight). No local Downed: nothing happens (no free HP change).
- **DOWNED_EXPIRED for us:** sets `expired` (saved). The next `Downed.act()` on the actor thread stands up, sets HP 0 and calls `Hero.die( new DownedExpiry() )`. `DownedExpiry` is a `Hero.Doom`: `Dungeon.fail` + log, rankings text "Bled out while downed". If we weren't Downed locally (e.g. an older save), we go down first so the death still runs: never a free revive. **For the ally:** a log line + a `WndMessage`, and their pending scroll comes back.
- **D-022 / MEC-01:** on `MISSION_END{failed}` both clients remove the prisoner from the mirror's `downed()` map (SRV-02 sends neither REVIVE nor DOWNED_EXPIRED then). The prisoner's client stands up at 25% HT, and MEC-00's queued penalty then takes both to the region start. An abort arrives as `REVIVE{inPlace,25}` before `MISSION_END{aborted}` and is handled by the REVIVE path. MEC-01's waves already skip a prisoner whose window is in the mirror.
- **Ally ghost visual:** `DownedFx`, a `Gizmo` added to the GameScene on `ALLY_DOWNED`, puts `CharSprite.State.PARALYSED` on the ghost sprite while the ally is Downed in the mirror, and removes itself after. It isn't re-added after a scene change: coming back to the floor (or reloading) while the ally is still Downed shows no visual until the next `ALLY_DOWNED`.
- **Recall placement** (`RecallLayout`, called from co-op gen after `placeTrap`, before `CoopItemIds.assign`, so the scrolls get regular `depth:branch:n` ids):
  - **floor:** one per region on floor `regionStart + Int(4)`, drawn from `seedForDepth( regionStart, 0 ) ^ SALT_FLOOR`, so every floor of the region agrees;
  - **cell:** chosen on that floor from `seedForDepth( depth, 0 ) ^ SALT_PLACE` (two draws always taken), in a `CHEST` heap on plain floor of a standard room (not entrance/exit/quest/Imp shop, not next to a door), with any free plain floor as a fallback (one seed's region 4 floor needed it);
  - **shop:** 50% per floor, a `FOR_SALE` one inside a `ShopRoom` (only shop floors have one);
  - **scope:** main branch, depths 1–24 only.
- **Coop.java line:** the grant reads "one `CoopCell.install();` line at co-op run start/restore". Run start (`onInit`) and restore (`restoreFromBundle`) are two methods, so the single statement sits in a `static {}` block of `Coop`. It runs before either (the first co-op hook loads `Coop`). Like MEC-01's hooks, it also runs in vanilla, where it only registers a listener that never fires.
- **MEC-01's static `CoopCell.install()` blocks** (`BindingTrap`, `CellKey`, `CellPrison`, `CellRescue`) are left as they are: they're in `coop/mission/cell/**`, not my lane, and harmless (an idempotent no-op). Removing them is a one-commit follow-up for whoever owns that lane.
- `CoopDowned` registers its own listener in the same class-loading way. The CoopConnection dispatch line runs it before `CoopMissions.onServerEvent`, so it is installed before the first mission event is applied; the hero's buffs and the Recall item also load it.
- **D-024** is cited in the prompt but `decisions.md` ends at D-023: nothing to apply.

## Changes per commit (subject only — NO SHAs)
1. `MEC-02: add Downed, Scroll of Recall and the Healing revive` (also: the four granted hooks, the Recall placement in co-op gen, the regenerated co-op goldens, strings, game-design §5.4; the commit gate stamps the whole tree, so every tracked change lands together)
2. `MEC-02: add the Downed and Recall tests`
3. `MEC-02: worklog`

## Verification
- `./gradlew :core:test --tests '*mission*'`: `EXIT 0` (MEC-00, MEC-01 and MEC-02 suites).
- New tests:
  - `DownedTest` (14):
    - Downed instead of death: HP 1, buff, paralysed, invisible, invulnerable, cause `Rat`;
    - one DOWNED, a resend only after 5 s without the server's ALLY_DOWNED, none after it;
    - the hook after the Ankh block (source check: the Ankh's revive needs a scene);
    - vanilla: hooks return false;
    - REVIVE regionStart → 25% HP and travel to depth 6 from 7; inPlace → no travel;
    - expiry → `DownedExpiry` death at the next act, also when not Downed locally;
    - failed Cell → cleared, 25% HP, mirror cleared, MEC-00 penalty still queued;
    - abort → REVIVE inPlace;
    - Recall: no Downed ally / only us Downed → stays; ally Downed on another floor → `USE_RECALL recall`, scroll waits, a second read refused, REVIVE uses it up, only the target travels;
    - a DENY `taken` ignored, `not_downed` refunds, no answer for 30 s refunds, the ally's expiry refunds + alert, an out ally can't be recalled;
    - Healing: not Downed, a miss, another potion, the ally elsewhere → vanilla; a hit → `USE_RECALL healing`, consumed;
    - save: Downed (expired flag, cause, counters), Invulnerability, the pending scroll survive a byte-level hero bundle round trip;
    - pacing with a fake clock: 5 s, the count restarts after a hold, stops on revive, interrupt.
  - `RecallLayoutTest` (3): seeds 123456789, 42, "logic link pd":
    - exactly one CHEST Recall per region 1–5 and none in region 6;
    - on the region's Recall floor, branch 0, not a boss floor, with an itemNetId;
    - shop stock only FOR_SALE and seen at least once;
    - identical floor/cell/type/netId for warrior + nothing vs mage + `HeavyPlay`;
    - vanilla floors 1–12 have none.
- Co-op goldens, regenerated twice with `./gradlew :core:test --tests '*CoopGoldensTest' -Dgoldens.regen=true --rerun-tasks`:
  - md5 before: `2629e5dd7a2dfc28f5a9f1d43cb2197b` (MEC-01's);
  - run 1: `93b5dcc025af26d3799c2a2607439c6a`;
  - run 2: `93b5dcc025af26d3799c2a2607439c6a`.
- Vanilla golden: md5 `d04a9d2507689d942ab38690b9ece9d4` (unchanged; `git diff` empty).
- **Golden diff explained** (per floor and component, old vs new JSON):
  - 57 of 252 floors changed, all branch 0, depth ≤ 24: 45 region chests (9 seeds × 5) + 12 shop stocks;
  - changed components: `items` and `netIds` on all 57 (+ `all`); `map`, `mobs`, `traps`, `plants`, `blobs`, `transitions` never;
  - one `mobState` change, seed 1539345351177 floor 16-0. Traced with a throw-away probe (Recall placement on vs off, test-only switch `RecallLayout.enabled`): a Mimic carries Gold, a ForceCube and a Gauntlet whose ids moved by one (`16:0:37` → `16:0:38` …, because the new heap is numbered before the mobs). The ForceCube's `set_id` is derived from its itemNetId (DET-01), so it changed too. No mob or other item state changed. The probe file was deleted.
- `make check`: see the final report. A first run passed (196 tests, 0 failures, 2 skipped) but wrote no stamp because I edited a doc while it ran.
- `make mission-probe`: **failed before the probe ran**. First there was no Docker daemon (I started `dockerd`), then the Docker Hub pull of `golang:1.27.2-alpine` got `429 Too Many Requests`. I ran the same steps without Docker: the server (`PORT=8080 DB_PATH=… go run ./cmd/server`) + `go run ./cmd/missionprobe` with `GOTOOLCHAIN` from `tools/versions.env` → `PASS: all 8 scenarios matched` (incl. Downed→Recall revive, Cell Downed expiry → `MISSION_END failed`, no `DOWNED_EXPIRED`, expiry outside a mission → `DOWNED_EXPIRED`, disconnect → frozen window → resume). This probes the server only (unchanged here); it confirms the messages my client code expects.
- Single-player impact: none intended. Every hook returns the vanilla answer outside co-op runs (tests above); no Recall in vanilla floors; vanilla golden unchanged.
- Determinism impact: co-op goldens deliberately changed by the Recall placements only (see above).

## Unverified
- **E2E with two clients + `make server-dev`: not run** (headless container, no display). Owner checklist:
  1. Get downed by a mob: HP 1, the hero can't act, mobs walk away, the world moves one turn every ~5 s, the ally sees "Your ally is downed!" and the ghost looks paralysed.
  2. Ally reads a Scroll of Recall from another floor: you revive at 25% at the region start (Mode.RETURN), the scroll is gone.
  3. Ally throws a Potion of Healing onto your ghost on the same floor: revive in place, no shatter.
  4. Let the window run out outside a mission: the real game over for you ("Bled out while downed"); the ally gets the window and plays on.
  5. Inside The Cell: fall to a wave, let the window run out → both lose 25% gold and return to the region start, the prisoner at 25% HP, no death.
  6. Quit and reload while Downed, and while a Recall waits: both states come back.
- How the paced Downed turns feel in a real game (5 s per turn, scene switches while waiting) and the ghost's paralysed look: not seen.
- Android/iOS not run.

## Follow-ups / out-of-lane findings
- Remove MEC-01's four static `CoopCell.install()` blocks now that `Coop` installs it (`coop/mission/cell/**`).
- `MissionPenalty.returnTo` could be made public so MEC-02 reuses it instead of its copy (`coop/mission`, MEC-00's lane).
- DENY carries no request id: a DENY meant for something else that happens to use one of the revive reasons while a Recall waits gives the scroll back early (harmless: the player can read it again).
- An abort's `REVIVE{inPlace}` while our Recall waits for the same prisoner uses the scroll up (we can't tell it from ours).
- `DownedFx` isn't re-added after a scene change while the ally stays Downed (see decisions).
- Recall placement and MEC-01's trap: whoever merges next on co-op gen regenerates the co-op goldens.

## Open questions
- The pacing choice (one Downed turn per 5 s of running clock, on the actor thread) instead of a racing paralysis: confirm.
- Shop stock chance (50% per shop floor) and price (60 gold base value): BAL-01?
