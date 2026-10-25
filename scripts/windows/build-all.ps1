<#
  一键构建 RustDesk 定制版（Windows 桌面端 + 无界面 CLI 被控端 + Android 手机端）
  ---------------------------------------------------------------------------
  用法（在 PowerShell 里）：
    pwsh -File A:\Downloads\tmpscripts\build-all.ps1
    pwsh -File A:\Downloads\tmpscripts\build-all.ps1 -SkipAndroid
    pwsh -File A:\Downloads\tmpscripts\build-all.ps1 -SkipWindows
    pwsh -File A:\Downloads\tmpscripts\build-all.ps1 -SkipApk -InstallApk

  参数：
    -SkipWindows   不编译 Windows（CLI 被控端 + 桌面端）
    -SkipCliHost   只跳过无界面 CLI 被控端
    -SkipAndroid   不编译 Android（Rust 库 + APK）
    -SkipApk       编 Android 的 Rust 库，但不打 APK（APK 走 Gradle）
    -InstallApk    编完 APK 后用 adb 覆盖安装到手机
    -Jobs N        cargo 并行任务数，默认 6

  产物：
    A:\rustdesk\rustdesk.exe                      无界面 CLI 被控端
    A:\rustdesk\desktop\rustdesk.exe              桌面端（Flutter + librustdesk.dll）
    ...\flutter\build\app\outputs\flutter-apk\app-debug.apk   手机端 APK

  日志：A:\Downloads\tmpscripts\logs\build-all-<时间戳>.log（每个阶段另有单独日志）
#>
param(
    [switch]$SkipWindows,
    [switch]$SkipCliHost,
    [switch]$SkipAndroid,
    [switch]$SkipApk,
    [switch]$InstallApk,
    [int]$Jobs = 6
)

$ErrorActionPreference = 'Continue'
$Root = 'A:\Downloads\rustdesk-src'
$Tmp = 'A:\Downloads\tmpscripts'
$VcpkgLib = 'A:\Downloads\vcpkg\installed\x64-windows-static\lib'
$HostDir = 'A:\rustdesk'
$DesktopDir = 'A:\rustdesk\desktop'
$ApkPath = Join-Path $Root 'flutter\build\app\outputs\flutter-apk\app-debug.apk'
$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$LogDir = Join-Path $Tmp 'logs'
New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
$MasterLog = Join-Path $LogDir "build-all-$Stamp.log"
$BuildStart = Get-Date
$Failed = @()

function Say {
    param([string]$Msg, [string]$Color = 'Gray')
    $line = "[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Msg
    Write-Host $line -ForegroundColor $Color
    Add-Content -Path $MasterLog -Value $line
}

function Banner {
    param([string]$Title)
    Say ''
    Say ('=' * 74)
    Say $Title 'Cyan'
    Say ('=' * 74)
}

# 运行一个阶段：实时把子进程输出打到屏幕，同时写入该阶段日志
function Step {
    param(
        [string]$Name,
        [string]$Command,
        [string]$StageLog
    )
    Say ("执行命令: {0}" -f $Command) 'DarkGray'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & cmd.exe /c $Command 2>&1 | Tee-Object -FilePath $StageLog | Out-Host
    $code = $LASTEXITCODE
    $sw.Stop()
    if ($null -eq $code) { $code = 0 }
    Say ("阶段 [{0}] 退出码={1}  用时={2:n1}s  日志={3}" -f $Name, $code, $sw.Elapsed.TotalSeconds, $StageLog) $(if ($code -eq 0) { 'Green' } else { 'Red' })
    return $code
}

function StepBlock {
    param(
        [string]$Name,
        [string]$StageLog,
        [scriptblock]$Body
    )
    Say '执行: <内联命令，输出实时打印到屏幕>' 'DarkGray'
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $script:StepCode = 0
    & $Body | Tee-Object -FilePath $StageLog | Out-Host
    $code = $script:StepCode
    $sw.Stop()
    Say ("阶段 [{0}] 退出码={1}  用时={2:n1}s  日志={3}" -f $Name, $code, $sw.Elapsed.TotalSeconds, $StageLog) $(if ($code -eq 0) { 'Green' } else { 'Red' })
    return $code
}

function Show-Artifact {
    param([string]$Path, [string]$Label)
    if (Test-Path $Path) {
        $f = Get-Item $Path
        $fresh = $f.LastWriteTime -ge $BuildStart
        $mark = if ($fresh) { '新' } else { '未更新!' }
        Say ("  {0,-28} {1,10:n2} MB  {2}  [{3}]" -f $Label, ($f.Length / 1MB), $f.LastWriteTime.ToString('MM-dd HH:mm:ss'), $mark) $(if ($fresh) { 'Green' } else { 'Yellow' })
    }
    else {
        Say ("  {0,-28} 缺失!" -f $Label) 'Red'
    }
}

Banner "RustDesk 定制版 · 一键构建"
Say ("开始时间 : {0}" -f $BuildStart.ToString('yyyy-MM-dd HH:mm:ss'))
Say ("仓库     : {0}" -f $Root)
Say ("总日志   : {0}" -f $MasterLog)
Say ("cargo -j : {0}" -f $Jobs)

# ---------------------------------------------------------------- 环境预检
Banner "[0/6] 环境预检"
$pre = @(
    @{ N = 'vcvars64.bat';  P = 'A:\MSVC\BuildTools\VC\Auxiliary\Build\vcvars64.bat' },
    @{ N = 'MSVC cl.exe';   P = 'A:\MSVC\BuildTools\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64\cl.exe' },
    @{ N = 'flutter';       P = 'A:\flutter\bin\flutter.bat' },
    @{ N = 'cargo';         P = 'A:\cargo\lib\cargo\bin\cargo.exe' },
    @{ N = 'rustup';        P = 'A:\cargo\lib\cargo\bin\rustup.exe' },
    @{ N = 'nasm';          P = 'A:\Downloads\tmpscripts\nasm\nasm-2.16.03\nasm.exe' },
    @{ N = 'msys clang';    P = 'A:\msys64\clang64\bin\clang.exe' },
    @{ N = 'vpx.lib';       P = (Join-Path $VcpkgLib 'vpx.lib') },
    @{ N = 'aom.lib';       P = (Join-Path $VcpkgLib 'aom.lib') },
    @{ N = 'yuv.lib';       P = (Join-Path $VcpkgLib 'yuv.lib') },
    @{ N = 'opus.lib';      P = (Join-Path $VcpkgLib 'opus.lib') },
    @{ N = 'Android SDK';   P = 'C:\Users\Administrator\AppData\Local\Android\sdk' },
    @{ N = 'Android NDK';   P = 'C:\Users\Administrator\AppData\Local\Android\sdk\ndk\26.1.10909125' },
    @{ N = 'JDK 17';        P = 'A:\jdk-17.0.12\bin\java.exe' },
    @{ N = 'adb';           P = 'A:\platform-tools\adb.exe' }
)
$missing = 0
foreach ($item in $pre) {
    if (Test-Path $item.P) { Say ("  [OK]      {0}" -f $item.N) 'Green' }
    else { Say ("  [缺失]    {0}  ({1})" -f $item.N, $item.P) 'Red'; $missing++ }
}
Say ''
Say ("注意: 4 个 MSVC 静态库缺失时，Windows 链接会失败（请先跑 tmpscripts 里的 vcpkg 构建）。") 'Yellow'
if ($missing -gt 0) {
    Say ("预检发现 {0} 项缺失，仍会继续尝试，但相关阶段大概率失败。" -f $missing) 'Yellow'
}
if (Test-Path 'C:\Program Files\Cloudflare\cloudflared\cloudflared.exe') { Say "  [OK]      cloudflared" 'Green' }
else { Say "  [提示]    cloudflared 未在默认位置（隧道需另行启动）" 'Yellow' }

# 桌面端被控端正在运行会锁住要覆盖的文件，先记下来，拷完再拉起来
$runningHost = (Get-NetTCPConnection -LocalPort 21118 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
$runningCli = (Get-Process rustdesk -ErrorAction SilentlyContinue | Measure-Object).Count -gt 0
if ($runningCli) {
    Say ''
    Say "检测到正在运行的 rustdesk，构建前会先关闭（避免文件占用），结束后重新拉起。" 'Yellow'
    Get-Process rustdesk -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue }
    Start-Sleep -Seconds 3
}

$total = 6
$n = 0

# ------------------------------------------------------------ 1. Windows CLI
if (-not $SkipWindows -and -not $SkipCliHost) {
    $n++
    Banner ("[{0}/{1}] Windows 无界面 CLI 被控端 (cargo --bin rustdesk, MSVC)" -f $n, $total)
    Say "说明: C 代码用 clang 包装器编译（cl.exe 在这台机器上会 D8050）；链接 4 个 MSVC 静态库。" 'DarkGray'
    $code = Step -Name 'cli' -Command 'A:\Downloads\tmpscripts\build-msvc-rustdesk.cmd' -StageLog (Join-Path $LogDir "cli-$Stamp.log")
    if ($code -eq 0) {
        New-Item -ItemType Directory -Force -Path $HostDir | Out-Null
        Copy-Item 'A:\Downloads\cargo-target-msvc\release\rustdesk.exe' (Join-Path $HostDir 'rustdesk.exe') -Force
        Say "已安装到 A:\rustdesk\rustdesk.exe" 'Green'
    }
    else { $Failed += 'Windows CLI 被控端' }
}

# --------------------------------------------------- 2. librustdesk.dll
if (-not $SkipWindows) {
    $n++
    Banner ("[{0}/{1}] Windows librustdesk.dll (cargo --lib --features flutter)" -f $n, $total)
    Say "说明: 桌面端的 Rust 动态库；Dart 代码改动不重编它，但 Rust 改动必须重编。" 'DarkGray'
    $code = Step -Name 'flutter-lib' -Command 'A:\Downloads\tmpscripts\build-msvc-flutter-lib.cmd' -StageLog (Join-Path $LogDir "flutter-lib-$Stamp.log")
    if ($code -eq 0) {
        Say "librustdesk.dll 已生成（下一步的 Flutter 构建会自动带进 Release 目录）" 'Green'
    }
    else { $Failed += 'librustdesk.dll' }
}

# ------------------------------------------------------ 3. Windows 桌面端
if (-not $SkipWindows) {
    $n++
    Banner ("[{0}/{1}] Windows 桌面端 (flutter build windows --release)" -f $n, $total)
    Say "说明: 若上一步成功，会把新的 librustdesk.dll 一起打进 Release 目录，避免新旧混搭。" 'DarkGray'
    $code = Step -Name 'flutter-windows' -Command 'A:\Downloads\tmpscripts\build-flutter-windows.cmd' -StageLog (Join-Path $LogDir "flutter-windows-$Stamp.log")
    if ($code -eq 0) {
        $src = Join-Path $Root 'flutter\build\windows\x64\runner\Release'
        New-Item -ItemType Directory -Force -Path $DesktopDir | Out-Null
        Copy-Item (Join-Path $src '*') $DesktopDir -Recurse -Force
        Say ("已安装到 {0}" -f $DesktopDir) 'Green'
    }
    else { $Failed += 'Windows 桌面端' }
}

# ------------------------------------------------------ 4. Android Rust 库
if (-not $SkipAndroid) {
    $n++
    Banner ("[{0}/{1}] Android Rust 库 (cargo rustc --target aarch64-linux-android)" -f $n, $total)
    Say "说明: 用 NDK clang 交叉编译，链接 arm64-android 的 libsodium.a；产物拷进 jniLibs。" 'DarkGray'
    $code = StepBlock -Name 'android-lib' -StageLog (Join-Path $LogDir "android-lib-$Stamp.log") -Body {
        $sdk = 'C:/Users/Administrator/AppData/Local/Android/sdk'
        $ndk = "$sdk/ndk/26.1.10909125"
        $bin = "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
        $env:CARGO_HOME = 'A:\Downloads\cargo-home'
        $env:RUSTUP_HOME = 'A:\cargo\lib\rustup'
        $env:CARGO_TARGET_DIR = 'A:\Downloads\cargo-target'
        $env:ANDROID_HOME = $sdk
        $env:ANDROID_NDK_HOME = $ndk
        $env:ANDROID_NDK_ROOT = $ndk
        $env:LIBCLANG_PATH = 'A:\msys64\clang64\bin'
        $env:VCPKG_ROOT = 'A:\Downloads\vcpkg'
        $env:TMP = 'A:\Downloads\tmpscripts\tmp'
        $env:TEMP = 'A:\Downloads\tmpscripts\tmp'
        $env:BINDGEN_EXTRA_CLANG_ARGS = '--target=aarch64-linux-android21 -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include') + ' -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include/aarch64-linux-android') + ' -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include/aarch64-linux-android/asm')
        $env:CC_aarch64_linux_android = "$bin/clang.exe"
        $env:CXX_aarch64_linux_android = "$bin/clang++.exe"
        $env:AR_aarch64_linux_android = "$bin/llvm-ar.exe"
        $env:RANLIB_aarch64_linux_android = "$bin/llvm-ranlib.exe"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$bin/clang.exe"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=--target=aarch64-linux-android21'
        $env:CFLAGS_aarch64_linux_android = '--target=aarch64-linux-android21'
        $env:CXXFLAGS_aarch64_linux_android = '--target=aarch64-linux-android21'
        $env:PATH = "A:\cargo\lib\cargo\bin;$bin;A:\msys64\clang64\bin;A:\msys64\ucrt64\bin;A:\msys64\usr\bin;$env:PATH"
        Push-Location $Root
        & cargo rustc --lib --target aarch64-linux-android --release --locked --offline --features flutter -- -C link-arg=A:/Downloads/vcpkg/installed/arm64-android/lib/libsodium.a
        $script:StepCode = $LASTEXITCODE
        Pop-Location
    }
    if ($code -eq 0) {
        $so = 'A:\Downloads\cargo-target\aarch64-linux-android\release\liblibrustdesk.so'
        if (Test-Path $so) {
            Copy-Item $so (Join-Path $Root 'flutter\android\app\src\main\jniLibs\arm64-v8a\librustdesk.so') -Force
            Say "liblibrustdesk.so 已拷入 jniLibs/arm64-v8a" 'Green'
        }
        else { Say "未找到 $so" 'Red'; $Failed += 'Android .so 拷贝' }
    }
    else { $Failed += 'Android Rust 库' }
}

# ------------------------------------------------------------ 5. Android APK
if (-not $SkipAndroid -and -not $SkipApk) {
    $n++
    Banner ("[{0}/{1}] Android APK (flutter build apk --debug, 内部走 Gradle)" -f $n, $total)
    Say "说明: 这一阶段会调用 Gradle（按你的规则默认禁止，故单独成阶段并可用 -SkipApk 跳过）。" 'Yellow'
    $code = StepBlock -Name 'apk' -StageLog (Join-Path $LogDir "apk-$Stamp.log") -Body {
        $sdk = 'C:/Users/Administrator/AppData/Local/Android/sdk'
        $ndk = "$sdk/ndk/26.1.10909125"
        $bin = "$ndk/toolchains/llvm/prebuilt/windows-x86_64/bin"
        $env:CARGO_HOME = 'A:\Downloads\cargo-home'
        $env:RUSTUP_HOME = 'A:\cargo\lib\rustup'
        $env:CARGO_TARGET_DIR = 'A:\Downloads\cargo-target'
        $env:JAVA_HOME = 'A:\jdk-17.0.12'
        $env:ANDROID_HOME = $sdk
        $env:ANDROID_SDK_ROOT = $sdk
        $env:ANDROID_NDK_HOME = $ndk
        $env:ANDROID_NDK_ROOT = $ndk
        $env:VCPKG_ROOT = 'A:\Downloads\vcpkg'
        $env:LIBCLANG_PATH = 'A:\msys64\clang64\bin'
        $env:BINDGEN_EXTRA_CLANG_ARGS = '--target=aarch64-linux-android21 -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include') + ' -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include/aarch64-linux-android') + ' -isystem ' + ($ndk + '/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/include/aarch64-linux-android/asm')
        $env:CC_aarch64_linux_android = "$bin/clang.exe"
        $env:CXX_aarch64_linux_android = "$bin/clang++.exe"
        $env:AR_aarch64_linux_android = "$bin/llvm-ar.exe"
        $env:RANLIB_aarch64_linux_android = "$bin/llvm-ranlib.exe"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER = "$bin/clang.exe"
        $env:CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS = '-C link-arg=--target=aarch64-linux-android21'
        $env:CFLAGS_aarch64_linux_android = '--target=aarch64-linux-android21'
        $env:CXXFLAGS_aarch64_linux_android = '--target=aarch64-linux-android21'
        $env:HTTP_PROXY = 'http://127.0.0.1:10808'
        $env:HTTPS_PROXY = 'http://127.0.0.1:10808'
        $env:TMP = 'A:\Downloads\tmpscripts\tmp'
        $env:TEMP = 'A:\Downloads\tmpscripts\tmp'
        $env:PATH = "A:\flutter\bin;A:\cargo\lib\cargo\bin;$bin;A:\msys64\clang64\bin;A:\msys64\ucrt64\bin;A:\msys64\usr\bin;A:\jdk-17.0.12\bin;A:\Git\bin;$env:PATH"
        Push-Location (Join-Path $Root 'flutter')
        & flutter build apk --debug --target-platform android-arm64
        $script:StepCode = $LASTEXITCODE
        Pop-Location
    }
    if ($code -eq 0) {
        if (Test-Path $ApkPath) { Say ("APK: {0}" -f $ApkPath) 'Green' } else { Say "APK 未生成" 'Red'; $Failed += 'APK' }
    }
    else { $Failed += 'Android APK' }

    if ($InstallApk -and (Test-Path $ApkPath)) {
        Banner "附加: adb 覆盖安装到手机"
        $code = Step -Name 'adb-install' -Command "A:\platform-tools\adb.exe install -r `"$ApkPath`"" -StageLog (Join-Path $LogDir "adb-install-$Stamp.log")
        if ($code -ne 0) { $Failed += 'adb 安装' }
    }
}

# ------------------------------------------------------------------ 汇总
Banner "[汇总] 产物"
if (-not $SkipWindows -and -not $SkipCliHost) { Show-Artifact (Join-Path $HostDir 'rustdesk.exe') 'CLI 被控端' }
if (-not $SkipWindows) {
    Show-Artifact (Join-Path $DesktopDir 'rustdesk.exe') '桌面端 exe'
    Show-Artifact (Join-Path $DesktopDir 'librustdesk.dll') '桌面端 librustdesk.dll'
    Show-Artifact (Join-Path $DesktopDir 'data\app.so') '桌面端 Dart (app.so)'
}
if (-not $SkipAndroid -and -not $SkipApk) { Show-Artifact $ApkPath 'Android APK' }

$elapsed = (Get-Date) - $BuildStart
Say ''
Say ("总用时: {0:n1} 分钟" -f $elapsed.TotalMinutes)
if ($Failed.Count -eq 0) {
    Say "全部阶段成功 ✅" 'Green'
}
else {
    Say ("失败阶段: {0}" -f ($Failed -join '、')) 'Red'
}

# 构建前如果被控端在跑，重新拉起（桌面端优先）
if ($runningCli) {
    Say ''
    Say "重新拉起被控端…" 'Cyan'
    if (Test-Path (Join-Path $DesktopDir 'rustdesk.exe')) {
        Start-Process -FilePath (Join-Path $DesktopDir 'rustdesk.exe') -WorkingDirectory $DesktopDir | Out-Null
        Say "已启动桌面端被控端（监听 21118）" 'Green'
    }
    elseif (Test-Path (Join-Path $HostDir 'rustdesk.exe')) {
        $env:RUSTDESK_HEADLESS = '1'
        Start-Process -FilePath (Join-Path $HostDir 'rustdesk.exe') -WorkingDirectory $HostDir -WindowStyle Hidden | Out-Null
        Say "已启动 CLI 无界面被控端（监听 21118）" 'Green'
    }
    Start-Sleep -Seconds 10
    $listen = (Get-NetTCPConnection -LocalPort 21118 -State Listen -ErrorAction SilentlyContinue | Measure-Object).Count
    Say ("21118 监听: {0}" -f $(if ($listen -gt 0) { '是' } else { '否（可能还需几秒，或手工从托盘启动）' }))
}
Say ''
Say ("完整日志: {0}" -f $MasterLog)
exit $(if ($Failed.Count -eq 0) { 0 } else { 1 })