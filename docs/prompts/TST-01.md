# Prompt: TST-01 — determinism goldens from vanilla v4.0.2

> **How to use:** start an agent with its working directory set to the main repo
> `/home/omarmaarouf/CS tools/Antigravity/game dev/spd-upstream` and paste everything below the line.
> It creates its own worktree. No owner inputs are needed.

---

You are working on **Logic Link PD** (`omarmaarouf18/spd-coop`), an online co-op mod of Shattered Pixel Dungeon v4.0.2.

## Read first and follow strictly
1. `AGENTS.md`: rules (especially §3.2 single-player stays vanilla, §3.3 determinism)
2. `AI_CONTEXT.md`: current state (foundation done, this is the first test task)
3. `docs/coop/WORKFLOW.md`: task IDs, lanes, `make check` / `make commit`, worklog, report format, **literal outputs only**
4. `docs/coop/WORKFLOW.md` §6 "Determinism goldens": what this task implements

## Task
- **Task ID:** `TST-01`
- **Start:** `make worktree-new TASK=TST-01 SLUG=determinism-goldens` (from `dev`), then work in `../spd-wt/TST-01`. Write `docs/worklog/TST-01.md` using the template.
- **Lane (allowed paths):** `core/src/test/**`, `docs/worklog/TST-01.md`, `docs/coop/WORKFLOW.md` (§6 only, and only if the implementation differs).
- **Hot paths granted:** `core/build.gradle`, **test configuration only** (`testImplementation` dependencies, the `test {}` block, one extra Gradle task for regeneration). Nothing that changes how the game itself is built.
- **Forbidden:** any change under `core/src/main/**`, `SPD-classes/**`, `desktop/**`, `android/**`, `ios/**`, or `services/**`. If level generation can't be driven headlessly without touching game code, **STOP and ask**. Don't patch the game to make the test work.

## Why this matters
Every future co-op change has to prove two things: (1) a **non-co-op run is still exactly vanilla v4.0.2**, and (2) later, both co-op players generate the **same** world. This task builds the harness and records the **vanilla goldens**. They're recorded now, **before any co-op code exists**, so they're a true copy of upstream.

## Preconditions (verify and paste literal output)
- `git diff --stat master..dev -- core/src/main SPD-classes/src` must be **empty**, which proves the game code on `dev` is still pristine v4.0.2. If it's not empty, STOP.
- `git describe --tags master` → `v4.0.2`.

## Deliverables

### 1. Test infrastructure (`core/build.gradle`, test scope only)
- JUnit (pick JUnit 5 or 4, pinned to a release that's at least 14 days old) plus whatever headless libGDX pieces are needed (e.g. `gdx-backend-headless`, the **same gdx version** the project already uses, read from the existing build files, not guessed).
- `test {}` config: working directory / system properties as needed (e.g. so `Gdx.files.internal` resolves `core/src/main/assets`), a temp dir for preferences so tests never touch the user's real SPD settings or saves, and no network.
- A Gradle task `regenGoldens` (or a system property `-Dgoldens.regen=true` on the same test) that **rewrites** the golden file instead of comparing. Plain `test` must **never** write goldens.

### 2. Harness: `core/src/test/java/.../coop/test/LevelFingerprint*` (package under `com.shatteredpixel.shatteredpixeldungeon` is fine)
- Initialize the game headlessly the way a new run starts (study `HeroSelectScene` start → `Dungeon.init()` → `InterlevelScene.descend()`, and especially the **debug path in `InterlevelScene.descend()` that generates all prior levels in order**). Fixed parameters: hero class **WARRIOR**, **no challenges**, not daily, not custom-seed-text.
- For each seed: set `Dungeon.seed`, then generate **branch 0, depths 1 → 26 in order** (order matters, because the generator state carries over between floors, exactly like a real run).
- Also investigate **non-zero branches** that v4.0.2 can generate (quest/vault areas). If they can be generated deterministically in the same way, include them. If not, list them in the worklog as a follow-up. Don't guess.
- **Fingerprint per level:** a SHA-256 over a **canonical** serialization of:
  - `map[]` (terrain), width/height, feeling, entrance/exit / transitions
  - mobs: class + pos (+ HP/state if stable), sorted
  - heaps: pos, type, and per item class + level + quantity + cursed/known flags, sorted
  - traps (class, pos, visible/active), plants, blobs present at generation, custom tiles/walls, sorted
  - Prefer explicit fields over hashing raw `Bundle` output. If you use `Bundle`, canonicalize it (sorted keys, no non-deterministic IDs) and explain why it's stable.
- Store **component hashes** as well (`map`, `mobs`, `heaps`, `traps`, …), so a mismatch says *what* changed, not just *that* something changed.
- **State isolation:** fully reset static state between seeds (whatever `Dungeon.init()` doesn't reset: `Generator` decks, `Statistics`, limited drops, `Actor` ids, …). Prove it in a test: generating seeds `[A, B]` in one JVM gives the same fingerprints for B as generating `[B]` alone.

### 3. Goldens: `core/src/test/resources/goldens/vanilla-v4.0.2.json`
- **Seeds:** at least 8, fixed and documented: include small, large, and edge values (e.g. `0`, `1`, `42`, `123456789`, `Long.MAX_VALUE`-style if SPD allows, and a few `DungeonSeed` text-converted ones). State SPD's valid seed range and respect it.
- **Format:** `{ "upstream": "v4.0.2", "hero": "WARRIOR", "challenges": 0, "seeds": { "<seed>": { "1-0": { "all": "...", "map": "...", ... }, ... } } }`, pretty-printed with stable key order so diffs are readable.
- **Generated only from the pristine code** (see the preconditions).

### 4. Tests
- `VanillaGoldensTest`: compares every seed/depth/branch to the golden. On a mismatch, fail with a **clear message**: seed, depth, branch, which components differ (and for `map`, the first differing cell index + old/new tile).
- `IsolationTest`: the order-independence check from above.
- `RepeatabilityTest`: generating the same seed twice in one JVM gives identical fingerprints.
- **Runtime budget:** the whole suite should finish in under ~60 s on CI. Report the actual time.

### 5. Cross-JDK proof (important)
CI uses **JDK 17**, and the owner's machine currently runs **OpenJDK 27 EA**. The goldens must pass on both:
- Run the tests locally (literal output).
- If a JDK 17 is available locally (e.g. `/usr/lib/jvm/java-17*`), run it there too. Otherwise state that the cross-JDK proof will come from CI after the owner pushes. **Don't claim it passed.**
- If fingerprints differ between JDKs, STOP and report what differs (that would be a real determinism hazard for co-op, because players run different JVMs/ART).

### 6. Docs
- Worklog: the seeds and why, what's in the fingerprint and what's deliberately excluded, branch coverage, runtime, how to regenerate (`./gradlew :core:regenGoldens` or the property), and the rule from WORKFLOW §6: **goldens change only in `UPS-*` tasks or a deliberate, reviewed `regen`**.
- WORKFLOW.md §6: update only if the commands/paths differ from what's written there.

## Verification (literal outputs in the report)
- The preconditions above
- `./gradlew :core:test` (all tests pass, with timing)
- A deliberate-break check, done **outside** the lane and **not committed**: temporarily change one constant in level gen (e.g. a room count), run the test, paste the failure message (it should name seed/depth/component), then `git checkout -- <file>` and show `git status` is clean
- `make check` (java scope)
- `git diff --stat dev...HEAD` shows only lane paths

## Stop point
Final report in the WORKFLOW format, then **STOP**. Don't merge or push.
Also list proposed follow-ups, e.g.: a `regen-goldens.yml` workflow (WF-03), co-op goldens (role A == role B) once `ROOM-*`/`MEC-*` add co-op level gen, and other hero classes if any level gen depends on the class.
