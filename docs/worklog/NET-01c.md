# Worklog: NET-01c — co-op lobby, run start and co-op saves

## Task and scope
- Task ID: NET-01c (third slice of NET-01: the protocol reaches the game; first time two players start the same run)
- Lane (allowed paths): `core/src/main/java/.../coop/**`, `core/src/test/java/.../coop/**`, `core/src/main/assets/messages/windows/windows.properties` (a `###COOP` section), `docs/worklog/NET-01c.md`, `docs/coop/architecture.md` §2
- Hot paths granted (owner, chat 2026-10-17, "ابدا" to the 4 NET-01c decisions): thin `// COOP:` hooks in `Dungeon.java`, `GamesInProgress.java`, `scenes/TitleScene.java`, `desktop/.../DesktopLauncher.java`; plus `Bones.java` (see below)
- Branch: `agent/NET-01c-coop-run-lobby`, from `dev` (short `fd32742`)

## Decisions taken
Approved by the owner before coding:
1. **Thin hooks** in upstream files, each one line calling `Coop`, marked `// COOP:`.
2. **Co-op runs are unranked**: they carry a seed code (`Dungeon.customSeedText`), so SPD treats them as seeded runs (no rankings, no badges).
3. **No challenges in co-op v1**: `Dungeon.challenges = 0` at co-op `init`, so both worlds use the same rules.
4. **Neutral profile during co-op level gen** (`CoopProfile`): level gen reads profile state (guide/alchemy/lore pages found, `SPDSettings.intro()`, and `EntranceRoom.placeEarlyGuidePages` uses an *unseeded* generator). During a co-op `level.create()` every document reads as fully read and the intro is off; afterwards the real profile is restored exactly (snapshot via `Document.store`, `deletePage` + `restore`). No badge side effects (it bypasses `readPage`/`findPage`).

Taken while coding:
- **Extra hook in `Bones.java`** (one `if`, `//COOP:` comment): `Bones.get` places remains from a player's *earlier* runs even in seeded runs, which would differ between the two worlds. Co-op runs get no remains. Same spirit as decision 4; flagging it because the file wasn't in the original hook list.
- **`Coop.isCoopRun()` is persisted** (`coop_run` bundle key in the run's save); `Coop.onInit()` makes a run co-op only if `Coop.startRun` just prepared one, so a single-player run started afterwards is vanilla (tested).
- **Co-op save slots 101–106** (folders `coop1`..`coop6`): never a single-player slot. `GamesInProgress` lists only slots 1–N, so co-op saves don't show up in the vanilla "continue" list; they are resumed from the Co-op window.
- **Run start**: on `RUN_START` (or a WELCOME snapshot with a new `runId`) the client builds a `CoopRun` (runId, seed, roleSeed, slot, hero, room code, server URL, credentials) and enters depth 1 with `InterlevelScene.Mode.DESCEND` and the server's seed (never the custom-seed setting). If the run is already saved on this device, it continues the save instead.
- **Resume**: credentials (playerId, resumeToken, epoch, ack) are stored in the `CoopRun` on every save; loading a co-op save reconnects with them on the GL thread (`runOnRenderThread`, loadGame runs on the loading thread).
- **`CoopSession` ordering fix**: the room/run data is delivered to the listener *before* the state change, so a UI redrawing on `onStateChanged` sees it. Found as a crash on "Create room" (`NullPointerException` on a null room); regression test added (fails on the old order).
- **Title screen**: a full-width "Co-op" row under the last row of buttons. Splitting the Play row was tried first, but "Enter the Dungeon" overflows half a row.
- **`LLPD_PROFILE=b`** (desktop only): keeps a second player's settings and saves in `profile-b/`, so two copies of the game can play together on one machine.
- Hero names are title-cased in the lobby; the continue button reads `Resume <code> (depth N)` (the longer text overflowed).
- `CoopSession.tick()` is driven by a 1 s scheduler in `CoopConnection` (not a hook in the game loop).
- `game-design.md` unchanged: no new item, buff, room or mechanic yet (lobby and run start only).

## Changes per commit (subject only — NO SHAs)
1. `NET-01c: add the co-op lobby and run start (CoopRun, co-op saves, neutral-profile level gen)`
2. `NET-01c: worklog`

## Verification
- `make check` (cloud session, JDK 21): `== gate: all checks passed (scope: java docs secrets) ==`. Suites in that run: CoopLevelGenTest 7, BackoffTest 5, CoopClientTest 6, CoopSessionTest 17, LiveServerTest 1 skipped, ProtocolContractTest 2, IsolationTest 1, JsonTest 3, RepeatabilityTest 1, VanillaGoldensTest 1, all passing. **VanillaGoldensTest passing = single-player generation unchanged.**
- `CoopLevelGenTest`:
  - a fresh profile (guide pages not found, intro on) and a veteran profile generate the same co-op depths 1–5;
  - control: without the neutral profile the same two profiles *differ* (so the test can detect it);
  - the real profile is restored after generation;
  - co-op runs have no challenges and a seed code;
  - a single-player run after a co-op run is not co-op;
  - `CoopRun` survives the save bundle;
  - co-op slots use their own folders.
- Mutation check (not committed): disabling `CoopProfile` made `coopFloorsDontDependOnTheProfile` fail; restored.
- **End to end on one machine** (cloud, Xvfb, two desktop instances A and B with `LLPD_PROFILE=b`, the Go server from `dev` on :8080, not Docker):
  - A: Co-op → Connect → Create room → lobby shows `Room code: JN4Z9W`;
  - B: Co-op → Connect → Join room, code typed **lowercase** (`jn4z9w`) → server `room joined … slot 1`; both lobbies show `You` / `Ally`;
  - Ready on both → server `run started … seed 1739277844574` → both games enter depth 1 at once, without the intro window;
  - both saves (`coop1/` and `profile-b/coop1/`): same seed, same runId, `challenges 0`, same seed code, and **the same depth-1 map** (identical hash of the `map` array);
  - B killed and restarted → the Co-op window lists the saved run → Resume → server `hello … resumed:true sameEpoch:true ack:19`, `player resumed into room … phase running`; B is back on depth 1.

## Unverified
- Android / iOS (the transport isn't wired there yet: Android R8 rules and iOS transport are follow-ups); `wss://`.
- Floors after depth 1 being identical in a real game (see findings: they can diverge).
- The owner's machine (JDK 17 via WF-05); the cloud gate ran on JDK 21.

## Follow-ups / out-of-lane findings
- **Floors after 1 can diverge between players.** Generator decks (`Generator` category probabilities), `LimitedDrops` and other run state change as each player plays (what they pick up, use, identify), and later floors read that state. Depth 1 is identical; depth 2+ is only identical if both played identically. Needs a design task (e.g. co-op level gen from a state that depends only on the seed and depth).
- **Known non-determinism in upstream level gen** (affects co-op only if those rooms appear): `SecretLaboratoryRoom`/`SecretLibraryRoom` and `ShopRoom.ChooseBag` iterate a `HashMap<Class, …>` (identity-hash order); `VaultLevel` uses `Collections.shuffle` with the JDK's own RNG.
- Guide/alchemy/lore pages don't drop in co-op runs (side effect of decision 4). Acceptable for v1; revisit if wanted.
- Startup `JSONException JSONObject["key"] not found` logs come from the vanilla `GitHubUpdates` service, unrelated; BRD-01 disables it.
- Wire `ProtocolContractTest` into the gate's `protocol` stage and CI's `protocol-contract` job (hot paths), carried over from NET-01b.
- Next: **GHO-01** (ghost, compass, pings), the first playable build.

## Open questions
- none for this slice.
