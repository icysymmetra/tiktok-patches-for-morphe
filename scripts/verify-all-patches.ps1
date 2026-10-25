<#
.SYNOPSIS
    Apply every patch in the bundle and reject partial or unsuccessful output.

.DESCRIPTION
    The per-patch check with --exclusive answers one patch at a time. This applies all of
    them in a single run and validates the desktop CLI process, result report, patch names,
    target version and saved APK. A result file can be written from a finally block after a
    failed compile, so a zero failed-patch count is not enough to call the run successful.

    The bundle declares one compatible version, and the CLI refuses any other APK unless it is
    told to go ahead with -f. -Force does that for a retained newer build: the stock APK's own
    version is read with aapt2 and the result is held to that instead of the catalog's, so the
    run answers "which patches still apply on this build" rather than being refused before it
    starts. The package still has to be the catalog's unless -ProbePackage explicitly names an
    undeclared package with -Force. That route qualifies a candidate without changing support
    metadata, and still holds every patch, output package, resource and language file to the APK.

    A clean run then holds the patched APK's resource table to the stock one with
    ResourceTableCheck.java: every resource of every package has to resolve by its id with its
    type and each configuration's value, and every file and reference the patched values name
    has to be there. A failure names the id. What the patches rewrote, what the rebuild renamed
    and what was added go in a report beside the result file.

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\path\to\native-fixture.apk `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch

.EXAMPLE
    scripts/verify-all-patches.ps1 -Apk C:\fixtures\tiktok-46.8.3.apk -Force `
        -DesktopJar C:\path\to\morphe-desktop.jar -WorkDir C:\path\to\scratch
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$DesktopJar,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Bundle,
    [string]$PatchList,
    [string]$Java,
    [switch]$Force,
    [string]$Aapt2,
    [string]$ProbePackage,
    # A folder that keeps a passing run's report and patched APK, with a stamp naming the bundle,
    # APK, patch list and CLI that made them, so the release receipt can read this run instead of
    # patching the same fixture again. Emptied first, and only when it's missing, empty or a folder
    # an earlier run kept; a failed run leaves just the ownership marker.
    [string]$KeepIn
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
$Java = Resolve-Java -Explicit $Java
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

if (-not $Bundle) {
    $version = Get-BundleVersion -Root $root
    $Bundle = Get-ReleaseBundlePath -Root $root -Version $version
    if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
        throw "No bundle for version $version at $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
    }
}
if (-not $PatchList) { $PatchList = Join-Path $root 'patches-list.json' }
if (-not $Bundle -or -not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "No bundle found. Run :patches:buildAndroid first." }
if (-not (Test-Path -LiteralPath $PatchList -PathType Leaf)) { throw "No patch list found: $PatchList" }
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) { throw "Desktop CLI jar not found: $DesktopJar" }

try {
    $catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
    $names = @($catalog.patches | ForEach-Object { $_.name })
} catch {
    throw "Could not read patch list ${PatchList}: $($_.Exception.Message)"
}
$expectedTarget = Get-PatchTarget -PatchList $catalog
if ($names.Count -eq 0 -or @($names | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count -ne 0) {
    throw "No valid patches listed in $PatchList."
}
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)
Write-Host "[verify] $($names.Count) patches from $(Split-Path -Leaf $Bundle)"

# The version the result is held to: whatever the stock APK says it is, read the same way the
# receipt reads it. The catalog declares more than one build, so the APK names which one this is.
# A build it doesn't declare is patched only under -Force.
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $root
$stock = Get-ApkManifestFacts -Apk $Apk -Aapt2 $Aapt2
$verificationTarget = Resolve-PatchVerificationTarget -Stock $stock -Target $expectedTarget `
    -Force:$Force -ProbePackage $ProbePackage
$expectedVersion = $verificationTarget.PackageVersion
$declaredText = Format-VersionList -Versions @($expectedTarget.PackageVersions)
$forced = $verificationTarget.Forced
if ($verificationTarget.Probe) {
    Write-Host "[verify] undeclared-package qualification: $($stock.package) $expectedVersion; this does not establish supported metadata"
} elseif ($forced) {
    Write-Host "[verify] forcing the bundle onto $($stock.package) $($stock.versionName); it declares $declaredText"
} else {
    Write-Host "[verify] $($stock.package) $($stock.versionName) is a declared target, so nothing is forced"
}

if ($KeepIn) {
    # Checked before anything is deleted or patched: a mistyped -KeepIn used to be emptied whole.
    $KeepIn = Initialize-KeptRunDirectory -Path $KeepIn -RepositoryRoot $root `
        -Inputs @($Apk, $Bundle, $PatchList, $DesktopJar, $WorkDir)
}
New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$runId = [guid]::NewGuid().ToString('N')
$runDir = Join-Path $workRoot "verify-$runId"
New-Item -ItemType Directory -Force -Path $runDir | Out-Null
$out = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all.apk') -Root $workRoot
$temp = Resolve-WithinRoot -Path (Join-Path $runDir 'verify-all-tmp') -Root $workRoot
$result = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-result-$runId.json") -Root $workRoot
$enable = @()
foreach ($name in $names) { $enable += '-e'; $enable += $name }
$arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
    '-o', $out, '-t', $temp, '-r', $result)
if ($forced) { $arguments += '-f' }
$arguments = $arguments + $enable + @($Apk)
$exitCode = 1

try {
    # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $cliOutput = @(& $Java '-jar' $DesktopJar @arguments 2>&1)
        $cliExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    $cliOutput | ForEach-Object {
        $line = [string]$_
        if ($line -match 'SEVERE|ERROR|Exception|result saved|Saved to') { Write-Host "[verify] $line" }
    }

    $report = $null
    if (Test-Path -LiteralPath $result -PathType Leaf) {
        try { $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json }
        catch { Write-Warning "Could not parse result JSON: $($_.Exception.Message)" }
    }
    $validation = Test-PatchingReport -Report $report -ExpectedNames $names `
        -AllowedDependencyNames $dependencyNames -OutputPath $out `
        -ExpectedPackageName $verificationTarget.PackageName -ExpectedPackageVersion $expectedVersion
    $reportApplied = if ($null -ne $report) { @($report.appliedPatches).Count } else { 0 }
    $reportFailed = if ($null -ne $report) { @($report.failedPatches).Count } else { 0 }
    $target = if ($null -ne $report) { "$($report.packageName) $($report.packageVersion)" } else { 'unknown target' }
    Write-Host "[verify] ${target}: applied $reportApplied, failed $reportFailed, CLI exit $cliExitCode"
    if ($null -ne $report) {
        foreach ($failure in @($report.failedPatches)) {
            $patchName = if ($null -ne $failure.patch) { $failure.patch.name } else { 'unknown patch' }
            Write-Host "[verify] FAILED ${patchName}: $($failure.reason -split "`n" | Select-Object -First 1)"
        }
    }
    Write-Host "[verify] result file: $result"
    if ($cliExitCode -ne 0) { Write-Warning "The desktop CLI exited with $cliExitCode." }
    if (-not $validation.Valid) { Write-Warning "[verify] $($validation.Reason)" }
    if ($cliExitCode -eq 0 -and $validation.Valid) {
        # The rebuilt resource table against the stock one. A resource patch has Morphe decode and
        # rebuild all twelve packages of TikTok's table, and an id the rebuild loses only fails
        # when TikTok inflates it (upstream #84, layout 0x7e03004d in the search package).
        $resourceReport = Resolve-WithinRoot -Path (Join-Path $workRoot "verify-all-resources-$runId.txt") -Root $workRoot
        $global:LASTEXITCODE = 0
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $resourceOutput = @(& $Java '-Xmx4g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'ResourceTableCheck.java') `
                $Apk $out $resourceReport 2>&1)
            $resourceExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $preference
        }
        $resourceOutput | ForEach-Object { Write-Host "[verify] $_" }
        Write-Host "[verify] resource report: $resourceReport"
        if ($resourceExitCode -eq 0) {
            # This command uses each patch's default options. Selecting every patch must not
            # silently replace TikTok's native translations with English (#67).
            $languagePatch = @($catalog.patches | Where-Object { $_.name -eq 'Remove unused language packs' })
            if ($languagePatch.Count -gt 0) {
                $locales = @($languagePatch[0].options | Where-Object { $_.key -eq 'locales' })
                if ($locales.Count -ne 1 -or $locales[0].default -ne 'all') {
                    throw 'Remove unused language packs must default to all in an all-patches verification.'
                }
                Add-Type -AssemblyName System.IO.Compression.FileSystem
                $stockZip = [System.IO.Compression.ZipFile]::OpenRead($Apk)
                $patchedZip = $null
                $digest = [System.Security.Cryptography.SHA256]::Create()
                try {
                    $patchedZip = [System.IO.Compression.ZipFile]::OpenRead($out)
                    $packs = @($stockZip.Entries | Where-Object {
                        $_.FullName.StartsWith('assets/strings#lang_', [StringComparison]::Ordinal) -and
                            -not $_.FullName.EndsWith('/')
                    })
                    if ($packs.Count -eq 0) { throw 'The stock APK has no native language files to verify.' }
                    foreach ($entry in $packs) {
                        $retained = $patchedZip.GetEntry($entry.FullName)
                        if ($null -eq $retained -or $retained.Length -ne $entry.Length) {
                            throw "The default all-patches build removed or changed $($entry.FullName)."
                        }
                        $sourceStream = $entry.Open()
                        try { $sourceHash = [BitConverter]::ToString($digest.ComputeHash($sourceStream)) }
                        finally { $sourceStream.Dispose() }
                        $retainedStream = $retained.Open()
                        try { $retainedHash = [BitConverter]::ToString($digest.ComputeHash($retainedStream)) }
                        finally { $retainedStream.Dispose() }
                        if ($sourceHash -ne $retainedHash) {
                            throw "The default all-patches build changed $($entry.FullName)."
                        }
                    }
                    Write-Host "[verify] all $($packs.Count) native language files are byte-identical to stock."
                } finally {
                    $digest.Dispose()
                    if ($patchedZip) { $patchedZip.Dispose() }
                    $stockZip.Dispose()
                }
            }
            Write-Host '[verify] success: every requested patch applied to a valid APK whose resource table holds every stock resource.'
            if ($KeepIn) {
                New-Item -ItemType Directory -Force -Path $KeepIn | Out-Null
                Move-Item -LiteralPath $out -Destination (Join-Path $KeepIn 'patched.apk') -Force
                Copy-Item -LiteralPath $result -Destination (Join-Path $KeepIn 'result.json') -Force
                # Written last, so a keep cut short has no stamp and is never read.
                [ordered]@{
                    bundleSha256     = Get-Sha256Hex -Path $Bundle
                    apkSha256        = Get-Sha256Hex -Path $Apk
                    patchListSha256  = Get-Sha256Hex -Path $PatchList
                    desktopJarSha256 = Get-Sha256Hex -Path $DesktopJar
                    versionName      = $stock.versionName
                    forced           = [bool]$forced
                } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $KeepIn 'stamp.json') -Encoding UTF8
                Write-Host "[verify] kept this run for the release receipt in $KeepIn"
            }
            $exitCode = 0
        } else {
            Write-Warning "[verify] the patched resource table failed its check against the stock one (exit $resourceExitCode)."
        }
    }
} finally {
    Remove-GeneratedPath -Path $runDir -Root $workRoot
}

exit $exitCode
