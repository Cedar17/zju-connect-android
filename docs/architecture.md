# Android client architecture and validation

## Purpose and capabilities

The project provides an unofficial Android client for Zhejiang University
aTrust/RVPN. Its design prioritizes a safe, stable, and recoverable end-to-end
connection path over feature breadth:

```text
zju-connect
→ gomobile AAR
→ Android VpnService / TUN
→ aTrust authentication
→ encrypted session snapshot
→ recovery after process restart
```

The client combines Kotlin, Jetpack Compose, Material 3, a reproducible Gradle
Wrapper, `minSdk = 29`, and a pinned gomobile AAR. Its production path includes
the aTrust authentication control plane, a real Android VPN service and TUN data
plane, deterministic shutdown, encrypted session recovery, a one-tap connection
flow, and Android-owned tunnel reconstruction after underlay network changes.
The Go bridge remains deliberately small and does not become a mobility layer.

## Scope

The client targets the current Zhejiang University aTrust service only. It does
not include the legacy EasyConnect protocol, WebVPN, multiple accounts, port
forwarding, a local proxy, or complex advanced configuration. The production
`RealVpnService` supports the normal explicit app start, a user-added Quick
Settings tile, and optional Android Always-on VPN selected by the user in
system settings. The app never enables Always-on or lockdown by itself, and it
does not add the tile automatically.

Keep the Android app as a single `app` module. Split out Go or API modules only
when the binding build or ownership boundary creates a demonstrated need.

## Layers

```text
Compose UI → ConnectionViewModel → MainActivity
    │                                │
    │                                └── manual start
    └── AuthSessionStore / AccountStore

Quick Settings Tile ── tile start / foreground handoff ──┐
Android system ─────── Always-on start ──────────────────┤
                                                         ↓
                                      RealVpnService / RealVpnStateStore
                                                         ↓
                                           Android TUN + Go data plane
```

- UI renders state and collects user input; it does not directly control TUN or Go objects.
- The ViewModel owns one explicit connection state machine.
- `MainActivity` handles Android VPN permission and service dispatch from one-shot effects.
- The active Quick Settings tile maps the service state to one direct action.
  A small process-local entry lease only serializes Tile and Activity starts;
  it does not duplicate authentication or VPN lifecycle state.
- Session and remembered-account storage are private Android implementation details owned by the ViewModel flow.
- `VpnService` owns the Android TUN, foreground service, and socket protection boundary.
- The official upstream Go core owns aTrust protocol handling, resource parsing,
  routing, the data plane, and internal L3 reconnect. The app-owned Go bridge
  maps its public API to Android without a production core fork.

## Connection state

Use a closed state model with explicit events rather than inferring phases from log text:

```text
Disconnected
RestoringSession
FetchingAuthMethods
Authenticating
AwaitingCredentials
AwaitingPhone
AwaitingSms
AwaitingCaptcha
PreparingVpnPermission
EstablishingVpn
Connected
Disconnecting
Error
```

Every transition should have a defined success, cancellation, timeout, and failure path. A process restart or network change must leave the app in a consistent recoverable state.

`RealVpnStateStore` additionally publishes service-owned
`waitingForNetwork`, `waitingForAuthentication`, and
`alwaysOnDisconnectBlocked` states. These are not a second UI state machine:
the Activity maps them to the existing recovery, login/error, and connected
presentations.

## Kotlin–Go boundary

The gomobile surface should expose simple types, `String`, `ByteArray`, and callback interfaces. Complex control-plane objects should use versioned JSON at the boundary; packet data must continue to use file descriptors rather than JSON serialization.

The control-plane bridge implements a single in-memory aTrust authentication
flow and the real VPN lifecycle. Its exact contract and security limits are
authoritative in
[gomobile-bridge.md](gomobile-bridge.md).

The implemented authentication interface is:

```text
StartAuthentication(requestJson, listener)
SubmitAuthentication(actionJson)
ResumeAuthentication(snapshotBytes, listener)
ExportAuthenticatedSession()
GetPendingCaptchaImage()
CancelAuthentication()
HasReusableAuthenticatedResult()
ClearAuthenticatedResult()
```

The versioned events are `authenticationStarted`, `sessionRestoreStarted`,
`sessionInvalid`, `authMethodsReady`,
`credentialsRequired`, `phoneRequired`, `smsRequired`, `captchaRequired`,
`authenticated`, `cancelled`, `retryStarted`, and safe-code `error` events.
CAPTCHA image bytes pass only through `GetPendingCaptchaImage`, not callback
JSON, shared files, or temporary files. Callback events contain no passwords,
cookies, SID, device identifiers, sign keys, CAPTCHA bytes, or raw responses.

The real VPN bridge adds `PrepareRealVpn`, `DiscardPreparedRealVpn`,
`StartRealVpn`, and `StopRealVpn`.
Preparation consumes the authenticated result already held in Go memory and
returns only the assigned IPv4 address plus IPv4 resource prefixes. Android
creates the TUN from those routes, then attaches it with a `SocketProtector` so
future aTrust underlay sockets are protected from the VPN interface.

## Session snapshot and security

The implemented encrypted snapshot persists only `schemaVersion`, the exact
`deviceId`, and the complete authentication cookie set returned for the fixed
ZJU endpoint. SID remains inside that cookie set. Resources, connection ID,
sign key, password, verification input, creation time, server identity, and
username are not stored in that snapshot. `ServerVersionInfo` is also not
persisted; upstream setup fetches the server manifest for each preparation,
so this app does not provide the upstream cached-manifest fallback. Separately,
Android stores only the last server-confirmed username in private preferences for display and login
prefill; resources and username are refreshed after session validation, and
connection-scoped identifiers are regenerated.

`RealVpnService` owns service-side session validation for the optional Always-on
and Quick Settings paths without starting an Activity or reading saved
passwords. The recovery ladder, invalidation rules (including
`sessionExpired != invalidSession`), and foreground handoff are authoritative
in [authentication-recovery.md](authentication-recovery.md).

Required security properties:

- Encrypt persisted state with an Android Keystore-backed non-exportable key, for example AES-GCM.
- Store ciphertext in a location excluded from system backup, such as `noBackupFilesDir` or its equivalent.
- Write the encrypted envelope atomically and bind its format to fixed authenticated data.
- Never bypass server-required SMS, CAPTCHA, or other interactive verification.
- TLS follows the pinned official upstream implementation. Both authentication
  and tunnel transports currently skip standard certificate-chain and hostname
  verification; the app does not add certificate or SPKI pins. This is the
  current upstream transport policy, not a claim of standard TLS peer validation.
- Never log passwords, cookies, SIDs, device identifiers, sign keys, captcha data, or complete authentication responses.
- The user-facing diagnostics report is an application-owned, bounded redacted
  event buffer rather than a Logcat viewer. It records only allowlisted state,
  authentication stage, stable cause/code, bounded operation duration, and
  data-plane counters; it excludes credentials, account identity, endpoints,
  packet metadata, routes, raw messages, and Logcat.
- The diagnostics Activity separates the user-facing summary from the copied
  report: the default screen shows the latest state and a short, de-duplicated
  monospaced history suitable for a screenshot, while copying retains the complete
  bounded event history, environment header, and counters. Consecutive equal
  display states (including counter-only changes) are grouped only in the
  display projection; persisted records remain unchanged for public problem reports.

## VpnService and socket protection

Once Android creates a TUN, the Go core's control/data connection to the VPN service must not be routed back through that TUN. The Android boundary must therefore allow the underlying socket to be passed through `VpnService.protect(fd)` before it connects, or create and protect the socket before handing it to Go.

`RealVpnService` installs this boundary after Android establishes the TUN. Its
validation must cover Wi-Fi and mobile networks, network switching, failed
connection cleanup, and release of the TUN file descriptor, sockets, goroutines,
and foreground service. Manual and Quick Settings starts carry distinct
explicit markers and remain `START_NOT_STICKY`; an Android Always-on start is
identified by the absence of an app marker and uses `START_STICKY` only as a
lifecycle aid. Android system VPN settings, not an app preference, remain the
source of truth for Always-on.

The service observes Internet-capable non-VPN networks and captures the system
default underlay at session start. Recovery follows that session's underlay;
changes to an unrelated backup network do not rebuild a working VPN. An opaque,
in-memory fingerprint covers network identity, transport, suspension, interface,
and IPv4/default-route changes; addresses and network names never enter logs or
diagnostics. A relevant stable change is debounced for 1.5 seconds, then the
service stops the old Go/TUN session and rebuilds it from the authenticated result already held
in Go memory. If no usable underlay exists, the foreground service waits until a
network returns. Cold Always-on session validation uses at most three attempts
at 0, 5, and 30 seconds for one underlay-network revision, then waits for a new
revision or a foreground retry. User stop, revoke, destruction, and the first
terminal failure always cancel pending recovery.

The protected Go sockets use the system-selected underlay and are not explicitly
bound to an Android `Network`, so the service deliberately leaves
`setUnderlyingNetworks` at its default rather than publishing a binding it does
not own.

## Reproducibility and upstream integration

The gomobile integration follows these requirements; future changes must
continue to follow them:

- Pin the upstream `zju-connect` commit.
- Pin Go, `golang.org/x/mobile`, NDK, Android SDK, and related build tools.
- Do not use floating `latest` dependencies.
- Do not commit an AAR whose source and toolchain cannot be traced and reproduced.
- Keep Android-specific structured authentication and socket-protection interfaces small and suitable for upstream contribution where practical.

## Capabilities and validation boundaries

### Android application baseline

- The single Android application module uses Kotlin, Compose, and Material 3
  and builds with the checked-in Gradle Wrapper.
- The project produces a single Release APK containing only `arm64-v8a` for
  physical-device validation through the documented Windows Android toolchain.
- Android VPN permission remains a user-approved system boundary.

### Gomobile binding

- The pinned source, toolchain lockfile, bootstrap command, and minimal bridge
  are defined in [gomobile-bridge.md](gomobile-bridge.md).
- Kotlin calls a narrow Go API and receives versioned JSON control-plane events
  through the generated AAR; packet data uses file descriptors.
- AAR, Go bridge, Android unit, build, and device checks remain distinct
  verification surfaces and must be repeated when their inputs change.

### Authentication control plane

- The Android bridge coordinates a single in-memory flow through upstream
  authentication and challenge APIs, including server-advertised password,
  SMS, CAPTCHA, TOTP, and RADIUS authentication.
- Kotlin receives versioned structured events and CAPTCHA bytes through the
  gomobile boundary; it never parses CLI output or exchanges secrets through
  temporary files.
- Authentication follows the upstream TLS policy described above, supports
  caller-owned cancellation and retry, maps UI-visible failures to stable codes,
  and retains the live result in Go memory while Android encrypts only the
  minimal recovery snapshot.

### Real VPN data plane

- The VPN reuses the in-memory authentication result without repeating password
  login and exposes only the assigned address and resource routes to Android.
- Android establishes `VpnService`, protects real underlay sockets, attaches the
  TUN, and owns deterministic stop and revoke cleanup.
- Packets outside the aTrust resource set are dropped as split-tunnel traffic;
  TUN and L3 failures use stable UI error codes.
- The upstream core owns server framing, L3 transport, and internal reconnect.
  The Android bridge maps errors returned by its public L3 connection to
  terminal VPN failures instead of maintaining another protocol recovery loop.
- Diagnostics expose only allowlisted states, stable codes, and bounded counters;
  they exclude packet metadata, payloads, authentication material, and network
  identifiers. Packet forwarding performs only lightweight IPv4 length/protocol
  checks; it does not parse or serialize detailed packet diagnostics.
- Logcat records local monotonic durations for preparation, TUN establishment
  and bridge attachment. Preparation distinguishes actual core setup from a
  prepared-client cache hit; the measurement contract is in
  [gomobile-bridge.md](gomobile-bridge.md).

The real data plane and lifecycle have been exercised on the K40, including CLI
requests to CC98 and private campus resources. Off-campus resource access over
cellular data has also been manually verified on a OnePlus Ace 3V.

### Encrypted session recovery

The snapshot composition and Android storage boundary are described above. The
recovery ladder, invalidation rules, and credential policy are authoritative in
[authentication-recovery.md](authentication-recovery.md).

### Daily connection experience

- App launch loads only the locally remembered username and observes any
  already-running VPN service; it does not validate the encrypted session or
  start authentication traffic.
- The Connect action owns one closed Android state machine that restores a
  session, requests only required authentication input, obtains Android VPN
  permission, and starts the real VPN.
- `RealVpnService` owns one ongoing VPN status notification for the lifetime of
  the connection. The app declares a normal/default notification channel; the
  user controls its final alert behavior in Android system settings. It starts
  as `正在连接`, updates through recovery states and `已连接`, opens
  `MainActivity` when tapped, and is removed when the service stops or fails.
  Android 13+ notification permission is requested from a user-initiated VPN
  start after the service is dispatched; a denial does not block the VPN attempt.
- In manual mode, `MainActivity` sends an explicit `manual` start marker and the
  service is `START_NOT_STICKY`. A user-added Quick Settings tile sends its own
  marker and verifies VPN authorization before dispatching the foreground
  service. Its recovery and foreground-handoff semantics, together with the
  equivalent boundary for Android's unmarked Always-on start, are defined in
  [authentication-recovery.md](authentication-recovery.md).
- While Always-on owns the VPN, the existing disconnect action remains visible
  but is guarded by `VpnService.isAlwaysOn()`. It leaves the connection running
  and posts a one-shot notification that opens Android VPN settings; after the
  user disables Always-on, the same disconnect action works normally.
- The main activity does not detect, configure, or claim success for Android
  battery optimization, OEM autostart, or recent-task locking. Those policies
  remain device-specific user settings; diagnostics may describe a failure but
  do not turn them into a connection prerequisite or a home-screen prompt.
- `auth/psw` with the `Radius` login domain is selected automatically when
  advertised. Another method is selected only when it is the sole method the
  mobile bridge supports.
- The Material 3 home surface provides one context-sensitive primary action, a
  disconnected-only account switch, IME-safe authentication forms, and a
  non-exported redacted diagnostics Activity. The Activity stores at most 100
  allowlisted records in `noBackupFilesDir` and exposes copy and clear controls.

The daily connection flow has been exercised on K40 and on OnePlus Ace 3V over
cellular data. K40 smoke checks use CLI requests to `https://www.cc98.org/` and
`http://10.10.98.98/`; browser rendering is not required.

### Underlay network recovery

- Android observes Internet-capable non-VPN networks for identity, transport,
  suspension, interface, IPv4 address, and default-route changes, and anchors
  recovery to the underlay captured when the current session starts.
- Callback bursts are coalesced before Android closes the old TUN and aTrust
  data plane and rebuilds them with the existing authenticated result.
- If no underlay is usable, the foreground service waits indefinitely for a
  network. User disconnect, revoke, destruction, and terminal failures cancel
  recovery.
- Recovery adds only `recovering` and `waitingForNetwork` to the redacted state
  contract and never records network identifiers.

Recovery policy is covered by JVM tests. Historical acceptance on 2026-08-11
covered three complete Wi-Fi disable/enable cycles between Wi-Fi and Ethernet
on K40, with one reconstruction per stable transition, return to `active` and
both campus-resource requests passing. The maintainer also accepted Wi-Fi ↔
cellular recovery on OnePlus Ace 3V that day. Each release records its own
real-device acceptance in its PR; these historical results do not validate
subsequent builds.

## Release blockers

Do not publish while any of the following is true:

- TLS behavior differs from the documented pinned-upstream policy without an
  explicit implementation and documentation update.
- Sensitive credentials or identifiers can enter logs or plaintext files.
- Session snapshots are unencrypted or included in cloud backup.
- The VPN socket is not protected from TUN routing loops.
- Disconnect leaves TUN, sockets, goroutines, or the foreground service behind.
- The AAR cannot be reproduced from pinned source and toolchain versions.
