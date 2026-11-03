# HarmonyOS PoC development

This directory is the HarmonyOS-specific work area of `Cedar17/zju-connect-android`, on branch `poc/harmonyos`. Work in Windows PowerShell 7, with DevEco Studio 26.0.0.851, SDK API 26, and DevEco CLI 1.3.0-stable. The app root for DevEco commands is `harmonyos-poc/app`.

## Scope

- First supported device for actual testing: MateBook Pro S, HarmonyOS PC. Source layout and UI must remain compatible with phones and tablets; do not claim validation on those devices without test evidence.
- Android app and existing Go bridge in `../app` and `../go/bridge` must remain unchanged during the PoC unless a concrete cross-platform extraction has been approved.
- P0: Go C-shared runtime through Node-API. P1: isolated system VPN TUN fd handed to Go. P2: authentication/challenge/resume. P3: dynamic routes and actual university resources. These are separate milestones; never label a PoC as a usable VPN.
- P1 uses an isolated test subnet and must not add a default route or alter DNS. The Go probe only duplicates and closes the duplicate fd; do not read/write network packets as part of this fd-validation step.
- Do not embed credentials, authentication snapshots, provisioning profiles, signing secrets, university account data, or machine-specific absolute paths in tracked files.
- Favor original Go authentication and L3 forwarding logic. Do not introduce tun2socks or replace the protocol stack without an actual compatibility blocker.
- Keep implementation minimal. Do not add extra documentation, dependencies, workflows, licenses, or unrelated refactors.

## Agent tooling

Use the locally installed `devecocli` (Huawei DevEco CLI). Search installed SDK references first instead of guessing ArkTS or VPN Extension signatures:

```powershell
devecocli --version
devecocli docs search VpnExtensionAbility
devecocli docs search vpnExtension
devecocli skills list
```

If the locally installed DevEco CLI supplies an official skill and MCP, follow their current help output to install them to this app project; prefer `devecocli init --agent codex --project .` and `devecocli init --mcp --agent codex --project .`. Only install relevant skills discovered from `devecocli skills find`. Agent config generated locally must not overwrite authored source files or include secrets.

## Build and verification

1. Run `..\scripts\doctor-ohos.ps1`, then `..\scripts\bootstrap-ohos-go.ps1`, then `..\scripts\build-go-probe.ps1` (from the app root), following `../README.md`.
2. Use `devecocli check arkts` or the installed SDK checker. Build a debug HAP with `devecocli build` or DevEco Studio.
3. On the real device run P0 first. Record `loaded=true`, version and `addResult=42`; report `dlopen` errors verbatim if it fails.
4. Only after P0 succeeds, start the P1 isolated probe. Check `hilog` for `P1_OK` or `P1_FAILED`. `startVpnExtensionAbility` returning successfully is not proof of fd success.
5. On every change provide evidence: command, return code, salient logs, SDK/device API, and whether it was source inspection, local build, or actual device testing.

Use ordinary commits on `poc/harmonyos`. Do not squash or change `master`.
