# Worklog: NET-01b — client protocol v1 (Protocol, CoopSession)

## Task and scope
- Task ID: NET-01b (second slice of NET-01: the protocol in the game, no UI, no upstream hooks)
- Lane (allowed paths): `core/src/main/java/.../coop/net/**`, `core/src/test/java/.../coop/net/**`, `docs/worklog/NET-01b.md`, `docs/coop/architecture.md` §2 (class list)
- Hot paths granted: `core/src/main/java/.../coop/net/Protocol.java` (NET-01 owns it; owner: "continue in the same sequence", 2026-10-17)
- Branch: `agent/NET-01b-client-protocol`, from `dev` (short `7822561`)

## Decisions taken
- **Strict JSON of our own (`ProtocolJson`).** libGDX's `JsonReader` is lenient (accepts unquoted strings/comments) and drops a number's spelling, but PROTOCOL.md §1 must tell `1` from `1.0`/`1e0`. Numbers stay `Num` with their source text; maps keep insertion order; a repeated key keeps the last value (as Go's `encoding/json`).
- **`Protocol` mirrors `server/internal/protocol` check for check** (same order: `v`, `t`, unknown envelope keys sorted, `seq`, `ack`, `from`, floor, payload fields in schema order, cross-field checks), so both sides give the same decode-error kind and field path on every fixture.
- **`CoopClient` no-queue mode** (`queueWhileClosed = false`, used by `CoopSession`): `send()` returns false unless the socket is open. This fixes the conflict found in SRV-01: queued frames used to go out before `HELLO`, but the spec requires `HELLO` as the first frame (seq 1) of every connection. `CoopClient` also implements the new `FrameLink` (send / reconnect / stop) and gains `reconnect()`.
- **`CoopSession`** (all on the main thread):
  - every connection: c2s seq restarts at 1 with `HELLO{protocol, gameVersionCode, platform}`, plus `resumeToken`, `epoch` and envelope `ack` when it has them;
  - s2c `seq` counts only from `WELCOME` on (pre-WELCOME `REJECT`/`ERROR` are numbered per connection, SRV-01); same epoch keeps the highest `ack`, a new epoch resets the baseline;
  - `REJECT{RESUME_FAILED}` clears the credentials and the next connection starts fresh; `REJECT{VERSION_MISMATCH|BAD_MESSAGE}` and `ERROR{SESSION_REPLACED}` stop for good (`onStopped`, with `minGameVersionCode`); other `ERROR`s keep the connection;
  - `tick(now)`: `PING` every 10 s after `WELCOME`; more than 30 s without any server message → `FrameLink.reconnect()`;
  - requests: `createRoom`, `joinRoom` (uppercases the code), `leaveRoom`, `ready`, `ghost`, `mark`, `quick`; they return false before `WELCOME`, after stop, or when the socket can't take the frame (the seq is not consumed then);
  - unknown s2c types and fields are ignored (§5.4–§5.5); the resume token is passed to the listener for saving and never logged.
- `String.format` avoided in the JSON writer (locale-dependent output on Arabic devices).
- The gate's `protocol` stage and the CI `protocol-contract` job still print "Java contract tests not wired yet": the Java contract test runs in the `java` stage / `Java build & test` job (`:core:test`). Wiring it into the protocol stage/job touches `.githooks/pre-push` and `ci.yml` (hot paths, not granted): follow-up.

## Changes per commit (subject only — NO SHAs)
1. `NET-01b: add client protocol v1 (strict decoder, CoopSession) with Java contract tests`
2. `NET-01b: worklog`

## Verification
- `ProtocolContractTest`: `contract fixtures exercised: 97 (31 valid, 66 invalid)`, same verdict as the Go side on every fixture; every v1 type has a valid fixture in each direction; all 20 reserved names decode as `unknown_type`.
- Mutation check (not committed): loosening `hpPct` to 0..101 made the test fail with `c2s-ghost-hp-over-100: accepted an invalid message (want out_of_range p.hpPct)`; the file was restored.
- `CoopSessionTest` 16/16 (every frame the session sends is re-decoded with the c2s decoder): HELLO first with seq 1; no requests before WELCOME; join uppercases; resume sends token/epoch/ack and seq restarts per connection; pre-WELCOME messages don't move the ack; same-epoch max vs new-epoch reset; WELCOME snapshot with a run; RUN_START → RUNNING; VERSION_MISMATCH stops (with 930); RESUME_FAILED clears and starts fresh; SESSION_REPLACED stops; ordinary errors keep the connection; ping at 10 s, reconnect after 30 s; presence in and out with floors; unknown types/fields ignored; an unsent frame doesn't skip a seq.
- **Against the real Go server** (`server/cmd/server` from `dev`, port 18082, not Docker): `LLPD_SERVER_URL=ws://127.0.0.1:18082/ws ./gradlew :core:test --tests '*LiveServerTest'` → `room code 57Y2BG`, `RUN_START on both: run:e9d82b799f0356ad:5194191911102:live`, `B resumed into the run as 69f1f6b0379ac567`, test passed. Without `LLPD_SERVER_URL` the test is skipped (as in the gate and CI).
- `make check` (cloud session, JDK 21): `== gate: all checks passed (scope: java server protocol secrets) ==`. Test suites in that run: BackoffTest 5, CoopClientTest 6, CoopSessionTest 16, LiveServerTest 1 skipped, ProtocolContractTest 2, IsolationTest 1, JsonTest 3, RepeatabilityTest 1, VanillaGoldensTest 1, all passing.
- Single-player impact: none (no upstream file, nothing calls `coop.net` yet). Determinism impact: none.

## Unverified
- Real devices (Android/iOS) and `wss://`; the game UI does not use this yet (NET-01c).

## Follow-ups / out-of-lane findings
- NET-01c: lobby UI, `CoopRun` + co-op save slot, run start with the server seed, a game-loop hook calling `CoopSession.tick()`, persisting the credentials with the co-op save. Needs the owner's grant for the upstream hooks listed in the NET-01 plan.
- Wire `ProtocolContractTest` into the gate's `protocol` stage and CI's `protocol-contract` job (hot paths).

## Open questions
- none for this slice (the NET-01c decisions are pending in chat: the 4 upstream hooks, unranked co-op, no challenges, neutral profile).
