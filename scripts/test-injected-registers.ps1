<#
.SYNOPSIS
    Exercise verifier tally equality and removed-method or removed-DEX refusal.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [string]$Java,
    [string]$DesktopJar,
    [string]$D8
)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [string]$Description)
    $output = @(& $Program @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0) {
        throw "$Description exited $LASTEXITCODE.`n$($output -join "`n")"
    }
}

function New-CompiledDex {
    param([string]$Name, [System.Collections.IDictionary]$Sources)
    $sourceRoot = Join-Path $caseRoot "$Name-src"
    $classes = Join-Path $caseRoot "$Name-classes"
    $dex = Join-Path $caseRoot "$Name-dex"
    New-Item -ItemType Directory -Force -Path $sourceRoot, $classes, $dex | Out-Null
    $sourcePaths = @()
    foreach ($entry in $Sources.GetEnumerator()) {
        $path = Join-Path $sourceRoot ($entry.Key -replace '/', [System.IO.Path]::DirectorySeparatorChar)
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        [System.IO.File]::WriteAllText($path, $entry.Value, [System.Text.UTF8Encoding]::new($false))
        $sourcePaths += $path
    }
    Invoke-Checked -Program $javac -Arguments (@('-encoding', 'UTF-8', '-d', $classes) + $sourcePaths) `
        -Description "javac for $Name"
    $jarPath = Join-Path $caseRoot "$Name.jar"
    Invoke-Checked -Program $jar -Arguments @('--create', '--file', $jarPath, '-C', $classes, '.') `
        -Description "jar for $Name"
    Invoke-Checked -Program $D8 -Arguments @('--min-api', '23', '--output', $dex, $jarPath) `
        -Description "d8 for $Name"
    return Join-Path $dex 'classes.dex'
}

function New-DexApk {
    param([string]$Name, [System.Collections.IDictionary]$Entries)
    $path = Join-Path $caseRoot "$Name.apk"
    $archive = [System.IO.Compression.ZipFile]::Open(
        $path, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entry in $Entries.GetEnumerator()) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $archive, $entry.Value, $entry.Key,
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally {
        $archive.Dispose()
    }
    return $path
}

function Invoke-DexDiff {
    param([string]$Clean, [string]$Patched, [string]$Allowlist, [string]$Name)
    $report = Join-Path $caseRoot "$Name-report.txt"
    $global:LASTEXITCODE = 0
    $output = @(& $Java '-Xmx1g' '-cp' $DesktopJar (Join-Path $PSScriptRoot 'DexDiff.java') `
        $Clean $Patched $report $Allowlist 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output; Report = $report }
}

$equal = Compare-VerifierTallies -Clean @{ lock = 2; class = 1 } -Patched @{ class = 1; lock = 2 }
Assert-True $equal.Valid 'Equal verifier message multisets were rejected.'
$extra = Compare-VerifierTallies -Clean @{ lock = 2 } -Patched @{ lock = 2; verify = 1 }
Assert-True (-not $extra.Valid -and $extra.Deltas.Count -eq 1 -and `
    $extra.Deltas[0].Kind -eq 'extra') 'An extra verifier message was accepted.'
$missing = Compare-VerifierTallies -Clean @{ lock = 2; verify = 1 } -Patched @{ lock = 2 }
Assert-True (-not $missing.Valid -and $missing.Deltas.Count -eq 1 -and `
    $missing.Deltas[0].Kind -eq 'missing') 'A missing verifier message was accepted.'
$reduced = Compare-VerifierTallies -Clean @{ lock = 3 } -Patched @{ lock = 1 }
Assert-True (-not $reduced.Valid -and $reduced.Deltas[0].Difference -eq 2 -and `
    $reduced.Deltas[0].Kind -eq 'missing') 'A reduced verifier message count was accepted.'
$caseChanged = Compare-VerifierTallies -Clean @{ VerifyError = 1 } -Patched @{ verifyerror = 1 }
Assert-True (-not $caseChanged.Valid -and $caseChanged.Deltas.Count -eq 2) `
    'A case-changed verifier message was treated as the same message.'

$verifierText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-injected-registers.ps1') -Raw
Assert-True ($verifierText -match 'injected-register-contracts\.ps1' -and `
    $verifierText -match 'Compare-VerifierTallies') `
    'The device verifier does not use the exact tally comparison contract.'
$prePushText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pre-push.ps1') -Raw
Assert-True ($prePushText -match 'scripts/test-injected-registers\.ps1') `
    'The push gate does not run the injected-register fixture test.'

$Java = Resolve-Java -Explicit $Java
$DesktopJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root -Required
$D8 = Resolve-D8 -Explicit $D8 -Root $Root
$javaBin = Split-Path -Parent $Java
$javac = Join-Path $javaBin 'javac.exe'
$jar = Join-Path $javaBin 'jar.exe'
foreach ($tool in @($javac, $jar, $D8)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required tool not found: $tool" }
}

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase `
    ("hushfeed-register-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + `
    [System.IO.Path]::DirectorySeparatorChar
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $cleanHost = New-CompiledDex -Name 'clean-host' -Sources ([ordered]@{
        'fixture/Host.java' = 'package fixture; public final class Host { public static int changed() { return 1; } public static int removed() { return 2; } }'
    })
    $patchedMissingMethod = New-CompiledDex -Name 'patched-missing-method' -Sources ([ordered]@{
        'fixture/Host.java' = 'package fixture; public final class Host { public static int changed() { return 3; } }'
        'app/morphe/Extension.java' = 'package app.morphe; public final class Extension { public static int added() { return 4; } }'
    })
    $secondary = New-CompiledDex -Name 'clean-secondary' -Sources ([ordered]@{
        'fixture/Secondary.java' = 'package fixture; public final class Secondary { public static int kept() { return 5; } }'
    })
    $patchedWithoutSecondDex = New-CompiledDex -Name 'patched-without-second-dex' -Sources ([ordered]@{
        'fixture/Host.java' = 'package fixture; public final class Host { public static int changed() { return 3; } public static int removed() { return 2; } }'
        'fixture/Secondary.java' = 'package fixture; public final class Secondary { public static int kept() { return 5; } }'
        'app/morphe/Extension.java' = 'package app.morphe; public final class Extension { public static int added() { return 4; } }'
    })

    $methodCleanApk = New-DexApk -Name 'method-clean' -Entries ([ordered]@{ 'classes.dex' = $cleanHost })
    $methodPatchedApk = New-DexApk -Name 'method-patched' -Entries ([ordered]@{ 'classes.dex' = $patchedMissingMethod })
    $dexCleanApk = New-DexApk -Name 'dex-clean' -Entries ([ordered]@{
        'classes.dex' = $cleanHost
        'classes2.dex' = $secondary
    })
    $dexPatchedApk = New-DexApk -Name 'dex-patched' -Entries ([ordered]@{
        'classes.dex' = $patchedWithoutSecondDex
    })
    $emptyAllowlist = Join-Path $PSScriptRoot 'injected-register-removal-allowlist.txt'
    $methodResult = Invoke-DexDiff -Clean $methodCleanApk -Patched $methodPatchedApk `
        -Allowlist $emptyAllowlist -Name 'removed-method'
    $dexResult = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $emptyAllowlist -Name 'removed-dex'

    $failures = @()
    if ($methodResult.ExitCode -eq 0) { $failures += 'a removed host method was accepted' }
    if ($dexResult.ExitCode -eq 0) { $failures += 'a removed DEX was accepted' }
    if ($failures.Count -ne 0) { throw ($failures -join '; ') }
    Assert-True (($methodResult.Output -join "`n") -match 'Lfixture/Host;->removed\(\)I') `
        'The removed-method failure did not name the method.'
    Assert-True (($dexResult.Output -join "`n") -match 'classes2\.dex') `
        'The removed-DEX failure did not name the DEX.'

    $methodAllowlist = Join-Path $caseRoot 'method-allowlist.txt'
    [System.IO.File]::WriteAllText($methodAllowlist, "method Lfixture/Host;->removed()I`n")
    $methodAllowed = Invoke-DexDiff -Clean $methodCleanApk -Patched $methodPatchedApk `
        -Allowlist $methodAllowlist -Name 'allowed-method'
    Assert-True ($methodAllowed.ExitCode -eq 0) 'An exact reviewed method removal was rejected.'

    $dexAllowlist = Join-Path $caseRoot 'dex-allowlist.txt'
    [System.IO.File]::WriteAllText($dexAllowlist, "dex classes2.dex`n")
    $dexAllowed = Invoke-DexDiff -Clean $dexCleanApk -Patched $dexPatchedApk `
        -Allowlist $dexAllowlist -Name 'allowed-dex'
    Assert-True ($dexAllowed.ExitCode -eq 0) 'An exact reviewed DEX removal was rejected.'

    $staleAllowlist = Join-Path $caseRoot 'stale-allowlist.txt'
    [System.IO.File]::WriteAllText($staleAllowlist, "method Lfixture/Host;->notRemoved()I`n")
    $staleResult = Invoke-DexDiff -Clean $methodCleanApk -Patched $methodPatchedApk `
        -Allowlist $staleAllowlist -Name 'stale-allowlist'
    Assert-True ($staleResult.ExitCode -ne 0) 'A stale removal allowlist entry was accepted.'
    Assert-True (($staleResult.Output -join "`n") -match 'notRemoved') `
        'The stale allowlist failure did not name the stale entry.'
    Assert-True ((Get-Content -LiteralPath $staleResult.Report -Raw) -match 'notRemoved') `
        'The stale allowlist report did not name the stale entry.'
} finally {
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and `
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] injected-register comparison contracts passed'
