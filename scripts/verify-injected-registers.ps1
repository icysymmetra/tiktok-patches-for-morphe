<#
.SYNOPSIS
    Check that nothing the bundle injects uses a register its host method never declared.

.DESCRIPTION
    A patch that writes into a register beyond the method's register count assembles without
    complaint and applies without complaint. The desktop CLI reports it as applied. It fails
    only when a device verifies the class, and then it fails as a VerifyError on whatever
    screen happens to reach it first.

    Two checks, either of which catches that:

    Static. scripts/DexDiff.java lists every method the patched APK does not share with the
    clean build it came from, and holds each line that was not in the clean body, and every
    line of the bundle's own added methods, to that method's register count.

    On a device. The Android runtime's own verifier is the authority, so with -Serial the
    clean APK and the patched APK are both put through dex2oat with the verify filter and the
    verifier's messages are compared. This target's own code raises lock-verification warnings
    on both, which is why it compares the two tallies rather than looking for an empty one: the
    patched build has to raise exactly the same message multiset as the clean build.

    Removed host methods and DEX entries fail the static check. The allowlist beside this script
    accepts only exact reviewed removals, and a stale entry fails so an old exception cannot mask
    a later regression.

    Both halves refuse to pass on absent evidence. A run where the clean build raised nothing,
    or the patched build raised nothing, or the dex comparison found no difference at all, is
    a run that compared the wrong things, and it says so instead of reporting success.

    The CLI's own --verify-with-sdk is not used here. Its d8 stage cannot round-trip this
    target's classes14.dex on any installed build-tools version, patched or not, so it reports
    a failure that says nothing about the bundle.

.EXAMPLE
    scripts/verify-injected-registers.ps1 -PatchedApk C:\work\patched.apk

.EXAMPLE
    scripts/verify-injected-registers.ps1 -FromDevice -Serial $env:HUSHFEED_DEVICE_SERIAL

    The clean APK defaults to the 47.4.3 vendor build in the folder HUSHFEED_FIXTURE_DIR names.

    The phones are shared, so the pull and the device verifier each run under the device lease
    (scripts/device-lease.ps1) on -Serial, taken right before and released right after, and the
    script refuses the device half while another task holds that phone. Pass -LeaseDir and
    -ChatIdentity, or set HUSHFEED_DEVICE_LEASE_DIR and HUSHFEED_CHAT_IDENTITY.
#>
[CmdletBinding()]
param(
    [string]$CleanApk,
    [string]$PatchedApk,
    [switch]$FromDevice,
    [string]$Serial,
    [string]$Adb,
    [string]$ReportPath,
    [string]$Java,
    [string]$DesktopJar,
    [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHFEED_CHAT_IDENTITY,
    [ValidateRange(1, 30)][int]$LeaseMinutes = 30
)

$ErrorActionPreference = 'Stop'
# The vendor 47.4.3 build the README links, which is the only clean side this comparison means.
$CleanApkSha256 = 'af7cbe2b00d33ff1339730f6abb8ed7e387a83b27ff2b16748b8f41e6fc87e0b'
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'device-lease.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')
$Java = Resolve-Java -Explicit $Java

if (-not $DesktopJar) { $DesktopJar = $env:HUSHFEED_DESKTOP_JAR }
if (-not $DesktopJar -or -not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) {
    # dexlib2 comes from the desktop CLI. Without it the comparison cannot run at all, and
    # saying so beats a stack trace about a missing class.
    throw 'No Morphe desktop CLI. Pass -DesktopJar or set HUSHFEED_DESKTOP_JAR.'
}

function Resolve-Adb {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    if ($env:HUSHFEED_ADB) { return $env:HUSHFEED_ADB }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    $winget = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    $found = Get-ChildItem $winget -Recurse -Filter 'adb.exe' -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($found) { return $found.FullName }
    throw 'No adb found. Pass -Adb or set HUSHFEED_ADB.'
}

if (-not $CleanApk -and $env:HUSHFEED_FIXTURE_DIR) {
    $fixture = Get-ChildItem -LiteralPath $env:HUSHFEED_FIXTURE_DIR -Filter '*47.4.3*.apk' -File -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($fixture) { $CleanApk = $fixture.FullName }
}
if (-not $CleanApk -or -not (Test-Path -LiteralPath $CleanApk -PathType Leaf)) {
    throw ('No clean APK. Pass -CleanApk with the vendor build this bundle targets, or set ' +
        'HUSHFEED_FIXTURE_DIR to the folder that holds it.')
}
# The clean side has to be the vendor build, not the largest file in a folder that also holds
# patched output and newer builds: two patched files differ from each other, both halves
# report differences, and the run passes while comparing nothing against a clean baseline.
$cleanHash = (Get-FileHash -LiteralPath $CleanApk -Algorithm SHA256).Hash.ToLowerInvariant()
if ($cleanHash -ne $CleanApkSha256) {
    throw "The clean APK at $CleanApk hashes to $cleanHash, not the vendor 47.4.3 build ($CleanApkSha256)."
}

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-regs-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Force -Path $work | Out-Null
# The report stays where the caller can read it after the run: at -ReportPath when one is given,
# and otherwise in the working directory, which is kept whenever the run failed or stopped short,
# because that is when the FAIL line points at the report. The pulled APK never outlives the run,
# because a full TikTok is hundreds of megabytes. Worked out before the default path is filled
# in: reading [bool]$ReportPath after that was always true, and read before it, it was false
# exactly when the report lived in the directory the cleanup removed.
$reportInWork = -not $ReportPath
if ($reportInWork) { $ReportPath = Join-Path $work 'injected-registers.txt' }
$failed = $false
$completed = $false
try {

$adbPath = $null
if ($FromDevice -or $Serial) {
    # Checked here so a missing setting fails now, not after the minutes-long dex comparison.
    if (-not $LeaseDir) { throw 'Device checks require -LeaseDir or HUSHFEED_DEVICE_LEASE_DIR for the shared device pool.' }
    if (-not $ChatIdentity) { throw 'Device checks require -ChatIdentity or HUSHFEED_CHAT_IDENTITY.' }
    $adbPath = Resolve-Adb -Explicit $Adb
}

if ($FromDevice) {
    if (-not $Serial) { throw '-FromDevice needs -Serial so it cannot pull from somebody else''s phone.' }
    # Released right after the pull, so the dex comparison doesn't hold the phone for minutes.
    $deviceLease = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project 'hushfeed' `
        -ChatIdentity $ChatIdentity -Purpose 'injected-register check pull' -LeaseMinutes $LeaseMinutes
    try {
        Assert-DeviceLease -Lease $deviceLease -Serial $Serial
        $paths = @(& $adbPath -s $Serial shell pm path com.zhiliaoapp.musically 2>&1 |
            ForEach-Object { "$_" } | Where-Object { $_ -match '^package:' })
        if ($paths.Count -eq 0) { throw "The target is not installed on $Serial." }
        if ($paths.Count -ne 1) {
            # A split install has no single APK to compare, and quietly taking the first would
            # compare the base against a whole clean build and call the rest missing.
            throw "The install on $Serial is split across $($paths.Count) APKs; pass -PatchedApk instead."
        }
        $onDevice = ($paths[0] -replace '^package:', '').Trim()
        $PatchedApk = Join-Path $work 'patched-installed.apk'
        Write-Host "[registers] pulling $onDevice"
        Assert-DeviceLease -Lease $deviceLease -Serial $Serial
        & $adbPath -s $Serial pull $onDevice $PatchedApk | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not pull the installed APK from $Serial." }
    } finally {
        Remove-DeviceLease -Lease $deviceLease -WarnOnFailure
    }
}

if (-not $PatchedApk -or -not (Test-Path -LiteralPath $PatchedApk -PathType Leaf)) {
    throw 'No patched APK. Pass -PatchedApk, or -FromDevice -Serial <serial>.'
}

Write-Host "[registers] clean   $CleanApk"
Write-Host "[registers] patched $PatchedApk"

$diffOutput = & $Java '-Xmx6g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'DexDiff.java') `
    $CleanApk $PatchedApk $ReportPath `
    (Join-Path $PSScriptRoot 'injected-register-removal-allowlist.txt') 2>&1
$diffExit = $LASTEXITCODE
$diffOutput | ForEach-Object { Write-Host "[registers] $_" }

if ($diffExit -ne 0) {
    Write-Host "[registers] FAIL: the dex comparison exited $diffExit; see $ReportPath"
    $failed = $true
} else {
    Write-Host '[registers] static: every injected instruction stays inside its host method register count.'
}

if ($Serial) {
    Write-Host "[registers] running the device verifier on $Serial"
    $deviceLease = New-DeviceLease -LeaseDir $LeaseDir -Serial $Serial -Project 'hushfeed' `
        -ChatIdentity $ChatIdentity -Purpose 'injected-register device verifier' -LeaseMinutes $LeaseMinutes
    try {
        $cleanTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
            -Local $CleanApk -Label 'clean' -Lease $deviceLease
        # Each tally pushes and compiles a whole APK, so the patched one starts on a fresh lease.
        Update-DeviceLease -Lease $deviceLease -LeaseMinutes $LeaseMinutes
        $patchedTally = Invoke-AndroidVerifierTally -Adb $adbPath -Serial $Serial `
            -Local $PatchedApk -Label 'patched' -Lease $deviceLease
    } finally {
        Remove-DeviceLease -Lease $deviceLease -WarnOnFailure
    }
    $comparison = Compare-VerifierTallies -Clean $cleanTally -Patched $patchedTally
    Write-Host "[registers] verifier messages: clean $($comparison.CleanTotal), patched $($comparison.PatchedTotal)"

    # Both sides have to have said something. An empty set compares equal to anything, so a
    # push or a verify that produced nothing would otherwise read as a clean result.
    if (-not $comparison.HasEvidence) {
        Write-Host '[registers] FAIL: one of the two runs raised no verifier message at all, so the comparison proves nothing.'
        $failed = $true
    } elseif (-not $comparison.Valid) {
        $missing = @($comparison.Deltas | Where-Object Kind -eq 'missing')
        $extra = @($comparison.Deltas | Where-Object Kind -eq 'extra')
        if ($missing.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build is missing $($missing.Count) verifier message tally entry or count:"
            $missing | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        if ($extra.Count -ne 0) {
            Write-Host "[registers] FAIL: the patched build has $($extra.Count) extra verifier message tally entry or count:"
            $extra | Select-Object -First 20 | ForEach-Object {
                Write-Host ("[registers]   {0}  (clean {1}, patched {2})" -f $_.Message, $_.Clean, $_.Patched)
            }
        }
        $failed = $true
    } else {
        Write-Host '[registers] device: the clean and patched verifier message tallies are identical.'
    }
}

$completed = $true
} finally {
    $keepReport = $reportInWork -and ($failed -or -not $completed) -and
        (Test-Path -LiteralPath $ReportPath -PathType Leaf)
    if ($keepReport) {
        Get-ChildItem -LiteralPath $work -Recurse -File -Filter '*.apk' -ErrorAction SilentlyContinue |
            Remove-Item -Force -ErrorAction SilentlyContinue
        Write-Host "[registers] the report is kept at $ReportPath"
    } else {
        Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

if ($failed) { exit 1 }
Write-Host '[registers] success.'
exit 0
