<#
  Windows 构建：无界面 CLI 被控端（host/）
  ---------------------------------------------------------------------------
  与 host/Dockerfile 和 .github/workflows/build-release.yml 的 Windows 任务使用同一条命令：

    cargo build --release --features system-libs

  system-libs 让 scrap 与 magnum-opus 通过 pkg-config 链接 MSYS2 UCRT64 提供的
  libvpx / libaom / libyuv / libopus，不再走 vcpkg。构建需要 x86_64-pc-windows-gnu
  工具链（rustup default stable-x86_64-pc-windows-gnu）。

  用法（在 PowerShell 里）：
    pwsh -File host\scripts\windows\build-all.ps1
    pwsh -File host\scripts\windows\build-all.ps1 -Jobs 4
    pwsh -File host\scripts\windows\build-all.ps1 -Msys2Root C:\msys64
    pwsh -File host\scripts\windows\build-all.ps1 -DryRun

  参数：
    -Jobs N        cargo 并行任务数，默认 6
    -Msys2Root P   MSYS2 安装根目录，默认从 PATH 上的 pkg-config 反推，再试 C:\msys64、A:\msys64
    -DryRun        只做环境预检并打印将执行的命令，不编译

  产物：
    host\target\release\rustdesk.exe
  日志：
    host\target\build-logs\build-<时间戳>.log

  host/ 没有 Flutter/Dart/NDK/vcpkg 步骤：它只有命令行入口；Android 端在 android\ 下
  用 Gradle 单独构建，不经过这个脚本。
#>
param(
    [int]$Jobs = 6,
    [string]$Msys2Root = '',
    [switch]$DryRun
)

$ErrorActionPreference = 'Continue'

function Say {
    param([string]$Msg, [string]$Color = 'Gray')
    Write-Host ("[{0}] {1}" -f (Get-Date -Format 'HH:mm:ss'), $Msg) -ForegroundColor $Color
}

function Die {
    param([string]$Msg)
    Say $Msg 'Red'
    exit 1
}

# MSYS2 UCRT64：优先用 -Msys2Root，其次从 PATH 上的 pkg-config 反推，最后试常见安装位置。
function Resolve-Msys2Root {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    $pc = Get-Command pkg-config -ErrorAction SilentlyContinue
    if ($pc -and $pc.Source -match '^(.*)[\\/]ucrt64[\\/]bin[\\/](pkg-config|pkgconf)\.exe$') {
        $candidates += $Matches[1]
    }
    $candidates += @('C:\msys64', 'A:\msys64')
    foreach ($c in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $c 'ucrt64\bin\pkg-config.exe')) {
            return (Resolve-Path -LiteralPath $c).Path
        }
    }
    return $null
}

$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$HostDir = Join-Path $RepoRoot 'host'
if (-not (Test-Path -LiteralPath (Join-Path $HostDir 'Cargo.toml'))) {
    Die "找不到 host/Cargo.toml（由脚本位置推出的仓库根目录是 $RepoRoot）。"
}

$Msys2 = Resolve-Msys2Root -Explicit $Msys2Root
if (-not $Msys2) {
    Die '找不到 MSYS2：请安装 MSYS2，并在 UCRT64 里装好 pkgconf 与 libvpx/aom/libyuv/opus，或用 -Msys2Root 指定安装根目录。'
}
$Ucrt64 = Join-Path $Msys2 'ucrt64'
$PkgConfig = Join-Path $Ucrt64 'bin\pkg-config.exe'

# 原生 pkgconf 接受 C:/... 形式的 Windows 路径；已有的 PKG_CONFIG_PATH 追加在后面。
$env:PATH = (Join-Path $Ucrt64 'bin') + ';' + $env:PATH
$PcDir = (Join-Path $Ucrt64 'lib\pkgconfig') -replace '\\', '/'
if ($env:PKG_CONFIG_PATH) { $env:PKG_CONFIG_PATH = $PcDir + ';' + $env:PKG_CONFIG_PATH }
else { $env:PKG_CONFIG_PATH = $PcDir }
$env:LIBCLANG_PATH = (Join-Path $Ucrt64 'bin')
$env:CC = 'gcc'
$env:CXX = 'g++'

$Cargo = Join-Path $Ucrt64 'bin\cargo.exe'
if (-not (Test-Path -LiteralPath $Cargo)) {
    $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
    if (-not $cargoCmd) { Die '找不到 cargo：请安装 Rust，并切到 x86_64-pc-windows-gnu 工具链。' }
    $Cargo = $cargoCmd.Source
}
$Rustc = Join-Path (Split-Path $Cargo) 'rustc.exe'
if (-not (Test-Path -LiteralPath $Rustc)) {
    $rustcCmd = Get-Command rustc -ErrorAction SilentlyContinue
    if ($rustcCmd) { $Rustc = $rustcCmd.Source }
}

Say '===== 环境预检 =====' 'Cyan'
Say "仓库    : $RepoRoot"
Say "host    : $HostDir"
Say "MSYS2   : $Msys2"
Say "cargo   : $Cargo"

if (-not (Test-Path -LiteralPath $Rustc)) { Die '找不到 rustc。' }
$hostLine = (& $Rustc -vV 2>$null | Select-String '^host:').Line
Say "rustc   : $((& $Rustc --version 2>$null))  [$hostLine]"
if (-not ($hostLine -match 'windows-gnu')) {
    Die "host/ 的 Windows 构建要 GNU 工具链才能链接 UCRT64 的库，当前 rustc 是「$hostLine」。请先执行：rustup default stable-x86_64-pc-windows-gnu"
}

foreach ($pcName in @('vpx', 'aom', 'libyuv', 'opus')) {
    $ver = (& $PkgConfig --modversion $pcName 2>$null)
    if ($LASTEXITCODE -ne 0) {
        Die "pkg-config 找不到 $pcName：请在 MSYS2 UCRT64 里安装 mingw-w64-ucrt-x86_64-libvpx / aom / libyuv / opus。"
    }
    Say ("  [OK] {0,-7} {1}" -f $pcName, $ver) 'Green'
}

$gcc = Join-Path $Ucrt64 'bin\gcc.exe'
if (Test-Path -LiteralPath $gcc) { Say "gcc     : $(& $gcc --version | Select-Object -First 1)" }
else { Die "找不到 $gcc。" }

$gitCmd = Get-Command git -ErrorAction SilentlyContinue
if (-not $gitCmd) { Die '找不到 git：cargo 需要它拉取本仓库的 git 依赖。' }

$cargoArgs = @('build', '--release', '--features', 'system-libs')
if ($Jobs -gt 0) { $cargoArgs += @('-j', "$Jobs") }

$Stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$LogDir = Join-Path $HostDir 'target\build-logs'
$Log = Join-Path $LogDir "build-$Stamp.log"

# 不主动结束进程：另一个位置的 rustdesk 不该被这个脚本杀掉。
$running = @(Get-Process -Name rustdesk -ErrorAction SilentlyContinue)
if ($running.Count -gt 0) {
    Say ("提示：有 {0} 个 rustdesk 进程在运行。若其中一个就是 target\release\rustdesk.exe，cargo 无法覆盖它，请先退出再构建。" -f $running.Count) 'Yellow'
}

Say ''
Say '===== 构建 =====' 'Cyan'
Say ("命令: {0} {1}" -f $Cargo, ($cargoArgs -join ' ')) 'DarkGray'
Say "日志: $Log"
if ($DryRun) {
    Say 'DryRun：只做预检，不执行构建。' 'Yellow'
    exit 0
}

New-Item -ItemType Directory -Force -Path $LogDir | Out-Null
Push-Location $HostDir
try {
    & $Cargo @cargoArgs 2>&1 | Tee-Object -FilePath $Log | Out-Host
    $code = $LASTEXITCODE
}
finally {
    Pop-Location
}

if ($null -eq $code) { $code = 0 }
Say ''
if ($code -ne 0) {
    Say "构建失败，退出码 $code；日志：$Log" 'Red'
    exit $code
}

$exe = Join-Path $HostDir 'target\release\rustdesk.exe'
if (-not (Test-Path -LiteralPath $exe)) {
    Die "cargo 报成功，但产物不存在：$exe"
}
$item = Get-Item -LiteralPath $exe
Say ("产物: {0}  ({1:n2} MB, {2})" -f $exe, ($item.Length / 1MB), $item.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')) 'Green'
Say '本机运行：target\release\rustdesk.exe --headless（见 host\TERMINAL.md）'
exit 0
