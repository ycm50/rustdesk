# 本分支改动说明（mobile input / 隧道会话）

本仓库在官方 RustDesk 之上做了一组用于「手机通过自建 WebSocket 隧道远控 Windows 主机」的改动，
并把 `libs/hbb_common` 从 git submodule 改为随仓库提交的普通目录，保证克隆后可直接编译。

## 1. 手机端输入（`flutter/lib/mobile/input/gesture/`）

| 能力 | 实现 |
| --- | --- |
| 双指捏合缩放 | 用两指间距变化驱动 `canvasModel.updateScale`，围绕两指焦点缩放 |
| 双指滚动 | 两指同向移动换算为滚轮，方向与官方浮窗的「向上/向下」按钮一致 |
| 捏合/滚动判别 | 间距变化大于焦点位移判为捏合，否则判为滚动，与视图缩放倍率无关 |
| 手势期间隐藏光标 | 双指手势进行时隐藏绘制的虚拟鼠标，手势结束后恢复 |
| 长按右键 | 长按后原地抬起发送右键 |
| 长按后拖动 | 保持左键按下并移动，等价于真实鼠标拖动框选文本 |
| 点哪到哪 | 单击直接落在触点对应的远端坐标 |

回归测试：`flutter/test/mobile_input_follow_cursor_test.dart`（`flutter test` 单文件运行）。

## 2. 手机端 PC 键盘面板（`flutter/lib/mobile/input/keyboard/`）

在会话工具栏新增键盘按钮，展开 PC 布局键盘与快捷键面板；面板展开时与系统输入法互斥，
避免两侧输入框争抢焦点。

## 3. 隧道会话（`wss://` 直连）

- 客户端：`src/client.rs` 识别 `wss://` 端点直接建连；端点可携带 `?password=...`，
  口令写入 `shared_password` 槽位，使哈希回复走官方的两段式
  `sha256(sha256(password || salt) || challenge)`。
- 主机端：`src/rendezvous_mediator.rs` 的 `direct_access_stream` 在直连端口上窥探首包，
  发现 `GET ... Upgrade: websocket` 时切换为 WebSocket 帧解析，其余仍按原协议处理。
- `libs/hbb_common`：新增 `WsFramedStream::new_with_headers`（升级请求附加自定义头，
  用于 Cloudflare Access 之类需要服务令牌的隧道）与 `WsFramedStream::from_tcp`
  （在被控端接受隧道转发的升级请求）。
- `tunnel-password`：本地选项，默认空。设置后，若主机端永久密码存储无法解码，
  可按该明文口令校验对端回复，用于自建隧道场景。

## 4. 桌面端

- 安装界面（`flutter/lib/desktop/pages/install_page.dart`）安装路径输入框可编辑，
  不再只能依赖系统文件夹选择器。
- 设置永久密码对话框不再校验密码强度格式，允许任意形式密码。

## 5. Windows 构建

`scripts/windows/build-all.ps1` 分阶段构建 Windows 桌面端、无界面 CLI 被控端与 Android 产物，
逐阶段打印实时输出、计时并把日志写入 `logs/`。脚本中的绝对路径来自开发机布局，
迁移时按需修改；`.cmd` 为双击入口。

## 已知限制

- `tunnel-password` 是本地明文口令钩子，只应在自建隧道、且主机永久密码存储不可用时启用。
- 子模块已内置，后续同步上游需手工合并 `libs/hbb_common`。
