# Decision Log

Short records of decisions and the reason behind each one. Newest at the bottom. Status: **Accepted**, **Proposed**, **Superseded**.
To change a decision, add a new entry that supersedes it. Don't edit history.

---

### D-001 — Rebuild on upstream v4.0.2 instead of continuing the v3.3.8 prototype
- **Status:** Accepted (2026-10-17)
- **Context:** The prototype (`../shattered-pixel-dungeon`, v3.3.8) has critical bugs (see `legacy-prototype-review.md`), and upstream v4.0.2 changed 532 files, including most files the prototype hooks into (`Mob` +329 lines, `GameScene`, `DungeonTileSheet`, `Hero`). Upstream also took terrain ID 39.
- **Decision:** Start clean from the `v4.0.2` tag. Use the prototype only as a reference for ideas.

### D-002 — Server in Go, authoritative for shared state
- **Status:** Accepted (2026-10-17)
- **Context:** The Node relay forwarded everything blindly, with no arbitration, timers or reconnect.
- **Decision:** A Go server that holds per-room shared state ("small database"): missions, timers, item transfers, co-op room state, event log. In memory, with snapshots to SQLite/bbolt.

### D-003 — Protocol: versioned JSON over wss
- **Status:** Accepted (2026-10-17)
- **Decision:** JSON envelope v1 (see `architecture.md` §3), with a version gate at the handshake. Protobuf can be reconsidered later if bandwidth or schema drift becomes an issue.

### D-004 — Co-op model: Parallel Worlds + interdependent tasks
- **Status:** Accepted (2026-10-17)
- **Context:** A shared world would require multi-hero support across ~2160 `Dungeon.hero` references and full determinism. Parallel worlds already work, and the owner's design turns the separation into the core mechanic.
- **Decision:** Each player keeps their own world, and cooperation comes from asymmetric co-op rooms and missions (The Cell, Mirror Lock, …). The ghost is intangible except under Super Sync.

### D-005 — Anti-stall: real-time mission clocks + forced turns
- **Status:** Accepted (2026-10-17)
- **Context:** A turn-based world only advances when its player acts, so turn-counted timers can be frozen by standing still.
- **Decision:** The server holds real-time clocks for missions and the downed window. During those, a player who doesn't act within 5 s gets an automatic wait turn. Clocks pause when a player disconnects or backgrounds the app.

### D-006 — The Cell failure path
- **Status:** Accepted (2026-10-17)
- **Decision:** The prisoner survives escalating waves. If they fall, they're Downed and the ally can revive them with a Scroll of Recall. Otherwise **both** players return to the region start (1/6/11/16/21) via `InterlevelScene.Mode.RETURN`, with an anti-exploit penalty.
- **Deferred:** a sub-dungeon as the failure punishment (revisit after v1).

### D-007 — Potion of Super Sync
- **Status:** Accepted (concept), control model **Proposed**
- **Decision:** An emergency potion. The drinker's ghost materializes in the ally's world as a solid Projection that can attack the ally's mobs, using a stat snapshot. v1 is AI-controlled; v2 (player-controlled) is optional later.

### D-008 — Single source of agent instructions
- **Status:** Accepted (2026-10-17)
- **Decision:** `AGENTS.md` is canonical. `CLAUDE.md` / `GEMINI.md` import it. All AI models follow the same rules.

### D-009 — Distribution: private, non-commercial
- **Status:** Accepted (2026-10-17)
- **Decision:** Android via a direct APK (or GitHub Releases). iOS via TestFlight (needs a Mac + Apple Developer Program). No public store listing for now. Contact the upstream author before any Google Play release (see `licensing.md`).

### D-010 — Co-op terrain IDs in reserved range 200–255
- **Status:** Accepted (2026-10-17)
- **Context:** Upstream adds terrain IDs at the low end (v4 took 39).
- **Decision:** Co-op terrain constants use 200–255.

### D-011 — Super Sync is player-controlled (refines D-007)
- **Status:** Accepted (2026-10-17)
- **Decision:** The drinker moves and attacks with their Projection directly. The Projection is simulated only in the ally's world, as an actor that waits for remote commands (`SYNC_CMD`), with a 5 s forced-turn fallback. The drinker sees a **Remote View**: the same map, with puppets of the ally's mobs streamed via `SYNC_VIEW` and limited to the Projection's FOV. v1 commands: move / melee / wait.
- **Consequence:** Remote View is the most expensive part of the feature. It's scheduled after The Cell.

### D-012 — Workflow adopted from SaaS prototype (WF-01/WF-03), adapted
- **Status:** Accepted (2026-10-17)
- **Decision:** Task IDs, one agent branch and worktree per task, worklog per task, `<TASK-ID>: <summary>` commits, an owner-only merge/push, a scoped local gate with a pass stamp, and GitHub CI as the full gate. Details: `docs/coop/WORKFLOW.md`.

### D-013 — CI on GitHub Actions
- **Status:** Accepted (2026-10-17)
- **Context:** 3 platforms, a Go server, a determinism requirement, and a public GPL repo (Actions are free for public repos).
- **Decision:** A CI gate for Java build + tests (including determinism goldens), Go server checks, protocol contract fixtures, Android debug APK artifact, and gitleaks. The iOS build runs on macOS on manual or release triggers only.

### D-014 — Server and all of its dependencies run in Docker
- **Status:** Accepted (2026-10-17)
- **Decision:** The Go server ships as a static image (multi-stage → distroless, non-root). Production uses `deploy/docker-compose.yml` with Caddy (TLS/wss) plus the server on a private network, and a volume for the SQLite/bbolt state. Dev uses `docker-compose.dev.yml`. CI builds and publishes the image to GHCR. Deploy files live in this repo under `deploy/` (one repo is enough at this scale).

### D-015 — Separate deploy repo; the VPS pulls prebuilt images (refines D-014)
- **Status:** Accepted (2026-10-17). It supersedes the "deploy files live in this repo" part of D-014.
- **Context:** The owner wants the same model as `saas-core` → `saas-core-deploy` (ADR-0010 / ADR-0015 there): the production host never sees source code or builds anything.
- **Decision:**
  - **Main repo** (`<game>`, public): source + `deploy/` reference files. `build-and-publish.yml` builds the server image to GHCR (public package), then syncs the reference files into the deploy repo, replacing `:IMAGE_TAG` with the commit SHA.
  - **Deploy repo** (`<game>-deploy`, private, bot-only): pinned `docker-compose.yml`, `Caddyfile`, `.env.example`, `deploy.yml`.
  - **VPS:** a self-hosted Actions runner runs `deploy.yml` on every push: pre-flight `-check-env` → pull → up → health → automatic rollback.
  - Auth via a GitHub App (Contents + Workflows write on the deploy repo only).
- **Consequence:** one more repo and a GitHub App to manage. The VPS has a minimal surface and deploys are fully automatic.

### D-016 — Server libraries: coder/websocket + modernc.org/sqlite
- **Status:** Accepted (2026-10-17, proposed in SRV-00)
- **Decision:** Websocket via `github.com/coder/websocket` v1.8.15 (context-based, maintained, no deps). Storage via `modernc.org/sqlite` v1.60.1 (pure Go → static binary for distroless). One connection, WAL, `busy_timeout` 5 s, a `meta.schema_version` table, and a newer schema is refused.
- **Alternatives:** `gorilla/websocket`, bbolt.

### D-017 — Local-only testing until a VPS exists; OPS-01 split
- **Status:** Accepted (2026-10-17)
- **Context:** There's no domain or host yet. The owner wants to lay the foundation now and test everything locally.
- **Decision:** All gameplay/network testing is local (desktop clients + `docker-compose.dev.yml` via `make server-dev`). OPS-01 is split:
  - **Part 1 (now):** deploy repo, GitHub App + secrets, first `main` release (the image goes to GHCR, the sync goes to the deploy repo, plus the APK artifact).
  - **Part 2 (deferred):** VPS, firewall, `deploybot`, DNS, self-hosted runner `spd-coop-vps`, first deploy, rollback drills A (pre-flight with a missing tag) and B (real rollback: internal health OK, Caddy 502).
- **Consequence:** `deploy.yml` won't run until the runner exists. A push to the deploy repo queues a `deploy` job that no runner picks up, and GitHub cancels it after ~24 h. That shows as a cancelled run, which is expected and harmless until part 2.

### D-018 — Deploy pipeline: GHCR image + bot-synced deploy repo + VPS runner (implements D-014/D-015)
- **Status:** Accepted (2026-10-17). The main-repo half was verified on GitHub (`main` at short `9c5863b`, build-and-publish attempt 2). The VPS half is deferred (D-017).
- **Decision:**
  - `build-and-publish.yml` (push to `main` touching `server/**`/`deploy/**`, or manual) builds `ghcr.io/omarmaarouf18/spd-coop-server:<sha>` + `:latest`. The package is public, inherited from the public repo.
  - The GitHub App `spd-coop-automation` (Contents + Workflows write, installed on `spd-coop-deploy` only, short-lived installation tokens, `persist-credentials: false`) syncs `deploy/` into the private, bot-only `spd-coop-deploy`, with `:IMAGE_TAG` pinned to `<sha>`.
  - The deploy repo's `deploy.yml` runs on a self-hosted runner (`spd-coop-vps`, user `deploybot`): `.env` symlink → pre-flight `-check-env` (abort before touching containers) → pull/up → internal + external health → automatic rollback to `HEAD~1` → log dump.
- **Consequences:** no PAT and no build on the VPS. A missing App secret skips the sync with a notice instead of failing (verified). A non-existent image tag is caught by the pre-flight, not the rollback. So the drills are: **A** pre-flight (missing tag), **B** real rollback (a config that passes the pre-flight but fails health, e.g. `PORT: "9999"` → Caddy 502).

### D-019 — Co-op level gen depends only on seed + depth (+ branch)
- **Status:** Accepted (2026-10-17)
- **Context:** NET-01c found that floors 2+ can differ between the two players, because level gen reads run state that gameplay changes (`Generator` deck/probabilities, `LimitedDrops`, …). TST-01 found upstream `HashMap` identity-order iteration (`SecretLaboratoryRoom`, `SecretLibraryRoom`, `ShopRoom.ChooseBag`) and `Collections.shuffle` with the JDK RNG (`VaultLevel`). ART/RoboVM can't use the test's JVM flag.
- **Decision:** in **co-op runs only**, level generation runs on a dedicated level-gen state that evolves only through level generation itself (swapped in around `level.create()`), and the non-deterministic iterations are made order-stable. Vanilla behavior and `VanillaGoldensTest` stay unchanged. Task `DET-01`, plus co-op goldens that prove both players' worlds are identical regardless of how each one plays.

### D-020 — The Cell is triggered by a hidden Binding Trap (supersedes the fixed Cell room of game-design v0.1)
- **Status:** Accepted (2026-10-17, owner: "a trap on the floor or something random, for challenge")
- **Decision:** variant **A**. A seeded hidden trap, 0–1 per eligible floor. It's armed only when the ally is online on the same floor. The stepper becomes the prisoner, and their current room seals. The rescuer's key spawns at a server-chosen cell. Variant **B** (a rare server-rolled ambush) is a later option. Details: game-design §4.1.

### D-021 — Carry over the owner's world-link mechanics from the v3.3.8 prototype
- **Status:** Accepted (2026-10-17, owner: "I thought about these from every angle; the old stack was the problem, not the design")
- **Decision:** all game mechanics of the prototype come back in the new design (game-design §5.3, §6A, §7, §10): shared floor loot (first claim wins), drop-to-ally on the same floor, shared boss HP pool, Twin Door, Twin Switches room (+ opaque ghost inside), door mirroring on the same floor, co-op balance (mobs ×1.2, more food), and revive by throwing a Potion of Healing at the downed ally's ghost.
- **Implementation rules (what changes vs the prototype):** server-authoritative claims/windows/boss HP (no turn-clock comparisons, no client-trusted state), every message floor-scoped (`depth`/`branch`), stable `itemNetId`s from co-op level gen (DET-01), everything gated on the persisted co-op run flag, never on socket state.

### D-022 — Forced turns never pause on the client; Downed expiry inside The Cell is not a real death
- **Status:** Accepted (2026-10-18, from the MEC-00 review)
- **Forced turns:** they keep running even with the pause menu open. Real absence (disconnect, app in background) is covered by the server pause (D-005). A client-side pause exception would let the prisoner freeze their world so the waves never reach them.
- **Downed expiry inside a Cell mission:** `MISSION_END{failed, penalty}` takes precedence over `DOWNED_EXPIRED`. The prisoner is **not** killed. Both players get the penalty (gold −25%, `Mode.RETURN` to the region start), and the prisoner arrives there at 25% HP. Outside missions, `DOWNED_EXPIRED` runs the real death path per `COOP_DEATH_MODE` (default `player_out`). MEC-02 implements it, and PROTOCOL.md §5.8 is clarified there.
- **Penalty timing:** a failed-mission penalty is applied once the hero isn't in a locked boss fight. A dead hero gets neither reward nor penalty.
