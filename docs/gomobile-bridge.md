# Reproducible gomobile bridge

This document defines the reproducible gomobile boundary. It builds a local
AAR from pinned source and toolchain inputs; the AAR is intentionally ignored
by Git.

## Pinned inputs

The authoritative machine-readable record is
[tools/gomobile.lock.json](../tools/gomobile.lock.json).

| Input | Pinned value |
| --- | --- |
| zju-connect upstream auth API | **dc3cfa808ecc6dc424a38cf97b4f557dd02314b2** |
| Android compatibility core | **Cedar17/zju-connect** branch **codex/pr53-auth-handler-compat** at **09e6d2e7224b773c67dd0bc32e47558a409d986d** |
| Go | **1.25.6** |
| golang.org/x/mobile | **v0.0.0-20260602190626-68735029466e** (68735029466e…) |
| Android NDK | **29.0.14206865** (r29) |
| Android API / Build Tools | **29** / **36.0.0** |
| AAR ABIs | **arm64-v8a**, **x86_64** |

The generated AAR retains both ABIs for the reusable Go bridge. The Android
application's Release APK applies its own packaging filter and contains only
`arm64-v8a`.

The bootstrap script verifies the Go archive's SHA-256 and the NDK archive's
published SHA-1 before extracting them. It leaves all downloaded tools under
the ignored .gomobile-toolchain directory, except for the NDK, which is
installed below the configured Android SDK.

## Build

Run these commands in Windows PowerShell at the repository root. Android Studio
must already have SDK Platform 36.1 and Build Tools 36.0.0 installed.

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-gomobile-toolchain.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-gomobile-aar.ps1
.\.gomobile-toolchain\go\bin\go.exe -C .\go\bridge test ./...
cmd.exe /c "gradlew.bat :app:assembleRelease --no-daemon --console=plain"
~~~

Use -AndroidSdkRoot, -JavaHome, or -ToolRoot on either script when the defaults
are not appropriate. The build writes:

- app/libs/zju-connect-core.aar
- app/libs/zju-connect-core-sources.jar
- app/libs/zju-connect-core.build-info.json with its SHA-256 and every pinned
  input

All outputs are ignored. Before binding, the script verifies that go.mod and
go.sum are tidy without modifying them, then downloads and verifies their
pinned modules. Gradle's verifyGoCoreAar task fails early with the required
command when the AAR has not been built. It does not download a toolchain as
a side effect of a normal Android build.

The Gradle unit-test task covers the Kotlin connection policies, CAPTCHA
coordinate mapper, encrypted-session envelope codec, VPN lifecycle, and
redacted diagnostics. It does not replace the Go bridge and fork state-machine
tests above.

## Kotlin–Go contract

The Go package is [go/bridge](../go/bridge). It exposes gomobile-safe strings,
`ByteArray`, and one callback interface. [GoCoreBridge.kt](../app/src/main/kotlin/io/github/cedar17/zjuconnect/GoCoreBridge.kt)
owns the generated Java API. `ConnectionViewModel` uses the wrapper for the
single active authentication flow, beginning network work only after the user
taps Connect.

All event payloads contain schemaVersion and type. Future callback events must
preserve this versioned JSON boundary and must not contain passwords, cookies,
SIDs, device IDs, sign keys, CAPTCHA bytes, or raw authentication responses.

Authentication failure events may additionally carry the allowlisted `stage`,
`cause`, and bounded `durationMs` fields. These fields describe where the
operation stopped (for example `auth.config`) and classify transport, DNS,
TLS, protocol, or server failures without exposing the underlying error text.

## Interactive authentication control plane

The production bridge uses upstream `auth.Session`, `NewLoginMethod`,
`Session.Login`, and `authchallenge.Handler`; it no longer depends on the
fork-only `InteractiveFlow`. Upstream owns the aTrust protocol, session, and
challenge definitions. The Android bridge implements the challenge handler and
owns the UI-facing coordinator and state machine. It exposes password,
server-triggered SMS, CAPTCHA, TOTP, RADIUS, and challenge state transitions as
structured events through `StartAuthentication`, `SubmitAuthentication`,
`GetPendingCaptchaImage`, `CancelAuthentication`,
`HasReusableAuthenticatedResult`, and `ClearAuthenticatedResult`;
`ExportAuthenticatedSession` and
`ResumeAuthentication` for the encrypted-at-rest recovery handoff; and a
single active flow that never puts
credentials, cookies, SID, device identifiers, sign keys, CAPTCHA data, or raw
responses into callback JSON. The production AAR remains temporarily pinned to
the Cedar compatibility core above because the data plane has not yet migrated
off the fork; the separate upstream auth compatibility test compiles this
Android-facing API against the pinned upstream `main` merge commit.

Authentication success leaves its client/resource result in Go memory for real
VPN setup. Its exported recovery snapshot contains only a schema version,
the exact device ID, and the complete endpoint cookie set. Kotlin encrypts
those bytes with Android Keystore AES-GCM and atomically stores the envelope in
`noBackupFilesDir`. After a process restart, the user's Connect action asks Go
to validate the restored cookies with the server and refetch username and
resources before recreating the in-memory result. App startup itself does not
read or validate the snapshot. The recovery ladder and invalidation policy,
including `sessionExpired != invalidSession`, are authoritative in
[authentication-recovery.md](authentication-recovery.md).

The bridge keeps a separate copy of a complete authenticated result while the
process remains alive. `CancelAuthentication()` cancels only the active
interactive flow and preserves that reusable result;
`HasReusableAuthenticatedResult()` reports availability without exposing its
fields. `ClearAuthenticatedResult()` clears both the reusable copy and any
result still held by the active flow.

`ResumeAuthentication()` is also callable by `RealVpnService` when Android
starts the app in Always-on mode without an Activity. The service reads only the
Keystore-protected snapshot and the current device identity, zeroes the
decrypted bytes after the bridge call, and never supplies `SavedCredentialStore`
data. The foreground and service-side recovery boundary is defined in
[authentication-recovery.md](authentication-recovery.md).

Android derives a stable 32-character aTrust device ID from its app-scoped
`ANDROID_ID` and supplies it to both start and resume calls. A restored legacy
snapshot cannot replace that identity. Saved-credential storage, reuse, and
invalidation rules are defined in
[authentication-recovery.md](authentication-recovery.md).

The bridge exposes `PrepareRealVpn()`, which prepares an
`atrust.Client` and returns a versioned event containing only the assigned IPv4
address and IPv4 resource prefixes. `StartRealVpn(tunFD, protector, listener)`
attaches the Android TUN and installs the `VpnService.protect()` boundary for
future underlay connections. `DiscardPreparedRealVpn()` releases only a
prepared client that has not yet been attached to a TUN, leaving an active VPN
untouched. `StopRealVpn()` is idempotent and closes the client, TUN, underlay
sockets, and L3 readers. Cancelling returns without
waiting for an in-flight request: it cancels the request context, closes that
session's active HTTP connections, and clears its sensitive state before any
stale event can reach the UI. QR/CAS/OAuth login and bridge-owned reconnect
behavior are out of scope.

Android owns network-switch recovery by calling the existing idempotent
`StopRealVpn()`, then `PrepareRealVpn()` and `StartRealVpn()` again after the
new underlay settles. The Go bridge does not migrate live sockets, choose an
Android `Network`, or implement an independent reconnect loop.

The Android data loop forwards IPv4 TCP/UDP packets. Packets outside the
aTrust resource set are dropped as split-tunnel traffic instead of terminating
the whole VPN. TUN and aTrust L3 I/O failures are mapped to stable UI error
codes, and cleanup preserves the original failure instead of replacing it with
an uninformative `stopped` state.

## Licensing

The pinned zju-connect repository is licensed under AGPL-3.0-only. Linking it
into a distributable Android application requires preserving the applicable
license notices and satisfying its source-availability obligations. Release
compliance must be established before distributing an APK containing the
generated AAR.
