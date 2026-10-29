#Requires -Version 7.0
<#
.SYNOPSIS
    Build (and optionally install) the pure-Java RustDesk Android APK.

.DESCRIPTION
    Locates Gradle, a JDK and the Android SDK, runs assembleDebug / assembleRelease with
    --console=plain, and reports the resulting APK path and size. Only the last 20 lines of the
    Gradle output are printed unless -Verbose is given.

    -Install pushes the APK to /data/local/tmp, runs `pm install -r` there and starts
    com.rustdesk.java/.MainActivity. /sdcard is intentionally not used because it is a FUSE
    mount and package installation from it fails.

.PARAMETER Release
    Build assembleRelease instead of assembleDebug. An unsigned release APK cannot be installed.

.PARAMETER Install
    Install the built APK on the one connected adb device and start the app.

.PARAMETER GradlePath
    gradle.bat, a Gradle bin directory, or a Gradle home. Defaults to $env:GRADLE_HOME, then the
    newest installed Gradle wrapper distribution under %USERPROFILE%\.gradle\wrapper\dists.

.PARAMETER JdkHome
    JDK directory (must contain bin\java.exe and bin\javac.exe). Defaults to $env:JAVA_HOME, then
    A:\jdk-17.0.12.

.PARAMETER Verbose
    Print the complete Gradle output instead of the last 20 lines.

.EXAMPLE
    .\tools\build-apk.ps1

.EXAMPLE
    .\tools\build-apk.ps1 -Release

.EXAMPLE
    .\tools\build-apk.ps1 -Install
#>
param(
    [switch]$Release,
    [switch]$Install,
    [string]$GradlePath = '',
    [string]$JdkHome = '',
    [switch]$Verbose
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$DefaultJdk = 'A:\jdk-17.0.12'
$DefaultSdk = 'C:\Users\Administrator\AppData\Local\Android\Sdk'

function Stop-Environment {
    param([Parameter(Mandatory)][string]$Message)
    [Console]::Error.WriteLine("ERROR: $Message")
    exit 2
}

function Stop-Build {
    param([Parameter(Mandatory)][string]$Message)
    [Console]::Error.WriteLine("ERROR: $Message")
    exit 1
}

function Resolve-ProjectPath {
    param([Parameter(Mandatory)][string]$Path)
    $expanded = [Environment]::ExpandEnvironmentVariables($Path)
    if (-not [System.IO.Path]::IsPathRooted($expanded)) {
        $expanded = Join-Path $ProjectRoot $expanded
    }
    return [System.IO.Path]::GetFullPath($expanded)
}

function Find-GradleBat {
    param([Parameter(Mandatory)][string]$Path)
    $candidate = Resolve-ProjectPath $Path
    if (Test-Path -LiteralPath $candidate -PathType Leaf) {
        if ($candidate -match '(?i)gradle(\.bat)?$') {
            return $candidate
        }
        return $null
    }
    if (Test-Path -LiteralPath $candidate -PathType Container) {
        foreach ($relative in @('gradle.bat', 'bin\gradle.bat')) {
            $bat = Join-Path $candidate $relative
            if (Test-Path -LiteralPath $bat -PathType Leaf) {
                return $bat
            }
        }
    }
    return $null
}

function Resolve-Gradle {
    param([string]$ExplicitPath)

    if ($ExplicitPath) {
        $bat = Find-GradleBat $ExplicitPath
        if (-not $bat) {
            Stop-Environment "-GradlePath 无效：'$ExplicitPath' 不是 gradle.bat、Gradle bin 目录或 Gradle 主目录。"
        }
        return $bat
    }

    if ($env:GRADLE_HOME) {
        $bat = Find-GradleBat $env:GRADLE_HOME
        if ($bat) {
            return $bat
        }
        Write-Warning "GRADLE_HOME 已设置但未找到 gradle.bat：$env:GRADLE_HOME（继续查找 dists）"
    }

    $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else {
        Join-Path $env:USERPROFILE '.gradle'
    }
    $distsRoot = Join-Path $gradleUserHome 'wrapper\dists'
    $pattern = Join-Path $distsRoot 'gradle-*-bin\*\gradle-*\bin\gradle.bat'
    $candidates = @(
        Get-ChildItem -Path $pattern -File -ErrorAction SilentlyContinue |
            Sort-Object -Property @{ Expression = { $_.LastWriteTimeUtc }; Descending = $true },
                @{ Expression = { $_.FullName }; Descending = $true }
    )
    if ($candidates.Count -eq 0) {
        Stop-Environment ("找不到 Gradle。已检查 -GradlePath（未提供）、GRADLE_HOME（{0}）和 {1}。" -f `
            ($(if ($env:GRADLE_HOME) { $env:GRADLE_HOME } else { '未设置' })), $pattern)
    }
    return $candidates[0].FullName
}

function Resolve-Jdk {
    param([string]$ExplicitPath)

    $candidates = @()
    if ($ExplicitPath) {
        $candidates += [pscustomobject]@{ Path = (Resolve-ProjectPath $ExplicitPath); Source = '-JdkHome'; Explicit = $true }
    }
    if ($env:JAVA_HOME) {
        $candidates += [pscustomobject]@{ Path = (Resolve-ProjectPath $env:JAVA_HOME); Source = 'JAVA_HOME'; Explicit = $false }
    }
    $candidates += [pscustomobject]@{ Path = $DefaultJdk; Source = 'default'; Explicit = $false }

    $tried = @()
    foreach ($candidate in $candidates) {
        $tried += "$($candidate.Path) ($($candidate.Source))"
        $java = Join-Path $candidate.Path 'bin\java.exe'
        $javac = Join-Path $candidate.Path 'bin\javac.exe'
        if ((Test-Path -LiteralPath $java -PathType Leaf) -and
            (Test-Path -LiteralPath $javac -PathType Leaf)) {
            return $candidate.Path
        }
        if ($candidate.Explicit) {
            Stop-Environment "-JdkHome 无效：'$($candidate.Path)' 缺少 bin\java.exe 或 bin\javac.exe。"
        }
    }

    Stop-Environment ("找不到 JDK。已检查：{0}。可用 -JdkHome 指定。" -f ($tried -join ', '))
}

function Test-AndroidSdk {
    param([Parameter(Mandatory)][string]$Path)
    return (Test-Path -LiteralPath (Join-Path $Path 'platforms') -PathType Container) -and
        (Test-Path -LiteralPath (Join-Path $Path 'build-tools') -PathType Container)
}

function Resolve-AndroidSdk {
    # Keep an already valid ANDROID_HOME untouched, as requested. ANDROID_SDK_ROOT is accepted as a
    # fallback because Android Studio sometimes sets only that variable.
    foreach ($candidate in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)) {
        if ($candidate) {
            $resolved = Resolve-ProjectPath $candidate
            if (Test-AndroidSdk $resolved) {
                return $resolved
            }
        }
    }
    if (Test-AndroidSdk $DefaultSdk) {
        return $DefaultSdk
    }
    Stop-Environment ("找不到 Android SDK。已检查 ANDROID_HOME='{0}'、ANDROID_SDK_ROOT='{1}' 和默认路径 '{2}'。" -f `
        $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, $DefaultSdk)
}

function Get-ApkArtifact {
    param([Parameter(Mandatory)][string]$Variant)

    $outDir = Join-Path $ProjectRoot "app\build\outputs\apk\$Variant"
    if (-not (Test-Path -LiteralPath $outDir -PathType Container)) {
        Stop-Build "构建结束但 APK 输出目录不存在：$outDir"
    }

    $preferred = Join-Path $outDir "app-$Variant.apk"
    if (Test-Path -LiteralPath $preferred -PathType Leaf) {
        return Get-Item -LiteralPath $preferred
    }

    $apk = @(Get-ChildItem -LiteralPath $outDir -File -Filter '*.apk' -ErrorAction SilentlyContinue |
        Sort-Object -Property LastWriteTimeUtc, Name -Descending | Select-Object -First 1)
    if ($apk.Count -eq 0) {
        Stop-Build "构建结束但 $outDir 下没有 APK。"
    }
    return $apk[0]
}

function Find-ApkSigner {
    param([Parameter(Mandatory)][string]$Sdk)
    $buildTools = Join-Path $Sdk 'build-tools'
    $signer = Get-ChildItem -LiteralPath $buildTools -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Property Name -Descending |
        ForEach-Object { Join-Path $_.FullName 'apksigner.bat' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
        Select-Object -First 1
    return $signer
}

function Install-Apk {
    param(
        [Parameter(Mandatory)][string]$Sdk,
        [Parameter(Mandatory)][System.IO.FileInfo]$Apk
    )

    $adb = Join-Path $Sdk 'platform-tools\adb.exe'
    if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) {
        Stop-Environment "找不到 adb：$adb"
    }

    $deviceLines = @(& $adb devices 2>&1 | ForEach-Object { $_.ToString() })
    if ($LASTEXITCODE -ne 0) {
        Stop-Environment "adb devices 失败（exit $LASTEXITCODE）。"
    }
    $online = @($deviceLines | ForEach-Object {
        if ($_ -match '^\s*(\S+)\s+device\s*$') { $matches[1] }
    })
    if ($online.Count -ne 1) {
        $states = @($deviceLines | Where-Object { $_ -match '^\s*\S+\s+\S+' } |
            ForEach-Object { $_.Trim() })
        $detail = if ($states.Count -gt 0) { $states -join '; ' } else { '无设备' }
        Stop-Environment ("-Install 需要恰好一台在线设备，当前 {0} 台。adb devices：{1}" -f $online.Count, $detail)
    }

    $serial = $online[0]
    $remote = "/data/local/tmp/$($Apk.Name)"
    Write-Output "Installing on $serial ..."

    $push = @(& $adb -s $serial push $Apk.FullName $remote 2>&1 | ForEach-Object { $_.ToString() })
    $pushCode = $LASTEXITCODE
    $push | ForEach-Object { Write-Output $_ }
    if ($pushCode -ne 0) {
        Stop-Build "adb push 失败（exit $pushCode）。"
    }

    # /data/local/tmp is world-readable and not FUSE; pm install from /sdcard is rejected.
    $install = @(& $adb -s $serial shell pm install -r $remote 2>&1 | ForEach-Object { $_.ToString() })
    $installCode = $LASTEXITCODE
    $install | ForEach-Object { Write-Output $_ }
    if ($installCode -ne 0 -or ($install -join "`n") -notmatch '(?m)^Success\s*$') {
        Stop-Build "pm install -r 失败（exit $installCode）。"
    }

    $start = @(& $adb -s $serial shell am start -n com.rustdesk.java/.MainActivity 2>&1 |
        ForEach-Object { $_.ToString() })
    $startCode = $LASTEXITCODE
    $start | ForEach-Object { Write-Output $_ }
    if ($startCode -ne 0 -or ($start -join "`n") -match '(?m)^Error') {
        Stop-Build "APK 已安装但启动 MainActivity 失败（exit $startCode）。"
    }
    Write-Output 'Installed and started: com.rustdesk.java/.MainActivity'
}

function Invoke-Build {
    $gradleBat = Resolve-Gradle -ExplicitPath $GradlePath
    $jdk = Resolve-Jdk -ExplicitPath $JdkHome
    $sdk = Resolve-AndroidSdk

    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = $sdk
    $env:ANDROID_SDK_ROOT = $sdk
    $jdkBin = Join-Path $jdk 'bin'
    if (($env:Path -split ';') -notcontains $jdkBin) {
        $env:Path = "$jdkBin;$env:Path"
    }
    $env:TERM = 'dumb'

    $variant = if ($Release) { 'release' } else { 'debug' }
    $task = if ($Release) { 'assembleRelease' } else { 'assembleDebug' }

    Write-Output 'RustDesk Java Android build'
    Write-Output "  project: $ProjectRoot"
    Write-Output "  gradle:  $gradleBat"
    Write-Output "  java:    $jdk"
    Write-Output "  sdk:     $sdk"
    Write-Output "  task:    $task"
    Write-Output ''

    $gradleArgs = @('-p', $ProjectRoot, $task, '--console=plain')
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $raw = @()
    try {
        $raw = @(& $gradleBat @gradleArgs 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } catch {
        Stop-Environment "无法启动 Gradle：$gradleBat`n$($_.Exception.Message)"
    }
    $watch.Stop()

    if ($Verbose) {
        foreach ($line in $raw) { Write-Output $line }
    } else {
        if ($raw.Count -gt 20) {
            Write-Output "--- Gradle output (last 20 of $($raw.Count) lines; use -Verbose for all) ---"
        }
        foreach ($line in @($raw | Select-Object -Last 20)) { Write-Output $line }
    }

    if ($exitCode -ne 0) {
        Stop-Build "Gradle $task 失败（exit $exitCode，$([math]::Round($watch.Elapsed.TotalSeconds, 1))s）。"
    }

    $apk = Get-ApkArtifact -Variant $variant
    $sizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Output ''
    Write-Output "APK: $($apk.FullName)"
    Write-Output "SIZE: $sizeMb MB"
    Write-Output ("BUILD SUCCESSFUL ({0:0.0}s)" -f $watch.Elapsed.TotalSeconds)

    $releaseUnsigned = $false
    if ($Release) {
        $isSigned = $false
        $apksigner = Find-ApkSigner -Sdk $sdk
        if ($apksigner) {
            $verify = @(& $apksigner verify $apk.FullName 2>&1 | ForEach-Object { $_.ToString() })
            $isSigned = ($LASTEXITCODE -eq 0)
            if (-not $isSigned -and $verify.Count -gt 0) {
                Write-Output "apksigner: $($verify[-1])"
            }
        } else {
            $isSigned = $apk.Name -notmatch '(?i)unsigned'
        }
        if (-not $isSigned) {
            $releaseUnsigned = $true
            Write-Warning 'release 未签名，无法直接安装。请配置 signingConfig 或先用 apksigner 签名。'
        }
    }

    if ($Install) {
        if ($releaseUnsigned) {
            Stop-Build 'release 未签名，无法直接安装。'
        }
        Install-Apk -Sdk $sdk -Apk $apk
    }
}

try {
    Invoke-Build
    exit 0
} catch {
    [Console]::Error.WriteLine("ERROR: $($_.Exception.Message)")
    exit 2
}
