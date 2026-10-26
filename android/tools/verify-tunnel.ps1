<#
.SYNOPSIS
    On-device verification of the Cloudflare-tunnel login fix.

.DESCRIPTION
    Installs the debug APK, launches the viewer against a tunnel hostname, captures logcat and
    asserts the four things that prove the tunnel path works end to end:

      login username=<host>:443    the fix: a bare hostname is rejected by the peer, host:port is not
      status: login ok
      first frame received
      no FATAL EXCEPTION

    The tunnel hostname must be reachable and the host behind it must be listening, otherwise the
    assertions fail for reasons that have nothing to do with the client.

.EXAMPLE
    pwsh -File android\tools\verify-tunnel.ps1 -Id rd.example.com -Password yyy

.NOTES
    Waits for the device for -WaitSeconds so it can be started before plugging the phone in.
#>
param(
    [string]$Serial = '',
    [int]$WaitSeconds = 300,
    [Parameter(Mandatory)][string]$Id,
    [string]$Password = '',
    [string]$Apk = '',
    [string]$Out = '',
    [int]$CaptureSec = 35
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

$androidRoot = Split-Path $PSScriptRoot -Parent
if (-not $Apk) { $Apk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk' }
if (-not $Out) {
    # Timestamped run directory: a previous run that is still holding logcat.txt would
    # otherwise make Set-Content fail with 'file is being used by another process'.
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $Out = Join-Path ([System.IO.Path]::GetTempPath()) "rdjava-tunnel-$stamp"
}
New-Item -ItemType Directory -Force -Path $Out | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments)][string[]]$Args)
    if ($script:Device) { & adb -s $script:Device @Args } else { & adb @Args }
}

# ---------------------------------------------------------------- wait for a device
$deadline = (Get-Date).AddSeconds($WaitSeconds)
$script:Device = $null
while ((Get-Date) -lt $deadline) {
    $lines = @(& adb devices 2>&1 | Where-Object { $_ -match '\tdevice$' })
    $serials = @($lines | ForEach-Object { ($_ -split '\s+')[0] } | Where-Object { $_ })
    if ($serials.Count -gt 0) {
        $script:Device = if ($Serial -and $serials -contains $Serial) { $Serial }
                         elseif ($serials.Count -eq 1) { $serials[0] }
                         else { $null }
        if ($script:Device) { break }
        Write-Host "multiple devices, pass -Serial: $($serials -join ', ')"
        exit 2
    }
    Start-Sleep -Seconds 5
}
if (-not $script:Device) {
    Write-Host "NO DEVICE within ${WaitSeconds}s (adb devices empty)"
    exit 2
}
Write-Host "device: $($script:Device)"

# ---------------------------------------------------------------- install
if (-not (Test-Path -LiteralPath $Apk)) { Write-Host "APK missing: $Apk"; exit 2 }
$apkInfo = Get-Item -LiteralPath $Apk
Write-Host "apk: $($apkInfo.Length) bytes $($apkInfo.LastWriteTime)"
Invoke-Adb push $Apk /data/local/tmp/rdjava.apk | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host 'PUSH FAILED'; exit 1 }
$install = Invoke-Adb shell pm install -r /data/local/tmp/rdjava.apk 2>&1
Write-Host "install: $install"
if ($install -notmatch 'Success') { Write-Host 'INSTALL FAILED'; exit 1 }

# ---------------------------------------------------------------- run and capture
Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell am force-stop com.rustdesk.java | Out-Null
$startArgs = @('shell', 'am', 'start', '-n', 'com.rustdesk.java/.MainActivity', '--es', 'id', $Id)
if ($Password) { $startArgs += @('--es', 'password', $Password) }
Invoke-Adb @startArgs 2>&1 | ForEach-Object { Write-Host "am: $_" }

Start-Sleep -Seconds $CaptureSec

$logPath = Join-Path $Out 'logcat.txt'
Invoke-Adb logcat -d -s 'RDJAVA:*' 'AndroidRuntime:E' 2>&1 | Set-Content -Path $logPath -Encoding UTF8

# Screenshot through a file on the device: piping exec-out straight into a PowerShell redirect
# corrupts the PNG, which is why an earlier attempt produced an unreadable image.
Invoke-Adb shell screencap -p /data/local/tmp/rdjava-verify.png | Out-Null
Invoke-Adb pull /data/local/tmp/rdjava-verify.png (Join-Path $Out 'screen.png') | Out-Null

Write-Host '--- key logcat lines ---'
Get-Content -LiteralPath $logPath | Where-Object {
    $_ -match 'status: |first frame|login username|secured=|closed:|ws open|handshake|FATAL'
} | ForEach-Object { Write-Host $_ }

$log = Get-Content -LiteralPath $logPath -Raw
$checks = [ordered]@{
    'login username=<host>:443' = $log -match 'login username=.*:\d+'
    'status: login ok'          = $log -match 'status: login ok'
    'first frame received'      = $log -match 'first frame received'
    'no FATAL EXCEPTION'        = $log -notmatch 'FATAL EXCEPTION'
}

Write-Host '--- checks ---'
$failed = 0
foreach ($name in $checks.Keys) {
    if ($checks[$name]) {
        Write-Host "$name : PASS"
    } else {
        Write-Host "$name : FAIL"
        $failed++
    }
}
Write-Host "logs: $Out"

if ($failed -eq 0) { Write-Host 'RESULT: DEVICE VERIFY PASSED'; exit 0 }
Write-Host "RESULT: DEVICE VERIFY FAILED ($failed of $($checks.Count))"
exit 1

