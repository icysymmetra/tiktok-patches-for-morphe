<#
.SYNOPSIS
    Tallies the kinds of strip above a caption and banner under a video the test phone is served.

.DESCRIPTION
    A strip above a caption (a place, an effect, a film, a link to another app) is one of the
    video's anchors, and the bar under a video is one of its banners. TikTok names each kind with
    a component key, and a switch that hides one kind needs that key. Guessing it from TikTok's
    config is no substitute: the config lists over a hundred and fifty of them.

    Each round asks the probe's stripkeys action for the keys of the video in front, then swipes
    to the next one, only while TikTok is in front. Keys are identifiers only, never what a strip
    says. The tally at the end says how often each key came up; an anchor also carries TikTok's
    type number for it.

    The probe reads a video's lists as the app holds them, after Hushfeed's own switches took their
    kinds out, so a banner a switch removes (the search banner under Hide bottom search bar, for
    one) never shows up here. The export's CaptionStrip and BottomBanner lines count every kind
    before any switch acts, so read the probe's report action after a hunt as well.

    With -Shots, the first video of each new key other than a place is screenshotted to
    work/strip-hunt/, so a key can be matched with what its strip showed. The pictures show other
    people's videos, and work/ never goes into git.

    The phones are shared, so the hunt takes the device lease (scripts/device-lease.ps1) on
    HUSHFEED_DEVICE_SERIAL before its first command, checks it before every one, and releases it
    at the end. It refuses to start while another task holds that phone.

.EXAMPLE
    Load the probe (tools/verification-probe/build.ps1, then its instrument line), open the For
    You feed in TikTok, then:

    tools/verification-probe/strip-hunt.ps1 -Rounds 150 -Shots
#>
param(
    [int]$Rounds = 100,
    # x1,y1,x2,y2 in panel pixels. The default moves a full-screen feed up one video.
    [string]$Swipe = '540,1750,540,650',
    [int]$SettleMs = 2000,
    [switch]$Shots,
    [string]$LeaseDir = $env:HUSHFEED_DEVICE_LEASE_DIR,
    [string]$ChatIdentity = $env:HUSHFEED_CHAT_IDENTITY,
    [ValidateRange(1, 30)][int]$LeaseMinutes = 30
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
. (Join-Path $root 'scripts/device-lease.ps1')
. (Join-Path $PSScriptRoot 'probe-log.ps1')
$serial = $env:HUSHFEED_DEVICE_SERIAL
if (-not $serial) { throw 'Set HUSHFEED_DEVICE_SERIAL to the serial of the test phone this machine may drive.' }
if (-not $LeaseDir) { throw 'The hunt requires -LeaseDir or HUSHFEED_DEVICE_LEASE_DIR for the shared device pool.' }
if (-not $ChatIdentity) { throw 'The hunt requires -ChatIdentity or HUSHFEED_CHAT_IDENTITY.' }
$swipePoints = @($Swipe -split ',' | ForEach-Object { [int]$_.Trim() })
if ($swipePoints.Count -ne 4) { throw "-Swipe needs four numbers, x1,y1,x2,y2: $Swipe" }
$shotDir = Join-Path $root 'work/strip-hunt'
if ($Shots) { New-Item -ItemType Directory -Force $shotDir | Out-Null }

function Assert-TikTok {
    Assert-DeviceLease -Lease $deviceLease -Serial $serial
    $top = [string](& adb -s $serial shell dumpsys activity activities | Select-String 'topResumedActivity' | Select-Object -First 1)
    if ($top -notmatch 'com\.zhiliaoapp\.musically') { throw "TikTok is not in front: $top" }
}

function Invoke-Phone([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments) {
    Assert-DeviceLease -Lease $deviceLease -Serial $serial
    $windowsScript = [IO.Path]::GetFullPath((Join-Path $root 'scripts/phone.sh')).Replace('\', '/')
    if ($windowsScript -notmatch '^([A-Za-z]):/(.+)$') {
        throw "Could not translate scripts/phone.sh for the guarded input: $windowsScript"
    }
    $script = '/mnt/' + $Matches[1].ToLowerInvariant() + '/' + $Matches[2]
    # phone.sh rereads the lease before each adb call it makes.
    & wsl.exe env "PHONE_SERIAL=$serial" "HUSHFEED_DEVICE_SERIAL=$serial" PHONE_SCALE=1 `
        "HUSHFEED_LEASE_FILE=$($deviceLease.Path)" "HUSHFEED_LEASE_TOKEN=$($deviceLease.OwnershipToken)" `
        bash $script @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Guarded phone input failed: $($Arguments -join ' ')" }
}

# The keys of the video in front, as "anchor <key>/<type>" and "banner <key>".
function Get-Keys {
    $line = [string](@(Invoke-ProbeAction -Serial $serial -Action stripkeys -Lease $deviceLease) |
        Where-Object { $_ -like 'ok stripkeys *' } | Select-Object -Last 1)
    if ($line -notmatch 'anchors=\[(.*?)\] banners=\[(.*?)\]') { return @() }
    $anchors = @($Matches[1] -split ', ' | Where-Object { $_ } | ForEach-Object { "anchor $_" })
    $banners = @($Matches[2] -split ', ' | Where-Object { $_ } | ForEach-Object { "banner $_" })
    return $anchors + $banners
}

$deviceLease = New-DeviceLease -LeaseDir $LeaseDir -Serial $serial -Project 'hushfeed' `
    -ChatIdentity $ChatIdentity -Purpose 'verification probe strip hunt' -LeaseMinutes $LeaseMinutes
try {
    Assert-TikTok
    $tally = @{}
    for ($round = 1; $round -le $Rounds; $round++) {
        # A long hunt outlasts one lease term, so each round extends it (after checking it's ours).
        Update-DeviceLease -Lease $deviceLease -LeaseMinutes $LeaseMinutes
        Assert-TikTok
        foreach ($key in @(Get-Keys)) {
            $tally[$key] = 1 + [int]$tally[$key]
            if ($tally[$key] -gt 1) { continue }
            "round ${round}: first $key"
            if ($Shots -and $key -notlike 'anchor anchor_poi/*') {
                $picture = Join-Path $shotDir (($key -replace '[^a-z0-9_]', '-') + '.png')
                Assert-DeviceLease -Lease $deviceLease -Serial $serial
                & adb -s $serial shell screencap -p /sdcard/hushfeed-strip-hunt.png
                Assert-DeviceLease -Lease $deviceLease -Serial $serial
                & adb -s $serial pull /sdcard/hushfeed-strip-hunt.png $picture 2>&1 | Out-Null
                if ($LASTEXITCODE -ne 0) { throw "Could not pull the screenshot for $key" }
                Assert-DeviceLease -Lease $deviceLease -Serial $serial
                & adb -s $serial shell rm /sdcard/hushfeed-strip-hunt.png
            }
        }
        if ($round -lt $Rounds) {
            Invoke-Phone swipe $swipePoints[0] $swipePoints[1] $swipePoints[2] $swipePoints[3] 350 0
            Start-Sleep -Milliseconds $SettleMs
        }
    }
    "tally after $Rounds videos:"
    $tally.GetEnumerator() | Sort-Object Value -Descending | ForEach-Object { '  {0} x{1}' -f $_.Key, $_.Value }
} finally {
    Remove-DeviceLease -Lease $deviceLease -WarnOnFailure
}
