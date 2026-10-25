<#
.SYNOPSIS
    Build the machine-readable release provenance receipt.

.DESCRIPTION
    Local patching puts three things together: the user's APK, this bundle, and a toolchain. A
    checksum can only say that one of them arrived unaltered. This writes down all of it as one
    JSON document: which commit and tag the bundle was built from, what the bundle weighs and
    hashes to, which extension payloads it carries, which APK each proof was run against, that
    every patch in the catalog applied to it, and what patching did to the Android manifest.

    Nothing here is asserted. Each fixture is patched with the real desktop CLI, the verdicts
    come out of the CLI's own result report, and both manifests are read back with aapt2. A
    patch that fails on any fixture stops the run with its name and no receipt is written: the
    receipt describes a bundle that fully applies, which is why the validator refuses any
    verdict of applied = false rather than reading it as a recorded failure. At least one
    fixture has to be the catalog's declared version patched without -f, or there is nothing
    in the receipt a user's Manager run corresponds to.

    The patched APKs are working files and are deleted on the way out, including after a failure.
    With -AppliedDir, a fixture the pre-push gate already patched with the same bundle, APK, patch
    list and CLI is read from the gate's kept run, the same report and patched APK, and checked the
    same way. Those files stay where the gate left them.

.EXAMPLE
    One -Fixture taking a comma separated list, not the switch repeated: PowerShell binds a
    parameter once and refuses the second.

    scripts/build-release-receipt.ps1 -WorkDir C:\scratch `
        -Fixture C:\fixtures\tiktok-47.1.4.apk,C:\fixtures\tiktok-47.1.4-merged.apk
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string[]]$Fixture,
    [Parameter(Mandatory = $true)][string]$WorkDir,
    [string]$Root,
    [string]$Bundle,
    [string]$PatchList,
    [string]$DesktopJar,
    [string]$Java,
    [string]$Aapt2,
    [string]$OutputPath,
    # The pre-push gate's kept runs, one folder per version (patches/build/fixture-apply). A
    # fixture whose stamp names this bundle, this APK, this patch list and this CLI is read from
    # there, the same report and patched APK this script's own run would produce, instead of
    # being patched a second time. Anything else is patched here as before.
    [string]$AppliedDir
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$Java = Resolve-Java -Explicit $Java
$Aapt2 = Resolve-Aapt2 -Explicit $Aapt2 -Root $Root

if (-not $DesktopJar) { $DesktopJar = $env:HUSHFEED_DESKTOP_JAR }
if (-not $DesktopJar -or -not (Test-Path -LiteralPath $DesktopJar -PathType Leaf)) {
    throw ('No Morphe desktop CLI. Pass -DesktopJar or set HUSHFEED_DESKTOP_JAR: the receipt ' +
        'records real patch verdicts, so it has to actually patch each fixture.')
}

$releaseVersion = Get-BundleVersion -Root $Root
if (-not $Bundle) { $Bundle = Get-ReleaseBundlePath -Root $Root -Version $releaseVersion }
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) {
    throw "No bundle for version ${releaseVersion}: $Bundle. Run :patches:generatePatchesList then :patches:buildAndroid."
}
if (-not $PatchList) { $PatchList = Join-Path $Root 'patches-list.json' }
if (-not $OutputPath) { $OutputPath = Join-Path $Root "release-receipt-$releaseVersion.json" }

$catalog = Get-Content -LiteralPath $PatchList -Raw | ConvertFrom-Json
$patchNames = @($catalog.patches | ForEach-Object { $_.name })
if ($patchNames.Count -eq 0) { throw "No patches listed in $PatchList." }
$dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $patchNames)
$expectedTarget = Get-PatchTarget -PatchList $catalog

$catalogText = Get-Content -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') -Raw
$patcherMatch = [regex]::Match($catalogText, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
$floorMatch = [regex]::Match($catalogText, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
if (-not $patcherMatch.Success -or -not $floorMatch.Success) {
    throw 'gradle/libs.versions.toml does not pin both morphe-patcher and manager-floor.'
}

$commit = (Invoke-RepoGit -Root $Root -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)
$commit = "$commit".Trim()
if ($commit -notmatch '^[0-9a-f]{40}$') { throw "git did not answer with a commit: $commit" }
$commitTimestamp = [long]((Invoke-RepoGit -Root $Root -Arguments @('log', '-1', '--format=%ct') |
    Select-Object -First 1))

# The bundle is read once, here, before any fixture is patched. Measuring it at the end instead
# would describe whatever the release path holds when the run finishes, which is not necessarily
# what the patch runs used: a run takes long enough for another buildAndroid to replace it.
$bundleManifest = Get-BundleManifestFacts -BundlePath $Bundle
$bundleSize = (Get-Item -LiteralPath $Bundle).Length
$bundleHash = Get-Sha256Hex -Path $Bundle

# New receipt creation always uses the current provenance contract, including for an old version
# label. Historical schema 1 reading is confined to validation of frozen published documents.
$sourceCheck = Test-BuildSourceFacts -Source $bundleManifest.source -ExpectedCommit $commit
if (-not $sourceCheck.Valid) { throw "The bundle is not eligible for a new receipt: $($sourceCheck.Reason)" }

# Checked before any fixture is patched. This is the release's reproducibility policy, not a
# substitute for the independent commit/clean/input checks above.
if ([long]$bundleManifest.timestamp -ne $commitTimestamp * 1000) {
    throw ("$Bundle is stamped $($bundleManifest.timestamp), not with this commit's time " +
        "($($commitTimestamp * 1000)). Build it again with the release timestamp at $commit.")
}

# Refused here rather than reported, because a receipt that records the mismatch would be a
# document saying its own subject cannot be rebuilt from the source it names.
$dirty = @(Invoke-RepoGit -Root $Root -Arguments @('status', '--porcelain', '--untracked-files=all'))
if ($LASTEXITCODE -ne 0) { throw 'Could not read the working tree for receipt creation.' }
if ($dirty.Count -gt 0) {
    # -join, not Join-String: the pre-push hook prefers pwsh and falls back to Windows
    # PowerShell 5.1, which has no Join-String, so a dirty tree would have stopped the run with
    # a command-not-found instead of the reason it stopped.
    $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
    throw ("The working tree has uncommitted changes, so the commit this receipt would name is " +
        "not what was built: $shown")
}

function Get-ExtensionPayloads {
    <#
    .SYNOPSIS
        The extension DEX each bundle carries, by name, size and hash.
    .DESCRIPTION
        An .mpe is a bare Android DEX, checked here by its magic rather than by its extension,
        so a bundle that shipped something else in that slot cannot pass as an extension.
    #>
    param([string]$BundlePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $payloads = New-Object System.Collections.Generic.List[object]
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        foreach ($entry in @($archive.Entries | Where-Object { $_.FullName -like 'extensions/*.mpe' } |
                Sort-Object FullName)) {
            $stream = $entry.Open()
            try {
                $memory = New-Object System.IO.MemoryStream
                $stream.CopyTo($memory)
                $bytes = $memory.ToArray()
                $memory.Dispose()
            } finally { $stream.Dispose() }

            $magic = [System.Text.Encoding]::ASCII.GetString($bytes, 0, [Math]::Min(4, $bytes.Length))
            if ($magic -ne "dex`n") {
                throw "$($entry.FullName) in $BundlePath is not an Android DEX payload."
            }
            $sha = [System.Security.Cryptography.SHA256]::Create()
            try { $hash = ($sha.ComputeHash($bytes) | ForEach-Object { '{0:X2}' -f $_ }) -join '' }
            finally { $sha.Dispose() }

            $payloads.Add([ordered]@{
                name      = $entry.FullName
                sizeBytes = [long]$bytes.Length
                sha256    = $hash
            })
        }
    } finally { $archive.Dispose() }

    if ($payloads.Count -eq 0) { throw "The bundle carries no extension payload: $BundlePath" }
    return ,$payloads.ToArray()
}

function Get-PatchVerdicts {
    <#
    .SYNOPSIS
        One entry per catalog patch, in catalog order, from the CLI's own report.
    .DESCRIPTION
        Every requested patch has to appear in exactly one of the report's two lists. A patch in
        neither was not decided, and recording it as applied would be the receipt inventing a
        verdict, which is the one thing it exists not to do.
    #>
    param([object]$Report, [string[]]$Names)

    $applied = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@(Get-ReportPatchNames -Entries $Report.appliedPatches),
        [System.StringComparer]::Ordinal)
    $failures = @{}
    foreach ($failure in @($Report.failedPatches)) {
        $name = if ($null -ne $failure.patch) { [string]$failure.patch.name } else { $null }
        if (-not $name) { continue }
        $reason = ([string]$failure.reason -split "`n" | Select-Object -First 1).Trim()
        $failures[$name] = $reason
    }

    $verdicts = New-Object System.Collections.Generic.List[object]
    foreach ($name in $Names) {
        if ($applied.Contains($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $true; reason = $null })
        } elseif ($failures.ContainsKey($name)) {
            $verdicts.Add([ordered]@{ name = $name; applied = $false; reason = $failures[$name] })
        } else {
            throw "The result report decided nothing about patch $name."
        }
    }
    return ,$verdicts.ToArray()
}

# Read with the hash and size above, and for the same reason.
$extensionPayloads = Get-ExtensionPayloads -BundlePath $Bundle

New-Item -ItemType Directory -Force -Path $WorkDir | Out-Null
$workRoot = (Resolve-Path -LiteralPath $WorkDir).Path
$targets = New-Object System.Collections.Generic.List[object]

foreach ($apk in $Fixture) {
    if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "Fixture not found: $apk" }
    $label = Split-Path -Leaf $apk

    $stock = Get-ApkManifestFacts -Apk $apk -Aapt2 $Aapt2
    if ($stock.package -ne $expectedTarget.PackageName) {
        throw "$label is $($stock.package), not the catalog's target $($expectedTarget.PackageName)."
    }

    $runId = [guid]::NewGuid().ToString('N')
    $runDir = Resolve-WithinRoot -Path (Join-Path $workRoot "receipt-$runId") -Root $workRoot
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    try {
        $out = Resolve-WithinRoot -Path (Join-Path $runDir 'patched.apk') -Root $workRoot
        $temp = Resolve-WithinRoot -Path (Join-Path $runDir 'tmp') -Root $workRoot
        $resultPath = Resolve-WithinRoot -Path (Join-Path $runDir 'result.json') -Root $workRoot

        # The bundle declares its compatible versions. A fixture of any other build is patched
        # under -f, which is the whole point of keeping the other builds around, and the receipt
        # says so rather than letting a forced run read like a declared-compatible one.
        $forced = $stock.versionName -cnotin @($expectedTarget.PackageVersions)

        $keptRun = $null
        if ($AppliedDir) {
            $keptDir = Join-Path $AppliedDir $stock.versionName
            $stampPath = Join-Path $keptDir 'stamp.json'
            if (Test-Path -LiteralPath $stampPath -PathType Leaf) {
                $stamp = Get-Content -LiteralPath $stampPath -Raw | ConvertFrom-Json
                if ($stamp.bundleSha256 -eq $bundleHash -and $stamp.apkSha256 -eq (Get-Sha256Hex -Path $apk) -and
                    $stamp.patchListSha256 -eq (Get-Sha256Hex -Path $PatchList) -and
                    $stamp.desktopJarSha256 -eq (Get-Sha256Hex -Path $DesktopJar) -and [bool]$stamp.forced -eq $forced -and
                    (Test-Path -LiteralPath (Join-Path $keptDir 'result.json') -PathType Leaf) -and
                    (Test-Path -LiteralPath (Join-Path $keptDir 'patched.apk') -PathType Leaf)) {
                    $keptRun = $keptDir
                } else {
                    Write-Host "[receipt] the gate's run of $label was made with another bundle, APK, patch list or CLI, so patching it here"
                }
            }
        }
        if ($keptRun) {
            Write-Host "[receipt] reading the gate's run of ${label}: same bundle, APK, patch list and CLI"
            $out = Join-Path $keptRun 'patched.apk'
            $resultPath = Join-Path $keptRun 'result.json'
            $cliOutput = @()
            $cliExitCode = 0
        } else {
            Write-Host "[receipt] patching $label with $($patchNames.Count) patches"
            $enable = @()
            foreach ($name in $patchNames) { $enable += '-e'; $enable += $name }
            $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
                '-o', $out, '-t', $temp, '-r', $resultPath)
            if ($forced) { $arguments += '-f' }
            $arguments = $arguments + $enable + @($apk)
            # Kept, not dropped: the 0.60.0 run stopped on 46.2.3 with no result report and no word
            # on why, and the same step applied all 94 patches the next morning.
            # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
            $preference = $ErrorActionPreference
            $ErrorActionPreference = 'Continue'
            try {
                $cliOutput = @(& $Java '-jar' $DesktopJar @arguments 2>&1)
                $cliExitCode = $LASTEXITCODE
            } finally {
                $ErrorActionPreference = $preference
            }
        }

        if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
            throw ("The desktop CLI wrote no result report for $label (exit $cliExitCode). " +
                "What it printed last:`n$(Get-CliOutputTail -Output $cliOutput)")
        }
        $report = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $validation = Test-PatchingReport -Report $report -ExpectedNames $patchNames `
            -AllowedDependencyNames $dependencyNames -OutputPath $out `
            -ExpectedPackageName $expectedTarget.PackageName `
            -ExpectedPackageVersion $stock.versionName
        if (-not $validation.Valid) { throw "$label did not patch cleanly: $($validation.Reason)" }
        if ($cliExitCode -ne 0) {
            throw ("The desktop CLI exited with $cliExitCode on $label. " +
                "What it printed last:`n$(Get-CliOutputTail -Output $cliOutput)")
        }

        $patched = Get-ApkManifestFacts -Apk $out -Aapt2 $Aapt2
        $delta = Get-ManifestDelta -Stock $stock -Patched $patched
        $verdicts = Get-PatchVerdicts -Report $report -Names $patchNames
        $changes = @(ConvertTo-ManifestDeltaEntries -Delta $delta)
        Write-Host ("[receipt] $label" + ": $(@($verdicts | Where-Object { $_.applied }).Count)/" +
            "$($patchNames.Count) applied, $($changes.Count) manifest changes")
        foreach ($change in $changes) { Write-Host "[receipt]   $change" }

        $targets.Add([ordered]@{
            source        = [ordered]@{
                file        = $label
                package     = $stock.package
                versionName = $stock.versionName
                versionCode = $stock.versionCode
                sha256      = Get-Sha256Hex -Path $apk
                forced      = $forced
            }
            patches       = $verdicts
            manifestDelta = [ordered]@{
                permissionsAdded          = @($delta.permissionsAdded)
                permissionsRemoved        = @($delta.permissionsRemoved)
                exportedComponentsAdded   = @($delta.exportedComponentsAdded)
                exportedComponentsRemoved = @($delta.exportedComponentsRemoved)
            }
        })
    } finally {
        if ($runDir.StartsWith($workRoot, [System.StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $runDir)) {
            Remove-Item -LiteralPath $runDir -Recurse -Force -ErrorAction SilentlyContinue
        }
    }
}

$receipt = [ordered]@{
    schemaVersion = Get-ReleaseReceiptSchemaVersion
    generatedAt   = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    release       = [ordered]@{
        version         = $releaseVersion
        tag             = "v$releaseVersion"
        commit          = $commit
        commitTimestamp = $commitTimestamp
        patchCount      = $patchNames.Count
    }
    bundle        = [ordered]@{
        file      = Split-Path -Leaf $Bundle
        sizeBytes = $bundleSize
        sha256    = $bundleHash
        timestamp = $bundleManifest.timestamp
    }
    toolchain     = [ordered]@{
        patcherVersion = $patcherMatch.Groups[1].Value
        managerFloor   = $floorMatch.Groups[1].Value
    }
    source        = $bundleManifest.source
    extension     = [ordered]@{ dexPayloads = $extensionPayloads }
    targets       = $targets.ToArray()
}

# The receipt is checked before it is written. A file that fails the gate it exists to pass is
# worse than no file, because the next reader has to work out which half to believe.
$approved = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
$check = Test-ReleaseReceipt -Receipt ($receipt | ConvertTo-Json -Depth 12 | ConvertFrom-Json) `
    -ExpectedVersion $releaseVersion -ExpectedPatchNames $patchNames `
    -ExpectedPatcherVersion $patcherMatch.Groups[1].Value `
    -ExpectedManagerFloor $floorMatch.Groups[1].Value `
    -ExpectedPackageName $expectedTarget.PackageName `
    -ExpectedPackageVersions @($expectedTarget.PackageVersions) -BundlePath $Bundle `
    -ApprovedManifestDelta $approved
if (-not $check.Valid) { throw "The receipt this run produced does not pass validation: $($check.Reason)" }

$receipt | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $OutputPath -Encoding UTF8
Write-Host "[receipt] wrote $OutputPath"
exit 0
