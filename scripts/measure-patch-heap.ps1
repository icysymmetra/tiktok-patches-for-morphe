<#
.SYNOPSIS
    Find the smallest heap the patcher finishes patching in.

.DESCRIPTION
    Morphe Manager patches with a memory limit the user can set. This runs the desktop CLI at
    a given -Xmx and accepts a case only when the process, result report and saved APK all pass
    the same gates as the full verification script. An observed OutOfMemoryError is retained as
    a useful measurement, while a partial or contradictory result is reported as invalid.

    Cases are "<what>:<Xmx>", where <what> is "settings" (the Settings patch alone) or "all"
    (every patch in the list).

    HUSHFEED_WORKDIR holds the desktop CLI jar and takes the scratch output. HUSHFEED_APK must
    point at the TikTok build README.md records; there is no default, because a heap figure only
    means something against a named APK. HUSHFEED_JAVA and HUSHFEED_DESKTOP_JAR are optional.

.EXAMPLE
    $env:HUSHFEED_WORKDIR = "C:\scratch"
    $env:HUSHFEED_JAVA = "C:\jdk-21\bin\java.exe"
    $env:HUSHFEED_APK = "C:\fixtures\com.zhiliaoapp.musically_47.1.4.apk"
    scripts/measure-patch-heap.ps1 settings:640m settings:768m all:768m
#>
[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Cases
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

$work = $env:HUSHFEED_WORKDIR
if (-not $work) { throw 'Set HUSHFEED_WORKDIR to a directory holding morphe-desktop.jar and the fixture APK.' }
New-Item -ItemType Directory -Force -Path $work | Out-Null
$workRoot = (Resolve-Path -LiteralPath $work).Path
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
$java = Resolve-Java

# The CLI ships under its version, morphe-desktop-1.15.0-all.jar and so on, so the most recently
# written jar matching the name is taken rather than one exact filename that goes stale on every
# release. Not the name: sorting those as text puts 1.9.0 above 1.15.0.
$jar = if ($env:HUSHFEED_DESKTOP_JAR) { $env:HUSHFEED_DESKTOP_JAR } else {
    $found = @(Get-ChildItem -LiteralPath $workRoot -Filter 'morphe-desktop*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending)
    if ($found.Count -eq 0) {
        throw ("No morphe-desktop*.jar in $workRoot. Put the desktop CLI there, or set " +
            'HUSHFEED_DESKTOP_JAR to it.')
    }
    $found[0].FullName
}

# There is no default APK. The one to measure against is the build README.md records with its
# checksum, which lives outside the repo, and quietly patching some other file would report a
# heap figure for a target nobody asked about. Between releases that is the "Next release" line,
# since the install steps keep naming the build the published release patches.
$apk = $env:HUSHFEED_APK
if (-not $apk) {
    throw ('Set HUSHFEED_APK to the TikTok build the patches declare: README.md records it under ' +
        '"Supported target", on the "Next release" line when there is one. ' +
        'The heap a patch needs depends on the APK, so there is no sensible default.')
}
$version = Get-BundleVersion -Root $root
$bundle = Get-Item -LiteralPath (Get-ReleaseBundlePath -Root $root -Version $version) -ErrorAction SilentlyContinue
if ($null -eq $bundle) { throw "No bundle for version $version. Run :patches:buildAndroid first." }
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Desktop CLI jar not found: $jar" }
if (-not (Test-Path -LiteralPath $apk -PathType Leaf)) { throw "APK not found: $apk" }

try {
    $catalog = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
    $all = @($catalog.patches | ForEach-Object { $_.name })
} catch {
    throw "Could not read patches-list.json: $($_.Exception.Message)"
}
$target = Get-PatchTarget -PatchList $catalog
if ($all.Count -eq 0) { throw 'No patches listed in patches-list.json.' }
if (@($Cases).Count -eq 0) { throw 'Provide at least one case such as settings:640m.' }

$invalidCases = 0
foreach ($case in @($Cases)) {
    $parts = $case -split ':', 2
    if ($parts.Count -ne 2 -or $parts[0] -notin @('settings', 'all') -or
        $parts[1] -notmatch '^\d+[kmg]$') {
        Write-Warning "[$case] invalid case format; use settings:640m or all:768m"
        $invalidCases++
        continue
    }
    $which = $parts[0]
    $mx = $parts[1]
    $expectedNames = if ($which -eq 'settings') { @('Settings') } else { $all }
    $dependencyNames = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $expectedNames)
    $runId = [guid]::NewGuid().ToString('N')
    $runDir = Join-Path $workRoot "heap-$runId"
    New-Item -ItemType Directory -Force -Path $runDir | Out-Null
    $out = Resolve-WithinRoot -Path (Join-Path $runDir 'output.apk') -Root $workRoot
    $temp = Resolve-WithinRoot -Path (Join-Path $runDir 'tmp') -Root $workRoot
    $result = Resolve-WithinRoot -Path (Join-Path $workRoot "heap-$which-$mx-$runId-result.json") -Root $workRoot
    $enable = if ($which -eq 'settings') { @('-e', 'Settings') } else {
        $argsForAll = @()
        foreach ($name in $all) { $argsForAll += '-e'; $argsForAll += $name }
        $argsForAll
    }
    $arguments = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $bundle.FullName,
        '-o', $out, '-t', $temp, '-r', $result) + $enable + @($apk)
    try {
        $log = @(& $java "-Xmx$mx" '-jar' $jar @arguments 2>&1)
        $cliExitCode = $LASTEXITCODE
        $outOfMemory = @($log | Where-Object { [string]$_ -match 'OutOfMemoryError|GC overhead' }).Count -gt 0
        $report = $null
        if (Test-Path -LiteralPath $result -PathType Leaf) {
            try { $report = Get-Content -LiteralPath $result -Raw | ConvertFrom-Json }
            catch { Write-Warning "[$which @ -Xmx$mx] could not parse result JSON: $($_.Exception.Message)" }
        }
        $validation = Test-PatchingReport -Report $report `
            -ExpectedNames $expectedNames -AllowedDependencyNames $dependencyNames `
            -OutputPath $out `
            -ExpectedPackageName $target.PackageName -ExpectedPackageVersion (Get-DeclaredReportVersion -Report $report -Target $target)
        if ($outOfMemory) {
            $verdict = 'OUT OF MEMORY'
            $invalidCases++
        } elseif ($cliExitCode -eq 0 -and $validation.Valid) {
            $verdict = 'ok'
        } else {
            $verdict = 'INVALID'
            $invalidCases++
        }
        Write-Host "[$which @ -Xmx$mx] $verdict (CLI $cliExitCode, report $($validation.Reason))"
        Write-Host "[$which @ -Xmx$mx] result file: $result"
    } catch {
        Write-Warning "[$which @ -Xmx$mx] failed to run CLI: $($_.Exception.Message)"
        $invalidCases++
    } finally {
        Remove-GeneratedPath -Path $runDir -Root $workRoot
    }
}

exit $invalidCases
