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
`RealVpnService` supports both the normal explicit app start and optional
Android Always-on VPN selected by the user in system settings. The app never
enables Always-on or lockdown by itself.

Keep the Android app as a single `app` module. Split out Go or API modules only
when the binding build or ownership boundary creates a demonstrated need.

## Layers

```text
Compose UI
    ↓
ConnectionViewModel / ConnectionUiState / StateFlow
    ├── AuthSessionStore / AccountStore
    ├── GoCoreBridge → zju-connect
    └── ConnectionEffect
            ↓
       MainActivity
            ↓
       RealVpnService / RealVpnStateStore
            ├── manual start (MainActivity)
            └── Always-on start (Android system)
            ↓
       Android TUN + Go data plane
```

- UI renders state and collects user input; it does not directly control TUN or Go objects.
- The ViewModel owns one explicit connection state machine.
- `MainActivity` handles Android VPN permission and service dispatch from one-shot effects.
- Session and remembered-account storage are private Android implementation details owned by the ViewModel flow.
- `VpnService` owns the Android TUN, foreground service, and socket protection boundary.
- The Go core owns aTrust protocol handling, resource parsing, routing, and the data plane.

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

The control-plane bridge now implements `GetBuildInfo`, `EmitBuildInfo`,
credential-free `FetchAuthInfo`, and a single in-memory aTrust authentication
flow. Its exact contract and security limits are authoritative in
[gomobile-bridge.md](gomobile-bridge.md).

The implemented authentication interface is:

```text
StartAuthentication(requestJson, listener)
SubmitAuthentication(actionJson)
ResumeAuthentication(snapshotBytes, listener)
ExportAuthenticatedSession()
GetPendingCaptchaImage()
CancelAuthentication()
ClearAuthenticatedResult()
```

The versioned events are `authenticationStarted`, `sessionRestoreStarted`,
`sessionInvalid`, `authMethodsReady`,
`credentialsRequired`, `phoneRequired`, `smsRequired`, `captchaRequired`,
`authenticated`, `cancelled`, `retryStarted`, and safe-code `error` events.
CAPTCHA image bytes pass only through `GetPendingCaptchaImage`, not callback
JSON, shared files, or temporary files. Callback events contain no passwords,
cookies, SID, device identifiers, sign keys, CAPTCHA bytes, or raw responses.

The real VPN bridge adds `PrepareRealVpn`, `StartRealVpn`, and `StopRealVpn`.
Preparation consumes the authenticated result already held in Go memory and
returns only the assigned IPv4 address plus IPv4 resource prefixes. Android
creates the TUN from those routes, then attaches it with a `SocketProtector` so
future aTrust underlay sockets are protected from the VPN interface.

## Session snapshot and security

The implemented encrypted snapshot persists only `schemaVersion`, the exact
`deviceId`, and the complete authentication cookie set returned for the fixed
ZJU endpoint. SID remains inside that cookie set. Resources, connection ID,
sign key, password, verification input, creation time, server identity, and
username are not stored in that snapshot. Separately, Android stores only the
last server-confirmed username in private preferences for display and login
prefill; resources and username are refreshed after session validation, and
connection-scoped identifiers are regenerated.

The optional Always-on path performs this same validation from
`RealVpnService`, without starting an Activity or reading saved passwords. A
missing or invalid snapshot, or a server response that requires interactive
authentication, leaves the service in a low-CPU foreground waiting state. Its
notification opens the existing Activity login flow; a successful foreground
authentication then wakes the waiting service through the existing explicit
start effect.

Required security properties:

- Encrypt persisted state with an Android Keystore-backed non-exportable key, for example AES-GCM.
- Store ciphertext in a location excluded from system backup, such as `noBackupFilesDir` or its equivalent.
- Write the encrypted envelope atomically and bind its format to fixed authenticated data.
- Do not bypass server-required SMS, captcha, TLS certificate, or hostname validation.
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
- When a snapshot is invalid or expired, fall back clearly to re-authentication instead of silently retrying forever.

## VpnService and socket protection

Once Android creates a TUN, the Go core's control/data connection to the VPN service must not be routed back through that TUN. The Android boundary must therefore allow the underlying socket to be passed through `VpnService.protect(fd)` before it connects, or create and protect the socket before handing it to Go.

`RealVpnService` installs this boundary after Android establishes the TUN. Its
validation must cover Wi-Fi and mobile networks, network switching, failed
connection cleanup, and release of the TUN file descriptor, sockets, goroutines,
and foreground service. A manual start remains `START_NOT_STICKY`; an Android
Always-on start is identified by the absence of the app's explicit start marker
and uses `START_STICKY` only as a lifecycle aid. Android system VPN settings,
not an app preference, remain the source of truth for Always-on.

The service observes all non-VPN networks with Internet capability instead of
the application's default network, which can be the VPN itself. An opaque,
in-memory fingerprint covers network identity, transport, suspension, interface,
and IPv4/default-route changes; addresses and network names never enter logs or
diagnostics. A stable change is debounced for 1.5 seconds, then the service stops
the old Go/TUN session and rebuilds it from the authenticated result already held
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
- The project produces a debug APK for physical-device validation through the
  documented Windows Android toolchain.
- Android VPN permission remains a user-approved system boundary.

### Gomobile binding

- The pinned source, toolchain lockfile, bootstrap command, and minimal bridge
  are defined in [gomobile-bridge.md](gomobile-bridge.md).
- Kotlin calls a narrow Go API and receives versioned JSON control-plane events
  through the generated AAR; packet data uses file descriptors.
- AAR, Go bridge, Android unit, build, and device checks remain distinct
  verification surfaces and must be repeated when their inputs change.

### Experimental TUN diagnostic path

The repository contains a debug-only integration probe that is separate from
the production aTrust connection path:

- A `VpnService` requests permission and creates a blocking TUN for the current
  app, a fixed test route, and MTU 1400.
- Go owns the detached TUN descriptor, opens a protected local fake UDP
  transport, and reflects a fixed marker packet so the UI can report counters.
- Start is single-session; stop, revoke, and service destruction close the
  diagnostic data plane idempotently.

This probe does not authenticate to aTrust, parse real resources, or prove
access to school-network resources.

### Authentication control plane

- The Android fork exposes a single in-memory aTrust flow for server-advertised
  password login, server-triggered SMS, and graphical CAPTCHA steps.
- Kotlin receives versioned structured events and CAPTCHA bytes through the
  gomobile boundary; it never parses CLI output or exchanges secrets through
  temporary files.
- Authentication enforces normal TLS certificate and hostname validation,
  supports cancellation and retry, maps UI-visible failures to stable codes,
  and retains the live result in Go memory while Android encrypts only the
  minimal recovery snapshot.

### Real VPN data plane

- The VPN reuses the in-memory authentication result without repeating password
  login and exposes only the assigned address and resource routes to Android.
- Android establishes `VpnService`, protects real underlay sockets, attaches the
  TUN, and owns deterministic stop and revoke cleanup.
- Packets outside the aTrust resource set are dropped as split-tunnel traffic;
  TUN and L3 failures use stable UI error codes.
- The length-framed response parser preserves the negotiated mode, reassembles
  IPv4 packets split across server frames, and fails closed on malformed length
  streams instead of desynchronizing the TUN.
- Diagnostics expose only allowlisted states, stable codes, and bounded counters;
  they exclude packet metadata, payloads, authentication material, and network
  identifiers.

The real data plane and lifecycle have been exercised on the K40, including CLI
requests to CC98 and private campus resources. Off-campus resource access over
cellular data has also been manually verified on a OnePlus Ace 3V.

### Encrypted session recovery

- Successful authentication exports only cookies plus the exact device ID.
- Android encrypts the snapshot with Keystore-backed AES-GCM in
  `noBackupFilesDir`.
- Recovery validates the snapshot with the server and refreshes username and
  resources after process death.
- Explicitly invalid or locally unreadable snapshots are deleted; transient
  network and TLS failures retain the snapshot for a later retry.

### Daily connection experience

- App launch loads only the locally remembered username and observes any
  already-running VPN service; it does not validate the encrypted session or
  start authentication traffic.
- The Connect action owns one closed Android state machine that restores a
  session, requests only required authentication input, obtains Android VPN
  permission, and starts the real VPN.
- `RealVpnService` owns one low-importance, silent, ongoing notification for
  the lifetime of the connection. It starts as `正在连接`, updates through
  recovery states and `已连接`, opens `MainActivity` when tapped, and is
  removed when the service stops or fails. Android 13+ notification permission
  is requested only when the user first reaches the real VPN start step; a
  denial does not block the VPN attempt.
- In manual mode, `MainActivity` sends an explicit `manual` start marker and the
  service is `START_NOT_STICKY`. In Always-on mode, Android sends an unmarked
  VPN service start, the service restores the encrypted session itself, and a
  `需要打开 App 完成登录` notification opens the same Activity only when
  interactive authentication is required.
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
- Definite session invalidation deletes only the encrypted authentication
  snapshot and retains the remembered username. Cancellation and disconnection
  retain the snapshot; passwords and verification input are never persisted.
- The Material 3 home surface provides one context-sensitive primary action, a
  disconnected-only account switch, IME-safe authentication forms, and a
  non-exported redacted diagnostics Activity. The Activity stores at most 100
  allowlisted records in `noBackupFilesDir` and exposes copy and clear controls.

The daily connection flow has been exercised on K40 and on OnePlus Ace 3V over
cellular data. K40 smoke checks use CLI requests to `https://www.cc98.org/` and
`http://10.10.98.98/`; browser rendering is not required.

### Underlay network recovery

- Android observes Internet-capable non-VPN networks for identity, transport,
  suspension, interface, IPv4 address, and default-route changes.
- Callback bursts are coalesced before Android closes the old TUN and aTrust
  data plane and rebuilds them with the existing authenticated result.
- If no underlay is usable, the foreground service waits indefinitely for a
  network. User disconnect, revoke, destruction, and terminal failures cancel
  recovery.
- Recovery adds only `recovering` and `waitingForNetwork` to the redacted state
  contract and never records network identifiers.

Recovery policy is covered by JVM tests. On K40, three complete Wi-Fi
disable/enable cycles switched between Wi-Fi and Ethernet while ADB and SSH
remained reachable; every stable transition produced one reconstruction,
returned to `active`, and passed both campus-resource requests. Wi-Fi ↔ cellular
recovery has also been manually accepted on a OnePlus Ace 3V.

## Release blockers

Do not publish while any of the following is true:

- TLS certificate or hostname verification can be bypassed.
- Sensitive credentials or identifiers can enter logs or plaintext files.
- Session snapshots are unencrypted or included in cloud backup.
- The VPN socket is not protected from TUN routing loops.
- Disconnect leaves TUN, sockets, goroutines, or the foreground service behind.
- The AAR cannot be reproduced from pinned source and toolchain versions.
