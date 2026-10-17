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
