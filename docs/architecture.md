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

The Android project baseline is now in place: Kotlin, Jetpack Compose, Material 3, a reproducible Gradle Wrapper, `minSdk = 29`, and a debug APK that has been built, installed, and launched on a physical device. The Go binding, VPN data plane, authentication, and session recovery remain future work and must not be described as implemented.

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

The minimum interface is expected to cover:

```text
start(configJson, listener)
submitAuth(responseJson)
attachTun(fd)
stop()
exportSession()
importSession(snapshotJson)
```

Expected structured events include `stateChanged`, `passwordRequired`, `smsRequired`, `captchaRequired`, `sessionUpdated`, `log`, and `fatalError`. Captcha image bytes and interaction metadata must not be passed through shared files or ad-hoc temporary files. Sensitive values must be redacted before log events leave the Go side.

The exact API is still a design target. Before implementation, inspect the upstream `zju-connect` API and determine which Android-specific interfaces can be kept small and potentially contributed upstream.

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

When gomobile integration begins:

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

### Phase 2 — Go binding: next

- Pin the upstream source and toolchain.
- Build a reproducible minimal AAR.
- Call a minimal Go API from Kotlin and receive structured callbacks.

### Phase 3 — TUN data plane

- Request VPN permission and create the TUN.
- Pass the TUN descriptor to Go.
- Protect the underlying VPN socket.
- Verify disconnect and abnormal-exit cleanup.

### Phase 4 — Authentication control plane

- Support password, SMS, and graphical captcha flows through structured events.
- Do not parse CLI stdout or exchange credentials through temporary files.
- Map failures to stable structured error types.

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
