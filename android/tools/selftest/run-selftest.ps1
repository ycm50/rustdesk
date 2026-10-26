#Requires -Version 7.0
<#
.SYNOPSIS
    Offline JVM self-test for the pure-Java RustDesk Android app.

.DESCRIPTION
    Compiles the Android-independent sources plus the migrated regression suites in
    tools\selftest\suites and runs them in-process. Needs no phone, no Android SDK, no
    Gradle and no network (unless -IncludeNetwork is used).

    The suites are compiled in two dependency groups, so a full run costs two javac
    processes instead of one per suite:

      A  protobuf only        TerminalMsgTest, ClipSyncTest, FileMsgTest, KbSelfTest,
                              IdCheckTest
      B  protobuf + bcprov    crypto.SelfTest, HostSessionTest, PeerHandshakeInteropTest,
                              Ed25519Test, LoopbackTest (and RelayTest with -IncludeNetwork)

    LoopbackTest lives in group B because it signs the fake host's SignedId with
    BouncyCastle directly; it cannot compile against protobuf alone.

    Sources are never modified. The output directory is wiped on every run, so repeated
    runs always start from a clean state.

.PARAMETER IncludeNetwork
    Additionally run RelayTest against a real rendezvous/relay server. Requires -PeerId.
    RelayTest is the only suite that still lives outside the repository (tmpscripts).

.PARAMETER Server
    Rendezvous server for RelayTest.

.PARAMETER PeerId
    Remote peer id for RelayTest. Required when -IncludeNetwork is set.

.PARAMETER Password
    Password for RelayTest. Default 123456 (the probe's own default).

.PARAMETER JdkHome
    JDK directory (needs bin\javac.exe). Falls back to $env:JAVA_HOME.

.PARAMETER GradleCache
    Root of the Gradle module cache to search for the two jars. Default
    $env:GRADLE_USER_HOME (or %USERPROFILE%\.gradle) + \caches\modules-2\files-2.1.

.PARAMETER TmpRoot
    Directory holding the temporary probe sources / build output. Default is the
    tmpscripts directory next to the project (..\..\tmpscripts from the project root).

.PARAMETER ProbeDir
    Directory with RelayTest.java (the only network suite left in tmpscripts). Default
    <TmpRoot>\rdprobe.

.PARAMETER OutDir
    Compilation output directory. Default <TmpRoot>\selftest\out. Wiped on every run.

.PARAMETER TimeoutSeconds
    Per-process timeout for javac and for each suite.

.EXAMPLE
    pwsh -File tools\selftest\run-selftest.ps1

.EXAMPLE
    pwsh -File tools\selftest\run-selftest.ps1 -IncludeNetwork -PeerId 123456789 -Password secret
#>
[CmdletBinding()]
param(
    [switch]$IncludeNetwork,
    [string]$Server = 'rs-ny.rustdesk.com',
    [string]$PeerId = '',
    [string]$Password = '123456',

    [string]$JdkHome = 'A:\jdk-17.0.12',
    [string]$GradleCache = '',
    [string]$TmpRoot = '',
    [string]$ProbeDir = '',
    [string]$OutDir = '',
    [int]$TimeoutSeconds = 120
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Stop-WithMessage {
    param([string]$Message, [int]$Code = 2)
    [Console]::Error.WriteLine("ERROR: $Message")
    exit $Code
}

function Invoke-Native {
    param(
        [Parameter(Mandatory)][string]$FilePath,
        [string[]]$Arguments = @(),
        [Parameter(Mandatory)][string]$WorkingDirectory,
        [Parameter(Mandatory)][int]$TimeoutSeconds
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new($FilePath)
    foreach ($arg in $Arguments) { $psi.ArgumentList.Add($arg) }
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    $psi.StandardOutputEncoding = $utf8
    $psi.StandardErrorEncoding = $utf8

    $proc = [System.Diagnostics.Process]::new()
    $proc.StartInfo = $psi
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        [void]$proc.Start()
    } catch {
        Stop-WithMessage "cannot start $FilePath : $($_.Exception.Message)"
    }
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()

    $timedOut = $false
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        $timedOut = $true
        try { $proc.Kill($true) } catch { }
        [void]$proc.WaitForExit(5000)
    }
    [void]$outTask.Wait(5000)
    [void]$errTask.Wait(5000)
    [void]$proc.WaitForExit(5000)
    $watch.Stop()

    $exitCode = -1
    try { $exitCode = $proc.ExitCode } catch { }
    $stdout = if ($outTask.IsCompletedSuccessfully) { $outTask.Result } else { '' }
    $stderr = if ($errTask.IsCompletedSuccessfully) { $errTask.Result } else { '' }
    $proc.Dispose()

    return [pscustomobject]@{
        ExitCode = $exitCode
        StdOut   = $stdout
        StdErr   = $stderr
        TimedOut = $timedOut
        Seconds  = $watch.Elapsed.TotalSeconds
    }
}

function Merge-Output {
    param([Parameter(Mandatory)]$Run)
    $text = $Run.StdOut
    if ($Run.StdErr) {
        if ($text -and -not $text.EndsWith("`n")) { $text += "`n" }
        $text += $Run.StdErr
    }
    return $text
}

# Suites that print "checks: N" report it themselves; the older ones only emit one
# "[PASS] ..." line per check, so count those instead.
function Get-CheckCount {
    param([string]$Text)
    if (-not $Text) { return 0 }
    $match = [regex]::Match($Text, '(?im)^\s*checks:\s*(\d+)')
    if ($match.Success) { return [int]$match.Groups[1].Value }
    return [regex]::Matches($Text, '(?m)^\[PASS\]').Count
}

# ---------------------------------------------------------------- layout
$ProjectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$JavaRoot = Join-Path $ProjectRoot 'app\src\main\java'
if (-not (Test-Path -LiteralPath (Join-Path $JavaRoot 'hbb'))) {
    Stop-WithMessage "source root not found: $JavaRoot"
}

$SuiteDir = Join-Path $PSScriptRoot 'suites'
if (-not (Test-Path -LiteralPath $SuiteDir)) {
    Stop-WithMessage "suite source directory not found: $SuiteDir"
}

if (-not $TmpRoot) {
    $downloads = Split-Path -Parent (Split-Path -Parent $ProjectRoot)
    $TmpRoot = Join-Path $downloads 'tmpscripts'
}
if (-not $ProbeDir) { $ProbeDir = Join-Path $TmpRoot 'rdprobe' }
if (-not $OutDir) { $OutDir = Join-Path $TmpRoot 'selftest\out' }

$relaySource = Join-Path $ProbeDir 'RelayTest.java'

if ($IncludeNetwork -and -not $PeerId) {
    # RelayTest needs an online peer, but the in-repo network suite brings its own host, so a
    # missing -PeerId only skips that one suite instead of failing the whole run.
    Write-Warning '-IncludeNetwork without -PeerId: RelayTest is skipped, the repository network suites still run.'
}

# ---------------------------------------------------------------- JDK
$jdkCandidates = @()
if ($JdkHome) { $jdkCandidates += $JdkHome }
if ($env:JAVA_HOME) { $jdkCandidates += $env:JAVA_HOME }
$jdk = $null
foreach ($candidate in $jdkCandidates) {
    if ($candidate -and (Test-Path -LiteralPath (Join-Path $candidate 'bin\javac.exe'))) {
        $jdk = $candidate
        break
    }
}
if (-not $jdk) {
    Stop-WithMessage ("no JDK with bin\javac.exe found. Tried: {0}. Pass -JdkHome." -f (
        ($jdkCandidates | Where-Object { $_ }) -join ', '))
}
$javacExe = Join-Path $jdk 'bin\javac.exe'
$javaExe = Join-Path $jdk 'bin\java.exe'
if (-not (Test-Path -LiteralPath $javaExe)) {
    Stop-WithMessage "JDK found but bin\java.exe missing: $javaExe"
}

# ---------------------------------------------------------------- jars
if (-not $GradleCache) {
    $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else {
        Join-Path $env:USERPROFILE '.gradle'
    }
    $GradleCache = Join-Path $gradleHome 'caches\modules-2\files-2.1'
}

function Find-DependencyJar {
    param([string]$FileName)
    if (-not (Test-Path -LiteralPath $GradleCache)) { return $null }
    $hit = Get-ChildItem -LiteralPath $GradleCache -Recurse -File -Filter $FileName -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($hit) { return $hit.FullName }
    return $null
}

$protobufJar = Find-DependencyJar 'protobuf-javalite-3.20.1.jar'
if (-not $protobufJar) {
    Stop-WithMessage ("protobuf-javalite-3.20.1.jar not found under '{0}'. Run one Gradle/Android Studio sync or pass -GradleCache." -f $GradleCache)
}
$bcJar = Find-DependencyJar 'bcprov-jdk18on-1.79.jar'
if (-not $bcJar) {
    Stop-WithMessage ("bcprov-jdk18on-1.79.jar not found under '{0}'. Run one Gradle/Android Studio sync or pass -GradleCache." -f $GradleCache)
}
# net/WsTransport.java drives the Cloudflare tunnel over OkHttp, so the WebSocket jar has to be on
# the JVM compile classpath too; without it the whole B group fails to compile.
$okhttpJar = Find-DependencyJar 'okhttp-4.12.0.jar'
if (-not $okhttpJar) {
    Stop-WithMessage ("okhttp-4.12.0.jar not found under '{0}'. Run one Gradle/Android Studio sync or pass -GradleCache." -f $GradleCache)
}
$okioJar = Find-DependencyJar 'okio-jvm-3.6.0.jar'
if (-not $okioJar) {
    Stop-WithMessage ("okio-jvm-3.6.0.jar not found under '{0}'. Run one Gradle/Android Studio sync or pass -GradleCache." -f $GradleCache)
}

# ---------------------------------------------------------------- groups
# Each group is one javac process. A group lists the project source directories it needs
# (plus single files outside those directories, and files to skip inside them) and the
# jars on its classpath; the suites themselves are assigned by their Group field below.
$groups = [ordered]@{
    'A' = [pscustomobject]@{
        Label        = 'protobuf only'
        Jars         = @($protobufJar)
        ProjectDirs  = @('hbb')
        ProjectFiles = @(
            'com\rustdesk\java\session\ClipboardMessages.java',
            'com\rustdesk\java\session\FileMessages.java',
            'com\rustdesk\java\session\InputMessages.java',
            'com\rustdesk\java\session\TerminalMessages.java'
        )
        Excluded     = @()
        HelperSources = @('PeerIdRules.java')   # suite-local helper, never a suite itself
    }
    'B' = [pscustomobject]@{
        Label        = 'protobuf + bcprov'
        Jars         = @($protobufJar, $bcJar, $okhttpJar, $okioJar)
        ProjectDirs  = @(
            'hbb',
            'com\rustdesk\java\crypto',
            'com\rustdesk\java\net',
            'com\rustdesk\java\session'
        )
        ProjectFiles = @(
            'com\rustdesk\java\host\HostSession.java',
            'com\rustdesk\java\host\HostInputSink.java',
            'com\rustdesk\java\host\HostClipboardSink.java',
            'com\rustdesk\java\host\HostRegistration.java',
            'com\rustdesk\java\host\HostAgent.java'
        )
        # Android-API dependent; see README.md "files excluded from the JVM build".
        Excluded     = @(
            'com\rustdesk\java\session\Session.java',        # imports codec.VideoDecoder -> android.media.MediaCodec
            'com\rustdesk\java\session\DeviceIdentity.java'  # android.content.Context / SharedPreferences
        )
        HelperSources = @()
    }
}

# ---------------------------------------------------------------- suites
$passAll = '(?m)^RESULT: ALL PASSED\s*$'
$suites = @(
    [pscustomobject]@{
        Group       = 'A'
        Name        = 'TerminalMsgTest'
        Main        = 'com.rustdesk.java.selftest.TerminalMsgTest'
        Source      = Join-Path $SuiteDir 'TerminalMsgTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'A'
        Name        = 'ClipSyncTest'
        Main        = 'com.rustdesk.java.selftest.ClipSyncTest'
        Source      = Join-Path $SuiteDir 'ClipSyncTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'A'
        Name        = 'FileMsgTest'
        Main        = 'com.rustdesk.java.selftest.FileMsgTest'
        Source      = Join-Path $SuiteDir 'FileMsgTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'A'
        Name        = 'KbSelfTest'
        Main        = 'com.rustdesk.java.selftest.KbSelfTest'
        Source      = Join-Path $SuiteDir 'KbSelfTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'A'
        Name        = 'IdCheckTest'
        Main        = 'com.rustdesk.java.selftest.IdCheckTest'
        Source      = Join-Path $SuiteDir 'IdCheckTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'crypto.SelfTest'
        Main        = 'com.rustdesk.java.crypto.SelfTest'
        Source      = $null
        Network     = $false
        Arguments   = { @() }
        PassPattern = '(?m)^RESULT: ALL TESTS PASSED\s*$'
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'HostSessionTest'
        Main        = 'com.rustdesk.java.selftest.HostSessionTest'
        Source      = Join-Path $SuiteDir 'HostSessionTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'PeerHandshakeInteropTest'
        Main        = 'com.rustdesk.java.selftest.PeerHandshakeInteropTest'
        Source      = Join-Path $SuiteDir 'PeerHandshakeInteropTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'Ed25519Test'
        Main        = 'com.rustdesk.java.selftest.Ed25519Test'
        Source      = Join-Path $SuiteDir 'Ed25519Test.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'LoopbackTest'
        Main        = 'com.rustdesk.java.selftest.LoopbackTest'
        Source      = Join-Path $SuiteDir 'LoopbackTest.java'
        Network     = $false
        Arguments   = { @() }
        PassPattern = $passAll
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'RelayTest'
        Main        = 'RelayTest'
        Source      = $relaySource
        Network     = $true
        Arguments   = { if ($Password) { @($Server, $PeerId, $Password) } else { @($Server, $PeerId) } }
        PassPattern = '(?m)^RESULT: RELAY SESSION WORKS'
    },
    [pscustomobject]@{
        Group       = 'B'
        Name        = 'HostEndToEnd'
        Main        = 'com.rustdesk.java.selftest.HostEndToEnd'
        Source      = Join-Path $SuiteDir 'HostEndToEnd.java'
        Network     = $true
        Arguments   = { @() }
        PassPattern = $passAll
    }
)

$activeSuites = @($suites | Where-Object {
    ((-not $_.Network) -or $IncludeNetwork) -and
    ($_.Name -ne 'RelayTest' -or [bool]$PeerId)
})
foreach ($suite in $activeSuites) {
    if ($suite.Source -and -not (Test-Path -LiteralPath $suite.Source)) {
        Stop-WithMessage "suite source not found: $($suite.Source)"
    }
}

$activeGroups = @()
foreach ($key in $groups.Keys) {
    if (@($activeSuites | Where-Object { $_.Group -eq $key }).Count -gt 0) {
        $activeGroups += $key
    }
}

function Get-GroupSources {
    param([Parameter(Mandatory)]$Group)
    $list = @()
    foreach ($relativeDir in $Group.ProjectDirs) {
        $dir = Join-Path $JavaRoot $relativeDir
        if (-not (Test-Path -LiteralPath $dir)) {
            Stop-WithMessage "expected source directory missing: $dir"
        }
        foreach ($file in Get-ChildItem -LiteralPath $dir -File -Filter '*.java') {
            $relative = $file.FullName.Substring($JavaRoot.Length + 1)
            if ($Group.Excluded -notcontains $relative) {
                $list += $file.FullName
            }
        }
    }
    foreach ($relative in $Group.ProjectFiles) {
        $path = Join-Path $JavaRoot $relative
        if (-not (Test-Path -LiteralPath $path)) {
            Stop-WithMessage "expected source file missing: $path"
        }
        $list += $path
    }
    return @($list)
}

# ---------------------------------------------------------------- clean output
$fullOut = [System.IO.Path]::GetFullPath($OutDir)
$fullRoot = [System.IO.Path]::GetFullPath($ProjectRoot)
$pathRoot = [System.IO.Path]::GetPathRoot($fullOut)
if ($fullOut.TrimEnd('\', '/') -eq $pathRoot.TrimEnd('\', '/') -or
    $fullOut.TrimEnd('\', '/') -eq $fullRoot.TrimEnd('\', '/')) {
    Stop-WithMessage "refusing to wipe output directory '$fullOut'"
}
if (Test-Path -LiteralPath $fullOut) {
    Remove-Item -LiteralPath $fullOut -Recurse -Force
}
$logDir = Join-Path $fullOut 'logs'
New-Item -ItemType Directory -Path $logDir -Force | Out-Null

Write-Output 'RustDesk Java self-test (offline: no phone, no Android SDK, no Gradle)'
Write-Output "  jdk: $jdk"
Write-Output "  suites: $SuiteDir"
Write-Output "  out: $fullOut"
Write-Output ''

# ---------------------------------------------------------------- compile
$compileState = @{}
foreach ($key in $activeGroups) {
    $group = $groups[$key]
    $groupOut = Join-Path $fullOut $key
    New-Item -ItemType Directory -Path $groupOut -Force | Out-Null

    $sources = @(Get-GroupSources -Group $group)
    foreach ($suite in $activeSuites) {
        if ($suite.Group -eq $key -and $suite.Source) { $sources += $suite.Source }
    }
    foreach ($helper in $group.HelperSources) {
        $helperPath = Join-Path $SuiteDir $helper
        if (-not (Test-Path -LiteralPath $helperPath)) {
            Stop-WithMessage "suite helper source not found: $helperPath"
        }
        $sources += $helperPath
    }
    $sources = @($sources | Sort-Object -Unique)

    $groupCp = ($group.Jars -join ';')
    $javacArgs = @(
        '-J-Duser.language=en',
        '-J-Duser.country=US',
        '-J-Dfile.encoding=UTF-8',
        '-J-Dstdout.encoding=UTF-8',
        '-J-Dstderr.encoding=UTF-8',
        '-encoding', 'UTF-8',
        '-cp', $groupCp,
        '-d', $groupOut
    ) + $sources

    $compile = Invoke-Native -FilePath $javacExe -Arguments $javacArgs -WorkingDirectory $ProjectRoot -TimeoutSeconds $TimeoutSeconds
    $compileText = Merge-Output -Run $compile
    if ($compile.ExitCode -ne 0 -or $compile.TimedOut) {
        Write-Output ("[FAIL] compile {0} ({1}) ({2:0.00}s)" -f $key, $group.Label, $compile.Seconds)
        Write-Output $compileText
        Write-Output ''
        Write-Output ("RESULT: 0/{0} PASSED" -f $activeSuites.Count)
        exit 1
    }
    Write-Output ("compile {0} ({1}): {2} sources ({3:0.00}s)" -f $key, $group.Label, $sources.Count, $compile.Seconds)

    $compileState[$key] = [pscustomobject]@{ Out = $groupOut; ClassPath = $groupCp }
}
Write-Output ''

# ---------------------------------------------------------------- run
$results = @()
foreach ($suite in $activeSuites) {
    $state = $compileState[$suite.Group]
    $runClasspath = "$($state.Out);$($state.ClassPath)"

    $extra = @()
    $rawArgs = & $suite.Arguments
    foreach ($item in $rawArgs) {
        if ($null -ne $item) { $extra += [string]$item }
    }

    $javaArgs = @(
        '-Dfile.encoding=UTF-8',
        '-Dstdout.encoding=UTF-8',
        '-Dstderr.encoding=UTF-8',
        '-Dsun.stdout.encoding=UTF-8',
        '-Dsun.stderr.encoding=UTF-8',
        '-cp', $runClasspath,
        $suite.Main
    ) + $extra

    $run = Invoke-Native -FilePath $javaExe -Arguments $javaArgs -WorkingDirectory $ProjectRoot -TimeoutSeconds $TimeoutSeconds
    $text = Merge-Output -Run $run
    $logPath = Join-Path $logDir ("{0}.log" -f $suite.Name)
    Set-Content -LiteralPath $logPath -Value $text -Encoding utf8

    $passed = (-not $run.TimedOut) -and ($run.ExitCode -eq 0) -and
        ($text -match $suite.PassPattern) -and ($text -notmatch '(?m)^\[FAIL\]')

    $failure = ''
    if (-not $passed) {
        $failLines = @($text -split "`r?`n" | Where-Object { $_ -match '^\[FAIL\]' } | Select-Object -First 3)
        if ($run.TimedOut) {
            $failure = "timed out after ${TimeoutSeconds}s"
        } elseif ($failLines.Count -gt 0) {
            $failure = (($failLines -join '; ') -replace '^\[FAIL\]\s*', '')
        } elseif ($run.ExitCode -ne 0) {
            $failure = "exit code $($run.ExitCode)"
        } else {
            $failure = "missing marker: $($suite.PassPattern)"
        }
        if ($failure.Length -gt 100) { $failure = $failure.Substring(0, 97) + '...' }
    }

    $checks = Get-CheckCount -Text $text
    $status = if ($passed) { 'OK' } else { 'FAIL' }
    $checkText = if ($checks -gt 0) { "checks: $checks" } else { 'checks: n/a' }
    Write-Output ("[{0}] {1} ({2:0.00}s) {3}" -f $status, $suite.Name, $run.Seconds, $checkText)

    $results += [pscustomobject]@{
        Name     = $suite.Name
        Group    = $suite.Group
        Status   = $status
        Passed   = $passed
        Seconds  = $run.Seconds
        Checks   = $checks
        Failure  = $failure
        LogPath  = $logPath
        Output   = $text
        ExitCode = $run.ExitCode
    }
}

# ---------------------------------------------------------------- summary
$nameWidth = 18
foreach ($result in $results) {
    if ($result.Name.Length -gt $nameWidth) { $nameWidth = $result.Name.Length }
}
$nameWidth += 2
$rowFormat = "{0,-$nameWidth} {1,-7} {2,9} {3,7}  {4}"

Write-Output ''
Write-Output 'SUITE SUMMARY'
Write-Output ($rowFormat -f 'SUITE', 'STATUS', 'TIME(s)', 'CHECKS', 'FAILURE')
Write-Output ($rowFormat -f ('-' * $nameWidth), ('-' * 7), ('-' * 9), ('-' * 7), ('-' * 7))
foreach ($result in $results) {
    $failureCell = if ($result.Failure) { $result.Failure } else { '-' }
    $tableStatus = if ($result.Passed) { 'PASS' } else { 'FAIL' }
    $checksCell = if ($result.Checks -gt 0) { [string]$result.Checks } else { '-' }
    Write-Output ($rowFormat -f $result.Name, $tableStatus, ('{0:0.00}' -f $result.Seconds), $checksCell, $failureCell)
}

$failed = @($results | Where-Object { -not $_.Passed })
if ($failed.Count -gt 0) {
    foreach ($result in $failed) {
        Write-Output ''
        Write-Output ("--- {0} output (exit {1}, log {2}) ---" -f $result.Name, $result.ExitCode, $result.LogPath)
        Write-Output $result.Output
    }
}

$totalChecks = 0
foreach ($result in $results) { $totalChecks += $result.Checks }
$passedCount = @($results | Where-Object { $_.Passed }).Count
Write-Output ''
Write-Output ("TOTAL: {0} checks in {1} suites" -f $totalChecks, $results.Count)
Write-Output ("RESULT: {0}/{1} PASSED" -f $passedCount, $results.Count)
if ($passedCount -ne $results.Count) { exit 1 }
exit 0

