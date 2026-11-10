<# Exercise the real pre-push collector with controlled process jobs. #>
[CmdletBinding()]
param([string]$Root, [string]$PrePushScript)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if (-not $PrePushScript) { $PrePushScript = Join-Path $Root 'scripts/pre-push.ps1' }
. (Join-Path $Root 'scripts/patch-target.ps1')
$parseErrors = $null
$tokens = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($PrePushScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
foreach ($name in @('Write-Step', 'Invoke-WithoutGitEnvironment', 'Invoke-FixturePatching')) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false)
    if (-not $definition) { throw "Missing collector dependency $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

function Assert-JobContract([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

$contractRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-job-contract-' + [guid]::NewGuid().ToString('N'))
$owned = New-Object 'System.Collections.Generic.List[object]'
$received = New-Object 'System.Collections.Generic.List[object]'
$savedSerial = $env:HUSHFEED_GATE_SERIAL
$env:HUSHFEED_GATE_SERIAL = $null

# Keep completion inside Receive-Job's initial backlog drain. AutoRemoveJob has two removal
# paths in this window on both supported PowerShell hosts, producing ReceiveJobAutoRemovalError.
function Start-Job {
    [CmdletBinding()]
    param([object[]]$ArgumentList, [scriptblock]$ScriptBlock)
    if ($script:scenario -eq 'startup-failure' -and $owned.Count -eq 1) { throw 'controlled startup failure' }
    $version = Split-Path -Leaf $ArgumentList[3]
    $delay = if ($version -eq 'slow') { 100 } else { 0 }
    $code = if ($script:scenario -eq 'fixture-failure' -and $version -eq 'fast') { 1 } else { 0 }
    $job = Microsoft.PowerShell.Core\Start-Job -ArgumentList $contractRoot, $version, $delay, $code -ScriptBlock {
        param($Work, $Version, $Delay, $Code)
        Set-Content -LiteralPath (Join-Path $Work "$Version.pid") -Value $PID -Encoding ASCII
        1..64 | ForEach-Object { "$Version row-$_" }
        [pscustomobject]@{ ExitCode = $Code }
        Set-Content -LiteralPath (Join-Path $Work "$Version.ready") -Value 'ready' -Encoding ASCII
        $clock = [Diagnostics.Stopwatch]::StartNew()
        while (-not (Test-Path -LiteralPath (Join-Path $Work "$Version.release"))) {
            if ($clock.Elapsed.TotalSeconds -gt 20) { throw 'Collector did not release its job.' }
            Start-Sleep -Milliseconds 10
        }
        Start-Sleep -Milliseconds $Delay
    }
    $owned.Add([pscustomobject]@{ Version = $version; Job = $job })
    $job
}

function Receive-Job {
    [CmdletBinding()]
    param([object]$Job, [switch]$Wait, [switch]$AutoRemoveJob)
    $version = ($owned | Where-Object { $_.Job.InstanceId -eq $Job.InstanceId }).Version
    if ($script:scenario -eq 'receive-failure' -and $version -eq 'fast') { throw 'controlled receive failure' }
    $clock = [Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Path -LiteralPath (Join-Path $contractRoot "$version.ready"))) {
        if ($clock.Elapsed.TotalSeconds -gt 15) { throw 'Job did not create its output backlog.' }
        Start-Sleep -Milliseconds 10
    }
    $count = 0
    $receiveErrors = @()
    Microsoft.PowerShell.Core\Receive-Job @PSBoundParameters -ErrorVariable receiveErrors | ForEach-Object {
        $received.Add([pscustomobject]@{ Version = $version; Value = $_ })
        if (++$count -eq 1) {
            Set-Content -LiteralPath (Join-Path $contractRoot "$version.release") -Value 'release' -Encoding ASCII
            # Windows PowerShell may defer its State update until this output consumer returns.
            # Hold the initial drain without waiting on that same event delivery.
            Start-Sleep -Milliseconds 800
        }
        $_
    }
    if ($receiveErrors.Count -gt 0) { throw "Receive error: $($receiveErrors[0].FullyQualifiedErrorId)" }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $contractRoot 'scripts'), (Join-Path $contractRoot 'patches/build/release') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $contractRoot 'scripts/verify-all-patches.ps1') -Value '# collector fixture' -Encoding ASCII
    Set-Content -LiteralPath (Join-Path $contractRoot 'patches/build/release/patches-test.mpp') -Value 'bundle' -Encoding ASCII
    $control = Microsoft.PowerShell.Core\Start-Job -ScriptBlock { Start-Sleep -Seconds 90; 'unrelated' }
    foreach ($script:scenario in @('mixed', 'reverse', 'mixed', 'fixture-failure', 'receive-failure', 'startup-failure')) {
        $owned.Clear()
        $received.Clear()
        Get-ChildItem -LiteralPath $contractRoot -File | Remove-Item -Force
        $versions = if ($scenario -eq 'reverse') { @('fast', 'slow') } else { @('slow', 'fast') }
        $failure = $null
        try {
            $fixtures = @($versions | ForEach-Object { [pscustomobject]@{ Version = $_; Apk = "$_.apk" } })
            Invoke-FixturePatching -GateRoot $contractRoot -Fixtures $fixtures 6> (Join-Path $contractRoot 'collector.log')
        } catch { $failure = $_ }
        if ($scenario -in @('mixed', 'reverse')) {
            $detail = Get-Content -LiteralPath (Join-Path $contractRoot 'collector.log') | Where-Object { $_ -like '*Could not collect*' }
            Assert-JobContract ($null -eq $failure) "Passing $scenario batch failed: $failure $detail"
        } elseif ($scenario -eq 'startup-failure') {
            Assert-JobContract ($failure -like '*controlled startup failure*') "Startup failure was lost: $failure"
        } else {
            Assert-JobContract ($failure -like '*did not apply to TikTok fast*') "Failed fixture passed or lost its identity: $failure"
        }
        foreach ($run in $owned) {
            Assert-JobContract (@(Get-Job | Where-Object { $_.InstanceId -eq $run.Job.InstanceId }).Count -eq 0) `
                "Owned job survived $scenario cleanup."
            if ($scenario -eq 'startup-failure' -or ($scenario -eq 'receive-failure' -and $run.Version -eq 'fast')) { continue }
            $values = @($received | Where-Object { $_.Version -eq $run.Version } | ForEach-Object { $_.Value })
            $rows = @($values | Where-Object { $_ -is [string] })
            Assert-JobContract ($values.Count -eq 65 -and @($rows | Select-Object -Unique).Count -eq 64) `
                "Fixture $($run.Version) output was lost or collected twice in $scenario."
            Assert-JobContract (@($values | Where-Object { $_.PSObject.Properties['ExitCode'] }).Count -eq 1) `
                "Fixture $($run.Version) result was lost or collected twice."
        }
        foreach ($pidFile in @(Get-ChildItem -LiteralPath $contractRoot -Filter '*.pid' -File)) {
            $jobPid = [int](Get-Content -LiteralPath $pidFile.FullName -Raw)
            $clock = [Diagnostics.Stopwatch]::StartNew()
            while (Get-Process -Id $jobPid -ErrorAction SilentlyContinue) {
                if ($clock.Elapsed.TotalSeconds -gt 10) { throw "Owned child process survived $scenario cleanup." }
                Start-Sleep -Milliseconds 50
            }
        }
        Assert-JobContract (@(Get-Job | Where-Object { $_.InstanceId -eq $control.InstanceId }).Count -eq 1) `
            'Collector removed an unrelated job.'
    }
} finally {
    foreach ($job in @($owned | ForEach-Object { $_.Job }) + @($control)) {
        if ($null -eq $job) { continue }
        if (@(Get-Job | Where-Object { $_.InstanceId -eq $job.InstanceId }).Count -eq 0) { continue }
        if ($job.State -notin @('Completed', 'Failed', 'Stopped')) { Stop-Job -Job $job -ErrorAction Stop }
        Remove-Job -Job $job -ErrorAction Stop
    }
    $env:HUSHFEED_GATE_SERIAL = $savedSerial
    $resolvedWork = [IO.Path]::GetFullPath($contractRoot)
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if (-not $resolvedWork.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe contract cleanup path.' }
    Remove-Item -LiteralPath $resolvedWork -Recurse -Force
}
Write-Host '[scripts] fixture-job ownership and completion contracts passed'
