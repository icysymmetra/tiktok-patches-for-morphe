<#
.SYNOPSIS
    Run the aggregate storage scanner's JVM metadata contracts without Android or a device.
#>
[CmdletBinding()]
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot),
    [string]$Java,
    [ValidateSet('all', 'parent-replacement', 'parent-replacement-restored')][string]$Case = 'all'
)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
$Java = Resolve-Java -Explicit $Java -Minimum 11
$javaDirectory = Split-Path -Parent (Get-Command $Java).Source
$compiler = Join-Path $javaDirectory $(if ($env:OS -eq 'Windows_NT') { 'javac.exe' } else { 'javac' })
$temporary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Resolve-WithinRoot -Root $temporary -Path (Join-Path $temporary ('hushfeed-storage-test-' + [Guid]::NewGuid().ToString('N')))
try {
    New-Item -ItemType Directory -Path $fixture | Out-Null
    $sources = @(
        (Join-Path $Root 'tools/verification-probe/src/app/hushfeed/verification/StorageScan.java'),
        (Join-Path $Root 'tools/verification-probe/tests/app/hushfeed/verification/StorageScanContract.java')
    )
    $previousPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $compiler --release 11 -encoding UTF-8 -d $fixture @sources 2>&1 |
            ForEach-Object { Write-Host "$_" }
        $compileStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($compileStatus -ne 0) { throw 'Storage scanner contract compilation failed.' }
    $caseArguments = @()
    if ($Case -ne 'all') { $caseArguments = @($Case) }
    try {
        # JVM startup diagnostics are stderr even when every contract succeeds. Keep them
        # readable without forwarding native ErrorRecords into a caller's Stop pipeline.
        $ErrorActionPreference = 'Continue'
        & $Java -cp $fixture app.hushfeed.verification.StorageScanContract @caseArguments 2>&1 |
            ForEach-Object { Write-Host "$_" }
        $testStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousPreference }
    if ($testStatus -ne 0) { throw 'Storage scanner contracts did not pass.' }
} finally {
    Remove-Item -LiteralPath $fixture -Recurse -Force -ErrorAction SilentlyContinue
}
