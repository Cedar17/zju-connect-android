# zju-connect-android

浙江大学 ZJU RVPN 的开源 Android 客户端，支持 aTrust 协议，基于 zju-connect 构建。

An open-source Android client for ZJU RVPN, with aTrust protocol support. Powered [zju-connect](https://github.com/Mythologyli/zju-connect).

**Public Beta · Android 10+ · arm64-v8a · [GitHub Releases](https://github.com/Cedar17/zju-connect-android/releases)**

## 项目定位

`zju-connect-android` 是面向浙江大学 aTrust / RVPN 的非官方开源 Android 客户端，通过 Android `VpnService` 建立系统级校网访问隧道。

项目以 [zju-connect](https://github.com/Mythologyli/zju-connect) 作为 aTrust 协议与网络核心，目标是在服务端会话仍然有效时尽可能做到一键连接、一键断开；需要重新认证时，再清晰地完成服务端要求的认证流程。

项目专注于浙江大学 Android 校外访问，不是通用 VPN、代理或网络工具箱。

当前版本处于 **Public Beta** 阶段，支持 **Android 10+**，首版仅提供 **arm64-v8a** APK。

## 功能

- 浙江大学新版 aTrust / RVPN 登录与 VPN 连接；
- 密码、短信、动态口令和图形验证码等交互式认证流程；
- 加密保存并安全恢复登录 session，减少重复认证；
- 一键连接与断开，并提供基础连接状态和错误反馈；
- Wi-Fi、蜂窝和以太网等底层网络变化后的 VPN 自动恢复；
- 用户手动添加的 Android Quick Settings VPN 磁贴；
- 可选的 Android Always-on VPN；
- 应用内脱敏 Diagnostics，可复制适合公开问题报告的诊断信息。

## 下载与快速开始

1. 从 [GitHub Releases](https://github.com/Cedar17/zju-connect-android/releases) 下载并安装最新 `zju-connect-v*-arm64-v8a.apk`。
2. 如果 Android 提示不允许安装未知来源应用，请按系统提示临时允许当前浏览器或文件管理器安装该 APK。
3. 打开 **ZJU Connect**，点击连接。
4. 首次连接时允许 Android 创建 VPN 连接。
5. 按服务端要求完成账号密码、短信、动态口令或图形验证码认证。
6. 连接成功后即可访问校内资源。
7. 后续服务端 session 仍有效时，通常可以直接恢复认证并一键连接。

APK 由本仓库的 GitHub Actions 自动构建、测试并使用项目 Release 密钥签名；源代码和构建流程均公开。应用仅申请 VPN 运行所需的网络、前台服务和通知等权限。

当前已在 **OnePlus Ace 3V / Android 15** 上完成安装与基本连接验证。项目兼容范围仍以 **Android 10+ / arm64-v8a** 为准。

如果服务端要求重新认证，应用会回到对应认证流程，不会绕过验证码或其他安全要求。

## Quick Settings 与 Always-on

### Quick Settings

可以在 Android 快捷设置编辑面板中手动添加 **ZJU Connect** 磁贴。已有可复用认证结果或有效加密 session 时，短按即可连接或断开；缺少 VPN 授权或需要前台认证时，应用会打开现有登录界面继续处理。

后台路径只尝试恢复已有 session，不会读取或自动提交保存密码，也不会自动处理认证挑战。

### Always-on VPN

可以在 Android 系统 VPN 设置中手动为 ZJU Connect 开启 Always-on。Android 会在开机或服务进程重启时启动 VPN 服务；服务只恢复已有加密 session，需要重新认证时会提示用户回到应用完成。

应用不会默认开启 Always-on 或 Lockdown。

## 网络范围与已知限制

应用主要用于通过校外 Wi-Fi 或蜂窝网络访问浙江大学校内资源，例如 `cc98.org` 等校内网站和私有网段内的校内服务器地址。

Active VPN 会监测非 VPN 的 Wi-Fi、蜂窝和以太网变化。底层网络发生变化后，应用会关闭旧 TUN / aTrust 会话并尝试基于当前认证状态重新建立 VPN；暂时没有可用网络时会等待网络恢复。

该过程允许短暂中断，不承诺无缝漫游，也不包含无限自动重试或复杂选路策略。

不同 Android 厂商对后台限制、电池策略和“活动应用”停止行为的处理不同。应用遵循 `VpnService` 与前台服务生命周期，但无法绕过系统强制停止、极端进程清理或厂商专属后台策略。

## 问题反馈

如果遇到连接、认证或网络恢复问题：

1. 在应用中打开 **Diagnostics** 并复制脱敏诊断信息；
2. 在 [GitHub Issues](https://github.com/Cedar17/zju-connect-android/issues) 中附上诊断信息，并补充网络环境和复现步骤。

诊断报告已包含应用版本、设备型号和 Android 版本 / API，无需重复填写。请不要额外公开账号、密码、短信验证码、图形验证码或其他敏感凭据。

## 技术架构

主要技术：

- Kotlin
- Jetpack Compose
- Material 3
- Android `VpnService`
- Go / gomobile
- [zju-connect](https://github.com/Mythologyli/zju-connect)

整体结构：

```text
Compose UI
    ↓
Connection state / application logic
    ↓
Android VpnService
    ↓
Kotlin ↔ Go bridge
    ↓
zju-connect
    ↓
ZJU aTrust
```

Android 负责用户交互、VPN 生命周期、安全存储和平台边界；Go 核心负责 aTrust 协议、认证状态、资源解析和 VPN 数据面。

Kotlin–Go 接口保持尽可能小且结构化，避免在 Android UI 层重新实现协议逻辑。

## 安全与工程原则

项目在减少重复认证的同时，不绕过服务端安全要求。

- 不默认永久保存用户密码；
- 不绕过短信、图形验证码等服务端认证要求；
- 不绕过 TLS 证书和主机名验证；
- 会话恢复前由服务端重新验证其有效性；
- 本地认证状态使用 Android Keystore 加密并排除系统云备份；
- 不在正式日志中记录密码、Cookie、SID、验证码和设备标识等敏感信息；
- 会话明确失效后回到正常认证流程，而不是无限重试；
- 用户可以通过 Android 系统设置清除应用数据和本地认证状态；
- 固定 Go、gomobile、Android SDK / NDK 和上游核心版本，保持构建可追踪和可复现；
- 优先复用 `zju-connect`，不在 Android 层重复实现 aTrust 协议；
- 不因为临时问题提前引入复杂架构。

## 开发文档

- [Architecture](docs/architecture.md) — Android 侧整体架构与主要边界；
- [Authentication Recovery](docs/authentication-recovery.md) — 认证复用、持久化 session 与恢复状态；
- [gomobile bridge](docs/gomobile-bridge.md) — Kotlin 与 Go core 之间的接口边界。

## 上游与参考

- [Mythologyli/zju-connect](https://github.com/Mythologyli/zju-connect) — aTrust 协议与网络核心；
- [Mythologyli/ZJU-Connect-for-Windows](https://github.com/Mythologyli/ZJU-Connect-for-Windows) — 桌面端产品体验参考；
- [Mythologyli/ZJUConnectForAndroid](https://github.com/Mythologyli/ZJUConnectForAndroid) — 历史 Android `VpnService` / gomobile 实现参考。

本项目采用 Kotlin、Jetpack Compose 和当前 aTrust 链路，不直接继承历史 Android 项目的旧 UI、认证接口或凭据存储方式。

## 许可证

本项目采用 [GNU Affero General Public License v3.0](LICENSE)（AGPL-3.0）发布，并遵循 [Mythologyli/zju-connect](https://github.com/Mythologyli/zju-connect) 的 AGPL-3.0 许可证及相关版权声明。

## 免责声明

本项目是个人维护的非官方开源项目，不是浙江大学、深信服或其关联机构的官方产品。使用者应遵守浙江大学网络、信息系统和电子资源的相关规定。aTrust 属于外部服务，其协议、认证流程和安全策略可能发生变化。本项目按现状提供，不保证服务端变化后的持续兼容性。
