<#
.SYNOPSIS
    Time each patch of one desktop CLI run and note the heap it peaked at.

.DESCRIPTION
    The CLI prints "Applied: <name>" (or a failure) as each patch finishes, in the order it runs
    them, and a patch's fingerprints are matched while it runs. This stamps every line the CLI
    prints with the wall clock and asks the JVM for a GC log with the same clock, so a patch's
    time is the gap since the line before it and its heap is the fullest the heap got in that
    gap (the size before each collection, which is where the heap peaks). The first patch also
    carries the shared patches it pulls in, and "Executing patches" marks where patching starts:
    everything before it is loading, decoding and filtering, and everything after the last
    patch is writing the APK.

    The table goes to <OutDir>\patch-times.md (slowest first, with the total and the phases),
    the raw stamped log to cli.log and the GC log to gc.log. Nothing is written to the repo.

    The desktop CLI is found the way patch-for-device.ps1 finds it: -DesktopJar,
    HUSHFEED_DESKTOP_JAR, HUSHFEED_WORKDIR, then build/morphe-tools. HUSHFEED_JAVA is optional.

.EXAMPLE
    scripts/time-patches.ps1 -Apk C:\fixtures\tiktok-47.1.4.apk -OutDir C:\scratch\times -DesktopJar C:\tools\morphe-desktop-1.18.0-all.jar
#>
# The CLI logs to stderr, and Windows PowerShell 5.1 turns a redirected stderr line into an error.
#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Bundle,
    [string]$DesktopJar,
    [string]$Xmx = '6g'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
$java = Resolve-Java

$jar = Resolve-DesktopCli -Explicit $DesktopJar -Root $root -Required
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }
if (-not $Bundle) {
    $Bundle = Get-ReleaseBundlePath -Root $root -Version (Get-BundleVersion -Root $root)
}
if (-not (Test-Path -LiteralPath $Bundle -PathType Leaf)) { throw "No bundle at $Bundle. Run :patches:buildAndroid first." }
# The CLI runs from the output folder, so a path relative to where this was started has to be made whole first.
$Apk = (Resolve-Path -LiteralPath $Apk).Path
$Bundle = (Resolve-Path -LiteralPath $Bundle).Path
$jar = (Resolve-Path -LiteralPath $jar).Path
$names = @((Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json).patches | ForEach-Object { $_.name })
if ($names.Count -eq 0) { throw 'No patches listed in patches-list.json.' }

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$OutDir = (Resolve-Path -LiteralPath $OutDir).Path
$gcLog = Join-Path $OutDir 'gc.log'
$cliLog = Join-Path $OutDir 'cli.log'
$temp = Join-Path $OutDir 'tmp'
$argv = @('patch', '--exclusive', '--continue-on-error', '--unsigned', '-p', $Bundle,
    '-o', (Join-Path $OutDir 'output.apk'), '-t', $temp, '-r', (Join-Path $OutDir 'result.json'))
foreach ($name in $names) { $argv += '-e'; $argv += $name }
$argv += $Apk
# A response file keeps 98 names off the command line, whose length Windows caps.
$bs = [string][char]92
$argFile = Join-Path $OutDir 'cli.args'
[IO.File]::WriteAllLines($argFile, @($argv | ForEach-Object { '"' + ([string]$_).Replace($bs, $bs + $bs).Replace('"', $bs + '"') + '"' }),
    (New-Object Text.UTF8Encoding $false))

# The GC log takes the wall clock in milliseconds since the epoch ("timemillis"), the same clock
# the stamps below use, so the two line up without guessing the JVM's start-up delay. -Xlog splits
# its option on colons, so a drive letter can't go in file=; java runs in $OutDir and names the
# log relative to it.
$javaArgs = @("-Xmx$Xmx", '-Xlog:gc:file=gc.log:timemillis', '-jar', $jar, "@$argFile")
$stamped = New-Object System.Collections.Generic.List[object]
$start = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
Push-Location -LiteralPath $OutDir
try {
    & $java @javaArgs 2>&1 | ForEach-Object {
        $line = [string]$_
        $stamped.Add([pscustomobject]@{ At = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); Line = $line })
    }
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
$end = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
[IO.File]::WriteAllLines($cliLog, @($stamped | ForEach-Object { '{0} {1}' -f ($_.At - $start), $_.Line }), (New-Object Text.UTF8Encoding $false))
if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
if (-not (Test-Path -LiteralPath $gcLog -PathType Leaf)) { throw "The JVM wrote no GC log (exit $exitCode). See $cliLog." }

$collections = Read-GcHeapLog -Lines @(Get-Content -LiteralPath $gcLog)
$times = Get-PatchTimes -Stamped $stamped.ToArray() -Collections $collections
if ($null -eq $times) { throw "The CLI never reached its patches (exit $exitCode). See $cliLog." }
$rows = $times.Rows
$lastPatch = $times.LastPatchAt

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add("Patch times for $(Split-Path -Leaf $Apk), bundle $(Split-Path -Leaf $Bundle), -Xmx$Xmx, CLI exit $exitCode.")
$lines.Add('')
$lines.Add(('Whole run {0:N1} s: loading and decoding {1:N1} s, patches {2:N1} s, writing {3:N1} s. Heap peak {4} MB.' -f
    (($end - $start) / 1000), (($times.ExecutingAt - $start) / 1000), (($lastPatch - $times.ExecutingAt) / 1000), (($end - $lastPatch) / 1000),
    (($collections | Measure-Object -Property Before -Maximum).Maximum)))
$lines.Add('')
$lines.Add('| Patch | Result | Seconds | Heap peak (MB) |')
$lines.Add('|---|---|---:|---:|')
foreach ($row in @($rows | Sort-Object -Property Ms -Descending)) {
    $peak = if ($null -eq $row.PeakMb) { 'no GC' } else { [string]$row.PeakMb }
    $lines.Add(('| {0} | {1} | {2:N2} | {3} |' -f $row.Patch, $row.Result, ($row.Ms / 1000), $peak))
}
$table = Join-Path $OutDir 'patch-times.md'
[IO.File]::WriteAllLines($table, $lines, (New-Object Text.UTF8Encoding $false))
Write-Host "[times] $($rows.Count) of $($names.Count) patches timed; table in $table"
if ($rows.Count -ne $names.Count) { Write-Warning "Only $($rows.Count) of $($names.Count) patches printed a result line. See $cliLog." }
exit $exitCode
