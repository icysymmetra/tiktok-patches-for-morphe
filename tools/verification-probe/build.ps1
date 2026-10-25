<#
.SYNOPSIS
    Build and install the test-only instrumentation that reaches Hushfeed settings on a phone.

.DESCRIPTION
    Built with the SDK tools directly rather than through the project's build, because it is not
    part of the bundle and has no business in its dependency graph or its dex.

    It is signed with the same keystore as the sideloaded build. That is not a formality: the
    platform refuses to instrument a package that is not debuggable unless the instrumentation
    carries the same signature, and the patched TikTok is not debuggable.

    Store and entry passwords can be supplied independently, including an empty store password
    for an exported BKS key. Unset passwords retain the local test-key defaults. apksigner receives
    temporary process environment references rather than password values. No key is converted or
    copied. The output, installed probe and TikTok certificates are checked before installation.
    Keep the key outside -OutDir. Keys and their copies or aliases are protected before output
    cleanup, and relative paths resolve from PowerShell's working folder.

.EXAMPLE
    tools/verification-probe/build.ps1 -Serial $env:HUSHFEED_DEVICE_SERIAL -Install

.EXAMPLE
    Load it into TikTok, then send it work. It answers in the log rather than through
    "am instrument -w", because TikTok replaces the thread's instrumentation and there is no
    result bundle to answer with by the time the probe is running. Reinstalling TikTok or
    stopping it drops the probe, so run the instrument line again after either.
    HUSHFEED_DEVICE_SERIAL names the test phone.

    $phone = $env:HUSHFEED_DEVICE_SERIAL
    adb -s $phone shell am instrument app.hushfeed.verification/.Probe
    adb -s $phone shell am broadcast -a app.hushfeed.verification.PROBE `
        -p com.zhiliaoapp.musically -e action dump
    adb -s $phone shell am broadcast -a app.hushfeed.verification.PROBE `
        -p com.zhiliaoapp.musically -e action set -e key auto_advance -e value true
    adb -s $phone logcat -d | Select-String HushfeedProbe
#>
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHFEED_CHAT_IDENTITY,
    [ValidateRange(1, 30)][int]$LeaseMinutes = 30,
    [switch]$Install,
    [switch]$Uninstall,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$Java,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [ValidateSet('BKS', 'JKS', 'PKCS12')][string]$KeystoreType,
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword,
    [string]$OutDir = (Join-Path $env:TEMP 'hushfeed-probe')
)

$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path -Parent (Split-Path -Parent $here)
. (Join-Path $root 'scripts/common.ps1')
. (Join-Path $root 'scripts/Resolve-Java.ps1')
. (Join-Path $root 'scripts/apk-signing.ps1')
. (Join-Path $root 'scripts/device-install.ps1')

function Initialize-ProbeOutputDirectory {
    param([string]$Path, [string]$RepositoryRoot)

    $absolute = [IO.Path]::GetFullPath($Path)
    if ($absolute.StartsWith('\\?\') -or $absolute.StartsWith('\\.\')) {
        throw 'Refusing probe output through a device path.'
    }
    foreach ($protected in @([IO.Path]::GetPathRoot($absolute),
            [Environment]::GetFolderPath('UserProfile'), $RepositoryRoot)) {
        if (-not $protected) { continue }
        $protected = [IO.Path]::GetFullPath($protected).TrimEnd('\')
        if ($absolute.TrimEnd('\') -eq $protected -or $protected.StartsWith($absolute.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing probe output at a filesystem, profile or repository root.'
        }
    }
    $absolute = $absolute.TrimEnd('\')
    if (Test-Path -LiteralPath (Join-Path $absolute '.git')) { throw 'Refusing probe output at a repository root.' }
    # GetFullPath is lexical. Reject links in every existing ancestor before trusting it.
    $cursor = $absolute
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $item = Get-Item -LiteralPath $cursor -Force
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing probe output through a linked path.' }
        }
        $cursor = Split-Path -Parent $cursor
    }
    $marker = Resolve-WithinRoot -Root $absolute -Path (Join-Path $absolute '.hushfeed-probe-output')
    $ownership = "hushfeed-verification-probe-output-v1`n$absolute"
    if (Test-Path -LiteralPath $absolute) {
        if (-not (Test-Path -LiteralPath $absolute -PathType Container)) { throw 'Refusing probe output over an existing file.' }
        $children = @(Get-ChildItem -LiteralPath $absolute -Force)
        if (@($children | Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint }).Count -gt 0) {
            throw 'Refusing probe output containing a linked path.'
        }
        if ($children.Count -gt 0 -and (-not (Test-Path -LiteralPath $marker -PathType Leaf) -or
                -not [string]::Equals([IO.File]::ReadAllText($marker), $ownership, [StringComparison]::OrdinalIgnoreCase))) {
            throw 'Refusing probe output in a nonempty unowned directory. Choose a fresh -OutDir.'
        }
        # Inspect one level at a time, so enumeration never follows a junction outside the output.
        $pending = New-Object 'Collections.Generic.Stack[IO.DirectoryInfo]'
        $pending.Push((Get-Item -LiteralPath $absolute -Force))
        while ($pending.Count -gt 0) {
            $directory = $pending.Pop()
            foreach ($child in @(Get-ChildItem -LiteralPath $directory.FullName -Force)) {
                if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing probe output containing a linked path.' }
                $relative = $child.FullName.Substring($absolute.Length + 1)
                $inClasses = $relative.StartsWith('classes\', [StringComparison]::OrdinalIgnoreCase)
                if ($child.PSIsContainer) {
                    $generated = $relative -in @('classes', 'dex') -or $inClasses
                } else {
                    $generated = $relative -in @('.hushfeed-probe-output', 'd8-inputs.args', 'probe-unsigned.apk', 'probe-aligned.apk',
                        'hushfeed-verification-probe.apk', 'hushfeed-verification-probe.apk.idsig') -or
                        ($inClasses -and $child.Extension -eq '.class') -or $relative -match '^dex\\classes\d*\.dex$'
                }
                if (-not $generated) { throw 'Refusing probe output containing unexpected files or directories.' }
                if ($child.PSIsContainer) { $pending.Push($child) }
            }
        }
        $absolute = Resolve-WithinRoot -Root (Split-Path -Parent $absolute) -Path $absolute
        Remove-Item -LiteralPath $absolute -Recurse -Force
        if (Test-Path -LiteralPath $absolute) { throw "Could not clear $absolute." }
    }
    New-Item -ItemType Directory -Path $absolute | Out-Null
    [IO.File]::WriteAllText($marker, $ownership)
    return $absolute
}

function Resolve-Adb {
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $found = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages') -Recurse `
        -Filter 'adb.exe' -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($found) { return $found.FullName }
    throw 'No adb found.'
}

if ($Uninstall) {
    if (-not $Serial) { throw '-Uninstall needs -Serial.' }
    $deviceLease = $null
    try {
        $adb = Resolve-Adb
        $deviceLease = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project 'hushfeed' `
            -ChatIdentity $ChatIdentity -Purpose 'verification probe uninstall' -LeaseMinutes $LeaseMinutes
        # pm path first: uninstalling a probe that isn't there fails with DELETE_FAILED_INTERNAL_ERROR.
        [void](Remove-AndroidPackageIfInstalled -Adb $adb -Serial $Serial -PackageName 'app.hushfeed.verification' -Lease $deviceLease)
    } finally { Remove-DeviceLease -Lease $deviceLease -WarnOnFailure }
    return
}

$buildTools = Get-ChildItem (Join-Path $Sdk 'build-tools') -Directory |
    Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending | Select-Object -First 1
if (-not $buildTools) { throw "No build-tools under $Sdk." }
$platform = Get-ChildItem (Join-Path $Sdk 'platforms') -Directory |
    Sort-Object { [int]($_.Name -replace '\D', '') } -Descending | Select-Object -First 1
if (-not $platform) { throw "No platforms under $Sdk." }
$androidJar = Join-Path $platform.FullName 'android.jar'
Write-Host "[probe] build-tools $($buildTools.Name), $($platform.Name)"

$Java = Resolve-Java -Explicit $Java
$javaPath = (Get-Command $Java -ErrorAction Stop).Source
$javac = Join-Path (Split-Path -Parent $javaPath) 'javac.exe'
if (-not $javac -or -not (Test-Path $javac)) { throw 'No javac found. Set JAVA_HOME.' }

$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
$signingSession = $null
$deviceLease = $null
try {
$signingSession = New-ApkSigningSession -BoundParameters $PSBoundParameters -Root $root -Sdk $Sdk `
    -Java $Java -Keystore $Keystore -KeyAlias $KeyAlias -KeystoreType $KeystoreType -OutputDirectory $OutDir

# Validate and lock the key before any output mutation, including whole-directory cleanup.
# A directory that cannot be cleared must not contribute stale classes to the new dex.
$OutDir = Initialize-ProbeOutputDirectory -Path $OutDir -RepositoryRoot $root
New-Item -ItemType Directory -Force -Path "$OutDir\classes", "$OutDir\dex" | Out-Null

# @() around both of these: with a single file the pipeline hands back a string rather than
# an array, and splatting a string spreads its characters, so javac is handed the colon out
# of C:\ as though it were a flag.
$sources = @(Get-ChildItem (Join-Path $here 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += Join-Path $root 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxRenderedModels.java'
Write-Host "[probe] compiling $($sources.Count) source file(s)"
# --release rather than -source/-target with a boot class path: a current JDK refuses that
# pairing outright. android.jar stays on the ordinary class path, which is where the android.*
# classes come from; java.* comes from the release the flag names.
# javac writes ordinary deprecation notes to stderr. Windows PowerShell 5.1 must judge
# compilation by its exit code, while still showing those notes and real compiler errors.
$preference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    & $javac --release 11 -classpath $androidJar `
        -d "$OutDir\classes" -encoding UTF-8 -nowarn @sources
    $compileStatus = $LASTEXITCODE
} finally { $ErrorActionPreference = $preference }
if ($compileStatus -ne 0) { throw 'javac failed.' }

$classes = @(Get-ChildItem "$OutDir\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName })
# d8.bat crosses cmd.exe's command-length limit once the probe has enough inner classes.
# Its argument file treats each line as one path, including spaces, without shell quoting.
$dexInputs = Join-Path $OutDir 'd8-inputs.args'
[IO.File]::WriteAllLines($dexInputs, $classes, (New-Object Text.UTF8Encoding($false)))
try {
    & (Join-Path $buildTools.FullName 'd8.bat') --lib $androidJar --min-api 23 --thread-count 2 --output "$OutDir\dex" "@$dexInputs"
    if ($LASTEXITCODE -ne 0) { throw 'd8 failed.' }
} finally { Remove-Item -LiteralPath $dexInputs -Force -ErrorAction SilentlyContinue }

$unsigned = Join-Path $OutDir 'probe-unsigned.apk'
& (Join-Path $buildTools.FullName 'aapt2.exe') link -I $androidJar `
    --manifest (Join-Path $here 'AndroidManifest.xml') -o $unsigned --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed.' }

# aapt2 writes the manifest and resource table; the dex goes in afterwards with the zip tool,
# which is what an ordinary build does too, just with more steps in between.
Push-Location "$OutDir\dex"
try {
    # aapt is gone from recent build-tools, and under Stop a missing command is a terminating
    # error that skipped the fallback below. Tested for, so the zip path is the one that runs.
    $aapt = Join-Path $buildTools.FullName 'aapt.exe'
    $added = $false
    if (Test-Path -LiteralPath $aapt -PathType Leaf) {
        & $aapt add $unsigned classes.dex | Out-Null
        $added = $LASTEXITCODE -eq 0
    }
    if (-not $added) {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
        try {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, (Join-Path $OutDir 'dex\classes.dex'), 'classes.dex') | Out-Null
        } finally { $zip.Dispose() }
    }
} finally { Pop-Location }

$aligned = Join-Path $OutDir 'probe-aligned.apk'
& (Join-Path $buildTools.FullName 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed.' }

$signed = Join-Path $OutDir 'hushfeed-verification-probe.apk'
Invoke-ApkSigning -Session $signingSession -InputApk $aligned -OutputApk $signed
Write-Host "[probe] $signed"

if ($Install) {
    if (-not $Serial) { throw '-Install needs -Serial.' }
    $adb = Resolve-Adb
    $deviceLease = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project 'hushfeed' `
        -ChatIdentity $ChatIdentity -Purpose 'verification probe install' -LeaseMinutes $LeaseMinutes
    Assert-InstalledApkSigner -Adb $adb -Serial $Serial -PackageName 'app.hushfeed.verification' -SigningSession $signingSession -Lease $deviceLease
    Assert-InstalledApkSigner -Adb $adb -Serial $Serial -PackageName 'com.zhiliaoapp.musically' `
        -SigningSession $signingSession -RequireInstalled -Lease $deviceLease
    Assert-DeviceLease -Lease $deviceLease -Serial $Serial
    & $adb -s $Serial install -r $signed | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb install failed on $Serial. The output above says why." }
    Write-Host '[probe] installed. Load it into TikTok, then send it work:'
    Write-Host "  adb -s $Serial shell am instrument app.hushfeed.verification/.Probe"
    Write-Host "  adb -s $Serial shell am broadcast -a app.hushfeed.verification.PROBE -p com.zhiliaoapp.musically -e action dump"
    Write-Host "  adb -s $Serial logcat -d | Select-String HushfeedProbe"
}
} finally {
    try { Remove-DeviceLease -Lease $deviceLease -WarnOnFailure }
    finally { Close-ApkSigningSession -Session $signingSession }
}
