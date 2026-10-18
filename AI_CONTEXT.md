# AI Context — current project state

> **Owner-maintained.** Agents read this first (after `AGENTS.md`) but don't edit it on agent branches. Write `docs/worklog/<TASK-ID>.md` instead.
> Last updated: 2026-10-17 (fold through NET-01c; D-019, D-020, D-021)

## Project summary
Logic Link PD: a non-commercial online 2-player co-op mod of Shattered Pixel Dungeon v4.0.2, for Android, iOS and Desktop. It uses the Parallel Worlds model with interdependent co-op tasks, and a Go server that is authoritative for shared state.

## Phase
**Building the first playable.** Lobby + run start work end to end locally (owner tested: two desktop clients + `make server-dev`, same room, same seed). Players don't see each other yet (GHO-01).
**Testing is local-only** (D-017). **Owner priority:** a fully playable game with every designed feature, before branding/publishing.

## Done
- Foundation: `DOC-01`, `WF-01..05` (gate, CI, Node 24 actions, JDK auto-pick, `make server-probe`), `SRV-00`, `OPS-01` part 1 (D-018).
- `TST-01`: vanilla v4.0.2 determinism goldens (9 seeds); `VanillaGoldensTest` guards single-player.
- `PRO-01`: protocol v1 (`docs/coop/protocol/PROTOCOL.md` + fixtures, Go package, contract tests).
- `SRV-01`: lobby, run start (same seed), presence relay, resume, runs survive a server restart.
- `NET-01a/b/c`: Java websocket transport, protocol client (`CoopSession`, Java contract tests), co-op lobby UI (`WndCoop`), `CoopRun`, co-op save slots 101–106, neutral profile during co-op level gen, `LLPD_PROFILE=b` second local player. Upstream hooks so far: `Dungeon`, `GamesInProgress`, `Bones`, `TitleScene`, `DesktopLauncher` (all `// COOP:`).

## Roadmap to fully playable (in order; same-step items run in parallel)
**Step 1 (now, in parallel):**
- `GHO-01` ghost presence, ally compass/status, pings, quick messages → first playable
- `DET-01` co-op level gen depends only on seed+depth, stable iteration, co-op goldens (D-019)
- `PRO-02` protocol v1.1 for missions/items/downed/super sync + `SRV-02` server mission engine (timers, item transfer, downed window)

**Step 2:** `MEC-00` client mission framework (forced turns, mission HUD, timer) + `ITM-01` item network IDs, shared floor loot, drop-to-ally (D-021) + `LINK-01` door mirroring + `DET-02` per-player loot overlay (after DET-01)
**Step 3:** `MEC-01` The Cell (Binding Trap, D-020) · `MEC-02` Downed + Scroll of Recall + Healing-potion revive · `BOSS-01` shared boss HP
**Step 3b (parallel with step 3, D-023):** `PRO-03`+`SRV-03` (protocol + server for shared mobs/locked doors/traps) · `MOB-01` shared monsters in different places · `LINK-02` locked doors + traps · `ITM-02` shared keys
**Step 4:** `SYN-01` Super Sync (player-controlled projection + Remote View)
**Step 5:** `ROOM-01` co-op room framework + puzzles (Twin Door, Twin Switches, Mirror Lock, Pressure Bridge, Rune Wall, Totem Shield, Dark Floor, Item Shrine) · `GATE-01` mandatory co-op gates
**Step 6:** `BAL-01` tuning, `UI-01` strings/sprites/tutorial, `QA-01` full 1→26 co-op run
**Later:** `BRD-01` branding, mobile/iOS, OPS-01 part 2 (VPS)

## Follow-ups
- **DET-01 side effects (fix in `DET-02`, per-player loot overlay):** co-op level gen uses a neutral hero, so these no longer appear in co-op: Dried Rose petals, Hourglass sand bags in shops, Cached Rations, trinket effects on gen, class-specific shop bag and Vault Mirror reward. Plan: spawn them **per player at runtime** when that player first enters a floor (local-only, not in the shared pool). Also: an artifact a player got from a mob/transmutation can generate again later (de-dup via run state shared on the server), and mob-held items (Mimic loot, Statue weapon) lose their `itemNetId` after reload (ITM-01).
- **Grants already decided for ITM-01:** `items/Item.java` (2 lines: store/restore `itemNetId` in the item bundle). The co-op food boost stays deck-based (≈30% on average, no `Generator.java` hook).
- Local builds: `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk` (the default `java` 25 is a headless JRE). The gate auto-picks a JDK by itself.
- GitHub settings: default branch → `dev`, protect `main`. Delete the merged cloud branch `claude/optimistic-ride-kjpruf`.
- Wire `ProtocolContractTest` (Java) into the gate's `protocol` stage and CI's `protocol-contract` job. Add the pre-`WELCOME` numbering line to PROTOCOL.md §2 (done in PRO-02).
- Android release: R8 rules for Java-WebSocket. iOS transport. `wss://` untested.
- Guide/alchemy/lore pages don't drop in co-op runs (side effect of the neutral profile). Revisit later.
- `OPS-01` part 2 (VPS) deferred (D-017). Pin the `caddy` tag then.
- `WF-03` follow-up: `APP_CLIENT_ID` + `client-id:` in build-and-publish (one deprecation warning).

## Open questions (owner)
See `docs/coop/game-design.md` §11. Blocking later steps: mandatory gate floors (step 5), real death outside missions (step 3), ghost FOV sharing, a prisoner-side puzzle in The Cell, thrown weapons in Super Sync. App name/package only matters for BRD-01.

## Standing rules
`AGENTS.md` (code rules) + `docs/coop/WORKFLOW.md` (process). Any new agent instruction file (CODEX.md, .cursorrules, …) must be a short pointer to `AGENTS.md`, never a copy.
