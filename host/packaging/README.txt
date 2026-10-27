========================================================================
 RustDesk 终端被控端 · 使用说明（中文为主，English below）
 RustDesk Terminal Host - User Manual (Chinese first, English follows)
========================================================================

【一、先看这里 / Read this first】

1. 本目录里的文件必须放在一起，不要只拷 rustdesk.exe：
   Windows 需要 rustdesk.exe + 8 个依赖 DLL（缺一个就会报 0xc0000135 无法启动）；
   Linux 需要 rustdesk，其余依赖由发行版提供（见文末“依赖”）。
2. 第一次运行会在 exe 同目录建 data\ 目录，请确保该目录可写。
3. 中继库 relay_bridge.dll / librelay_bridge.so 只有带 --relay-bridge 时才会被加载。

【二、三种启动方式 / Three ways to start】

  rustdesk.exe
      不带参数：转入 Windows 系统托盘后台运行。程序是 GUI 子系统程序，双击时系统根本不会创建
      控制台窗口（不是“隐藏”，而是没有），屏幕上只有任务栏图标。
      No arguments: runs in the Windows notification area. The binary is a GUI-subsystem program,
      so a double-click creates no console window at all - only the notification-area icon.

  rustdesk.exe --headless
      留在控制台：打印设备 ID、固定密码、日志目录，并实时回显事件。按 Ctrl+C 停止。
      注意：从 cmd/PowerShell 启动时输出会挂到该窗口；用资源管理器双击则看不到输出。
      Stays in the console: prints device ID, permanent password, log directory, and tails events.
      The output attaches to the calling cmd/PowerShell window; a double-click shows nothing.

  rustdesk.exe --headless --relay-bridge "wss://<你的域名>/bridge?key=<密钥>"
      控制台 + 中继；把 --relay-bridge 换成不带 --headless 的组合则走托盘 + 中继。
      Console + relay; drop --headless to run the same relay from the tray.

【三、设置固定密码 / Set the permanent password】

  rustdesk.exe --password 你的密码

  不需要已有实例在运行，直接写文件（不再是 IPC 调用）：
  - data\config\RustDesk.toml          加盐后的单向校验值（不可读出）；
  - data\config\permanent-password.txt 明文副本，供横幅/托盘显示用。

  重要：每次有客户端登录都会重新读取 permanent-password.txt 再校验，
        因此运行中改密码后“旧密码立即失效”，不需要重启；
        只有终端横幅上的文字要重启才会更新。
        文件存在时它就是唯一依据，配置里的旧值不再参与校验。

  No running instance is required: the command writes the files directly.
  Every login re-reads permanent-password.txt, so an old password stops working
  immediately after a change - no restart needed. Only the banner text is stale
  until the next start. While the plain-text file exists it is the only authority.

  安全提醒：permanent-password.txt 是明文，配置文件目录属于敏感目录，不要外发或提交。
  Security: permanent-password.txt is plain text; keep the data\config directory private.

【四、连接与中继 / Connecting and the relay】

  同一局域网：手机 ID 填 <被控端 IP>:21118，并在 data\config\RustDesk2.toml 里设置
      [options]
      direct-server = 'Y'

  跨网络（对称 NAT、官方中继不通时）：部署 worker/ 里的 Cloudflare Worker，
  然后在本机运行带 --relay-bridge 的命令；手机 ID 填 wss://<你的域名>。
  - 被控端不需要任何入站端口或端口映射；桥接是出站长连接。
  - 顺序：先起被控端（含 --relay-bridge），再让手机连接。

  Same LAN: phone ID = <host IP>:21118, with direct-server = 'Y' in RustDesk2.toml.
  Across networks: deploy the Cloudflare Worker from worker/, run this host with
  --relay-bridge, and put wss://<your domain> in the phone ID field. No inbound
  port is needed; start the host first, then connect the phone.

【五、托盘菜单 / Tray menu】

  左键或右键单击图标都会弹出菜单，前三行是实时状态，每次打开都重新读取：
      ID: <设备 ID> / 密码: <当前生效的固定密码> / 中继: <服务器>
      “重启” 重新拉起进程；“退出” 停止被控端并移除图标。
  某些会话（无通知区的远程环境）里系统会拒绝加图标，此时程序自动退回无头模式继续服务。

  Left or right click shows the menu; the first three rows are live state.
  "重启" restarts the process, "退出" stops the host. Where the shell refuses a tray
  icon (some remote sessions), the host silently keeps serving without one.

【六、日志与排查 / Logs and troubleshooting】

  日志：data\log\（headless 子目录是服务端日志）。
  启动失败先看这里：DLL 缺失会报 0xc0000135；数据目录不可写会在启动时退出。
  中继连不上：确认 --relay-bridge 的 URL 与密钥，并检查 data\log 里 bridge 相关行。

  Logs: data\log\ (the headless subdirectory is the server log). A missing DLL shows
  up as 0xc0000135; an unwritable data directory makes startup fail. For the relay,
  check the URL/key and the bridge lines in the log.

【七、依赖 / Dependencies】

  Windows（MSYS2 UCRT64，8 个 DLL 全在包内）：
      libvpx-1 / libaom / libyuv / libopus-0 / libstdc++-6 /
      libgcc_s_seh-1 / libwinpthread-1 / libjpeg-8
  Linux：libvpx / libaom / libyuv / libopus / GTK3 / GStreamer / X11 等，由发行版提供。

  Windows: all eight DLLs ship in this archive. Linux: use distribution packages.

【八、退出 / Stopping】

  托盘模式：菜单“退出”。
  控制台模式：Ctrl+C。

  Tray: menu "退出". Console: Ctrl+C.

========================================================================
 版本 / Version: 见 rustdesk --version     项目主页 / Project: 见仓库 README.md
========================================================================
