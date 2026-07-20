# Reproducible gomobile bridge

This document implements Issue #3's Phase 2 boundary. It builds a local AAR
from a pinned source and toolchain; the AAR is intentionally ignored by Git.

## Pinned inputs

The authoritative machine-readable record is
[tools/gomobile.lock.json](../tools/gomobile.lock.json).

| Input | Pinned value |
| --- | --- |
| zju-connect | **7776cdcfa33e3df56ba8da438c17b2274e316128** |
| Go module | **v1.2.2-0.20260717055316-7776cdcfa33e** |
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

The Gradle unit-test task currently has no Kotlin/Java test sources and may
finish with `NO-SOURCE`; it does not replace the Go bridge tests above.

## Kotlin–Go contract

The Go package is [go/bridge](../go/bridge). It exposes only gomobile-safe
strings and one callback interface:

- GetBuildInfo() String returns a deterministic versioned JSON event.
- EmitBuildInfo(BridgeListener) delivers that event through onEvent(String).
- FetchAuthInfo(requestJson) String calls the upstream public
  atrust.GetAuthInfoList API and returns a redacted JSON response. Its request
  is limited to server and port; it never accepts credentials.

[GoCoreBridge.kt](../app/src/main/kotlin/cn/zju/connect/GoCoreBridge.kt) owns
the generated Java API. The startup screen calls both build-info methods and
shows the returned upstream commit, which makes the binding and reverse
callback observable without a network connection.

All event payloads contain schemaVersion and type. Future callback events must
preserve this versioned JSON boundary and must not contain passwords, cookies,
SIDs, device IDs, sign keys, CAPTCHA bytes, or raw authentication responses.

## Upstream gaps before the next phase

The pinned upstream's mobile/mobile_android.go exposes EasyConnect Login,
Logout, and StartStack; it is not an aTrust bridge. Its aTrust path instead
exposes atrust.GetAuthInfoList and a synchronous atrust.Client.Setup that takes
credentials and files as inputs. The next phase needs a reviewed
Android-facing façade for:

- password, SMS, OAuth/CAS, and CAPTCHA state transitions as structured events;
- in-memory CAPTCHA bytes rather than GraphCodeFile;
- an explicit, encrypted session snapshot rather than external client/resource
  files and raw aTrust identifiers;
- passing an Android TUN descriptor with defined ownership and close behavior;
- creating or protecting every underlying socket with VpnService.protect(fd)
  before it connects.

No authentication, TUN, socket protection, session persistence, or connection
UI is implemented by this bridge.

## Licensing

The pinned zju-connect repository is licensed under AGPL-3.0-only. Linking it
into a distributable Android application requires preserving the applicable
license notices and satisfying its source-availability obligations. This issue
does not make a release-compliance determination; do that before distributing
an APK containing the generated AAR.
