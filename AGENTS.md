# RustDesk Guide

本仓库是 RustDesk 的重构 fork，拆成两个互不依赖的部分：

* `android/` —— 纯 Java 安卓客户端（主控端 + 被控端）。没有 Flutter/Dart、Rust `.so`、NDK 或 vcpkg；构建走 Gradle。
* `host/` —— 被控端，保留上游 Rust 源码。没有 Flutter/Dart UI，不使用 vcpkg；只有无头终端（入口在 `host/src/terminal/`，使用说明见 `host/TERMINAL.md`）。
* `worker/` —— 可选的中继：Cloudflare Worker + Durable Object 转发 WSS 二进制帧，配一个跑在被控端机器上的出站桥接（`worker/bridge`，独立 Rust crate）。它不参与上面两者的构建。

几条适用于全仓库的构建约定：

* `host/` 的系统库构建统一用 `--features system-libs`；`linux-pkg-config` 是它的兼容别名。
* `host/` 在 Linux 上走发行版 pkg-config，在 Windows 上走 MSYS2 UCRT64 的 pkg-config；不要新增 vcpkg 依赖或 vcpkg 构建步骤。
* FFmpeg 只有 `hwcodec` 需要，使用 `host/scripts/fetch-ffmpeg.{sh,ps1}` 按平台/架构取开源预构建件，不本地编译。
* 改 `android/` 不需要也不能调用 `host/` 的 Cargo 构建，反之亦然。

## Project Layout

### Directory Structure
* `host/src/` Rust app（被控端）
* `host/src/server/` audio / clipboard / input / video / network
* `host/src/platform/` platform-specific code
* `host/src/terminal/` 无头终端入口（`--headless` 跨平台；`--connect` / `--terminal` / `--file-transfer` / `--port-forward` 目前仅 Windows 实现）
* `host/src/lang/` 本地化
* `host/libs/hbb_common/` shared with the server: rendezvous proto, sockets, `Config` core。它是本仓库的普通 workspace member，不再是 git submodule
* `host/libs/base/` (crate `base`) shared: option keys, message proto, file transfer, platform code
* `host/libs/scrap/` screen capture
* `host/libs/enigo/` input control
* `host/libs/clipboard/` clipboard
* `host/libs/magnum-opus/` vendored opus wrapper（path 依赖；`system-libs` 下走 pkg-config）
* `host/libs/base/src/config/keys.rs` the single import path for all options
* `android/` pure Java Android client（Gradle project; 没有 `host/` 的 Cargo workspace 成员关系）
* `worker/` Cloudflare Worker 中继（`src/`）+ 被控端出站桥接（`bridge/`）；用法见 `worker/README.md`

### Key Components
- **Remote Desktop Protocol**: Custom protocol implemented in `host/src/rendezvous_mediator.rs` for communicating with rustdesk-server
- **Screen Capture**: Platform-specific screen capture in `host/libs/scrap/`
- **Input Handling**: Cross-platform input simulation in `host/libs/enigo/`
- **Audio/Video Services**: Real-time audio/video streaming in `host/src/server/`
- **File Transfer**: Secure file transfer implementation in `host/libs/base/src/fs.rs`
- **Headless Terminal**: 被控端通过 `host/src/terminal/` 的 `--headless` 入口运行（跨平台），不启动 GUI；不带参数则在 Windows 上转由 `host/src/tray/` 驻留通知区域；`--relay-bridge <url>` 按需加载 `relay_bridge.dll` 做 WSS 中继；连接端的 `--connect` / `--terminal` / `--file-transfer` / `--port-forward` 目前仅 Windows 实现，非 Windows 只打印 `only implemented on Windows in this build` 并以退出码 2 结束

`hbb_common` 是本仓库的普通 workspace member（目录随仓库提交），可以直接改；它不再是从外部拉取的 submodule。`base::config::keys` re-exports the handful of keys `hbb_common` still reads, so callers get the whole set from that one path.

### No UI

Dart/UI 树已删除。**唯一**的 GUI 是在 `host/src/tray/` 里的 Windows 系统托盘（纯 Win32 `Shell_NotifyIconW`，不带参数启动时驻留，菜单显示 ID / 密码 / 中继并支持重启、退出）；其余交互仍走终端（`host/TERMINAL.md`）。

Rust 侧仍残留 `#[cfg(feature = "flutter")]` 门控的死代码（feature 已不再声明），历史 flutter 条件编译分支正在清理；`build.rs` 里的 `RUSTDESK_SCITER` 宏名虽带 Sciter 字样，守护的其实是 `--service-control` 的服务安装/卸载代码（`platform/windows.cc`），不要当 UI 残留删除。不要在 `host/` 里引入 WebView、Flutter engine、或除托盘之外的窗口系统代码。

## Rust Rules

* Avoid `unwrap()` / `expect()` in production code.
* Exceptions:

  * tests;
  * lock acquisition where failure means poisoning, not normal control flow.
* Otherwise prefer `Result` + `?` or explicit handling.
* Do not ignore errors silently.
* Avoid unnecessary `.clone()`.
* Prefer borrowing when practical.
* Do not add dependencies unless needed.
* Keep code simple and idiomatic.

### Logging

* `debug` and above are written to the log file. A log call that can fire
  repeatedly (per packet, frame, input event, or loop iteration, or at a rate a
  peer controls) must not use `debug` or higher unthrottled.
* For such a site, pick one:

  * `log::trace!` when the event is expected and the line only helps while
    actively debugging;
  * `hbb_common::throttled_log!(interval, level, ...)` when it signals a fault
    that should still show up in a user's log. It keeps one line per interval
    with a count of the rest. Use `hbb_common::log_throttle::LogThrottle`
    directly only when the decision drives more than one log call.

## Tokio Rules

* Assume a Tokio runtime already exists.
* Never create nested runtimes.
* Never call `Runtime::block_on()` inside Tokio / async code.
* Do not hide runtime creation inside helpers or libraries.
* Do not hold locks across `.await`.
* Prefer `.await`, `tokio::spawn`, channels.
* Use `spawn_blocking` or dedicated threads for blocking work.
* Do not use `std::thread::sleep()` in async code.

## Android (Java)

* `android/` 是纯 Java 的 Android Gradle 工程；不要引入 Flutter/Dart、Rust JNI 库、NDK 或 vcpkg。
* 安卓端的构建与测试命令在 `android/README.md` / `android/tools/`；不要把它接进 `host/` 的 Cargo workspace。
* 改安卓协议/会话代码时，先读 `android/docs/PROTOCOL.md` 的「文件:行号」出处，保持与新结构一致。

## Editing Hygiene

* Change only what is required.
* Prefer the smallest valid diff.
* Do not refactor unrelated code.
* Do not make formatting-only changes.
* Keep naming/style consistent with nearby code.

### Imports

* One `use` per crate. Everything a file takes from the same crate goes in a
  single braced block, not one statement per item:

  ```rust
  // no
  use base::fs;
  use base::message_proto::*;

  // yes
  use base::{fs, message_proto::*};
  ```

* The only reason to split is a `#[cfg(...)]` that does not apply to the whole
  block -- an attribute binds to one item, so a differently-gated import has to
  stand on its own. A `pub use` re-export likewise cannot join a plain `use`.

  ```rust
  #[cfg(not(feature = "drm"))]
  use base::fs;
  use base::message_proto::*;
  ```

* When splitting an existing `use` because some of its items moved to another
  crate, fold each side into that crate's existing block rather than leaving a
  second statement behind.

### Comments

* Avoid comments unless they explain a non-obvious reason, constraint, or workaround.
* Never restate what the code does; prefer clearer code instead.
* If the code is self-explanatory, add no comment.

### Be minimally invasive

* Prefer purely additive changes: layer new (`#[cfg]`-gated) blocks or new functions around existing code instead of restructuring it. The ideal diff for a fix adds lines and modifies/deletes none.
* Do not extract or reshape existing code just to enable your new code; look for a mechanism that leaves existing lines untouched (e.g. hide/show an existing object instead of refactoring its construction into a helper for rebuilding).
* Accept a little duplication over a restructure. A new function that repeats a few lines of an existing one is a better diff than reshaping the original so both can share it.
* Put new logic in self-contained functions in the module it belongs to (platform-specific logic in `host/src/platform/`, with `use` inside the function body to avoid churning shared import blocks). Call sites in shared files (`host/src/core_main.rs`, `host/src/server/connection.rs`, `host/src/rendezvous_mediator.rs`, …) should be thin one-line hooks.

### Scope check before touching shared code

* Before changing a shared trait, a shared struct, or the signature of a widely used function, check whether the bug or feature is specific to one path. If it is, keep the change inside that path unless that is impossible, and say in the PR why it was.
* If an unrelated caller needs `Default::default()`, `None`, or another placeholder solely to satisfy a signature you changed, the diff is too broad: stop and redesign.
* The expected shape of a fix is a new function in the feature's own module, plus at most a new field or a thin hook in the shared code it needs. Feature-specific state belongs beside the feature's existing state, not in a new abstraction every caller has to learn.

### Mandatory regression-surface check

Before considering any implementation complete, perform a minimization pass over the final diff.

* Inspect every modified existing file and every modified existing code path. Each must be strictly necessary for the requested change. Revert changes that are merely cleanup, refactoring, consistency improvements, or fixes for pre-existing issues.
* For new features, preserve the existing implementation path when the feature is disabled or unsupported whenever practical. `feature off` should run the old code, not a rewritten equivalent.
* Do not route existing behavior through a new abstraction merely to share code with the new feature. Prefer a parallel new function or a small amount of duplication over changing a proven existing path.
* Keep new implementation logic in new or feature-specific modules. Changes to shared/core files should normally be thin hooks, capability checks, or protocol plumbing.
* Do not fix unrelated pre-existing bugs in the same PR. Put them in a separate change unless they directly block correctness or security of the requested work.
* Before finalizing, explicitly report the regression surface: list the existing files and existing runtime paths whose behavior changed, and explain why each change is unavoidable.
* During review, treat an unnecessarily modified legacy path as a review finding even if tests pass and the rewritten behavior appears equivalent.

### Corner cases raised in review

A refactor added to cover a corner case rarely converges. Each new counter, timestamp, cache or eviction/expiry rule interacts with state that existing code relies on, and the next review round finds the problems it introduced.

* A corner case is still worth fixing when the fix is easy and low-risk: a local change of a few lines that adds no state and changes no existing lookup, such as moving a check or refusing bad input earlier.
* When the only fix needs new state, a new lifecycle rule or a restructure, and the code already fails cleanly there or behaves as master does, document it as a known limit in the PR instead. Anything beyond the easy fix needs the maintainer's explicit go-ahead first.
* Before adding state that reorders, expires or reuses existing data, list every lookup that reads that data and check each one still holds.
* Prefer a clean failure, where the operation reports an error, over machinery that tries to make a rare case succeed.
* A severity label from any reviewer (P1, Critical, Major) is not a triage result. Apply the next rule by consequence, not by label.
* A corner case whose fix needs new state is fixed only when it crashes, loses data, weakens security, or a user has reported it. A rare cosmetic or layout glitch (e.g. rotation during an active drag, a feature that is off by default) is a known limit: reply once, list it under "Known limits" in the PR body, and leave the code alone.
* When a finding is about behavior an earlier commit of this same PR introduced, fix it by removing or simplifying that commit, not by adding a layer on top.
* Judge growth across all rounds, not per round. If review follow-ups have grown the non-test diff by more than half of the first fix, or added a new kind of state (handles into another component, cross-component references, deferred / post-frame callbacks, timers, caches, flags), stop and ask the maintainer before pushing.
* When keeping the user's preferred state is hard in a rare case, fall back to a deterministic default computed from the current inputs. Do not coordinate mutable state across components or frames to preserve the preference.

## Tests

* A fix gets regression tests for the reported behavior only: they fail on master and pass with the fix. One to three tests is normal.
* Assert what the user sees or what the API returns. Do not test private state, the order an algorithm runs its steps in, or each corner case raised in review.
* Do not add test infrastructure (browser runners, golden/screenshot harnesses, new mock layers, test-only hooks in production code) for a bug fix unless the maintainer asks.
* If the test diff is more than twice the fix, cut it back to the tests that pin the reported behavior. A state that needs long setup to reach is usually too rare to fix.
* When the number of tests needed to describe the behavior keeps growing, the implementation is too complex: simplify it instead of adding tests.
* Rust unit tests go in a `#[cfg(test)]` module beside the code. Run `cargo test --workspace` from `host/`; CI（`.github/workflows/build-release.yml`）目前只做构建与安卓 JVM 自测（`javac` + `crypto.SelfTest`），不跑 Rust 测试，Rust 测试需在本地执行。A platform-gated test in another workspace crate runs only if the test command names the crate with `-p`. Gate a test module on a feature or `target_os` only when the code under test is gated; a pure helper that only a gated path calls gets `#[cfg(any(feature = "...", test))]`, so its tests run everywhere.
* 安卓端测试是独立的 PowerShell 自测（`android/tools/selftest/`），不是 Dart/Flutter 测试，也不进 cargo，不要混用两套测试入口。

## Reviewing a PR

* Review only what the diff introduces. Verify ownership with `gh pr diff` before reporting a finding — if the offending lines are untouched context, it is a pre-existing problem, not this PR's.
* List pre-existing problems in a separate section at the end, or leave out the ones that are not fatal. Never mix them into the findings the author has to fix.
* Before re-reviewing, read the author's reply comments. Do not re-raise items they declined on scope grounds.
* State a finding's consequence exactly: distinguish "the value is lost" from "the shortcut is inert but the value still saves".
* Do not report a rare corner case as blocking when fixing it needs new state; mark it as a known limit the author may leave unfixed.
* Treat growth across review rounds as a finding: if the latest commits add more state than the original fix, say so instead of asking for more handling.

## Localization (`host/src/lang/*.rs`)

Each file is a `HashMap<key, translation>`. Layout:

* `template.rs` is the master list of every key. **Never edit it** as part of translation work.
* `en.rs` holds only the keys whose English display text differs from the key itself.
* Every other file (`de.rs`, `fr.rs`, …) carries the full key set; an untranslated entry has an empty value: `("key", "")`.
* `it.rs` is maintained by hand by its translator. Never fill or change its entries; when adding new keys, append them to it with `""` and leave the translation to the maintainer.

### Finding the English source for a key

When filling an empty entry, determine the source English text with this rule:

* If `key` exists in `en.rs` **with a non-empty value**, that value is the source text (look it up in `en.rs`).
* Otherwise the **key string itself is the source text** (the key is already plain English).

Then translate that source into the file's target language (infer the language from the file's existing non-empty entries / filename).

### Translation hygiene

* Only fill empty values. Never change keys, and never touch existing non-empty translations.
* Preserve placeholders (`{}`) and escape sequences (`\n`, `\"`) exactly as in the source.
* Do not translate brand or technical tokens: `RustDesk`, `Socks5`, `TLS`, `UAC`, `Wayland`, `X11`, `TCP`, `UDP`, `2FA`, `RDP`, `D3D`, etc.
* Copy URL values (e.g. `doc_*` keys) verbatim from `en.rs`.

### Adding new keys (feature work)

* New English-text keys use sentence case, not Title Case: `Use ID whitelisting`, **not** `Use ID Whitelisting`. Acronyms (ID, IP, 2FA…) stay uppercase. Legacy Title-Case keys (e.g. `Use IP Whitelisting`) stay as-is — do not rename them.
* Since the key itself is the English display text, a sentence-case key usually needs **no** `en.rs` entry; add one only when the display text must differ from the key (e.g. `*_tip` keys).
* Append each new key to `template.rs` (with `""`) and to every `host/src/lang/*.rs` file (translated, or `""` if unsure; always `""` for `it.rs`), at the end of the list.
