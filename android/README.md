# RustDesk 安卓客户端（纯 Java 实现）

仓库按职责分成两棵互不依赖的目录树：`android/`（本目录，安卓端）与 `host/`（被控端，保留上游 Rust 源码；
不含 Flutter/Dart 与 vcpkg，只有无头终端）。

`android/` 把安卓端从 **Flutter + Dart + Rust `librustdesk.so` + vcpkg 原生依赖** 重构为**单一 Android Gradle (Java) 工程**。

- 不再有 Flutter/Dart 界面，不再有 Rust 动态库，不再有 NDK 与 vcpkg
- 全部依赖来自 Maven：`protobuf-javalite`（协议）、`okhttp`（WebSocket 隧道）、`BouncyCastle`（Ed25519）
- 采集/编码/解码/注入使用系统 API：`MediaCodec`、`MediaProjection`、`AccessibilityService`

| 指标 | 旧安卓方案（Flutter+Rust+vcpkg） | `android/` 本工程 |
|---|---|---|
| 构建耗时 | 50–70 分钟（首次更久） | **约 12–15 秒** |
| 产物大小 | — | 约 5.4 MB |
| 原生工具链 | NDK + Rust + vcpkg | **无** |

## 能力现状

证据等级：**强** = 有真实服务器/真机运行产物；**中** = 有自动化断言；**弱** = 仅代码完成，未实机验证。

### 主控端（手机控制电脑）

| 能力 | 状态 | 证据 |
|---|---|---|
| 通过 ID 连接（hbbs 打洞） | 完成 | 强，真实公网服务器 |
| 直连 `IP[:端口]`（跳过服务器） | 完成 | 强，真机验证 |
| 通过隧道域名连接（Cloudflare） | 完成 | 中，`wss://` 握手通过；登录用户名按 `域名:端口` 发送（`Session.tunnelUserFromUrl`），端到端登录还没有可复跑产物 |
| 登录（两段 SHA-256 摘要） | 完成 | 强，真实主机 `login ok` |
| 视频接收（VP9/H.264/H.265/VP8/AV1） | 完成 | 强，真机解码 300+ 帧 |
| 等比缩放显示（不拉伸） | 完成 | 强，真机截图 |
| 横屏模式 | 完成 | 强，`2412×1080` |
| 左侧上下分区左/右键（可长按锁定） | 完成 | 强，真机日志 |
| 鼠标移动 / 滚轮 / 文本输入 | 完成 | 中，真实主机接受 |
| 单指平移画布、双指缩放、长按拖选 | 完成 | 中，29 项数学自检 |
| 三种输入模式（普通/快捷键/虚拟键盘） | 完成 | 强，真机截图 |
| 虚拟键盘多指共按 | 完成 | 强，自绘 + 多点触控 |
| 剪贴板双向 | 完成 | 强，真机 `clipboard up/down` |
| 文件传输 / 终端 | 协议完成；文件无界面，终端有页面无入口 | 中，75/73 项协议断言；`ui/TerminalActivity` 已存在但全仓没有 `startActivity` 指向它 |

### 被控端（手机被电脑控制）

| 能力 | 状态 | 证据 |
|---|---|---|
| hbbs 注册（`RegisterPk` + 每 10s 续期） | 完成 | 强，真实服务器 |
| 打洞应答（`PunchHole` → `PunchHoleSent`） | 完成 | 强 |
| 直连监听（可配置端口） | 完成 | 强 |
| `SignedId` 握手 + 密码挑战 | 完成 | 强，本仓库主控端互通 |
| 登录后推流（协议层） | 完成 | 中，`HostSessionTest` 断言 viewer 收到 3 条 `video_frame(h264s)`；真实采集帧还没接进来（见下） |
| 屏幕采集 + H.264 编码 + EGL 上传 | 代码完成 | 弱，`ScreenCaptureService` 拿到授权后确实在跑，但 `setFrameListener` 无调用者、`HostAgent.onCapturedFrame` 也没人喂 → 采集帧到不了编码器，未真机 |
| 键盘注入（无障碍） | 部分 | 弱，已接线（`MainActivity` 把 `InputInjectorSink` 装进 `HostAgent`）；仍只有 4 个全局动作 + `ACTION_SET_TEXT`，普通键码不可用 |
| 剪贴板双向 | 协议完成，未接线 | 弱，`HostSession` 侧收发齐了，但没有任何 Android 类实现 `HostClipboardSink`，`setClipboardSink` 无调用者 |

### 尚未实现

音频、多显示器切换、2FA、文件传输界面、被控端鼠标/触摸注入（`HostSession` 只分发 `key_event` 与剪贴板）、
zstd 压缩剪贴板、防熄屏常亮。

## 快速开始

### 构建

```powershell
cd android
gradle assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

或在 Android Studio 里直接打开 `android/` 目录构建。

### 安装到手机

```powershell
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/x.apk
adb shell pm install -r /data/local/tmp/x.apk
```

> 必须推到 `/data/local/tmp/`，`/sdcard` 是 FUSE 挂载，装不上。

### 连接电脑

ID 输入框**一个框三种用法**：

| 输入 | 含义 | 是否需要公网 |
|---|---|---|
| `1182258341` | 走服务器打洞 | 需要 |
| `10.52.190.199:21118` | 局域网直连 | **不需要** |
| `rd.example.com` | 隧道域名（自动补 `wss://`） | 需要 |

被控端需要开启直连监听，编辑 `data/config/RustDesk2.toml`：

```toml
[options]
direct-server = 'Y'
direct-access-port = '21118'
```

## 界面说明

```
┌───────────────────────────────────────────────────────┐
│ 顶部弹出按键面板（点模式按钮才出现，可拖动，带关闭按钮）    │
├──┬────────────────────────────────────────────────────┤
│L │                                                    │
│  │            远端画面（等比居中，不拉伸）               │
│R │                                    右上竖排：⟳ ⌨ 模式 │
├──┴────────────────────────────────────────────────────┤
```

- **左侧竖条**：整条分上下两半，上半=左键（长按锁定可拖拽窗口、拉选文本），下半=右键。竖条内触摸不会穿透到远端。
- **右上三个按钮**（竖排）：`⟳` 重连、`⌨` 软键盘、模式切换。
- **三种输入模式**：
  - `TEXT` 普通输入 —— 软键盘内容整体发送，**唯一能正确输入中文的模式**
  - `SHORTCUT` 快捷键 —— `Ctrl+C/V/X/Z/A/S`、`Alt+Tab`、`Ctrl+Shift+Esc` 等组合键，顶部弹出面板
  - `KEYS` 虚拟键盘 —— 全键自绘键盘（F1–F12、数字、字母、修饰键、方向键），**支持多指共按**，可按住 Shift 再点字母

  按钮标签是 `TEXT`/`SHORTCUT`/`KEYS`，`SharedPreferences` 里存的内部名是 `Normal`/`Shortcut`/`Virtual`；
  键事件按 `KeyboardMode.Translate`（普通输入）与 `KeyboardMode.Legacy`（快捷键/虚拟键）发给对端。

## 目录结构

```
android/                        安卓端（Android Gradle 工程根）
├── app/src/main/java/
│   ├── hbb/                    protobuf 生成代码（提交入库，无需现场生成）
│   └── com/rustdesk/java/
│       ├── crypto/             加密原语：X25519、XSalsa20-Poly1305、BLAKE2b、Ed25519
│       ├── net/                帧编解码、传输、地址混淆、打洞、中继、WebSocket 隧道
│       ├── session/            会话、握手、身份验签、各类消息编码
│       ├── codec/              MediaCodec 视频解码
│       ├── ui/                 主界面、远程界面、手势、按键面板、终端页、输入注入
│       └── host/               被控端：采集、编码、注册、监听、会话、无障碍注入
├── docs/PROTOCOL.md            协议细节与实现现状（每行带代码出处）
└── tools/                      构建、自测、真机验证脚本
```

仓库根另有 `host/`（Rust 无头被控端：`src/`、`libs/`、`scripts/`，无 Flutter/Dart、无 vcpkg）与 `.github/workflows/`（CI 在仓库根，
`android/` 内没有自己的 workflow 目录）；安卓 APK 由根级 `build-release.yml` 的 `android` job 构建，与 host 的 `linux-x64` / `linux-arm64` / `windows-x64` 矩阵 job 互不依赖。

## 测试

```powershell
# 离线自测：10 个套件 / 347 项断言
tools\selftest\run-selftest.ps1

# 含联网套件：再加 HostEndToEnd（自带公网被控端）与 RelayTest（需 -PeerId，否则跳过）
tools\selftest\run-selftest.ps1 -IncludeNetwork

# 一键：自测 + 构建
tools\check-all.ps1

# 真机验证（需手机连接）
tools\verify-device.ps1 -Id 1182258341 -Password 123456
```

隧道模式另有一条专门的验证脚本，会断言 `LoginRequest.username` 确实以「域名:端口」发出：

```powershell
tools\verify-tunnel.ps1 -Id rd.example.com -Password yyy
```

它等待设备（默认 300 秒，可先启动再插手机）、安装 debug APK、用隧道域名启动 viewer，然后断言四条：`login username=<host>:端口`、`status: login ok`、`first frame received`、无 `FATAL EXCEPTION`。无设备时以 exit 2 退出。

`HostEndToEnd` 会用真实公网服务器把**本仓库的被控端**注册上去，再用**本仓库的主控端**打洞、握手、登录，是唯一能证明两端链路完整的测试。

## 关键协议事实

完整说明见 [docs/PROTOCOL.md](docs/PROTOCOL.md)，这里只列最容易踩的几条：

- **帧格式**：`value = (len << 2) | (headerBytes - 1)`，小端
- **加密**：secretbox = XSalsa20-Poly1305，**tag 在密文之前**，密钥流从第 32 字节开始
- **两级身份**：`PunchHoleResponse.pk` 里是**服务器签名的** IdPk，其中 `pk` 是对端的 **ed25519 签名公钥**；对端自己发的 `SignedId` 里 `pk` 是它的 **curve25519 加密公钥**
- **登录**：`LoginRequest.username` 必须是**自己的 ID**；密码是 `sha256(sha256(密码‖salt) ‖ challenge)` 两段摘要
- **`RegisterPeer` 在 hbbs 的 TCP 路径上不存在**（无 `match` 分支），被控端必须用 `RegisterPk` 在持久连接上注册，主控端的 `PunchHole` 会推到同一条流上
- **地址混淆**：`AddrMangle` 编码后通常只有 **11–12 字节**，解码端口必须截断到 u16，否则大部分真实地址解不出来
- **直连时先发的是 `Hash` 不是 `SignedId`**：握手必须扫描前导消息，不能只读第一条

## 已知限制

1. **被控端视频链路差最后一根线**：`ScreenCaptureService` 已在授权后运行，但没人调用它的 `setFrameListener`
   把帧交给 `HostAgent.onCapturedFrame`，采集帧到不了编码器。
2. **被控端键盘注入受限**：已通过 `HostInputSink` / `InputInjectorSink` 接线，但无障碍服务只能做
   Back/Home/Recents/通知四类全局动作，外加往聚焦节点塞文本；普通字母数字键码无法注入。
3. **被控端剪贴板与鼠标未接线**：`HostClipboardSink` 没有 Android 实现、`setClipboardSink` 无调用者；
   `HostSession` 主循环没有鼠标 `case`，鼠标消息落到 `default`。
4. **长文本剪贴板**：对端会用 zstd 压缩，本工程未实现解压，主控端与被控端都跳过并记日志。
5. **屏幕会熄灭**：尚未加 `FLAG_KEEP_SCREEN_ON`。
6. `KEYS` 模式是「功能键+数字+字母+修饰键」，不是完整 104/108 键位图（无小键盘、无符号区）。

## 授权说明

本工程为个人重构实践，协议实现参考 RustDesk 官方仓库。使用前请遵守 RustDesk 的授权条款。
