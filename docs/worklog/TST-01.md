# Worklog: TST-01 — Vanilla determinism goldens harness

## Task and scope
- Task ID: TST-01
- Lane (allowed paths): `core/src/test/**`, `docs/worklog/TST-01.md`, `docs/coop/WORKFLOW.md` §6
- Hot paths granted: `core/build.gradle` (test configuration only)
- Branch: `agent/TST-01-determinism-goldens`, from `dev` (base `8caf8fe77`; `dev` has since
  advanced to `2f8fe43bb` (WF-04) — `git merge-tree --write-tree HEAD dev` exits 0, clean)

## Decisions taken
- All harness code lives in `core/src/test/.../coop/test/` (test scope, never shipped); no game
  code is touched or patched — upstream nondeterminism is handled only by pinning global state
  from the harness, or by flags on the test JVM.
- A floor's fingerprint is a SHA-256 per component (`meta, map, mobs, heaps, traps, plants,
  blobs, custom, transitions`) plus an `all` digest over them, in fixed order. The map canonical
  form is run-length encoded (`count:tile,...`) so failures can name the first differing cell.
  Every collection is sorted or fixed-order, so fingerprinting itself cannot leak map iteration
  order.
- The golden (`core/src/test/resources/goldens/vanilla-v4.0.2.json`) covers 9 seeds: `0`, `1`,
  `42`, `123456789`, `TotalSeeds/2`, `TotalSeeds-1`, and the text seeds `"logic link pd"`,
  `"2026-10-17"`, `"ABC-ABC-ABC"`. A run generates floors 1–26, and a quest branch floor is
  generated immediately when its `BRANCH_EXIT` transition appears on the main floor. This
  canonical order is a convention (deck state is order-sensitive); it is documented in
  `VanillaRun`'s javadoc.
- Goldens are written only by `./gradlew regenGoldens` (`-Dgoldens.regen=true`); a plain
  `./gradlew :core:test` only reads and compares them.
- `forkEvery = 1`: one JVM per test class, so `IsolationTest`'s first `generate` call really is
  the first generation in that process.
- Upstream nondeterminism found and how it is neutralized (all from the harness):
  1. **Journal meta progression.** A fresh profile has the Guidebook / `GUIDE_SEARCHING` page
     unplaced; `EntranceRoom.placeEarlyGuidePages` places them using an unseeded
     `Random.pushGenerator()` (entropy `java.util.Random`). That leaks into seeded passes:
     `RegularPainter.paintGrass` skips its `Random.Float()` draw on cells that hold a heap, so
     the placement shifts the whole sub-generator stream (grass, deco, traps shimmer). The
     harness pins the snapshot `Document.ADVENTURERS_GUIDE` states `GUIDE_INTRO=READ`,
     `GUIDE_SEARCHING=FOUND`, so the early placements never fire and every remaining guide item
     is placed by the seeded path.
  2. **`VaultLevel.setupConsumables()`** calls `java.util.Collections.shuffle(...)` with the JDK's
     private, entropyLR-seeded `Collections.r` (4 call sites). Pinned by reflection to
     `new Random(1L)` per run; test JVM needs `--add-opens java.base/java.util=ALL-UNNAMED`.
  3. **`VaultLevel.T2SolveItems` / `T3SolveItems`** static arrays are shuffled in place and never
     reset; snapshotted at start and restored per run.
  4. **`SecretLaboratoryRoom` / `SecretLibraryRoom`** pick potions/scrolls via
     `Random.chances(HashMap<Class<?>,Float>)`, which iterates the map in identity-hash order.
     That order depends on class-loading/allocation history, i.e. on the JVM, so the *same
     seeded run* produces different secret-room loot in different JVMs. Neutralized for tests by
     `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (constant identity hash), which makes
     every such iteration order deterministic.
  5. **`ShopRoom.ChooseBag`** breaks ties by iterating a `HashMap<Bag,Integer>`; same class of
     problem (fixed by 4 for tests). The fingerprint additionally normalizes any `Bag` subclass
     to `"Bag"` so shop-bag identity can never leak into a hash.
- `IsolationTest` covers generation-order independence (seed A alone vs. A after B, in a fresh
  JVM); `RepeatabilityTest` covers same-seed-twice in one JVM; `VanillaGoldensTest` covers the
  golden itself, and because goldens are produced by a different JVM invocation it doubles as
  the cross-JVM check.

## Changes per commit (subject only — NO SHAs)
1. `TST-01: add vanilla generation determinism goldens harness and golden file`

## Verification
- `make check` — PASS (`== gate: all checks passed (scope: java secrets) ==`)
- `JAVA_HOME=$HOME/jdk17 ./gradlew :core:test --rerun-tasks` — `BUILD SUCCESSFUL in 8s`
  (`real 0m8.591s`; run twice back to back, both `BUILD SUCCESSFUL in 8s`, `real 0m8.590s`).
  6 tests: `VanillaGoldensTest`, `IsolationTest`, `RepeatabilityTest`, `JsonTest` ×3 methods.
- Cross-JDK: `JAVA_HOME=$HOME/jdk17 ./gradlew :core:test --rerun-tasks
  -Pllpd.testJvm=/usr/lib/jvm/java-27/bin/java` — `BUILD SUCCESSFUL in 8s`. Gradle itself stays
  on the required JDK 17 (Gradle 9.5 rejects 27-ea), only the test workers run on 27. Literal
  executor command from `--info`:
  `/usr/lib/jvm/java-27/bin/java ... --add-opens java.base/java.util=ALL-UNNAMED -XX:+UnlockExperimentalVMOptions -XX:hashCode=2 ...`
  This also proves the goldens do not depend on the JDK version (`java.util.Random`,
  `Collections.shuffle`, `HashMap` iteration under a constant hash).
- Regeneration is stable: `./gradlew regenGoldens` run twice in separate invocations produced
  byte-identical files, md5 `d04a9d2507689d942ab38690b9ece9d4` (707,611 bytes).
- Deliberate-break check (temporary, reverted): changing the first `SCROLL.defaultProbs` weight
  from 3 to 4 in `items/Generator.java` made `VanillaGoldensTest` fail with e.g.
  `seed=0 floor 11-0: differs in [map, mobs, heaps, traps, blobs, transitions] (first differing
  cell 80: golden tile=4, generated tile=12)`.
  After `git checkout -- <file>`, `git status --porcelain` shows only this task's files.
- Earlier in-JVM debug (temporary `DebugNondetTest`, deleted): all 9 seeds generated twice in
  one JVM are IDENTICAL, 28 floors each.
- Cross-JVM convergence check (temporary debug classes, deleted): four test classes with
  different pre-generation work (nothing / read golden / parse JSON / full golden read) all
  produced the same floor-2 fingerprint `1cbff23e...` once the test JVM used
  `-XX:+UnlockExperimentalVMOptions -XX:hashCode=2` (before the flag they differed — that is the
  secret-room bug above).
- Single-player impact: none. `git diff master..HEAD -- core/src/main SPD-classes` is empty; all
  changes are test scope plus test JVM configuration.
- Determinism impact: this task defines the goldens (new file); they were recorded under the
  configuration described above.

## Unverified
- GitHub CI not run for this branch; `-XX:hashCode=2` is a HotSpot flag. Verified locally on
  Temurin 17.0.19 and 27-ea; it would be rejected by non-HotSpot JVMs (e.g. OpenJ9). The repo's
  CI is Temurin-based, but this is not yet proven on CI.
- macOS/Windows were not run. All verification was on Linux.
- Android/iOS on-device determinism is out of scope here.

## Follow-ups / out-of-lane findings
- The upstream nondeterminism in tasks 4 and 5 above can produce *different loot from the same
  seed on two devices* (secret lab/library rooms, shop bag ties). For Logic Link PD both clients
  must agree, so co-op level generation needs to normalize or accept these sources. Needs a
  design decision (not this task). Same for `Collections.shuffle` in vaults and the guide-page
  entropy placement.
- WF-03 (`regen-goldens.yml`) must invoke `./gradlew regenGoldens` with the same JVM arguments.
- Planned follow-ups already noted in WORKFLOW §6: co-op goldens (role A == role B), other hero
  classes, other challenges.

## Open questions
- Is "any Temurin 17" acceptable for regeneration, or should CI pin the exact JDK build? The
  cross-JDK check (17 vs 27-ea) passed, so version drift looks safe, but that is only one data
  point.
