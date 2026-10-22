# Android client architecture

This guide defines Android component ownership, VPN lifecycle, network recovery,
UI and diagnostics. The other two guides own narrower contracts:

- [Authentication recovery](authentication-recovery.md): stored state, reuse,
  invalidation and foreground/background authentication policy.
- [gomobile bridge](gomobile-bridge.md): Kotlin–Go interfaces, pinned builds,
  R8 and release procedures.

## Scope and components

The app connects Android devices to Zhejiang University's aTrust/RVPN service.
It uses one Kotlin/Compose/Material 3 application module and the official
zju-connect Go module through a generated gomobile AAR. App launch loads the
remembered username and observes the service; connection work begins on demand.

```text
Compose UI → ConnectionViewModel → MainActivity → manual service start
                  │
                  └── AuthSessionStore / SavedCredentialStore / AccountStore

Quick Settings Tile ── tile start / foreground handoff ──┐
Android system ─────── Always-on start ──────────────────┤
                                                       ↓
                                    RealVpnService / RealVpnStateStore
                                                       ↓
                                         Android TUN + Go bridge
                                                       ↓
                                         Official zju-connect core
```

| Component | Ownership |
| --- | --- |
| Compose UI | Render state and collect input through resource-backed text |
| `ConnectionViewModel` | Foreground connection attempt and authentication recovery |
| `MainActivity` | System VPN permission and one-shot service-dispatch effects |
| `QuickSettingsTileService` | User-added tile, permission check and direct connect/disconnect |
| `RealVpnService` | TUN, foreground notification, socket protection and underlay recovery |
| Go bridge | Single authentication coordinator, prepared/active client ownership and JSON boundary |
| Official Go core | aTrust protocol, authentication, resource parsing/routing, L3 transport and internal L3 reconnect |

The client targets aTrust and one active account. Legacy EasyConnect, WebVPN,
local proxy/port forwarding and general VPN configuration are outside its scope.
Keep the single app module unless a demonstrated ownership or build need calls
for another module. Core changes belong upstream; Android platform policy stays
here. Dependency pins and the no-`replace` build rule are in the bridge guide.

## Connection lifecycle

[`ConnectionViewModel.kt`](../app/src/main/kotlin/io/github/cedar17/zjuconnect/ConnectionViewModel.kt)
defines `ConnectionPhase` and attempt-scoped effects. A foreground attempt follows
[the authentication recovery flow](authentication-recovery.md#foreground-recovery)
through permission, preparation, TUN establishment and connection. Challenges,
errors and cancellation are explicit states, rather than inferred log messages.

For an in-process reusable result, the ViewModel can prepare before dispatching
the Service, allowing authentication rejection to fall back in the foreground.
The Service then consumes the already prepared client. A zero-duration Service
cache hit therefore does not measure the complete connection attempt.

Android publishes the foreground notification before network setup. The Service
creates the TUN from prepared routes and installs `VpnService.protect(fd)` for
future Go underlay sockets, keeping those sockets out of the VPN routing loop.
Its establish watchdog, failure handling and deterministic cleanup cover the TUN
file descriptor, Go client, underlay sockets and reader goroutines.

| Guard | What it protects |
| --- | --- |
| Foreground attempt ID | Reject old authentication callbacks and permission/service effects |
| Process-local connection-entry lease | Serialize Activity and tile entry; transfer cleanup ownership on dispatch |
| Service lifecycle and bridge generation | Reject progress after stop/failure and callbacks from replaced sessions |
| Go preparation owner | Close once on cancellation and reject late setup results after ownership changes |

User stop, revoke, destruction and the first terminal failure cancel pending
recovery. Cleanup retains that failure instead of replacing it with a generic
`stopped` outcome. The distinctions among in-flight cancellation, prepared-client
discard and active stop are defined in the [bridge API](gomobile-bridge.md#vpn-api).

### Start paths and notifications

| Entry | Marker | Service restart policy |
| --- | --- | --- |
| Activity | Explicit manual start | `START_NOT_STICKY` |
| Quick Settings | Explicit tile start | `START_NOT_STICKY` |
| Android Always-on | No app-owned start marker | `START_STICKY` |

Android system VPN settings own Always-on. While it is enabled, disconnect
leaves the VPN running and posts guidance opening those settings. Foreground
handoff and the background authentication boundary are defined in the
[authentication guide](authentication-recovery.md#foreground-and-background).

One ongoing localized notification follows connecting, active and recovery
states, opens `MainActivity` when tapped, and is removed on stop/failure. Android
13+ notification permission is requested after user-initiated service dispatch;
denial does not block VPN establishment. The channel starts at normal/default
importance; users control its final alert behavior in system settings.

The home screen has one context-sensitive primary action, account switching
while disconnected, and IME-safe authentication fields. Battery management,
OEM autostart and recent-task locking remain system/device settings.

## Underlay recovery

Android observes Internet-capable non-VPN networks and captures the system's
selected underlay at session start. Recovery follows that underlay; changes to an
unrelated backup network leave a working session alone. The in-memory fingerprint
covers network identity, transport, suspension, interface and IPv4/default-route
changes. Network identifiers are excluded from diagnostics.

A stable relevant change is debounced for 1.5 seconds, then the Service closes
the old Go/TUN session and rebuilds from the authenticated result. With no usable
network, it waits for a network to return. Service-owned `recovering` and
`waitingForNetwork` states map into the existing UI presentation.

Cold Always-on validation allows three attempts at delays of 0, 5 and 30 seconds
for one underlay revision. Exhaustion waits for a new revision or foreground
retry. `waitingForAuthentication` requests foreground participation; it does not
start another login state machine in the Service.

Protected sockets use the system-selected underlay without explicit binding to
an Android `Network`. Consequently `setUnderlyingNetworks` stays at its default.
Internal L3 reconnect belongs to the official core; errors returned through its
public connection become terminal bridge failures. Android owns tunnel rebuilds,
not another protocol reconnect loop.

## Interface languages

English and Simplified Chinese share Compose screens and connection policies.
English defaults are in `res/values/strings.xml`; Chinese resources are in
`res/values-zh-rCN/strings.xml`. UI states and errors resolve through string
resources rather than raw bridge messages. The manifest references
`res/xml/locales_config.xml`, declaring `en` and `zh-CN`.

Language normally follows the system; Android 13+ offers per-app selection in
system settings. There is no in-app language picker. Documentation uses labels
from the relevant resources: “诊断 → 复制报告” or “Diagnostics → Copy report”.

## Diagnostics

The non-exported diagnostics Activity reads a bounded buffer of at most 100
allowlisted records in `noBackupFilesDir`. Records contain state, authentication
stage, stable code/cause, bounded duration and aggregate packet counters. They
exclude account identity, credentials, endpoints, network identifiers, routes,
packet metadata, payloads, raw messages and Logcat.

The localized screen projects a summary and recent history, grouping consecutive
equal display states even when only counters differ. Copying preserves the full
bounded record sequence and environment header. The copied report currently has
fixed Chinese headings and English field names/state codes. Report timestamps
use UTC; on-screen history uses the device time zone. The authentication guide
owns [recovery labels](authentication-recovery.md#observable-recovery-paths);
the bridge guide owns [local call measurements](gomobile-bridge.md#measurements).

Encrypted state contents and Keystore storage are defined only in
[authentication-recovery.md](authentication-recovery.md#authentication-state).
Verification challenges remain interactive human steps, and sensitive values
stay out of logs and callback JSON as specified by the bridge contract.

## Validation

Use the [build and validation procedure](gomobile-bridge.md#build-and-validate)
for host tests and packaging. Device acceptance covers normal disconnect/reconnect,
process-restart recovery, cancellation/failure cleanup, Wi-Fi/cellular/Ethernet
changes and TUN/socket/foreground-service release. Check actual resource access
with CLI requests to `https://www.cc98.org/` and `http://10.10.98.98/`.

On a campus underlay, successful HTTP alone cannot establish VPN traversal;
correlate with tunnel activity. Record device, build and actual acceptance scope
in the PR, identifying maintainer-reported tests. Historical acceptance does not
validate a later build. Issue measurements and product decisions also belong in
PRs rather than this guide.
