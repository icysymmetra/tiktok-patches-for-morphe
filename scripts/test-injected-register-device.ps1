<#
.SYNOPSIS
    Exercise ADB failure cleanup for injected-register verification.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'injected-register-device.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

# Every fake run holds a real lease on SERIAL in a throwaway pool, the way the script does.
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$leaseFixture = Join-Path $temporaryRoot ('hushfeed-register-lease-test-' + [guid]::NewGuid().ToString('N'))
$leasePool = Join-Path $leaseFixture 'pool'
$leaseContexts = [System.Collections.Generic.List[object]]::new()
function New-FixtureLease {
    param([string]$Serial = 'SERIAL')
    $context = New-DeviceLease -LeaseDir $leasePool -Serial $Serial -Project 'fixture-project' `
        -ChatIdentity 'fixture-chat' -Purpose 'fake verifier' -LeaseMinutes 5
    $leaseContexts.Add($context)
    return $context
}
try {
$lease = New-FixtureLease

function New-FakeAdb {
    param(
        [string]$FailureStage,
        [int]$CleanupFailureNumber = 0,
        [int]$CleanupThrowNumber = 0
    )

    $state = [pscustomobject]@{
        Calls = [System.Collections.Generic.List[string]]::new()
        RemotePaths = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::Ordinal)
        CleanupCalls = 0
        Marker = $null
    }
    $invoker = {
        param([string]$Executable, [string[]]$Arguments)
        $line = $Arguments -join ' '
        [void]$state.Calls.Add($line)
        $exitCode = 0
        $output = @()
        $operation = $Arguments[2]

        if ($operation -eq 'push') {
            [void]$state.RemotePaths.Add($Arguments[4])
            if ($FailureStage -eq 'push') { $exitCode = 11 }
        } elseif ($operation -eq 'shell' -and $Arguments[3] -like '*&& mkdir -p*') {
            $directory = ($Arguments[3] -split 'mkdir -p ', 2)[1]
            [void]$state.RemotePaths.Add($directory)
            if ($FailureStage -eq 'setup') { $exitCode = 12 }
        } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-c') {
            throw 'The verifier cleared the log buffer of a shared phone.'
        } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'log -t HushfeedVerify *') {
            $state.Marker = $Arguments[3].Substring('log -t HushfeedVerify '.Length)
            if ($FailureStage -eq 'mark') { $exitCode = 13 }
        } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'dex2oat64*') {
            $output = if ($FailureStage -eq 'dex2oat') { @('exit=14') } else { @('exit=0') }
        } elseif ($operation -eq 'logcat' -and $Arguments[3] -eq '-d') {
            if ($FailureStage -eq 'log-read-throw') {
                throw 'fake ADB threw while reading logcat'
            } elseif ($FailureStage -eq 'log-read') {
                $exitCode = 15
            } else {
                # Another session's run, marker included, sits before this one and isn't counted.
                $before = @(
                    "I HushfeedVerify: hushfeed-verify-case-$('0' * 32)",
                    'I dex2oat64: Verification error in Lfixture/Host;',
                    'W dex2oat64: Rejecting class Lfixture/Earlier;'
                )
                $marked = if ($FailureStage -eq 'rotated') { @() } else { @("I HushfeedVerify: $($state.Marker)") }
                $output = $before + $marked + @(
                    'I dex2oat64: Verification error in Lfixture/Host;',
                    'I dex2oat64: Verification error in Lfixture/Host;',
                    'W dex2oat: VerifyError in Lfixture/Other;'
                )
            }
        } elseif ($operation -eq 'shell' -and $Arguments[3] -like 'rm -rf *') {
            $state.CleanupCalls++
            $path = $Arguments[3].Substring('rm -rf '.Length)
            if ($CleanupThrowNumber -eq $state.CleanupCalls) {
                throw "fake ADB threw while removing $path"
            } elseif ($CleanupFailureNumber -eq $state.CleanupCalls) {
                $exitCode = 91
            } else {
                [void]$state.RemotePaths.Remove($path)
            }
        } else {
            throw "Unexpected fake ADB call through ${Executable}: $line"
        }
        [pscustomobject]@{ ExitCode = $exitCode; Output = $output }
    }.GetNewClosure()
    [pscustomobject]@{ State = $state; Invoker = $invoker }
}

function Assert-BothCleanupCalls {
    param($State, [string]$Context)
    $expected = @(
        '-s SERIAL shell rm -rf /data/local/tmp/hushfeed-verify-case',
        '-s SERIAL shell rm -rf /data/local/tmp/hushfeed-verify-case.apk'
    )
    foreach ($call in $expected) {
        Assert-True (@($State.Calls | Where-Object { $_ -eq $call }).Count -eq 1) `
            "$Context did not attempt cleanup exactly once: $call"
    }
}

$failures = [ordered]@{
    push = 'Could not push case to SERIAL (ADB exit 11).'
    setup = 'Could not prepare the verifier output directory for case on SERIAL (ADB exit 12).'
    mark = 'Could not mark logcat on SERIAL before verifying case (ADB exit 13).'
    dex2oat = 'dex2oat on case exited 14.'
    'log-read' = 'Could not read logcat on SERIAL after verifying case (ADB exit 15).'
    rotated = "The log on SERIAL no longer holds the start of the case run, so its verifier messages can't be counted. Retry when the phone is quieter."
}
foreach ($stage in $failures.Keys) {
    $fake = New-FakeAdb -FailureStage $stage
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
            -Local 'C:/fixture.apk' -Label 'case' -Lease $lease -AdbInvoker $fake.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught) "$stage failure was accepted."
    Assert-True ($caught.Exception.Message -eq $failures[$stage]) `
        "$stage returned a different failure: $($caught.Exception.Message)"
    Assert-BothCleanupCalls -State $fake.State -Context $stage
    Assert-True ($fake.State.RemotePaths.Count -eq 0) "$stage left fake remote files behind."
}

$thrownFailure = New-FakeAdb -FailureStage 'log-read-throw'
$caught = $null
try {
    [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local 'C:/fixture.apk' -Label 'case' -Lease $lease -AdbInvoker $thrownFailure.Invoker)
} catch {
    $caught = $_
}
Assert-True ($caught.Exception.Message -eq 'fake ADB threw while reading logcat') `
    'A thrown ADB error was replaced or accepted.'
Assert-BothCleanupCalls -State $thrownFailure.State -Context 'thrown log retrieval failure'
Assert-True ($thrownFailure.State.RemotePaths.Count -eq 0) `
    'A thrown ADB error left fake remote files behind.'

$primaryWithCleanupFailure = New-FakeAdb -FailureStage 'mark' -CleanupThrowNumber 1
$warnings = @()
$caught = $null
try {
    [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local 'C:/fixture.apk' -Label 'case' -Lease $lease -AdbInvoker $primaryWithCleanupFailure.Invoker `
        -WarningVariable warnings)
} catch {
    $caught = $_
}
Assert-True ($caught.Exception.Message -eq $failures.mark) `
    'A cleanup failure replaced the original verification failure.'
Assert-BothCleanupCalls -State $primaryWithCleanupFailure.State -Context 'secondary cleanup failure'
Assert-True (($warnings | ForEach-Object { "$_" }) -join "`n" -match 'cleanup also failed') `
    'A cleanup failure beside a primary failure produced no secondary diagnostic.'

$success = New-FakeAdb
$tally = Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
    -Local 'C:/fixture.apk' -Label 'case' -Lease $lease -AdbInvoker $success.Invoker
Assert-True ($tally.Count -eq 2 -and $tally['Verification error in Lfixture/Host;'] -eq 2 -and
    $tally['VerifyError in Lfixture/Other;'] -eq 1) 'The successful fake verifier tally was wrong.'
Assert-BothCleanupCalls -State $success.State -Context 'success'
Assert-True ($success.State.RemotePaths.Count -eq 0) 'The successful run left fake remote files behind.'

$successWithCleanupFailure = New-FakeAdb -CleanupFailureNumber 1
$caught = $null
try {
    [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' `
        -Local 'C:/fixture.apk' -Label 'case' -Lease $lease -AdbInvoker $successWithCleanupFailure.Invoker)
} catch {
    $caught = $_
}
Assert-True ($null -ne $caught -and $caught.Exception.Message -like 'Verifier cleanup failed.*') `
    'A cleanup failure after successful verification was accepted.'
Assert-BothCleanupCalls -State $successWithCleanupFailure.State -Context 'success cleanup failure'

# No lease, a lease on another serial, or a context whose token no longer matches the file:
# nothing reaches the phone, cleanup included.
$otherSerialLease = New-FixtureLease -Serial 'OTHER'
$forgedLease = [pscustomobject]@{
    Path = $lease.Path; Serial = $lease.Serial; Stream = $lease.Stream
    OwnershipToken = [guid]::NewGuid().ToString('N')
}
foreach ($refused in @(
        @{ Name = 'missing'; Lease = $null; Message = 'Device lease is no longer held. No device command was sent.' },
        @{ Name = 'other serial'; Lease = $otherSerialLease; Message = 'Device lease ownership or serial changed. No device command was sent.' },
        @{ Name = 'foreign token'; Lease = $forgedLease; Message = 'Device lease ownership or serial changed. No device command was sent.' })) {
    $fake = New-FakeAdb
    $caught = $null
    try {
        [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'SERIAL' -Local 'C:/fixture.apk' `
            -Label 'case' -Lease $refused.Lease -AdbInvoker $fake.Invoker 3>$null)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -eq $refused.Message) `
        "A $($refused.Name) lease was not refused as expected: $($caught.Exception.Message)"
    Assert-True ($fake.State.Calls.Count -eq 0) `
        "A $($refused.Name) lease still reached the fake device: $($fake.State.Calls -join ' | ')"
}

# A lease that runs out after the push stops the run there, and cleanup without it is refused too.
$expiring = New-FakeAdb
$expiringLease = New-FixtureLease -Serial 'EXPIRING'
$expiringInvoker = {
    param([string]$Executable, [string[]]$Arguments)
    $result = & $expiring.Invoker $Executable $Arguments
    if ($Arguments[2] -eq 'push') {
        $held = Read-DeviceLeaseRecord -Lease $expiringLease
        $held.expiresUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
        Write-DeviceLeaseRecord -Lease $expiringLease -Record $held
    }
    $result
}
$caught = $null
try {
    [void](Invoke-AndroidVerifierTally -Adb 'fake-adb' -Serial 'EXPIRING' -Local 'C:/fixture.apk' `
        -Label 'case' -Lease $expiringLease -AdbInvoker $expiringInvoker 3>$null)
} catch {
    $caught = $_
}
Assert-True ($null -ne $caught -and $caught.Exception.Message -like 'Device lease expired.*') `
    "A lease that expired between commands was not refused: $($caught.Exception.Message)"
Assert-True ($expiring.State.Calls.Count -eq 1 -and $expiring.State.Calls[0] -like '-s EXPIRING push *') `
    "Commands after the lease expired reached the fake device: $($expiring.State.Calls -join ' | ')"

$verifierText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'verify-injected-registers.ps1') -Raw
Assert-True ($verifierText -match 'injected-register-device\.ps1' -and
    $verifierText -match 'Invoke-AndroidVerifierTally') `
    'The device verifier does not use the cleanup helper.'
# Each device half takes its own lease right before a try whose finally gives it back, and every
# adb command and verifier tally runs inside one of those tries, the tallies with that lease.
$verifierAst = [System.Management.Automation.Language.Parser]::ParseFile(
    (Join-Path $PSScriptRoot 'verify-injected-registers.ps1'), [ref]$null, [ref]$null)
$leaseTries = @($verifierAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
                "$($node.Left)" -eq '$deviceLease' -and "$($node.Right)" -match '\ANew-DeviceLease\b'
        }, $true) | ForEach-Object {
        $statements = @($_.Parent.Statements)
        $next = $statements[[array]::IndexOf($statements, $_) + 1]
        if ($next -is [System.Management.Automation.Language.TryStatementAst] -and $null -ne $next.Finally -and
            "$($next.Finally)" -match 'Remove-DeviceLease -Lease \$deviceLease -WarnOnFailure') { $next }
    })
$deviceCalls = @($verifierAst.FindAll({
            param($node)
            $node -is [System.Management.Automation.Language.CommandAst] -and
                ("$($node.CommandElements[0])" -eq '$adbPath' -or $node.GetCommandName() -eq 'Invoke-AndroidVerifierTally')
        }, $true))
$outsideLease = @($deviceCalls | Where-Object {
        $call = $_
        $inside = $false
        for ($up = $call.Parent; $null -ne $up -and -not $inside; $up = $up.Parent) {
            foreach ($leaseTry in $leaseTries) { if ([object]::ReferenceEquals($up, $leaseTry.Body)) { $inside = $true } }
        }
        -not $inside -or ($call.GetCommandName() -eq 'Invoke-AndroidVerifierTally' -and "$call" -notmatch '-Lease \$deviceLease\b')
    })
Assert-True ($leaseTries.Count -eq 2 -and $deviceCalls.Count -ge 4 -and $outsideLease.Count -eq 0) `
    "The device verifier reaches the phone outside a held lease: $(@($outsideLease | ForEach-Object { $_.Extent.StartLineNumber }) -join ', ')"

# A command naming another phone than the leased one is refused before it runs.
foreach ($arguments in @(, @('-s', 'OTHER', 'shell', 'true')) + @(, @('shell', 'true'))) {
    $fake = New-FakeAdb
    $caught = $null
    try {
        [void](Invoke-HushfeedAdbCommand -Adb 'fake-adb' -Arguments $arguments -Lease $lease -Serial 'SERIAL' -Invoker $fake.Invoker)
    } catch {
        $caught = $_
    }
    Assert-True ($null -ne $caught -and $caught.Exception.Message -eq "The ADB command doesn't target the leased serial. No device command was sent.") `
        "A command for another phone was not refused: $($arguments -join ' ')"
    Assert-True ($fake.State.Calls.Count -eq 0) "A command for another phone reached the fake device: $($fake.State.Calls -join ' | ')"
}
$prePushText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'pre-push.ps1') -Raw
Assert-True ($prePushText -match 'scripts/test-injected-register-device\.ps1') `
    'The push gate does not run the verifier cleanup fixtures.'
} finally {
    foreach ($context in $leaseContexts) {
        if ($null -ne $context.Stream) { $context.Stream.Dispose() }
    }
    $resolved = [IO.Path]::GetFullPath($leaseFixture)
    if ([IO.Directory]::Exists($resolved)) {
        if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
            [IO.Path]::GetFileName($resolved) -notmatch '\Ahushfeed-register-lease-test-[a-f0-9]{32}\z') {
            throw 'Refusing cleanup outside the temporary lease fixture.'
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}

$global:LASTEXITCODE = 0
Write-Host '[scripts] injected-register device cleanup and lease contracts passed'
