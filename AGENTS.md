# AGENTS.md

## 项目目标

本项目是面向浙江大学新版 RVPN/aTrust 的非官方开源 Android 客户端。当前阶段优先验证完整连接链路，不追求功能数量。

## 技术基线

- Android 使用 Kotlin、Jetpack Compose 和 Material 3。
- 网络与协议核心使用 `Mythologyli/zju-connect`。
- Android 侧通过 `VpnService` 建立 TUN，并将文件描述符交给 Go 核心。
- Kotlin 与 Go 之间使用结构化接口和事件，不解析 CLI stdout，不依赖临时文件传递验证码或认证状态。
- 固定 Go、`x/mobile`、Android SDK、NDK 与上游核心 commit，保证构建可追溯。

## 安全边界

- 不绕过服务端要求的短信、图形验证码或其他认证。
- 默认不持久化密码；不得以明文保存密码、Cookie、SID、设备标识或 Session Snapshot。
- 持久化认证状态必须使用 Android Keystore 加密，并存放在不参与系统备份的位置。
- 不允许跳过 TLS 证书与主机名校验。
- 日志必须脱敏，不输出密码、Cookie、SID、SignKey、完整认证响应或验证码。
- 隧道相关底层 socket 必须绕过本应用 VPN，避免路由回环。
- 断开后必须释放 TUN、socket、goroutine 和前台服务资源。

## 范围控制

首个可用版本只支持浙江大学新版 aTrust。暂不实现旧版 EasyConnect、WebVPN、多账号、端口转发、本地代理、Always-on VPN、开机自连和复杂高级配置。

每个 PR 聚焦一个可审查目标。不要在初始化工程时同时实现完整认证、VPN、会话恢复和正式 UI。

## 开发原则

- 优先修复明确的正确性、安全性与生命周期风险。
- 默认不为简单 UI、包装代码、CLI 输出或一次性验证脚本扩展测试。
- 只有存在明确静默错误风险时，增加最小、针对性的测试。
- 不复制历史 Android 项目的 Java/XML 架构或明文凭据存储实现。
- 不提交来源不明或无法复现的预编译 AAR。
- 不在无关改动中重构上游核心。

## 当前验证顺序

1. 初始化可构建的 Android 工程。
2. 从固定上游 commit 生成可复现的 gomobile AAR。
3. 建立 `VpnService`、TUN 与 socket protection 链路。
4. 通过结构化回调完成密码、短信和图形验证码认证。
5. 导出并加密保存 Session Snapshot。
6. 验证进程重启、网络切换和会话失效后的恢复与回退。
