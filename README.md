# zju-connect-android

An open-source Android client for ZJU aTrust remote access, powered by zju-connect.

面向浙江大学新版 RVPN/aTrust 的开源 Android 客户端。

## 项目定位

浙江大学现有 Android 校外访问方案依赖深信服 aTrust。用户连接时通常需要重复输入密码并完成短信或图形验证，使用流程较为繁琐；同时，闭源客户端的权限使用、数据存储和后台行为难以独立审计。

本项目以 [zju-connect](https://github.com/Mythologyli/zju-connect) 为协议与网络核心，使用 Android `VpnService` 建立校网访问隧道。首次完成完整认证后，应用将通过 Android Keystore 加密保存必要的会话和设备状态，在认证状态仍有效时实现尽可能接近桌面版的一键连接。

当会话失效或学校安全策略重新触发短信、图形验证码等认证时，应用将提供原生、清晰的交互流程，而不会绕过服务端要求的身份验证。

界面采用 Kotlin、Jetpack Compose 和 Material 3，重点关注简洁易用、最小权限、安全存储、日志脱敏、可审计性和可复现构建。

## 核心目标

* 支持浙江大学新版 RVPN/aTrust。
* 使用 Android `VpnService` 提供系统级校网访问。
* 在会话有效时实现免重复输入的一键连接。
* 原生支持短信验证和图形点击验证码。
* 使用 Android Keystore 保护认证与设备状态。
* 默认不以明文形式保存密码、Cookie 或其他敏感信息。
* 提供现代 Material 3 深色与浅色界面。
* 保持权限、后台服务和本地文件的最小化与透明化。
* 对日志中的密码、Cookie、SID、设备标识等敏感数据进行脱敏。
* 固定并记录 Go、Android SDK、NDK 和上游核心版本，支持可复现构建。

## 初期范围

首个可用版本仅面向浙江大学新版 aTrust，优先完成：

1. 上网账号登录；
2. 短信和图形验证码；
3. 安全会话恢复；
4. Android TUN 隧道；
5. 校网资源路由与 DNS；
6. 网络切换与断线重连；
7. 前台通知和一键断开。

旧版 EasyConnect、WebVPN、多账号、端口转发、本地代理、Always-on VPN 和复杂高级配置暂不属于初期范围。

## 安全原则

* 不绕过浙江大学或 aTrust 服务端要求的认证流程。
* 不承诺永久免验证，一键连接取决于服务端会话和设备状态是否仍然有效。
* 不默认永久保存用户密码。
* 不忽略 TLS 证书错误。
* 不将认证状态纳入系统云备份。
* 不在正式日志中记录完整凭据、认证响应或验证码。
* 提供清除本地会话、取消设备授信和彻底注销功能。

## 上游与参考项目

* 核心实现：[Mythologyli/zju-connect](https://github.com/Mythologyli/zju-connect)
* 桌面端交互参考：[Mythologyli/ZJU-Connect-for-Windows](https://github.com/Mythologyli/ZJU-Connect-for-Windows)
* 历史 Android 原型：[Mythologyli/ZJUConnectForAndroid](https://github.com/Mythologyli/ZJUConnectForAndroid)

历史 Android 项目仅作为 `VpnService`、TUN 文件描述符和 Go mobile binding 的技术参考。本项目将采用新的 Kotlin、Compose 和分层架构，不直接继承其 Java/XML、明文凭据存储和旧认证接口。

## 项目状态

项目目前处于技术验证与早期开发阶段，尚不适合日常使用。

当前首要任务是验证以下完整链路：

```text
zju-connect
→ gomobile AAR
→ Android VpnService / TUN
→ aTrust 登录
→ 加密 Session Snapshot
→ 进程重启后免密码恢复连接
```

## 免责声明

本项目不是浙江大学或深信服官方产品，与浙江大学、深信服及其关联机构无隶属或授权关系。

使用者应遵守浙江大学网络、信息系统和电子资源的相关规定。项目按现状提供，不保证服务端协议更新后仍能持续工作。

本项目依赖采用 AGPL-3.0 许可的 `zju-connect`。公开发布前将确定并完整遵守相应的开源许可和源代码提供义务。
