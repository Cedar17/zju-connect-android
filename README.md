# zju-connect-android

An open-source Android client for ZJU aTrust remote access, powered by [zju-connect](https://github.com/Mythologyli/zju-connect).

面向浙江大学新版 RVPN / aTrust 的非官方开源 Android 客户端。

## 项目定位

浙江大学现有 Android 校外访问方案依赖深信服 aTrust。实际使用中可能需要重复完成账号密码、短信或图形验证码认证，同时闭源客户端的权限、本地数据和后台行为难以独立审计。

`zju-connect-android` 希望提供一个更轻量、透明的开源替代方案。

项目以 [zju-connect](https://github.com/Mythologyli/zju-connect) 作为 aTrust 协议与网络核心，通过 Android `VpnService` 建立系统级校网访问隧道。

目标很简单：

> 在服务端会话仍然有效时，尽可能做到一键连接、一键断开；需要重新认证时，再清晰地完成服务端要求的认证流程。

项目专注于浙江大学 aTrust Android 接入，而不是通用 VPN、代理或网络工具箱。

## 目标体验

首个可用版本希望把日常流程收束为：

```text
打开应用
   ↓
点击连接
   ↓
恢复已有会话
   ├─ 有效 → 建立 VPN
   └─ 失效 → 完成必要认证
                    ↓
                建立 VPN
                    ↓
               访问校内资源
                    ↓
                 一键断开
```

产品侧重点：

* 明确展示未连接、连接中、需要认证、已连接和失败状态；
* 会话有效时尽可能避免重复认证；
* 服务端要求重新认证时自然进入对应流程；
* 提供明确的一键连接与断开入口；
* 将底层网络错误转换为用户可以理解和处理的反馈；
* 尽量减少权限、后台行为和本地持久化状态。

Issue #14 的一键连接日常体验已在 K40 和 OnePlus Ace 3V 蜂窝网络上完成验证；当前 Draft PR #15 保持 Draft，等待最终人工复核后再合并。

## 技术架构

主要技术：

* Kotlin
* Jetpack Compose
* Material 3
* Android `VpnService`
* Go / gomobile
* [zju-connect](https://github.com/Mythologyli/zju-connect)

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

Android 负责用户交互、VPN 生命周期、安全存储和平台边界。

Go 核心负责 aTrust 协议、认证状态、资源解析和 VPN 数据面。

Kotlin–Go 接口保持尽可能小且结构化，避免在 Android UI 层重新实现协议逻辑。

详细设计见：

* [Architecture](docs/architecture.md)
* [gomobile bridge](docs/gomobile-bridge.md)

## 安全与工程原则

项目在减少重复认证的同时，不绕过服务端安全要求。

主要原则：

* 不默认永久保存用户密码；
* 不绕过短信、图形验证码等服务端认证要求；
* 不绕过 TLS 证书和主机名验证；
* 会话恢复前由服务端重新验证其有效性；
* 本地认证状态使用 Android Keystore 加密并排除系统云备份；
* 不在正式日志中记录密码、Cookie、SID、验证码和设备标识等敏感信息；
* 会话明确失效后回到正常认证流程，而不是无限重试；
* 用户可以通过 Android 系统设置清除应用数据和本地认证状态；
* 固定 Go、gomobile、Android SDK / NDK 和上游核心版本，保持构建可追踪和可复现；
* 优先复用 `zju-connect`，不在 Android 层重复实现 aTrust 协议；
* 不因为临时问题提前引入复杂架构。

## 网络范围与已知限制

应用使用 Android `VpnService`，主要用于通过校外 Wi-Fi 或蜂窝网络访问浙江大学校内资源，例如：

* `cc98.org` 等校内网站；
* 私有网段内的校内服务器地址。

K40 上的一键连接、真实数据面、会话恢复和断开清理已经验证。OnePlus
Ace 3V 蜂窝网络人工验收也已于 2026-08-10 通过。

目前不承诺 active VPN 在 Wi-Fi 与蜂窝网络切换时无缝迁移。底层网络发生变化后，必要时可通过重新连接恢复。

自动重连和复杂网络切换策略属于后续可靠性增强，不是当前产品成立的前提。

## 功能范围

首个可用版本包括：

* 浙江大学新版 aTrust / RVPN；
* 密码、短信和图形验证码认证；
* 安全会话恢复；
* Android 系统 VPN；
* 校内资源路由；
* 一键连接与断开；
* 仅在未连接且已有记住账号时提供的切换账号入口（不做多账号管理）；
* 基础连接状态和错误反馈；
* 应用内白名单脱敏诊断 Activity，可复制适合公开 Issue 的报告；
* Android 前台 VPN 生命周期管理。

## Roadmap

### 1. 技术可行性验证 — 已完成

完成 Android、gomobile、aTrust 认证、会话恢复、`VpnService`、真实数据面和真实校内资源访问的端到端验证。

### 2. 首个可用产品 — Issue #14 验收完成，等待合并

连接状态、认证流程、一键连接 / 断开、账号切换、错误反馈、前台服务和诊断体验已收束，适合日常使用。

Issue #14 的代码与 K40 / OnePlus Ace 3V 人工验收已完成；Draft PR #15 仍需项目维护者最终复核与合并。

### 3. 可靠性增强

根据真实使用反馈决定是否增加网络切换恢复、自动重连等能力。

### 4. 首个可分发版本

产品体验和生命周期稳定后，准备首个 Release，并完成许可证、版本信息、可复现构建和必要用户文档。

## 上游与参考

* [Mythologyli/zju-connect](https://github.com/Mythologyli/zju-connect) — aTrust 协议与网络核心
* [Mythologyli/ZJU-Connect-for-Windows](https://github.com/Mythologyli/ZJU-Connect-for-Windows) — 桌面端产品体验参考
* [Mythologyli/ZJUConnectForAndroid](https://github.com/Mythologyli/ZJUConnectForAndroid) — 历史 Android `VpnService` / gomobile 实现参考

本项目采用 Kotlin、Jetpack Compose 和当前 aTrust 链路，不直接继承历史 Android 项目的旧 UI、认证接口或凭据存储方式。

## 许可证

本项目依赖采用 AGPL-3.0 许可证的 `zju-connect`。在公开发布可分发版本前，将明确并遵守上游许可证对应的版权声明、开源和源代码提供义务。

## 免责声明

本项目是个人维护的非官方开源项目，不是浙江大学、深信服或其关联机构的官方产品。使用者应遵守浙江大学网络、信息系统和电子资源的相关规定。aTrust 属于外部服务，其协议、认证流程和安全策略可能发生变化。本项目按现状提供，不保证服务端变化后的持续兼容性。
