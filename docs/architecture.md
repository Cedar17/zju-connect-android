# Android client architecture and validation plan

## Purpose and current status

The project aims to provide an unofficial Android client for Zhejiang University aTrust/RVPN. The first objective is a safe, stable, and recoverable end-to-end connection path rather than a feature-complete UI:

```text
zju-connect
→ gomobile AAR
→ Android VpnService / TUN
→ aTrust authentication
→ encrypted session snapshot
→ recovery after process restart
```

The Android baseline and a deliberately minimal Go binding are now in place:
Kotlin, Jetpack Compose, Material 3, a reproducible Gradle Wrapper, `minSdk = 29`,
a pinned gomobile AAR, and a debug APK that has been built, installed, and
launched on a physical device. The experimental TUN data plane, aTrust
authentication control plane, real Android VPN service, deterministic shutdown,
and encrypted session recovery are implemented on `master`. The Issue #14
development line consolidates those separate validation tools into one
user-triggered connection flow.

## Scope

The first usable version targets the current Zhejiang University aTrust service only. It does not initially include the legacy EasyConnect protocol, WebVPN, multiple accounts, port forwarding, a local proxy, Always-on VPN, boot-time auto-connect, or complex advanced configuration.

Keep the initial Android app as a single `app` module. Split out Go or API modules only when the binding build or ownership boundary creates a demonstrated need.

## Proposed layers

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

Required security properties:

- Encrypt persisted state with an Android Keystore-backed non-exportable key, for example AES-GCM.
- Store ciphertext in a location excluded from system backup, such as `noBackupFilesDir` or its equivalent.
- Write the encrypted envelope atomically and bind its format to fixed authenticated data.
- Do not bypass server-required SMS, captcha, TLS certificate, or hostname validation.
- Never log passwords, cookies, SIDs, device identifiers, sign keys, captcha data, or complete authentication responses.
- When a snapshot is invalid or expired, fall back clearly to re-authentication instead of silently retrying forever.

## VpnService and socket protection

Once Android creates a TUN, the Go core's control/data connection to the VPN service must not be routed back through that TUN. The Android boundary must therefore allow the underlying socket to be passed through `VpnService.protect(fd)` before it connects, or create and protect the socket before handing it to Go.

`RealVpnService` installs this boundary after Android establishes the TUN. Its
validation must cover Wi-Fi and mobile networks, network switching, failed
connection cleanup, and release of the TUN file descriptor, sockets, goroutines,
and foreground service.

## Reproducibility and upstream integration

The current gomobile integration follows these requirements; future changes must
continue to follow them:

- Pin the upstream `zju-connect` commit.
- Pin Go, `golang.org/x/mobile`, NDK, Android SDK, and related build tools.
- Do not use floating `latest` dependencies.
- Do not commit an AAR whose source and toolchain cannot be traced and reproduced.
- Keep Android-specific structured authentication and socket-protection interfaces small and suitable for upstream contribution where practical.

## Validation phases

### Phase 1 — Android baseline: complete

- Kotlin, Compose, and Material 3 project builds with the checked-in wrapper.
- Debug APK is generated and launched on a physical device.
- Windows/PowerShell and Git Bash wrapper usage is documented.

### Phase 2 — Go binding: complete

- The pinned source, toolchain lockfile, bootstrap command, and minimal bridge
  are defined in [gomobile-bridge.md](gomobile-bridge.md).
- Kotlin calls a minimal Go API and receives a versioned JSON callback through
  the generated AAR.
- The pinned toolchain has built the AAR and debug APK, and the debug APK has
  been installed on the physical-device target. Future changes must repeat the
  documented build and device checks.

### Phase 3 — TUN data plane

The repository now contains an experimental Issue #6 validation path. It is not
an aTrust connection implementation:

- A debug-only VpnService requests permission and creates a blocking TUN.
- The current app is the only allowed application, with a test route and MTU 1400.
- Go receives ownership of the detached TUN descriptor.
- Go creates a local fake UDP transport and asks Android to protect its socket
  before connecting.
- A fixed marker UDP packet is sent through the TUN and reflected back through
  Go, allowing the Android UI to show packet and byte counters.
- Start is single-session, stop is idempotent, and service revoke/destroy paths
  close the Go data plane.

The experimental path still requires a person to approve the system VPN dialog
on the first run. It deliberately does not authenticate to aTrust, parse real
resources, or provide production connection UI.

### Phase 4 — Authentication control plane: implemented

- The Android fork exposes a single in-memory aTrust flow for server-advertised
  password login, server-triggered SMS, and graphical CAPTCHA steps.
- Kotlin receives versioned structured events and CAPTCHA bytes through the
  gomobile boundary; it never parses CLI output or exchanges secrets through
  temporary files.
- Authentication enforces normal TLS certificate and hostname validation,
  supports cancellation/retry, maps UI-visible failures to stable codes, and
  retains the live result in Go memory while Android encrypts only its minimal
  recovery snapshot for a later process.

### Phase 5 — Real VPN minimum loop

- Reuse the in-memory authentication result without repeating password login.
- Prepare the aTrust client and expose only the assigned address and resource routes.
- Establish Android `VpnService`, protect the real underlay sockets, and attach the TUN.
- Stop and revoke the service without leaving TUN descriptors, sockets, or L3 readers.
- Drop split-tunnel packets outside the aTrust resource set instead of stopping
  the whole VPN; report TUN/L3 failures with stable UI error codes.
- Validate lifecycle on K40, then validate off-campus resource access on a OnePlus Ace 3V over cellular data.

This phase intentionally does not add automatic reconnect or complex network
switching.

Issue #11 validation on 2026-08-10 completed the real data-plane framing loop on
the preserved-data K40 installation. The aTrust length-framed response parser
now keeps the negotiated mode and reassembles IPv4 packets split across server
frames; malformed length streams fail closed instead of desynchronizing the
TUN. Android diagnostics expose only bounded packet metadata (including TCP
flags/sequence/ack/window and checksum status) at the four data-plane stages,
never payload or authentication material. CLI requests to `cc98.org` (including
redirects), `office.ckc.zju.edu.cn`, and the internal console endpoint returned
real HTTP responses, and Edge rendered the CC98 homepage. The required OnePlus
Ace 3V cellular acceptance remains pending; this evidence does not claim a
merged or deployed release.

### Phase 6 — Session recovery: minimum checkpoint implemented

- Export only cookies plus the exact device ID after successful authentication.
- Encrypt the snapshot with Android Keystore AES-GCM in `noBackupFilesDir`.
- Validate with the server and refresh username/resources after process death.
- Delete explicitly invalid or locally unreadable snapshots; retain snapshots
  across transient network/TLS failures so restoration can be retried.

### Phase 7 — One-tap daily connection: functional checkpoint

- App launch loads only the locally remembered username and observes any
  already-running VPN service; it does not read or validate the encrypted
  session and does not start authentication network traffic.
- The user's Connect action owns one closed Android connection state machine:
  restore and validate a saved session, fall back to the minimum server-required
  authentication steps, request Android VPN permission, and start the real VPN.
- `auth/psw` with the `Radius` login domain is selected automatically when it is
  advertised. A different method is selected only when it is the sole method
  supported by the mobile interactive bridge.
- Definite session invalidation deletes only the encrypted authentication
  snapshot and retains the separately remembered username. Cancellation and VPN
  disconnection retain the snapshot; passwords and verification input are never
  persisted.
- The primary Compose surface is Material 3, dynamic-color, and dark-only. It
  exposes one context-sensitive primary action, the last server-confirmed
  username, and only the authentication input currently required by the server.
  Password input is hidden by default with an explicit show/hide control; raw
  bridge codes and the synthetic TUN tool are no longer shown on the home screen.
- The status card keeps a two-line hierarchy: a large connection state and one
  supporting line. Stable states use the supporting line for the remembered
  account; in-progress and error states use it for actionable detail. Rare
  non-blocking warnings render outside the status card.
- K40 connection smoke checks use CLI HTTP requests to
  `https://www.cc98.org/` and `http://10.10.98.98/`; opening a browser is
  not required for this checkpoint.
- The first checkpoint is accepted on the K40. A separate redacted diagnostics
  Activity, visual polish, and OnePlus Ace 3V cellular acceptance remain follow-up
  work within Issue #14.

## Release blockers

Do not publish while any of the following is true:

- TLS certificate or hostname verification can be bypassed.
- Sensitive credentials or identifiers can enter logs or plaintext files.
- Session snapshots are unencrypted or included in cloud backup.
- The VPN socket is not protected from TUN routing loops.
- Disconnect leaves TUN, sockets, goroutines, or the foreground service behind.
- The AAR cannot be reproduced from pinned source and toolchain versions.
