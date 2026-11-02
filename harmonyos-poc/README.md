# HarmonyOS PoC

HarmonyOS 手机、平板和 PC 共用的原生客户端实验工程。当前第一阶段只验证 Go runtime、cgo shared library 与 ArkTS/Node-API 的调用链；VPN 和 aTrust 集成在后续阶段接入。

## P0：Go runtime + NAPI

验收目标：Go 1.26.5 OpenHarmony 工具链编译出 ARM64 `.so`，由 HarmonyOS 应用加载，并从 ArkTS 线程调用 Go 导出函数。

### 环境

- Windows 11 x64。
- DevEco Studio 26.0.0.851 和 Command Line Tools 26.0.0.851。
- HarmonyOS SDK 26.0.0（API 26），native 工具链。
- Go 1.25.6 或更新版本作为工具链 bootstrap。仓库现有 `.gomobile-toolchain/go` 可直接复用。
- Git。

### 检查本机环境

先解压 DevEco Studio 和 Command Line Tools，安装/配置 SDK 后，在仓库根目录执行：

```powershell
.\harmonyos-poc\scripts\doctor-ohos.ps1
```

如果脚本没有自动找到 SDK，传入包含 `native\sysroot` 和 `native\llvm` 的 OpenHarmony SDK 根目录：

```powershell
.\harmonyos-poc\scripts\doctor-ohos.ps1 -OhosSdkRoot "D:\Huawei\command-line-tools\sdk\default\openharmony"
```

### 构建 Go 工具链

在仓库根目录执行：

```powershell
.\harmonyos-poc\scripts\bootstrap-ohos-go.ps1
```

脚本固定检出 `star4277/ohos-go` 的 `v1.26.5-beta1`，并从源码构建 `GOOS=openharmony` 工具链。工具链源码和构建产物放在 `harmonyos-poc/.toolchain/`，不会提交到 Git。

### 构建 Go shared library

将 DevEco SDK 的 OpenHarmony 根目录设为环境变量：

```powershell
$env:OHOS_SDK_ROOT = "D:\Huawei\command-line-tools\sdk\default\openharmony"
.\harmonyos-poc\scripts\build-go-probe.ps1
```

脚本会生成并打包：

`harmonyos-poc/app/entry/libs/arm64-v8a/libzju_go_probe.so`

构建时检查 ELF 的 `PT_TLS`、`R_AARCH64_TLSDESC` 和三个 C 导出符号。只检查“编译成功”不足以证明 Go runtime 能在 HarmonyOS musl 环境中被动态加载。

### 真机验证

1. 在 DevEco Studio 中打开 `harmonyos-poc/app`。
2. 等待工程同步，选择 MateBook Pro S 对应的真机设备，配置本机签名。
3. Build HAP 并安装运行。
4. 点击 **Run Go + NAPI probe**。

预期结果包含：

- `loaded=true`
- Go 版本为 `go1.26.5 (openharmony/arm64)`
- `addResult=42`

这一步验证 Go shared library 能被 HarmonyOS 加载，并且 ArkTS → N-API → Go runtime 的调用可正常返回。它不代表 VPN 数据面或 aTrust 已经打通。

## SDK 版本策略

工程使用 API 26 编译，目标 API 26，最低兼容版本暂定 HarmonyOS 6.0.0（API 20）。这样可以使用新工具链，同时保留对较早 HarmonyOS 6 设备的安装兼容性；若 MateBook Pro S 的实际系统 API 高于 20，不需要因此提高最低版本。

## 后续阶段

- P1：VpnExtensionAbility 创建 TUN，验证 fd 生命周期和 Go 读写。
- P2：复用现有 Go aTrust 认证流程，接入完整挑战交互及 HUKS 加密会话恢复。
- P3：将 aTrust 返回的地址和资源路由配置到系统 VPN，验证真实浙大内网访问、断开和诊断。

Android 现有工程保持独立；鸿蒙端通过共享 Go 核心和平台适配层复用协议实现。
