#Requires -Version 7.0
<#
.SYNOPSIS
    One-shot on-device verification for the pure-Java RustDesk Android client.

.DESCRIPTION
    Verifies that the app installs, launches straight into the viewer and reaches the
    RustDesk login handshake on a real, already-connected phone.

    Preconditions (all exit 2): adb exists, exactly one usable device (or -Serial),
    a reusable APK when -SkipBuild is given, a writable -OutDir.

    The run installs through /data/local/tmp (never /sdcard: it is a FUSE mount and
    `pm install` from it fails), starts MainActivity with the id/password extras,
    captures logcat for -DurationSec seconds plus a screencap every five seconds,
    and then asserts:

      * no FATAL EXCEPTION / AndroidRuntime crash in the captured log
      * RDJAVA connection keywords and `status: login ok` in the captured log
      * RemoteActivity present in `dumpsys activity activities`
      * at least one screenshot larger than 20 KB (rules out a pure black/blank grab)

    Exit codes: 0 all checks passed, 1 run/verification failure, 2 precondition failure.
    It never uninstalls the app and never changes system settings.

.PARAMETER Id
    Peer id of the controlled device. Required; am start forwards it as `--es id`.

.PARAMETER Password
    Peer password. Default 123456. Forwarded as `--es password`; MainActivity does read
    this extra and passes it on to RemoteActivity.

.PARAMETER Rendezvous
    Rendezvous server, accepted on the command line as -Host (PowerShell reserves the
    automatic variable $Host, so the parameter itself is named Rendezvous and carries
    a Host alias). MainActivity does not read or forward a `server` extra, so the value
    cannot take effect without editing the app. RemoteActivity's built-in default is
    rs-ny.rustdesk.com.

.PARAMETER DurationSec
    Capture window in seconds. Default 30.

.PARAMETER SkipBuild
    Reuse app/build/outputs/apk/debug/app-debug.apk instead of running build-apk.ps1.

.PARAMETER OutDir
    Directory for logcat, dumpsys text, screenshots and summary.txt.
    Default A:\Downloads\tmpscripts\devverify.

.PARAMETER Serial
    adb serial to use when more than one device is attached. Without it, the script
    requires exactly one device.

.EXAMPLE
    pwsh -NoProfile -File tools/verify-device.ps1 -Id 123456789

.EXAMPLE
    pwsh -NoProfile -File tools/verify-device.ps1 -Id 123456789 -Password 123456 -DurationSec 60 -SkipBuild

.EXAMPLE
    pwsh -NoProfile -File tools/verify-device.ps1 -Id 123456789 -Serial R58M1234ABC
#>
param(
    [string]$Id = '',
    [string]$Password = '123456',
    [Alias('Host')]
    [string]$Rendezvous = 'rs-ny.rustdesk.com',
    [int]$DurationSec = 30,
    [switch]$SkipBuild,
    [string]$OutDir = 'A:\Downloads\tmpscripts\devverify',
    [string]$Serial = ''
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
# Native stderr from adb must not turn into a terminating error.
$PSNativeCommandUseErrorActionPreference = $false

$script:ProjectRoot = Split-Path -Parent $PSScriptRoot
$script:DefaultAdb = 'C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$script:Package = 'com.rustdesk.java'
$script:Component = 'com.rustdesk.java/.MainActivity'
$script:BuildScript = Join-Path $PSScriptRoot 'build-apk.ps1'
$script:DefaultRendezvous = 'rs-ny.rustdesk.com'
$script:LogProc = $null
$script:Adb = $null

function Stop-Precondition {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Yellow
    exit 2
}

function Stop-Run {
    param([Parameter(Mandatory)][string]$Message)
    Write-Host ''
    Write-Host "ERROR: $Message" -ForegroundColor Yellow
    exit 1
}

function Stop-LogcatProcess {
    param($Process)
    if ($null -eq $Process) { return }
    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force -ErrorAction Stop
        }
    } catch {
        # Already gone, or cannot be killed: nothing useful left to do here.
    }
    try { [void]$Process.WaitForExit(5000) } catch { }
}

function Resolve-Adb {
    $candidates = [System.Collections.Generic.List[string]]::new()
    $candidates.Add($script:DefaultAdb)
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $root = [Environment]::GetEnvironmentVariable($envName)
        if ($root) {
            $candidates.Add((Join-Path $root 'platform-tools\adb.exe'))
        }
    }
    $onPath = Get-Command 'adb.exe' -ErrorAction SilentlyContinue
    if ($onPath) { $candidates.Add($onPath.Source) }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return [System.IO.Path]::GetFullPath($candidate)
        }
    }
    Stop-Precondition ("找不到 adb.exe。已检查：{0}。请安装 Android SDK platform-tools 或设置 ANDROID_HOME。" -f ($candidates -join ', '))
}

function Invoke-AdbText {
    param([Parameter(Mandatory)][string[]]$Arguments)
    $lines = @(& $script:Adb @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    $code = $LASTEXITCODE
    return [pscustomobject]@{ Output = $lines; Code = $code }
}

# Binary-safe `adb exec-out`: raw stdout goes straight into the file, no text decoding.
function Invoke-AdbBinary {
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [Parameter(Mandatory)][string]$OutFile
    )
    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:Adb
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    foreach ($argument in $Arguments) { $psi.ArgumentList.Add($argument) }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi
    [void]$process.Start()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $stream = [System.IO.File]::Open($OutFile,
        [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    $copyTask = $process.StandardOutput.BaseStream.CopyToAsync($stream)
    $exited = $process.WaitForExit(30000)
    if (-not $exited) {
        try { $process.Kill($true) } catch { }
    }
    try { [void]$copyTask.GetAwaiter().GetResult() } catch { }
    $stream.Dispose()
    if (-not $exited) {
        return [pscustomobject]@{ Code = -1; Error = 'adb exec-out 超时（30s）' }
    }
    $stderr = ''
    try { $stderr = $errorTask.GetAwaiter().GetResult() } catch { }
    return [pscustomobject]@{ Code = $process.ExitCode; Error = $stderr }
}

# adb shell joins its arguments and the device runs them through sh, so quote anything
# that is not obviously safe. RustDesk ids are numeric but passwords/ids are user input.
function ConvertTo-RemoteArg {
    param([Parameter(Mandatory)][string]$Value)
    if ($Value -match '^[A-Za-z0-9._:/=+-]+$') { return $Value }
    return "'" + $Value.Replace("'", "'\''") + "'"
}

function Get-AdbDeviceEntries {
    $result = Invoke-AdbText -Arguments @('devices')
    if ($result.Code -ne 0) {
        Stop-Precondition ("adb devices 执行失败（exit {0}）。请先确认 adb server 正常：adb kill-server 后重试。" -f $result.Code)
    }
    $entries = @()
    foreach ($line in $result.Output) {
        if ($line -match '^\s*(\S+)\s+(device|unauthorized|offline|bootloader|recovery|sideload|rescue)\s*$') {
            $entries += [pscustomobject]@{ Serial = $matches[1]; State = $matches[2].ToLowerInvariant() }
        }
    }
    # `adb devices` can list the same transport twice after a reconnect.
    return @($entries | Sort-Object -Property Serial -Unique)
}

function Select-AdbDevice {
    param([object[]]$Entries = @())

    if ($Serial) {
        $selected = @($Entries | Where-Object { $_.Serial -eq $Serial })
        if ($selected.Count -eq 0) {
            $seen = if ($Entries.Count -gt 0) {
                ($Entries | ForEach-Object { "$($_.Serial) [$($_.State)]" }) -join ', '
            } else {
                'adb devices 为空（无设备）'
            }
            Stop-Precondition ("设备 '{0}' 不存在。当前可见：{1}。请确认 serial（adb devices）后重试。" -f $Serial, $seen)
        }
        $device = $selected[0]
        if ($device.State -ne 'device') {
            Stop-Precondition ("设备 '{0}' 当前状态为 '{1}'，不是 device。请在手机上确认 USB 调试授权，或重新插拔 USB。" -f $Serial, $device.State)
        }
        return $device
    }

    if ($Entries.Count -eq 0) {
        Stop-Precondition '没有检测到已连接的 Android 设备（adb devices 为空）。请插上手机、开启 USB 调试并在手机上允许本机授权后重试。'
    }
    if ($Entries.Count -gt 1) {
        $list = ($Entries | ForEach-Object { "$($_.Serial) [$($_.State)]" }) -join ', '
        Stop-Precondition ("检测到 {0} 台设备（{1}），请用 -Serial <serial> 指定要验证的一台。" -f $Entries.Count, $list)
    }
    $only = $Entries[0]
    switch ($only.State) {
        'device' { return $only }
        'unauthorized' {
            Stop-Precondition ("设备 '{0}' 未授权（unauthorized）。请在手机上点[允许 USB 调试]后重试。" -f $only.Serial)
        }
        'offline' {
            Stop-Precondition ("设备 '{0}' 处于 offline。请重新插拔 USB 或执行 adb reconnect offline 后重试。" -f $only.Serial)
        }
        default {
            Stop-Precondition ("设备 '{0}' 状态为 '{1}'，不是 device，无法验证。" -f $only.Serial, $only.State)
        }
    }
}

function Resolve-Apk {
    $debugDir = Join-Path $script:ProjectRoot 'app\build\outputs\apk\debug'
    if (-not $SkipBuild) {
        if (-not (Test-Path -LiteralPath $script:BuildScript -PathType Leaf)) {
            Stop-Precondition "找不到构建脚本：$script:BuildScript"
        }
        Write-Host '[2/7] 构建 debug APK（build-apk.ps1，可能耗时 1-3 分钟）...'
        $pwshExe = Join-Path $PSHOME 'pwsh.exe'
        if (-not (Test-Path -LiteralPath $pwshExe -PathType Leaf)) {
            $pwshExe = (Get-Process -Id $PID).Path
        }
        $buildLines = @(& $pwshExe -NoProfile -ExecutionPolicy Bypass -File $script:BuildScript 2>&1 |
            ForEach-Object {
                $text = $_.ToString()
                Write-Host "  $text"
                $text
            })
        $buildCode = $LASTEXITCODE
        if ($buildCode -ne 0) {
            Stop-Run "构建失败（build-apk.ps1 exit $buildCode），未做任何安装。"
        }
        $apk = $null
        $apkLine = @($buildLines | Where-Object { $_ -match '^APK:\s*(.+)$' }) | Select-Object -Last 1
        if ($apkLine -and $apkLine -match '^APK:\s*(.+)$') {
            $candidate = $matches[1].Trim()
            if (Test-Path -LiteralPath $candidate -PathType Leaf) { $apk = $candidate }
        }
        if (-not $apk) {
            $preferred = Join-Path $debugDir 'app-debug.apk'
            if (Test-Path -LiteralPath $preferred -PathType Leaf) {
                $apk = $preferred
            } else {
                $newest = @(Get-ChildItem -LiteralPath $debugDir -Filter '*.apk' -File -ErrorAction SilentlyContinue |
                    Sort-Object -Property LastWriteTimeUtc -Descending | Select-Object -First 1)
                if ($newest.Count -gt 0) { $apk = $newest[0].FullName }
            }
        }
        if (-not $apk) {
            Stop-Run "构建脚本返回成功，但没有找到 APK（查找目录：$debugDir）。"
        }
        return [pscustomobject]@{ Path = $apk; Mode = 'built' }
    }

    $preferred = Join-Path $debugDir 'app-debug.apk'
    if (Test-Path -LiteralPath $preferred -PathType Leaf) {
        return [pscustomobject]@{ Path = $preferred; Mode = 'reused' }
    }
    $newest = @(Get-ChildItem -LiteralPath $debugDir -Filter '*.apk' -File -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTimeUtc -Descending | Select-Object -First 1)
    if ($newest.Count -eq 0) {
        Stop-Precondition "-SkipBuild 已指定，但 $debugDir 下没有可复用的 APK。请去掉 -SkipBuild 先构建。"
    }
    return [pscustomobject]@{ Path = $newest[0].FullName; Mode = 'reused' }
}

function Find-ApkSigner {
    $sdk = $null
    foreach ($envName in @('ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        $root = [Environment]::GetEnvironmentVariable($envName)
        if ($root -and (Test-Path -LiteralPath (Join-Path $root 'build-tools') -PathType Container)) {
            $sdk = $root
            break
        }
    }
    if (-not $sdk) {
        $fallback = 'C:\Users\Administrator\AppData\Local\Android\Sdk'
        if (Test-Path -LiteralPath (Join-Path $fallback 'build-tools') -PathType Container) {
            $sdk = $fallback
        }
    }
    if (-not $sdk) { return $null }
    return @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }) | Select-Object -First 1
}

function Show-SigningMismatchHint {
    param([Parameter(Mandatory)][string]$ApkPath)
    $signer = Find-ApkSigner
    $signerText = if ($signer) { "& '$signer'" } else { 'apksigner.bat（位于 SDK build-tools 下）' }
    Write-Host ''
    Write-Host 'INSTALL_FAILED_UPDATE_INCOMPATIBLE：APK 与设备上已安装的 com.rustdesk.java 签名不一致。' -ForegroundColor Yellow
    Write-Host '按约定不自动卸载（卸载会清除应用数据）。请先比对证书，确认是同一 keystore 后再决定：'
    Write-Host "  本地 APK : $signerText verify --print-certs '$ApkPath'"
    Write-Host '  已装 APK : adb shell pm path com.rustdesk.java'
    Write-Host '             adb pull <上一步输出的路径> installed.apk'
    Write-Host "             $signerText verify --print-certs installed.apk"
    Write-Host '  两边 SHA-256 不同就说明换了 keystore（默认 alias key0 / 密码 123456），重新签名后重试。'
}

function Format-Size {
    param([long]$Bytes)
    if ($Bytes -ge 1MB) { return ('{0:0.0} MB' -f ($Bytes / 1MB)) }
    if ($Bytes -ge 1KB) { return ('{0:0.0} KB' -f ($Bytes / 1KB)) }
    return "$Bytes B"
}

try {
    # ---------------------------------------------------------------- arguments
    if (-not $Id -or $Id.Trim().Length -eq 0) {
        Stop-Precondition '缺少 -Id <peerId>（被控端 ID）。用法：pwsh -NoProfile -File tools\verify-device.ps1 -Id 123456789'
    }
    $Id = $Id.Trim()
    if ($DurationSec -lt 1 -or $DurationSec -gt 3600) {
        Stop-Precondition "-DurationSec 必须在 1..3600 之间，当前为 $DurationSec。"
    }

    $outDirFull = [System.IO.Path]::GetFullPath([Environment]::ExpandEnvironmentVariables($OutDir))
    try {
        [void](New-Item -ItemType Directory -Path $outDirFull -Force -ErrorAction Stop)
    } catch {
        Stop-Precondition ("无法创建输出目录 '{0}'：{1}" -f $outDirFull, $_.Exception.Message)
    }

    $script:Adb = Resolve-Adb

    Write-Host 'RustDesk Java 真机验证'
    Write-Host "  项目:     $script:ProjectRoot"
    Write-Host "  adb:      $script:Adb"
    Write-Host "  peer id:  $Id"
    Write-Host "  密码:     $(if ($Password) { '已提供（不显示）' } else { '空（将只传 id）' })"
    Write-Host "  服务端:   $Rendezvous"
    Write-Host "  时长:     ${DurationSec}s"
    Write-Host "  输出:     $outDirFull"
    if ($Rendezvous -ne $script:DefaultRendezvous) {
        Write-Host "  WARN: -Host $Rendezvous 无法生效：MainActivity 不转发 server extra，实际仍用 $script:DefaultRendezvous。" -ForegroundColor Yellow
    }

    # ---------------------------------------------------------------- 1. preflight
    Write-Host ''
    Write-Host '[1/7] 检查 adb 与设备 ...'
    $entries = @(Get-AdbDeviceEntries)
    $device = Select-AdbDevice -Entries $entries
    $serial = $device.Serial
    Write-Host "  设备: $serial (device)"

    # Clean only this script's own artifacts; never touch anything else in the folder.
    Get-ChildItem -LiteralPath $outDirFull -Filter 'shot_*.png' -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
    foreach ($name in @('logcat_RDJAVA.txt', 'logcat_live.txt', 'logcat_stderr.txt', 'dumpsys_activity.txt', 'summary.txt')) {
        $old = Join-Path $outDirFull $name
        if (Test-Path -LiteralPath $old -PathType Leaf) {
            Remove-Item -LiteralPath $old -Force -ErrorAction SilentlyContinue
        }
    }

    # ---------------------------------------------------------------- 2. APK
    Write-Host ''
    Write-Host '[2/7] 准备 APK ...'
    $apkInfo = Resolve-Apk
    $apkPath = $apkInfo.Path
    $apkItem = Get-Item -LiteralPath $apkPath
    if ($apkItem.Length -le 0) {
        Stop-Run "APK 为空文件：$apkPath"
    }
    Write-Host "  $($apkInfo.Mode): $apkPath ($(Format-Size $apkItem.Length))"

    # ---------------------------------------------------------------- 3. logcat clear
    Write-Host ''
    Write-Host '[3/7] 清空 logcat ...'
    $clear = Invoke-AdbText -Arguments @('-s', $serial, 'logcat', '-c')
    if ($clear.Code -ne 0) {
        Stop-Run ("adb logcat -c 失败（exit {0}）：{1}" -f $clear.Code, ($clear.Output -join ' '))
    }
    Write-Host '  ok'

    # ---------------------------------------------------------------- 4. install
    Write-Host ''
    Write-Host '[4/7] 安装 APK（/data/local/tmp + pm install -r）...'
    $remoteApk = '/data/local/tmp/rdjava.apk'
    $push = Invoke-AdbText -Arguments @('-s', $serial, 'push', $apkPath, $remoteApk)
    foreach ($line in $push.Output) { Write-Host "  $line" }
    if ($push.Code -ne 0) {
        Stop-Run "adb push 失败（exit $($push.Code)）。"
    }
    $install = Invoke-AdbText -Arguments @('-s', $serial, 'shell', 'pm', 'install', '-r', $remoteApk)
    $installText = $install.Output -join "`n"
    foreach ($line in $install.Output) { Write-Host "  $line" }
    if ($installText -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Show-SigningMismatchHint -ApkPath $apkPath
        Stop-Run 'pm install -r 因签名不一致失败；未卸载任何应用。'
    }
    if ($installText -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
        Stop-Run 'pm install -r 失败：设备上的版本更高（INSTALL_FAILED_VERSION_DOWNGRADE）。请先构建更高 versionCode 的 APK。'
    }
    if ($install.Code -ne 0 -or $installText -notmatch '(?m)^\s*Success\s*$') {
        $lastInstallLine = @($install.Output | Where-Object { $_.Trim() }) | Select-Object -Last 1
        Stop-Run ("pm install -r 失败（exit {0}）：{1}；未卸载任何应用。" -f $install.Code, $lastInstallLine)
    }

    # ---------------------------------------------------------------- 5. launch
    Write-Host ''
    Write-Host '[5/7] 启动 MainActivity 并直连 viewer ...'
    # logcat must already be attached before force-stop/am start, otherwise the very crash
    # this script is meant to catch (onCreate / activity launch) would only exist in the ring
    # buffer and never reach the file.
    $logPath = Join-Path $outDirFull 'logcat_RDJAVA.txt'
    $logLivePath = Join-Path $outDirFull 'logcat_live.txt'
    $logErrPath = Join-Path $outDirFull 'logcat_stderr.txt'
    $logArgs = @('-s', $serial, 'logcat', '-v', 'time', 'RDJAVA:*', 'AndroidRuntime:E', '*:F')
    try {
        $script:LogProc = Start-Process -FilePath $script:Adb -ArgumentList $logArgs `
            -RedirectStandardOutput $logLivePath -RedirectStandardError $logErrPath `
            -NoNewWindow -PassThru
    } catch {
        Stop-Run "无法启动后台 logcat：$($_.Exception.Message)"
    }
    Start-Sleep -Milliseconds 500
    if ($script:LogProc.HasExited) {
        $earlyErr = if (Test-Path -LiteralPath $logErrPath) { (Get-Content -LiteralPath $logErrPath -Raw).Trim() } else { '' }
        Stop-Run ("后台 logcat 提前退出（exit {0}）：{1}" -f $script:LogProc.ExitCode, $earlyErr)
    }
    Write-Host "  logcat 后台采集中: $logPath"

    $forceStop = Invoke-AdbText -Arguments @('-s', $serial, 'shell', 'am', 'force-stop', $script:Package)
    if ($forceStop.Code -ne 0) {
        Stop-Run ("am force-stop 失败（exit {0}）：{1}" -f $forceStop.Code, ($forceStop.Output -join ' '))
    }
    Start-Sleep -Milliseconds 300

    $startArgs = @('-s', $serial, 'shell', 'am', 'start', '-W', '-n', $script:Component,
        '--es', 'id', (ConvertTo-RemoteArg $Id))
    if ($Password) {
        # MainActivity.java:87 forwards this extra to RemoteActivity; RemoteActivity.java:177
        # hands it to Session. It is a real extra, not a guess.
        $startArgs += @('--es', 'password', (ConvertTo-RemoteArg $Password))
    }
    $start = Invoke-AdbText -Arguments $startArgs
    foreach ($line in $start.Output) { Write-Host "  $line" }
    $startText = $start.Output -join "`n"
    if ($start.Code -ne 0 -or $startText -match '(?m)^\s*(Error|Exception)\b') {
        Stop-Run ("am start 失败（exit {0}）。" -f $start.Code)
    }

    # ---------------------------------------------------------------- 6. capture
    Write-Host ''
    Write-Host "[6/7] 采集 ${DurationSec}s（每 5s 截图）..."

    $dumpsysPath = Join-Path $outDirFull 'dumpsys_activity.txt'
    $activityLines = @()
    $dumpsysCode = -1
    $activityChecked = $false
    $shots = [System.Collections.Generic.List[string]]::new()

    $settleMs = [int][Math]::Min(1500, [Math]::Max(200, $DurationSec * 100))
    Start-Sleep -Milliseconds $settleMs

    $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $index = 0
    while ($stopwatch.Elapsed.TotalSeconds -lt $DurationSec) {
        $index++
        $shotPath = Join-Path $outDirFull ('shot_{0:D2}.png' -f $index)
        $grab = Invoke-AdbBinary -Arguments @('-s', $serial, 'exec-out', 'screencap', '-p') -OutFile $shotPath
        $shots.Add($shotPath)
        $size = if (Test-Path -LiteralPath $shotPath) { (Get-Item -LiteralPath $shotPath).Length } else { 0 }
        $grabNote = if ($grab.Code -eq 0) { '' } else { " (adb exit $($grab.Code) $($grab.Error.Trim()))" }
        Write-Host ("  截图 {0}: {1}{2}" -f ('{0:D2}' -f $index), (Format-Size $size), $grabNote)

        if (-not $activityChecked) {
            $activityChecked = $true
            $dump = Invoke-AdbText -Arguments @('-s', $serial, 'shell', 'dumpsys', 'activity', 'activities')
            $dumpsysCode = $dump.Code
            $activityLines = @($dump.Output | Where-Object { $_ -match 'RemoteActivity' })
            $dumpBody = if ($activityLines.Count -gt 0) { $activityLines -join "`r`n" } else { "RemoteActivity not found in dumpsys activity activities`r`n" }
            [System.IO.File]::WriteAllText($dumpsysPath, $dumpBody, [System.Text.UTF8Encoding]::new($false))
            Write-Host ("  dumpsys: RemoteActivity {0}" -f $(if ($activityLines.Count -gt 0) { '已出现' } else { '未出现' }))
        }

        $remainingMs = [int](($DurationSec - $stopwatch.Elapsed.TotalSeconds) * 1000)
        if ($remainingMs -le 0) { break }
        Start-Sleep -Milliseconds ([Math]::Min(5000, $remainingMs))
    }
    $elapsed = $stopwatch.Elapsed.TotalSeconds
    $stopwatch.Stop()
    if ($elapsed -lt $DurationSec) {
        Start-Sleep -Milliseconds ([int](($DurationSec - $elapsed) * 1000))
    }
    Stop-LogcatProcess -Process $script:LogProc
    $script:LogProc = $null
    Start-Sleep -Milliseconds 300

    # The killed streaming client may still have a libc buffer that never reached the file.
    # `logcat -d` exits normally and therefore flushes, so the authoritative log is the dump;
    # the stream stays as evidence that the background capture ran.
    $logDump = Invoke-AdbText -Arguments @('-s', $serial, 'logcat', '-d', '-v', 'time',
        'RDJAVA:*', 'AndroidRuntime:E', '*:F')
    if ($logDump.Code -eq 0 -and $logDump.Output.Count -gt 0) {
        [System.IO.File]::WriteAllText($logPath, ($logDump.Output -join "`r`n"),
            [System.Text.UTF8Encoding]::new($false))
    } elseif (Test-Path -LiteralPath $logLivePath -PathType Leaf) {
        Copy-Item -LiteralPath $logLivePath -Destination $logPath -Force
    }

    # ---------------------------------------------------------------- 7. assertions
    Write-Host ''
    Write-Host '[7/7] 断言 ...'
    $logText = ''
    if (Test-Path -LiteralPath $logPath -PathType Leaf) {
        $logText = Get-Content -LiteralPath $logPath -Raw -ErrorAction SilentlyContinue
        if ($null -eq $logText) { $logText = '' }
    }

    $crashLines = @($logText -split "`r?`n" | Where-Object {
        $_ -match 'FATAL EXCEPTION' -or $_ -match '\bAndroidRuntime\b'
    })
    $crashOk = $crashLines.Count -eq 0
    $crashNote = if ($crashOk) {
        '未出现 FATAL EXCEPTION / AndroidRuntime'
    } else {
        "发现 $($crashLines.Count) 行崩溃日志：$($crashLines[0].Trim())"
    }

    # Exact strings from Session.java / RemoteActivity.java, not invented patterns.
    $connectKeys = @(
        'status: resolving peer through rendezvous',  # Session.java:149
        'status: connecting to ',                     # Session.java:158
        'status: relaying via ',                      # Session.java:165
        'status: secured=',                           # Session.java:189
        'status: password challenge answered'         # Session.java:300
    )
    $matchedConnect = @($connectKeys | Where-Object { $logText.Contains($_) })
    $connectOk = $matchedConnect.Count -gt 0
    $connectNote = if ($connectOk) {
        '命中: ' + (($matchedConnect | ForEach-Object { $_.Substring(8) }) -join '; ')
    } else {
        '未命中连接关键字（resolving/connecting/relaying/secured/password challenge）'
    }

    $loginOk = $logText.Contains('status: login ok')   # Session.java:319
    $loginRejected = $logText.Contains('closed: login rejected')  # Session.java:305
    $closedMatch = [regex]::Match($logText, 'closed: [^\r\n]+')
    $closedReason = if ($closedMatch.Success) { $closedMatch.Value.Trim() } else { '' }
    $loginNote = if ($loginOk) {
        '命中: login ok'
    } elseif ($loginRejected) {
        '登录被拒绝: closed: login rejected'
    } elseif ($closedReason) {
        "未出现 status: login ok；会话已关闭: $closedReason"
    } else {
        '未出现 status: login ok'
    }

    $shotItems = @($shots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object { Get-Item -LiteralPath $_ })
    $maxShot = 0L
    $maxShotName = '-'
    foreach ($item in $shotItems) {
        if ($item.Length -gt $maxShot) {
            $maxShot = $item.Length
            $maxShotName = $item.Name
        }
    }
    $bigShots = @($shotItems | Where-Object { $_.Length -gt 20480 })
    $shotOk = $bigShots.Count -gt 0
    $shotNote = if ($shotOk) {
        ('{0} 张 >20KB，最大 {1} ({2})' -f $bigShots.Count, (Format-Size $maxShot), $maxShotName)
    } else {
        ('所有截图均 ≤20KB，最大 {0} ({1})，疑似黑屏/空白' -f (Format-Size $maxShot), $maxShotName)
    }

    $activityOk = $dumpsysCode -eq 0 -and $activityLines.Count -gt 0
    $activityNote = if ($activityOk) {
        'RemoteActivity 出现在 dumpsys activity activities'
    } elseif ($dumpsysCode -ne 0) {
        "dumpsys 失败（exit $dumpsysCode）"
    } else {
        'dumpsys 中没有 RemoteActivity'
    }

    # RemoteActivity 每 5s 打一条 `stats: frames=<n> bytes=<n> deltaFrames=<d>`，首次收帧打
    # `first frame received`；deltaFrames>0 才算画面真的在动，全 0 说明只连上没收到帧。
    $statsPositive = @([regex]::Matches($logText, 'stats: frames=\d+ bytes=\d+ deltaFrames=[1-9]\d*')).Count
    $firstFrames = [regex]::Matches($logText, 'first frame received').Count
    $framesOk = $statsPositive -ge 2
    $framesNote = if ($framesOk) {
        "deltaFrames>0 的 stats 日志 $statsPositive 条（需 ≥2）"
    } else {
        "deltaFrames>0 的 stats 日志只有 $statsPositive 条（需 ≥2）：帧未在增长，画面未更新"
    }
    $firstFrameOk = $firstFrames -eq 1
    $firstFrameNote = if ($firstFrameOk) {
        'first frame received 出现 1 次'
    } else {
        "first frame received 出现 $firstFrames 次（需恰好 1 次）"
    }

    $checks = @(
        [pscustomobject]@{ Check = 'DEVICE';        Pass = $true;                    Note = "$serial state=device" }
        [pscustomobject]@{ Check = 'APK';           Pass = $true;                    Note = "$($apkInfo.Mode) $(Format-Size $apkItem.Length)" }
        [pscustomobject]@{ Check = 'INSTALL';       Pass = $true;                    Note = 'pm install -r Success' }
        [pscustomobject]@{ Check = 'VIEWER_PAGE';   Pass = $activityOk;              Note = $activityNote }
        [pscustomobject]@{ Check = 'LOGCAT_CONNECT'; Pass = $connectOk;              Note = $connectNote }
        [pscustomobject]@{ Check = 'LOGCAT_LOGIN';  Pass = $loginOk;                 Note = $loginNote }
        [pscustomobject]@{ Check = 'FRAMES_MOVING'; Pass = $framesOk;               Note = $framesNote }
        [pscustomobject]@{ Check = 'FIRST_FRAME';   Pass = $firstFrameOk;            Note = $firstFrameNote }
        [pscustomobject]@{ Check = 'NO_CRASH';      Pass = $crashOk;                 Note = $crashNote }
        [pscustomobject]@{ Check = 'SCREENSHOT';    Pass = $shotOk;                  Note = $shotNote }
    )
    $passed = @($checks | Where-Object { $_.Pass }).Count
    $total = $checks.Count

    $summaryLines = [System.Collections.Generic.List[string]]::new()
    $summaryLines.Add('RustDesk Java device verification')
    $summaryLines.Add("time:    $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $summaryLines.Add("device:  $serial")
    $summaryLines.Add("peer id: $Id")
    $summaryLines.Add("apk:     $apkPath")
    $summaryLines.Add("logcat:  $logPath")
    $summaryLines.Add("live:    $logLivePath")
    $summaryLines.Add('')
    $summaryLines.Add(('{0,-16} {1,-7} {2}' -f 'CHECK', 'RESULT', 'NOTE'))
    foreach ($check in $checks) {
        $result = if ($check.Pass) { 'PASS' } else { 'FAIL' }
        $summaryLines.Add(('{0,-16} {1,-7} {2}' -f $check.Check, $result, $check.Note))
    }
    $summaryLines.Add('')
    $summaryLines.Add("RESULT: $passed/$total PASSED")
    if ($Rendezvous -ne $script:DefaultRendezvous) {
        $summaryLines.Add("NOTE: -Host $Rendezvous 未生效；MainActivity 不转发 server extra，RemoteActivity 使用默认 $script:DefaultRendezvous。")
    }
    $summaryPath = Join-Path $outDirFull 'summary.txt'
    [System.IO.File]::WriteAllText($summaryPath, ($summaryLines -join "`r`n"), [System.Text.UTF8Encoding]::new($false))

    Write-Host ''
    Write-Host ('{0,-16} {1,-7} {2}' -f 'CHECK', 'RESULT', 'NOTE')
    Write-Host ('{0,-16} {1,-7} {2}' -f ('-' * 16), ('-' * 7), ('-' * 48))
    foreach ($check in $checks) {
        $result = if ($check.Pass) { 'PASS' } else { 'FAIL' }
        $color = if ($check.Pass) { 'Green' } else { 'Red' }
        Write-Host ('{0,-16} {1,-7} {2}' -f $check.Check, $result, $check.Note) -ForegroundColor $color
    }
    Write-Host ''
    if ($Rendezvous -ne $script:DefaultRendezvous) {
        Write-Host "NOTE: -Host $Rendezvous 未生效：MainActivity 只读取 id/password（MainActivity.java:82,87），" -ForegroundColor Yellow
        Write-Host "      不转发 server extra；RemoteActivity 的默认值是 $script:DefaultRendezvous（RemoteActivity.java:23）。" -ForegroundColor Yellow
    }
    Write-Host 'NOTE: 收帧改由 RemoteActivity 日志断言：每 5s 一条 stats: frames=<n> bytes=<n> deltaFrames=<d>，' -ForegroundColor DarkGray
    Write-Host '      要求 ≥2 条 deltaFrames>0（FRAMES_MOVING）；first frame received 恰好 1 次（FIRST_FRAME）。' -ForegroundColor DarkGray
    Write-Host ''
    if ($passed -eq $total) {
        Write-Host "RESULT: $passed/$total PASSED" -ForegroundColor Green
    } else {
        Write-Host "RESULT: $passed/$total PASSED" -ForegroundColor Red
    }

    Write-Host ''
    Write-Host '产物（绝对路径）：'
    Write-Host "  OUTDIR:  $outDirFull"
    Write-Host "  LOGCAT:  $logPath"
    Write-Host "  LIVE:    $logLivePath"
    Write-Host "  DUMPSYS: $dumpsysPath"
    $shotList = ($shotItems | ForEach-Object { $_.Name }) -join ', '
    Write-Host "  SHOTS:   $($shotItems.Count) 张 [$shotList]"
    Write-Host "  SUMMARY: $summaryPath"
    Write-Host "  APK:     $apkPath"

    if ($passed -eq $total) { exit 0 }
    exit 1
} catch {
    Stop-LogcatProcess -Process $script:LogProc
    Write-Host ''
    Write-Host "ERROR: 验证脚本中止：$($_.Exception.Message)" -ForegroundColor Red
    exit 1
} finally {
    Stop-LogcatProcess -Process $script:LogProc
}
