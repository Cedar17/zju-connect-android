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
launched on a physical device. The experimental TUN data plane and the aTrust
authentication control plane are implemented separately; formal tunnel
connection and session recovery remain future work.

## Scope

The first usable version targets the current Zhejiang University aTrust service only. It does not initially include the legacy EasyConnect protocol, WebVPN, multiple accounts, port forwarding, a local proxy, Always-on VPN, boot-time auto-connect, or complex advanced configuration.

Keep the initial Android app as a single `app` module. Split out Go or API modules only when the binding build or ownership boundary creates a demonstrated need.

## Proposed layers

```text
Compose UI
    ↓
ConnectionViewModel / StateFlow
    ↓
VpnRepository
    ├── VpnServiceController
    ├── SecureSessionStore
    ├── SettingsStore
    └── GoCoreBridge
            ↓
       zju-connect
```

- UI renders state and collects user input; it does not directly control TUN or Go objects.
- The ViewModel owns one explicit connection state machine.
- The repository coordinates authentication, session storage, VPN permission, and service lifecycle.
- `VpnService` owns the Android TUN, foreground service, and socket protection boundary.
- The Go core owns aTrust protocol handling, resource parsing, routing, and the data plane.

## Connection state

Use a closed state model with explicit events rather than inferring phases from log text:

```text
Idle
PreparingVpnPermission
Authenticating
AwaitingPassword
AwaitingSms
AwaitingCaptcha
FetchingResources
EstablishingTunnel
Connected
Reconnecting
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
GetPendingCaptchaImage()
CancelAuthentication()
ClearAuthenticatedResult()
```

The versioned events are `authenticationStarted`, `authMethodsReady`,
`credentialsRequired`, `phoneRequired`, `smsRequired`, `captchaRequired`,
`authenticated`, `cancelled`, `retryStarted`, and safe-code `error` events.
CAPTCHA image bytes pass only through `GetPendingCaptchaImage`, not callback
JSON, shared files, or temporary files. Callback events contain no passwords,
cookies, SID, device identifiers, sign keys, CAPTCHA bytes, or raw responses.

Attaching a production TUN, stopping a production connection, and exporting or
importing a session remain future work. Any such extension must keep the
Android-specific surface small and be assessed for potential upstreaming.

## Session snapshot and security

Persist only the minimum state required for recovery, using a versioned schema. Candidate fields include `schemaVersion`, server identity, username, authentication data, session identifiers, resource data, creation time, and core version. Do not persist a password by default; only persist other sensitive material if recovery experiments prove it is necessary.

Required security properties:

- Encrypt persisted state with an Android Keystore-backed non-exportable key, for example AES-GCM.
- Store ciphertext in a location excluded from system backup, such as `noBackupFilesDir` or its equivalent.
- Do not bypass server-required SMS, captcha, TLS certificate, or hostname validation.
- Never log passwords, cookies, SIDs, device identifiers, sign keys, captcha data, or complete authentication responses.
- When a snapshot is invalid or expired, fall back clearly to re-authentication instead of silently retrying forever.

## VpnService and socket protection

Once Android creates a TUN, the Go core's control/data connection to the VPN service must not be routed back through that TUN. The Android boundary must therefore allow the underlying socket to be passed through `VpnService.protect(fd)` before it connects, or create and protect the socket before handing it to Go.

This design is not yet implemented. Its validation must cover Wi-Fi and mobile networks, network switching, failed connection cleanup, and release of the TUN file descriptor, sockets, goroutines, and foreground service.

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
  retains the successful result only in Go process memory for the future tunnel
  phase.

### Phase 5 — Session recovery

- Export a minimal session snapshot after successful authentication.
- Encrypt it with Keystore-backed storage.
- Recover after process death while the session is valid.
- Reliably fall back to authentication after expiry or invalidation.

## Release blockers

Do not publish while any of the following is true:

- TLS certificate or hostname verification can be bypassed.
- Sensitive credentials or identifiers can enter logs or plaintext files.
- Session snapshots are unencrypted or included in cloud backup.
- The VPN socket is not protected from TUN routing loops.
- Disconnect leaves TUN, sockets, goroutines, or the foreground service behind.
- The AAR cannot be reproduced from pinned source and toolchain versions.
