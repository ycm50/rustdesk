# RustDesk 重构版：纯 Java 安卓客户端 + Rust 无头被控端

一句话定位：把 RustDesk 拆成**两个互不依赖的目录** —— `android/` 是纯 Java 的安卓客户端（主控端 + 被控端，**无 Flutter、无 Dart、无 Rust `.so`、无 NDK、无 vcpkg**）；`host/` 是**被控端**，保留上游 Rust 源码，改造后不再使用 vcpkg 与 Flutter/Dart，单语言、无 UI，只提供**无头终端**（使用说明见 [`host/TERMINAL.md`](host/TERMINAL.md)）。安卓端不再需要原生工具链，构建时间从 **50–70 分钟降到约 12–15 秒**（本机实测值，记录在 [`android/README.md`](android/README.md)）。

## 为什么做这个重构

安卓端：

| 维度 | 旧方案（Flutter + Dart + Rust + vcpkg） | 本仓库 `android/` |
|---|---|---|
| 界面 | Flutter / Dart | 原生 Android View + Java |
| 协议与加解密 | Rust `librustdesk.so` + flutter_rust_bridge | 纯 Java（protobuf + BouncyCastle） |
| 原生依赖 | vcpkg 编 libvpx / libyuv / opus / aom / ffmpeg | **无**，全部走 Maven 与系统 API |
| 工具链 | NDK + Rust + CMake + vcpkg | JDK 17 + Android SDK |
| 构建耗时 | 50–70 分钟（首次更久） | **约 12–15 秒** |
| 采集 / 编码 / 解码 / 注入 | C++ / Rust 实现 | `MediaProjection` / `MediaCodec` / `AccessibilityService` |

重构的动机很直接：安卓端大部分的复杂度来自「把桌面端的 Rust 栈塞进手机」。改成纯 Java 后，改一行协议代码不需要等 cargo + NDK + vcpkg 走一遍，音量、剪贴板、手势这类小改动的迭代成本从「一小时」变成「十几秒」。

被控端（`host/`）的动机相同：它本来就是一台机器上的 Rust 服务，不需要随包携带 Flutter/Dart UI，也不该为了四个编解码库去引导一整套 vcpkg。

| 维度 | 旧构建方式 | 本仓库 `host/` |
|---|---|---|
| UI | Flutter + Dart（另有 Sciter 旧界面） | **无 UI**，只有无头终端（`--headless` 等） |
| 语言 | Rust + Dart / C++ | **Rust**（单语言） |
| 原生依赖 | vcpkg 编 libvpx / libaom / libyuv / opus | 系统 pkg-config：Linux 发行版包 / Windows MSYS2 UCRT64 |
| FFmpeg | 本地编译或 vcpkg | **仅 hwcodec 需要**，用开源预构建件按平台/架构下载 |
| 构建入口 | 平台脚本 + vcpkg 引导 | `cargo build --release --features system-libs` |

`host/` 之所以单独成目录而不是留在根目录：它的构建链（Cargo + 系统库）和安卓端完全不重叠，两边各自独立构建；CI 用互不依赖的 job 家族，一个挂了不拖累另一个。安卓端不调用 `host/` 里的任何东西，`host/` 也不知道 `android/` 存在。

> 耗时与产物大小（约 12–15 秒、约 5.4 MB）沿用 `android/README.md` 里记录的本机实测值；本次改写 README 未重跑 Gradle（按项目约定，Gradle 构建由 Android Studio 执行）。

## 仓库结构（改造后）

```
rustdesk/
├── .github/workflows/build-release.yml   一个 workflow，两个互不依赖的 job 家族
├── .gitattributes / .gitignore / LICENCE
├── AGENTS.md / CLAUDE.md / GEMINI.md     协作说明（目录描述以本 README 为准）
├── README.md                             ← 本文件
│
├── android/                              【目录 A】纯 Java 安卓客户端
│   ├── README.md                         客户端自己的详细说明（界面、手势、输入模式）
│   ├── build.gradle / settings.gradle / gradle.properties
│   ├── gradle/wrapper/gradle-wrapper.properties   （只有 properties，没有 wrapper jar）
│   ├── app/
│   │   ├── build.gradle                  applicationId、SDK 版本、三个依赖
│   │   └── src/main/
│   │       ├── AndroidManifest.xml
│   │       ├── res/
│   │       └── java/
│   │           ├── hbb/                  protobuf 生成代码（已入库，无需现场生成）
│   │           └── com/rustdesk/java/
│   │               ├── MainActivity.java 入口：ID / IP / 隧道域名三合一输入框、被控端开关
│   │               ├── crypto/           加密原语（Ed25519、secretbox、BLAKE2b）
│   │               ├── net/              帧编解码、传输、地址混淆、打洞、中继、WSS 隧道
│   │               ├── session/          会话、握手、两级身份验签、各类消息编解码
│   │               ├── codec/            MediaCodec 视频解码
│   │               ├── ui/               远程界面、手势、按键面板、键位映射
│   │               └── host/             被控端：采集、编码、注册、监听、会话、无障碍注入
│   ├── docs/PROTOCOL.md                  协议事实与实现现状（每条带「文件:行号」出处）
│   └── tools/                            selftest/、build-apk.ps1、check-all.ps1、verify-device.ps1
│
├── host/                                 【目录 B】RustDesk 的 Rust 被控端
    ├── Cargo.toml / Cargo.lock / build.rs / build.py
    ├── src/                              应用代码（server/ platform/ terminal/ …）
    ├── libs/                             hbb_common / base / scrap / enigo / clipboard / magnum-opus …
    ├── scripts/fetch-ffmpeg.sh           按平台/架构下载 FFmpeg 预构建件（hwcodec 才需要）
    ├── scripts/fetch-ffmpeg.ps1          Windows 版本
    ├── res/ docs/ examples/ scripts/ fastlane/ flatpak/ appimage/
    ├── .cargo/config.toml
    └── Dockerfile / entrypoint.sh / TERMINAL.md
│
└── worker/                               【目录 C】可选的中继（Cloudflare Worker + 桥接）
    ├── README.md                         部署、用法、已知边界
    ├── wrangler.toml                     Durable Object 绑定、自定义域名、BRIDGE_KEY
    ├── src/index.js                      路由：/websocket（观看端）、/bridge（桥接）、/health
    ├── src/relay-session.js              Durable Object：按 key 配对两条 socket 并转发
    └── bridge/                           被控端出站桥接，编译成 relay_bridge.dll（cdylib）
```

三个目录的边界：

- **没有任何构建期交叉引用**。`android/` 不调用 `host/` 里的任何东西，`host/` 也不知道 `android/` 存在；`worker/` 同样不参与前两者的构建。
- `android/app/src/main/java` 下共 **47 个 `.java`**：45 个手写文件在 `com/rustdesk/java/`，另有 2 个 protobuf 生成文件在 `hbb/`。
- `host/`（改造后）构建不再产出 Flutter/Dart UI，也不使用 vcpkg；构建只需要 Rust 工具链与系统库。Dart/UI 树已删除，但 Rust 侧仍残留 `#[cfg(feature = "flutter")]` 门控的死代码（feature 已不再声明），历史 flutter 条件编译分支正在清理；`build.rs` 里的 `RUSTDESK_SCITER` 宏名虽带 Sciter 字样，实际守护的是 `--service-control` 的服务安装/卸载代码，不要当 UI 残留删除。`host/libs/magnum-opus` 已 vendored 进仓库并改为 path 依赖，`host/libs/hbb_common` 是普通目录而不是 git submodule。
- `host/` 的默认 feature 是 `["use_dasp"]`；无头终端由 `host/src/terminal/` 提供。不带参数启动时进程驻留 **Windows 系统托盘**（`host/src/tray/`，纯 Win32）。Windows 上这个二进制编译为 **GUI 子系统**（`windows_subsystem = "windows"`），双击时系统不会创建控制台窗口；从 cmd/PowerShell 启动时由 `platform::attach_parent_console()` 把输出挂到该窗口。进入托盘路径后还会把 stdout/stderr 指向 NUL 并 `FreeConsole()`，屏幕上只剩任务栏图标；托盘不可用的会话里自动退回无头模式继续服务。
- `worker/` 不属于上面两者的构建：它有自己的 `Cargo.toml`（仅桥接）与 `wrangler.toml`，也不进 `host/` 的 Cargo workspace。

## 两端怎么构建

### `android/`：Gradle

```powershell
cd android

gradle assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk
gradle assembleRelease   # 产物：app/build/outputs/apk/release/app-release.apk
```

- 仓库里只有 `gradle/wrapper/gradle-wrapper.properties`，**没有 `gradle-wrapper.jar`**，所以没有 `./gradlew`。用本机 Gradle **8.14.3**，或者直接用 Android Studio 打开 `android/` 目录。
- `assembleRelease` 用仓库内共享测试 keystore（`android/keystore/test.keystore`）签名，产物可安装、可原地升级；这个 key 是公开的测试 key，**不要用于对外发布**。
- 三个依赖全部来自 Maven（见 `android/app/build.gradle`）：

  | 依赖 | 版本 | 用途 |
  |---|---|---|
  | `com.google.protobuf:protobuf-javalite` | 3.20.1 | 协议消息（`hbb/` 生成代码） |
  | `com.squareup.okhttp3:okhttp` | 4.12.0 | WebSocket 隧道（`wss://`） |
  | `org.bouncycastle:bcprov-jdk18on` | 1.79 | Ed25519 验签 |

- 工程参数：`applicationId` / `namespace` = `com.rustdesk.java`，`compileSdk 36`、`minSdk 24`、`targetSdk 36`、Java 17。
- 便捷脚本：`tools/build-apk.ps1`（自动定位 Gradle / JDK / SDK，`-Install` 会推到 `/data/local/tmp` 再 `pm install -r`）、`tools/check-all.ps1`（自测 + 构建一把过）、`tools/verify-device.ps1`（真机连指定对端抓 logcat）。

### `host/`：Cargo（被控端构建，Linux / Windows MSYS2）

统一入口，两个平台同一条命令：

```bash
cargo build --release --features system-libs
```

- 在 `host/` 目录下执行，或在仓库根用 `--manifest-path host/Cargo.toml`。
- `system-libs` 让 `scrap` 与 `magnum-opus` 的 `build.rs` 走系统 pkg-config，而不是 vcpkg。旧 flag `linux-pkg-config` **仍可用**，它是 `system-libs` 的别名。
- 产物：`host/target/release/rustdesk`（无头终端，运行方式见 [`host/TERMINAL.md`](host/TERMINAL.md)）。

**Linux（发行版包 + pkg-config）**

```bash
sudo apt-get install -y libvpx-dev libaom-dev libyuv-dev libopus-dev
cd host
cargo build --release --features system-libs
```

- 除这四个库，还需要 C/C++ 工具链、`pkg-config`、`clang`/`libclang`（bindgen）、`cmake`、`nasm` 等基础构建依赖；完整的 CI 安装清单以 workflow 为准。
- Ubuntu 的 `libyuv-dev` 只装头文件和库、不提供 `libyuv.pc`，CI 会按需补一个最小的 `libyuv.pc`；本机构建遇到同样情况也要补。

**Windows（MSYS2 UCRT64）**

在 MSYS2 的 UCRT64 环境里装依赖：

```bash
pacman -S mingw-w64-ucrt-x86_64-{gcc,pkgconf,libvpx,aom,libyuv,opus,nasm,cmake,clang}
```

然后在 PowerShell 里设置构建环境（把 `A:\msys64` 换成本机路径）：

```powershell
$env:PATH='A:\msys64\ucrt64\bin;A:\msys64\usr\bin;A:\msys64\clang64\bin;'+$env:PATH
$env:TMP='A:\Downloads\rustdesk\.tmpbuild'; $env:TEMP=$env:TMP
New-Item -ItemType Directory -Force $env:TMP | Out-Null
$env:LIBCLANG_PATH='A:\msys64\clang64\bin'
$env:PKG_CONFIG_PATH='A:\msys64\ucrt64\lib\pkgconfig'
$env:CC='gcc'; $env:CXX='g++'
& 'A:\msys64\ucrt64\bin\cargo.exe' check --manifest-path host/Cargo.toml --features system-libs
```

- **必须把 `TMP`/`TEMP` 指到工作区目录**，否则 gcc 在 MSYS2 下会以 `0xc0000409` 崩溃。
- **必须设 `LIBCLANG_PATH`** 指向 `libclang.dll` 所在目录（这里是 `A:\msys64\clang64\bin`），bindgen 靠它生成绑定。
- **必须设 `PKG_CONFIG_PATH`** 指向 `A:\msys64\ucrt64\lib\pkgconfig`，否则 pkg-config 找不到 UCRT64 的 `.pc`。
- 把 `check` 换成 `build --release` 就是发布构建；运行前还要把 `libstdc++-6.dll`、`libgcc_s_seh-1.dll`、`libwinpthread-1.dll` 放到 exe 同目录，详见 `host/TERMINAL.md`。

**FFmpeg（只有 hwcodec 需要）**

默认 feature 不需要 FFmpeg。启用 `hwcodec` 时，用开源预构建件（BtbN/FFmpeg-Builds）按平台/架构下载，**不本地编译、不走 vcpkg**：

```bash
host/scripts/fetch-ffmpeg.sh    # Linux
```

```powershell
host\scripts\fetch-ffmpeg.ps1   # Windows
```

脚本的下载地址、版本与放置目录以脚本自身为准（本次文档改写未实际运行，待验证）。

> 构建现状：Windows（MSYS2 UCRT64）本机构建已实测通过（`cargo build --release --features system-libs` 产出 22.9 MB 的 `rustdesk.exe`，`--version` / `--get-id` / `--help` 均可运行，动态依赖 8 个 DLL）。`windows-x64` / `linux-x64` / `linux-arm64` 三个 CI 组合曾在 GitHub Actions 上跑过一次全绿（提交 `3bb1bec`，run 40439757697）；本次新增托盘、密码文件与中继库之后尚未重跑。

### 固定密码与托盘（`host/`）

- `rustdesk --password <密码>` **不再走 IPC**：直接把单向校验值写进 `data/config/RustDesk.toml`，并额外写一份明文副本 `data/config/permanent-password.txt` 供显示。
- 每次有客户端登录都会重新读这份明文文件再校验，所以**运行中改密码旧密码立刻失效**，不用重启（只有横幅文字要重启才更新）。
- 有固定密码时横幅显示密码本身，不再显示临时密码。
- 不带参数启动 -> 系统托盘：菜单实时显示 ID / 密码 / 中继服务器，并提供重启、退出；托盘模式不显示任何终端界面（控制台被脱离、标准输出指向 NUL），日志仍照常写入 `data\log\`。

## 安卓端能力矩阵

证据等级定义（本表严格按这三档标注，不含"应该有"）：

- **强** —— 有真机或真实公网服务器上的运行产物（logcat / 截图 / 对端日志 / 真实 hbbs 交互）。
- **中** —— 只有自动化断言通过（离线自测或含网络的端到端自测），没有真机产物佐证。
- **弱** —— 仅代码完成，无任何运行证据。

### 主控端（手机控制电脑）：`ui/` + `session/` + `net/` + `codec/`

| 能力 | 状态 | 证据等级 | 依据 |
|---|---|---|---|
| 通过 ID 连接（hbbs 打洞 / 中继） | 完成 | 强 | 真实公网服务器 |
| 直连 `IP[:端口]`（跳过服务器） | 完成 | 强 | 真机验证（`android/README.md` 记录）；本次端到端自测确认服务器返回了可达直连地址 |
| 隧道域名连接（Cloudflare，自动补 `wss://`） | 完成 | 中 | `wss://` 握手通过；登录用户名已改为按 `域名:端口` 发送（`Session.tunnelUserFromUrl`），但这次修复之后**没有**再跑隧道端到端 |
| 登录（两段 SHA-256 摘要） | 完成 | 强 | 真实 hbbs 上 `login ok`；`HostEndToEnd` 复跑通过 |
| 视频接收与解码（VP9 / H.264 / H.265 / VP8 / AV1） | 完成 | 强 ※ | 真机解码 300+ 帧、真机截图 |
| 等比缩放显示（不拉伸）、横屏 | 完成 | 强 | 真机截图，`2412×1080` |
| 左侧竖条分上下半 = 左 / 右键（长按锁定） | 完成 | 强 | 真机日志 |
| 鼠标移动 / 滚轮 / 文本输入 | 完成 | 中 | 真实主机接受，无截图级产物 |
| 单指平移、双指缩放、长按拖选 | 完成 | 中 | 29 项数学自检 |
| 三种输入模式（TEXT / SHORTCUT / KEYS） | 完成 | 强 | 真机截图 |
| 虚拟键盘多指共按 | 完成 | 强 | 自绘键盘 + 多点触控，真机验证 |
| 剪贴板双向 | 完成 | 强 | 真机 `clipboard up/down` |
| 文件传输（协议层） | 协议完成、无界面 | 中 | `FileMsgTest` 75 项断言 |
| 终端（协议层） | 协议完成、无界面 | 中 | `TerminalMsgTest` 73 项断言 |
| 终端界面 | **未接线** | 弱 | `ui/TerminalActivity.java` 存在且已在清单注册，但全仓库没有任何地方启动它 |

※ **一处文档冲突，如实记录**：`android/README.md` 把「视频接收 + 解码 + 渲染」记为强（真机 300+ 帧），但 `android/docs/PROTOCOL.md` §11.7 的「未验证」清单里仍列着「viewer 解码 + 渲染」。两份文档没有同步；保守起见请按「中」看待，直到补上可复跑的 logcat / 截图。

### 安卓 app 内的被控端（手机被电脑控制）：`host/` 包

| 能力 | 状态 | 证据等级 | 依据 |
|---|---|---|---|
| hbbs 注册（`RegisterPk` + 每 10s 续期） | 完成 | 强 | 本次 `HostEndToEnd` 在公共 hbbs 上注册成功（`host registered with hbbs`） |
| 服务器分配身份、打洞应答（`PunchHole` → 响应） | 完成 | 强 | 同上：服务器返回了本机身份与可达地址 |
| 直连监听（端口可配） | 完成 | 强 | 同上：`host bound a listening port` |
| `SignedId` 握手 + 密码挑战 | 完成 | 强 | 同一次端到端（与本仓库主控端互通，含两级身份验签） |
| 登录、维持会话、接收输入 | 完成 | 强 | 同上：`login accepted`、`host reported the viewer connected`、`session survived input` |
| 登录后推流（协议层，假帧） | 完成 | 中 | `HostSessionTest` 断言主控端收到 3 条 `video_frame(h264s)`；真实采集帧还没接进来 |
| 屏幕采集 + H.264 编码 + EGL 上传 | 代码完成 | **弱** | `ScreenCaptureService` 拿到授权后确实在跑，但 `setFrameListener(...)` 无调用者、`HostAgent.onCapturedFrame(...)` 没人喂 → 帧到不了编码器，从未真机 |
| 键盘注入（无障碍） | 部分 | **弱** | 已接线（`MainActivity` 把 `InputInjectorSink` 装进 `HostAgent`）；仍只有 Back / Home / Recents / 通知四类全局动作 + 往聚焦节点塞文本，普通键码不可用 |
| 剪贴板双向 | 协议完成、未接线 | **弱** | `HostSession` 收发齐了，但没有任何 Android 类实现 `HostClipboardSink`，`setClipboardSink` 无调用者 |
| 鼠标 / 触摸注入 | **未实现** | 弱 | `HostSession` 主循环没有鼠标 `case`，鼠标消息落到 `default` |
| 防熄屏常亮 | **未实现** | 弱 | 未加 `FLAG_KEEP_SCREEN_ON` |

`HostEndToEnd` 是唯一能证明**两端链路完整**的测试：它把本仓库的被控端注册到真实公共 hbbs，再用本仓库的主控端打洞、握手、登录、发输入，共 15 项断言（2026-10-26 复跑通过）。

### 明确没做的

音频、多显示器切换、2FA、文件传输界面、终端界面入口、被控端鼠标/触摸注入、zstd 剪贴板压缩、防止屏幕熄灭。

## 连接方式对照表

ID 输入框**一个框三种用法**：

| 输入形式 | 例子 | 走什么 | 需要公网吗 |
|---|---|---|---|
| 纯数字 ID | `1182258341` | hbbs 打洞（失败则中继） | **需要** |
| `IP` 或 `IP:端口` | `10.52.190.199:21118` | TCP 直连，完全不碰服务器 | **不需要**（同一局域网即可） |
| 域名 | `rd.example.com` | 隧道 `wss://`（自动补协议头） | **需要** |
| `wss://` 地址 | `wss://rdworker.example.com` | 自家 Worker 中继（见下节） | **需要**（被控端只要出站） |

被控端要做局域网直连，需在 `data/config/RustDesk2.toml` 打开直连监听：

```toml
[options]
direct-server = 'Y'
direct-access-port = '21118'
```

## Worker 中继（可选，跨网络用）

对称 NAT（运营商移动网络常见）下 ID 直连打不通、官方中继又可能连不上，`worker/` 提供一条完全自建的替代路径：

```
手机 ──WSS──> Cloudflare Worker / Durable Object ──WSS──> relay_bridge.dll ──TCP 127.0.0.1:21118──> 被控端
```

- 被控端**不需要任何入站端口或端口映射**，桥接是出站长连接。
- 手机 ID 填 `wss://<你的域名>`；域名解析到 Cloudflare 边缘，境内通常可直连。
- 桥接是一个独立动态库 `relay_bridge.dll`，只有带 `--relay-bridge` 时才会被 `LoadLibrary` 进内存：

  ```powershell
  .\rustdesk.exe --headless --relay-bridge "wss://rdworker.example.com/bridge?key=你的KEY"
  .\rustdesk.exe --relay-bridge "wss://rdworker.example.com/bridge?key=你的KEY"   # 托盘 + 中继
  ```

- 两端分帧不同，桥接负责转换：TCP 侧是长度前缀（`host/libs/hbb_common/src/bytes_codec.rs`），WS 侧每帧只装裸消息（`websocket.rs` 的 `send_raw`）。
- 部署与排障见 `worker/README.md`；`BRIDGE_KEY` 必须改成自己的随机串，否则任何人都能占用这个中继。

## CI：一个 workflow，两组 job

CI（改造后）由仓库根的单一 workflow `.github/workflows/build-release.yml` 承担；`android/` 与 `host/` 不各自维护 workflow。它包含两个 job 家族，互不 `needs`，故意隔离：Rust 侧依赖炸了不该赔上 APK，反之亦然。

1. **android**：在 `ubuntu-latest` 上构建 release APK（Gradle）。
2. **host 矩阵**：`linux-x64` / `linux-arm64` / `windows-x64`，统一走 `cargo build --release --features system-libs`。Linux 用发行版 apt + pkg-config；Windows 用 MSYS2 UCRT64。产物名为 `host-release-<platform>-<arch>`，例如：

   ```text
   host-release-linux-x64
   host-release-linux-arm64
   host-release-windows-x64
   ```

FFmpeg 不参与矩阵构建：默认只用系统库。`host/scripts/fetch-ffmpeg.{sh,ps1}` 可以按平台/架构取开源预构建件（win-x64 已实测：BtbN 包 + sha256 校验 + `.pc` 可直接被 pkg-config 读到），但 **`hwcodec` 尚未接线**——它的 `build.rs` 只认 vcpkg 布局的静态 FFmpeg，硬开这个 feature 会 panic，所以 CI 不提供该开关。

每个平台的产物都会打成一个**压缩包**（Windows `.zip`、Linux `.tar.gz`），包内除可执行文件与依赖库，还带一份中英双语的 `README.txt` 使用说明（源文件 `host/packaging/README.txt`，中文为主）。`dist/` 里是原始文件：

- Windows：`rustdesk.exe` + 递归导入的 8 个 DLL + `relay_bridge.dll`；
- Linux：`rustdesk` + `librelay_bridge.so`（其余依赖由发行版提供）。

`worker/bridge` 不在 `host/` 的 workspace 里，所以 CI 单独为它跑一次 `cargo build`，再把库拷进 `dist/`。

实际触发条件、系统包清单、缓存与超时都以 workflow 文件为准。本机没有实跑 GitHub Actions，三个矩阵组合的实际通过状态（尤其 `linux-arm64` 与 `windows-x64`）**待验证**。

## 测试怎么跑

离线自测（不需要手机、不需要 Android SDK、不需要 Gradle、不需要网络）：

```powershell
cd android
pwsh -File tools\selftest\run-selftest.ps1
```

含真实公网端到端：

```powershell
pwsh -File tools\selftest\run-selftest.ps1 -IncludeNetwork
```

本次实测的真实输出（2026-10-26）：

```
compile A (protobuf only): 12 sources (5.58s)
compile B (protobuf + bcprov): 32 sources (5.50s)

[OK] TerminalMsgTest (0.19s) checks: 73
[OK] ClipSyncTest (0.17s) checks: 11
[OK] FileMsgTest (0.23s) checks: 75
[OK] KbSelfTest (0.17s) checks: 7
[OK] IdCheckTest (0.09s) checks: 14
[OK] crypto.SelfTest (0.35s) checks: 51
[OK] HostSessionTest (0.68s) checks: 49
[OK] PeerHandshakeInteropTest (0.37s) checks: 31
[OK] Ed25519Test (0.25s) checks: 22
[OK] LoopbackTest (0.34s) checks: 14

TOTAL: 347 checks in 10 suites
RESULT: 10/10 PASSED
```

加 `-IncludeNetwork` 后多出 `HostEndToEnd`（15 项，走真实公共 hbbs）：

```
[OK] HostEndToEnd (4.87s) checks: 15

TOTAL: 362 checks in 11 suites
RESULT: 11/11 PASSED
```

套件分工：

| 套件 | 断言数 | 覆盖 |
|---|---|---|
| `TerminalMsgTest` | 73 | 终端消息编解码 |
| `ClipSyncTest` | 11 | 剪贴板消息（含多格式） |
| `FileMsgTest` | 75 | 文件传输消息 |
| `KbSelfTest` | 7 | 键位映射 |
| `IdCheckTest` | 14 | ID 合法性规则 |
| `crypto.SelfTest` | 51 | Ed25519 / secretbox / 帧格式 |
| `HostSessionTest` | 49 | 被控端会话状态机 |
| `PeerHandshakeInteropTest` | 31 | 真实 `PeerHandshake` 驱动真实 `HostSession` |
| `Ed25519Test` | 22 | 签名/验签与上游互通 |
| `LoopbackTest` | 14 | 本机环路建链 |
| `HostEndToEnd` | 15 | 网络套件：真实 hbbs 注册 + 打洞 + 握手 + 登录 + 输入 |
| `RelayTest` | — | 网络套件，**源码在仓库外**（仓库同级的 `tmpscripts/rdprobe/`），须给 `-PeerId` 才会跑 |

其他脚本：`tools/check-all.ps1`（自测 + 构建，输出一张汇总表）、`tools/verify-device.ps1 -Id <对端ID> -Password <密码>`（真机连线抓 logcat）。

`host/` 的 Rust 测试沿用上游规则：`#[cfg(test)]` 模块贴在代码旁边，在 `host/` 下 `cargo test --workspace`。CI 目前只做构建与安卓 JVM 自测，不跑 Rust 测试，Rust 测试需在本地执行。

## 已知限制

1. **被控端视频链路差最后一处接线**：`ScreenCaptureService` 拿到授权后确实在运行，但没人调用它的 `setFrameListener(...)` 把帧交给 `HostAgent.onCapturedFrame(...)`，采集帧到不了编码器。零件都在，就差这一根线，所以从未在真机上出过画面。
2. **被控端键盘注入受限**：已经通过 `HostInputSink` / `InputInjectorSink` 接线，但无障碍服务只能做 Back / Home / Recents / 通知四类全局动作，外加往聚焦节点塞文本；普通字母数字键码注入不进去（这是 Android 无障碍 API 的边界，不是没写完）。
3. **被控端剪贴板和鼠标未接线**：`HostClipboardSink` 没有任何 Android 实现、`setClipboardSink` 无调用者；`HostSession` 主循环没有鼠标 `case`，鼠标消息落到 `default`。
4. **未实现 zstd 剪贴板压缩**：对端发长文本剪贴板时会用 zstd 压缩，本工程不实现解压，主控端和被控端都会跳过并记日志。
5. **没加 `FLAG_KEEP_SCREEN_ON`**：远程操作时屏幕会按系统超时熄灭。
6. **隧道登录用户名改完还没复测**：用户名已从「纯 ID」改成按 `域名:端口` 发送（`Session.tunnelUserFromUrl`），但修复之后没有再跑隧道端到端，目前只有代码层面的确认。
7. **`KEYS` 模式不是完整键位图**：只有功能键 + 数字 + 字母 + 修饰键，没有小键盘、没有符号区。
8. **CI 用共享测试 keystore 签名 release APK，并用 `apksigner` 校验**：该 key 能证明包可安装/可升级，但**不能用于对外发布**；正式发布签名不在本仓库范围内。
9. **文件传输、终端都只有协议层**：文件传输没有界面；终端有 `ui/TerminalActivity`，但全仓库没有任何 `startActivity` 指向它，等于没入口。
10. **`host/` 只在 Windows 本机验证过，CI 组合尚未实跑**：Windows（MSYS2 UCRT64）的 `cargo check` / `cargo build --release` / `cargo test --lib`（202 通过、1 个需提权环境的用例失败）与二进制冒烟均有实测记录；`linux-x64` / `linux-arm64` / `windows-x64` 三个 GitHub Actions 组合、Docker 镜像、deb 打包仍待验证。

## 协议要点（极简）

完整版见 [`android/docs/PROTOCOL.md`](android/docs/PROTOCOL.md)（每条结论都带「文件:行号」出处；§9 是踩坑清单，§11 是实现现状与「未验证」清单）。这里只列最容易踩的几条：

- **帧格式**：`value = (len << 2) | (headerBytes - 1)`，小端；`MAX_FRAME_LENGTH = 0x3FFF_FFFF`。空负载合法（`00`），Rust 端握手失败时发的正是空 `Message`（表示「不加密」），不能当错误。
- **加密**：secretbox = XSalsa20-Poly1305，**tag 在密文之前**（`tag(16) || ciphertext`），密钥流从第 32 字节开始。`SecureTransport` 只封负载，帧头保持明文。
- **hbbs 流程**：服务器先发 `KeyExchange`，不应答就断连；主控端 `RegisterPk` 即可（`RegisterPeer` 那条私有方法主流程不调用），之后在同一条连接上问 `PunchHoleRequest`。
- **两级身份**：`PunchHoleResponse.pk` 是**服务器签名**的 IdPk blob，其中 `pk` 是对端的 **Ed25519 验签公钥**；对端自己发的 `SignedId.pk` 是它的 **Curve25519 加密公钥**。两者不是一回事，混用必验签失败。
- **登录**：`LoginRequest.username` 必须是**自己的 ID**；密码是 `sha256(sha256(密码 ‖ salt) ‖ challenge)` 两段摘要。
- **直连时先发的是 `Hash` 而不是 `SignedId`**：握手必须扫描前导消息，不能只读第一条。
- **地址混淆**：`AddrMangle` 编码结果常见只有 11–12 字节（先左填零到 16 字节再按小端 u128 还原），解码端口必须截断到 u16，否则内网短地址整条解不出来。

## 授权与出处

- 协议实现参考 RustDesk 官方仓库（`host/` 即其 Rust 源码）。使用前请遵守 RustDesk 的授权条款，见 `LICENCE`。
- 本项目为个人重构实践，与 RustDesk 官方无隶属关系。请勿用于未经授权的远程访问。
- 参考文档：[`android/README.md`](android/README.md)（客户端细节）、[`android/docs/PROTOCOL.md`](android/docs/PROTOCOL.md)（协议与实现现状）、[`android/tools/selftest/README.md`](android/tools/selftest/README.md)（自测脚本）、[`host/TERMINAL.md`](host/TERMINAL.md)（无头终端使用）、[`host/docs/FORK-CHANGES-ZH.md`](host/docs/FORK-CHANGES-ZH.md)（本分支改动）。
