# Android 客户端架构与技术验证

## 目标

当前阶段的目标不是尽快完成完整 App，而是验证以下链路在 Android 上能够安全、稳定且可恢复地工作：

```text
zju-connect
→ gomobile AAR
→ Android VpnService / TUN
→ aTrust 认证
→ 加密 Session Snapshot
→ 进程重启后恢复连接
```

只有该链路成立后，才扩展正式 UI、网络切换策略和更多产品功能。

## 总体分层

```text
Compose UI
    ↓
ConnectionViewModel / StateFlow
    ↓
VpnRepository
    ├── VpnServiceController
    ├── SecureSessionStore
    ├── SettingsStore
    └── GoCoreBridge
            ↓
       zju-connect
```

- UI 只渲染连接状态并提交用户输入，不直接控制 TUN 或 Go 对象。
- ViewModel 维护单一连接状态机。
- Repository 协调认证、会话、VPN 权限和服务生命周期。
- `VpnService` 负责 Android TUN、前台服务和底层 socket protection。
- Go 核心负责 aTrust 协议、资源解析、路由和数据面。

初期保持单一 `app` 模块即可。只有 Go binding 构建明显拖累工程时，再拆分 `core-go` 或 `core-api`，避免过早模块化。

## 连接状态机

建议使用封闭状态模型：

```text
Idle
PreparingVpnPermission
Authenticating
AwaitingPassword
AwaitingSms
AwaitingCaptcha
FetchingResources
EstablishingTunnel
Connected
Reconnecting
Disconnecting
Error
```

每次状态迁移必须由明确事件触发。不得通过解析日志文本判断认证阶段。

## Kotlin 与 Go 边界

`gomobile` 绑定只暴露简单类型、`String`、`ByteArray` 和回调接口。复杂对象在边界处编码为版本化 JSON；数据面仍直接使用文件描述符，不把流量序列化为 JSON。

建议最小接口：

```text
start(configJson, listener)
submitAuth(responseJson)
attachTun(fd)
stop()
exportSession()
importSession(snapshotJson)
```

建议回调事件：

```text
stateChanged
passwordRequired
smsRequired
captchaRequired
sessionUpdated
log
fatalError
```

图形验证码直接传递图片字节和交互元数据，不写入共享目录或临时文件。日志事件在 Go 侧产生前完成脱敏。

## Session Snapshot

仅保存恢复连接所需的最小状态，采用版本化结构，例如：

```text
schemaVersion
server
username
authData
sid
resourceData
createdAt
coreVersion
```

是否持久化 `SignKey` 由实际恢复实验决定；若可稳定重新生成，则不保存。

安全要求：

- 使用 Android Keystore 中不可导出的 AES-GCM 密钥加密。
- 密文存放于 `noBackupFilesDir` 或等效不参与备份的位置。
- 默认不保存密码。
- 会话无法恢复时清晰回退到重新认证，而不是反复静默重试。
- 提供清除本地会话和取消设备授信的入口。

## VpnService 与 socket protection

Android 建立 TUN 后，Go 核心用于连接 VPN 服务端的底层 socket 不能再次进入该 TUN，否则会形成路由回环。

因此 Go 网络层必须允许 Android 在 socket 建立后、连接前调用 `VpnService.protect(fd)`，或由 Android 创建并保护 socket 后交给 Go 使用。仅依赖桌面端的网卡自动选择不足以满足 Android。

验证项：

- Wi-Fi 与移动网络下均能建立连接。
- 切换网络后不会出现流量回环或僵死 socket。
- 断开后 TUN fd、底层连接、goroutine 和前台通知全部释放。
- Android 进程被系统回收后能够进入一致的可恢复状态。

## TLS 与日志

公开发布前必须满足：

- 使用系统信任链并校验证书主机名。
- 不提供默认或隐藏的忽略证书错误路径。
- 密码、Cookie、SID、DeviceID、SignKey、验证码及完整认证响应不得进入 release 日志。
- Debug 日志也应默认脱敏，敏感诊断只能由开发者显式、临时启用。

## 上游集成

初期通过构建脚本从 `zju-connect` 的固定 commit 生成 AAR：

- 固定上游 commit SHA。
- 固定 Go、`golang.org/x/mobile`、NDK 与 Android SDK 版本。
- 不使用 `@latest`。
- 不提交无法追溯来源的二进制 AAR。
- Android 专用的结构化认证与 socket protection 接口优先设计为可贡献回上游的通用改动。

## 技术验证顺序

### 阶段 1：Android 工程基线

- Kotlin、Compose、Material 3 工程可构建。
- 声明最小权限和空 `VpnService`。
- CI 能生成 debug APK。

### 阶段 2：Go binding

- 固定工具链构建 AAR。
- Kotlin 能调用最小 Go API 并接收结构化回调。
- 构建过程可在干净环境复现。

### 阶段 3：TUN 数据面

- 完成 VPN 权限申请与 TUN 建立。
- TUN fd 可交给 Go。
- 底层 socket 已正确 protect。
- 断开与异常退出无资源残留。

### 阶段 4：认证控制面

- 支持密码、短信和图形点击验证码。
- 不依赖 stdout 或临时文件。
- 所有错误映射为稳定的结构化错误类型。

### 阶段 5：会话恢复

- 完整认证后导出 Session Snapshot。
- Keystore 加密存储。
- 杀死进程后可在会话有效时免密码恢复。
- 会话过期时可靠回退到重新认证。

## Release blockers

存在以下任一情况时不得公开发布：

- TLS 证书或主机名校验被跳过。
- 密码、Cookie、SID、SignKey 或设备标识可能进入日志或明文文件。
- Session Snapshot 未加密或会进入系统云备份。
- Go 隧道 socket 未经过 Android VPN bypass/protection。
- 断开连接后仍存在 TUN、socket、goroutine 或前台服务残留。
- AAR 无法由固定版本的源码和工具链复现。
