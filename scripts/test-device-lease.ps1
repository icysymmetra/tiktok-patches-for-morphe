[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'device-lease.ps1')
$script:passed = 0
$script:deviceCommands = 0
$contexts = New-Object 'Collections.Generic.List[object]'
function Assert-Lease([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    $script:passed++
}
function Assert-LeaseRejected([scriptblock]$Action, [string]$Message) {
    $rejected = $false
    try { & $Action } catch { $rejected = $true }
    Assert-Lease $rejected $Message
}
function New-FixtureLease([string]$Serial = 'fixture-device') {
    $context = New-DeviceLease -LeaseDir $pool -Serial $Serial -Project 'fixture-project' `
        -ChatIdentity 'fixture-chat' -Purpose 'fake-device tests' -LeaseMinutes 1
    $contexts.Add($context)
    return $context
}
function Invoke-FixtureDevice { $script:deviceCommands++ }
function Read-FixtureSharedFile([string]$Path) {
    $file = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
        ([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    try {
        $reader = New-Object IO.StreamReader($file)
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $file.Dispose() }
}
function Wait-FixtureFile([string]$Path) {
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while (-not [IO.File]::Exists($Path)) {
        if ($timer.Elapsed.TotalSeconds -ge 20) { throw "Fixture worker did not produce $(Split-Path -Leaf $Path)." }
        Start-Sleep -Milliseconds 20
    }
}
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$fixture = Join-Path $temporaryRoot ('hushfeed-device-lease-test-' + [guid]::NewGuid().ToString('N'))
$pool = Join-Path $fixture 'shared-pool'
[void][IO.Directory]::CreateDirectory($fixture)
$job = $null
try {
    # Dot-sourcing is inert: local build paths do not need a serial or a lease directory.
    Assert-Lease (-not [IO.Directory]::Exists($pool)) 'Loading the helper acquired or created a device lease.'
    $lease = New-FixtureLease
    $path = $lease.Path
    $record = Read-DeviceLeaseRecord $lease
    Assert-Lease ($record.schemaVersion -eq 1 -and $record.serial -ceq 'fixture-device') 'Lease identity metadata is missing.'
    Assert-Lease ($record.project -ceq 'fixture-project' -and $record.chatIdentity -ceq 'fixture-chat' -and
        $record.purpose -ceq 'fake-device tests') 'Project, chat identity or purpose was lost.'
    Assert-Lease ($record.ownershipToken -cmatch '\A[a-f0-9]{32}\z') 'Lease token is not unique-token shaped.'
    $rawRecord = Read-FixtureSharedFile $path
    Assert-Lease ($rawRecord -cmatch '"acquiredUtc":"\d{4}-[^"]+Z"' -and $rawRecord -cmatch '"expiresUtc":"\d{4}-[^"]+Z"') 'Lease timestamps were not written as UTC.'
    $term = (ConvertTo-DeviceLeaseExpiry $record.expiresUtc) - (ConvertTo-DeviceLeaseExpiry $record.acquiredUtc)
    Assert-Lease ($term -eq [TimeSpan]::FromMinutes(1)) 'Lease term did not match the requested minutes.'
    Assert-Lease (Test-DeviceLease $lease) 'Fresh lease was rejected.'
    Assert-Lease ((Read-FixtureSharedFile $path | ConvertFrom-Json).ownershipToken -ceq $lease.OwnershipToken) 'Metadata cannot be inspected by a compatible reader.'
    # Other tasks in the pool read leases with Get-Content or Python, which share writing but not deleting.
    Assert-Lease (((Get-Content -LiteralPath $path -Raw) | ConvertFrom-Json).ownershipToken -ceq $lease.OwnershipToken) 'Get-Content could not read a held lease.'
    $opened = $false
    try {
        [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite).Dispose()
        $opened = $true
    } catch { }
    Assert-Lease $opened 'A reader sharing writing alone could not open a held lease.'
    Assert-LeaseRejected { New-DeviceLease -LeaseDir $pool -Serial 'long-fixture' -Project 'fixture' -ChatIdentity 'fixture' -Purpose 'fixture' -LeaseMinutes 31 } 'A lease longer than the pool limit was accepted.'
    Assert-LeaseRejected { Update-DeviceLease $lease -LeaseMinutes 31 } 'A renewal longer than the pool limit was accepted.'
    Assert-Lease (-not [IO.File]::Exists((Join-Path $pool 'long-fixture.json'))) 'A refused long lease left a file.'
    $second = New-FixtureLease 'other-fixture-device'
    Assert-Lease ($second.OwnershipToken -cne $lease.OwnershipToken) 'Two acquisitions reused a token.'
    Remove-DeviceLease $second

    Invoke-WithDeviceLease -Lease $lease -Serial 'fixture-device' -Action { Invoke-FixtureDevice }
    Assert-Lease ($script:deviceCommands -eq 1) 'Owned command was not invoked exactly once.'
    Assert-LeaseRejected { Invoke-WithDeviceLease $lease { Invoke-FixtureDevice } -Serial 'other-device' } 'A command for a different serial ran.'
    Assert-LeaseRejected { Invoke-WithDeviceLease $null { Invoke-FixtureDevice } } 'A command without a lease ran.'
    Assert-Lease ($script:deviceCommands -eq 1) 'A refused command reached the fake device.'
    Assert-LeaseRejected { New-FixtureLease } 'A second owner acquired the occupied serial.'
    # Failure must terminate even when the caller has the PowerShell default error policy.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        try { New-FixtureLease; Invoke-FixtureDevice } catch { }
    } finally { $ErrorActionPreference = $preference }
    Assert-Lease ($script:deviceCommands -eq 1) 'Failed acquisition continued into a device command.'

    $acquired = ConvertTo-DeviceLeaseExpiry $record.acquiredUtc
    $expiry = ConvertTo-DeviceLeaseExpiry $record.expiresUtc
    Update-DeviceLease $lease -LeaseMinutes 2
    $renewed = Read-DeviceLeaseRecord $lease
    Assert-Lease ((ConvertTo-DeviceLeaseExpiry $renewed.expiresUtc) -gt $expiry) 'Renewal did not extend expiry.'
    Assert-Lease ($renewed.ownershipToken -ceq $record.ownershipToken -and
        (ConvertTo-DeviceLeaseExpiry $renewed.acquiredUtc) -eq $acquired -and
        $renewed.chatIdentity -ceq $record.chatIdentity) 'Renewal changed acquisition identity.'

    # A separate process tries replacement while the same lease is checked and renewed.
    $ready = Join-Path $fixture 'worker-ready.json'
    $release = Join-Path $fixture 'release-ready'
    $nextOwner = Join-Path $fixture 'next-owner'
    $stop = Join-Path $fixture 'worker-stop'
    $job = Start-Job -ArgumentList (Join-Path $PSScriptRoot 'device-lease.ps1'), $pool, $path, $ready, $release, $nextOwner, $stop -ScriptBlock {
        param($Helper, $Pool, $Path, $Ready, $Release, $NextOwner, $Stop)
        $ErrorActionPreference = 'Stop'
        . $Helper
        $held = $null
        $checks = @{ writeDenied = $false; deleteDenied = $false; moveDenied = $false; acquireDenied = $false }
        try {
            try { [IO.File]::WriteAllText($Path, 'foreign replacement') } catch { $checks.writeDenied = $true }
            try { [IO.File]::Delete($Path) } catch { $checks.deleteDenied = $true }
            try { [IO.File]::Move($Path, "$Path.moved") } catch { $checks.moveDenied = $true }
            try {
                $held = New-DeviceLease -LeaseDir $Pool -Serial 'fixture-device' -Project 'worker' -ChatIdentity 'worker' -Purpose 'contention'
            } catch { $checks.acquireDenied = $true }
            [IO.File]::WriteAllText($Ready, ($checks | ConvertTo-Json -Compress))
            $timer = [Diagnostics.Stopwatch]::StartNew()
            while (-not [IO.File]::Exists($Release)) {
                if ($timer.Elapsed.TotalSeconds -ge 20) { throw 'Parent did not release its fixture lease.' }
                Start-Sleep -Milliseconds 10
            }
            # Compete at the instant release closes its handle; never overwrite an existing file.
            while ($null -eq $held) {
                try {
                    $held = New-DeviceLease -LeaseDir $Pool -Serial 'fixture-device' -Project 'worker' -ChatIdentity 'worker' -Purpose 'next owner'
                } catch {
                    if ($timer.Elapsed.TotalSeconds -ge 20) { throw }
                    Start-Sleep -Milliseconds 10
                }
            }
            [IO.File]::WriteAllText($NextOwner, $held.OwnershipToken)
            while (-not [IO.File]::Exists($Stop)) {
                if ($timer.Elapsed.TotalSeconds -ge 20) { throw 'Parent did not finish ownership checks.' }
                Start-Sleep -Milliseconds 10
            }
        } finally { Remove-DeviceLease -Lease $held }
    }
    Wait-FixtureFile $ready
    $checks = [IO.File]::ReadAllText($ready) | ConvertFrom-Json
    Assert-Lease ($checks.writeDenied -and $checks.deleteDenied -and $checks.moveDenied -and $checks.acquireDenied) 'Another process could replace an active lease.'
    Update-DeviceLease $lease
    Invoke-WithDeviceLease $lease { Invoke-FixtureDevice }
    Assert-Lease ((Read-DeviceLeaseRecord $lease).ownershipToken -ceq $lease.OwnershipToken) 'Contended renewal lost the token.'
    [IO.File]::WriteAllText($release, 'ready')
    Remove-DeviceLease $lease
    Wait-FixtureFile $nextOwner
    $foreign = Read-FixtureSharedFile $path
    Assert-Lease (($foreign | ConvertFrom-Json).ownershipToken -ceq [IO.File]::ReadAllText($nextOwner)) 'Release removed the next owner.'
    Remove-DeviceLease $lease
    Assert-Lease ((Read-FixtureSharedFile $path) -ceq $foreign) 'Repeated release removed another owner.'
    Assert-LeaseRejected { Update-DeviceLease $lease } 'Released context renewed another owner.'
    Assert-LeaseRejected { Invoke-WithDeviceLease $lease { Invoke-FixtureDevice } } 'Released context sent a command.'
    [IO.File]::WriteAllText($stop, 'finished')
    [void](Wait-Job -Job $job -Timeout 20)
    Assert-Lease ($job.State -eq 'Completed') 'Competing fixture process did not finish.'
    [void](Receive-Job -Job $job -ErrorAction Stop)
    Remove-Job -Job $job
    $job = $null
    Assert-Lease (-not [IO.File]::Exists($path)) 'The new owner could not release its own lease.'

    # Preserve DateTime.Kind and explicit offsets across both PowerShell JSON readers.
    $instant = [DateTimeOffset]::Parse('2026-01-02T03:04:05Z', [Globalization.CultureInfo]::InvariantCulture)
    foreach ($value in $instant, $instant.UtcDateTime, $instant.LocalDateTime, '2026-01-02T03:04:05Z', '2026-01-01T23:04:05-04:00') {
        Assert-Lease ((ConvertTo-DeviceLeaseExpiry $value) -eq $instant) 'Expiry conversion shifted the instant.'
    }
    foreach ($value in $null, '', '2026-01-02T03:04:05', 'invalid', [DateTime]::SpecifyKind($instant.DateTime, [DateTimeKind]::Unspecified)) {
        Assert-LeaseRejected { ConvertTo-DeviceLeaseExpiry $value } 'Unknown expiry was interpreted as a valid instant.'
    }
    foreach ($value in [DateTime]::UtcNow.AddMinutes(-5).ToString('o'), [DateTimeOffset]::UtcNow.AddMinutes(-5).ToOffset([TimeSpan]::FromHours(-4)).ToString('o')) {
        $lease = New-FixtureLease
        $record = Read-DeviceLeaseRecord $lease
        $record.expiresUtc = $value
        Write-DeviceLeaseRecord $lease $record
        $before = Read-FixtureSharedFile $path
        Assert-Lease (-not (Test-DeviceLease $lease)) 'Expired own token remained valid.'
        Assert-LeaseRejected { Update-DeviceLease $lease } 'Expired own token was renewed.'
        Assert-LeaseRejected { Invoke-WithDeviceLease $lease { Invoke-FixtureDevice } } 'Expired own token sent a command.'
        Assert-LeaseRejected { Remove-DeviceLease $lease } 'Expired own token authorized cleanup.'
        Assert-Lease ([IO.File]::ReadAllText($path) -ceq $before) 'Expired own lease was modified without recovery.'
        [IO.File]::Delete($path) # Fixture test is stopped; this is the explicit recovery step.
    }
    foreach ($body in @(
        (@{ ownershipToken = 'foreign'; expiresUtc = [DateTime]::UtcNow.AddMinutes(5).ToString('o') } | ConvertTo-Json),
        (@{ ownershipToken = 'foreign'; expiresUtc = [DateTime]::UtcNow.AddMinutes(-5).ToString('o') } | ConvertTo-Json),
        '{"token":"legacy-foreign","expires_utc":"2020-01-01T00:00:00Z"}',
        '{"ownershipToken":"foreign"}', 'invalid json', ''
    )) {
        [IO.File]::WriteAllText($path, $body)
        Assert-LeaseRejected { New-FixtureLease } 'A foreign or malformed lease was reclaimed.'
        Remove-DeviceLease $lease
        Assert-Lease ([IO.File]::ReadAllText($path) -ceq $body) 'Rejected acquisition or stale release changed a foreign lease.'
        [IO.File]::Delete($path)
    }

    foreach ($change in 'token', 'serial', 'expiry', 'schema', 'oversized', 'ownership-loss') {
        $lease = New-FixtureLease
        $record = Read-DeviceLeaseRecord $lease
        switch ($change) {
            token { $record.ownershipToken = 'new-owner' }
            serial { $record.serial = 'another-serial' }
            expiry { $record.expiresUtc = 'no-time-zone' }
            schema { $record.schemaVersion = 2 }
            ownership-loss { $lease.Stream.Dispose(); $record.ownershipToken = 'new-owner' }
        }
        if ($change -eq 'ownership-loss') {
            [IO.File]::WriteAllText($path, ($record | ConvertTo-Json))
        } elseif ($change -eq 'oversized') {
            $bytes = [Text.Encoding]::UTF8.GetBytes('x' * 16385)
            $lease.Stream.Position = 0
            $lease.Stream.Write($bytes, 0, $bytes.Length)
            $lease.Stream.Flush($true)
        } else { Write-DeviceLeaseRecord $lease $record }
        $before = Read-FixtureSharedFile $path
        Assert-Lease (-not (Test-DeviceLease $lease)) "Invalid lease was current: $change"
        Assert-LeaseRejected { Update-DeviceLease $lease } "Invalid lease renewed: $change"
        Assert-LeaseRejected { Invoke-WithDeviceLease $lease { Invoke-FixtureDevice } } "Invalid lease sent a command: $change"
        Assert-LeaseRejected { Remove-DeviceLease $lease } "Invalid lease authorized cleanup: $change"
        Assert-Lease ([IO.File]::ReadAllText($path) -ceq $before) "Refused renewal/release changed another owner's file: $change"
        [IO.File]::Delete($path)
    }
    Assert-Lease ($script:deviceCommands -eq 2) 'A failed ownership check reached the fake device.'
    foreach ($serial in '..', '../escape', '..\escape', 'folder/device', 'folder\device', 'drive:5555', 'CON', 'Lpt9', 'NUL.txt', 'trailing.', "newline`n", ('x' * 129), ('dev' + [char]0x212A)) {
        Assert-LeaseRejected { New-FixtureLease $serial } "Unsafe serial became a path: $serial"
    }
    Assert-LeaseRejected { New-DeviceLease -LeaseDir '' -Serial 'fixture-device' -Project 'fixture' -ChatIdentity 'fixture' -Purpose 'fixture' } 'A blank shared directory was accepted.'
    $obstacle = Join-Path $fixture 'not-a-directory'
    [IO.File]::WriteAllText($obstacle, 'fixture')
    Assert-LeaseRejected { New-DeviceLease -LeaseDir (Join-Path $obstacle 'pool') -Serial 'fixture-device' -Project 'fixture' -ChatIdentity 'fixture' -Purpose 'fixture' } 'Failed directory creation acquired a lease.'

    # Simulate a first-write failure after CREATE_NEW. Only that exact partial file may be removed.
    $writer = (Get-Item Function:\Write-DeviceLeaseRecord).ScriptBlock
    try {
        function Write-DeviceLeaseRecord { param($Lease, $Record); throw 'Fixture write failure.' }
        Assert-LeaseRejected { New-FixtureLease } 'A failed first write reported successful acquisition.'
        Assert-Lease (-not [IO.File]::Exists($path)) 'Failed acquisition left a partial lease or open handle.'
    } finally { Set-Item -LiteralPath Function:\Write-DeviceLeaseRecord -Value $writer }
    $lease = New-FixtureLease
    Remove-DeviceLease $lease
    Remove-DeviceLease $null
    Assert-Lease (-not [IO.File]::Exists($path)) 'Healthy release did not delete the owned lease.'

    # A pool reader that doesn't share deleting, open as release starts, delays the delete only.
    if (-not ('HushfeedFixture.BriefReader' -as [type])) {
        Add-Type -TypeDefinition @'
using System.IO;
using System.Threading;
namespace HushfeedFixture {
    public static class BriefReader {
        public static void Hold(string path, int milliseconds) {
            var file = File.Open(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
            new Thread(() => { Thread.Sleep(milliseconds); file.Dispose(); }) { IsBackground = true }.Start();
        }
    }
}
'@
    }
    $lease = New-FixtureLease
    [HushfeedFixture.BriefReader]::Hold($path, 300)
    Remove-DeviceLease $lease
    Assert-Lease (-not [IO.File]::Exists($path)) 'Release gave up while a pool reader had the lease open.'

    # In a finally block a refused release warns instead of replacing the run's own error.
    $lease = New-FixtureLease
    $record = Read-DeviceLeaseRecord $lease
    $record.expiresUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
    Write-DeviceLeaseRecord $lease $record
    $warnings = $null
    Remove-DeviceLease $lease -WarnOnFailure -WarningVariable warnings -WarningAction SilentlyContinue
    Assert-Lease (@($warnings).Count -eq 1 -and [IO.File]::Exists($path)) 'A refused release in a finally block threw or deleted an expired lease.'
    [IO.File]::Delete($path)
    $lease = New-FixtureLease
    $record = Read-DeviceLeaseRecord $lease
    $record.expiresUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
    Write-DeviceLeaseRecord $lease $record
    $failure = $null
    try {
        try { throw 'fixture run failure' } finally { Remove-DeviceLease $lease -WarnOnFailure -WarningAction SilentlyContinue }
    } catch { $failure = $_.Exception.Message }
    Assert-Lease ($failure -ceq 'fixture run failure') 'Lease release replaced the error that ended the run.'
    [IO.File]::Delete($path)

    # Exercise the real installation helpers without SDK tools, APKs or a connected device.
    . (Join-Path $PSScriptRoot 'common.ps1')
    . (Join-Path $PSScriptRoot 'device-install.ps1')
    $script:packageCalls = @()
    $script:packageMode = 'present'
    $script:pulledFixture = $null
    function Invoke-FixturePackageCommand {
        $script:packageCalls += ($args -join ' ')
        $global:LASTEXITCODE = 0
        switch ($args[2]) {
            shell {
                if ($script:packageMode -like 'expire-*') {
                    $held = Read-DeviceLeaseRecord $script:packageLease
                    $held.expiresUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
                    Write-DeviceLeaseRecord $script:packageLease $held
                }
                if ($script:packageMode -in @('absent-silent', 'expire-state')) { $global:LASTEXITCODE = 1; return }
                if ($script:packageMode -ne 'absent') { 'package:/fixture/base.apk' }
            }
            get-state { 'device' }
            pull {
                if ($script:packageMode -eq 'pull-refused') { 'adb: error: fixture refusal'; $global:LASTEXITCODE = 1; return }
                $script:pulledFixture = $args[4]
                [IO.File]::WriteAllText($script:pulledFixture, 'fake package bytes')
            }
        }
    }
    function Get-ApkSigningCertificate {
        param($Session, [string]$Apk)
        if (-not [IO.File]::Exists($Apk)) { throw 'Fixture pull did not create its file.' }
        return $Session.Certificate
    }
    $packageArguments = @{ Adb = 'Invoke-FixturePackageCommand'; Serial = 'fixture-device'; PackageName = 'fixture.package' }
    $signingFixture = [pscustomobject]@{ Certificate = 'fixture-certificate' }
    foreach ($action in @(
        { Get-AndroidPackagePaths @packageArguments },
        { Assert-InstalledApkSigner @packageArguments -SigningSession $signingFixture },
        { Remove-AndroidPackageIfInstalled @packageArguments }
    )) { Assert-LeaseRejected $action 'An installation helper accepted a missing lease.' }
    Assert-Lease ($script:packageCalls.Count -eq 0) 'A missing lease reached the package command.'
    $script:packageLease = New-FixtureLease
    $packageArguments.Lease = $script:packageLease
    $packageArguments.Serial = 'wrong-device'
    foreach ($action in @(
        { Get-AndroidPackagePaths @packageArguments },
        { Assert-InstalledApkSigner @packageArguments -SigningSession $signingFixture },
        { Remove-AndroidPackageIfInstalled @packageArguments }
    )) { Assert-LeaseRejected $action 'An installation helper accepted a different serial.' }
    Assert-Lease ($script:packageCalls.Count -eq 0) 'A mismatched serial reached the package command.'
    $packageArguments.Serial = 'fixture-device'
    $script:packageMode = 'absent'
    Assert-Lease (@(Get-AndroidPackagePaths @packageArguments).Count -eq 0) 'An absent package was reported installed.'
    $script:packageCalls = @()
    $script:packageMode = 'absent-silent'
    Assert-Lease (@(Get-AndroidPackagePaths @packageArguments).Count -eq 0 -and $script:packageCalls.Count -eq 2) 'The owned API 36 fallback changed.'
    $script:packageCalls = @()
    $script:packageMode = 'present'
    Assert-Lease (Remove-AndroidPackageIfInstalled @packageArguments) 'An owned replacement did not uninstall.'
    Assert-Lease ($script:packageCalls.Count -eq 2 -and $script:packageCalls[1] -like '*uninstall fixture.package') 'Replacement did not query before uninstalling.'
    $script:packageCalls = @()
    Assert-InstalledApkSigner @packageArguments -SigningSession $signingFixture
    Assert-Lease ($script:packageCalls.Count -eq 2 -and $script:packageCalls[1] -like '*pull*') 'Signer check did not query then pull.'
    Assert-Lease (-not [IO.File]::Exists($script:pulledFixture)) 'Signer check left its local temporary file.'
    $script:packageMode = 'pull-refused'
    $refusal = $null
    try { Assert-InstalledApkSigner @packageArguments -SigningSession $signingFixture } catch { $refusal = $_.Exception.Message }
    Assert-Lease ($refusal -like '*fixture.package: adb: error: fixture refusal') "A refused pull hid adb's reason: $refusal"
    $script:packageMode = 'present'
    Remove-DeviceLease $script:packageLease
    foreach ($mode in 'expire-state', 'expire-pull', 'expire-uninstall') {
        $script:packageLease = New-FixtureLease
        $packageArguments.Lease = $script:packageLease
        $script:packageCalls = @()
        $script:packageMode = $mode
        $operation = switch ($mode) {
            expire-state { { Get-AndroidPackagePaths @packageArguments } }
            expire-pull { { Assert-InstalledApkSigner @packageArguments -SigningSession $signingFixture } }
            expire-uninstall { { Remove-AndroidPackageIfInstalled @packageArguments } }
        }
        Assert-LeaseRejected $operation "A follow-up command ignored expiry: $mode"
        Assert-Lease ($script:packageCalls.Count -eq 1) "A follow-up command reached the device after expiry: $mode"
        Assert-LeaseRejected { Remove-DeviceLease $script:packageLease } 'Expired helper lease authorized cleanup.'
        [IO.File]::Delete($path)
    }

    # The probe's early uninstall path must acquire/release even though it never builds anything.
    $probeBuilder = Join-Path (Split-Path -Parent $PSScriptRoot) 'tools/verification-probe/build.ps1'
    $fakeBin = Join-Path $fixture 'fake-bin'
    [void][IO.Directory]::CreateDirectory($fakeBin)
    $fakeAdb = Join-Path $fakeBin 'adb.cmd'
    $fakeLog = Join-Path $fakeBin 'adb.log'
    # pm path exits 1 with no output for a missing package (API 36), and get-state still says device.
    # With a front file TikTok is the top activity, and a probe action's marker comes back in the
    # log ahead of one answer. Redirects go first, since "echo x3>>log" redirects handle 3.
    [IO.File]::WriteAllLines($fakeAdb, [string[]]@(
        '@echo off', '>>"%~dp0adb.log" echo %*', 'if exist "%~dp0fail" exit /b 19',
        'if "%3"=="get-state" (echo device& exit /b 0)', 'if "%3"=="logcat" goto logcat',
        'if not "%3"=="shell" exit /b 0', 'if "%4"=="dumpsys" goto dumpsys',
        'if "%4"=="log" (>"%~dp0marker" echo %7& exit /b 0)', 'if "%4"=="am" exit /b 0',
        'if exist "%~dp0absent" exit /b 1', 'echo package:/data/app/probe/base.apk', 'exit /b 0',
        ':dumpsys', 'if exist "%~dp0front" echo   topResumedActivity=ActivityRecord{1 u0 com.zhiliaoapp.musically/.MainActivity t1}', 'exit /b 0',
        ':logcat', 'if exist "%~dp0marker" type "%~dp0marker"', 'echo ok stripkeys anchors=[fixture_anchor/1] banners=[]', 'exit /b 0'))
    $environmentBefore = @{}
    foreach ($name in 'PATH', 'HUSHFEED_DEVICE_LEASE_DIR', 'HUSHFEED_CHAT_IDENTITY', 'HUSHFEED_DEVICE_SERIAL') {
        $environmentBefore[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
    }
    try {
        $env:PATH = $fakeBin + [IO.Path]::PathSeparator + $env:PATH
        $env:HUSHFEED_DEVICE_LEASE_DIR = $pool
        $env:HUSHFEED_CHAT_IDENTITY = 'probe-fixture-chat'
        Assert-Lease ((Get-Command adb).Source -ceq $fakeAdb) 'Probe fixture did not select its fake ADB executable.'
        & $probeBuilder -Uninstall -Serial 'probe-fixture'
        $probePath = Join-Path $pool 'probe-fixture.json'
        $probeCommands = @([IO.File]::ReadAllLines($fakeLog) | ForEach-Object { $_.Trim() }) -join '|'
        Assert-Lease ($probeCommands -ceq '-s probe-fixture shell pm path app.hushfeed.verification|-s probe-fixture uninstall app.hushfeed.verification') "Probe uninstall targeted the wrong serial or operation: $probeCommands"
        Assert-Lease (-not [IO.File]::Exists($probePath)) 'Successful probe uninstall retained its lease.'
        [IO.File]::Delete($fakeLog)
        $absentMarker = Join-Path $fakeBin 'absent'
        [IO.File]::WriteAllText($absentMarker, 'not installed')
        & $probeBuilder -Uninstall -Serial 'probe-fixture'
        $probeCommands = @([IO.File]::ReadAllLines($fakeLog) | ForEach-Object { $_.Trim() }) -join '|'
        Assert-Lease ($probeCommands -ceq '-s probe-fixture shell pm path app.hushfeed.verification|-s probe-fixture get-state') "A probe that is not installed was still uninstalled: $probeCommands"
        Assert-Lease (-not [IO.File]::Exists($probePath)) 'Uninstalling a probe that is not installed kept its lease.'
        [IO.File]::Delete($absentMarker)
        [IO.File]::Delete($fakeLog)
        $lease = New-FixtureLease 'probe-fixture'
        Assert-LeaseRejected { & $probeBuilder -Uninstall -Serial 'probe-fixture' } 'Probe uninstall ignored another active lease.'
        Assert-Lease (-not [IO.File]::Exists($fakeLog) -and (Test-DeviceLease $lease)) 'Busy probe uninstall sent a command or changed the owner.'
        Remove-DeviceLease $lease
        Assert-LeaseRejected { & $probeBuilder -Uninstall -Serial 'probe-fixture' -LeaseDir '' } 'Probe uninstall accepted a missing shared directory.'
        Assert-LeaseRejected { & $probeBuilder -Uninstall -Serial 'probe-fixture' -ChatIdentity '' } 'Probe uninstall accepted a missing chat identity.'
        Assert-Lease (-not [IO.File]::Exists($fakeLog) -and -not [IO.File]::Exists($probePath)) 'Invalid probe lease parameters reached ADB or left a lease.'
        [IO.File]::WriteAllText((Join-Path $fakeBin 'fail'), 'fixture failure')
        Assert-LeaseRejected { & $probeBuilder -Uninstall -Serial 'probe-fixture' } 'Failed probe uninstall reported success.'
        Assert-Lease (-not [IO.File]::Exists($probePath)) 'Failed probe uninstall retained its lease.'
        [IO.File]::Delete((Join-Path $fakeBin 'fail'))
        [IO.File]::Delete($fakeLog)

        # A probe action checks the caller's lease on that exact serial before every command.
        . (Join-Path (Split-Path -Parent $PSScriptRoot) 'tools/verification-probe/probe-log.ps1')
        $script:probeCalls = @()
        $script:probeExpire = $null
        function Invoke-FixtureProbeCommand {
            $script:probeCalls += ($args -join ' ')
            $global:LASTEXITCODE = 0
            if ($args[3] -eq 'log') { $script:probeMarker = $args[-1] }
            if ($null -ne $script:probeExpire) {
                $held = Read-DeviceLeaseRecord $script:probeExpire
                $held.expiresUtc = [DateTime]::UtcNow.AddMinutes(-1).ToString('o')
                Write-DeviceLeaseRecord $script:probeExpire $held
            }
            if ($args[2] -eq 'logcat') { 'ok stripkeys anchors=[earlier] banners=[]'; $script:probeMarker; 'ok stripkeys anchors=[ours] banners=[]' }
        }
        $probeAction = @{ Serial = 'probe-action'; Action = 'stripkeys'; WaitMs = 0; Adb = 'Invoke-FixtureProbeCommand' }
        $probeLease = New-FixtureLease 'probe-action'
        $answer = @(Invoke-ProbeAction @probeAction -Lease $probeLease)
        Assert-Lease ($answer.Count -eq 1 -and $answer[0] -ceq 'ok stripkeys anchors=[ours] banners=[]') "An owned probe action lost its answer: $($answer -join ' | ')"
        Assert-Lease ($script:probeCalls.Count -eq 3 -and $script:probeCalls[0] -clike '-s probe-action shell log -t HushfeedProbe hushfeed-probe-*' -and
            $script:probeCalls[1] -clike '-s probe-action shell am broadcast * -e action stripkeys' -and
            $script:probeCalls[2] -ceq '-s probe-action logcat -d -s HushfeedProbe:V -v raw') "An owned probe action sent the wrong commands: $($script:probeCalls -join ' | ')"
        $otherProbeLease = New-FixtureLease 'probe-other'
        $forgedProbeLease = [pscustomobject]@{
            Path = $probeLease.Path; Serial = $probeLease.Serial; Stream = $probeLease.Stream
            OwnershipToken = [guid]::NewGuid().ToString('N')
        }
        foreach ($refusedLease in $null, $otherProbeLease, $forgedProbeLease) {
            $script:probeCalls = @()
            Assert-LeaseRejected { Invoke-ProbeAction @probeAction -Lease $refusedLease } 'A probe action ran without owning its serial.'
            Assert-Lease ($script:probeCalls.Count -eq 0) "A probe action without ownership reached the device: $($script:probeCalls -join ' | ')"
        }
        Remove-DeviceLease $otherProbeLease
        $script:probeCalls = @()
        $script:probeExpire = $probeLease
        Assert-LeaseRejected { Invoke-ProbeAction @probeAction -Lease $probeLease } 'A probe action carried on after its lease expired.'
        Assert-Lease ($script:probeCalls.Count -eq 1) "Probe commands after expiry reached the device: $($script:probeCalls -join ' | ')"
        $script:probeExpire = $null
        Assert-LeaseRejected { Remove-DeviceLease $probeLease } 'An expired probe lease authorized cleanup.'
        [IO.File]::Delete($probeLease.Path)

        # The probe scripts refuse a phone another task holds, and otherwise take its lease right
        # before their first command and give it back when they stop.
        $env:HUSHFEED_DEVICE_SERIAL = 'probe-tool'
        $toolLeasePath = Join-Path $pool 'probe-tool.json'
        $front = '-s probe-tool shell dumpsys activity activities'
        $mark = '-s probe-tool shell log -t HushfeedProbe hushfeed-probe-*'
        $read = '-s probe-tool logcat -d -s HushfeedProbe:V -v raw'
        $broadcast = '-s probe-tool shell am broadcast -a app.hushfeed.verification.PROBE -p com.zhiliaoapp.musically -e action'
        foreach ($tool in @(
                @{ Name = 'strip-hunt.ps1'; Arguments = @{ Rounds = 1 }; Output = 'round 1: first anchor fixture_anchor/1'
                    Commands = @($front, $front, $mark, "$broadcast stripkeys", $read) },
                @{ Name = 'record-markers.ps1'; Arguments = @{ Route = 'for-you'; Rounds = 1 }; Output = 'round 1: 0 new, *'
                    Commands = @($front, $mark, "$broadcast marker-corpus -e route for-you", $read,
                        $front, $mark, "$broadcast marker-corpus -e route for-you", $read) })) {
            $toolScript = Join-Path (Split-Path -Parent $PSScriptRoot) "tools/verification-probe/$($tool.Name)"
            $toolArguments = $tool.Arguments
            $otherTask = New-DeviceLease -LeaseDir $pool -Serial 'probe-tool' -Project 'fixture-project' `
                -ChatIdentity 'other-chat' -Purpose 'another test' -LeaseMinutes 1
            $contexts.Add($otherTask)
            Assert-LeaseRejected { & $toolScript @toolArguments } "$($tool.Name) ran while another task held the phone."
            Assert-Lease (-not [IO.File]::Exists($fakeLog) -and (Test-DeviceLease $otherTask)) "$($tool.Name) reached the phone or disturbed another task's lease."
            Remove-DeviceLease $otherTask
            $toolFailure = $null
            try { & $toolScript @toolArguments } catch { $toolFailure = $_.Exception.Message }
            Assert-Lease ($toolFailure -clike 'TikTok is not in front*') "$($tool.Name) did not reach its first command on a free phone: $toolFailure"
            $toolCommands = @([IO.File]::ReadAllLines($fakeLog) | ForEach-Object { $_.Trim() }) -join '|'
            Assert-Lease ($toolCommands -ceq '-s probe-tool shell dumpsys activity activities') "$($tool.Name) sent the wrong first command: $toolCommands"
            Assert-Lease (-not [IO.File]::Exists($toolLeasePath)) "$($tool.Name) kept its lease after stopping."
            [IO.File]::Delete($fakeLog)

            # With TikTok in front a round finishes: the probe answer comes back under the
            # script's own lease, which it renews each round and releases at the end.
            $frontFile = Join-Path $fakeBin 'front'
            [IO.File]::WriteAllText($frontFile, 'tiktok')
            try { $toolOutput = @(& $toolScript @toolArguments) }
            finally { [IO.File]::Delete($frontFile); [IO.File]::Delete((Join-Path $fakeBin 'marker')) }
            $sent = @([IO.File]::ReadAllLines($fakeLog) | ForEach-Object { $_.Trim() })
            $expected = @($tool.Commands)
            $sentInOrder = $sent.Count -eq $expected.Count
            for ($i = 0; $sentInOrder -and $i -lt $expected.Count; $i++) { $sentInOrder = $sent[$i] -clike $expected[$i] }
            Assert-Lease $sentInOrder "$($tool.Name) sent the wrong commands for one round: $($sent -join ' | ')"
            Assert-Lease (@($toolOutput | Where-Object { "$_" -clike $tool.Output }).Count -eq 1) "$($tool.Name) lost the probe answer: $($toolOutput -join ' | ')"
            Assert-Lease (-not [IO.File]::Exists($toolLeasePath)) "$($tool.Name) kept its lease after a full round."
            [IO.File]::Delete($fakeLog)
        }
    } finally {
        foreach ($name in $environmentBefore.Keys) {
            $value = $environmentBefore[$name]
            if ($null -eq $value) { $value = [NullString]::Value }
            [Environment]::SetEnvironmentVariable($name, $value, 'Process')
        }
    }
    Write-Host "[device-lease] $script:passed checks passed on PowerShell $($PSVersionTable.PSVersion)."
} finally {
    if ($null -ne $job) {
        Stop-Job -Job $job -ErrorAction SilentlyContinue
        Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
    }
    foreach ($context in $contexts) {
        if ($null -ne $context.Stream) { $context.Stream.Dispose() }
    }
    $resolved = [IO.Path]::GetFullPath($fixture)
    if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase) -or
        [IO.Path]::GetFileName($resolved) -notmatch '\Ahushfeed-device-lease-test-[a-f0-9]{32}\z') {
        throw 'Refusing cleanup outside the temporary lease fixture.'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
