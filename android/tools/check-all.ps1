#Requires -Version 7.0
<#
.SYNOPSIS
    Run the offline self-test and the Android build, then print one summary table.

.DESCRIPTION
    Steps:
      1. tools\selftest\run-selftest.ps1 (unless -SkipTests)
      2. tools\build-apk.ps1           (unless -SkipBuild, -Release is forwarded)

    Each step runs in its own pwsh process so its exit code and output are captured independently.
    The final line is always `RESULT: N/N PASSED`, where N counts the steps that actually ran.
    Exit codes: 0 = all executed steps passed, 1 = a step failed, 2 = environment problem.

.PARAMETER SkipBuild
    Do not run the APK build.

.PARAMETER SkipTests
    Do not run the self-test.

.PARAMETER Release
    Forward -Release to build-apk.ps1.

.EXAMPLE
    .\tools\check-all.ps1

.EXAMPLE
    .\tools\check-all.ps1 -SkipTests -Release
#>
param(
    [switch]$SkipBuild,
    [switch]$SkipTests,
    [switch]$Release
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$SelfTestScript = Join-Path $ProjectRoot 'tools\selftest\run-selftest.ps1'
$BuildScript = Join-Path $ProjectRoot 'tools\build-apk.ps1'
$PowerShellExe = Join-Path $PSHOME 'pwsh.exe'
if (-not (Test-Path -LiteralPath $PowerShellExe -PathType Leaf)) {
    $PowerShellExe = (Get-Process -Id $PID).Path
}

function Invoke-ChildScript {
    param(
        [Parameter(Mandatory)][string]$ScriptPath,
        [string[]]$Arguments = @()
    )

    if (-not (Test-Path -LiteralPath $ScriptPath -PathType Leaf)) {
        return [pscustomobject]@{
            ExitCode = 2
            Lines    = @("ERROR: script not found: $ScriptPath")
            Seconds  = 0.0
        }
    }

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $captureFile = [System.IO.Path]::GetTempFileName()
    try {
        # Redirect the child's output to a file instead of piping it back. Capturing a child
        # process through a pipe can block indefinitely in constrained shells, while a file
        # redirect always completes and still gives us the exit code.
        & $PowerShellExe -NoProfile -NonInteractive -File $ScriptPath @Arguments *> $captureFile
        $code = $LASTEXITCODE
        $lines = @(Get-Content -LiteralPath $captureFile -ErrorAction SilentlyContinue |
            ForEach-Object { $_.ToString() })
    } catch {
        $lines = @("ERROR: cannot start ${ScriptPath}: $($_.Exception.Message)")
        $code = 2
    } finally {
        Remove-Item -LiteralPath $captureFile -Force -ErrorAction SilentlyContinue
    }
    $watch.Stop()

    # Gradle and PowerShell may emit ANSI colour sequences; strip them before parsing markers.
    $clean = @($lines | ForEach-Object { $_ -replace "`e\[[0-9;?]*[ -/]*[@-~]", '' })
    return [pscustomobject]@{
        ExitCode = $code
        Lines    = $clean
        Seconds  = $watch.Elapsed.TotalSeconds
    }
}

function Get-ShortFailure {
    param([Parameter(Mandatory)][string[]]$Lines, [Parameter(Mandatory)][int]$ExitCode)

    $failure = @($Lines | Where-Object { $_ -match '^\[FAIL\]|^ERROR:' } | Select-Object -First 2)
    if ($failure.Count -gt 0) {
        $text = (($failure -join '; ') -replace '^\[FAIL\]\s*', '')
    } else {
        $text = "exit $ExitCode"
    }
    if ($text.Length -gt 72) {
        $text = $text.Substring(0, 69) + '...'
    }
    return $text
}

$rows = @()

try {
    if (-not $SkipTests) {
        Write-Output '===== selftest ====='
        $testRun = Invoke-ChildScript -ScriptPath $SelfTestScript
        foreach ($line in $testRun.Lines) { Write-Output $line }
        Write-Output ''

        $testResult = @($testRun.Lines | Where-Object { $_ -match '^RESULT:\s*\d+/\d+\s+PASSED\s*$' } |
            Select-Object -Last 1)
        $testPassed = ($testRun.ExitCode -eq 0) -and ($testResult.Count -eq 1)
        $testStatus = if ($testPassed) { 'PASS' } elseif ($testRun.ExitCode -eq 2) { 'ENV' } else { 'FAIL' }
        $testNote = if ($testPassed) {
            $testResult[0]
        } elseif ($testRun.ExitCode -eq 2) {
            'environment: ' + (Get-ShortFailure -Lines $testRun.Lines -ExitCode 2)
        } else {
            Get-ShortFailure -Lines $testRun.Lines -ExitCode $testRun.ExitCode
        }

        $rows += [pscustomobject]@{
            Step      = 'selftest'
            Status    = $testStatus
            Seconds   = $testRun.Seconds
            Note      = $testNote
            ExitCode  = $testRun.ExitCode
            EnvError  = ($testRun.ExitCode -eq 2)
        }
    } else {
        $rows += [pscustomobject]@{
            Step = 'selftest'; Status = 'SKIP'; Seconds = $null; Note = '-'; ExitCode = 0; EnvError = $false
        }
    }

    if (-not $SkipBuild) {
        Write-Output '===== build ====='
        $buildArgs = @()
        if ($Release) { $buildArgs += '-Release' }
        $buildRun = Invoke-ChildScript -ScriptPath $BuildScript -Arguments $buildArgs
        foreach ($line in $buildRun.Lines) { Write-Output $line }
        Write-Output ''

        $apkLine = @($buildRun.Lines | Where-Object { $_ -match '^APK:\s*(.+?)\s*$' } | Select-Object -Last 1)
        $sizeLine = @($buildRun.Lines | Where-Object { $_ -match '^SIZE:\s*(.+?)\s*$' } | Select-Object -Last 1)
        $buildPassed = ($buildRun.ExitCode -eq 0) -and ($apkLine.Count -eq 1)
        $buildStatus = if ($buildPassed) { 'PASS' } elseif ($buildRun.ExitCode -eq 2) { 'ENV' } else { 'FAIL' }

        if ($buildPassed) {
            $apkPath = $apkLine[0] -replace '^APK:\s*', ''
            $sizeText = if ($sizeLine.Count -eq 1) { $sizeLine[0] -replace '^SIZE:\s*', '' } else { '' }
            $buildNote = "$(Split-Path -Leaf $apkPath) ($sizeText)"
        } elseif ($buildRun.ExitCode -eq 2) {
            $buildNote = 'environment: ' + (Get-ShortFailure -Lines $buildRun.Lines -ExitCode 2)
        } else {
            $buildNote = Get-ShortFailure -Lines $buildRun.Lines -ExitCode $buildRun.ExitCode
        }

        $rows += [pscustomobject]@{
            Step      = 'build'
            Status    = $buildStatus
            Seconds   = $buildRun.Seconds
            Note      = $buildNote
            ExitCode  = $buildRun.ExitCode
            EnvError  = ($buildRun.ExitCode -eq 2)
        }
    } else {
        $rows += [pscustomobject]@{
            Step = 'build'; Status = 'SKIP'; Seconds = $null; Note = '-'; ExitCode = 0; EnvError = $false
        }
    }
} catch {
    [Console]::Error.WriteLine("ERROR: check-all failed unexpectedly: $($_.Exception.Message)")
    exit 2
}

$executed = @($rows | Where-Object { $_.Status -ne 'SKIP' })
$passed = @($executed | Where-Object { $_.Status -eq 'PASS' }).Count
$total = $executed.Count
$failed = @($executed | Where-Object { $_.Status -ne 'PASS' }).Count
$environmentProblem = @($rows | Where-Object { $_.EnvError }).Count -gt 0

$stepWidth = [Math]::Max(8, ($rows | ForEach-Object { $_.Step.Length } | Measure-Object -Maximum).Maximum)
$statusWidth = 6
$timeWidth = 9
$timeFormat = "{0,$timeWidth}"
$rowFormat = "{0,-$stepWidth}  {1,-$statusWidth}  {2,$timeWidth}  {3}"

Write-Output 'SUMMARY'
Write-Output ($rowFormat -f 'STEP', 'STATUS', 'TIME', 'NOTE')
Write-Output ($rowFormat -f ('-' * $stepWidth), ('-' * $statusWidth), ('-' * $timeWidth), ('-' * 4))
foreach ($row in $rows) {
    $timeText = if ($null -eq $row.Seconds) { '-' } else { "{0:0.00}s" -f $row.Seconds }
    Write-Output ($rowFormat -f $row.Step, $row.Status, $timeText, $row.Note)
}
Write-Output ''
Write-Output ("RESULT: {0}/{1} PASSED" -f $passed, $total)

if ($environmentProblem) { exit 2 }
if ($failed -gt 0) { exit 1 }
exit 0
