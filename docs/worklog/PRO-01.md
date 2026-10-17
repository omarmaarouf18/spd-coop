# Worklog: PRO-01 — protocol v1 spec, fixtures, Go protocol package, contract tests

## Task and scope
- Task ID: PRO-01 (prompt `docs/prompts/PRO-01_SRV-01.md`, task 1)
- Lane: `docs/coop/protocol/**`, `server/internal/protocol/**`, `docs/worklog/PRO-01.md`, `docs/coop/architecture.md` §3
- Hot paths granted: `scripts/changed-scope.sh` + `.githooks/pre-push` (only the `protocol` stage), `.github/workflows/ci.yml` (only a `protocol-contract` job)
- Branch: agent/PRO-01-protocol-v1, from dev

## Decisions taken
- **s2c `seq` is per player within an `epoch`** (a random id per server process, reported in `WELCOME`), not per connection. The prompt said "per-connection outbound counter". That works for c2s, but for s2c it would make the client's `ack` meaningless after a reconnect, so replay would be impossible. The epoch tells the client when a restart reset the counter. Then `WELCOME.complete:false` and the `room`/`run` snapshot is the truth.
- `WELCOME` carries `epoch`, `replayed` (count of replayed events that follow), `complete`, and `run` (RunInfo) besides the prompt's fields. This way a client that missed `RUN_START` (a restart, or eviction from the log) can still rebuild its run from the snapshot alone.
- Replayed events keep their **original `seq`**, so they arrive with a lower seq than the `WELCOME`. This is documented in §7.
- **Unknown fields:** c2s strict (the server rejects them, which catches client typos), s2c lenient (the client ignores them, so the server can add optional fields within v1). Fixtures mark dropped s2c fields with `ignoredFields`.
- **Fixture format** adds `field` (a dotted path) and `ignoredFields` to the prompt's `{name, direction, valid, reason, message}`. `reason` is one of 10 machine-checkable decode-error kinds (§11). For `not_json`, `message` is a string holding the raw frame.
- Decode errors map to codes: `unsupported_version` → `VERSION_MISMATCH` (sent as `REJECT`), `unknown_type` → `UNKNOWN_TYPE`, the rest → `BAD_MESSAGE`.
- Error codes: the prompt's list plus `SESSION_REPLACED` (an older connection is closed when the same player resumes elsewhere) and `INTERNAL`. `REJECT` only uses `BAD_MESSAGE` / `VERSION_MISMATCH` / `RESUME_FAILED`, and `ERROR` uses the rest. Both are enforced by the schema.
- Close statuses `4001` (after REJECT), `4002` (heartbeat / no HELLO within 10 s), `4003` (session replaced), plus the standard `1001` / `1008` / `1009`.
- Value formats: playerId / runId / epoch = 16 lowercase hex chars. resumeToken = 43 base64url chars (32 bytes). Room code = 6 chars of `ABCDEFGHJKMNPQRSTUVWXYZ23456789`. `anim` = SPD `CharSprite` animations. `tier` 0..6 = `Hero.tier()`. `depth` 1..30 / `branch` 0..9 are sanity bounds. `QUICK.phraseId` 0..63.
- `depth`/`branch`: required on `GHOST`/`MARK`, optional on `QUICK`, forbidden on all other types (`forbidden_field`).
- c2s `from` must be a string if present, and its value is ignored. On s2c it is required on relayed presence and forbidden elsewhere.
- **`ALLOWED_VERSIONS` becomes a list of integers** (`gameVersionCode`), because the prompt gates on the int `gameVersionCode`. The current `server/internal/config` keeps strings, and `deploy/.env.example` has `ALLOWED_VERSIONS=0.1.0-coop` (a name). The config change belongs to SRV-01. **`deploy/.env.example` is outside both lanes**, see the open questions.
- Lobby rules written into the spec (§6) for SRV-01: a disconnected lobby player keeps the slot, but `ready` resets; `RUN_START` needs both players connected; no `ROOM_LEAVE` in `running`; `paused` is always false in v1.
- `encode` validates its own output against the schema, so the server can never send a message the client would reject.
- The protocol gate stage runs `go test -run TestContract ./internal/protocol/`, with a marked hook for the Java side. `docs/coop/protocol/*.md` (the spec) now maps to `protocol + docs`.
- **Bug fix in a granted file (WF-01 latent bug):** `scripts/changed-scope.sh` printed the first 20 changed paths with `| head -n 20`. With `pipefail`, `head` closing the pipe made the script die with SIGPIPE whenever the change was large (`make check` → `Error 141` here, with 107 paths). It now uses `sed -n '1,20…p'`, which reads all input. This is called out in the commit message.
- Fixture `resumeToken` values are the obviously fake, low-entropy `fake-fake-…` (43 chars). A random-looking base64 value tripped gitleaks' `generic-api-key` rule, and `.gitleaks.toml` is not in this lane.

## Changes per commit (subject only — NO SHAs)
1. `PRO-01: wire the protocol stage into the gate and CI`. This comes first, because the old gate blocks as soon as the fixtures exist, and the pass stamp refuses a commit while another tracked file is modified.
2. `PRO-01: add protocol v1 spec and contract fixtures` (PROTOCOL.md, 97 fixtures, architecture §3)
3. `PRO-01: add Go protocol package with fixture contract tests`
4. `PRO-01: worklog`

## Verification
- `go test ./internal/protocol/...`: 97 fixtures exercised (31 valid, 66 invalid). Every v1 type in each direction has a valid fixture, and every reserved name decodes as `unknown_type`.
- Mutation check: turning `c2s-ghost-tier-7` into a valid message made the contract test fail, as it should. The file was then restored.
- `make check` (scope includes `protocol`), actionlint on `ci.yml`: literal outputs in the chat report.
- Single-player impact: none (no game code). Determinism impact: none.

## Unverified
- The `protocol-contract` CI job has not run on GitHub yet (it runs after the owner pushes).
- The Java side of the contract: NET-01.

## Follow-ups / out-of-lane findings
- `docs/coop/WORKFLOW.md` §6 still says the protocol stage "blocks if the fixtures dir exists". It is now wired; the owner should update that line when folding.
- SRV-01: `config.AllowedVersions` → `[]int`, and the REJECT `minGameVersionCode` = min of the list.

## Open questions
- `deploy/.env.example` has `ALLOWED_VERSIONS=0.1.0-coop`. Once SRV-01 parses integers, that value fails `-check-env` (the deploy pre-flight and any run with that file). Options: (a) grant `deploy/.env.example` to SRV-01 so it becomes `ALLOWED_VERSIONS=` (empty = no gate) or `922`; (b) keep strings and gate on a name. The recommendation is (a), since the spec gates on the integer.
