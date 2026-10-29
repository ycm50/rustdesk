#Requires -Version 5.1
<#
.SYNOPSIS
    Fetch an open-source prebuilt FFmpeg (shared) for the host Rust build.

.DESCRIPTION
    FFmpeg is ONLY needed for the hwcodec feature:
        cargo build --features hwcodec
    The default host build does not link FFmpeg at all.

    Packages come from the BtbN/FFmpeg-Builds GitHub releases (GPL or LGPL,
    shared libraries) and are unpacked into:

        host/.deps/ffmpeg/<platform>-<arch>/

    Supported: win-x64, win-arm64, linux-x64, linux-arm64. Downloads use
    Invoke-WebRequest and zip extraction uses System.IO.Compression, so
    curl/wget/tar are not required on Windows.

    gyan.dev is a Windows-only fallback (unversioned, 7-Zip required, no
    pkg-config files); it is used when BtbN cannot serve the Windows asset and
    when -Source gyan / FFMPEG_SOURCE=gyan is passed.

.PARAMETER Force
    Re-download and re-extract even when the tree already exists.

.PARAMETER Tag
    GitHub release tag, default latest. Pin e.g. autobuild-2026-10-26-14-25
    for reproducible CI. Env: FFMPEG_BUILD_TAG.

.PARAMETER Series
    FFmpeg branch: 8.1, n8.1, master, ... Default: newest stable branch in the
    release. Env: FFMPEG_SERIES.

.PARAMETER Asset
    Exact release asset file name; skips discovery. Env: FFMPEG_ASSET.

.PARAMETER License
    gpl (default) or lgpl. Env: FFMPEG_LICENSE.

.PARAMETER Platform
    win or linux (host default). Env: FFMPEG_PLATFORM.

.PARAMETER Arch
    x64 or arm64 (host default). Env: FFMPEG_ARCH.

.PARAMETER Dest
    Output directory. Default: host/.deps/ffmpeg/<platform>-<arch>.
    Env: FFMPEG_DEST.

.PARAMETER DepsRoot
    Root for the download cache and outputs. Default: host/.deps.
    Env: FFMPEG_DEPS_ROOT.

.PARAMETER Source
    btbn (default), gyan (Windows only) or auto (BtbN, then gyan on Windows).
    Env: FFMPEG_SOURCE.

.PARAMETER DryRun
    Resolve and print the asset; download nothing.

.EXAMPLE
    pwsh -File host/scripts/fetch-ffmpeg.ps1 --help

.EXAMPLE
    pwsh -File host/scripts/fetch-ffmpeg.ps1 -Arch arm64 -Series 8.1

.EXAMPLE
    $env:FFMPEG_BUILD_TAG = 'autobuild-2026-10-26-14-25'
    pwsh -File host/scripts/fetch-ffmpeg.ps1 -Force
#>
[CmdletBinding()]
param(
    [switch]$Force,
    [string]$Tag = $env:FFMPEG_BUILD_TAG,
    [string]$Series = $env:FFMPEG_SERIES,
    [string]$Asset = $env:FFMPEG_ASSET,
    [string]$License = $env:FFMPEG_LICENSE,
    [string]$Platform = $(if ($env:FFMPEG_PLATFORM) { $env:FFMPEG_PLATFORM } else { '' }),
    [string]$Arch = $(if ($env:FFMPEG_ARCH) { $env:FFMPEG_ARCH } else { '' }),
    [string]$Dest = $env:FFMPEG_DEST,
    [string]$DepsRoot = $env:FFMPEG_DEPS_ROOT,
    [string]$Source = $(if ($env:FFMPEG_SOURCE) { $env:FFMPEG_SOURCE } else { 'auto' }),
    [switch]$DryRun,
    [switch]$Help,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$NewLine = [Environment]::NewLine
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
} catch { }

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$HostDir = Split-Path -Parent $ScriptDir
$Repo = $(if ($env:FFMPEG_GITHUB_REPO) { $env:FFMPEG_GITHUB_REPO } else { 'BtbN/FFmpeg-Builds' })
$RequireChecksum = ($env:FFMPEG_REQUIRE_CHECKSUM -eq '1')
$ExpectedChecksum = $env:FFMPEG_CHECKSUM
$Script:ReleasePublished = 'unknown'
$Script:ActualSha = 'unknown'

function Write-Info { param([string]$Message) Write-Host "[fetch-ffmpeg] $Message" }
function Write-Warn { param([string]$Message) Write-Host "[fetch-ffmpeg] WARNING: $Message" -ForegroundColor Yellow }
function Stop-Fetch { param([int]$Code, [string]$Message) Write-Host "[fetch-ffmpeg] ERROR: $Message" -ForegroundColor Red; exit $Code }

function Show-Usage {
    @'
fetch-ffmpeg.ps1 - download a prebuilt FFmpeg for the host build

  Only needed for the hwcodec feature:
      cargo build --features hwcodec
  The default host build does not use FFmpeg at all.

Usage:
  pwsh -File host/scripts/fetch-ffmpeg.ps1 [options]
  (both -Option and --option spellings are accepted)

Options:
  -Force              re-download and re-extract even if the tree exists
  -Tag <tag>          GitHub release tag (default: latest)
                      pin with e.g. autobuild-2026-10-26-14-25
  -Series <branch>    FFmpeg branch: 8.1, n8.1, master ...
                      (default: newest stable branch in the release)
  -Asset <file>       exact release asset name, skips discovery
  -License <kind>     gpl (default) or lgpl
  -Platform <p>       win or linux (host default)
  -Arch <a>           x64 or arm64 (host default)
  -Dest <dir>         output dir (default host/.deps/ffmpeg/<platform>-<arch>)
  -DepsRoot <dir>     cache+output root (default host/.deps)
  -Source <s>         btbn (default), gyan (Windows only), auto
  -DryRun             resolve and print the asset, download nothing
  -Help               this text

Output layout:
  host/.deps/ffmpeg/<platform>-<arch>/{bin,include,lib,...}
  host/.deps/ffmpeg/_cache/                    downloaded archives + checksums

Environment: FFMPEG_BUILD_TAG, FFMPEG_SERIES, FFMPEG_ASSET, FFMPEG_LICENSE,
FFMPEG_PLATFORM, FFMPEG_ARCH, FFMPEG_DEST, FFMPEG_DEPS_ROOT, FFMPEG_CHECKSUM,
FFMPEG_REQUIRE_CHECKSUM, FFMPEG_SOURCE, GITHUB_TOKEN.

Notes:
  - Only Invoke-WebRequest is used for downloads; no curl/wget needed.
  - gyan.dev fallback needs 7-Zip (7z.exe) and ships no pkg-config files.
  - Exit codes: 0 ok, 2 network/offline, 3 checksum, 64 usage, 65 unsupported.
'@
}

# ---------------------------------------------------------------------------
# --long options parsed from the remaining arguments (so --help works)
# ---------------------------------------------------------------------------
$RestList = @()
if ($Rest) { $RestList = @($Rest) }

function Get-RestValue {
    param([string]$Name, [int]$Index)
    if ($Index -ge $RestList.Count) { Stop-Fetch 64 "$Name needs a value" }
    return $RestList[$Index]
}

$ri = 0
while ($ri -lt $RestList.Count) {
    $arg = $RestList[$ri]
    switch -Regex ($arg) {
        '^(--help|-help|-h|-\?|/\?)$' { Show-Usage; exit 0 }
        '^(--force|-f)$' { $Force = $true }
        '^(--lgpl|-lgpl)$' { $License = 'lgpl' }
        '^(--gpl|-gpl)$' { $License = 'gpl' }
        '^(--dry-run|-dryrun)$' { $DryRun = $true }
        '^--tag$|^-tag$' { $ri++; $Tag = Get-RestValue '--tag' $ri }
        '^--series$|^-series$' { $ri++; $Series = Get-RestValue '--series' $ri }
        '^--asset$|^-asset$' { $ri++; $Asset = Get-RestValue '--asset' $ri }
        '^--license$|^-license$' { $ri++; $License = Get-RestValue '--license' $ri }
        '^--platform$|^-platform$' { $ri++; $Platform = Get-RestValue '--platform' $ri }
        '^--arch$|^-arch$' { $ri++; $Arch = Get-RestValue '--arch' $ri }
        '^--dest$|^-dest$' { $ri++; $Dest = Get-RestValue '--dest' $ri }
        '^--deps-root$|^-deps-root$' { $ri++; $DepsRoot = Get-RestValue '--deps-root' $ri }
        '^--source$|^-source$' { $ri++; $Source = Get-RestValue '--source' $ri }
        default { Stop-Fetch 64 "unknown argument: $arg (try --help)" }
    }
    $ri++
}

if ($Help) { Show-Usage; exit 0 }

$TagExplicit = $Tag
$SeriesExplicit = $Series

# ---------------------------------------------------------------------------
# platform / arch
# ---------------------------------------------------------------------------
function Get-HostPlatform {
    if ($env:OS -eq 'Windows_NT') { return 'win' }
    try {
        if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::Linux)) { return 'linux' }
        if ([System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([System.Runtime.InteropServices.OSPlatform]::OSX)) { return 'macos' }
    } catch { }
    return 'unknown'
}

function Get-HostArch {
    try {
        $a = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString()
    } catch {
        $a = $env:PROCESSOR_ARCHITECTURE
    }
    switch -Regex ([string]$a) {
        '^(X64|AMD64|x86_64)$' { return 'x64' }
        '^(Arm64|ARM64|aarch64)$' { return 'arm64' }
        default { return 'unknown' }
    }
}

function ConvertTo-Platform {
    param([string]$Value)
    switch -Regex ($Value.ToLowerInvariant()) {
        '^(linux|linux64|gnu|ubuntu|debian|centos|fedora)$' { return 'linux' }
        '^(win|windows|win32|win64)$' { return 'win' }
        '^(macos|darwin|osx|mac)$' { return 'macos' }
        default { return 'unknown' }
    }
}

function ConvertTo-Arch {
    param([string]$Value)
    switch -Regex ($Value.ToLowerInvariant()) {
        '^(x64|amd64|x86_64)$' { return 'x64' }
        '^(arm64|aarch64)$' { return 'arm64' }
        default { return 'unknown' }
    }
}

$Platform = $(if ($Platform) { ConvertTo-Platform $Platform } else { Get-HostPlatform })
$Arch = $(if ($Arch) { ConvertTo-Arch $Arch } else { Get-HostArch })

if ($Platform -eq 'unknown') { Stop-Fetch 65 'unsupported platform (supported: win, linux, macos)' }
if ($Arch -eq 'unknown') { Stop-Fetch 65 'unsupported architecture (supported: x64, arm64)' }

if ($Platform -eq 'macos') {
    Stop-Fetch 65 (@(
            'BtbN/FFmpeg-Builds publishes no macOS prebuilds and this script has no macOS fallback.',
            '  Use a package-manager FFmpeg instead:',
            '      brew install ffmpeg pkg-config',
            '      export PKG_CONFIG_PATH="$(brew --prefix ffmpeg)/lib/pkgconfig"',
            '      export FFMPEG_DIR="$(brew --prefix ffmpeg)"',
            '  or build FFmpeg yourself and point PKG_CONFIG_PATH/FFMPEG_DIR at it.',
            '  (FFmpeg is only needed for --features hwcodec.)'
        ) -join $NewLine)
}

$PlatformToken = $null
$AssetExt = $null
switch ("$Platform-$Arch") {
    'win-x64' { $PlatformToken = 'win64'; $AssetExt = 'zip' }
    'win-arm64' { $PlatformToken = 'winarm64'; $AssetExt = 'zip' }
    'linux-x64' { $PlatformToken = 'linux64'; $AssetExt = 'tar.xz' }
    'linux-arm64' { $PlatformToken = 'linuxarm64'; $AssetExt = 'tar.xz' }
    default { Stop-Fetch 65 "no prebuilt FFmpeg mapping for $Platform-$Arch" }
}

if (-not $Tag) { $Tag = 'latest' }
if (-not $License) { $License = 'gpl' }
$License = $License.ToLowerInvariant()
if ($License -notin @('gpl', 'lgpl')) { Stop-Fetch 64 "License must be gpl or lgpl, got: $License" }
if (-not $DepsRoot) { $DepsRoot = Join-Path $HostDir '.deps' }
if (-not $Dest) { $Dest = Join-Path $DepsRoot (Join-Path 'ffmpeg' "$Platform-$Arch") }
$Dest = [IO.Path]::GetFullPath($Dest)
$DepsRoot = [IO.Path]::GetFullPath($DepsRoot)
if ($Dest.TrimEnd('\', '/') -eq $DepsRoot.TrimEnd('\', '/')) {
    Stop-Fetch 64 'Dest must not be the same directory as DepsRoot'
}
if (-not $Source) { $Source = 'btbn' }
$Source = $Source.ToLowerInvariant()
if ($Source -notin @('btbn', 'gyan', 'auto')) { Stop-Fetch 64 'Source must be btbn, gyan or auto' }

$Cache = Join-Path $DepsRoot 'ffmpeg/_cache'
$Marker = Join-Path $Dest '.ffmpeg-build'
$ReleaseUrl = "https://github.com/$Repo/releases/download/$Tag"
$ApiUrl = "https://api.github.com/repos/$Repo/releases/tags/$Tag"
$Checksums = Join-Path $Cache "checksums.$Tag.sha256"

$SeriesNorm = $Series
if ($SeriesNorm) { $SeriesNorm = $SeriesNorm.ToLowerInvariant() -replace '^n', '' }

function Get-SeriesOf {
    param([string]$Name)
    $m = [regex]::Match($Name, '-shared-([0-9]+\.[0-9]+)\.(tar\.xz|zip)$')
    if ($m.Success) { return $m.Groups[1].Value }
    if ($Name -match '-master-') { return 'master' }
    return ''
}

# ---------------------------------------------------------------------------
# HTTP helpers - Invoke-WebRequest only
# ---------------------------------------------------------------------------
function Invoke-Download {
    param([string]$Uri, [string]$OutFile, [int]$Attempts = 3)
    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            $params = @{ Uri = $Uri; OutFile = $OutFile; TimeoutSec = 900; ErrorAction = 'Stop' }
            if ($PSVersionTable.PSVersion.Major -lt 6) { $params.UseBasicParsing = $true }
            Invoke-WebRequest @params
            return
        } catch {
            if ($i -eq $Attempts) { throw }
            Write-Warn "download attempt $i failed: $($_.Exception.Message)"
            Start-Sleep -Seconds (2 * $i)
        }
    }
}

function Get-UrlText {
    param([string]$Uri, [int]$Attempts = 3)
    $headers = @{ 'User-Agent' = 'rustdesk-fetch-ffmpeg' }
    if ($env:GITHUB_TOKEN) { $headers['Authorization'] = "Bearer $env:GITHUB_TOKEN" }
    for ($i = 1; $i -le $Attempts; $i++) {
        try {
            $params = @{ Uri = $Uri; Headers = $headers; TimeoutSec = 120; ErrorAction = 'Stop' }
            if ($PSVersionTable.PSVersion.Major -lt 6) { $params.UseBasicParsing = $true }
            $r = Invoke-WebRequest @params
            if ($r.Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($r.Content) }
            return [string]$r.Content
        } catch {
            if ($i -eq $Attempts) { throw }
            Start-Sleep -Seconds (2 * $i)
        }
    }
}

function Get-ExpectedSha {
    param([string]$AssetName)
    if ($ExpectedChecksum) {
        Write-Info 'using checksum from FFMPEG_CHECKSUM'
        return $ExpectedChecksum.Trim().ToLowerInvariant()
    }
    if (-not (Test-Path -LiteralPath $Checksums)) {
        try {
            Invoke-Download -Uri "$ReleaseUrl/checksums.sha256" -OutFile $Checksums -Attempts 2
        } catch {
            Write-Warn "could not download checksums.sha256 for release '$Tag'"
            return $null
        }
    }
    foreach ($line in @(Get-Content -LiteralPath $Checksums -ErrorAction SilentlyContinue)) {
        $m = [regex]::Match([string]$line, '^([0-9a-fA-F]{64})\s+\*?(.+?)\s*$')
        if ($m.Success -and $m.Groups[2].Value -eq $AssetName) { return $m.Groups[1].Value.ToLowerInvariant() }
    }
    Write-Warn "checksums.sha256 for '$Tag' does not list '$AssetName'"
    return $null
}

function Warn-Unverified {
    param([string]$AssetName)
    if ($RequireChecksum) {
        Stop-Fetch 3 "no SHA256 available for '$AssetName' and FFMPEG_REQUIRE_CHECKSUM=1. Pin FFMPEG_CHECKSUM=<sha256>."
    }
    Write-Warn '=================================================================='
    Write-Warn "UNVERIFIED DOWNLOAD: no SHA256 known for '$AssetName'."
    Write-Warn 'The release checksum file is missing or does not list that asset.'
    Write-Warn 'Set FFMPEG_CHECKSUM=<sha256>, or FFMPEG_REQUIRE_CHECKSUM=1 to fail.'
    Write-Warn '=================================================================='
}

function Assert-Hash {
    param([string]$Path, [string]$Expected, [string]$AssetName)
    if (-not $Expected) { return }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    $Script:ActualSha = $actual
    if ($actual -ne $Expected) {
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        Stop-Fetch 3 (@(
                "SHA256 mismatch for '$AssetName'",
                "  expected: $Expected",
                "  actual  : $actual",
                '  The archive was deleted; re-run to download it again.'
            ) -join $NewLine)
    }
    Write-Info "sha256 ok: $actual"
}

# ---------------------------------------------------------------------------
# archive handling
# ---------------------------------------------------------------------------
function Find-SevenZip {
    foreach ($name in @('7z.exe', '7zz.exe', '7za.exe')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }
    $candidates = @()
    if ($env:ProgramFiles) { $candidates += (Join-Path $env:ProgramFiles '7-Zip\7z.exe') }
    $pf86 = [Environment]::GetEnvironmentVariable('ProgramFiles(x86)')
    if ($pf86) { $candidates += (Join-Path $pf86 '7-Zip\7z.exe') }
    foreach ($p in $candidates) {
        if (Test-Path -LiteralPath $p) { return $p }
    }
    return $null
}

function Expand-ArchiveTo {
    param([string]$ArchivePath, [string]$Target)
    if ($ArchivePath -like '*.zip') {
        try { Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop } catch { }
        if ('System.IO.Compression.ZipFile' -as [type]) {
            [System.IO.Compression.ZipFile]::ExtractToDirectory($ArchivePath, $Target)
        } else {
            Expand-Archive -LiteralPath $ArchivePath -DestinationPath $Target -Force
        }
        return
    }
    if ($ArchivePath -like '*.tar.*' -or $ArchivePath -like '*.tgz') {
        & tar -xf $ArchivePath -C $Target
        if ($LASTEXITCODE -ne 0) { Stop-Fetch 2 "tar failed to extract $ArchivePath" }
        return
    }
    if ($ArchivePath -like '*.7z') {
        $sevenZip = Find-SevenZip
        if (-not $sevenZip) {
            Stop-Fetch 2 (@(
                    'the gyan.dev package is a .7z archive and 7-Zip was not found.',
                    '  Install 7-Zip (7z.exe on PATH or in Program Files), or use the default BtbN source.'
                ) -join $NewLine)
        }
        & $sevenZip x -y "-o$Target" $ArchivePath | Out-Null
        if ($LASTEXITCODE -ne 0) { Stop-Fetch 2 "7-Zip failed to extract $ArchivePath" }
        return
    }
    Stop-Fetch 2 "unsupported archive type: $ArchivePath"
}

function Clear-Destination {
    if (-not (Test-Path -LiteralPath $Dest)) { return }
    if ((Resolve-Path -LiteralPath $Dest).Path.TrimEnd('\', '/') -eq $DepsRoot.TrimEnd('\', '/')) {
        Stop-Fetch 64 'refusing to wipe DepsRoot itself'
    }
    Get-ChildItem -LiteralPath $Dest -Force | ForEach-Object {
        Remove-Item -LiteralPath $_.FullName -Recurse -Force
    }
}

function Update-PkgConfigPrefix {
    param([string]$Root)
    $newPrefix = $Root -replace '\\', '/'
    foreach ($sub in @('lib/pkgconfig', 'lib64/pkgconfig', 'share/pkgconfig')) {
        $pcDir = Join-Path $Root $sub
        if (-not (Test-Path -LiteralPath $pcDir)) { continue }
        foreach ($pc in @(Get-ChildItem -LiteralPath $pcDir -Filter '*.pc' -File)) {
            $text = [IO.File]::ReadAllText($pc.FullName)
            $m = [regex]::Match($text, '(?m)^prefix=(.+?)\r?$')
            if (-not $m.Success) { continue }
            $old = $m.Groups[1].Value
            if ([regex]::IsMatch($old, '\$\{')) { continue }
            if (Test-Path -LiteralPath $old) { continue }
            [IO.File]::WriteAllText($pc.FullName, [regex]::Replace($text, '(?m)^prefix=.*$', "prefix=$newPrefix"))
            Write-Info "rewrote pkg-config prefix: $($pc.Name): $old -> $newPrefix"
        }
    }
}

function Install-Tree {
    param([string]$ArchivePath)
    New-Item -ItemType Directory -Force -Path $Cache | Out-Null
    $extractDir = Join-Path $Cache ('extract-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
    try {
        Expand-ArchiveTo -ArchivePath $ArchivePath -Target $extractDir
        $items = @(Get-ChildItem -LiteralPath $extractDir -Force)
        if ($items.Count -eq 1 -and $items[0].PSIsContainer) { $src = $items[0].FullName } else { $src = $extractDir }
        New-Item -ItemType Directory -Force -Path $Dest | Out-Null
        Clear-Destination
        Get-ChildItem -LiteralPath $src -Force | ForEach-Object {
            Move-Item -LiteralPath $_.FullName -Destination (Join-Path $Dest $_.Name) -Force
        }
    } finally {
        Remove-Item -LiteralPath $extractDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    $header = Join-Path $Dest 'include/libavcodec/avcodec.h'
    if (-not (Test-Path -LiteralPath $header)) {
        Stop-Fetch 2 "unexpected archive layout: $header is missing"
    }
    Update-PkgConfigPrefix -Root $Dest
}

# ---------------------------------------------------------------------------
# idempotency check
# ---------------------------------------------------------------------------
function Read-Marker {
    $h = @{}
    if (-not (Test-Path -LiteralPath $Marker)) { return $h }
    foreach ($line in @(Get-Content -LiteralPath $Marker)) {
        $m = [regex]::Match([string]$line, '^([^=]+)=(.*)$')
        if ($m.Success) { $h[$m.Groups[1].Value] = $m.Groups[2].Value }
    }
    return $h
}

function Test-Tree {
    return ((Test-Path -LiteralPath (Join-Path $Dest 'include/libavcodec/avcodec.h')) -and (Test-Path -LiteralPath (Join-Path $Dest 'lib')))
}

$SkipFetch = $false
$ChosenAsset = $Asset
if ((-not $Force) -and (-not $DryRun) -and (Test-Tree)) {
    if (Test-Path -LiteralPath $Marker) {
        $saved = Read-Marker
        $oldAsset = $saved['asset']
        $oldTag = $saved['tag']
        $refetch = $false
        if ($Asset -and $oldAsset -and ($oldAsset -ne $Asset)) { $refetch = $true }
        if ($TagExplicit -and $oldTag -and ($oldTag -ne $Tag)) { $refetch = $true }
        if ($SeriesExplicit -and $SeriesNorm -and $oldAsset -and ($oldAsset -notmatch "-shared-$SeriesNorm\.")) { $refetch = $true }
        if (-not $refetch) {
            Write-Info "already present, nothing to do: $Dest"
            if ($oldAsset) { $ChosenAsset = $oldAsset }
            if ($oldTag) { $Tag = $oldTag }
            $SkipFetch = $true
        } else {
            Write-Info "different FFmpeg requested (tree has tag=$oldTag asset=$oldAsset); re-fetching"
        }
    } else {
        Write-Info "already present (unmanaged tree, no .ffmpeg-build marker): $Dest"
        $SkipFetch = $true
    }
}

# ---------------------------------------------------------------------------
# BtbN
# ---------------------------------------------------------------------------
function Resolve-BtbNAsset {
    if ($Asset) {
        Write-Info "using asset from -Asset/FFMPEG_ASSET: $Asset"
        return $Asset
    }
    Write-Info "resolving FFmpeg asset for $Platform-$Arch ($License-shared, tag=$Tag)"
    $names = @()
    try {
        $rel = Invoke-RestMethod -Uri $ApiUrl -Headers @{ 'Accept' = 'application/vnd.github+json'; 'User-Agent' = 'rustdesk-fetch-ffmpeg' } -TimeoutSec 120 -ErrorAction Stop
        $names = @($rel.assets | ForEach-Object { $_.name })
        $pub = $rel.published_at
        if ($pub -is [datetime]) {
            $Script:ReleasePublished = $pub.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        } elseif ($pub) {
            $Script:ReleasePublished = [string]$pub
        }
    } catch {
        Write-Warn "GitHub API unreachable: $($_.Exception.Message)"
    }

    if ($names.Count -eq 0) {
        if ($Tag -ne 'latest') {
            Stop-Fetch 2 (@(
                    "cannot enumerate assets of release '$Tag' (GitHub API unreachable).",
                    '  Retry later, set GITHUB_TOKEN, or pass -Asset <exact file name>.'
                ) -join $NewLine)
        }
        $probe = @()
        if ($SeriesNorm) { $probe = @($SeriesNorm) } else { $probe = @('10.0', '9.0', '8.1', '7.1', '6.1', 'master') }
        foreach ($s in $probe) {
            if ($s -eq 'master') {
                $n = "ffmpeg-master-$Tag-$PlatformToken-$License-shared.$AssetExt"
            } else {
                $n = "ffmpeg-n$s-$Tag-$PlatformToken-$License-shared-$s.$AssetExt"
            }
            try {
                $head = Invoke-WebRequest -Uri "$ReleaseUrl/$n" -Method Head -TimeoutSec 60 -ErrorAction Stop
                if ($head.StatusCode -eq 200) { return $n }
            } catch { }
        }
        Stop-Fetch 2 (@(
                'cannot determine the FFmpeg asset name (GitHub API and name probing both failed).',
                '  Check the network or pass the asset explicitly:',
                "      -Asset ffmpeg-n9.0-latest-$PlatformToken-$License-shared-9.0.$AssetExt",
                '  Offline? A previously downloaded archive in',
                "      $Cache",
                '  is reused automatically, no network needed.'
            ) -join $NewLine)
    }

    $pattern = "^ffmpeg-.+-$PlatformToken-$License-shared(-[0-9]+\.[0-9]+)?\.(tar\.xz|zip)$"
    $filtered = @($names | Where-Object { $_ -match $pattern } | Sort-Object)
    if ($filtered.Count -eq 0) {
        Stop-Fetch 2 (@(
                "release '$Tag' has no $PlatformToken/$License-shared asset for $Platform-$Arch.",
                "  Inspect https://github.com/$Repo/releases/tag/$Tag",
                '  then re-run with -Tag/-Series/-License or -Asset.'
            ) -join $NewLine)
    }

    if ($SeriesNorm) {
        if ($SeriesNorm -eq 'master') {
            $chosen = @($filtered | Where-Object { $_ -match '-master-' } | Select-Object -First 1)
        } else {
            $chosen = @($filtered | Where-Object { (Get-SeriesOf $_) -eq $SeriesNorm } | Select-Object -First 1)
        }
        if ($chosen.Count -eq 0) {
            $matching = (@($filtered) | ForEach-Object { "    $_" }) -join $NewLine
            Stop-Fetch 2 (@(
                    "release '$Tag' has no FFmpeg '$Series' asset for $Platform-$Arch.",
                    '  Matching assets in this release:',
                    $matching
                ) -join $NewLine)
        }
        $picked = $chosen[0]
    } else {
        $stable = @($filtered | ForEach-Object {
                $s = Get-SeriesOf $_
                if ($s -and $s -match '^\d+\.\d+$') { [pscustomobject]@{ Series = [version]$s; Name = $_ } }
            })
        if ($stable.Count -gt 0) {
            $picked = ($stable | Sort-Object Series | Select-Object -Last 1).Name
        } else {
            $picked = $filtered[0]
            Write-Warn "release '$Tag' has no stable-branch asset for $Platform-$Arch; using '$picked'"
        }
    }
    Write-Info "selected asset: $picked"
    return $picked
}

function Invoke-BtbN {
    $name = Resolve-BtbNAsset
    $url = "$ReleaseUrl/$name"
    $archive = Join-Path $Cache $name
    $Script:ChosenAsset = $name

    if ($DryRun) {
        Write-Host '[fetch-ffmpeg] dry run'
        Write-Host "  asset  : $name"
        Write-Host "  url    : $url"
        Write-Host "  dest   : $Dest"
        Write-Host "  archive: $archive"
        exit 0
    }

    New-Item -ItemType Directory -Force -Path $Cache | Out-Null
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null

    $expected = Get-ExpectedSha -AssetName $name
    if (-not $expected) { Warn-Unverified -AssetName $name }

    $cached = (Test-Path -LiteralPath $archive) -and ((Get-Item -LiteralPath $archive).Length -gt 0)
    if ($Force -or -not $cached) {
        Write-Info "downloading $url"
        try {
            Invoke-Download -Uri $url -OutFile "$archive.partial"
        } catch {
            Remove-Item -LiteralPath "$archive.partial" -Force -ErrorAction SilentlyContinue
            Stop-Fetch 2 (@(
                    "download failed: $url",
                    '  Offline or blocked? Put the archive at',
                    "      $archive",
                    '  and re-run: it is verified and extracted without touching the network.',
                    '  FFmpeg is only required for --features hwcodec.'
                ) -join $NewLine)
        }
        Move-Item -LiteralPath "$archive.partial" -Destination $archive -Force
    } else {
        Write-Info "using cached archive: $archive"
    }

    Assert-Hash -Path $archive -Expected $expected -AssetName $name

    Write-Info "extracting $name -> $Dest"
    Install-Tree -ArchivePath $archive

    Set-Content -LiteralPath $Marker -Encoding UTF8 -Value @(
        "asset=$name",
        "tag=$Tag",
        "repo=$Repo",
        "sha256=$($Script:ActualSha)",
        "release_published_at=$($Script:ReleasePublished)",
        "fetched_at=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "platform=$Platform",
        "arch=$Arch",
        "license=$License"
    )
}

# ---------------------------------------------------------------------------
# gyan.dev (Windows-only fallback: unversioned, 7-Zip, no pkg-config)
# ---------------------------------------------------------------------------
function Invoke-Gyan {
    if ($Platform -ne 'win') { Stop-Fetch 65 'the gyan.dev fallback is Windows-only' }
    $name = 'ffmpeg-release-full-shared.7z'
    $url = "https://www.gyan.dev/ffmpeg/builds/$name"
    $archive = Join-Path $Cache $name
    $Script:ChosenAsset = $name
    Write-Warn 'using gyan.dev: unversioned rolling build, no pkg-config files'

    if ($DryRun) {
        Write-Host '[fetch-ffmpeg] dry run'
        Write-Host "  asset  : $name"
        Write-Host "  url    : $url"
        Write-Host "  dest   : $Dest"
        Write-Host "  archive: $archive"
        exit 0
    }

    New-Item -ItemType Directory -Force -Path $Cache | Out-Null
    New-Item -ItemType Directory -Force -Path $Dest | Out-Null

    $expected = $null
    if ($ExpectedChecksum) {
        $expected = $ExpectedChecksum.Trim().ToLowerInvariant()
    } else {
        try {
            $shaText = Get-UrlText -Uri "$url.sha256"
            $m = [regex]::Match($shaText, '([0-9a-fA-F]{64})')
            if ($m.Success) { $expected = $m.Groups[1].Value.ToLowerInvariant() }
        } catch {
            Write-Warn "could not download $url.sha256"
        }
    }
    if (-not $expected) { Warn-Unverified -AssetName $name }

    if ($Force -or -not (Test-Path -LiteralPath $archive)) {
        Write-Info "downloading $url"
        try {
            Invoke-Download -Uri $url -OutFile "$archive.partial"
        } catch {
            Remove-Item -LiteralPath "$archive.partial" -Force -ErrorAction SilentlyContinue
            Stop-Fetch 2 "download failed: $url (offline? put the archive at $archive and re-run)"
        }
        Move-Item -LiteralPath "$archive.partial" -Destination $archive -Force
    } else {
        Write-Info "using cached archive: $archive"
    }

    Assert-Hash -Path $archive -Expected $expected -AssetName $name
    Write-Info "extracting $name -> $Dest"
    Install-Tree -ArchivePath $archive

    Set-Content -LiteralPath $Marker -Encoding UTF8 -Value @(
        "asset=$name",
        'tag=gyan-release',
        'repo=gyan.dev',
        "sha256=$($Script:ActualSha)",
        'release_published_at=unknown',
        "fetched_at=$([DateTime]::UtcNow.ToString('yyyy-MM-ddTHH:mm:ssZ'))",
        "platform=$Platform",
        "arch=$Arch",
        "license=$License"
    )
}

if (-not $SkipFetch) {
    if ($Source -eq 'gyan') {
        Invoke-Gyan
    } else {
        try {
            Invoke-BtbN
        } catch {
            if ($Source -eq 'auto' -and $Platform -eq 'win') {
                Write-Warn "BtbN fetch failed: $($_.Exception.Message)"
                Write-Warn 'falling back to gyan.dev (Windows-only, unversioned, no pkg-config)'
                Invoke-Gyan
            } else {
                throw
            }
        }
    }
}

# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
$PcDir = $null
foreach ($sub in @('lib/pkgconfig', 'lib64/pkgconfig', 'share/pkgconfig')) {
    $p = Join-Path $Dest $sub
    if (Test-Path -LiteralPath $p) { $PcDir = $p; break }
}
$DestUnix = $Dest -replace '\\', '/'

Write-Host ''
Write-Host '[fetch-ffmpeg] FFmpeg ready'
Write-Host "  asset      : $ChosenAsset"
Write-Host "  tag        : $Tag"
Write-Host "  tree       : $Dest"
if ($PcDir) {
    Write-Host "  pkg-config : $PcDir"
} else {
    Write-Host "  pkg-config : (none in this package; link with -L$DestUnix/lib and -lavcodec ...)"
}
Write-Host ''
Write-Host '  Only the hwcodec build needs these variables:'
if ($PcDir) {
    $pcUnix = $PcDir -replace '\\', '/'
    Write-Host ('    $env:PKG_CONFIG_PATH = "{0}"' -f $PcDir)
    Write-Host ('    $env:FFMPEG_PKG_CONFIG_PATH = "{0}/libavcodec.pc"' -f $PcDir)
    Write-Host ('    $env:FFMPEG_DIR = "{0}"' -f $Dest)
    Write-Host ('    $env:PATH = "{0};" + $env:PATH' -f (Join-Path $Dest 'bin'))
    Write-Host '  bash:'
    Write-Host ('    export PKG_CONFIG_PATH="{0}"' -f $pcUnix)
    Write-Host ('    export FFMPEG_PKG_CONFIG_PATH="{0}/libavcodec.pc"' -f $pcUnix)
    Write-Host ('    export FFMPEG_DIR="{0}"' -f $DestUnix)
} else {
    Write-Host ('    $env:FFMPEG_DIR = "{0}"' -f $Dest)
    Write-Host "    # no .pc files: link with -L$DestUnix/lib and -lavcodec/-lavutil/-lavformat (or .lib on MSVC)"
}

$pkgConfig = Get-Command pkg-config -ErrorAction SilentlyContinue
if ($pkgConfig -and $PcDir) {
    $pcFile = Join-Path $PcDir 'libavcodec.pc'
    $declared = ''
    if (Test-Path -LiteralPath $pcFile) {
        $m = [regex]::Match([IO.File]::ReadAllText($pcFile), '(?m)^Version:\s*(\S+)')
        if ($m.Success) { $declared = $m.Groups[1].Value }
    }
    $savedPcPath = $env:PKG_CONFIG_PATH
    $env:PKG_CONFIG_PATH = $PcDir
    try {
        $ver = ''
        try { $ver = [string](& $pkgConfig.Source --modversion libavcodec 2>$null) } catch { }
        $ver = $ver.Trim()
        Write-Host ''
        if ($ver -and $ver -eq $declared) {
            Write-Host "  check: pkg-config --exists libavcodec -> ok (version $ver)"
        } else {
            Write-Host '  check: pkg-config --exists libavcodec -> FAILED'
            $shown = $(if ($ver) { $ver } else { '<none>' })
            $declaredShown = $(if ($declared) { $declared } else { '<none>' })
            Write-Host ('    pkg-config reported "{0}" but {1} declares "{2}"' -f $shown, $pcFile, $declaredShown)
            Write-Host '    (another libavcodec.pc may shadow it in the default pkg-config search path)'
        }
    } finally {
        if ($null -eq $savedPcPath) {
            Remove-Item Env:PKG_CONFIG_PATH -ErrorAction SilentlyContinue
        } else {
            $env:PKG_CONFIG_PATH = $savedPcPath
        }
    }
} elseif ($PcDir) {
    Write-Host ''
    Write-Host '  check: pkg-config not found on PATH; skipped'
}
