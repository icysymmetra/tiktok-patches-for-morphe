# The probe answers in logcat under HushfeedProbe. The phones are shared, so the buffer is never
# cleared: a unique marker line under the same tag starts each broadcast's answer, and only the
# lines after its last copy are read.
#
# Every command also needs the caller's lease on that exact serial (scripts/device-lease.ps1),
# checked again before each one, so a lease that lapses or changes hands between the marker and
# the log read stops the action there.

. (Join-Path $PSScriptRoot '../../scripts/device-lease.ps1')

function Select-ProbeRun {
    param([string[]]$Lines, [Parameter(Mandatory = $true)][string]$Marker, [string]$Serial)
    $Lines = @($Lines)
    for ($i = $Lines.Count - 1; $i -ge 0; $i--) {
        if ("$($Lines[$i])".Trim() -eq $Marker) { return @($Lines | Select-Object -Skip ($i + 1)) }
    }
    throw "The log on $Serial no longer holds the start of this probe answer. Retry when the phone is quieter."
}

function Invoke-ProbeAction {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$Action,
        [Parameter(Mandatory = $true)][AllowNull()]$Lease,
        [int]$WaitMs = 1200,
        [string[]]$Extras = @(),
        # The fake-device fixtures name a command of their own here.
        [string]$Adb = 'adb'
    )
    $marker = "hushfeed-probe-$([guid]::NewGuid().ToString('N'))"
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell log -t HushfeedProbe $marker
    if ($LASTEXITCODE -ne 0) { throw "Could not mark logcat on $Serial before the $Action probe action." }
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial shell am broadcast -a app.hushfeed.verification.PROBE -p com.zhiliaoapp.musically `
        -e action $Action @Extras | Out-Null
    Start-Sleep -Milliseconds $WaitMs
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    $lines = @(& $Adb -s $Serial logcat -d -s HushfeedProbe:V -v raw)
    if ($LASTEXITCODE -ne 0) { throw "Could not read logcat on $Serial after the $Action probe action." }
    Select-ProbeRun -Lines $lines -Marker $marker -Serial $Serial
}
