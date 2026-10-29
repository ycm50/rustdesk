# 迁入仓库的 JVM 回归套件

这批测试原来只散落在仓库外的 `A:\Downloads\tmpscripts\`（未入 git，清理临时目录就丢）。
现在源码已迁进本目录，可以直接提交、重复运行；`tmpscripts` 里的原件保留未删。

迁移约定：

- 包名统一为 `com.rustdesk.java.selftest`，避免与 `com.rustdesk.java.*` 的 app 源码冲突；文件名保持不变。
- 只迁**纯 JVM**（不 import `android.*`）的测试；需要 `android.jar` 的仍留在 `tmpscripts`。
- 测试逻辑与断言与原件逐字一致，只做三处改动：文件头一行来源注释、`package` 行、把非 ASCII 字符写成
  `\uXXXX`（本目录所有文件都是纯 ASCII）。
- 每个文件第 1 行注释就是原件的绝对路径，便于追溯。
- `PeerIdRules.java` 是 `IdCheckTest` 引用的辅助类（不是套件），随测试一起迁入。

## 套件清单

| 文件 | 检查项数 | 依赖 jar | 来源（`tmpscripts` 下的原件） |
| --- | ---: | --- | --- |
| `TerminalMsgTest.java` | 73 | protobuf | `terminal\TerminalMsgTest.java` |
| `ClipSyncTest.java` | 11 | protobuf | `clip\ClipSyncTest.java` |
| `FileMsgTest.java` | 75 | protobuf | `filemsg\FileMsgTest.java` |
| `KbSelfTest.java` | 7 | protobuf | `kb\KbSelfTest.java` |
| `IdCheckTest.java` | 14 | 无（纯 Java） | `main\IdCheckTest.java` |
| `VideoSettingsTest.java` | 62 | protobuf | 本仓库新增（无原件） |
| `TextInputDiffTest.java` | 3 | 无（纯 Java） | 本仓库新增（无原件） |
| `PeerIdRules.java` | — （辅助类） | 无（纯 Java） | `main\gen\PeerIdRules.java` |
| `HostSessionTest.java` | 49 | protobuf + bcprov | `hostsess\HostSessionTest.java` |
| `TransportOrderTest.java` | 6 | protobuf + bcprov | 本仓库新增（无原件） |
| `PeerHandshakeInteropTest.java` | 31 | protobuf + bcprov | `hostsess\PeerHandshakeInteropTest.java` |
| `Ed25519Test.java` | 22 | protobuf + bcprov | `hostsess\Ed25519Test.java` |
| `LoopbackTest.java` | 14 | protobuf + bcprov | `rdprobe\LoopbackTest.java` |
| `HostEndToEnd.java` | 随服务器/对端而定（默认不跑） | protobuf + bcprov | `e2e\HostEndToEnd.java` |

本目录内 12 个离线套件合计 **367** 项检查；加上工程自带的 `crypto.SelfTest`（51 项）共 **418** 项。
`HostEndToEnd` 要真实 rendezvous 服务器，只有 `-IncludeNetwork` 才编译运行，不计入 415。

## 编译分组（`run-selftest.ps1`）

| 组 | classpath | 套件 | 工程源码 |
| --- | --- | --- | --- |
| A | protobuf | TerminalMsgTest、ClipSyncTest、FileMsgTest、KbSelfTest、IdCheckTest、VideoSettingsTest、TextInputDiffTest（+ `PeerIdRules`） | `hbb` + `session` 下 4 个纯消息类 + `VideoSettings` + `ui/TextInputDiff` |
| B | protobuf + bcprov | crypto.SelfTest、HostSessionTest、TransportOrderTest、PeerHandshakeInteropTest、Ed25519Test、LoopbackTest | `hbb` + `crypto` + `net` + `session`（排除 `Session.java`、`DeviceIdentity.java`）+ `host` 下 5 个纯 JVM 类（`HostSession`、`HostInputSink`、`HostClipboardSink`、`HostRegistration`、`HostAgent`） |

`LoopbackTest` 归到 B 组：它自己用 BouncyCastle 的 `Ed25519Signer` 给假被控端签 `SignedId`，
无法只靠 protobuf 编译。

## 没有迁入的测试及原因

| 文件 | 原因 |
| --- | --- |
| `keywire\probe\Probe.java` | import `android.view.KeyEvent` 并用其构造器，必须 `android.jar` |
| `keymap\KeyEventMapperTest.java` | 依赖 `com.rustdesk.java.ui.KeyEventMapper` → `android.view.KeyEvent`；只能靠 `keymap\fake` 里手写的假 `KeyEvent` 编译 |
| `codec\smoke\HostCaptureCodecSmoke.java` | 被测类 `HostCaptureCodec` 依赖 `android.media.MediaCodec` 等，运行时需要 `android.jar` |
| `sess\test-src\...\SessionSelfTest.java` | import `codec.VideoDecoder` → `android.media.MediaCodec` |
| `rdprobe\RelayTest.java` | 需要真实 rendezvous/relay 与在线对端，仍是 `tmpscripts` 里的网络套件（`-IncludeNetwork` 时才编译运行） |
| `rdprobe\` 其余 `RawProbe*.java`、`DirectTest.java`、`FrameDump.java`、`KeyCheck.java`、`RendezvousProbe.java` | 一次性手工探针，没有断言，不适合当回归套件 |
| `hostsess\mut-*\HostSession.java` | 变异测试用的 HostSession 副本，不是测试 |
| `filemsg\DescribeDemo.java`、`main\probe\Probe.java`、`reg\*` | 演示/探针脚本 |
| `viewmath\ViewMath.java` | 纯 JVM 但不在本次迁移清单内（可作为后续候选） |
