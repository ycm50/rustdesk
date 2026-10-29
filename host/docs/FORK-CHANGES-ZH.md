# 本分支改动说明（目录拆分 / 无头被控端 / 系统库构建）

本文记录本仓库相对上游 RustDesk 的结构性改动。构建命令与平台依赖以根 `README.md`、
[`host/TERMINAL.md`](../TERMINAL.md) 和 `.github/workflows/build-release.yml` 为准。

## 1. 仓库拆成两个互不依赖的目录

- `android/`：纯 Java 的安卓客户端（主控端 + 被控端）。没有 Flutter/Dart、Rust `.so`、NDK 或 vcpkg，构建走 Android Gradle。
- `host/`：被控端，保留上游 Rust 源码。没有 Flutter/Dart UI，不使用 vcpkg，只提供无头终端。

两边没有构建期交叉引用：安卓端不调用 `host/`，`host/` 也不知道 `android/` 存在；CI 也用两个互不 `needs` 的 job 家族承载。

## 2. host 的构建：去掉 vcpkg，改用系统库

### 2.1 `system-libs` feature

`host/Cargo.toml` 新增 `system-libs` feature：

```toml
system-libs = ["magnum-opus/system-libs", "scrap/system-libs"]
# 兼容旧脚本/旧文档里的 flag
linux-pkg-config = ["system-libs"]
```

启用后，`scrap` 与 `magnum-opus` 的 `build.rs` 在 Linux 和 Windows 上走 pkg-config 找
libvpx / libaom / libyuv / opus，而不是去引导 vcpkg。旧的 `linux-pkg-config` 仍可用，但新脚本统一写
`--features system-libs`。

### 2.2 magnum-opus 内置

上游的 `magnum-opus`（opus 封装）原本从外部拉取，且构建脚本只认 vcpkg。本仓库把它 vendored 到
`host/libs/magnum-opus`，在 `host/Cargo.toml` 里改成 path 依赖，并让它的 `build.rs` 支持
`system-libs`（走 pkg-config）。

### 2.3 平台依赖

- Linux：发行版包 `libvpx-dev`、`libaom-dev`、`libyuv-dev`、`libopus-dev`，外加
  `pkg-config`、`clang`/`libclang`（bindgen）等基础工具。
- Windows：MSYS2 UCRT64，包名
  `mingw-w64-ucrt-x86_64-{gcc,pkgconf,libvpx,aom,libyuv,opus,nasm,cmake,clang}`。
  bindgen 从 `LIBCLANG_PATH` 找 `libclang.dll`，pkg-config 从 `PKG_CONFIG_PATH` 找
  `ucrt64/lib/pkgconfig`。本机 MSYS2 上的 gcc 还需要把 `TMP`/`TEMP` 指到工作区，否则会以
  `0xc0000409` 退出。

### 2.4 hbb_common 不再是 submodule

`host/libs/hbb_common` 随仓库提交，克隆后可直接编译；仓库里没有 `.gitmodules`。后续同步上游时
需要手工合并这个目录。

## 3. 无头终端

`host/src/terminal/` 是终端入口，进程启动后不再拉起 Flutter / Sciter / 托盘：默认作为本机被控端运行，
也可用命令行连接对端。主要命令（见 `host/src/terminal/mod.rs` 的 USAGE）：

```text
rustdesk --headless [-v]
rustdesk --connect <id> [pass]
rustdesk --terminal <id> [pass]
rustdesk --file-transfer <id> [pass] <ls|get|put> ...
rustdesk --port-forward <id> [pass] <local_port> <host> <port>
rustdesk --get-id
rustdesk --password <pass>
```

使用细节（管理员权限、远程库 DLL、配置/日志目录、已知限制）见 `host/TERMINAL.md`。

## 4. CI

CI 使用仓库根的单一 workflow `.github/workflows/build-release.yml`，包含两个 job 家族：

- `android`：构建 release APK；
- `host` 矩阵：`linux-x64` / `linux-arm64` / `windows-x64`，统一
  `cargo build --release --features system-libs`，产物名为
  `host-release-<platform>-<arch>`。

两个家族互不 `needs`：Rust 侧依赖失败不应赔上 APK，反之亦然。具体触发条件、系统包清单与超时以
workflow 文件为准。

## 5. FFmpeg

FFmpeg 只有 `hwcodec` feature 需要。默认构建不用 FFmpeg，也不本地编译：由
`host/scripts/fetch-ffmpeg.sh`（Linux）与 `host/scripts/fetch-ffmpeg.ps1`（Windows）按平台/架构
下载 BtbN/FFmpeg-Builds 的开源预构建件。脚本的版本与放置目录以脚本自身为准。

## 6. 保留的隧道改动（非 UI）

- 客户端识别 `ws://` / `wss://` 端点，端点可携带 `?password=...`（`host/src/client.rs`）。
- 被控端的直连端口会窥探首包，发现 `Upgrade: websocket` 时切换为 WebSocket 帧解析
  （`host/src/rendezvous_mediator.rs` 的 `direct_access_stream`）。
- `hbb_common` 提供 `WsFramedStream::new_with_headers`（升级请求附加自定义头）与
  `WsFramedStream::from_tcp`（接受隧道转发的升级请求）。

## 状态与待验证（截至 2026-10-26 文档改写）

- `host/` 在 Windows（MSYS2 UCRT64）上的本机构建仍在收敛：`build.rs` 里遗留的
  vcpkg/homebrew 分支引用、bindgen 加载 `libclang.dll`、`libs/clipboard` 的 C 代码在 GCC 16 下的
  `int-conversion`、以及 `webm-sys` / `kcp-sys` 的构建脚本问题。修好前，Windows 产物待验证。
- `host` 矩阵的 `linux-arm64` 与 `windows-x64` 组合、以及 `fetch-ffmpeg` 脚本实际下载结果，本机都
  没有实跑记录，待验证。
- 隧道登录的端到端复测仍未补上；目前只有代码层面的确认（与安卓端文档一致）。
