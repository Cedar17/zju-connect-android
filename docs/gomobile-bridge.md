# Reproducible gomobile bridge

This guide owns the Kotlin–Go API, pinned AAR build and Android release process.
[Architecture](architecture.md) owns Android lifecycle and network recovery;
[authentication recovery](authentication-recovery.md) owns persisted state and
invalidation rules. Machine/device-specific commands stay in the local guide.

## Pinned inputs

[`tools/gomobile.lock.json`](../tools/gomobile.lock.json) is authoritative for
core/toolchain versions and checksums; `go/bridge/go.mod` and `go.sum` must match
its official zju-connect dependency. The AAR build rejects a core module `replace`.
Core contributions belong in the separate upstream checkout; Android pins a
fetchable official commit after upstream integration.

| Input | Value |
| --- | --- |
| Official core | Exact module version and commit in the lockfile |
| Go | 1.25.6 |
| golang.org/x/mobile | v0.0.0-20260602190626-68735029466e |
| Android NDK | 29.0.14206865 (r29) |
| Android API / Build Tools | 29 / 36.0.0 |
| AAR ABIs | arm64-v8a and x86_64 |
| Release APK ABI | arm64-v8a only |

Bootstrap checks the Go archive's SHA-256 and NDK archive's published SHA-1.
Tools live in ignored `.gomobile-toolchain`, except the NDK under the configured
Android SDK. Application SDK/Gradle settings remain in the checked-in build files.

## Build and validate

Use Windows PowerShell at the repository root, with SDK Platform 36.1 and Build
Tools 36.0.0 installed. Bootstrap downloads pinned dependencies; use it when
provisioning the toolchain, subject to local authorization rules:

~~~powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\bootstrap-gomobile-toolchain.ps1
~~~

With the toolchain available, test, rebuild the AAR after production bridge/core
changes, then package Android:

~~~powershell
.\.gomobile-toolchain\go\bin\go.exe -C .\go\bridge test ./...
.\.gomobile-toolchain\go\bin\go.exe -C .\go\bridge vet ./...
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-gomobile-aar.ps1
cmd.exe /c "gradlew.bat :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease --no-daemon --console=plain"
~~~

The scripts accept `-AndroidSdkRoot`, `-JavaHome` and `-ToolRoot` overrides.
AAR generation checks `go mod tidy -diff`, downloads/verifies pinned modules,
and writes these ignored outputs under `app/libs/`:

- `zju-connect-core.aar`
- `zju-connect-core-sources.jar`
- `zju-connect-core.build-info.json`, containing AAR SHA-256 and pinned inputs

Verify the receipt against the AAR and lockfile before Android packaging.
Gradle's `verifyGoCoreAar` checks AAR existence and prints the required build
command when absent; it does not itself validate the receipt or bootstrap tools.
When using cached dependencies only, use Gradle `--offline` and Go
`GOPROXY=off` / `GOSUMDB=off` as appropriate.

Go tests cover bridge behavior and consumed public upstream APIs. Android Release
unit tests cover policies, codecs, lifecycle and diagnostics; lint and assembly
check the Android package. These host checks complement the
[device acceptance scope](architecture.md#validation) and
[authentication validation contract](authentication-recovery.md#validation-contract).
The [CI workflow](../.github/workflows/ci.yml) runs Go tests/vet, AAR generation,
Release unit tests/lint and unsigned APK assembly on PRs targeting `master`.

### R8 and crash mappings

Release enables R8 optimization/obfuscation and resource shrinking with
`proguard-android-optimize.txt`. The AAR supplies consumer keep rules for `go.**`
and `io.github.cedar17.zjuconnect.gocore.**`, preserving JNI entry points and
callback interfaces. Optional Compose group-key mapping is disabled for the
pinned offline toolchain; ordinary R8 mappings are still generated.

Preserve the corresponding `app/build/outputs/mapping/release/` files with the
exact APK version/hash for crash investigation. These files are ignored locally.
Current CI and release workflows publish no mapping artifact; release builds
publish only the APK. Deobfuscation support for those builds requires adding
mapping retention to the workflow.

## Versioning and release

`app/build.gradle.kts` owns `versionName` and integer `versionCode`. Patch versions
cover compatible fixes/small improvements; minor versions mark features or
substantial core/behavior changes. During `0.x`, minor versions identify
milestones. After `1.0.0`, incompatible supported-use, configuration or persisted
state changes require a major version.

Increment `versionCode` for each distinct distributed build. Publish from a merged
integration commit with tag `v<versionName>`, matching the APK filename
`zju-connect-v<versionName>-arm64-v8a.apk`. Existing releases retain their contents.
The [tag workflow](../.github/workflows/release.yml) restores the Release key from
`RELEASE_KEYSTORE_BASE64`, runs Release unit tests, assembles/signs the APK,
verifies its signature and creates a regular GitHub Release.

Local APK, passing CI, merged PR and published Release are separate states.
Record actual device acceptance and remaining gaps in the PR before publishing.
Issue measurements and product tradeoffs also belong there. The linked upstream
core uses AGPL-3.0-only; distribution preserves license notices and the applicable
source-availability obligations.

## Kotlin-Go contract

The source declarations in [`bridge.go`](../go/bridge/bridge.go),
[`real_vpn.go`](../go/bridge/real_vpn.go) and
[`socket_protector.go`](../go/bridge/socket_protector.go) define the API. The host
stub matches VPN entry points for non-Android tests.
[`GoCoreBridge.kt`](../app/src/main/kotlin/io/github/cedar17/zjuconnect/GoCoreBridge.kt)
wraps generated Java bindings; Go integer parameters follow gomobile's generated
Java/Kotlin types.

Control-plane requests/results use JSON strings; snapshots and CAPTCHA images
use byte arrays. Packets pass through the TUN file descriptor. Go callback
interfaces are
`BridgeListener.OnEvent(eventJSON)` and `SocketProtector.Protect(socketFD) bool`;
the generated Kotlin implementations override `onEvent` and `protect`.
Event payloads carry `schemaVersion` and `type`; stable state/code/cause fields
support UI mapping independently of raw messages. Authentication errors may add
`stage`, `cause` and bounded `durationMs`.

Callback JSON excludes passwords, cookies, SID, DeviceID, sign keys, CAPTCHA bytes,
verification input and raw server responses. Authentication events can supply
confirmed username and phone choices for UI; these are not diagnostic records.
Prepared address/routes configure the Android TUN and likewise stay outside the
redacted report. CAPTCHA bytes are retrieved only through `GetPendingCaptchaImage`.

### Authentication API

| Go entry point | Result / effect |
| --- | --- |
| `StartAuthentication(requestJSON, listener)` | JSON response; starts the single asynchronous authentication flow |
| `SubmitAuthentication(responseJSON)` | JSON response; advances or retries the current flow |
| `ResumeAuthentication(snapshotBytes, deviceID, listener)` | JSON response; validates snapshot and starts server-side session recovery |
| `ExportAuthenticatedSession()` | Opaque snapshot bytes for Android encryption |
| `GetPendingCaptchaImage()` | Current challenge image bytes |
| `CancelAuthentication()` | Cancels interactive flow and clears its verification input |
| `HasReusableAuthenticatedResult()` | Boolean availability, without exposing session fields |
| `ClearAuthenticatedResult()` | Clears reusable result and result retained by the active flow |

The start request carries the fixed ZJU server/port and Android-derived device ID;
resume receives the current device ID explicitly. The bridge calls upstream
`auth.NewSessionContext`, `NewLoginMethod`, `Session.Login` and
`authchallenge.Handler`; it selects supported methods and maps interactive
challenges into structured events. Authentication result retention, snapshot
contents, background restrictions and invalidation are defined once in the
[authentication guide](authentication-recovery.md).

### VPN API

| Go entry point | Result / effect |
| --- | --- |
| `PrepareRealVpn()` | JSON configuration; prepares a client or reuses an already prepared one |
| `StartRealVpn(tunFD, protector, listener)` | Attaches the Android TUN to the prepared client and starts callbacks |
| `CancelPreparingRealVpn()` | Cancels the current in-flight pre-TUN preparation |
| `DiscardPreparedRealVpn()` | Releases an already prepared, unattached client; leaves active VPN alone |
| `StopRealVpn()` | Idempotently cancels preparation and releases prepared/active VPN resources |

Preparation consumes the authenticated result already in Go memory and calls
upstream `SetupContext` with a 30-second deadline. Success supplies IPv4 address,
resource prefixes and MTU. Android establishes the TUN, then attaches it with the
socket protector for future underlay connections.

Cancellation clears preparation ownership before a late result can be published.
The preparation owner closes its client once; cancellation does not wait for the
setup goroutine to finish. Full stop closes TUN/client resources and waits for
reader shutdown, bounded by a cleanup timeout. Cancellation/discard/stop retain
the authentication assets according to the authentication guide.

The data loop forwards IPv4 TCP/UDP, using lightweight IP header/total-length and
protocol checks. Packets outside aTrust resources are split-tunnel drops; TUN/L3
I/O errors produce stable failure codes. Inbound validation updates counters and
retains the existing write behavior. Lifecycle/failure callbacks carry numeric
counter snapshots, not per-packet metadata, endpoint parsing or checksum samples.

### Measurements

Preparation exposes optional `setupDurationMs` and `reusedPrepared` with safe
Kotlin defaults when absent. `setupDurationMs` is Go's monotonic duration of the
whole `SetupContext`, not its manifest/probe/virtual-IP sub-stages. A prepared
cache hit retains the original setup duration while `durationMs` describes only
the current call. The preparation stage is fixed at `prepare.setup`.

`ZjuConnectRealVpn` logs local monotonic durations for ViewModel/Service prepare,
TUN establishment and bridge attachment. Count actual setup once; a Service cache
hit must not hide earlier ViewModel work. Logs measure individual calls, not a
successful end-to-end attempt, and introduce no cross-component trace state or
persisted measurement schema.
