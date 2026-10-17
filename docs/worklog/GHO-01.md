# Worklog: GHO-01 — ghost presence, ally compass/status, pings, quick messages

## Task and scope
- Task ID: GHO-01 (first playable milestone: each player sees the ally as a ghost, knows where the ally is, and can ping / send quick messages)
- Lane (allowed paths): `core/src/main/java/.../coop/ghost/**`, `coop/ui/**` (new files), `core/src/test/**` (co-op tests only), `core/src/main/assets/messages/**` (new co-op keys, English base), `docs/worklog/GHO-01.md`, `docs/coop/game-design.md` §3/§8; in `coop/Coop.java` only new methods at the end under `// --- GHO-01 ---`
- Hot paths granted: thin `// COOP:` hooks in `actors/hero/Hero.java`, `scenes/GameScene.java`, `ui/StatusPane.java` (`CellSelector.java` was not needed)
- Not touched (parallel PRO-02/SRV-02): `coop/net/**`, `coop/levelgen/**`, existing `Coop` methods
- Branch: `agent/GHO-01-ghost-presence`, from `dev`

## Decisions taken
- **Lane extension, flagged:** `coop/CoopConnection.java` (in `coop/`, not `coop/net/`) gets 2 forwarding lines: its `onPresence` stub (`//GHO-01 draws the ally from these`) now calls `CoopGhosts.onPresence`, and `onAllyStatus` also calls `CoopGhosts.onAllyStatus` (for `lastSeen`). This is the "wired via `CoopConnection`" path of the task; `coop/net/**` is untouched. `Coop.java` also gains imports (rule 12) next to the new methods.
- **When GHOST is sent:** `Hero.act()` start (`Coop.onHeroTurn`): the hero's previous action has resolved, so `pos` is the new cell (the prototype sent the old one). The snapshot is taken on the actor thread and posted to the GL thread. `PresenceSender` (pure logic): at most one per hero turn (keyed by `Actor.now()`; `act()` runs twice on a turn where the hero waited for input), unchanged state not resent, idle resend every 5 s with anim `idle` (never replays an attack; a dead hero is resent as `die`), refused sends retried once per second, forced sends on level shown / our reconnect (session back to RUNNING) / ally back online.
- **anim:** `run` = moved since the last turn; `attack` / `operate` from 1-line hooks in `Hero.onAttackComplete` / `onOperateComplete`; `die` from a hook in `Hero.die` after the real death is decided (sent at once, no next turn); `zap` is **inferred** (the hero spent time without moving/attacking/operating and its sprite plays a non-looping animation), because wands/throws tell the Hero nothing; a precise `zap` needs a hook in `Item`/`Wand` (out of lane). Otherwise `idle`.
- **Neutral hero guard:** only `Dungeon.hero` is reported (DET-01's level-gen `new Hero()` never is).
- **Ghost sprite (`GhostSprite extends CharSprite`)**: hero spritesheet + armor-tier film of the ally's class (`HeroSprite.tiers()`, same frames as `HeroSprite`), never linked to a Char and never touches `Dungeon.hero`/its sprite (the v3.3.8 bug). Own move tween (CharSprite's calls back into a Char). Alpha 0.5 + cool tint; offline = grey, alpha 0.25; opaque mode = alpha 1, light tint.
- **Layering:** `GhostLayer` is added by GameScene between the heap group and the mob group (above floor/items, under mobs and our hero). Draw rule `GhostLayer.shouldDraw`: same depth+branch and `level.visited[pos] || level.mapped[pos]`. The layer rebuilds from the last received GHOST, so level change / save-load / reconnect restore it.
- **API for later tasks (`GhostAlly`)**: `cell()`, `isVisible()`, `isOnline()`, `setOpaque()/isOpaque()`, `targetCell()` / `isTarget(cell)` (Healing throw, MEC-02), `sprite()`.
- **Ally state (`AllyState`)**: last GHOST, the ally's floor (from GHOST or MARK), connection from `ALLY_STATUS` or the room snapshot (`players[].connected`, polled when `CoopConnection.room()` changes; `ALLY_STATUS` is only sent on changes, not at run start). A received GHOST implies online. Nothing of this is saved with the run (in-memory; after an app restart the ally's 5 s idle resend restores the ghost).
- **Compass/chip (StatusPane hook)**: `AllyCompass` = tinted second compass arrow around the portrait (same floor), or "↓N"/"↑N" ("≠" for the other branch of the same depth). `AllyStatusChip` under the status pane (top-left when the pane sits at the bottom with the large UI): ally hero avatar, `hpPct · Ddepth`, online / offline + time since last seen / reconnecting. Tapping it opens quick messages.
- **Ping input**: `PingInput` is added right after the CellSelector, so pointer events reach it after the HUD/windows and before the CellSelector. Hold 0.5 s (touch or mouse) or middle-click a cell; only cells we've seen. A hold resets the CellSelector's press so releasing it doesn't walk there. Picker = `WndOptions` with 4 kinds. `CellSelector.java` not modified.
- **Desktop keys**: the input layer only forwards keys bound to an action, so `CoopActions` registers two `GameAction`s with hard bindings, `T` = quick messages, `G` = ping under the mouse (a player's own binding for the key wins; both appear in the key bindings window as `windows.wndkeybindings.coop_*`). Registered on the first co-op GameScene only.
- **Marks (`Marks`)**: 5 s lifetime, local rate limit 1/s, a newer ping on the same cell replaces the older, received pings for another floor are ignored (they update the ally floor). Drawn by `MarkLayer` after the fog; `CoopHud` draws edge arrows for off-screen marks, the quick-message toast, and drives `CoopGhosts.tick()` every frame.
- **Quick messages**: `QuickPhrases` ids 0–7 ↔ `coop.ghost.quickphrases.*` (wire format, append-only). Unknown ids (newer client) are ignored.
- **Messages**: new keys in `messages/windows/windows.properties` (`###COOP (GHO-01)` section), English only.
- No new gameplay item/buff/room/mechanic and no new message type; game-design §3/§8 updated to match. `architecture.md` not in lane: what is sent is already PROTOCOL.md §5.3.

## Changes per commit (subject only — NO SHAs)
1. `GHO-01: add the ally ghost, ally compass/status, pings and quick messages`
2. `GHO-01: add ghost presence tests`
3. `GHO-01: document pings, quick messages, chip, compass and ghost (game-design §3, §8)`
4. `GHO-01: worklog`

## Verification
- `make check`: see the final report (run on the final tree). Earlier run on the code + tests: `== gate: all checks passed (scope: java secrets) ==`, 87 tests, 0 failures, 1 skipped (`LiveServerTest`, needs a server). Suites: CoopLevelGenTest 7, GhostPresenceTest 10, MarksTest 5, PresenceSenderTest 9, QuickPhrasesTest 3, CoopBalanceAndIdsTest 6, CoopDeterminismTest 5, CoopHashOrderTest 1, CoopStableIterationTest 3, BackoffTest 5, CoopClientTest 6, CoopSessionTest 17, LiveServerTest 1 (skipped), ProtocolContractTest 2, CoopGoldensTest 1, IsolationTest 1, JsonTest 3, RepeatabilityTest 1, VanillaGoldensTest 1.
- New tests: post-move `pos` on the wire (a real `CoopSession` in a running room, frame re-decoded as c2s: `pos` 45 after a 44→45 step, anim `run`); hook anims; neutral hero never reported; throttle (once per turn, deferred same-turn change, no resend of unchanged state), idle resend at 5 s as `idle`, retry after a refused send, forced sends, death on the same turn; other depth → hidden and compass `↓1` / `↑2` / `≠`; never drawn on unseen cells (visited / mapped / outside the level); relayed GHOST/MARK update the ally, other-floor MARK ignored but updates the ally floor; offline chip `offline 2m` / `reconnecting...`; room snapshot gives the ally's connection; mark expiry 5 s, same floor only, rate limit 1/s, replace on same cell, kinds = PROTOCOL.md; all 8 `phraseId` ↔ keys ↔ English `.properties` text; presence never draws gameplay `Random` (seeded stream unchanged after turns, received GHOST/MARK, local ping, tick).
- Mutation checks (temporary, restored, `diff -q` clean): draw rule ignoring visited/mapped → `neverDrawnOnACellWeHaventSeen` fails (`unseen cell ==> expected: <false> but was: <true>`); per-turn throttle removed → `atMostOnePerHeroTurn` and `deferredSameTurnChangeGoesOutWithTheIdleResend` fail.
- **End to end** (local, Docker server from this tree on `127.0.0.1:18090`, two desktop clients from `desktop:release` with `LLPD_PROFILE=gho01a` / `gho01b`, on a headless mutter + rooted Xwayland display driven by xdotool): A created room `B47CRE`, B joined, both ready, `run started … seed 2490549670646`; A moved → B saw the ghost move to the same cell; A pinged DANGER (hold) → marker on the same cell in both worlds, B's log `Ally (Warrior) pinged: Danger`; B pressed `T` → `Help!` → A's log `[GAME] @@ Ally (Warrior): Help!` + toast; A fought (B's chip went to `60% · D1`); A descended → B's chip `D2`, ghost hidden, compass `↓1`; A's view `↑1`; A back on D1 at the exit (a cell B never saw) → B draws no ghost, compass arrow only; B killed (`kill -9`) → A's chip `offline 5s`, ghost greyed; B resumed (`resumed:true sameEpoch:true`) → ghost back to normal on both sides at once; middle-click ping and `G` ping work; off-screen ping → edge arrow on B. Screenshots and the server log are in the final report.
- Single-player impact: none intended. Every hook is a no-op when `Coop.run()` is null (`Coop.onHero*`, `addGhostLayer`, `addGameHud`, `addStatusHud`); `CoopActions` key bindings are registered only by a co-op GameScene. `VanillaGoldensTest`, `CoopGoldensTest`, `CoopDeterminismTest`, `CoopLevelGenTest` unchanged and green.
- Determinism impact: none. Presence code never uses gameplay `Random` (tested) and runs outside level gen; goldens unchanged.

## Unverified
- Android / iOS: long-press on a real touchscreen, the mobile layout of the chip (small UI) and the T/G keys don't apply there; only the desktop large UI was driven end to end.
- Kill-and-resume in the same process (save/load without restart) restores the ghost from memory by design; only the app-restart path was run E2E.
- `zap` inference was not exercised E2E (no wand on depth 1).
- The ally's `ALLY_STATUS.lastSeen` uses the server clock; "offline Ns" compares it with the local clock (skew shows as a few seconds).

## Follow-ups / out-of-lane findings
- **For MEC / SYN / ROOM tasks (what the ghost will need):**
  - MEC-02 Downed: a `downed` animation/state on `GhostSprite` (e.g. the die frame held, tinted) and an anim value for it (PROTOCOL `anim` has no `downed` today: a v1.1 enum value or a field in `ALLY_DOWNED`); the chip's state line (OK / In mission / Downed).
  - MEC-02 Healing revive: `GhostAlly.targetCell()` / `isTarget(cell)` exist; the throw path needs a hook where a thrown potion lands (out of lane) and must accept the ghost's cell as a target even though no Char stands there.
  - SYN-01 Super Sync: a "materialized" state (opaque, solid, normal colors), or hide the ghost while the Projection Actor exists in the host world; `GhostAlly.setOpaque` covers the visual part only.
  - ROOM-01 Twin Switches: call `GhostAlly.setOpaque(true/false)` when both players are inside the room.
  - Dark Floor: ghost light radius (TorchHalo on `GhostSprite`).
  - A precise `zap` anim needs a 1-line hook in `Item.cast`/`Wand` (today inferred).
  - MEC-00 mission HUD will want room near the chip; the chip is a `Button` under the status pane.
- `CoopConnection.allyConnected()` (used by the lobby) is only set by `ALLY_STATUS`, so it reads `false` after a fresh run start; `CoopGhosts` uses the room snapshot as well. Worth aligning in a NET follow-up.
- The server does not log relayed GHOST/MARK/QUICK (by design, §5.3), so E2E evidence for them is on-screen and in the game log.

## Open questions
- Keep `T`/`G` as co-op hard bindings, or add proper `SPDAction` entries (upstream file, not granted)?
- Should a ping on a cell the *receiver* hasn't seen be drawn (today: yes, the marker shows a location, not terrain), or only on seen cells like the ghost?
