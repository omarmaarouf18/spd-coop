# Worklog: NET-01a — co-op websocket client transport

## Task and scope
- Task ID: NET-01a (first slice of NET-01: transport only, no protocol)
- Lane (allowed paths): `core/src/main/java/.../coop/net/**`, `core/src/test/java/.../coop/net/**`, `docs/worklog/NET-01a.md`, `docs/coop/architecture.md` §2 and §5 (class names / reliability notes only)
- Hot paths granted: `core/build.gradle` (one `implementation` + one `runtimeOnly` line), owner in chat, 2026-10-17
- Branch: `agent/NET-01a-client-transport`, from `dev` (short `d85914a`, after the TST-01 merge)
- Plan approved by the owner in chat before coding (library: Java-WebSocket; delivery: commands applied on the owner's machine).

## Decisions taken
- **Library:** `org.java-websocket:Java-WebSocket:1.6.0` (2024-12-15, targets Java 7, only dependency `slf4j-api`). The previous prototype used 1.5.3 on Android and desktop. It sits behind `CoopTransport`, so a native iOS transport (`NSURLSessionWebSocketTask`) can replace it later without touching `CoopClient`. `org.slf4j:slf4j-nop:2.0.13` (the version Java-WebSocket's pom uses) at runtime only, so slf4j doesn't print a "no provider" warning to stderr.
- **Go on the client was considered and rejected** (owner asked): the game is Java on Android/iOS(RoboVM)/desktop; a Go client would need gomobile/cgo builds per platform. Go stays the server language.
- **Transport = text frames only.** No envelope, `HELLO`, `seq`/`ack` or JSON `PING`: those are PRO-01's spec and NET-01's job. That keeps this slice out of the protocol hot path.
- **Outbound queue (rule 21):** frames sent while not open are queued and flushed in order on open. Bound 256; `send()` returns `false` (and logs in debug) when full or after `stop()`, never a silent drop. `stop()` discards the queue on purpose (logged).
- **Threading (rule 20):** transport callbacks arrive on the socket thread; `CoopClient` only posts `Listener` calls to `MainThread` (`Gdx.app.postRunnable` in game). State changes are `synchronized`; a connection `generation` counter makes callbacks from an abandoned socket no-ops.
- **Reconnect:** `Backoff` 1 s doubling to a 30 s cap, each delay uniform in [ceiling/2, ceiling]. Jitter uses its own `java.util.Random`, never `com.watabou.utils.Random` (rules 8–9). Reset on a successful open. No close-code special cases: a fatal server answer (e.g. version mismatch) is a protocol message, and the protocol layer will call `stop()`.
- **Liveness:** Java-WebSocket ping frames every 30 s (`setConnectionLostTimeout`); the Go server (`coder/websocket`) answers pings itself.
- **Logging (rule 24):** `Gdx.app.debug` only when `Gdx.app` exists and `DeviceCompat.isDebug()`.

## Changes per commit (subject only — NO SHAs)
1. `NET-01a: add co-op websocket client transport (queue, reconnect, GL-thread callbacks)`
2. `NET-01a: worklog`

## Verification
- `./gradlew :core:test --tests 'com.shatteredpixel.shatteredpixeldungeon.coop.net.*'`: `BackoffTest` 5/5, `CoopClientTest` 6/6 (real Java-WebSocket server on 127.0.0.1): queued-before-open order, callbacks only on the main-thread stand-in, reconnect after a server close + queued frame delivered on the new connection, no reconnect after `stop()`, full queue rejects, retries until a server appears on the port.
- Flakiness: the `coop.net` tests run 10 times with `--rerun`: `passed 10/10`.
- A real bug caught by `BackoffTest.manyAttemptsNeverOverflowPastCap`: the first version computed `base << attempt`, which overflowed at attempt 54 (`delay -325153170130820592`). Fixed by doubling a clamped ceiling instead.
- Interop with the project's Go server (`server/cmd/server` on `dev`, port 18080, not Docker): a throwaway probe test (deleted, not committed) queued `{"t":"HELLO"}` before `start()`; result `connected | msg:{"t":"NOT_IMPLEMENTED"}`; server log: `websocket connected`, `message received ... bytes:13`, `websocket closed ... status:1000`.
- `make check` in the cloud session: `== gate: all checks passed (scope: java docs secrets) ==` (JDK 21 with `JAVA_TOOL_OPTIONS` set, which works since WF-04). The same `test` run included TST-01's suites, all passing: `IsolationTest`, `JsonTest`, `RepeatabilityTest`, `VanillaGoldensTest` (goldens unchanged by the new dependency).
- Single-player impact: none. Nothing references `coop.net` yet, and no upstream file changed.
- Determinism impact: none (no level-gen code, no game RNG use).

## Unverified
- **iOS (RoboVM):** Java-WebSocket on RoboVM is untested (needs macOS). If it fails, add a native `CoopTransport` in a REL/NET task.
- **Android release (R8 minify):** not built (no Android SDK in the session; `android-apk.yml` builds debug). Java-WebSocket may need `-dontwarn` rules in `android/proguard-rules.pro`; check on the first release build.
- **wss:// through Caddy:** only `ws://` against a local server was exercised; TLS uses the platform default `SSLSocketFactory`.
- JDK 17 locally: tests ran on JDK 21 here; CI (JDK 17) will run them after the merge.

## Follow-ups / out-of-lane findings
- NET-01 (after PRO-01 merges): envelope, `seq`/`ack` resend after reconnect, JSON heartbeat, `Dispatcher`, and calling `stop()` on fatal protocol replies.
- `android/proguard-rules.pro`: possibly `-dontwarn org.java_websocket.**` / slf4j rules (hot-ish path, needs a grant when the first release build shows the need).

## Open questions
- none
