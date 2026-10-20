# Reproducible gomobile bridge

This document defines the reproducible gomobile boundary. It builds a local
AAR from pinned source and toolchain inputs; the AAR is intentionally ignored
by Git.

## Pinned inputs

The authoritative machine-readable record is
[tools/gomobile.lock.json](../tools/gomobile.lock.json).

| Input | Pinned value |
| --- | --- |
| Official zju-connect core | Exact module version and commit in the lockfile |
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
redacted diagnostics. It does not replace the Go bridge tests, which also cover
the public upstream authentication API used by Android, or real-device checks.

## Versioning and release

`app/build.gradle.kts` owns the user-facing `versionName` and Android's integer
`versionCode`. For this app, patch releases contain fixes and small compatible
improvements; minor releases introduce features or substantial core/behavior
changes. During `0.x` development, the minor number identifies a development
milestone. After `1.0.0`, incompatible changes to supported use, configuration,
or persisted state require a major release.

Increment `versionCode` for each distinct distributed build. A release tag must
be `v<versionName>` so the generated APK name matches the release workflow.
Publish from the merged integration commit; do not replace an existing release
with different contents. The tag workflow builds and signs the arm64 APK and
publishes a GitHub Pre-release. A local APK, successful CI, a merged PR, and a
published release are separate states. Record real-device acceptance in the PR
with its actual scope before publishing.

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

The production bridge uses upstream `auth.NewSessionContext`, `NewLoginMethod`,
`Session.Login`, and `authchallenge.Handler`. Upstream owns the aTrust protocol,
session, and challenge definitions. The Android bridge implements the challenge handler and
owns the UI-facing coordinator and state machine. It exposes password,
server-triggered SMS, CAPTCHA, TOTP, RADIUS, and challenge state transitions as
structured events through `StartAuthentication`, `SubmitAuthentication`,
`GetPendingCaptchaImage`, `CancelAuthentication`,
`HasReusableAuthenticatedResult`, and `ClearAuthenticatedResult`;
`ExportAuthenticatedSession` and
`ResumeAuthentication` for the encrypted-at-rest recovery handoff; and a
single active flow that never puts
credentials, cookies, SID, device identifiers, sign keys, CAPTCHA data, or raw
responses into callback JSON. The production Go module depends directly on the
pinned official upstream core. The AAR build rejects a core module `replace`;
the bridge tests and Android AAR build validate the consumed public APIs.

TLS policy is inherited from that pinned core: authentication and tunnel
transports currently skip standard certificate-chain and hostname verification.
The Android bridge supplies no custom certificate or SPKI verification policy.

Authentication success leaves its client/resource result in Go memory for real
VPN setup. Its exported recovery snapshot contains only a schema version,
the exact device ID, and the complete endpoint cookie set. Kotlin encrypts
those bytes with Android Keystore AES-GCM and atomically stores the envelope in
`noBackupFilesDir`. After a process restart, the user's Connect action asks Go
to validate the restored cookies with the server and refetch username and
resources before recreating the in-memory result. App startup itself does not
read or validate the snapshot. `ServerVersionInfo` is not part of this snapshot;
upstream setup must fetch the server manifest during preparation. The recovery
ladder and invalidation policy, including `sessionExpired != invalidSession`, are authoritative in
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
`atrust.Client` through `SetupContext` with a 30-second preparation deadline,
and returns a versioned event containing only the assigned IPv4
address and IPv4 resource prefixes. `StartRealVpn(tunFD, protector, listener)`
attaches the Android TUN and installs the `VpnService.protect()` boundary for
future underlay connections. `DiscardPreparedRealVpn()` releases only a
prepared client that has not yet been attached to a TUN, leaving an active VPN
untouched. `StopRealVpn()` is idempotent and closes the client, TUN, underlay
sockets, and L3 readers. Cancelling returns without
waiting for an in-flight request: it cancels the caller context and rejects
late results after ownership changes. QR/CAS/OAuth login and bridge-owned reconnect
behavior are out of scope.

Android owns network-switch recovery by calling the existing idempotent
`StopRealVpn()`, then `PrepareRealVpn()` and `StartRealVpn()` again after the
new underlay settles. The Go bridge does not migrate live sockets, choose an
Android `Network`, or implement an independent reconnect loop. Internal L3
reconnect belongs to upstream; errors returned by the public L3 connection are
mapped to terminal Android VPN failures.

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
