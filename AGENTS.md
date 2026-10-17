# AGENTS.md — Rules & context for AI coding agents

> This is the **canonical** instruction file for every AI model working in this repo (Claude, Gemini/Antigravity, Codex, etc.).
> `CLAUDE.md` and `GEMINI.md` only point here. Edit this file, not those.
> Humans: this is also the fastest onboarding read.

---

## 1. What this project is

A **non-commercial online co-op mod** of **Shattered Pixel Dungeon (SPD)**, working title **"Logic Link PD"**.

- **Base:** upstream SPD **v4.0.2** (`github.com/00-Evan/shattered-pixel-dungeon`, tag `v4.0.2`, commit `57a4e06a4`).
- **Goal:** 2 friends play together online across **Android, iOS and Desktop**. Distribution is private (APK / TestFlight), not a commercial store release.
- **Co-op model: "Parallel Worlds".** Each player simulates their own copy of the same seeded dungeon. The ally appears as a **ghost**. Cooperation comes from **interdependent tasks** across the two worlds (see `docs/coop/game-design.md`).
- **Server:** a small **Go** server, authoritative for all *shared* state (missions, item transfers, timers). It is not a dumb relay.

A previous prototype on v3.3.8 exists in `../shattered-pixel-dungeon/` (Node relay). It is **reference only**. Do not port its code blindly; see `docs/coop/legacy-prototype-review.md` for its bugs.

## 2. Read before you code

| Doc | When |
|---|---|
| `AI_CONTEXT.md` | **First.** Current state, next tasks |
| `docs/coop/README.md` | Index, always |
| `docs/coop/WORKFLOW.md` | Branches, commits, worklogs, gate, CI |
| `docs/coop/game-design.md` | Any gameplay/mechanics work |
| `docs/coop/architecture.md` | Any network/server/sync work |
| `docs/coop/decisions.md` | Before proposing a design change: check whether it was already decided |
| `docs/coop/licensing.md` | Before touching credits, branding, headers, assets, services |
| `docs/recommended-changes.md` | Upstream author's guide for forks |

If a doc and the code disagree, **stop and ask** rather than guessing which one is right.

## 3. Golden rules (non-negotiable)

### 3.1 Licensing (GPLv3)
1. Never remove or alter `LICENSE.txt` or the GPL/copyright header at the top of any upstream file.
2. **Never remove any existing credit** in `AboutScene.java`. You may reposition credits or add new ones. The CC-BY freesound credits are legally required.
3. New source files get the standard GPLv3 header, with an extra line `Logic Link PD — Copyright (C) 2026 <contributors>` below the upstream lines.
4. Never add telemetry, analytics, ads, or tracking of any kind.

### 3.2 Single-player must stay vanilla
5. All co-op behavior is gated on **`Coop.isCoopRun()`**, a flag **persisted in the run's save bundle**. **Never gate gameplay on socket state** (`isConnected()`). The connection can drop; the run's rules must not change.
6. A non-co-op run with seed X must generate **exactly** what vanilla v4.0.2 generates with seed X. Don't change global probabilities, room lists, or loot tables outside the co-op gate.

### 3.3 Determinism (both worlds must match)
7. Both clients must generate **identical levels** from the same seed. Any co-op level-gen code:
   - runs inside the existing seeded generator scope (`Random.pushGenerator(...)` as used by level gen),
   - consumes RNG **identically on both clients**. Never `if (network-state) Random.x()`,
   - may branch on the player's **role (A/B)** only for *what is shown/locked*, never for *how much RNG is consumed*.
8. Visual-only code (sprites, effects, particles) must not consume gameplay RNG inside level generation.
9. Never use wall-clock time, `HashMap` iteration order, or device-specific values in anything that affects level gen.

### 3.4 Minimal upstream diff (so we can merge future SPD updates)
10. Put co-op code in the package **`com.shatteredpixel.shatteredpixeldungeon.coop`** (sub-packages: `net`, `mission`, `items`, `levels`, `ui`, …).
11. Changes inside upstream files are **thin hooks**: one or two lines calling into `coop`, marked with a `// COOP:` comment. No large logic blocks in upstream files.
12. Use imports, not inline fully-qualified class names (match upstream style: tabs, braces, naming).
13. Before adding an override or hook, check whether upstream already has an extension point (buff, room type, `Generator` category, `Bundlable`).

### 3.5 IDs, saves, compatibility
14. **Co-op terrain IDs use the reserved range 200–255** (`Terrain.flags` is `int[256]`). Upstream keeps adding IDs at the low end. The old prototype's `COOP_DOOR = 39` now collides with upstream `CUSTOM_DECO_WTR = 39`.
15. Never lower `appVersionCode`. Start from upstream's (922) and increment.
16. New save fields use new, namespaced bundle keys (`coop_*`). Old saves must still load.
17. Co-op runs use **their own save slots**. Never overwrite a single-player slot.

### 3.6 Networking
18. The **server is authoritative** for shared state: mission state, item transfers between worlds, timers. Clients send *requests* ("I want to give key #12"), the server decides and broadcasts *results*.
19. Every gameplay message carries `depth` and `branch`. Receivers ignore messages for a floor they're not on, unless the message is explicitly cross-floor.
20. All game mutation happens on the **GL/render thread** (`Gdx.app.postRunnable`). The socket thread only parses.
21. Outgoing messages sent before the socket is open are **queued**, never silently dropped.
22. The protocol is **versioned**. Client and server reject mismatched protocol/game versions at handshake.

### 3.7 Code quality
23. Player-facing text goes through `Messages` / `.properties` (English base). No hard-coded strings.
24. No `System.out.println`. Use `Gdx.app.log/debug` behind a debug flag.
25. Every new item, buff, room or mechanic gets an entry in `docs/coop/game-design.md` (what it does) and, if it touches the network, in `architecture.md` (what it sends).
26. Don't commit `node_modules`, `build/`, `.gradle/`, or secrets. The server reads config from env vars.

## 4. Repo map (co-op relevant)

```
spd-upstream/                 ← the base we build on (v4.0.2)
├── AGENTS.md                 ← this file (CLAUDE.md / GEMINI.md point here)
├── core/src/main/java/com/shatteredpixel/shatteredpixeldungeon/
│   ├── coop/                 ← ALL co-op code goes here (to be created)
│   ├── Dungeon.java          ← global run state (Dungeon.hero is referenced ~2160×)
│   ├── actors/ items/ levels/ scenes/ sprites/ ui/ windows/
├── docs/coop/                ← co-op documentation
├── server/                   ← Go server (to be created)
├── android/ ios/ desktop/    ← platform launchers
└── services/                 ← news/updates (to be disabled or redirected)
```

Key engine facts:
- **Turn-based actor loop** (`actors/Actor.java`). A world's time only advances when its hero acts. This matters for all timers; see game-design §6.
- **Save system = `Bundle`** serialization of the whole run. It can be reused for snapshots.
- **Level transitions** go through `InterlevelScene` modes. To send a hero to another depth use `Mode.RETURN` with `returnDepth/returnBranch/returnPos`. **Never** set `Dungeon.depth` directly before a transition (that corrupts saves).

## 5. Build & run

- **JDK 17+** required (Gradle 9.5 wrapper). The game bytecode targets Java 11.
- Desktop: `./gradlew desktop:debug`
- Android: `./gradlew android:assembleDebug`
- iOS: needs macOS + Xcode (RoboVM/MobiVM). See `docs/getting-started-ios.md`.
- Server (Go, once created): `cd server && go run ./cmd/server`

## 6. Workflow rules for agents (full process: `docs/coop/WORKFLOW.md`)

- **Read `AI_CONTEXT.md` first.** It's the current project state, maintained by the owner.
- **Plan first** for anything touching more than ~3 files or any network message: write the plan, get approval, then code.
- **One task = one ID = one branch `agent/<TASK-ID>-<slug>` = one worktree** in `../spd-wt/<TASK-ID>`.
- **Lane rule:** only edit the paths the task grants. Hot paths (gradle files, `.github/**`, `Makefile`, `.githooks/**`, `scripts/**`, `server/go.mod`, protocol files, **any upstream non-`coop` Java file**) need an explicit grant.
- **Never push, never merge, never rebase `dev`/`main`, never `--no-verify`.** The owner merges and pushes.
- **Commits:** `<TASK-ID>: <imperative summary>`, one logical change each, via `make commit FILES="..." MSG="..."` after `make check` passes.
- **Don't edit** `AI_CONTEXT.md`, `docs/coop/decisions.md` or `docs/changelog/**` on agent branches. Write `docs/worklog/<TASK-ID>.md` instead.
- **Stop conditions:** a needed decision, an out-of-lane change, a failing gate you can't fix in lane, or a conflict with `dev`. Write the question in the worklog and stop.
- **Final report:** literal `git log --oneline dev..HEAD`, `git diff --stat`, every verification command with its **literal output**, and what was not verified. Never write illustrative or reconstructed output.
- No full 40-char SHAs in files on agent branches. Never `--amend` a commit whose hash is cited.
- If you're unsure whether something breaks single-player or determinism, **say so explicitly**. Don't hide it.
- **Language:** code, comments and docs in **English**. Chat with the project owner (Omar) in **Egyptian Arabic** unless asked otherwise.
- Any new agent instruction file (CODEX.md, .cursorrules, …) must be a short pointer to this file, never a copy.
