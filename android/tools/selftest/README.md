# JVM 自测（不需要手机，不需要 Gradle）

本目录只做一件事：用**桌面 JDK** 把工程里与 Android API 无关的 Java 代码和迁进仓库的回归套件编出来跑完，
逐套件打印检查数，最后汇总总检查数。全程不联网（除非显式 `-IncludeNetwork`）、不跑 Gradle、不改工程里的任何
`.java` 文件。

| 文件 | 作用 |
| --- | --- |
| `run-selftest.ps1` | 唯一入口：找 JDK / 找 jar / 依赖分组编译 / 跑套件 / 汇总 |
| `README.md` | 本说明 |
| `suites\` | 已迁入仓库的 JVM 测试源码（10 个套件 + 1 个辅助类，含要真服务器的 `HostEndToEnd`），清单见 `suites\README.md` |

## 快速开始

```powershell
cd A:\Downloads\rustdesk\android
pwsh -NoProfile -File .\tools\selftest\run-selftest.ps1
```

当前代码的完整输出（时间会浮动）：

```text
RustDesk Java self-test (offline: no phone, no Android SDK, no Gradle)
  jdk: A:\jdk-17.0.12
  suites: A:\Downloads\rustdesk\android\tools\selftest\suites
  out: A:\Downloads\tmpscripts\selftest\out

compile A (protobuf only): 12 sources (9.68s)
compile B (protobuf + bcprov): 32 sources (9.51s)

[OK] TerminalMsgTest (0.19s) checks: 73
[OK] ClipSyncTest (0.16s) checks: 11
[OK] FileMsgTest (0.18s) checks: 75
[OK] KbSelfTest (0.15s) checks: 7
[OK] IdCheckTest (0.08s) checks: 14
[OK] crypto.SelfTest (0.28s) checks: 51
[OK] HostSessionTest (0.70s) checks: 49
[OK] PeerHandshakeInteropTest (0.37s) checks: 31
[OK] Ed25519Test (0.26s) checks: 22
[OK] LoopbackTest (0.33s) checks: 14

SUITE SUMMARY
SUITE                      STATUS    TIME(s)  CHECKS  FAILURE
-------------------------- ------- --------- -------  -------
TerminalMsgTest            PASS         0.19      73  -
ClipSyncTest               PASS         0.16      11  -
FileMsgTest                PASS         0.18      75  -
KbSelfTest                 PASS         0.15       7  -
IdCheckTest                PASS         0.08      14  -
crypto.SelfTest            PASS         0.28      51  -
HostSessionTest            PASS         0.70      49  -
PeerHandshakeInteropTest   PASS         0.37      31  -
Ed25519Test                PASS         0.26      22  -
LoopbackTest               PASS         0.33      14  -

TOTAL: 347 checks in 10 suites
RESULT: 10/10 PASSED
```

退出码 `0`。任一套件失败时退出码 `1`，并在 `RESULT:` 前打印失败套件的完整输出；参数/路径/依赖错误退出码 `2`。

需要真机对端时才加：

```powershell
pwsh -NoProfile -File .\tools\selftest\run-selftest.ps1 `
    -IncludeNetwork -PeerId 123456789 -Password secret `
    -Server rs-ny.rustdesk.com
```

## 编译分组

套件按依赖分成两组，整轮只跑 **2 个 javac 进程**（不是一个套件一个）：

| 组 | classpath | 套件 | 编译的工程源码 |
| --- | --- | --- | --- |
| A | protobuf | TerminalMsgTest、ClipSyncTest、FileMsgTest、KbSelfTest、IdCheckTest（+ 辅助类 `PeerIdRules`） | `hbb` + `session` 下 4 个纯消息类 |
| B | protobuf + bcprov | crypto.SelfTest、HostSessionTest、PeerHandshakeInteropTest、Ed25519Test、LoopbackTest | `hbb` + `crypto` + `net` + `session`（排除 `Session.java`、`DeviceIdentity.java`）+ `host` 下 5 个纯 JVM 类（`HostSession`、`HostInputSink`、`HostClipboardSink`、`HostRegistration`、`HostAgent`） |

`LoopbackTest` 属于 B 组：它用 BouncyCastle 的 `Ed25519Signer` 给假被控端签 `SignedId`，不能只靠 protobuf 编译。
组 A 只编它用到的工程源码，所以是真正的「只需 protobuf」组。

## 参数

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `-IncludeNetwork` | 关 | 追加运行 `RelayTest`（真实 rendezvous/relay + 在线被控端） |
| `-Server` | `rs-ny.rustdesk.com` | 仅 `RelayTest` 使用 |
| `-PeerId` | 空 | `-IncludeNetwork` 时必填，缺失直接报错退出（退出码 2） |
| `-Password` | `123456` | 仅 `RelayTest` 使用；传空串则省略该参数，由测试自身决定 |
| `-JdkHome` | `A:\jdk-17.0.12` | 必须是含 `bin\javac.exe` 的 JDK；不存在时回退 `$env:JAVA_HOME` |
| `-GradleCache` | `%USERPROFILE%\.gradle\caches\modules-2\files-2.1` | 两个 jar 的搜索根；可用 `-GradleCache` 覆盖 |
| `-TmpRoot` | 工程目录上两级的 `tmpscripts`（即 `A:\Downloads\tmpscripts`） | 临时产物根目录（`RelayTest` 源码、编译输出） |
| `-ProbeDir` | `<TmpRoot>\rdprobe` | **只**用于 `RelayTest.java` 的查找（唯一还留在 tmpscripts 的套件） |
| `-OutDir` | `<TmpRoot>\selftest\out` | `javac -d` 输出根；下面按组建 `A\`、`B\` 子目录；**每次运行前整体删除** |
| `-TimeoutSeconds` | `120` | javac 与每个套件进程的超时 |

脚本要求 PowerShell 7+（`#Requires -Version 7.0`，用 `pwsh` 而不是 `powershell`）。

## 检查数怎么统计

- 套件自己打印 `checks: N` 的（TerminalMsgTest、FileMsgTest、crypto.SelfTest、HostSessionTest、
  PeerHandshakeInteropTest、Ed25519Test）直接取该值；
- 老式套件（ClipSyncTest、KbSelfTest、IdCheckTest、LoopbackTest）只逐项打印 `[PASS]`/`[FAIL]`，
  脚本数 `[PASS]` 行作为检查数。
- 两者都不会把失败算成通过：判定通过仍要求退出码 `0` + `PassPattern` 命中 + 输出里没有 `[FAIL]` 行。

## 它验什么

### 组 A（只需 protobuf）

#### `TerminalMsgTest`（73 项）

`com.rustdesk.java.session.TerminalMessages` 的终端协议消息：open/data/resize/close/response/error 的
字段级往返，含中文、控制字节、ANSI 转义序列。

#### `ClipSyncTest`（11 项）

`ClipboardMessages`：Text/Html/MultiClipboards 的序列化/解析、空文本、压缩剪贴板拒绝、非 Text 内容返回 null。

#### `FileMsgTest`（75 项）

`FileMessages`：目录/文件/传输请求与响应的字段级往返，以及 `describe()` 的可读描述；含中文路径与文件名。

#### `KbSelfTest`（7 项）

`InputMessages`：控制键按下、win2win 热键打包、文本输入序列。

#### `IdCheckTest`（14 项）

`PeerIdRules.isValidPeerId`（与 `MainActivity` 同规则）：9/10 位数字 ID、IPv4、IPv4:port、`[IPv6]:port`、
非法输入与前缀空白裁剪。

### 组 B（protobuf + bcprov）

#### `crypto.SelfTest`（51 项）

工程内的 `com.rustdesk.java.crypto` 自测：v0 线上密文向量、v1 split 的 4 组官方密钥向量、`Encrypt` 往返/篡改
拒绝、`Sodium` box 往返与篡改拒绝、BLAKE2b 向量。

#### `HostSessionTest`（49 项）

手写 viewer 驱动 `host.HostSession`：host-first `SignedId`、签名校验、公钥回复、stream key 解封、双向 split
加密流、密码 challenge、登录响应、输入回环等。

#### `PeerHandshakeInteropTest`（31 项）

仓库自己的 viewer 握手（`PeerHandshake.connect`）↔ 仓库自己的 host（`HostSession`）互通；覆盖此前会互相等待
死锁的配对、签名/身份校验与后续消息。

#### `Ed25519Test`（22 项）

`crypto.Ed25519`：libsodium 布局（`signature(64) || message`）、签名/验签往返、篡改拒绝、密钥派生、
RFC 8032 固定向量、参数校验。

#### `LoopbackTest`（14 项）

`127.0.0.1` 随机端口上的假被控端 + 真实 viewer 路径：`SignedId` 验签、`PublicKey` 回复、stream key 解封、
双向 split 加密、密码摘要、`LoginResponse` 解析、鼠标输入加密回环。

### `RelayTest`（仅 `-IncludeNetwork`，仍在 `tmpscripts\rdprobe`）

真实 rendezvous 解析 → relay 连接 → 握手 → 登录 → 收视频帧（或打印失败原因）。
它本身不 `SystemExit(1)`，因此脚本以输出中的 `RESULT: RELAY SESSION WORKS` 作为通过标记。
网络/对端状态不可控，默认不跑。

## 它不验什么

- **Android UI**：`ui\*`（Activity、View、TextureView、手势）完全不编译。
- **MediaCodec / 解码**：`codec\VideoDecoder` 及其 `Surface` 输出不编译。
- **`session.Session` 编排逻辑**：它 import 了 `VideoDecoder`，属于 Android 依赖链，被排除。
- **`DeviceIdentity` 持久化**：依赖 `SharedPreferences`，被排除。
- **Android 构建链**：不跑 Gradle/AGP/R8，不产出 APK。
- **设备行为**：权限、生命周期、网络策略、输入注入的真实效果。
- **性能/压力/内存**：只验证协议与算法正确性。

## 被排除的文件（以及原因）

工程里与 Android API 耦合、无法在无 `android.jar` 的桌面 JVM 上编译的文件：

| 文件 | 排除原因 |
| --- | --- |
| `com\rustdesk\java\session\Session.java` | import `codec.VideoDecoder` → `android.media.MediaCodec` 依赖链 |
| `com\rustdesk\java\session\DeviceIdentity.java` | `android.content.Context` / `SharedPreferences` |
| `com\rustdesk\java\codec\VideoDecoder.java` | `android.media.MediaCodec` / `MediaFormat` / `android.view.Surface` |
| `com\rustdesk\java\host\HostFrameSource.java` | `android.util.Log`，且依赖 `HostCaptureCodec` / `GlFrameUploader` |
| `com\rustdesk\java\host\InputInjectorSink.java` | `android.util.Log` + 无障碍 `InputInjector` |
| `com\rustdesk\java\host\GlFrameUploader.java` | `android.opengl.*` |
| `com\rustdesk\java\host\HostCaptureCodec.java` | `android.media.MediaCodec` |
| `com\rustdesk\java\host\HostConfig.java` | `android.content.Context` / `SharedPreferences` |
| `com\rustdesk\java\host\InputInjector.java` | 无障碍服务 / `android.view.KeyEvent` |
| `com\rustdesk\java\host\ScreenCaptureService.java` | `MediaProjection` / `Service` / `Notification` |
| `com\rustdesk\java\ui\*` | `android.view` / `android.graphics` / Activity |
| `com\rustdesk\java\MainActivity.java` | `android.app.Activity` 入口 |

`codec\*`、`ui\*`、`MainActivity.java` 不在任何组的扫描目录里；`session\Session.java`、`session\DeviceIdentity.java`
是「在扫描目录里、但被显式排除」的（排除表在脚本的 `$groups.B.Excluded`）。组 B 从 `host` 目录只取 5 个纯
JVM 文件（`HostSession`、`HostInputSink`、`HostClipboardSink`、`HostRegistration`、`HostAgent`），其余
`host\*`（`HostFrameSource`、`HostCaptureCodec`、`GlFrameUploader`、`HostConfig`、`InputInjector`、
`InputInjectorSink`、`ScreenCaptureService`）不编。

## 加新套件

1. 把 `.java` 放进 `tools\selftest\suites\`，包名写 `com.rustdesk.java.selftest`，并在第 1 行加来源注释；
   文件必须是纯 ASCII（中文用 `\uXXXX`）。
2. 在 `run-selftest.ps1` 的 `$suites` 表加一行：

```powershell
[pscustomobject]@{
    Group       = 'A'                             # A = 只需 protobuf；B = protobuf + bcprov
    Name        = 'MyTest'                        # 显示名
    Main        = 'com.rustdesk.java.selftest.MyTest'  # 主类
    Source      = Join-Path $SuiteDir 'MyTest.java'    # 套件源码；工程内类写 $null
    Network     = $false                          # $true 时只有 -IncludeNetwork 才编译并运行
    Arguments   = { @() }                         # 运行参数，数组
    PassPattern = '(?m)^RESULT: ALL PASSED\s*$'   # 输出里必须匹配的正则
}
```

3. 若测试引用了同目录的辅助类（如 `PeerIdRules.java`），把它加进对应组的 `HelperSources`；
   若引用 `host` 下另一个纯 JVM 类，加进该组的 `ProjectFiles`；若需要新 jar，在脚本「jars」段和组
   `Jars` 里加解析（没配就会编译失败，这是有意的）。
4. 在 `suites\README.md` 的清单里补一行（文件 / 检查项数 / 依赖 jar / 来源）。

判定通过的三个条件：退出码 `0`、输出匹配 `PassPattern`、输出中没有 `[FAIL]` 行。

## 幂等性与日志

- 每次运行先 `Remove-Item -Recurse` 整个 `OutDir`（并拒绝删除盘符根/工程根），再重建 `A\`、`B\` 子目录；
  因此可以无限重复执行，不会残留半成品 class 或旧日志。
- 每个套件的完整 stdout+stderr 存到 `<OutDir>\logs\<套件名>.log`（UTF-8），成功时只打印一行汇总，
  失败时会把该套件的输出原样回显。
- 退出码：`0` 全过；`1` 编译失败或套件失败；`2` 参数/路径/依赖错误。
- 不写 Gradle 缓存、不碰工程源码；`javac -d` 只写入 `OutDir`。

## 依赖

1. JDK 17（默认 `A:\jdk-17.0.12`，否则 `$env:JAVA_HOME`）。
2. Gradle 缓存里的两个 jar（由任意一次 Gradle/Android Studio sync 产生）：
   - `protobuf-javalite-3.20.1.jar`
   - `bcprov-jdk18on-1.79.jar`

找不到 jar 时脚本会打印搜索根并退出码 2；用 `-GradleCache <dir>` 指向别的缓存根即可。

## 迁移说明

`suites\` 里的测试原先只在仓库外的 `A:\Downloads\tmpscripts\`（未入 git）。迁移时保留了测试逻辑与断言，
只加来源注释、改包名、把非 ASCII 字符转成 `\uXXXX`。依赖 `android.jar` 的测试没有迁入，清单与原因见
`suites\README.md`。`tmpscripts` 里的原件未删除。
