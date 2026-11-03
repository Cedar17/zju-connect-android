# HarmonyOS PoC

HarmonyOS 手机、平板和 PC 共用的原生客户端实验工程。第一台真机是 MateBook Pro S。本目录的 `app` 是 DevEco Studio 工程根目录；`native/go-probe` 是独立的 Go C-shared 运行时测试程序。

## 环境

- Windows 11 x64 / PowerShell 7。
- 已安装 DevEco Studio 26.0.0.851，自带 `sdk/default/openharmony`、Hvigor、ohpm、Node。
- DevEco CLI 1.3.0-stable 可选，但推荐供 Codex 使用。**不需要单独安装 Command Line Tools。**
- Go >= 1.24.6 用于 bootstrap；OpenHarmony fork 固定在 `toolchain.lock.json`。

## P0：Go runtime + N-API

在仓库根目录运行：

```powershell
git switch poc/harmonyos
.\harmonyos-poc\scripts\doctor-ohos.ps1
.\harmonyos-poc\scripts\bootstrap-ohos-go.ps1
$env:OHOS_SDK_ROOT = 'C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony'
.\harmonyos-poc\scripts\build-go-probe.ps1
```

工具链 bootstrap 在 `harmonyos-poc/.toolchain/ohos-go` 中构建第三方 `star4277/ohos-go v1.26.5-beta1`；C-shared 产物在 `harmonyos-poc/app/entry/libs/arm64-v8a/libzju_go_probe.so`，并检查 `PT_TLS`、`R_AARCH64_TLSDESC` 和四个导出符号。CMake 会把它复制进 HAP native 库目录。缺少 Go `.so` 时 HAP 构建会直接失败，不会生成一个伪装成功的空壳。

在 DevEco Studio 打开 `harmonyos-poc/app`，同步依赖、配置本机签名、安装到真机。点击 **P0 · Run Go probe**，成功标准是 `loaded=true`、版本包含 `go1.26.5 (openharmony/arm64)`、`addResult=42`。

## P1：系统 VPN fd → Go

P0 成功后，点击 **P1 · Start isolated VPN probe**。此步骤使用 `198.18.254.0/24` 的隔离路由，不添加默认路由、不覆盖 DNS、也不访问校网。VPN Extension 建立 TUN fd 后，Go 侧只 `dup` 然后关闭副本，确认 Go runtime 能在 VPN 扩展进程访问系统文件描述符。

`startVpnExtensionAbility` 返回仅表示启动请求已完成，并不意味着 fd 验证通过。需要在 DevEco hilog 或 `devecocli log` 中确认 `P1_OK` 或 `P1_FAILED`。完成后点击 Stop VPN probe。系统 VPN 授权或设备能力限制都属于实际测试结果，不能通过源码检查代替。

## Codex CLI + 华为工具

从本地的 HarmonyOS 工程根目录配置官方 DevEco CLI Skill 与 MCP：

```powershell
cd harmonyos-poc\app
devecocli docs search VpnExtensionAbility
devecocli skills list
devecocli init --agent codex --project .
devecocli init --mcp --agent codex --project .
codex
```

`devecocli skills find <keyword>` 检索相关细分技能，针对具体 ArkTS、API 兼容、设备测试问题按需安装；不要一次性安装全部技能。Node/npm 全局包若跳过 DevEco CLI 的安装脚本，应先运行文档检索和 `devecocli --help` 确认功能正常，必要时按 npm 的提示明确授权该包的安装脚本。

`AGENTS.md` 中有面向本地 Codex 的工程规则。Codex 的构建、签名、真机测试结果应完整记录真实命令、错误和关键日志。

## 下一阶段

P2：基于 `../go/bridge` 的真实 aTrust 认证、验证码和会话恢复；P3：通过服务器动态返回地址和资源路由构造 VPN 配置，完整建立双向 L3 转发并访问浙大内网。

当前代码属于 PoC，尚未完成 HarmonyOS HAP 编译或真机验证。
