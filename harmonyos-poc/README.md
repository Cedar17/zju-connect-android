# HarmonyOS PoC

HarmonyOS 手机、平板和 PC 共用的原生客户端实验工程。当前第一阶段只验证 Go runtime、cgo shared library 与 ArkTS/Node-API 的调用链；VPN 和 aTrust 集成在后续阶段接入。

## P0：Go runtime + NAPI

验收目标：Go 1.26.5 OpenHarmony 工具链编译出 ARM64 `.so`，由 HarmonyOS 应用加载，并从 ArkTS 线程调用 Go 导出函数。

### 环境

- Windows 11 x64。
- DevEco Studio 6.0 或更新版本，安装 HarmonyOS SDK 6.0.0(20) 和对应 NDK。
- Go 1.25.6 或更新版本作为工具链 bootstrap。仓库现有 `.gomobile-toolchain/go` 可直接复用。
- Git。

### 构建 Go 工具链

在仓库根目录执行：

```powershell
.harmonyos-pocscriptsootstrap-ohos-go.ps1
```

脚本固定检出 `star4277/ohos-go` 的指定 commit，并从源码构建 `GOOS=openharmony` 工具链。工具链源码和构建产物放在 `harmonyos-poc/.toolchain/`，不会提交到 Git。

### 构建 Go shared library

将 DevEco SDK 的 OpenHarmony 根目录设为环境变量：

```powershell
$env:OHOS_SDK_ROOT = "C:\Program Files\Huawei\DevEco Studio\sdk\default\openharmony"
.harmonyos-poc\scripts\build-go-probe.ps1
```

脚本会生成：

`harmonyos-poc/app/entry/libs/arm64-v8a/libzju_go_probe.so`

并检查 ELF 的 `PT_TLS` 和 `R_AARCH64_TLSDESC`，避免只验证“编译成功”而遗漏 HarmonyOS 动态加载所需的 TLS 模型。

### 真机验证

1. 在 DevEco Studio 中打开 `harmonyos-poc/app`。
2. 等待工程同步，选择 MateBook Pro S 对应的真机设备，配置本机签名。
3. Build HAP 并安装运行。
4. 点击 **Run Go + NAPI probe**。

预期结果包含：

- `loaded=true`
- Go 版本为 `go1.26.5`
- `addResult=42`

这一步验证 Go shared library 能被 HarmonyOS 加载，并且 ArkTS → NAPI → Go runtime 的调用可正常返回。它不代表 VPN 数据面或 aTrust 已经打通。

## 后续阶段

- P1：VpnExtensionAbility 创建 TUN，验证 fd 生命周期和 Go 读写。
- P2：复用现有 Go aTrust 认证流程，接入完整挑战交互及 HUKS 加密会话恢复。
- P3：将 aTrust 返回的地址和资源路由配置到系统 VPN，验证真实浙大内网访问、断开和诊断。

Android 现有工程保持独立；鸿蒙端通过共享 Go 核心和平台适配层复用协议实现。
