# Codex Desktop bridge

Relay supports `--codex-runtime auto|desktop|app_server` and
`RIVO_CODEX_DESKTOP_BRIDGE_DIR` and the original
the current OS user and have no group/world permissions.

The bridge forwards native RPC through Desktop's already initialized
AppServerConnection. Native notifications and human approval/question responses
use that connection. Desktop continues executing its own dynamic tools. Relay
does not initialize, terminate, or launch Desktop's native process.

`desktop` requires the authenticated bridge and its internal native connection
to be ready. `auto` probes the bridge, verifies the actual native host and home
through each connection, and requires two seconds of stable readiness before
selecting Desktop for new subscriptions. A missing or unverified native target
cannot silently switch to an unrelated local Desktop home. Existing subscriptions,
active turns, and threads awaiting their first turn retain their original writer
until an explicit unsubscribe. A dropped or timed-out mutation is never replayed.
Connection details include bridge availability separately from writer readiness,
protocol, provenance, capabilities, identity mismatch, and selected writer.

## Host adapter

`server/rivo_relay/codex/desktop_host.cjs` exports
`startDesktopBridge({connection, directory, appVersion})`. It must be loaded by a
Desktop host integration that already holds the local AppServerConnection. It
creates an owner-only socket and random token, authenticates clients, limits
frame size/concurrency/buffer pressure, observes notifications and human requests,
and detaches without stopping the underlying connection. Its `close()` removes
only the endpoint it created. Existing endpoints are never replaced automatically.

The module uses these observed host methods: `sendAppServerRequest`,
`handleClientResponse`, `getConnectionState`,
`registerInternalNotificationHandler`, `registerInternalConnectionStateHandler`,
and `registerInternalServerRequestHandler`. Human-request observation returns
`null` so Desktop's existing approval UI retains ownership. Expired request IDs
cannot be answered again. Dynamic-tool requests are not intercepted.

This repository does not patch installed app bundles or start an injection
loader. If no host integration is installed, `desktop` reports unavailable and
`auto` exposes that state while using its verified native target. The isolated
Node host fixture exercises the complete client/host protocol without opening
the user's Desktop or writing native history.

## Native host identity

`native_host_info()` runs a bounded helper with the selected native connection's
`command/exec`. It obtains the daemon PID from `server/diagnostics`, verifies its
start time before and after discovery, resolves the executable, and reads only
the relevant home identity. Linux reads only selected `CODEX_HOME`/`HOME` process
environment keys. macOS requires the helper to descend from the selected daemon,
then prefers that daemon's open state database over inherited home defaults.
Neither history repair nor auto selection treats Relay's local `~/.codex` as the
remote host's storage. Unverifiable identity fails closed.

## Source and static evidence

`1642fc810f543a7995ca334e0e4916c20968d858`.

loader. A bounded read-only inspection of the installed ChatGPT main JavaScript
`codex-desktop-bridge` strings in `.vite/build/*.js`. This is not proof that all
possible external loaders are absent. No installed app was modified or launched.

REA artifact SHA-256:
`57602118a9890cb62ddfa750996e41440396d0aac2725b6837b5391305c78acf`.
Inventory evidence:
`ev_ec6a26e72997e53a561e077bc07367b2892de9c8400841b936adac2a12feb2ff`.
Selected connection bundle analysis evidence:
`ev_412576b65aeb497acce83609e595b913c5044c0df8e23752b4f24938a3e3a53c`.
Static analysis is partial and does not establish runtime reachability. Seven
unpacked native companion files had declared-integrity mismatches; they were
recorded as untrusted and were neither executed nor used to implement the bridge.
