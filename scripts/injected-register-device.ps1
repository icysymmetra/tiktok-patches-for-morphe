# The phones are shared, so every command here runs under the caller's lease on that exact serial
# (scripts/device-lease.ps1), checked again before each one.
. (Join-Path $PSScriptRoot 'device-lease.ps1')

function Invoke-HushfeedAdbCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [Parameter(Mandatory = $true)][string]$Serial,
        [scriptblock]$Invoker
    )

    Assert-DeviceLease -Lease $Lease -Serial $Serial
    # The lease covers one phone, so the command has to name that same one.
    if ($Arguments.Count -lt 2 -or $Arguments[0] -cne '-s' -or $Arguments[1] -cne $Serial) {
        throw "The ADB command doesn't target the leased serial. No device command was sent."
    }
    if ($Invoker) {
        $result = & $Invoker $Adb $Arguments
        if ($null -eq $result -or $null -eq $result.ExitCode) {
            throw 'The ADB invoker returned no exit code.'
        }
        return [pscustomobject]@{
            ExitCode = [int]$result.ExitCode
            Output = @($result.Output | ForEach-Object { "$_" })
        }
    }

    $PSNativeCommandUseErrorActionPreference = $false
    $output = @(& $Adb @Arguments 2>&1 | ForEach-Object { "$_" })
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = $output }
}

function Format-HushfeedAdbFailure {
    param([string]$Message, $Result)

    $detail = @($Result.Output | Select-Object -First 3) -join '; '
    $suffix = if ([string]::IsNullOrWhiteSpace($detail)) { '' } else { " Output: $detail" }
    "$Message (ADB exit $($Result.ExitCode)).$suffix"
}

function Invoke-AndroidVerifierTally {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Local,
        [Parameter(Mandatory = $true)][string]$Label,
        [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [scriptblock]$AdbInvoker
    )

    if ($Label -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$') {
        throw "Invalid verifier label: $Label"
    }
    $device = @{ Adb = $Adb; Invoker = $AdbInvoker; Lease = $Lease; Serial = $Serial }

    $remote = "/data/local/tmp/hushfeed-verify-$Label.apk"
    $directory = "/data/local/tmp/hushfeed-verify-$Label"
    $primaryFailure = $null
    try {
        $push = Invoke-HushfeedAdbCommand @device `
            -Arguments @('-s', $Serial, 'push', $Local, $remote)
        if ($push.ExitCode -ne 0) {
            throw (Format-HushfeedAdbFailure -Message "Could not push $Label to $Serial" -Result $push)
        }

        $setup = Invoke-HushfeedAdbCommand @device `
            -Arguments @('-s', $Serial, 'shell', "rm -rf $directory && mkdir -p $directory")
        if ($setup.ExitCode -ne 0) {
            throw (Format-HushfeedAdbFailure `
                -Message "Could not prepare the verifier output directory for $Label on $Serial" `
                -Result $setup)
        }

        # The phones are shared, so the log buffer is never cleared. A unique marker line starts
        # this run's part of it, and only the lines after it are read.
        $marker = "hushfeed-verify-$Label-$([guid]::NewGuid().ToString('N'))"
        $mark = Invoke-HushfeedAdbCommand @device `
            -Arguments @('-s', $Serial, 'shell', "log -t HushfeedVerify $marker")
        if ($mark.ExitCode -ne 0) {
            throw (Format-HushfeedAdbFailure `
                -Message "Could not mark logcat on $Serial before verifying $Label" `
                -Result $mark)
        }

        $dexCommand = "dex2oat64 --dex-file=$remote --oat-file=$directory/out.oat " +
            "--output-vdex=$directory/out.vdex --instruction-set=arm64 " +
            '--compiler-filter=verify --runtime-arg -Xmx1024m -j4; echo exit=$?'
        $dex = Invoke-HushfeedAdbCommand @device `
            -Arguments @('-s', $Serial, 'shell', $dexCommand)
        if ($dex.ExitCode -ne 0) {
            throw (Format-HushfeedAdbFailure `
                -Message "Could not run dex2oat on $Label on $Serial" -Result $dex)
        }
        $dexExit = $null
        foreach ($line in $dex.Output) {
            if ($line -match 'exit=(-?\d+)') { $dexExit = [int]$Matches[1] }
        }
        if ($null -eq $dexExit) {
            throw "dex2oat on $Label reported no exit code; the shell did not finish."
        }
        if ($dexExit -ne 0) { throw "dex2oat on $Label exited $dexExit." }

        $logResult = Invoke-HushfeedAdbCommand @device `
            -Arguments @('-s', $Serial, 'logcat', '-d')
        if ($logResult.ExitCode -ne 0) {
            throw (Format-HushfeedAdbFailure `
                -Message "Could not read logcat on $Serial after verifying $Label" `
                -Result $logResult)
        }
        $logLines = @($logResult.Output)
        $markedAt = -1
        for ($i = $logLines.Count - 1; $i -ge 0; $i--) {
            if ("$($logLines[$i])".Contains($marker)) { $markedAt = $i; break }
        }
        if ($markedAt -lt 0) {
            throw "The log on $Serial no longer holds the start of the $Label run, so its verifier messages can't be counted. Retry when the phone is quieter."
        }
        $runLines = @($logLines | Select-Object -Skip ($markedAt + 1))

        # The method in the verifier line is its identity. Timestamps and process IDs differ
        # between runs and are stripped. Counts remain significant.
        $messages = @($runLines |
            Where-Object { $_ -match 'dex2oat' } |
            Where-Object { $_ -match '(failed lock verification|Verification error|Rejecting class|VerifyError)' } |
            ForEach-Object { ($_ -replace '^.*dex2oat[0-9]*:\s*', '').Trim() })
        $tally = [System.Collections.Generic.Dictionary[string, int]]::new(
            [System.StringComparer]::Ordinal)
        foreach ($message in $messages) {
            if ($tally.ContainsKey($message)) { $tally[$message]++ }
            else { $tally.Add($message, 1) }
        }
        return $tally
    } catch {
        $primaryFailure = $_
        throw
    } finally {
        $cleanupFailures = [System.Collections.Generic.List[string]]::new()
        foreach ($remotePath in @($directory, $remote)) {
            try {
                $cleanup = Invoke-HushfeedAdbCommand @device `
                    -Arguments @('-s', $Serial, 'shell', "rm -rf $remotePath")
                if ($cleanup.ExitCode -ne 0) {
                    $cleanupFailures.Add((Format-HushfeedAdbFailure `
                        -Message "Could not remove $remotePath" -Result $cleanup))
                }
            } catch {
                $cleanupFailures.Add("Could not remove ${remotePath}: $($_.Exception.Message)")
            }
        }
        if ($cleanupFailures.Count -ne 0) {
            $message = $cleanupFailures -join ' '
            if ($null -ne $primaryFailure) {
                Write-Warning -WarningAction Continue `
                    "Verifier cleanup also failed after the original error. $message"
            } else {
                throw "Verifier cleanup failed. $message"
            }
        }
    }
}
