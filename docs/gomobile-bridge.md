# Reproducible gomobile bridge

This document defines the reproducible gomobile boundary. It builds a local
AAR from pinned source and toolchain inputs; the AAR is intentionally ignored
by Git.

## Pinned inputs

The authoritative machine-readable record is
[tools/gomobile.lock.json](../tools/gomobile.lock.json).

| Input | Pinned value |
| --- | --- |
| zju-connect upstream base | **1b6ad138737547782dcfc09def2a950738a67188** |
| Android real-VPN fork | **Cedar17/zju-connect** at **1a736c5355b8a2bcafee7827ccf147ea38ab0a32** |
| Go | **1.25.6** |
| golang.org/x/mobile | **v0.0.0-20260602190626-68735029466e** (68735029466e…) |
| Android NDK | **29.0.14206865** (r29) |
| Android API / Build Tools | **29** / **36.0.0** |
| AAR ABIs | **arm64-v8a**, **x86_64** |

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
cmd.exe /c "gradlew.bat :app:assembleDebug --no-daemon --console=plain"
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
`ByteArray`, and one callback interface:

- GetBuildInfo() String returns a deterministic versioned JSON event.
- EmitBuildInfo(BridgeListener) delivers that event through onEvent(String).
- FetchAuthInfo(requestJson) String calls the upstream public
  atrust.GetAuthInfoList API and returns a redacted JSON response. Its request
  is limited to server and port; it never accepts credentials.

[GoCoreBridge.kt](../app/src/main/kotlin/io/github/cedar17/zjuconnect/GoCoreBridge.kt) owns
the generated Java API. Build-info and credential-free discovery functions
remain validation surfaces, but the production home screen neither calls nor
displays them. `ConnectionViewModel` uses the same wrapper for the single active
authentication flow, beginning network work only after the user taps Connect.

All event payloads contain schemaVersion and type. Future callback events must
preserve this versioned JSON boundary and must not contain passwords, cookies,
SIDs, device IDs, sign keys, CAPTCHA bytes, or raw authentication responses.

## Interactive authentication control plane

The original upstream's mobile/mobile_android.go exposes EasyConnect Login,
Logout, and StartStack; it is not an aTrust bridge. The maintained Android fork
adds a small in-memory aTrust state machine that preserves certificate and host
validation and replaces its synchronous CLI interactions. The Android façade
provides password, server-triggered SMS, CAPTCHA, TOTP, RADIUS, and challenge state transitions as
structured events; `StartAuthentication`, `SubmitAuthentication`,
`GetPendingCaptchaImage`, `CancelAuthentication`, and
`ClearAuthenticatedResult`; `ExportAuthenticatedSession` and
`ResumeAuthentication` for the encrypted-at-rest recovery handoff; and a
single active flow that never puts
credentials, cookies, SID, device identifiers, sign keys, CAPTCHA data, or raw
responses into callback JSON.

Authentication success leaves its client/resource result in Go memory for real
VPN setup. Its exported recovery snapshot contains only a schema version,
the exact device ID, and the complete endpoint cookie set. Kotlin encrypts
those bytes with Android Keystore AES-GCM and atomically stores the envelope in
`noBackupFilesDir`. After a process restart, the user's Connect action asks Go
to validate the restored cookies with the server and refetch username and
resources before recreating the in-memory result. App startup itself does not
read or validate the snapshot. An explicit session expiry clears only the
cookie snapshot and continues with the same device identity; transport and TLS
failures retain cookies and saved credentials for retry.

Android derives a stable 32-character aTrust device ID from its app-scoped
`ANDROID_ID` and supplies it to both start and resume calls. A restored legacy
snapshot cannot replace that identity. When cookies expire, the same flow
returns to the server-advertised authentication method instead of discarding
the device identity. Password credentials are encrypted separately with a
dedicated Android Keystore AES-GCM key, are written only after successful
authentication, and are removed when the server explicitly rejects them.

The bridge exposes `PrepareRealVpn()`, which prepares an
`atrust.Client` and returns a versioned event containing only the assigned IPv4
address and IPv4 resource prefixes. `StartRealVpn(tunFD, protector, listener)`
attaches the Android TUN and installs the `VpnService.protect()` boundary for
future underlay connections. `StopRealVpn()` is idempotent and closes the
client, TUN, underlay sockets, and L3 readers. Cancelling returns without
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

## Experimental data-plane validation boundary

The bridge additionally exposes a credential-free validation surface:

- SocketProtector.Protect(socketFD) is implemented by Android VpnService.
- StartTestDataPlane(tunFD, protector, listener) takes ownership of the
  detached TUN descriptor on the Go side.
- StopTestDataPlane() is idempotent and closes the TUN and fake transport.
- Event payloads use type = testVpnState and report state, stable error code,
  packet counts, and byte counts.
- The Go side opens a local UDP echo transport, calls Protect through
  net.Dialer.Control before connecting, and reflects only the fixed
  zju-connect-tun-test-v1 IPv4/UDP marker.

This is an integration probe for Android TUN, gomobile callbacks, descriptor
ownership, socket protection, and cleanup. It must not be mistaken for the
production aTrust client API or a proof of real school-network access.

## Licensing

The pinned zju-connect repository is licensed under AGPL-3.0-only. Linking it
into a distributable Android application requires preserving the applicable
license notices and satisfying its source-availability obligations. Release
compliance must be established before distributing an APK containing the
generated AAR.
