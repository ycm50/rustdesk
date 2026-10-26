# RustDesk 1.5.1 Windows 纯终端版使用说明

这是 RustDesk 1.5.1 的 Windows 纯终端版本：不包含图形界面和系统托盘。所有操作都在 Windows Terminal、PowerShell 或 cmd 中完成。

支持的主要功能：

- 无头被控端：显示设备 ID、临时密码和固定密码状态；
- 远程终端：连接后操作远端 PowerShell/cmd；
- 文件传输：列目录、下载和上传；
- 端口转发；
- ANSI 真彩半块字符远程画面、键盘和鼠标输入。

## 一、手工组装运行目录

本仓库不再产出便携包（`build.py` 的 `--portable` 已随 Flutter 打包一起移除）。运行编译产物不需要 MSYS2，也不需要 MSVC 或 Rust 工具链，但它**动态链接**了 MSYS2 UCRT64 里的编解码库，所以必须把依赖的 DLL 和 `rustdesk.exe` 放在同一目录（实测 8 个 DLL 缺一不可，合计约 41 MB；CI 的 Windows 产物已经把 exe 与这 8 个 DLL 一起打进 `host-release-windows-x64`）。

```text
rustdesk-terminal\
  rustdesk.exe
  libvpx-1.dll          (直接依赖)
  libaom.dll            (直接依赖)
  libyuv.dll            (直接依赖)
  libopus-0.dll         (直接依赖)
  libstdc++-6.dll       (直接依赖)
  libgcc_s_seh-1.dll    (libstdc++ / libyuv 的传递依赖)
  libwinpthread-1.dll   (libvpx / libaom / libstdc++ 的传递依赖)
  libjpeg-8.dll         (libyuv 的传递依赖)
```

DLL 全部来自 `A:\msys64\ucrt64\bin`。实际依赖集以 `objdump -p` 的 `DLL Name` 递归展开为准；`api-ms-win-*` 不在磁盘上属正常（API Set 由系统解析）。另外 exe 会把自己的 `data\` 建在**所在目录**旁边，所以该目录必须可写。

首次运行后会自动创建：

```text
rustdesk-terminal\data\config\
rustdesk-terminal\data\log\
```

建议从真实终端窗口启动，不要使用会立即关闭标准输入/输出管道的脚本方式测试交互功能。

启动参数只取第一个：`--headless --help` 会**直接启动被控端**而不是打印帮助（第二个参数被忽略），查帮助请用 `--help` 或 `-h`。

## 二、启动被控端

### 运行前提

- Windows 10/11 x64；
- `rustdesk.exe` 同目录有 MinGW 运行库 DLL（见第一节）；
- 用真实终端窗口运行，并保持窗口打开；
- 被控端需要管理员权限（见下）。

### 必须用管理员权限启动（重要）

被控端**必须以管理员身份运行**，否则远端只能看到画面、无法控制鼠标键盘。

原因：RustDesk 的鼠标键盘注入走 Windows `SendInput`，非提权进程在这台机器上会被系统静默丢弃 —— `SendInput` 返回成功，但输入没有任何效果，日志里也不报错，现象就是"能看不能控"。

正确做法：

1. 开始菜单搜索 PowerShell
2. 右键 → **以管理员身份运行**
3. 在提权后的窗口里执行 `.\rustdesk.exe --headless`

启动后横幅会显示当前权限：

```text
  权限       : 管理员（可正常注入远端鼠标键盘）
```

如果显示：

```text
  权限       : 普通用户（远端鼠标键盘会失效，请以管理员身份运行）
```

说明窗口没有提权，远端将无法操控。

### 启动命令

直接运行：

```powershell
.\rustdesk.exe
```

或者显式运行：

```powershell
.\rustdesk.exe --headless
```

启动后会显示类似信息：

```text
RustDesk 1.5.1 终端被控端
  设备 ID    : 371956946
  固定密码   : 已设置（用 `rustdesk --password <密码>` 修改）
  临时密码   : jsdxjk（每次启动随机生成，可选）
  中继服务器 : rs-ny.rustdesk.com:21116
  日志目录   : <程序目录>\data\log
```

按 `Ctrl+C` 停止被控端。当前版本没有托盘图标，关闭终端窗口也会停止进程。

### 两个密码的区别

| 名称 | 含义 | 是否持久 |
|---|---|---|
| 固定密码 | 你用 `--password` 自己设置的那个密码 | 持久保存，重启不变 |
| 临时密码 | 每次启动随机生成的备用密码 | 每次启动都变 |

连接时**用固定密码**即可。横幅里的“临时密码”不是你的密码，它只是另一条可用的随机密码。

### 设置固定密码

```powershell
.\rustdesk.exe --password 你的固定密码
```

成功后输出 `Done!`。之后再启动被控端，横幅会显示 `固定密码 : 已设置`。

固定密码会保存在程序目录下的配置中。配置目录属于敏感目录，请不要公开或上传其中的文件。

验证固定密码是否生效，可以自己连自己：

```powershell
.\rustdesk.exe --file-transfer <设备ID> <你的固定密码> ls C:\
```

### 查询设备 ID

```powershell
.\rustdesk.exe --get-id
```

### 查看帮助和版本

```powershell
.\rustdesk.exe --help
.\rustdesk.exe --version
```

## 三、远程桌面画面、键盘和鼠标

在另一台真实 Windows Terminal、PowerShell 或 cmd 窗口中运行：

```powershell
.\rustdesk.exe --connect <设备ID> <密码>
```

连接后，远端画面会使用 ANSI 真彩色半块字符 `▀` 绘制。上半部分使用前景色，下半部分使用背景色。底部状态栏显示帧率和输出量。

按 `Ctrl+]` 退出画面连接并恢复原终端。

### 颜色设置

默认使用真彩色。如果终端不支持真彩色，可以使用 256 色：

```powershell
$env:RUSTDESK_TERM_256 = '1'
.\rustdesk.exe --connect <设备ID> <密码>
```

### 鼠标说明

当前支持：

- 左键、右键、中键；
- 滚轮；
- 鼠标移动和拖动；
- 根据终端单元格和远端画面尺寸进行坐标换算。

不同 DPI、窗口缩放、全屏和失焦场景可能产生坐标偏差，建议实际使用时确认。

## 四、远程终端

```powershell
.\rustdesk.exe --terminal <设备ID> <密码>
```

连接后可以操作远端 shell。按 `Ctrl+]` 退出。

交互终端必须在真实控制台中运行。直接使用类似下面的管道方式时，标准输入会立即 EOF，远端 shell 可能在回显命令前收到退出指令：

```powershell
'echo hello' | .\rustdesk.exe --terminal <设备ID> <密码>
```

## 五、文件传输

### 列出远端目录

```powershell
.\rustdesk.exe --file-transfer <设备ID> <密码> ls <远端目录>
```

### 下载文件

第一个路径是远端源文件，第二个路径是本地目标文件：

```powershell
.\rustdesk.exe --file-transfer <设备ID> <密码> get <远端文件> <本地文件>
```

例如：

```powershell
.\rustdesk.exe --file-transfer 371956946 test1234 get `
  'C:\Users\Public\中文 文件.txt' `
  'C:\Temp\下载文件.txt'
```

### 上传文件

第一个路径是本地源文件，第二个路径是远端目标文件：

```powershell
.\rustdesk.exe --file-transfer <设备ID> <密码> put <本地文件> <远端文件>
```

例如：

```powershell
.\rustdesk.exe --file-transfer 371956946 test1234 put `
  'C:\Temp\上传文件.txt' `
  'C:\Users\Public\上传文件.txt'
```

已验证空文件、二进制文件、中文文件名和包含空格的文件名上传。

## 六、端口转发

```powershell
.\rustdesk.exe --port-forward <设备ID> <密码> <本地端口> <远端主机> <远端端口>
```

例如：

```powershell
.\rustdesk.exe --port-forward 371956946 test1234 18081 127.0.0.1 18080
```

之后访问本机 `127.0.0.1:18081`，流量会转发到远端 `127.0.0.1:18080`。

## 七、配置、日志和环境变量

默认配置和日志都放在程序目录：

```text
<程序目录>\data\config\RustDesk.toml
<程序目录>\data\config\RustDesk2.toml
<程序目录>\data\log\
```

如果程序目录不可写，会回退到系统用户目录。

### 指定配置目录

```powershell
$env:RUSTDESK_CONFIG_DIR = 'D:\RustDeskData'
.\rustdesk.exe --headless
```

### 强制使用 256 色

```powershell
$env:RUSTDESK_TERM_256 = '1'
```

## 八、从源码构建（Windows / MSYS2 UCRT64）

`host/` 用 MSYS2 UCRT64 工具链构建，不使用 MSVC，也不使用 vcpkg：libvpx、libaom、libyuv、libopus 都来自 UCRT64 系统包，通过 pkg-config 链接（feature `system-libs`）。

### 1. 安装依赖

在 MSYS2 的 UCRT64 shell 中：

```bash
pacman -S mingw-w64-ucrt-x86_64-{gcc,pkgconf,libvpx,aom,libyuv,opus,nasm,cmake,clang}
```

`clang` 提供 bindgen 需要的 `libclang.dll`；`nasm` / `cmake` 供个别 `-sys` crate 使用。

### 2. 设置环境（PowerShell）

下面的 `A:\msys64` 按本机实际安装位置替换：

```powershell
$env:PATH='A:\msys64\ucrt64\bin;A:\msys64\usr\bin;A:\msys64\clang64\bin;'+$env:PATH
$env:TMP='A:\Downloads\rustdesk\.tmpbuild'; $env:TEMP=$env:TMP
New-Item -ItemType Directory -Force $env:TMP | Out-Null
$env:LIBCLANG_PATH='A:\msys64\clang64\bin'
$env:PKG_CONFIG_PATH='A:\msys64\ucrt64\lib\pkgconfig'
$env:CC='gcc'; $env:CXX='g++'
```

- `TMP` / `TEMP` 必须指到工作区或其它可写目录，否则 gcc 可能以 `0xc0000409` 退出。
- `LIBCLANG_PATH` 必须指向 `libclang.dll` 所在目录，bindgen 依赖它生成绑定。
- `PKG_CONFIG_PATH` 必须指向 UCRT64 的 `pkgconfig`，否则 pkg-config 找不到 libvpx / aom / libyuv / opus 的 `.pc`。

### 3. 构建

```powershell
cd A:\Downloads\rustdesk
& 'A:\msys64\ucrt64\bin\cargo.exe' build --release --manifest-path host/Cargo.toml --features system-libs
```

产物是 `host\target\release\rustdesk.exe`。旧 flag `linux-pkg-config` 仍可用，它是 `system-libs` 的别名；新脚本统一写 `system-libs`。

### 4. 复制运行库 DLL

MinGW 运行库不会静态链进 exe，必须和 `rustdesk.exe` 放在同一目录：

```text
libstdc++-6.dll
libgcc_s_seh-1.dll
libwinpthread-1.dll
```

从 `A:\msys64\ucrt64\bin` 复制。若 exe 还依赖别的 DLL，用 `objdump -p` 或 `ldd` 查看后按实际输出补齐（待验证）。

### 5. FFmpeg（只有 hwcodec 需要）

默认构建不需要 FFmpeg。需要硬件编解码（`hwcodec`）时先运行：

```powershell
host\scripts\fetch-ffmpeg.ps1
```

它按平台/架构下载 BtbN/FFmpeg-Builds 的开源预构建件，不本地编译，也不用 vcpkg。脚本的下载地址与放置目录以脚本自身为准（本次未运行，待验证）。

## 九、安全和生命周期

- Release 程序未签名，首次运行可能触发 Windows SmartScreen 提示；
- 固定密码保存在配置文件中，请保护 `data\config`；
- 临时密码在被控端每次启动时重新生成；
- 没有托盘图标，使用 `Ctrl+C` 或关闭控制台停止被控端；
- `--tray` 参数会被接受但不执行任何托盘操作；
- 断线后当前版本不会自动重连；
- 远程光标目前不绘制，终端光标在画面模式下隐藏。

## 十、常见问题

### 手机或另一台电脑能看画面，但点不动、打不了字

**几乎都是被控端没有以管理员身份运行。**

RustDesk 的鼠标键盘注入走 Windows `SendInput`，非提权进程发出的输入会被系统静默丢弃：`SendInput` 返回成功、日志无报错、画面正常推送，但远端鼠标键盘完全没有反应。

解决办法：用"以管理员身份运行"的 PowerShell 启动被控端，横幅会显示：

```text
  权限       : 管理员（可正常注入远端鼠标键盘）
```

已实测确认：同一个 ID、同一台手机，非提权启动时完全无法操控；提权启动后立刻正常。

如果需要进一步自检当前环境能否注入输入，可以用一个 Windows `SendInput` 测试脚本（不在本仓库）移动光标，观察结果：

- `extra=0 : MOVED` 但 `extra=100 : NO MOVE` → 输入被安全策略按标记过滤
- 两者都是 `NO MOVE` → 该窗口没有注入权限，请提权

### 电脑光标动了，但手机上对不上

Windows 截屏（DXGI）默认不包含鼠标指针，指针由客户端自己绘制。若手机端绘制位置与实际位置有偏差，属于客户端坐标映射问题，不影响实际点击落点。

### 设置固定密码后，横幅里显示的密码不是我的

横幅里有两个密码：

- **固定密码**：你用 `--password` 设置的，持久保存
- **临时密码**：每次启动随机生成，是备用的另一条密码

连接时用固定密码即可。

## 十一、已知限制

- 画面帧率取决于画面内容、CPU 和终端尺寸，没有固定值；
- 当前一次显示一个远端画面，没有显示器切换器；
- 暂不支持音频、剪贴板、摄像头、打印机和 RDP；
- 鼠标在 DPI、全屏、窗口比例变化和失焦释放场景下需要人工确认；
- 不支持自动重连；
- 构建不启用任何 UI 相关 feature，终端构建也不会启动图形界面；但上游共用源文件里可能仍留有 UI 条件编译分支。
