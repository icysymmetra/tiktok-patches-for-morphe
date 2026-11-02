<#
.SYNOPSIS
    Exercise shared patch-report, patch-target, Java-resolution and device replacement contracts.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'patch-report.ps1')
. (Join-Path $PSScriptRoot 'device-install.ps1')
. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Action, [string]$Pattern, [string]$Message)
    try {
        & $Action
    } catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "$Message Unexpected error: $($_.Exception.Message)"
    }
    throw "$Message No error was raised."
}

# --- phone.sh foreground parser -------------------------------------------------------------
#
# Android can report focused windows for several displays. The input guard reads display 0 only,
# which is where an unqualified adb input command lands, and refuses a missing or null focus.
$bash = (Get-Command bash -ErrorAction Stop).Source
$bashHost = $bash
$bashArguments = @('-lc')
$phoneScript = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot 'phone.sh')).Path
if ($env:OS -eq 'Windows_NT') {
    $bashHost = (Get-Command wsl.exe -ErrorAction Stop).Source
    $bashArguments = @('--exec', '/bin/bash', '-lc')
    $escapedPhoneScript = $phoneScript.Replace("'", "'\''")
    $phoneScript = (& $bashHost @bashArguments "wslpath -a -- '$escapedPhoneScript'").Trim()
    if ($LASTEXITCODE -ne 0 -or -not $phoneScript) {
        throw 'Could not translate phone.sh to a path visible to bash.'
    }
}
$escapedPhoneScript = $phoneScript.Replace("'", "'\''")
$phoneParserCommand = "PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 ADB=/not-used " +
    "PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '$escapedPhoneScript' parse_top"

function Invoke-PhoneTopParser {
    param([Parameter(Mandatory)][string]$Fixture, [switch]$Popup)
    $parserCommand = if ($Popup) { $phoneParserCommand -replace ' parse_top$', ' parse_popup_top' } else { $phoneParserCommand }
    # Windows PowerShell 5.1 prefixes piped native stdin with a UTF-8 byte-order mark, so the fixture
    # travels inside the command as base64 and reaches the parser byte for byte in both shells.
    $encoded = [Convert]::ToBase64String([Text.UTF8Encoding]::new($false).GetBytes($Fixture.Replace("`r`n", "`n").TrimEnd("`n") + "`n"))
    $output = @(& $bashHost @bashArguments "printf %s $encoded | base64 -d | $parserCommand" 2> $null)
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output   = ($output -join "`n").Trim()
    }
}

$defaultDisplayTop = Invoke-PhoneTopParser @'
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=55 (organized)
  mCurrentFocus=Window{1111111 u0 com.zhiliaoapp.musically/.OtherDisplayActivity}
  Display: mDisplayId=0
  mCurrentFocus=Window{2222222 u0 com.example.app/.MainActivity}
'@
Assert-True ($defaultDisplayTop.ExitCode -eq 0 -and
    $defaultDisplayTop.Output -eq 'com.example.app/.MainActivity') `
    'phone.sh trusted TikTok on a display that adb input does not target.'

$wrappedTop = Invoke-PhoneTopParser @'
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0
  mCurrentFocus=Window{ddb77cb u0
    com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity}
  Display: mDisplayId=55 (organized)
  mCurrentFocus=Window{3333333 u0 com.example.app/.OtherActivity}
'@
Assert-True ($wrappedTop.ExitCode -eq 0 -and $wrappedTop.Output -eq
    'com.zhiliaoapp.musically/com.ss.android.ugc.aweme.main.MainActivity') `
    'phone.sh did not parse the wrapped focused window on display 0.'

$nullTop = Invoke-PhoneTopParser @'
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0
  mCurrentFocus=null
'@
Assert-True ($nullTop.ExitCode -ne 0 -and -not $nullTop.Output) `
    'phone.sh accepted display 0 with no focused window.'

$missingTop = Invoke-PhoneTopParser 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)'
Assert-True ($missingTop.ExitCode -ne 0 -and -not $missingTop.Output) `
    'phone.sh accepted a dump with no default display.'

$historicalTop = Invoke-PhoneTopParser @'
WINDOW MANAGER LAST ANR (dumpsys window lastanr)
  Display: mDisplayId=0
  mCurrentFocus=Window{old123 u0 com.zhiliaoapp.musically/.MainActivity}
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=0
  mCurrentFocus=Window{new456 u0 com.example.other/.MainActivity}
'@
Assert-True ($historicalTop.ExitCode -eq 0 -and $historicalTop.Output -eq 'com.example.other/.MainActivity') `
    'phone.sh trusted a historical foreground instead of the current display.'

$ownedPopupFixture = @'
WINDOW MANAGER WINDOWS (dumpsys window windows)
  Window #0 Window{aaa111 u0 PopupWindow:abc123}:
    mDisplayId=0 taskId=42
    mOwnerUid=10434 showForAllUsers=false package=com.zhiliaoapp.musically appop=NONE
    mAttrs={(0,199)(1080xwrap) ty=APPLICATION_PANEL fmt=TRANSPARENT}
    mActivityRecord=ActivityRecord{bbb222 u0
      com.zhiliaoapp.musically/.HostActivity t42}
  Window #1 Window{ccc333 u0 com.zhiliaoapp.musically/.HostActivity}:
    mDisplayId=0 taskId=42
    mOwnerUid=10434 package=com.zhiliaoapp.musically
    mActivityRecord=ActivityRecord{bbb222 u0 com.zhiliaoapp.musically/.HostActivity t42}
WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
  Display: mDisplayId=55 (organized)
    mCurrentFocus=Window{fff555 u0 com.example.other/.Activity}
    mFocusedApp=ActivityRecord{fff666 u0 com.example.other/.Activity t55}
  Display: mDisplayId=0
    mCurrentFocus=Window{aaa111 u0 PopupWindow:abc123}
    mFocusedApp=ActivityRecord{bbb222 u0 com.zhiliaoapp.musically/.HostActivity t42}
'@
$ownedPopup = Invoke-PhoneTopParser -Fixture $ownedPopupFixture -Popup
Assert-True ($ownedPopup.ExitCode -eq 0 -and $ownedPopup.Output -eq 'com.zhiliaoapp.musically/.HostActivity') `
    'phone.sh refused a popup with the same application token, package and UID as its focused owner.'
$historicalWindows = $ownedPopupFixture.Replace('WINDOW MANAGER WINDOWS (dumpsys window windows)', 'WINDOW MANAGER LAST ANR (dumpsys window lastanr)').Replace('WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)', 'HISTORICAL DISPLAY CONTENTS').Replace('mOwnerUid=10434', 'mOwnerUid=99999')
$afterHistorical = Invoke-PhoneTopParser -Fixture ($historicalWindows + "`n" + $ownedPopupFixture) -Popup
Assert-True ($afterHistorical.ExitCode -eq 0 -and $afterHistorical.Output -eq 'com.zhiliaoapp.musically/.HostActivity') `
    'phone.sh confused the last ANR snapshot with current popup ownership.'
$onlyHistorical = Invoke-PhoneTopParser -Fixture $historicalWindows -Popup
Assert-True ($onlyHistorical.ExitCode -ne 0 -and -not $onlyHistorical.Output) `
    'phone.sh trusted historical windows without a current window list and display.'
$unsafePopups = @(
    $ownedPopupFixture.Replace('mCurrentFocus=Window{aaa111 u0 PopupWindow:abc123}', 'mCurrentFocus=null'),
    $ownedPopupFixture.Replace('mCurrentFocus=Window{aaa111 u0 PopupWindow:abc123}', 'mCurrentFocus=Window{unknown u0 PopupWindow:abc123}'),
    $ownedPopupFixture.Replace('mCurrentFocus=Window{aaa111 u0 PopupWindow:abc123}', 'mCurrentFocus=Window{aaa111 u0 SystemOverlay}'),
    $ownedPopupFixture.Replace('mOwnerUid=10434 showForAllUsers=false', 'mOwnerUid=10435 showForAllUsers=false'),
    $ownedPopupFixture.Replace('package=com.zhiliaoapp.musically appop=NONE', 'package=com.example.other appop=NONE'),
    $ownedPopupFixture.Replace('mDisplayId=0 taskId=42', 'mDisplayId=55 taskId=42'),
    $ownedPopupFixture.Replace('ty=APPLICATION_PANEL', 'ty=APPLICATION_OVERLAY'),
    $ownedPopupFixture.Replace('com.zhiliaoapp.musically/.HostActivity t42}', 'com.zhiliaoapp.musically/.HostActivity t43}').Replace('mFocusedApp=ActivityRecord{bbb222 u0 com.zhiliaoapp.musically/.HostActivity t43}', 'mFocusedApp=ActivityRecord{bbb222 u0 com.zhiliaoapp.musically/.HostActivity t42}'),
    $ownedPopupFixture.Replace('mFocusedApp=ActivityRecord{bbb222 u0 com.zhiliaoapp.musically/.HostActivity t42}', 'mFocusedApp=null'),
    $ownedPopupFixture.Replace('Window #1 Window{ccc333 u0 com.zhiliaoapp.musically/.HostActivity}', 'Window #1 Window{ccc333 u0 com.example.other/.Activity}'),
    $ownedPopupFixture.Replace('Window #1 Window{ccc333', 'Window #1 Window{aaa111'),
    $ownedPopupFixture.Replace('Display: mDisplayId=0', 'Display: mDisplayId=9')
)
foreach ($fixture in $unsafePopups) {
    $unsafePopup = Invoke-PhoneTopParser -Fixture $fixture -Popup
    Assert-True ($unsafePopup.ExitCode -ne 0 -and -not $unsafePopup.Output) `
        'phone.sh accepted a popup without a uniquely matched foreground application owner.'
}

$phoneSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'phone.sh') -Raw
foreach ($verb in @('tap', 'swipe', 'keyevent', 'text')) {
    Assert-True ($phoneSource -match "shell input -d 0 $verb") `
        "phone.sh guards display 0 but sends $verb input to another display."
}

function Invoke-PhoneOpenUrl {
    param([Parameter(Mandatory)][string]$Url)
    $escapedUrl = $Url.Replace("'", "'\''")
    $savedUrlEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $bashHost @bashArguments ("PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 ADB=/not-used " +
            "PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '$escapedPhoneScript' open_url '$escapedUrl'") 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedUrlEAP
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = ($output -join "`n") }
}

# The URL reaches the phone's shell unquoted, so a TikTok prefix alone is not enough.
foreach ($url in @('https://example.com/', 'https://www.tiktok.com/@a;reboot',
        'https://www.tiktok.com/$(id)', 'https://www.tiktok.com/@a/video/1 x')) {
    $refused = Invoke-PhoneOpenUrl $url
    Assert-True ($refused.ExitCode -eq 2 -and $refused.Output -like '*only accepts https://www.tiktok.com/*') `
        "phone.sh accepted $url for device navigation."
}
# Lease files for the runs that reach the fake adb. Written here rather than in bash, whose command
# lines can't carry the JSON's double quotes through Windows PowerShell 5.1, and handed over as
# Windows paths, which phone.sh turns into its own.
$phoneLeaseDir = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-phone-lease-' + [guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($phoneLeaseDir)
function Write-PhoneLeaseFixture([string]$Name, [string]$Serial, [string]$Expires, [switch]$Pretty) {
    $record = [ordered]@{
        schemaVersion = 1; serial = $Serial; project = 'hushfeed'; chatIdentity = 'contract'
        purpose = 'phone.sh lease contract'; ownershipToken = 'goodtoken'
        acquiredUtc = [DateTime]::UtcNow.ToString('o'); expiresUtc = $Expires
    }
    $path = Join-Path $phoneLeaseDir $Name
    [IO.File]::WriteAllText($path, ($record | ConvertTo-Json -Compress:(-not $Pretty)), [Text.UTF8Encoding]::new($false))
    return $path.Replace("'", "'\''")
}
$validPhoneLease = Write-PhoneLeaseFixture 'valid.json' 'TESTPHONE01' ([DateTime]::UtcNow.AddHours(1).ToString('o'))
$expiredPhoneLease = Write-PhoneLeaseFixture 'expired.json' 'TESTPHONE01' ([DateTime]::UtcNow.AddMinutes(-1).ToString('o')) -Pretty
$otherPhoneLease = Write-PhoneLeaseFixture 'other.json' 'OTHERPHONE02' ([DateTime]::UtcNow.AddHours(1).ToString('o'))
$absentPhoneLease = (Join-Path $phoneLeaseDir 'absent.json').Replace("'", "'\''")

# A plain video link has to reach am start as one intact argument, or the refusals above prove
# nothing. The fake adb reports TikTok in front on display 0 and records what am start was given.
# No double quotes: Windows PowerShell 5.1 splits a native argument at them.
$openUrlCommand = (@'
fixture=$(mktemp -d)
trap 'rm -rf $fixture' EXIT
cat > $fixture/adb <<'FAKE'
#!/bin/sh
case $* in
    *dumpsys*) printf 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n  Display: mDisplayId=0\n  mCurrentFocus=Window{1 u0 com.zhiliaoapp.musically/.MainActivity}\n';;
    *start*) printf '%s\n' $@ > ${0%/*}/am-start;;
esac
FAKE
chmod +x $fixture/adb
PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 ADB=$fixture/adb \
    HUSHFEED_LEASE_FILE='__LEASE__' HUSHFEED_LEASE_TOKEN=goodtoken \
    PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '__PHONE_SCRIPT__' open_url 'https://www.tiktok.com/@creator/video/123' 0
cat $fixture/am-start
'@).Replace('__PHONE_SCRIPT__', $escapedPhoneScript).Replace('__LEASE__', $validPhoneLease)
$openedUrl = @(& $bashHost @bashArguments $openUrlCommand 2> $null)
Assert-True ($LASTEXITCODE -eq 0 -and ($openedUrl -join "`n") -like
    "*-d`nhttps://www.tiktok.com/@creator/video/123`n-p`ncom.zhiliaoapp.musically*") `
    'phone.sh did not pass a plain TikTok video link to am start intact.'

$findAdbCommand = (@'
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
printf '#!/bin/sh\nexit 0\n' > "$fixture/adb.exe"
chmod +x "$fixture/adb.exe"
PATH="$fixture:/usr/bin:/bin" PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 \
    PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '__PHONE_SCRIPT__' find_adb
'@).Replace('__PHONE_SCRIPT__', $escapedPhoneScript)
$resolvedWindowsAdb = @(& $bashHost @bashArguments $findAdbCommand 2> $null)
Assert-True ($LASTEXITCODE -eq 0 -and ($resolvedWindowsAdb -join "`n").Trim() -like '*/adb.exe') `
    'phone.sh did not resolve adb.exe from an interoperable Windows path.'

# The device guard. The one phone this machine may drive comes from HUSHFEED_DEVICE_SERIAL rather
# than a serial written into the script, and anything else, or no named phone at all, is refused
# before adb is looked for.
# Windows PowerShell 5.1 turns a native command's stderr into a terminating error under Stop
# even when 2>&1 redirects it. Relax for the two calls that expect stderr output.
$savedEAP = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $otherPhone = @(& $bashHost @bashArguments ("PHONE_SERIAL=OTHERPHONE02 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 ADB=/not-used " +
        "PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '$escapedPhoneScript' top") 2>&1)
    $otherPhoneExit = $LASTEXITCODE
    $unnamed = @(& $bashHost @bashArguments ("env -u HUSHFEED_DEVICE_SERIAL PHONE_SERIAL=TESTPHONE01 ADB=/not-used " +
        "PHONE_SHOTS=/tmp/hushfeed-phone-parser-contract '$escapedPhoneScript' top") 2>&1)
    $unnamedExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedEAP
}
Assert-True ($otherPhoneExit -eq 2 -and ($otherPhone -join "`n") -like '*REFUSED*') `
    'phone.sh drove a device other than the one HUSHFEED_DEVICE_SERIAL names.'
Assert-True ($unnamedExit -ne 0 -and ($unnamed -join "`n") -like '*HUSHFEED_DEVICE_SERIAL*') `
    'phone.sh ran with no test phone named in HUSHFEED_DEVICE_SERIAL.'

# The device lease. Every run below reaches for adb through a fake one that writes down each
# call, so a refused run has to leave that list empty. The held lease is a real one from
# device-lease.ps1, whose open handle shares reading alone, so phone.sh has to read through it.
$heldPhoneLease = New-DeviceLease -LeaseDir (Join-Path $phoneLeaseDir 'held') -Serial 'TESTPHONE01' -Project 'hushfeed' `
    -ChatIdentity 'contract' -Purpose 'phone.sh held lease contract' -LeaseMinutes 5
try {
    $leaseRunsCommand = (@'
fixture=$(mktemp -d)
trap 'rm -rf $fixture' EXIT
cat > $fixture/adb <<'FAKE'
#!/bin/sh
echo $* >> ${0%/*}/calls
case $* in
    *dumpsys*) printf 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)\n  Display: mDisplayId=0\n  mCurrentFocus=Window{1 u0 com.zhiliaoapp.musically/.MainActivity}\n';;
esac
FAKE
chmod +x $fixture/adb
run() {
    name=$1; lease=$2; token=$3; verb=$4
    rm -f $fixture/calls
    if [ $lease = none ]; then
        out=$(env -u HUSHFEED_LEASE_FILE -u HUSHFEED_LEASE_TOKEN PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 \
            ADB=$fixture/adb PHONE_SHOTS=$fixture/shots '__PHONE_SCRIPT__' $verb 1 2>&1)
    else
        out=$(env HUSHFEED_LEASE_FILE=$lease HUSHFEED_LEASE_TOKEN=$token PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 \
            ADB=$fixture/adb PHONE_SHOTS=$fixture/shots '__PHONE_SCRIPT__' $verb 1 2>&1)
    fi
    code=$?
    calls=0
    [ -f $fixture/calls ] && calls=$(( $(wc -l < $fixture/calls) ))
    case $out in *'REFUSED: no device lease'*) refused=1;; *) refused=0;; esac
    echo lease-run $name $code $calls $refused
}
run none none none top
run absent '__ABSENT__' goodtoken top
run foreign '__VALID__' othertoken top
run expired '__EXPIRED__' goodtoken top
run otherserial '__OTHER__' goodtoken top
run foreignshot '__VALID__' othertoken shot
run foreigntap '__VALID__' othertoken tap
run valid '__VALID__' goodtoken top
run held '__HELD__' __HELDTOKEN__ top
'@).Replace('__PHONE_SCRIPT__', $escapedPhoneScript).Replace('__ABSENT__', $absentPhoneLease).
        Replace('__VALID__', $validPhoneLease).Replace('__EXPIRED__', $expiredPhoneLease).
        Replace('__OTHER__', $otherPhoneLease).Replace('__HELD__', $heldPhoneLease.Path.Replace("'", "'\''")).
        Replace('__HELDTOKEN__', $heldPhoneLease.OwnershipToken)
    $savedLeaseEAP = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $leaseRuns = @(& $bashHost @bashArguments $leaseRunsCommand 2> $null)
    } finally {
        $ErrorActionPreference = $savedLeaseEAP
    }
} finally {
    Remove-DeviceLease -Lease $heldPhoneLease
}
$leaseResults = @{}
foreach ($line in $leaseRuns) {
    $fields = ([string]$line).Trim() -split ' '
    if ($fields.Count -eq 5 -and $fields[0] -eq 'lease-run') { $leaseResults[$fields[1]] = $fields[2..4] -join ' ' }
}
foreach ($refusal in @('none', 'absent', 'foreign', 'expired', 'otherserial', 'foreignshot', 'foreigntap')) {
    Assert-True ($leaseResults[$refusal] -eq '2 0 1') `
        "phone.sh reached adb or didn't refuse without a lease of its own ($refusal): exit, adb calls, refused = $($leaseResults[$refusal])"
}
Assert-True ($leaseResults['valid'] -eq '0 1 0') "phone.sh refused its own lease: $($leaseResults['valid'])"
Assert-True ($leaseResults['held'] -eq '0 1 0') `
    "phone.sh couldn't read a lease device-lease.ps1 holds open: $($leaseResults['held'])"
Assert-True (([regex]::Matches($phoneSource, '"\$ADB" -s')).Count -eq 2) `
    'phone.sh calls adb outside adb_call and adb_quiet, which check the lease first.'
foreach ($caller in @('tools/verification-probe/strip-hunt.ps1', 'tools/verification-probe/record-markers.ps1')) {
    $callerSource = Get-Content -LiteralPath (Join-Path $Root $caller) -Raw
    Assert-True ($callerSource -match 'HUSHFEED_LEASE_FILE=\$\(\$deviceLease\.Path\)' -and
        $callerSource -match 'HUSHFEED_LEASE_TOKEN=\$\(\$deviceLease\.OwnershipToken\)') `
        "$caller runs phone.sh without handing it the device lease."
}

# A wslpath that fails should refuse the path rather than creating a stray directory.
$wslpathFailCommand = (@'
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
printf '#!/bin/sh\nexit 1\n' > "$fixture/wslpath"
chmod +x "$fixture/wslpath"
out=$(PATH="$fixture:/usr/bin:/bin" PHONE_SERIAL=TESTPHONE01 HUSHFEED_DEVICE_SERIAL=TESTPHONE01 ADB=/not-used PHONE_SHOTS=/tmp/hushfeed-wslpath-contract bash -c '. "__PHONE_SCRIPT__"; normalise_path "C:\\repos\\test"' 2>/dev/null)
exit_code=$?
[ "$exit_code" -ne 0 ] && [ -z "$out" ]
'@).Replace('__PHONE_SCRIPT__', $escapedPhoneScript)
$savedEAP2 = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $bashHost @bashArguments $wslpathFailCommand 2>$null
    $wslpathExit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $savedEAP2
}
Assert-True ($wslpathExit -eq 0) `
    'phone.sh passed through a Windows path when wslpath was present but failed.'
Remove-Item -LiteralPath $phoneLeaseDir -Recurse -Force

Write-Host '[scripts] guarded phone foreground parser contracts passed'

$catalog = Get-Content -LiteralPath (Join-Path $Root 'patches-list.json') -Raw | ConvertFrom-Json
$target = Get-PatchTarget -PatchList $catalog
Assert-True ($target.PackageName -eq 'com.zhiliaoapp.musically') 'The catalog package was not resolved.'
$stockTarget = [pscustomobject]@{ package = 'com.zhiliaoapp.musically'; versionName = '47.4.3' }
$normalTarget = Resolve-PatchVerificationTarget -Stock $stockTarget -Target $target
Assert-True (-not $normalTarget.Forced -and -not $normalTarget.Probe) 'Declared support became a qualification probe.'
$asiaTarget = [pscustomobject]@{ package = 'com.ss.android.ugc.trill'; versionName = '47.0.3' }
Assert-Throws { Resolve-PatchVerificationTarget -Stock $asiaTarget -Target $target -Force } `
    '*not the catalog*' 'Force alone allowed an undeclared package.'
Assert-Throws { Resolve-PatchVerificationTarget -Stock $asiaTarget -Target $target -ProbePackage $asiaTarget.package } `
    '*requires -Force*' 'A package probe did not require an explicit force.'
Assert-Throws { Resolve-PatchVerificationTarget -Stock $stockTarget -Target $target -Force -ProbePackage $asiaTarget.package } `
    '*not the requested probe*' 'A package probe trusted a different APK.'
Assert-Throws { Resolve-PatchVerificationTarget -Stock $stockTarget -Target $target -Force -ProbePackage $stockTarget.package } `
    '*must name an undeclared package*' 'A package probe mislabeled supported input.'
$qualifiedTarget = Resolve-PatchVerificationTarget -Stock $asiaTarget -Target $target -Force -ProbePackage $asiaTarget.package
Assert-True ($qualifiedTarget.Forced -and $qualifiedTarget.Probe -and
    $qualifiedTarget.PackageName -ceq $asiaTarget.package -and $qualifiedTarget.PackageVersion -ceq $asiaTarget.versionName) `
    'The package probe did not preserve the actual APK identity.'
$futureStock = [pscustomobject]@{ package = $stockTarget.package; versionName = '99.0.0' }
Assert-Throws { Resolve-PatchVerificationTarget -Stock $futureStock -Target $target } '*Pass -Force*' `
    'A future build was accepted without force.'
Assert-True (Resolve-PatchVerificationTarget -Stock $futureStock -Target $target -Force).Forced `
    'An explicitly forced future build was not marked forced.'
Assert-True ((@($target.PackageVersions) -join ',') -eq '47.4.3' -and $target.PackageVersion -eq '47.4.3') `
    "The catalog versions were not resolved: $(@($target.PackageVersions) -join ', ')."
Assert-True ((Format-VersionList -Versions @('47.0.3')) -eq '47.0.3' -and
    (Format-VersionList -Versions @('47.0.3', '47.1.3')) -eq '47.0.3 and 47.1.3' -and
    (Format-VersionList -Versions @('1.0', '2.0', '3.0')) -eq '1.0, 2.0 and 3.0') 'A version list was not written as a sentence.'
$twoTargets = [pscustomobject]@{ PackageVersions = @('47.1.4', '47.2.3'); PackageVersion = '47.2.3' }
Assert-True ((Get-DeclaredReportVersion -Report ([pscustomobject]@{ packageVersion = '47.1.4' }) -Target $twoTargets) -eq '47.1.4' -and
    (Get-DeclaredReportVersion -Report ([pscustomobject]@{ packageVersion = '47.4.3' }) -Target $target) -eq '47.4.3' -and
    (Get-DeclaredReportVersion -Report ([pscustomobject]@{ packageVersion = '47.0.3' }) -Target $target) -eq '47.4.3' -and
    (Get-DeclaredReportVersion -Report ([pscustomobject]@{ packageVersion = '47.2.3' }) -Target $target) -eq '47.4.3' -and
    (Get-DeclaredReportVersion -Report $null -Target $target) -eq '47.4.3') `
    'A report was held to a version other than the declared one it names.'

$allNames = @($catalog.patches | ForEach-Object { $_.name })
$allDependencies = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $allNames)
# An unnamed patch is listed by its kind: every unnamed bytecode patch reads BytecodePatch, the
# one unnamed resource patch is the manifest half of Keep a streak going, and the unnamed raw
# resource patches record each patch-time choice in Build details.
Assert-True ((@($allDependencies | Sort-Object) -join ',') -eq 'BytecodePatch,RawResourcePatch,ResourcePatch') `
    "The real catalog dependency closure was not its three internal patch kinds: $(@($allDependencies) -join ', ')."
Assert-True (Test-ReportedPatchNames -Expected $allNames `
    -Actual @($allNames + $allDependencies) -AllowedDependencies $allDependencies) `
    'A result that included the real internal dependency was rejected.'

$dependencyCatalog = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'Root'; dependencies = @('Helper') },
        [pscustomobject]@{ name = 'Helper'; dependencies = @('Internal') },
        [pscustomobject]@{ name = 'Unrelated'; dependencies = @() }
    )
}
$dependencies = @(Get-PatchDependencyNames -PatchList $dependencyCatalog -RequestedNames @('Root'))
Assert-True ($dependencies.Count -eq 2 -and $dependencies -contains 'Helper' -and
    $dependencies -contains 'Internal') 'The transitive dependency closure was incomplete.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root') `
    -AllowedDependencies $dependencies) 'A report that omitted optional dependency rows was rejected.'
Assert-True (Test-ReportedPatchNames -Expected @('Root') -Actual @('Internal', 'Root', 'Helper') `
    -AllowedDependencies $dependencies) 'Declared dependency rows were rejected or order mattered.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Helper', 'Internal') `
    -AllowedDependencies $dependencies)) 'A report missing its requested root was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Unrelated') `
    -AllowedDependencies $dependencies)) 'An unrelated extra patch was accepted as a dependency.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Root') `
    -AllowedDependencies $dependencies)) 'A duplicated requested patch was accepted.'
Assert-True (-not (Test-ReportedPatchNames -Expected @('Root') -Actual @('Root', 'Helper', 'Helper') `
    -AllowedDependencies $dependencies)) 'A duplicated dependency patch was accepted.'

$futureCatalog = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'future target'
        compatiblePackages = [pscustomobject]@{ 'com.example.future' = @('99.4.2') }
    })
}
$futureTarget = Get-PatchTarget -PatchList $futureCatalog
Assert-True ($futureTarget.PackageName -eq 'com.example.future' -and
    $futureTarget.PackageVersion -eq '99.4.2') 'A synthetic future target was replaced by a pinned value.'

$twoVersions = [pscustomobject]@{
    patches = @([pscustomobject]@{
        name = 'two versions'
        compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('10.0.0', '2.0.0') }
    })
}
$twoTarget = Get-PatchTarget -PatchList $twoVersions
Assert-True ((@($twoTarget.PackageVersions) -join ',') -eq '2.0.0,10.0.0' -and $twoTarget.PackageVersion -eq '10.0.0') `
    'Two declared versions were not both returned, oldest first by number.'
$unevenVersions = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'both'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0', '2.0.0') } },
        [pscustomobject]@{ name = 'one'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } }
    )
}
Assert-Throws { Get-PatchTarget -PatchList $unevenVersions } '*every patch to declare the same versions*' `
    'A catalog whose patches declare different versions was accepted.'
$secondApp = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'both apps'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0'); 'com.example.drama' = @('1.0.0') } },
        [pscustomobject]@{ name = 'main app'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } }
    )
}
$secondTarget = Get-PatchTarget -PatchList $secondApp
Assert-True ($secondTarget.PackageName -eq 'com.example.app' -and (@($secondTarget.OtherPackages) -join ',') -eq 'com.example.drama') `
    'A second app declared by some patches replaced the package every patch declares.'
$splitApps = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'one'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } },
        [pscustomobject]@{ name = 'other'; compatiblePackages = [pscustomobject]@{ 'com.example.drama' = @('1.0.0') } }
    )
}
Assert-Throws { Get-PatchTarget -PatchList $splitApps } '*one package every patch declares*' `
    'A catalog with no package every patch declares was accepted.'
$unevenSecond = [pscustomobject]@{
    patches = @(
        [pscustomobject]@{ name = 'a'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0'); 'com.example.drama' = @('1.0.0') } },
        [pscustomobject]@{ name = 'b'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0'); 'com.example.drama' = @('2.0.0') } },
        [pscustomobject]@{ name = 'c'; compatiblePackages = [pscustomobject]@{ 'com.example.app' = @('1.0.0') } }
    )
}
Assert-Throws { Get-PatchTarget -PatchList $unevenSecond } '*same versions of com.example.drama*' `
    'A second app declared at different versions was accepted.'
$missingTarget = [pscustomobject]@{ patches = @([pscustomobject]@{ name = 'missing target' }) }
Assert-Throws { Get-PatchTarget -PatchList $missingTarget } '*has no compatible package*' `
    'A patch without compatibility metadata was accepted.'

$tempBase = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
$caseRoot = [System.IO.Path]::GetFullPath((Join-Path $tempBase ("hushfeed-script-test-" + [guid]::NewGuid().ToString('N'))))
$requiredPrefix = $tempBase.TrimEnd('\') + '\'
$deviceLeases = @{}
if (-not $caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create test files outside the temporary directory: $caseRoot"
}
try {
    New-Item -ItemType Directory -Path $caseRoot | Out-Null
    $reportApk = Join-Path $caseRoot 'report.apk'
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open(
        $reportApk, [System.IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($entryName in @('AndroidManifest.xml', 'classes.dex')) {
            $stream = $archive.CreateEntry($entryName).Open()
            try { $stream.WriteByte(0) } finally { $stream.Dispose() }
        }
    } finally {
        $archive.Dispose()
    }
    $dependencyReport = [pscustomobject]@{
        patchingSteps = @([pscustomobject]@{ success = $true })
        appliedPatches = @($allNames + $allDependencies | ForEach-Object {
            [pscustomobject]@{ name = $_ }
        })
        failedPatches = @()
        packageName = $target.PackageName
        packageVersion = $target.PackageVersion
    }
    $reportValidation = Test-PatchingReport -Report $dependencyReport -ExpectedNames $allNames `
        -AllowedDependencyNames $allDependencies -OutputPath $reportApk `
        -ExpectedPackageName $target.PackageName -ExpectedPackageVersion $target.PackageVersion
    Assert-True $reportValidation.Valid `
        "A complete result with a declared dependency was rejected: $($reportValidation.Reason)"

    # Exercise the verifier's actual preservation branch against archives, including corruption
    # with an unchanged length. A resource-table check alone doesn't cover native XRSC files.
    $verifyTokens = $null
    $verifyErrors = $null
    $verifyAst = [System.Management.Automation.Language.Parser]::ParseFile(
        (Join-Path $PSScriptRoot 'verify-all-patches.ps1'), [ref]$verifyTokens, [ref]$verifyErrors)
    Assert-True ($verifyErrors.Count -eq 0) 'The all-patches verifier did not parse.'
    $nativeGuards = @($verifyAst.FindAll({ param($node)
        $node -is [System.Management.Automation.Language.IfStatementAst] -and
            $node.Extent.Text.StartsWith('if ($languagePatch.Count -gt 0)')
    }, $true))
    Assert-True ($nativeGuards.Count -eq 1) 'The verifier has no single native-language preservation branch.'
    $nativeGuard = [scriptblock]::Create($nativeGuards[0].Extent.Text)
    function Invoke-NativeLanguageGuard([string]$Apk, [string]$out, [string]$Default = 'all') {
        $catalog = [pscustomobject]@{ patches = @([pscustomobject]@{
            name = 'Remove unused language packs'
            options = @([pscustomobject]@{ key = 'locales'; default = $Default })
        }) }
        $languagePatch = @($catalog.patches)
        & $nativeGuard 6> $null
    }
    function New-LanguageArchive([string]$Name, [System.Collections.IDictionary]$Files) {
        $path = Join-Path $caseRoot "$Name.apk"
        $zip = [System.IO.Compression.ZipFile]::Open($path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($key in $Files.Keys) {
                $stream = $zip.CreateEntry($key).Open()
                try {
                    $bytes = [Text.Encoding]::UTF8.GetBytes($Files[$key])
                    $stream.Write($bytes, 0, $bytes.Length)
                } finally { $stream.Dispose() }
            }
        } finally { $zip.Dispose() }
        return $path
    }
    $nativeEnglish = 'assets/strings#lang_en/en.xrsc'
    $nativeTurkish = 'assets/strings#lang_tr/tr.xrsc'
    $nativeStock = New-LanguageArchive 'native-stock' @{ $nativeEnglish = 'English'; $nativeTurkish = 'Turkish' }
    $nativeSame = New-LanguageArchive 'native-same' @{ $nativeEnglish = 'English'; $nativeTurkish = 'Turkish' }
    Invoke-NativeLanguageGuard $nativeStock $nativeSame
    Assert-Throws { Invoke-NativeLanguageGuard $nativeStock $nativeSame 'en' } '*must default to all*' `
        'An all-patches catalog that silently removed languages was accepted.'
    $nativeMissing = New-LanguageArchive 'native-missing' @{ $nativeEnglish = 'English' }
    Assert-Throws { Invoke-NativeLanguageGuard $nativeStock $nativeMissing } "*removed or changed $nativeTurkish*" `
        'A missing native language file passed the verifier.'
    $nativeEmpty = New-LanguageArchive 'native-empty' @{ $nativeEnglish = 'English'; $nativeTurkish = '' }
    Assert-Throws { Invoke-NativeLanguageGuard $nativeStock $nativeEmpty } "*removed or changed $nativeTurkish*" `
        'A zeroed native language file passed the verifier.'
    $nativeChanged = New-LanguageArchive 'native-changed' @{ $nativeEnglish = 'English'; $nativeTurkish = 'Changed' }
    Assert-Throws { Invoke-NativeLanguageGuard $nativeStock $nativeChanged } "*build changed $nativeTurkish*" `
        'A same-length native language corruption passed the verifier.'
    $nativeAbsent = New-LanguageArchive 'native-absent' @{ 'AndroidManifest.xml' = 'manifest' }
    Assert-Throws { Invoke-NativeLanguageGuard $nativeAbsent $nativeSame } '*no native language files*' `
        'An unexercised native-language verifier reported success.'
    Write-Host '[scripts] native language preservation contracts passed'

    $fakeAdb = Join-Path $caseRoot 'adb.cmd'
    $log = Join-Path $caseRoot 'adb.log'
    $mode = Join-Path $caseRoot 'mode.txt'
    $fakeBody = @'
@echo off
set /p FAKE_ADB_MODE=<"%~dp0mode.txt"
echo mode=%FAKE_ADB_MODE% args=%*>>"%~dp0adb.log"
if "%3|%4|%5"=="shell|pm|path" goto package_path
if "%3"=="uninstall" goto uninstall
if "%3"=="get-state" goto state
exit /b 0
:state
if not "%FAKE_ADB_MODE%"=="check-fail" echo device
exit /b 0
:package_path
if "%FAKE_ADB_MODE%"=="check-fail" goto check_fail
if "%FAKE_ADB_MODE%"=="absent-silent" exit /b 1
if "%FAKE_ADB_MODE%"=="present" echo package:/data/app/example/base.apk
if "%FAKE_ADB_MODE%"=="uninstall-fail" echo package:/data/app/example/base.apk
exit /b 0
:uninstall
if "%FAKE_ADB_MODE%"=="uninstall-fail" goto uninstall_fail
echo Success
exit /b 0
:check_fail
exit /b 17
:uninstall_fail
exit /b 19
'@
    [System.IO.File]::WriteAllText($fakeAdb, $fakeBody, [System.Text.Encoding]::ASCII)
    foreach ($fixtureSerial in 'CLEAN', 'READY', 'BROKEN', 'LOCKED') {
        $deviceLeases[$fixtureSerial] = New-DeviceLease -LeaseDir (Join-Path $caseRoot 'device-leases') `
            -Serial $fixtureSerial -Project 'hushfeed' -ChatIdentity 'script-contracts' -Purpose 'fake replacement checks'
    }

    [System.IO.File]::WriteAllText($mode, 'absent', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app' -Lease $deviceLeases.CLEAN
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True (-not $removed) 'An absent package was reported as removed.'
    Assert-True ($calls.Count -eq 1 -and $calls[0] -like '*shell pm path com.example.app') `
        'The absent-package path attempted an uninstall.'

    # API 36's pm path exits 1 with nothing to say for a package that isn't there.
    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'absent-silent', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'CLEAN' -PackageName 'com.example.app' -Lease $deviceLeases.CLEAN
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True (-not $removed) 'A package API 36 reports absent was reported as removed.'
    Assert-True ($calls.Count -eq 2 -and $calls[1] -like '*get-state') `
        'A silent pm path failure was not checked against the device state, or went on to uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'present', [System.Text.Encoding]::ASCII)
    $removed = Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'READY' -PackageName 'com.example.app' -Lease $deviceLeases.READY
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True $removed 'An installed package was not removed.'
    Assert-True ($calls.Count -eq 2 -and $calls[0] -like '*shell pm path com.example.app' -and
        $calls[1] -like '*uninstall com.example.app') 'The installed-package path did not check then uninstall.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'check-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'BROKEN' -PackageName 'com.example.app' -Lease $deviceLeases.BROKEN
    } '*could not check*' 'An ADB transport failure was treated as an absent package.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 2 -and $calls[1] -like '*get-state') 'The check-failure path continued after ADB failed.'

    Remove-Item -LiteralPath $log -Force
    [System.IO.File]::WriteAllText($mode, 'uninstall-fail', [System.Text.Encoding]::ASCII)
    Assert-Throws {
        Remove-AndroidPackageIfInstalled -Adb $fakeAdb -Serial 'LOCKED' -PackageName 'com.example.app' -Lease $deviceLeases.LOCKED
    } '*uninstall failed*' 'An uninstall failure was accepted.'
    $calls = @(Get-Content -LiteralPath $log)
    Assert-True ($calls.Count -eq 2) 'The uninstall-failure path did not perform exactly a check and uninstall.'
    foreach ($context in $deviceLeases.Values) { Remove-DeviceLease -Lease $context }

    $emptyJdk = Join-Path $caseRoot 'empty-jdk'
    New-Item -ItemType Directory -Path $emptyJdk | Out-Null
    Assert-Throws {
        Resolve-Java -Explicit $emptyJdk
    } "*$emptyJdk*" 'An explicit directory without bin/java fell through to the PATH Java.'

    # Resolve-Java answers a bare `java` when the one on PATH is new enough; take its file.
    $pathJava = (Get-Command -Name (Resolve-Java) -CommandType Application | Select-Object -First 1).Source
    $jdkRoot = Split-Path -Parent (Split-Path -Parent $pathJava)
    $resolvedJava = Resolve-Java -Explicit $jdkRoot
    Assert-True ([System.IO.Path]::GetFullPath($resolvedJava).Equals(
        [System.IO.Path]::GetFullPath($pathJava), [System.StringComparison]::OrdinalIgnoreCase)) `
        'A valid explicit JDK directory did not resolve its bin/java executable.'

    # A release bundle older than the sources it is built from. On 2026-09-23 a test run after a
    # patch change left it behind the new hook and the phone got the old one; patch-for-device
    # now stops on it. Build output beside a module's sources must not count.
    $staleRoot = Join-Path $caseRoot 'stale-bundle'
    $patchSource = Join-Path $staleRoot 'patches/src/main/kotlin/Hook.kt'
    $extensionSource = Join-Path $staleRoot 'extensions/app/library/src/main/java/Hook.java'
    $buildOutput = Join-Path $staleRoot 'extensions/app/build/intermediates/Hook.class'
    $gradleFile = Join-Path $staleRoot 'extensions/app/build.gradle.kts'
    $staleBundle = Join-Path $staleRoot 'patches/build/release/patches-1.0.0.mpp'
    $staleFiles = @($patchSource, $extensionSource, $buildOutput, $gradleFile, $staleBundle)
    foreach ($file in $staleFiles) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
    }
    $then = [DateTime]::UtcNow.AddHours(-1)
    foreach ($file in $staleFiles) { [System.IO.File]::SetLastWriteTimeUtc($file, $then) }
    [System.IO.File]::SetLastWriteTimeUtc($staleBundle, $then.AddMinutes(5))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'A bundle built after every source was called stale.'
    [System.IO.File]::SetLastWriteTimeUtc($buildOutput, $then.AddMinutes(9))
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 0) `
        'Build output written after the bundle was counted as a source.'
    [System.IO.File]::SetLastWriteTimeUtc($extensionSource, $then.AddMinutes(8))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 1 -and $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName) `
        "A submodule's source written after the bundle was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    [System.IO.File]::SetLastWriteTimeUtc($patchSource, $then.AddMinutes(6))
    [System.IO.File]::SetLastWriteTimeUtc($gradleFile, $then.AddMinutes(7))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 3 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $extensionSource).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $gradleFile).FullName -and
        $newer[2].FullName -eq (Get-Item -LiteralPath $patchSource).FullName) `
        "Newer sources are not all listed, newest first: $(@($newer | ForEach-Object FullName) -join ', ')"
    # The R8 rules every extension's build reads, and a compile-only stub in a patches
    # submodule (its constants can be inlined into patch code), count too.
    $rules = Join-Path $staleRoot 'extensions/proguard-rules.pro'
    $stub = Join-Path $staleRoot 'patches/stub/src/main/java/android/os/Build.java'
    foreach ($file in @($rules, $stub)) {
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
        [System.IO.File]::WriteAllText($file, 'x', [System.Text.Encoding]::ASCII)
        [System.IO.File]::SetLastWriteTimeUtc($file, $then)
    }
    Assert-True (@(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle).Count -eq 3) `
        'An R8 rules file or a stub written before the bundle was counted.'
    [System.IO.File]::SetLastWriteTimeUtc($rules, $then.AddMinutes(10))
    [System.IO.File]::SetLastWriteTimeUtc($stub, $then.AddMinutes(11))
    $newer = @(Get-SourcesNewerThanBundle -Root $staleRoot -Bundle $staleBundle)
    Assert-True ($newer.Count -eq 5 -and
        $newer[0].FullName -eq (Get-Item -LiteralPath $stub).FullName -and
        $newer[1].FullName -eq (Get-Item -LiteralPath $rules).FullName) `
        "The R8 rules or a patches submodule's stub was missed: $(@($newer | ForEach-Object FullName) -join ', ')"
    $deviceScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'patch-for-device.ps1') -Raw
    Assert-True ($deviceScript -match 'Get-SourcesNewerThanBundle' -and
        $deviceScript -match '\[switch\]\$AllowStaleBundle') `
        'patch-for-device.ps1 patches with a bundle without asking whether its sources are newer.'
    # A phone check of an option-driven patch (Accent color's presets) needs the option on the
    # CLI line, right after that patch's -e.
    Assert-True ($deviceScript -match '\[string\[\]\]\$Option' -and $deviceScript -match "\`$enable \+= '-O'") `
        'patch-for-device.ps1 has no way to pass a patch option to the CLI.'
} finally {
    foreach ($context in $deviceLeases.Values) {
        if ($null -ne $context.Stream) { Remove-DeviceLease -Lease $context }
    }
    if ($caseRoot.StartsWith($requiredPrefix, [System.StringComparison]::OrdinalIgnoreCase) -and
        (Test-Path -LiteralPath $caseRoot)) {
        Remove-Item -LiteralPath $caseRoot -Recurse -Force
    }
}

$consumerScripts = @('patch-for-device.ps1', 'verify-all-patches.ps1', 'measure-patch-heap.ps1')
foreach ($name in $consumerScripts) {
    $text = Get-Content -LiteralPath (Join-Path $PSScriptRoot $name) -Raw
    Assert-True ($text -notmatch '(?m)expectedPackageVersion\s*=\s*[''\"]') `
        "$name pins an expected package version instead of reading the catalog."
    Assert-True ($text -match 'Get-PatchTarget') "$name does not read its target through Get-PatchTarget."
    Assert-True ($text -match 'Get-PatchDependencyNames') `
        "$name does not derive the selected patches' dependency closure."
    Assert-True ($text -match 'AllowedDependencyNames') `
        "$name does not pass declared dependency names into result validation."
}

# The release receipt runs over the builds the gate applied. Collecting every package's targets
# took PineDrama's 47.1.4 for a TikTok build at 0.76.0 and stopped the build stage.
$releaseText = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'release/release.ps1') -Raw
Assert-True ($releaseText -match '\$target = Get-PatchTarget' -and $releaseText -match 'foreach \(\$v in @\(\$target\.PackageVersions\)\)') `
    'release.ps1 does not pick its receipt fixtures from the bundle''s target through Get-PatchTarget.'
Assert-True ($releaseText -notmatch 'compatibility\.targets') `
    'release.ps1 reads every package''s compatibility targets, so a second app''s version reads as a TikTok build.'

# --- patch timing ----------------------------------------------------------------------------
#
# time-patches.ps1 splits one CLI run into patches by the "Applied:" and "FAILED:" lines and gives
# each the heap peak of the collections between its line and the one before (#54). Lines before
# "Executing patches" are loading, and a gap with no collection has no peak rather than zero.

$gcLines = @(
    '[1500ms] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 900M->200M(4096M) 3.100ms',
    '[2200ms] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 1000M->300M(4096M) 5.100ms',
    '[2300ms] GC(1) Using 24 workers of 24 for evacuation',
    '[2400ms] GC(2) Pause Young (Concurrent Start) (G1 Humongous Allocation) 1200M->400M(4096M) 6.200ms',
    '[3000ms] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 800M->350M(4096M) 4.000ms'
)
$collections = Read-GcHeapLog -Lines $gcLines
Assert-True ($collections.Count -eq 4 -and $collections[1].At -eq 2200 -and $collections[1].Before -eq 1000) `
    'Read-GcHeapLog did not read each collection as its clock and the heap before it.'
$stamped = @(
    [pscustomobject]@{ At = 1000; Line = 'INFO: Loading patches' },
    [pscustomobject]@{ At = 1800; Line = 'INFO: Applied: Early' },
    [pscustomobject]@{ At = 2000; Line = 'INFO: Executing patches' },
    [pscustomobject]@{ At = 2500; Line = 'INFO: Applied: First' },
    [pscustomobject]@{ At = 4000; Line = 'SEVERE: FAILED: Second' },
    [pscustomobject]@{ At = 4050; Line = 'INFO: Ignored progress line' },
    [pscustomobject]@{ At = 4100; Line = 'INFO: Applied: Third  ' }
)
$times = Get-PatchTimes -Stamped $stamped -Collections $collections
Assert-True ($null -ne $times -and $times.ExecutingAt -eq 2000 -and $times.LastPatchAt -eq 4100 -and $times.Rows.Count -eq 3) `
    'Get-PatchTimes counted a line before "Executing patches" or missed a result line.'
Assert-True ($times.Rows[0].Patch -eq 'First' -and $times.Rows[0].Ms -eq 500 -and $times.Rows[0].PeakMb -eq 1200) `
    'Get-PatchTimes gave the first patch the wrong time or heap peak.'
Assert-True ($times.Rows[1].Patch -eq 'Second' -and $times.Rows[1].Result -eq 'FAILED' -and
    $times.Rows[1].Ms -eq 1500 -and $times.Rows[1].PeakMb -eq 800) `
    'Get-PatchTimes did not read a failed patch as its own row.'
Assert-True ($times.Rows[2].Patch -eq 'Third' -and $times.Rows[2].Ms -eq 100 -and $null -eq $times.Rows[2].PeakMb) `
    'Get-PatchTimes kept trailing spaces in a name or gave a gap without a collection a heap peak.'
Assert-True ($null -eq (Get-PatchTimes -Stamped @($stamped[0], $stamped[1]) -Collections $collections)) `
    'Get-PatchTimes timed a run that never reached its patches.'
$timingScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'time-patches.ps1') -Raw
Assert-True ($timingScript -match 'Get-PatchTimes' -and $timingScript -match 'Read-GcHeapLog' -and
    $timingScript -match 'Resolve-DesktopCli') `
    'time-patches.ps1 no longer reads its run through the shared parsers and CLI lookup.'

# --- release receipt -------------------------------------------------------------------------

. (Join-Path $PSScriptRoot 'release-receipt.ps1')

# Under Windows PowerShell 5.1, which runs the hook when pwsh isn't on git's PATH, a native
# command's stderr line throws under Stop even when it is redirected, so a failing aapt2 or CLI
# surfaced as its own first stderr line and the script's report was lost. Run the manifest reader
# there with a stand-in aapt2 that complains on stderr and fails.
$windowsPowerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (Test-Path -LiteralPath $windowsPowerShell -PathType Leaf) {
    $nativeRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-ps51-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $nativeRoot | Out-Null
    try {
        $standInAapt2 = Join-Path $nativeRoot 'aapt2.cmd'
        [IO.File]::WriteAllText($standInAapt2, "@echo W: not an APK 1>&2`r`n@exit /b 1`r`n")
        $standInApk = Join-Path $nativeRoot 'input.apk'
        [IO.File]::WriteAllText($standInApk, 'x')
        $receiptScript = Join-Path $PSScriptRoot 'release-receipt.ps1'
        $command = "`$ErrorActionPreference = 'Stop'; . '$receiptScript'; " +
            "try { Get-ApkManifestFacts -Apk '$standInApk' -Aapt2 '$standInAapt2' | Out-Null; 'no error' } " +
            "catch { `$_.Exception.Message }"
        $message = (& $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command 2>&1 |
            Out-String).Trim()
        $global:LASTEXITCODE = 0
        Assert-True ($message -like 'aapt2 could not read the manifest of*not an APK*') `
            "Under Windows PowerShell 5.1 a failing aapt2 surfaced as: $message"

        # And a harmless stderr line from a call that succeeds: adb starting its server says so on
        # stderr, which ended a -Replace run before it had checked anything.
        $standInAdb = Join-Path $nativeRoot 'adb.cmd'
        [IO.File]::WriteAllText($standInAdb, "@echo * daemon not running; starting now at tcp:5037 1>&2`r`n@exit /b 0`r`n")
        $installScript = Join-Path $PSScriptRoot 'device-install.ps1'
        $command = "`$ErrorActionPreference = 'Stop'; . '$installScript'; " +
            "`$lease = New-DeviceLease -LeaseDir '$(Join-Path $nativeRoot 'device-leases')' -Serial 'serial' -Project 'hushfeed' -ChatIdentity 'ps51-fixture' -Purpose 'stderr fixture'; " +
            "try { 'removed=' + (Remove-AndroidPackageIfInstalled -Adb '$standInAdb' -Serial 'serial' -PackageName 'com.example' -Lease `$lease 6> `$null) } " +
            "catch { 'threw: ' + `$_.Exception.Message } finally { Remove-DeviceLease -Lease `$lease }"
        $answer = (& $windowsPowerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass -Command $command 2>&1 |
            Out-String).Trim()
        $global:LASTEXITCODE = 0
        Assert-True ($answer -eq 'removed=False') `
            "Under Windows PowerShell 5.1 adb's startup line stopped the install helper: $answer"
    } finally {
        Remove-Item -LiteralPath $nativeRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# A failed fixture run quotes the CLI: an early error wherever it fell, and the last lines.
$cliRun = @('INFO: Loading patches...', 'SEVERE: early fingerprint failure') +
    @(1..30 | ForEach-Object { "INFO: Applied: patch $_" }) +
    @('', 'java.lang.OutOfMemoryError: Java heap space')
$cliTail = Get-CliOutputTail -Output $cliRun
Assert-True ($cliTail -match 'early fingerprint failure' -and $cliTail -match 'OutOfMemoryError' -and
    $cliTail -match 'patch 30' -and $cliTail -notmatch 'Loading patches') `
    "The CLI tail lost an error line or kept the whole run: $cliTail"
Assert-True (@($cliTail -split "`n").Count -eq 21) `
    "The CLI tail repeated a line it already had: $(@($cliTail -split "`n").Count) lines"
# Many failed patches first, then the line that ended the run and a long trace under it.
$manyFailures = @(1..12 | ForEach-Object { "SEVERE: patch $_ failed" }) +
    @('Exception in thread "main" java.lang.IllegalStateException: the run ended here') +
    @(1..25 | ForEach-Object { "    at frame$_(Source.java:$_)" })
$manyTail = Get-CliOutputTail -Output $manyFailures
Assert-True ($manyTail -match 'the run ended here' -and $manyTail -match 'patch 1 failed') `
    "The CLI tail dropped the line that ended the run behind earlier failures: $manyTail"
Assert-True ((Get-CliOutputTail -Output @()) -eq '(the CLI printed nothing)') `
    'A silent CLI run left the failure message with nothing after the colon.'
$receiptScript = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'build-release-receipt.ps1') -Raw
Assert-True ($receiptScript -match 'bundleManifest\.timestamp -ne \$commitTimestamp \* 1000') `
    'build-release-receipt.ps1 patches the fixtures before checking the bundle is stamped with its commit.'
$bundleBuild = Get-Content -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'patches/build.gradle.kts') -Raw
Assert-True ($bundleBuild -match '"git", "status", "--porcelain"' -and
    $bundleBuild.IndexOf('"status", "--porcelain"') -lt $bundleBuild.IndexOf('"log", "-1", "--format=%ct"')) `
    'The bundle takes HEAD''s time without asking whether the tree has uncommitted changes.'
Assert-True ($receiptScript -notmatch 'DesktopJar @arguments 2>&1 \| Out-Null' -and
    $receiptScript -match 'Get-CliOutputTail') `
    'build-release-receipt.ps1 throws the desktop CLI output away again.'

$manifestLines = @(
    'N: android=http://schemas.android.com/apk/res/android (line=1)',
    '  E: manifest (line=1)',
    '    A: http://schemas.android.com/apk/res/android:versionCode(0x0101021b)=2024607030',
    '    A: http://schemas.android.com/apk/res/android:versionName(0x0101021c)="46.7.3" (Raw: "46.7.3")',
    '    A: package="com.example.host" (Raw: "com.example.host")',
    '    A: platformBuildVersionCode=36',
    '      E: uses-permission (line=10)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")',
    '      E: uses-permission (line=11)',
    '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.CAMERA" (Raw: "android.permission.CAMERA")',
    '      E: application (line=20)',
    '        E: activity (line=21)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Main" (Raw: "com.example.host.Main")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true',
    '            E: intent-filter (line=22)',
    '              E: action (line=23)',
    '                A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.intent.action.MAIN" (Raw: "android.intent.action.MAIN")',
    '        E: activity (line=30)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Private" (Raw: "com.example.host.Private")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=false',
    '        E: service (line=40)',
    '          A: http://schemas.android.com/apk/res/android:name(0x01010003)=".Sync" (Raw: ".Sync")',
    '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'
)

$facts = ConvertFrom-ManifestXmlTree -Lines $manifestLines -Source 'fixture'
Assert-True ($facts.package -eq 'com.example.host') 'The manifest package was not read.'
Assert-True ($facts.versionName -eq '46.7.3') 'The manifest version name was not read.'
Assert-True ($facts.versionCode -eq '2024607030') 'The manifest version code was not read.'
Assert-True (($facts.permissions -join ',') -eq 'android.permission.CAMERA,android.permission.INTERNET') `
    'The requested permissions were not read and sorted.'
Assert-True (($facts.exported -join ',') -eq 'activity:com.example.host.Main,service:com.example.host.Sync') `
    "Exported components were misread: $($facts.exported -join ',')"
Assert-True ($facts.exported -notcontains 'activity:com.example.host.Private') `
    'A component marked exported=false was reported as exported.'
Assert-True ($facts.exported -notcontains 'action:android.intent.action.MAIN') `
    'An intent-filter action was counted as an exported component.'

Assert-Throws {
    ConvertFrom-ManifestXmlTree -Lines @('  E: manifest (line=1)') -Source 'nameless'
} '*no package name*' 'A manifest with no package was accepted.'

$patchedFacts = ConvertFrom-ManifestXmlTree -Source 'patched' -Lines (
    @($manifestLines | Where-Object { $_ -notlike '*android.permission.CAMERA*' }) + @(
        '      E: uses-permission (line=12)',
        '        A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.VIBRATE" (Raw: "android.permission.VIBRATE")',
        '      E: application (line=20)',
        '        E: receiver (line=50)',
        '          A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.example.host.Probe" (Raw: "com.example.host.Probe")',
        '          A: http://schemas.android.com/apk/res/android:exported(0x01010010)=true'))
$delta = Get-ManifestDelta -Stock $facts -Patched $patchedFacts
Assert-True (($delta.permissionsAdded -join ',') -eq 'android.permission.VIBRATE') `
    'An added permission was not reported.'
Assert-True (($delta.permissionsRemoved -join ',') -eq 'android.permission.CAMERA') `
    'A removed permission was not reported.'
Assert-True (($delta.exportedComponentsAdded -join ',') -eq 'receiver:com.example.host.Probe') `
    'A newly exported component was not reported.'
Assert-True (@($delta.exportedComponentsRemoved).Count -eq 0) `
    'A component that stayed exported was reported as removed.'
$entries = ConvertTo-ManifestDeltaEntries -Delta $delta
Assert-True (($entries -join '; ') -eq (@(
    'exported-added receiver:com.example.host.Probe',
    'permission-added android.permission.VIBRATE',
    'permission-removed android.permission.CAMERA') -join '; ')) `
    "The delta did not flatten to allowlist lines: $($entries -join '; ')"

$unchanged = Get-ManifestDelta -Stock $facts -Patched $facts
Assert-True (@(ConvertTo-ManifestDeltaEntries -Delta $unchanged).Count -eq 0) `
    'An unchanged manifest produced a delta.'

# Exactly Keep a streak going's three alarm permissions. Anything more means a patch changes the
# manifest in a way nobody has reviewed here yet.
$checkedInAllowlist = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')
Assert-True ((@($checkedInAllowlist | Where-Object { $_ }) -join '; ') -eq (@(
    'permission-added android.permission.RECEIVE_BOOT_COMPLETED',
    'permission-added android.permission.SCHEDULE_EXACT_ALARM',
    'permission-added android.permission.USE_EXACT_ALARM') -join '; ')) `
    ('The checked-in manifest delta allowlist changed, so the patches now change the Android ' +
     "manifest in a new way: $(@($checkedInAllowlist) -join ', ')")

$allowlistRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("receipt-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $allowlistRoot | Out-Null
try {
    $good = Join-Path $allowlistRoot 'good.txt'
    Set-Content -LiteralPath $good -Encoding UTF8 -Value @(
        '# a comment', '', 'permission-added android.permission.VIBRATE',
        'exported-added receiver:com.example.host.Probe')
    Assert-True (@(Read-ManifestDeltaAllowlist -Path $good).Count -eq 2) `
        'The allowlist reader did not skip comments and blank lines.'

    $bad = Join-Path $allowlistRoot 'bad.txt'
    Set-Content -LiteralPath $bad -Encoding UTF8 -Value @('permission-added')
    Assert-Throws { Read-ManifestDeltaAllowlist -Path $bad } '*<kind> <value>*' `
        'A malformed allowlist line was accepted.'
    Assert-Throws { Read-ManifestDeltaAllowlist -Path (Join-Path $allowlistRoot 'absent.txt') } `
        '*allowlist is missing*' 'A missing allowlist was treated as an empty one.'

    # A stand-in bundle, so the size, hash and manifest checks compare against real bytes.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $commitSeconds = 1700000000L
    function New-TestBundle {
        param([string]$Path, [string]$Version = '9.9.9', [long]$Timestamp = 1700000000000L,
            [string]$Patcher = '1.12.0',
            [string]$SourceCommit = '0123456789abcdef0123456789abcdef01234567',
            [string]$SourceClean = 'true',
            [string]$SourceStart = ('A' * 64), [string]$SourceEnd = ('A' * 64),
            [switch]$OmitSourceProvenance)
        if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
        $archive = [System.IO.Compression.ZipFile]::Open(
            $Path, [System.IO.Compression.ZipArchiveMode]::Create)
        try {
            $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
            $writer = New-Object System.IO.StreamWriter($entry.Open())
            try {
                $writer.Write("Manifest-Version: 1.0`nVersion: $Version`n" +
                    "Timestamp: $Timestamp`nPatcher-Version: $Patcher`n")
                if (-not $OmitSourceProvenance) {
                    foreach ($sourceLine in @("Hushfeed-Source-Commit: $SourceCommit",
                            "Hushfeed-Source-Clean: $SourceClean",
                            "Hushfeed-Source-Start: $SourceStart", "Hushfeed-Source-End: $SourceEnd")) {
                        # Real manifests fold long attributes. Exercise the reader's unfolding too.
                        while ($sourceLine.Length -gt 70) {
                            $writer.Write($sourceLine.Substring(0, 70) + "`n")
                            $sourceLine = ' ' + $sourceLine.Substring(70)
                        }
                        $writer.Write($sourceLine + "`n")
                    }
                }
                $writer.Write("`n")
            } finally { $writer.Dispose() }
        } finally { $archive.Dispose() }
    }

    $bundle = Join-Path $allowlistRoot 'patches-9.9.9.mpp'
    New-TestBundle -Path $bundle
    $bundleHash = Get-Sha256Hex -Path $bundle
    $bundleSize = (Get-Item -LiteralPath $bundle).Length

    $manifestFacts = Get-BundleManifestFacts -BundlePath $bundle
    Assert-True ($manifestFacts.version -eq '9.9.9') 'The bundle manifest version was not read.'
    Assert-True ($manifestFacts.timestamp -eq 1700000000000L) 'The bundle timestamp was not read.'
    Assert-True ($manifestFacts.patcherVersion -eq '1.12.0') 'The bundle patcher stamp was not read.'

    $template = [ordered]@{
        schemaVersion = Get-ReleaseReceiptSchemaVersion
        release   = [ordered]@{ version = '9.9.9'; tag = 'v9.9.9'
            commit = '0123456789abcdef0123456789abcdef01234567'
            commitTimestamp = $commitSeconds; patchCount = 2 }
        bundle    = [ordered]@{ file = 'patches-9.9.9.mpp'; sizeBytes = $bundleSize
            sha256 = $bundleHash; timestamp = 1700000000000L }
        toolchain = [ordered]@{ patcherVersion = '1.12.0'; managerFloor = '1.29.0' }
        source = [ordered]@{ commit = '0123456789abcdef0123456789abcdef01234567'; clean = $true
            startFingerprint = ('A' * 64); endFingerprint = ('A' * 64) }
        extension = [ordered]@{ dexPayloads = @([ordered]@{
            name = 'extensions/tiktok.rve'; sizeBytes = 10; sha256 = ('A' * 64) }) }
        targets   = @([ordered]@{
            source = [ordered]@{ file = 'stock.apk'; package = 'com.example.host'
                versionName = '46.7.3'; versionCode = '2024607030'; sha256 = ('B' * 64)
                forced = $false }
            patches = @([ordered]@{ name = 'Alpha'; applied = $true; reason = $null },
                        [ordered]@{ name = 'Beta'; applied = $true; reason = $null })
            manifestDelta = [ordered]@{ permissionsAdded = @(); permissionsRemoved = @()
                exportedComponentsAdded = @(); exportedComponentsRemoved = @() }
        })
    }
    $templateJson = $template | ConvertTo-Json -Depth 12

    function New-TestReceipt {
        param([scriptblock]$Mutate)
        $copy = $templateJson | ConvertFrom-Json
        if ($Mutate) { & $Mutate $copy }
        return $copy
    }

    function Test-TestReceipt {
        param($Receipt, [string[]]$Approved = @())
        return Test-ReleaseReceipt -Receipt $Receipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $bundle -ApprovedManifestDelta $Approved
    }

    $valid = Test-TestReceipt -Receipt (New-TestReceipt)
    Assert-True $valid.Valid "A complete receipt was refused: $($valid.Reason)"

    # A matching timestamp and receipt hash describe bytes, not the source used to build them.
    # These bundles keep both correct while changing only the producer's source evidence.
    $sourceCases = [ordered]@{
        'another source commit with the same timestamp' = @{ SourceCommit = ('f' * 40) }
        'a dirty build with a reproducible timestamp'    = @{ SourceClean = 'false' }
        'a source state that could not be read'          = @{ SourceClean = 'unknown' }
        'inputs changed during the build'               = @{ SourceEnd = ('B' * 64) }
        'an unreadable starting source fingerprint'      = @{ SourceStart = '' }
        'a missing build-time provenance record'         = @{ OmitSourceProvenance = $true }
    }
    $sourceCaseIndex = 0
    foreach ($description in $sourceCases.Keys) {
        $sourceBundle = Join-Path $allowlistRoot "source-provenance-$sourceCaseIndex.mpp"
        $sourceCaseIndex++
        $sourceArguments = $sourceCases[$description]
        New-TestBundle -Path $sourceBundle @sourceArguments
        $sourceReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $sourceBundle).Length
            $r.bundle.sha256 = Get-Sha256Hex -Path $sourceBundle
        }
        $sourceResult = Test-ReleaseReceipt -Receipt $sourceReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
            -ExpectedPackageVersion '46.7.3' -BundlePath $sourceBundle `
            -ExpectedCommit '0123456789abcdef0123456789abcdef01234567'
        Assert-True (-not $sourceResult.Valid) "Receipt validation accepted $description."
        Assert-True ([bool]$sourceResult.Reason) "Source provenance was refused without a reason: $description"
    }

    # An old release label and schema cannot turn a newly built dirty artifact into historical
    # evidence. Historical verification must be tied to the actual published bytes instead.
    $legacyNamedBundle = Join-Path $allowlistRoot 'new-build-named-0.61.0.mpp'
    New-TestBundle -Path $legacyNamedBundle -Version '0.61.0' -SourceClean 'false'
    $legacyNamedReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.schemaVersion = 1
        $r.release.version = '0.61.0'
        $r.release.tag = 'v0.61.0'
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $legacyNamedBundle).Length
        $r.bundle.sha256 = Get-Sha256Hex -Path $legacyNamedBundle
    }
    $legacyNamedResult = Test-ReleaseReceipt -Receipt $legacyNamedReceipt -ExpectedVersion '0.61.0' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersion '46.7.3' -BundlePath $legacyNamedBundle
    Assert-True (-not $legacyNamedResult.Valid) `
        'An old version label and receipt schema bypassed current-build source eligibility.'

    # The exact published document remains readable. Its frozen file digest and bundle/commit
    # identity are the boundary; changing the parsed object while supplying that file is refused.
    $historicalPath = Join-Path $PSScriptRoot 'fixtures/legacy-receipt-0.61.0.json'
    $historicalReceipt = Get-Content -LiteralPath $historicalPath -Raw | ConvertFrom-Json
    $historicalArguments = @{
        ExpectedVersion = '0.61.0'
        ExpectedPatchNames = @($historicalReceipt.targets[0].patches | ForEach-Object { [string]$_.name })
        ExpectedPatcherVersion = '1.13.0'
        ExpectedManagerFloor = '1.30.0'
        ExpectedPackageName = 'com.zhiliaoapp.musically'
        ExpectedPackageVersions = @('47.0.3', '47.1.3')
        ExpectedCommit = '5abaa467107e28e6800637718a1270d3635210d8'
        ReceiptPath = $historicalPath
    }
    $historicalCheck = Test-ReleaseReceipt -Receipt $historicalReceipt @historicalArguments
    Assert-True $historicalCheck.Valid "The exact published receipt was refused: $($historicalCheck.Reason)"
    $historicalReceipt.bundle.sha256 = 'F' * 64
    $alteredHistorical = Test-ReleaseReceipt -Receipt $historicalReceipt @historicalArguments
    Assert-True (-not $alteredHistorical.Valid) 'A changed receipt borrowed another file historical identity.'

    # Every fact the receipt exists to pin, put in front of the check one at a time. A gate that
    # has never been shown to fail is a gate nobody has tested.
    $mutations = [ordered]@{
        'a receipt from a different schema'     = { param($r) $r.schemaVersion = 99 }
        'a missing source provenance record'   = { param($r) $r.source = $null }
        'a source clean flag written as text'  = { param($r) $r.source.clean = 'true' }
        'a receipt naming another source'      = { param($r) $r.source.commit = ('e' * 40) }
        'receipt inputs changed during build'  = { param($r) $r.source.endFingerprint = ('E' * 64) }
        'receipt fingerprints differ from bundle' = { param($r)
            $r.source.startFingerprint = ('E' * 64); $r.source.endFingerprint = ('E' * 64) }
        'a receipt for a different version'     = { param($r) $r.release.version = '9.9.8' }
        'a tag that does not match the version' = { param($r) $r.release.tag = 'v9.9.8' }
        'a short commit'                        = { param($r) $r.release.commit = '0123456' }
        'a patch count that is not the catalog' = { param($r) $r.release.patchCount = 3 }
        'a different patcher'                   = { param($r) $r.toolchain.patcherVersion = '1.13.0' }
        'a different Manager floor'             = { param($r) $r.toolchain.managerFloor = '1.30.0' }
        'a bundle size that is not the bundle'  = { param($r) $r.bundle.sizeBytes = 4 }
        'a bundle hash that is not the bundle'  = { param($r) $r.bundle.sha256 = ('C' * 64) }
        'an empty extension payload'            = { param($r) $r.extension.dexPayloads[0].sizeBytes = 0 }
        'an unhashed extension payload'         = { param($r) $r.extension.dexPayloads[0].sha256 = 'nope' }
        'no extension payload at all'           = { param($r) $r.extension.dexPayloads = @() }
        'no target at all'                      = { param($r) $r.targets = @() }
        'an unhashed source APK'                = { param($r) $r.targets[0].source.sha256 = '' }
        'fewer verdicts than patches'           = { param($r) $r.targets[0].patches = @($r.targets[0].patches[0]) }
        'a patch the catalog does not list'     = { param($r) $r.targets[0].patches[1].name = 'Gamma' }
        'the same patch reported twice'         = { param($r) $r.targets[0].patches[1].name = 'Alpha' }
        'a patch that did not apply'            = { param($r) $r.targets[0].patches[1].applied = $false }
        'a receipt with no commit time'         = { param($r) $r.release.commitTimestamp = 0 }
        'a stamp that is not the bundle stamp'  = { param($r) $r.bundle.timestamp = 1700000001000L }
        'a run against another package'         = { param($r) $r.targets[0].source.package = 'com.example.other' }
        'a run with no version name'            = { param($r) $r.targets[0].source.versionName = '' }
        'a receipt that omits the forced flag'  = { param($r) $r.targets[0].source.PSObject.Properties.Remove('forced') }
        'a forced flag that is not a boolean'   = { param($r) $r.targets[0].source.forced = 'false' }
        'a declared-version run marked forced'  = { param($r) $r.targets[0].source.forced = $true }
        'only forced runs past the target'      = { param($r) $r.targets[0].source.versionName = '46.8.3'; $r.targets[0].source.forced = $true }
        'a newer build patched without -f'      = { param($r) $r.targets[0].source.versionName = '46.8.3' }
    }
    foreach ($description in $mutations.Keys) {
        $result = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations[$description])
        Assert-True (-not $result.Valid) "Receipt validation accepted $description."
        Assert-True ([bool]$result.Reason) "Receipt validation refused $description without saying why."
    }

    # A receipt built only from forced runs against newer builds has to be refused for that
    # reason and name the target it is missing, not trip over some other field on the way.
    $onlyForced = Test-TestReceipt -Receipt (New-TestReceipt -Mutate $mutations['only forced runs past the target'])
    Assert-True ($onlyForced.Reason -like '*No target*46.7.3*without -f*46.8.3*') `
        "A forced-only receipt was refused for the wrong reason: $($onlyForced.Reason)"
    $secondTarget = New-TestReceipt -Mutate {
        param($r)
        $newer = $r.targets[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $newer.source.versionName = '46.8.3'
        $newer.source.forced = $true
        $r.targets = @($r.targets[0], $newer)
    }
    $twoTargets = Test-TestReceipt -Receipt $secondTarget
    Assert-True $twoTargets.Valid "A receipt with the declared target beside a forced run was refused: $($twoTargets.Reason)"

    # Two declared versions: each needs a run of its own without -f.
    $bothDeclared = New-TestReceipt -Mutate {
        param($r)
        $newer = $r.targets[0] | ConvertTo-Json -Depth 8 | ConvertFrom-Json
        $newer.source.versionName = '46.8.3'
        $r.targets = @($r.targets[0], $newer)
    }
    $bothValid = Test-ReleaseReceipt -Receipt $bothDeclared -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersions @('46.7.3', '46.8.3') -BundlePath $bundle
    Assert-True $bothValid.Valid "A receipt proving both declared versions was refused: $($bothValid.Reason)"
    $oneMissing = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' `
        -ExpectedPackageVersions @('46.7.3', '46.8.3') -BundlePath $bundle
    Assert-True (-not $oneMissing.Valid -and $oneMissing.Reason -like '*No target*46.8.3*without -f*') `
        "A receipt missing one declared version was not refused for it: $($oneMissing.Reason)"

    # The bundle the receipt is about, gone. Every fact above is checked against a file, and a
    # missing file is the one case where there is nothing to disagree with, so an unguarded
    # check would read it as agreement and pass the release.
    $absent = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath (Join-Path $allowlistRoot 'not-built.mpp')
    Assert-True (-not $absent.Valid) 'A receipt was accepted against a bundle that is not there.'
    Assert-True ($absent.Reason -like '*not there*') `
        "The missing bundle was refused for the wrong reason: $($absent.Reason)"

    # The bundle itself disagreeing with the receipt, which is the v0.28.0 failure: a bundle
    # built before its release commit existed carries the previous commit's pin, and nobody can
    # reproduce the published hash from the tag.
    $strayBundle = Join-Path $allowlistRoot 'patches-stray.mpp'
    New-TestBundle -Path $strayBundle -Timestamp 1699999999000L
    $strayReceipt = New-TestReceipt -Mutate {
        param($r)
        $r.bundle.sizeBytes = (Get-Item -LiteralPath $strayBundle).Length
        $r.bundle.sha256 = Get-Sha256Hex -Path $strayBundle
        $r.bundle.timestamp = 1699999999000L
    }
    $strayResult = Test-ReleaseReceipt -Receipt $strayReceipt -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $strayBundle
    Assert-True (-not $strayResult.Valid) 'A bundle built from another commit was accepted.'
    Assert-True ($strayResult.Reason -like '*different*commit*') `
        "The stale bundle pin was refused for the wrong reason: $($strayResult.Reason)"

    foreach ($wrong in @(
        @{ Name = 'a bundle stamped with another version'; Version = '9.9.8'; Patcher = '1.12.0'
            Pattern = '*manifest says version*' },
        @{ Name = 'a bundle stamped by another patcher'; Version = '9.9.9'; Patcher = '1.13.0'
            Pattern = '*stamped by patcher*' })) {
        $odd = Join-Path $allowlistRoot 'patches-odd.mpp'
        New-TestBundle -Path $odd -Version $wrong.Version -Patcher $wrong.Patcher
        $oddReceipt = New-TestReceipt -Mutate {
            param($r)
            $r.bundle.sizeBytes = (Get-Item -LiteralPath $odd).Length
            $r.bundle.sha256 = Get-Sha256Hex -Path $odd
        }
        $oddResult = Test-ReleaseReceipt -Receipt $oddReceipt -ExpectedVersion '9.9.9' `
            -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
            -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $odd
        Assert-True (-not $oddResult.Valid) "Receipt validation accepted $($wrong.Name)."
        Assert-True ($oddResult.Reason -like $wrong.Pattern) `
            "$($wrong.Name) was refused for the wrong reason: $($oddResult.Reason)"
    }

    # The commit the receipt names, checked against something outside the receipt. Its own
    # timestamp field and the bundle stamp both come from the same document, so a receipt kept
    # from an earlier release agrees with itself and passes on that pair alone.
    $sameCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $bundle `
        -ActualCommitTimestamp $commitSeconds
    Assert-True $sameCommit.Valid "A receipt matching git was refused: $($sameCommit.Reason)"

    $movedCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $bundle `
        -ActualCommitTimestamp ($commitSeconds + 60)
    Assert-True (-not $movedCommit.Valid) `
        'A receipt whose commit time git disagrees with was accepted.'
    Assert-True ($movedCommit.Reason -like '*git says*') `
        "The stale receipt was refused for the wrong reason: $($movedCommit.Reason)"

    $otherCommit = Test-ReleaseReceipt -Receipt (New-TestReceipt) -ExpectedVersion '9.9.9' `
        -ExpectedPatchNames @('Alpha', 'Beta') -ExpectedPatcherVersion '1.12.0' `
        -ExpectedManagerFloor '1.29.0' -ExpectedPackageName 'com.example.host' -ExpectedPackageVersion '46.7.3' -BundlePath $bundle `
        -ExpectedCommit ('f' * 40)
    Assert-True (-not $otherCommit.Valid) 'A receipt for another commit was accepted on a release.'
    Assert-True ($otherCommit.Reason -like '*this release is*') `
        "The wrong-commit receipt was refused for the wrong reason: $($otherCommit.Reason)"

    # A truncated document, which is the shape @($null) turns into one null entry.
    foreach ($missing in @('targets', 'dexPayloads')) {
        $truncated = $templateJson | ConvertFrom-Json
        if ($missing -eq 'targets') {
            $truncated.PSObject.Properties.Remove('targets')
        } else {
            $truncated.extension.PSObject.Properties.Remove('dexPayloads')
        }
        $result = Test-TestReceipt -Receipt $truncated
        Assert-True (-not $result.Valid) "A receipt with no $missing was accepted."
        Assert-True ($result.Reason -like '*no target*' -or $result.Reason -like '*no extension*') `
            "A receipt with no $missing was refused for the wrong reason: $($result.Reason)"
    }

    $withDelta = New-TestReceipt -Mutate {
        param($r) $r.targets[0].manifestDelta.permissionsAdded = @('android.permission.VIBRATE')
    }
    $unreviewed = Test-TestReceipt -Receipt $withDelta
    Assert-True (-not $unreviewed.Valid) 'An unreviewed manifest change was accepted.'
    Assert-True ($unreviewed.Reason -like '*nobody reviewed*') `
        "The unreviewed manifest change was refused for the wrong reason: $($unreviewed.Reason)"

    $reviewed = Test-TestReceipt -Receipt $withDelta -Approved @('permission-added android.permission.VIBRATE')
    Assert-True $reviewed.Valid "A reviewed manifest change was refused: $($reviewed.Reason)"

    # The shape a real run hands over, rather than a literal @(). An allowlist file with no
    # entries reaches the validator as $null, and treating that null as an approved entry failed
    # every clean run with an empty list of changes nobody could read.
    $emptyFromFile = Join-Path $allowlistRoot 'empty.txt'
    Set-Content -LiteralPath $emptyFromFile -Encoding UTF8 -Value @('# nothing approved', '')
    $fromFile = Read-ManifestDeltaAllowlist -Path $emptyFromFile
    $clean = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $fromFile
    Assert-True $clean.Valid `
        "A receipt with no manifest change failed against an empty allowlist: $($clean.Reason)"
    $cleanNull = Test-TestReceipt -Receipt (New-TestReceipt) -Approved $null
    Assert-True $cleanNull.Valid `
        "A receipt with no manifest change failed against a null allowlist: $($cleanNull.Reason)"

    $stale = Test-TestReceipt -Receipt (New-TestReceipt) -Approved @('permission-added android.permission.VIBRATE')
    Assert-True (-not $stale.Valid) 'An allowlist entry no patch produces was accepted.'
    Assert-True ($stale.Reason -like '*any more*') `
        "The stale allowlist entry was refused for the wrong reason: $($stale.Reason)"
} finally {
    Remove-Item -LiteralPath $allowlistRoot -Recurse -Force -ErrorAction SilentlyContinue
}

# Which catalog a receipt is judged against. A receipt describes a release that has shipped, so
# moving the patcher pin afterwards must not turn it into a failure; a release, whose receipt is
# cut against the catalog it was built from, must still be held to that catalog exactly.
#
# Driven against a real two-commit repository, because the whole question is what `git show` says
# at a commit and a fake cannot answer that.
#
# Every git call below goes through Invoke-FixtureGit, and that is not tidiness. On 2026-09-15
# this block ran from inside the pre-push hook, which is a git child process, so GIT_DIR and
# GIT_WORK_TREE were in its environment. `git -C <tempdir>` changes the working directory and
# does not override GIT_DIR, so `init` reused the real repository, `add -A` read the two-file
# temp tree through it and staged every other tracked file as deleted, and three fixture commits
# authored by Contracts landed on the branch and were pushed to main, where the tip deleted all
# 703 files. Clearing the environment is the fix; the assertion after init is what would have
# stopped it in the second it happened.
$toolchainRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-toolchain-" + [guid]::NewGuid().ToString('N'))

function Invoke-FixtureGit {
    <#
    .SYNOPSIS
        git against a fixture repository, with no inherited git environment.
    .DESCRIPTION
        Removes every GIT_* variable for the length of the call, so the repository git acts on is
        the one -C names and nothing else. Run from a hook, GIT_DIR alone is enough to point all
        of this at the real tree.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    try {
        return & git -C $Root @Arguments 2>&1
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

try {
    New-Item -ItemType Directory -Path (Join-Path $toolchainRoot 'gradle') -Force | Out-Null
    $catalogFile = Join-Path $toolchainRoot 'gradle/libs.versions.toml'
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('init', '--quiet') | Out-Null

    # Where git says it will write, asked before anything is written. A fixture that has taken
    # hold of the real repository fails here instead of committing to it.
    $fixtureGitDir = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', '--absolute-git-dir') |
        Select-Object -First 1)".Trim()
    $expectedGitDir = (Join-Path $toolchainRoot '.git')
    Assert-True ($fixtureGitDir -and
        ([IO.Path]::GetFullPath($fixtureGitDir).TrimEnd('\', '/') -ieq [IO.Path]::GetFullPath($expectedGitDir).TrimEnd('\', '/'))) `
        ("The fixture repository resolved to $fixtureGitDir, not $expectedGitDir. Refusing to " +
            'write: this is the shape that put three fixture commits on the real branch.')

    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.email', 'contracts@example.invalid') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('config', 'user.name', 'Contracts') | Out-Null

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "1.29.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'release', '--quiet') | Out-Null
    $releaseCommitSha = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()

    # One file in the fixture, so one file in its first commit. A commit that carries hundreds is
    # a commit against somebody else's repository.
    $firstCommitFiles = @(Invoke-FixtureGit -Root $toolchainRoot `
        -Arguments @('show', '--name-only', '--format=', 'HEAD') | Where-Object { "$_".Trim() })
    Assert-True ($firstCommitFiles.Count -eq 1 -and "$($firstCommitFiles[0])".Trim() -eq 'gradle/libs.versions.toml') `
        ("The fixture's first commit touched $($firstCommitFiles.Count) files: " +
            (($firstCommitFiles | Select-Object -First 5) -join ', '))

    Set-Content -LiteralPath $catalogFile -Encoding UTF8 -Value @(
        '[versions]', 'morphe-patcher = "1.13.0"', 'manager-floor = "1.30.0"')
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'move the pin', '--quiet') | Out-Null

    $workingToolchain = Read-CatalogToolchain -Text (Get-Content -LiteralPath $catalogFile -Raw) `
        -Source 'the working catalog'
    Assert-True ($workingToolchain.PatcherVersion -eq '1.13.0' -and $workingToolchain.ManagerFloor -eq '1.30.0') `
        "The working catalog was not read: $($workingToolchain.PatcherVersion), $($workingToolchain.ManagerFloor)"

    # The source push after the pin moved: the receipt's own commit still pinned 1.12.0, so that
    # is what it answers for, and the push this used to stop now goes through.
    $atRelease = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $releaseCommitSha `
        -WorkingToolchain $workingToolchain
    Assert-True ($atRelease.Toolchain.PatcherVersion -eq '1.12.0') `
        "The receipt was not held to the patcher its own commit pinned: $($atRelease.Toolchain.PatcherVersion)"
    Assert-True ($atRelease.Toolchain.ManagerFloor -eq '1.29.0') `
        "The receipt was not held to the Manager floor its own commit pinned: $($atRelease.Toolchain.ManagerFloor)"
    Assert-True ($atRelease.Note -like '*1.12.0*' -and $atRelease.Note -like '*1.13.0*') `
        "The difference between the two catalogs was not reported: $($atRelease.Note)"

    # The release push: the receipt's commit is the commit being released, so the catalog it is
    # held to is the working one and the strict comparison is unchanged. Without this case the
    # one above would pass just as well if the check had been turned off.
    $head = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atHead = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $head -WorkingToolchain $workingToolchain
    Assert-True ($atHead.Toolchain.PatcherVersion -eq '1.13.0' -and $atHead.Toolchain.ManagerFloor -eq '1.30.0') `
        "A receipt at the released commit was not held to that commit's catalog: $($atHead.Toolchain.PatcherVersion)"
    Assert-True ($null -eq $atHead.Note) "An unchanged catalog still reported a difference: $($atHead.Note)"

    # A commit with no catalog in it, and a receipt naming no commit at all. Both fall back to
    # the working catalog rather than throwing, and the first says so.
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rm', '--quiet', '--', 'gradle/libs.versions.toml') | Out-Null
    Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', 'no catalog', '--quiet') | Out-Null
    $bare = "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') |
        Select-Object -First 1)".Trim()
    $atBare = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit $bare -WorkingToolchain $workingToolchain
    Assert-True ($atBare.Toolchain.PatcherVersion -eq '1.13.0') `
        'A commit with no catalog did not fall back to the working one.'
    Assert-True ($atBare.Note -like '*no version catalog*') `
        "The fallback was not reported: $($atBare.Note)"

    $noCommit = Resolve-ReceiptToolchain -Root $toolchainRoot -Commit '' -WorkingToolchain $workingToolchain
    Assert-True ($noCommit.Toolchain.PatcherVersion -eq '1.13.0' -and $null -eq $noCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working catalog.'

    # The patch list, the same way: a patch renamed after the release is held to the name its own
    # commit carried, which is what the hold after 0.58.0 needed; the released commit itself is
    # held to the working list; no list at that commit, or no commit, falls back to the working one.
    $listFile = Join-Path $toolchainRoot 'patches-list.json'
    function Save-FixtureList([string[]]$Names) {
        $list = @{ patches = @($Names | ForEach-Object { @{ name = $_; compatiblePackages = @{ 'com.example' = @('1.0.0') } } }) }
        Set-Content -LiteralPath $listFile -Encoding UTF8 -Value ($list | ConvertTo-Json -Depth 5)
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('add', '-A') | Out-Null
        Invoke-FixtureGit -Root $toolchainRoot -Arguments @('commit', '-m', "list $($Names -join ' ')", '--quiet') | Out-Null
        return "$(Invoke-FixtureGit -Root $toolchainRoot -Arguments @('rev-parse', 'HEAD') | Select-Object -First 1)".Trim()
    }
    $listReleased = Save-FixtureList @('Alpha', 'Beta')
    $listNow = Save-FixtureList @('Alpha', 'Gamma')
    $workingList = Get-Content -LiteralPath $listFile -Raw | ConvertFrom-Json
    $atListRelease = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listReleased -WorkingPatchList $workingList
    $namesAtRelease = @($atListRelease.PatchList.patches | ForEach-Object { [string]$_.name }) -join ','
    Assert-True ($namesAtRelease -eq 'Alpha,Beta' -and $atListRelease.Note -like '*2 patches*') `
        "The receipt was not held to the patch list its own commit carried: $namesAtRelease / $($atListRelease.Note)"
    $atListHead = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $listNow -WorkingPatchList $workingList
    Assert-True ((@($atListHead.PatchList.patches | ForEach-Object { [string]$_.name }) -join ',') -eq 'Alpha,Gamma' -and
        $null -eq $atListHead.Note) "A receipt at the released commit was not held to the working patch list: $($atListHead.Note)"
    $atNoList = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit $releaseCommitSha -WorkingPatchList $workingList
    Assert-True ($atNoList.PatchList -eq $workingList -and $atNoList.Note -like '*no patch list*') `
        "A commit with no patch list did not fall back to the working one: $($atNoList.Note)"
    $atNoCommit = Resolve-ReceiptCatalog -Root $toolchainRoot -Commit '' -WorkingPatchList $workingList
    Assert-True ($atNoCommit.PatchList -eq $workingList -and $null -eq $atNoCommit.Note) `
        'A receipt naming no commit did not fall back quietly to the working patch list.'
    # And the release check hands the receipt that list, names and target both, rather than the
    # working one. A helper nothing calls would pass every case above.
    $factsSource = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'validate-release-facts.ps1') -Raw
    Assert-True ($factsSource -match '-ExpectedPatchNames @\(\$resolvedList\.PatchList\.patches' -and
        $factsSource -match '\$receiptTarget = Get-PatchTarget -PatchList \$resolvedList\.PatchList' -and
        $factsSource -match '-ExpectedPackageName \$receiptTarget\.PackageName') `
        'validate-release-facts.ps1 no longer holds the receipt to the patch list its own commit carried.'

    # A catalog that pins nothing usable still stops the run, rather than being read as blank.
    foreach ($broken in @(
        @{ Name = 'no patcher pin'; Lines = @('[versions]', 'manager-floor = "1.29.0"') },
        @{ Name = 'no Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"') },
        @{ Name = 'an unusable Manager floor'; Lines = @('[versions]', 'morphe-patcher = "1.12.0"', 'manager-floor = "latest"') })) {
        Assert-Throws { Read-CatalogToolchain -Text ($broken.Lines -join "`n") -Source 'the test catalog' } `
            '*the test catalog*' "A catalog with $($broken.Name) was read without complaint."
    }
} finally {
    Remove-Item -LiteralPath $toolchainRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release receipt schema, manifest reading and validation contracts passed'

# --- validate-release-facts.ps1 -------------------------------------------------------------
#
# The gate the pre-push hook runs on every push that touches a published file, and the one a
# release cannot go out without. Its receipt half has been exercised above since the receipt
# existed; the facts half, which is what holds README.md and patches-bundle.json to the
# generated catalog, had never been shown to fail at all.
#
# Driven against a copy of this checkout rather than a hand-built tree: a fixture assembled by
# hand is a second opinion about what the release files look like, and the thing worth catching
# is a real file drifting from the real catalog. One fact is moved per case.

$factsScript = Join-Path $PSScriptRoot 'validate-release-facts.ps1'
$factsRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-facts-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $factsRoot -Force | Out-Null
    foreach ($relative in @('patches-list.json', 'patches-bundle.json', 'gradle.properties', 'README.md', 'CHANGELOG.md')) {
        Copy-Item -LiteralPath (Join-Path $Root $relative) -Destination (Join-Path $factsRoot $relative)
    }
    New-Item -ItemType Directory -Path (Join-Path $factsRoot 'gradle') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') `
        -Destination (Join-Path $factsRoot 'gradle/libs.versions.toml')
    $bugFormRelative = '.github/ISSUE_TEMPLATE/bug_report.yml'
    New-Item -ItemType Directory -Path (Join-Path $factsRoot '.github/ISSUE_TEMPLATE') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root $bugFormRelative) -Destination (Join-Path $factsRoot $bugFormRelative)
    $guideRelative = 'concepts/patch-development.md'
    New-Item -ItemType Directory -Path (Join-Path $factsRoot 'concepts') -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root $guideRelative) -Destination (Join-Path $factsRoot $guideRelative)

    function Invoke-Facts {
        param([switch]$WithUrls)
        $arguments = @{ Root = $factsRoot; SkipDescriptionTestCount = $true }
        if (-not $WithUrls) { $arguments['SkipUrlCheck'] = $true }
        # 6>, not *>. The check says what it found with Write-Host, which is the information
        # stream, and that is all this wants to hide. Redirecting every stream also swallows the
        # terminating error, so each case below was accepted in silence and proved nothing.
        & $factsScript @arguments 6> $null
    }

    function Set-FactsFile {
        param([string]$Name, [scriptblock]$Edit)
        $path = Join-Path $factsRoot $Name
        $text = Get-Content -LiteralPath $path -Raw
        Set-Content -LiteralPath $path -Value (& $Edit $text) -Encoding UTF8 -NoNewline
    }

    # A prepare window: the source version has moved past the index, which still describes the
    # last release, and the gate allows that lag for an index that did not change. The copied
    # tree is not a release tree then, and holding it to the lagging index failed the control
    # for a reason that had nothing to do with the check (2026-09-17, 0.40.0 being prepared over
    # the 0.39.0 index). The copied index is written up to the catalog it sits beside, its
    # version strings and its patch count, so every case below still moves exactly one fact
    # and is judged on the strict path.
    # A release hold is the same lag with the version standing still: patches join the catalog
    # while the index keeps the published count (2026-09-23, 93 against 0.58.0's 91), so the count
    # is synced even when the version already matches.
    function Sync-FixtureIndex {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $indexPath = Join-Path $factsRoot 'patches-bundle.json'
        $indexText = Get-Content -LiteralPath $indexPath -Raw
        $indexVersion = "$(($indexText | ConvertFrom-Json).version)"
        $count = @((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json).patches).Count
        $synced = $indexText
        if ($indexVersion -ne $fixtureVersion) { $synced = $synced -replace [regex]::Escape($indexVersion), $fixtureVersion }
        $synced = $synced -replace '\b\d+ patches\b', "$count patches"
        # And the TikTok builds the catalog declares, where the index names its target: two since
        # 47.1.3 joined 47.0.3, while the published index still names the one it went out with.
        # Only the headline: the Manager changelog groups from the first ### on quote CHANGELOG.md
        # word for word, and a retarget left their "TikTok 47.1.4" rewritten to 47.4.3 (2026-10-17).
        $catalogTargets = Format-VersionList -Versions @((Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json)).PackageVersions)
        $groupStart = $synced.IndexOf('### ', [StringComparison]::Ordinal)
        if ($groupStart -lt 0) { $groupStart = $synced.Length }
        $synced = [regex]::Replace($synced.Substring(0, $groupStart), 'TikTok\s+\d+(?:\.\d+)+(?:(?:,\s*|,?\s+and\s+)\d+(?:\.\d+)+)*', "TikTok $catalogTargets") +
            $synced.Substring($groupStart)
        # The 0.60.0 index went out on the owner's word with the gate skipped, and its description
        # quotes the runtime count alone. The cases below move one count at a time against the two
        # a gated release quotes, so the copy gains the missing one rather than every scripts change
        # failing here until the next release.
        if ($synced -notmatch '\b\d+ patch tests passed\b') {
            $synced = $synced -replace '\b(\d+ runtime tests passed)\b', '$1 and 421 patch tests passed'
        }
        # The Manager changelog groups quote the index's own CHANGELOG section, so a copy moved up
        # to the source version quotes that version's groups. 0.75.0 was the first index to carry
        # groups, and preparing 0.76.0 over it failed every scripts push here (2026-10-18). They come
        # from this checkout's CHANGELOG, since a case resets the index before its edited copy.
        if ($indexVersion -ne $fixtureVersion) {
            $document = $synced | ConvertFrom-Json
            $description = [string]$document.description
            $groupMatch = [regex]::Match($description, '(?m)^### ')
            if ($groupMatch.Success) {
                $changelog = Get-Content -LiteralPath (Join-Path $Root 'CHANGELOG.md') -Raw
                $document.description = $description.Substring(0, $groupMatch.Index) +
                    (Get-ManagerChangelogGroups -Text $changelog -Version $fixtureVersion)
                $synced = $document | ConvertTo-Json -Depth 100
            }
        }
        if ($synced -ceq $indexText) { return }
        Set-FactsFile 'patches-bundle.json' { param($text) $synced }
    }

    # The bug form names the published version and its newest TikTok target, which the synced
    # index above now names too.
    function Sync-FixtureBugForm {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $publishedHere = "$((Get-Content -LiteralPath (Join-Path $Root 'patches-bundle.json') -Raw | ConvertFrom-Json).version)"
        $newestTarget = (Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json)).PackageVersion
        Set-FactsFile $bugFormRelative {
            param($text) $text -replace ('Version ' + [regex]::Escape($publishedHere) + ' for TikTok \d+(?:\.\d+)+'), "Version $fixtureVersion for TikTok $newestTarget"
        }
    }

    # The development guide quotes the source version and counts, and the published bundle the
    # index names. The copy is written up to the fixture's catalog and its synced index, the way
    # the index is, so each case below moves one fact of the guide against a tree that agrees.
    function Sync-FixtureGuide {
        $fixtureVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
            -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''
        $count = @((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json).patches).Count
        $index = Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json
        $indexCount = [regex]::Match([string]$index.description, '(\d+) patches').Groups[1].Value
        Set-FactsFile $guideRelative {
            param($text)
            $text = $text -replace '(?m)^Source snapshot: version \S+, \d+ patch entries\.', "Source snapshot: version ${fixtureVersion}, $count patch entries."
            $text -replace '(?m)^Published bundle: version \S+, \d+ patches\.', "Published bundle: version $($index.version), $indexCount patches."
        }
    }

    function Reset-FactsFile {
        param([string]$Name)
        Copy-Item -LiteralPath (Join-Path $Root $Name) -Destination (Join-Path $factsRoot $Name) -Force
        if ($Name -eq 'patches-bundle.json') { Sync-FixtureIndex }
        if ($Name -eq $bugFormRelative) { Sync-FixtureBugForm }
        if ($Name -eq $guideRelative) { Sync-FixtureGuide }
    }

    function Set-FixtureManagerChangelog {
        param([string[]]$Changes)
        $indexPath = Join-Path $factsRoot 'patches-bundle.json'
        $document = Get-Content -LiteralPath $indexPath -Raw | ConvertFrom-Json
        $group = "### Fixes`n`n" + (@($Changes | ForEach-Object { "* **TikTok:** $_" }) -join "`n")
        $changelogPath = Join-Path $factsRoot 'CHANGELOG.md'
        $changelog = Get-Content -LiteralPath $changelogPath -Raw
        $release = [regex]::Match($changelog,
            "(?ms)^## $([regex]::Escape([string]$document.version)) \([^\r\n)]+\)\r?\n(?<Body>.*?)(?=^## |\z)")
        if (-not $release.Success) { throw "The fixture CHANGELOG has no $($document.version) section." }
        $body = $release.Groups['Body']
        $changelog = $changelog.Substring(0, $body.Index) + "`n$group`n`n" +
            $changelog.Substring($body.Index + $body.Length)
        Set-Content -LiteralPath $changelogPath -Value $changelog -Encoding UTF8 -NoNewline
        $existing = [regex]::Match([string]$document.description, '(?m)^### ')
        $facts = if ($existing.Success) { ([string]$document.description).Substring(0, $existing.Index).TrimEnd() } else { [string]$document.description }
        $document.description = $facts + "`n`n$group"
        Set-Content -LiteralPath $indexPath -Value ($document | ConvertTo-Json -Depth 100) -Encoding UTF8 -NoNewline
    }

    Sync-FixtureIndex
    Sync-FixtureBugForm
    Sync-FixtureGuide

    # The control. Everything below is this same tree with one fact moved, so a failure there is
    # the moved fact talking and not the fixture being wrong.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused an unmodified copy of this checkout.'

    $catalogVersion = ((Get-Content -LiteralPath (Join-Path $factsRoot 'gradle.properties')) `
        -match '^version\s*=' | Select-Object -First 1) -replace '^version\s*=\s*', ''

    # The lagging path: a published index behind the catalog, here in its patch count and its
    # targets at the same version, which is a release hold with a patch added. There the published
    # facts decide what the description and the bug form have to say, and the description is read
    # from -DescriptionText instead of gh. Every other case syncs the index up to the catalog, so
    # this branch only ever ran on a real push; on 2026-09-26 it held two bugs the contracts passed:
    # one published target read as a string ("TikTok 4"), and a lookahead that refused "47.0.3." at
    # the end of a sentence.
    $beforeLag = (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
    Set-FactsFile 'patches-bundle.json' {
        param($text)
        $document = $text | ConvertFrom-Json
        $description = [string]$document.description
        $groups = [regex]::Match($description, '(?m)^### ')
        $facts = if ($groups.Success) { $description.Substring(0, $groups.Index) } else { $description }
        $suffix = if ($groups.Success) { $description.Substring($groups.Index) } else { '' }
        $document.description = [regex]::Replace(($facts -replace '\b\d+ patches\b', '90 patches'),
            'TikTok\s+\d+(?:\.\d+)+(?:(?:,\s*|,?\s+and\s+)\d+(?:\.\d+)+)*', 'TikTok 47.0.3') + $suffix
        $document | ConvertTo-Json -Depth 100
    }
    $afterLag = (Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw | ConvertFrom-Json).description
    $groupsBeforeLag = [regex]::Match($beforeLag, '(?ms)^### .*').Value
    Assert-True ([regex]::Match($afterLag, '(?ms)^### .*').Value -ceq $groupsBeforeLag) `
        'The lagging-index fixture changed published changelog evidence along with release facts.'
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace ('Version ' + [regex]::Escape($catalogVersion) + ' for TikTok \d+(?:\.\d+)+'), "Version $catalogVersion for TikTok 47.0.3"
    }
    Set-FactsFile $guideRelative {
        param($text) $text -replace '(?m)^Published bundle: version (\S+), \d+ patches\.', 'Published bundle: version $1, 90 patches.'
    }
    $lagging = @{ Root = $factsRoot; SkipDescriptionTestCount = $true; SkipUrlCheck = $true; AllowPublishedIndexLag = $true }
    & $factsScript @lagging -DescriptionText "Hushfeed v$($catalogVersion): TikTok with less noise. 90 patches for TikTok 47.0.3." 6> $null
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'A release hold with a patch added, its index still on the published facts, was refused.'
    $catalogCount = @((Get-Content -LiteralPath (Join-Path $factsRoot 'patches-list.json') -Raw | ConvertFrom-Json).patches).Count
    Assert-Throws { & $factsScript @lagging -DescriptionText "Hushfeed v$($catalogVersion): $catalogCount patches for TikTok 47.0.3 and 47.1.3." 6> $null } `
        '*does not say 90 patches*' 'While the index lags, the description was held to the catalog instead of the published index.'
    # A description can leave the release facts out (the Hush repos' descriptions dropped them on
    # 2026-10-14); only a fact it states is held to the release.
    & $factsScript @lagging -DescriptionText 'Hushfeed Morphe patches for TikTok.' 6> $null
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'A description that names no version, patch count or TikTok version was refused.'
    Assert-Throws { & $factsScript @lagging -DescriptionText 'Hushfeed v0.1.0: Morphe patches for TikTok.' 6> $null } `
        '*does not say v*' 'A description that names an old Hushfeed version passed.'
    Reset-FactsFile 'patches-bundle.json'
    Reset-FactsFile $bugFormRelative
    Reset-FactsFile $guideRelative

    # Manager reads the release's ### groups after the description facts. Those bullets may name
    # older builds and counts, so they cannot satisfy or contradict the prose contract above them.
    Set-FixtureManagerChangelog @('TikTok 9.9.9 once had 3 patches.')
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check treated a CHANGELOG bullet as description facts.'
    Reset-FactsFile 'patches-bundle.json'
    Reset-FactsFile 'CHANGELOG.md'

    Set-FixtureManagerChangelog @("The current list has $catalogCount patches.")
    Set-FactsFile 'patches-bundle.json' {
        param($text)
        $document = $text | ConvertFrom-Json
        $description = [string]$document.description
        $group = [regex]::Match($description, '(?m)^### ')
        $document.description = ($description.Substring(0, $group.Index) -replace
            "\b$catalogCount patches\b", '3 patches') + $description.Substring($group.Index)
        $document | ConvertTo-Json -Depth 100
    }
    Assert-Throws { Invoke-Facts } '*bundle description patch count*' `
        'A correct count in a CHANGELOG group hid a stale count in the description facts.'
    Reset-FactsFile 'patches-bundle.json'
    Reset-FactsFile 'CHANGELOG.md'

    Set-FixtureManagerChangelog @('First complete change.', 'Second complete change.')
    Set-FactsFile 'patches-bundle.json' {
        param($text)
        $document = $text | ConvertFrom-Json
        $document.description = ([string]$document.description).Replace(
            "`n* **TikTok:** Second complete change.", '')
        $document | ConvertTo-Json -Depth 100
    }
    Assert-Throws { Invoke-Facts } '*Manager changelog does not match*' `
        'A truncated Manager changelog passed the release facts gate.'
    Reset-FactsFile 'patches-bundle.json'
    Reset-FactsFile 'CHANGELOG.md'

    # Manager decodes created_at as kotlinx.datetime.LocalDateTime, not Instant.
    # A trailing Z produces its generic "remote metadata file is unavailable" error,
    # even when both the JSON and bundle download answer HTTP 200.
    foreach ($invalidTimestamp in @(
            '"2026-09-20T21:23:31Z"',
            '"2026-09-20T21:23:31+00:00"',
            '"2026-02-30T21:23:31"',
            '"2026-09-20"',
            '""',
            'null',
            '1790000000')) {
        Set-FactsFile 'patches-bundle.json' {
            param($text)
            $text -replace '"created_at"\s*:\s*("[^"\r\n]*"|null|\d+)', ('"created_at": ' + $invalidTimestamp)
        }
        Assert-Throws { Invoke-Facts } '*created_at*' `
            "An index with a Manager-incompatible created_at was accepted: $invalidTimestamp"
        Reset-FactsFile 'patches-bundle.json'
    }
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '"created_at"\s*:\s*"[^"\r\n]*"\s*,', ''
    }
    Assert-Throws { Invoke-Facts } '*created_at*' 'An index without created_at was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # A published index naming a version the catalog does not build. This is the shape v0.28.0
    # shipped in: the index said one thing and the bundle behind it was another.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace [regex]::Escape('"' + $catalogVersion + '"'), '"0.0.1"'
    }
    Assert-Throws { Invoke-Facts } '*' 'A published index naming another version was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The count the published description quotes, which is what somebody reads before they
    # install. A number nobody would notice by eye, which is why the catalog is the source of it.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace '\b\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'An index counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The development guide states the current source version and counts in prose. It sat on
    # 0.70.0 and 131 entries over a 0.71.x checkout, and a published bundle two releases back,
    # with nothing reading it. Each fact moves alone, against a copy that agrees with the catalog.
    Set-FactsFile $guideRelative {
        param($text) $text -replace '(?m)^Source snapshot: version \S+,', 'Source snapshot: version 0.0.1,'
    }
    Assert-Throws { Invoke-Facts } '*concepts/patch-development.md says source 0.0.1*' `
        'A development guide naming another source version was accepted.'
    Reset-FactsFile $guideRelative
    Set-FactsFile $guideRelative {
        param($text) $text -replace '(?m)^(Source snapshot: version \S+,) \d+ patch entries\.', '$1 3 patch entries.'
    }
    Assert-Throws { Invoke-Facts } '*concepts/patch-development.md says source*3 patch entries*' `
        'A development guide counting source patch entries the catalog does not have was accepted.'
    Reset-FactsFile $guideRelative
    Set-FactsFile $guideRelative {
        param($text) $text -replace '(?m)^Published bundle: version \S+,', 'Published bundle: version 0.0.1,'
    }
    Assert-Throws { Invoke-Facts } '*concepts/patch-development.md says the published bundle is 0.0.1*' `
        'A development guide naming another published bundle was accepted.'
    Reset-FactsFile $guideRelative
    Set-FactsFile $guideRelative {
        param($text) $text -replace '(?m)^\| Feed \| \d+ \|', '| Feed | 3 |'
    }
    Assert-Throws { Invoke-Facts } '*stale category table*Feed is 3 there*' `
        'A development guide with a stale category count was accepted.'
    Reset-FactsFile $guideRelative
    Set-FactsFile $guideRelative {
        param($text) $text -replace "catalog's \d+ patches are grouped", "catalog's 3 patches are grouped"
    }
    Assert-Throws { Invoke-Facts } '*concepts/patch-development.md groups 3 patches*' `
        'A development guide grouping a patch total the catalog does not have was accepted.'
    Reset-FactsFile $guideRelative
    Remove-Item -LiteralPath (Join-Path $factsRoot $guideRelative)
    Assert-Throws { Invoke-Facts } '*concepts/patch-development.md is missing*' `
        'A checkout with no development guide was accepted.'
    Reset-FactsFile $guideRelative

    # The same number in the README, which is the other half of the same promise.
    Set-FactsFile 'README.md' {
        param($text) $text -replace '\b\d+ patches\b', '3 patches'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README counting patches the catalog does not have was accepted.'
    Reset-FactsFile 'README.md'

    # The Manager floor in the README is what stops somebody being told to use a Manager that
    # refuses the bundle, so it is held to the patcher the catalog pins.
    #
    # The floor is read out of the fixture rather than written here. It used to name 1.29.0, and
    # when the catalog moved to Manager 1.30.0 the replacement below stopped matching: the README
    # went in unchanged, the check passed as it should have, and this case failed with "No error
    # was raised" while the gate it covers was working perfectly. A version literal in a test
    # goes stale on the next bump, and does it silently until something reads the message.
    $fixtureFloor = ([regex]::Match(
        (Get-Content -LiteralPath (Join-Path $factsRoot 'gradle/libs.versions.toml') -Raw),
        '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')).Groups[1].Value
    Assert-True ($fixtureFloor -match '^\d+\.\d+\.\d+$') `
        "The fixture catalog does not pin a Manager floor, so this case would prove nothing: $fixtureFloor"
    Set-FactsFile 'README.md' {
        param($text) $text -replace ('Morphe Manager ' + [regex]::Escape($fixtureFloor) + ' or newer'),
            'Morphe Manager 1.20.0 or newer'
    }
    Assert-Throws { Invoke-Facts } '*' 'A README naming a Manager older than the patcher needs was accepted.'
    Reset-FactsFile 'README.md'

    # The heading that says this version shipped. Renaming it is what happened on 2026-09-14,
    # and the file is read by this check and by nothing else.
    Set-FactsFile 'CHANGELOG.md' {
        param($text) $text -replace ('(?m)^##\s+' + [regex]::Escape($catalogVersion) + '\b.*$'), '## Unreleased'
    }
    Assert-Throws { Invoke-Facts } '*' 'A CHANGELOG with no heading for the built version was accepted.'
    Reset-FactsFile 'CHANGELOG.md'

    # The bug form's placeholders, which sat three TikTok releases behind the target before
    # anything read them. One for the version line, one for the manager line.
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Version \S+ for TikTok )\S+', '${1}46.2.3'
    }
    Assert-Throws { Invoke-Facts } '*bug report form version placeholder*' `
        'A bug report form naming an old TikTok build was accepted.'
    Reset-FactsFile $bugFormRelative
    Set-FactsFile $bugFormRelative {
        param($text) $text -replace '(placeholder:\s*Morphe Manager )\S+', '${1}1.20.0'
    }
    Assert-Throws { Invoke-Facts } '*bug report form Manager placeholder*' `
        'A bug report form naming a Manager below the floor was accepted.'
    Reset-FactsFile $bugFormRelative

    # A dead link in the index, answered from this machine so the case needs no network of its
    # own: nothing listens on port 1, so the request is refused before it leaves the host.
    Set-FactsFile 'patches-bundle.json' {
        param($text) $text -replace 'https://github\.com/SysAdminDoc/hushfeed/releases/download/[^"]+',
            'http://127.0.0.1:1/patches.mpp'
    }
    Assert-Throws { Invoke-Facts -WithUrls } '*' 'An index pointing at a dead address was accepted.'
    Reset-FactsFile 'patches-bundle.json'

    # The test counts the description quotes, which only the strict path reads: a release, or the
    # push that rewrites the index. The copied tree holds no test results, so each folder gets a
    # suite of exactly as many tests as the copied description names, and one fact moves per case.
    $factsDescription = [string](Get-Content -LiteralPath (Join-Path $factsRoot 'patches-bundle.json') -Raw |
        ConvertFrom-Json).description
    $runtimeQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) runtime tests passed\b').Groups[1].Value)
    $patchQuoted = [int]([regex]::Match($factsDescription, '\b(\d+) patch tests passed\b').Groups[1].Value)
    Assert-True ($runtimeQuoted -gt 0 -and $patchQuoted -gt 0) `
        "The copied description quotes no test counts, so these cases would prove nothing: $factsDescription"
    function Write-FactsResults {
        param([string]$Folder, [string]$Suite, [int]$Tests, [int]$Skipped = 0)
        $directory = Join-Path $factsRoot $Folder
        Remove-Item -LiteralPath $directory -Recurse -Force -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $cases = (1..$Tests | ForEach-Object {
            if ($_ -le $Skipped) { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"><skipped/></testcase>" }
            else { "<testcase name=`"t$_`" classname=`"fixture.$Suite`"/>" }
        }) -join ''
        Set-Content -LiteralPath (Join-Path $directory "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
            "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"$Tests`" " +
            "skipped=`"$Skipped`" failures=`"0`" errors=`"0`">$cases</testsuite>")
        if ($Folder.StartsWith('patches/')) {
            # Only these input files exist in this deliberately small release fixture.
            $receiptPaths = @('patches-list.json', 'patches/src/test/kotlin/fixture/PatchTest.kt',
                'patches/src/test/kotlin/fixture/NativeTest.kt', 'patches/src/test/kotlin/fixture/DocumentationTest.kt')
            if ($Folder.EndsWith('/documentationTest')) { $receiptPaths += @('README.md', 'patches-bundle.json') }
            elseif ($Folder.EndsWith('/test')) { $receiptPaths += 'extensions/tiktok/src/test/java/fixture/RuntimeTest.java' }
            $lines = @($receiptPaths | Where-Object { Test-Path -LiteralPath (Join-Path $factsRoot $_) } |
                ForEach-Object {
                    '{0} {1}' -f (Get-FileHash -LiteralPath (Join-Path $factsRoot $_) -Algorithm SHA256).Hash.ToLowerInvariant(), $_
                })
            Set-Content -LiteralPath (Join-Path $directory 'source-inputs.sha256') -Encoding UTF8 -Value $lines
        }
    }
    function Invoke-StrictFacts { & $factsScript -Root $factsRoot -SkipUrlCheck 6> $null }
    $runtimeResults = 'extensions/tiktok/build/test-results/testDebugUnitTest'
    $patchResults = 'patches/build/test-results/test'
    $documentationResults = 'patches/build/test-results/documentationTest'
    $nativeResults = 'patches/build/test-results/nativeTest'
    $patchNativeQuoted = $patchQuoted - 2
    try {
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchNativeQuoted
        Write-FactsResults $nativeResults 'NativeTest' 1
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused test results that match the description.'

        # A fixture test that skipped, which Gradle reports as a pass.
        Write-FactsResults $patchResults 'PatchTest' $patchNativeQuoted -Skipped 1
        Assert-Throws { Invoke-StrictFacts } '*skipped 1 test*' `
            'A release was checked against patch test results with a skipped fixture test.'

        # A count the run doesn't have, which is how "All 269 patch tests passed" was written.
        Write-FactsResults $patchResults 'PatchTest' ($patchNativeQuoted + 1)
        Assert-Throws { Invoke-StrictFacts } '*patch test count*' `
            'A description quoting a patch test count the run does not have was accepted.'

        # A test class deleted since the last run. Its results stay until the tests run again,
        # no source left is newer than them, and every class left has results, so its tests
        # were counted as passing. The sources go in first so the results are the newer files.
        $runtimeSource = Join-Path $factsRoot 'extensions/tiktok/src/test/java/fixture/RuntimeTest.java'
        $patchSource = Join-Path $factsRoot 'patches/src/test/kotlin/fixture/PatchTest.kt'
        $documentationSource = Join-Path $factsRoot 'patches/src/test/kotlin/fixture/DocumentationTest.kt'
        $nativeSource = Join-Path $factsRoot 'patches/src/test/kotlin/fixture/NativeTest.kt'
        foreach ($source in @($runtimeSource, $patchSource, $nativeSource, $documentationSource)) {
            New-Item -ItemType Directory -Path (Split-Path -Parent $source) -Force | Out-Null
            Set-Content -LiteralPath $source -Value '' -Encoding ASCII
        }
        function Add-OrphanResult([string]$Folder, [string]$Suite) {
            Set-Content -LiteralPath (Join-Path (Join-Path $factsRoot $Folder) "TEST-fixture.$Suite.xml") -Encoding UTF8 -Value (
                "<?xml version=`"1.0`" encoding=`"UTF-8`"?><testsuite name=`"fixture.$Suite`" tests=`"1`" " +
                "skipped=`"0`" failures=`"0`" errors=`"0`"><testcase name=`"t1`" classname=`"fixture.$Suite`"/></testsuite>")
        }
        Write-FactsResults $patchResults 'PatchTest' $patchNativeQuoted
        Write-FactsResults $nativeResults 'NativeTest' 1
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Write-FactsResults $runtimeResults 'RuntimeTest' ($runtimeQuoted - 1)
        Add-OrphanResult $runtimeResults 'GoneTest'
        Assert-Throws { Invoke-StrictFacts } '*runtime test results include 1 test class*GoneTest*' `
            'The runtime results of a deleted test class were counted.'
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' ($patchNativeQuoted - 1)
        Add-OrphanResult $patchResults 'GonePatchTest'
        Assert-Throws { Invoke-StrictFacts } '*patch test results include 1 test class*GonePatchTest*' `
            'The patch results of a deleted test class were counted.'
        # The control: the same sources with no orphan pass.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted
        Write-FactsResults $patchResults 'PatchTest' $patchNativeQuoted
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'The strict release check refused results that match their sources and the description.'

        Set-FixtureManagerChangelog @('An older run had 1 runtime test and 1 patch test pass.')
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Invoke-StrictFacts
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'CHANGELOG test counts were treated as the published validation facts.'
        Reset-FactsFile 'patches-bundle.json'
        Reset-FactsFile 'CHANGELOG.md'
        Write-FactsResults $documentationResults 'DocumentationTest' 1

        Set-FixtureManagerChangelog @("Validation had $runtimeQuoted runtime tests passed.")
        Set-FactsFile 'patches-bundle.json' {
            param($text)
            $document = $text | ConvertFrom-Json
            $description = [string]$document.description
            $group = [regex]::Match($description, '(?m)^### ')
            $document.description = ($description.Substring(0, $group.Index) -replace
                "\b$runtimeQuoted runtime tests passed\b", '1 runtime test passed') +
                $description.Substring($group.Index)
            $document | ConvertTo-Json -Depth 100
        }
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Assert-Throws { Invoke-StrictFacts } '*bundle description test count*' `
            'A correct runtime count in a CHANGELOG group hid a stale description fact.'
        Reset-FactsFile 'patches-bundle.json'
        Reset-FactsFile 'CHANGELOG.md'
        Write-FactsResults $documentationResults 'DocumentationTest' 1

        Set-FixtureManagerChangelog @("Validation had $patchQuoted patch tests passed.")
        Set-FactsFile 'patches-bundle.json' {
            param($text)
            $document = $text | ConvertFrom-Json
            $description = [string]$document.description
            $group = [regex]::Match($description, '(?m)^### ')
            $document.description = ($description.Substring(0, $group.Index) -replace
                "\b$patchQuoted patch tests passed\b", '1 patch test passed') +
                $description.Substring($group.Index)
            $document | ConvertTo-Json -Depth 100
        }
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Assert-Throws { Invoke-StrictFacts } '*bundle description patch test count*' `
            'A correct patch count in a CHANGELOG group hid a stale description fact.'
        Reset-FactsFile 'patches-bundle.json'
        Reset-FactsFile 'CHANGELOG.md'
        Write-FactsResults $documentationResults 'DocumentationTest' 1

        Write-FactsResults $documentationResults 'DocumentationTest' 1 -Skipped 1
        Assert-Throws { Invoke-StrictFacts } '*skipped 1 test*' `
            'A skipped documentation test was omitted from the release check.'
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        $documentationXml = Join-Path (Join-Path $factsRoot $documentationResults) 'TEST-fixture.DocumentationTest.xml'
        $validDocumentationXml = Get-Content -LiteralPath $documentationXml -Raw
        foreach ($attribute in @('failures', 'errors')) {
            Set-Content -LiteralPath $documentationXml -Encoding UTF8 -Value (
                $validDocumentationXml.Replace("$attribute=`"0`"", "$attribute=`"1`""))
            Assert-Throws { Invoke-StrictFacts } '*has failures=*' `
                "Documentation $attribute did not fail release validation."
        }
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        $duplicate = Join-Path (Join-Path $factsRoot $patchResults) 'TEST-fixture.DocumentationTest.xml'
        Copy-Item -LiteralPath $documentationXml -Destination $duplicate
        Assert-Throws { Invoke-StrictFacts } '*Duplicate patch test results*' `
            'A documentation suite duplicated in the fixture partition was counted twice.'
        Remove-Item -LiteralPath $duplicate
        Remove-Item -LiteralPath $documentationXml
        Assert-Throws { Invoke-StrictFacts } '*No patch test results*documentationTest*' `
            'A missing documentation partition was accepted.'
        Write-FactsResults $documentationResults 'DocumentationTest' 1
        Remove-Item -LiteralPath (Join-Path (Join-Path $factsRoot $nativeResults) 'TEST-fixture.NativeTest.xml')
        Assert-Throws { Invoke-StrictFacts } '*No patch test results*nativeTest*' `
            'A missing native partition was accepted.'
        Write-FactsResults $nativeResults 'NativeTest' 1

        # Content, not mtime, determines freshness in each partition.
        $partitionResults = [ordered]@{ $patchResults = @('PatchTest', $patchNativeQuoted)
            $nativeResults = @('NativeTest', 1); $documentationResults = @('DocumentationTest', 1) }
        function Write-OtherPartitions([string]$Stale) {
            foreach ($other in @($partitionResults.Keys | Where-Object { $_ -ne $Stale })) {
                Write-FactsResults $other $partitionResults[$other][0] $partitionResults[$other][1]
            }
        }
        foreach ($folder in @($patchResults, $nativeResults, $documentationResults)) {
            $receiptPath = Join-Path (Join-Path $factsRoot $folder) 'source-inputs.sha256'
            $receiptBytes = [IO.File]::ReadAllBytes($receiptPath)
            try {
                Remove-Item -LiteralPath $receiptPath
                Assert-Throws { Invoke-StrictFacts } '*No patch input receipt*' 'Missing content evidence was accepted.'
            } finally { [IO.File]::WriteAllBytes($receiptPath, $receiptBytes) }
            $inputPath = if ($folder -eq $patchResults) { $patchSource }
                elseif ($folder -eq $nativeResults) { $nativeSource } else { Join-Path $factsRoot 'README.md' }
            $originalBytes = [IO.File]::ReadAllBytes($inputPath)
            $originalDate = (Get-Item -LiteralPath $inputPath).LastWriteTimeUtc
            try {
                (Get-Item -LiteralPath $inputPath).LastWriteTimeUtc = [DateTime]::UtcNow.AddDays(1)
                Invoke-StrictFacts
                [IO.File]::WriteAllBytes($inputPath, $originalBytes + [Text.Encoding]::UTF8.GetBytes("`n<!-- input changed -->`n"))
                (Get-Item -LiteralPath $inputPath).LastWriteTimeUtc = $originalDate
                Write-OtherPartitions $folder
                $partitionName = Split-Path -Leaf $folder
                Assert-Throws { Invoke-StrictFacts } "*Patch test inputs changed in $partitionName.*" `
                    "Fresh results in another partition hid changed $folder inputs."
            } finally {
                [IO.File]::WriteAllBytes($inputPath, $originalBytes)
                (Get-Item -LiteralPath $inputPath).LastWriteTimeUtc = $originalDate
                Write-OtherPartitions ''
            }
        }
        # A runtime-only edit leaves the native and documentation results valid: rerunning the
        # partition that reads the extensions is enough. The time stays, so content decides.
        $runtimeBytes = [IO.File]::ReadAllBytes($runtimeSource)
        $runtimeDate = (Get-Item -LiteralPath $runtimeSource).LastWriteTimeUtc
        try {
            [IO.File]::WriteAllBytes($runtimeSource, [Text.Encoding]::UTF8.GetBytes('x' * ($runtimeBytes.Length + 1)))
            (Get-Item -LiteralPath $runtimeSource).LastWriteTimeUtc = $runtimeDate
            Assert-Throws { Invoke-StrictFacts } '*Patch test inputs changed in test.*' `
                'A runtime-only edit left the results of the partition that reads the extensions valid.'
            Write-FactsResults $patchResults 'PatchTest' $patchNativeQuoted
            Invoke-StrictFacts
            Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
                'A runtime-only edit invalidated native or documentation results that never read it.'
        } finally {
            [IO.File]::WriteAllBytes($runtimeSource, $runtimeBytes)
            (Get-Item -LiteralPath $runtimeSource).LastWriteTimeUtc = $runtimeDate
            Write-OtherPartitions ''
        }
        $indexInput = Join-Path $factsRoot 'patches-bundle.json'
        $indexBytes = [IO.File]::ReadAllBytes($indexInput)
        $indexDate = (Get-Item -LiteralPath $indexInput).LastWriteTimeUtc
        try {
            [IO.File]::WriteAllBytes($indexInput, $indexBytes + [Text.Encoding]::UTF8.GetBytes("`n "))
            (Get-Item -LiteralPath $indexInput).LastWriteTimeUtc = $indexDate
            Assert-Throws { Invoke-StrictFacts } '*Patch test inputs changed in documentationTest*' `
                'An index-only content change reused stale documentation results.'
            Write-FactsResults $documentationResults 'DocumentationTest' 1
            Invoke-StrictFacts
        } finally {
            [IO.File]::WriteAllBytes($indexInput, $indexBytes)
            (Get-Item -LiteralPath $indexInput).LastWriteTimeUtc = $indexDate
            Write-FactsResults $documentationResults 'DocumentationTest' 1
        }
        Invoke-StrictFacts
        foreach ($folder in @('extensions/tiktok/src', 'patches/src')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force
        }

        Remove-Item -LiteralPath (Join-Path $factsRoot 'patches') -Recurse -Force
        Assert-Throws { Invoke-StrictFacts } '*No patch test results*' `
            'A release was checked with no patch test results at all.'

        # Results the check is told to leave unread, as the pre-push hook does in its worktree,
        # where they can belong to another commit: read, a skipped runtime test fails the lenient
        # check; unread, it doesn't. A check that quotes counts can't be told to skip them.
        Write-FactsResults $runtimeResults 'RuntimeTest' $runtimeQuoted -Skipped 1
        Assert-Throws { Invoke-Facts } '*skipped=1*' 'A lenient check read past a skipped runtime test.'
        & $factsScript -Root $factsRoot -SkipDescriptionTestCount -SkipUrlCheck -SkipTestResults 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A check told to leave the test results unread read them anyway.'
        Assert-Throws { & $factsScript -Root $factsRoot -SkipUrlCheck -SkipTestResults 6> $null } `
            '*SkipDescriptionTestCount*' 'A check holding the description to its counts left the results unread.'
    } finally {
        foreach ($folder in @('patches', 'extensions')) {
            Remove-Item -LiteralPath (Join-Path $factsRoot $folder) -Recurse -Force -ErrorAction SilentlyContinue
        }
    }

    # And the same tree, once every fact is put back, is accepted again. Without this the cases
    # above would also pass against a fixture that had become permanently broken.
    Invoke-Facts
    Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
        'The release facts check refused the fixture after every change was put back.'
} finally {
    Remove-Item -LiteralPath $factsRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] release facts contracts passed'

# --- pre-push.ps1 ----------------------------------------------------------------------------
#
# Which files make the hook run the release check. The receipt is what the check holds a release
# to and the allowlist is what it accepts manifest changes from, and a push that moved only one
# of them ran no release check at all. Driven against a stub root whose validate script records
# that it was called, so the case proves the routing and not the check.

$prePushScript = Join-Path $PSScriptRoot 'pre-push.ps1'
$hookRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-hook-" + [guid]::NewGuid().ToString('N'))
$savedSkip = $env:HUSHFEED_SKIP_PRE_PUSH
$savedHookGit = @{}
# These cases invoke another hook against a foreign repository. Git's own hook environment
# must not leak into that repository or its local bare transport, including GIT_EXEC_PATH.
foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
    $savedHookGit[$variable.Name] = $variable.Value
    Remove-Item -LiteralPath ('Env:\' + $variable.Name)
}
try {
    $env:HUSHFEED_SKIP_PRE_PUSH = $null
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'scripts') -Force | Out-Null
    $factsMarker = Join-Path $hookRoot 'facts-ran.txt'
    $contractsMarker = Join-Path $hookRoot 'contracts-ran.txt'
    $signingMarker = Join-Path $hookRoot 'signing-ran.txt'
    $attestationMarker = Join-Path $hookRoot 'attestation-ran.txt'
    $bundleSignatureMarker = Join-Path $hookRoot 'bundle-signature-ran.txt'
    $searchLynxMarker = Join-Path $hookRoot 'search-lynx-ran.txt'
    $requireWrapper = Join-Path $hookRoot 'require-wrapper.txt'
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
        '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath)',
        "if ((Test-Path -LiteralPath '$requireWrapper') -and !(Test-Path -LiteralPath '$(Join-Path $hookRoot 'wrapper-ran.txt')')) { throw 'Release validation ran before documentation tests.' }",
        "Set-Content -LiteralPath '$factsMarker' -Value `"lag=`$AllowPublishedIndexLag verify=`$VerifyPublishedAsset artifact=`$ArtifactPath`"",
        'exit 0')
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Set-Content -LiteralPath '$contractsMarker' -Value 'ran'",
        'exit 0')
    Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/test-apk-signing.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)',
        "Set-Content -LiteralPath '$signingMarker' -Value `"root=`$Root`"",
        'exit 0')
    foreach ($suite in @(
        @('test-release-attestation.ps1', $attestationMarker),
        @('test-release-signature.ps1', $bundleSignatureMarker)
    )) {
        Set-Content -LiteralPath (Join-Path $hookRoot "scripts/$($suite[0])") -Encoding UTF8 -Value @(
            'param([string]$Root)', "Set-Content -LiteralPath '$($suite[1])' -Value 'ran'", 'exit 0')
    }
    New-Item -ItemType Directory -Path (Join-Path $hookRoot 'tools/verification-probe') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $hookRoot 'tools/verification-probe/test-search-lynx.ps1') -Encoding UTF8 -Value @(
        'param([string]$Root)', "Set-Content -LiteralPath '$searchLynxMarker' -Value `"root=`$Root`"", 'exit 0')

    function Invoke-Hook {
        param([string[]]$Paths)
        Remove-Item -LiteralPath $factsMarker, $contractsMarker, $signingMarker, $attestationMarker, $bundleSignatureMarker, $searchLynxMarker -Force -ErrorAction SilentlyContinue
        $global:LASTEXITCODE = 0
        & $prePushScript -Root $hookRoot -ChangedPaths $Paths 6> $null
        if ($LASTEXITCODE -ne 0) { throw "pre-push exited $LASTEXITCODE for $($Paths -join ', ')" }
    }

    # The control: a file no gate reads runs no gate, so a marker below is the routing talking.
    Invoke-Hook -Paths @('CONTRIBUTING.md')
    Assert-True (-not (Test-Path -LiteralPath $factsMarker) -and
        -not (Test-Path -LiteralPath $contractsMarker) -and
        -not (Test-Path -LiteralPath $signingMarker)) `
        'An unread documentation change selected release or catalog contracts.'

    # The previous routing fixtures covered scripts and catalog consumers, but no probe
    # source or signing input. Exercise the real selector without running SDK tools here.
    foreach ($inputPath in @('scripts/release-attestation.ps1', 'scripts/test-release-attestation.ps1',
        'scripts/validate-release-facts.ps1')) {
        Invoke-Hook -Paths @($inputPath)
        Assert-True (Test-Path -LiteralPath $attestationMarker) "An attestation input $inputPath skipped its contracts."
    }
    foreach ($inputPath in @('scripts/release-signature.ps1', 'scripts/test-release-signature.ps1',
        'scripts/validate-release-facts.ps1', 'cosign.pub')) {
        Invoke-Hook -Paths @($inputPath)
        Assert-True (Test-Path -LiteralPath $bundleSignatureMarker) "A signature input $inputPath skipped its contracts."
    }
    Assert-True (Test-Path -LiteralPath $factsMarker) 'A public-key change skipped release validation.'
    foreach ($signingInput in @(
        'scripts/apk-signing.ps1', 'scripts/SigningCertificateCheck.java',
        'scripts/SigningKeyFixtures.java', 'scripts/test-apk-signing.ps1',
        'scripts/patch-for-device.ps1', 'scripts/device-install.ps1', 'scripts/device-lease.ps1',
        'scripts/common.ps1', 'scripts/Resolve-Java.ps1',
        'scripts/patch-target.ps1', 'scripts/patch-report.ps1',
        'tools/verification-probe/build.ps1', 'tools/verification-probe/AndroidManifest.xml',
        'tools/verification-probe/src/Probe.java'
    )) {
        Invoke-Hook -Paths @($signingInput)
        Assert-True ((Get-Content -LiteralPath $signingMarker -Raw).Trim() -eq "root=$hookRoot") `
            "A device builder input $signingInput skipped its SDK signing fixtures or used another root."
        Assert-True (Test-Path -LiteralPath $contractsMarker) 'A probe input skipped the ordinary script contracts.'
    }
    foreach ($unrelatedInput in @('scripts/pre-push.ps1', 'scripts/time-patches.ps1',
            'tools/verification-probe/README.md', 'CONTRIBUTING.md')) {
        Invoke-Hook -Paths @($unrelatedInput)
        Assert-True (-not (Test-Path -LiteralPath $signingMarker)) `
            "An unrelated input $unrelatedInput selected SDK signing fixtures."
    }

    Invoke-Hook -Paths @('tools/verification-probe/tests/app/hushfeed/verification/StorageScanContract.java')
    Assert-True (Test-Path -LiteralPath $contractsMarker) 'A storage scanner test change skipped its contracts.'
    Assert-True (-not (Test-Path -LiteralPath $signingMarker)) 'A scanner JVM test selected SDK signing fixtures.'
    Assert-True (-not (Test-Path -LiteralPath $searchLynxMarker)) 'A storage scanner test change selected the Search Lynx contracts.'

    foreach ($searchLynxInput in @('tools/verification-probe/src/app/hushfeed/verification/SearchLynxEvidence.java',
            'tools/verification-probe/src/app/hushfeed/verification/SearchLynxSnapshot.java',
            'tools/verification-probe/tests/app/hushfeed/verification/SearchLynxEvidenceContract.java',
            'tools/verification-probe/test-search-lynx.ps1')) {
        Invoke-Hook -Paths @($searchLynxInput)
        Assert-True ((Test-Path -LiteralPath $searchLynxMarker) -and
            (Get-Content -LiteralPath $searchLynxMarker -Raw).Trim() -eq "root=$hookRoot") `
            "Search Lynx input $searchLynxInput skipped its contracts or used another root."
    }

    foreach ($probeTool in @('tools/verification-probe/probe-log.ps1', 'tools/verification-probe/strip-hunt.ps1',
            'tools/verification-probe/record-markers.ps1')) {
        Invoke-Hook -Paths @($probeTool)
        Assert-True (Test-Path -LiteralPath $contractsMarker) "A probe tool change $probeTool skipped its log-marker contracts."
        Assert-True (-not (Test-Path -LiteralPath $signingMarker)) "A probe tool change $probeTool selected SDK signing fixtures."
    }

    foreach ($auditInput in @('audit/run-audit.ps1', 'audit/evidence.ps1', 'audit/test-evidence.ps1', 'audit/catalog.json')) {
        Invoke-Hook -Paths @($auditInput)
        Assert-True (Test-Path -LiteralPath $contractsMarker) "Audit input $auditInput skipped its evidence contracts."
        Assert-True (-not (Test-Path -LiteralPath $signingMarker)) "Audit-only input $auditInput selected SDK signing fixtures."
    }

    Invoke-Hook -Paths @('release-receipt-0.31.0.json')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the release receipt ran no release check.'
    Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=True*') `
        'A receipt-only push took the strict published-index path.'

    Invoke-Hook -Paths @('scripts/manifest-delta-allowlist.txt')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the manifest delta allowlist ran no release check.'
    Assert-True (Test-Path -LiteralPath $contractsMarker) `
        'A push that changed the manifest delta allowlist skipped the script contract tests.'

    Invoke-Hook -Paths @('CHANGELOG.md')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the CHANGELOG ran no release check.'

    Invoke-Hook -Paths @('.github/ISSUE_TEMPLATE/bug_report.yml')
    Assert-True (Test-Path -LiteralPath $factsMarker) `
        'A push that changed only the bug report form ran no release check.'

    # A new remote branch can contain several unpublished commits. The code change here is in
    # the first commit and the tip changes only documentation. Looking at HEAD^..HEAD silently
    # misses the code and skips every build gate.
    $newBranchSource = Join-Path $hookRoot 'extensions/tiktok/src/main/java/FirstCommit.java'
    New-Item -ItemType Directory -Path (Split-Path -Parent $newBranchSource) -Force | Out-Null
    Set-Content -LiteralPath $newBranchSource -Encoding UTF8 -Value 'final class FirstCommit {}'
    & git -C $hookRoot init --quiet
    $actualHookGitDir = (& git -C $hookRoot rev-parse --absolute-git-dir).Trim()
    Assert-True ([IO.Path]::GetFullPath($actualHookGitDir).TrimEnd('\', '/') -ieq
        [IO.Path]::GetFullPath((Join-Path $hookRoot '.git')).TrimEnd('\', '/')) `
        'The hook fixture resolved outside its temporary repository; refusing to write.'
    & git -C $hookRoot config user.name 'Hook Contract'
    & git -C $hookRoot config user.email 'hook@example.invalid'
    & git -C $hookRoot add extensions/tiktok/src/main/java/FirstCommit.java
    & git -C $hookRoot commit --quiet -m 'code first'
    $firstCommit = (& git -C $hookRoot rev-parse HEAD).Trim()
    Set-Content -LiteralPath (Join-Path $hookRoot 'CONTRIBUTING.md') -Encoding UTF8 -Value 'tip only'
    & git -C $hookRoot add CONTRIBUTING.md
    & git -C $hookRoot commit --quiet -m 'docs tip'
    $newBranchHead = (& git -C $hookRoot rev-parse HEAD).Trim()
    # A stale local remote-tracking ref that already points at the code commit must not subtract
    # that commit from a new branch push. The destination advertises this branch as new.
    & git -C $hookRoot update-ref refs/remotes/origin/stale $firstCommit
    $newBranchRefs = "refs/heads/new $newBranchHead refs/heads/new $('0' * 40)"
    $savedNewBranchPath = $env:PATH
    $savedNewBranchActor = $env:GITHUB_ACTOR
    $savedNewBranchToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = Split-Path -Parent (Get-Command git).Source
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        Assert-Throws { & $prePushScript -Root $hookRoot -PushedRefs $newBranchRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A new branch checked only its documentation tip and skipped earlier code.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }

    # A release tag can name the exact tree already on a remote branch. It adds a pointer,
    # not a new index: treating it as a first branch push creates a publication deadlock.
    $tagRemote = Join-Path $hookRoot 'tag-remote.git'
    & git init --bare --quiet $tagRemote
    & git -C $hookRoot push --quiet $tagRemote "${newBranchHead}:refs/heads/main"
    & git -C $hookRoot tag -a release-same-tree -m 'release' $newBranchHead
    $tagObject = (& git -C $hookRoot rev-parse refs/tags/release-same-tree).Trim()
    $tagRefs = "refs/tags/release-same-tree $tagObject refs/tags/release-same-tree $('0' * 40)"
    try {
        # Git hooks put git-core first. Keeping only that directory breaks Windows Git's
        # local transport because its runtime DLLs live elsewhere. Hide gh, not Git's dependencies.
        $env:PATH = (@($savedNewBranchPath -split [IO.Path]::PathSeparator | Where-Object {
            $directory = $_.Trim('"')
            $directory -and -not (@('gh', 'gh.exe', 'gh.cmd', 'gh.bat') | Where-Object {
                Test-Path -LiteralPath (Join-Path $directory $_) -PathType Leaf
            })
        }) -join [IO.Path]::PathSeparator)
        Assert-True (-not (Get-Command gh -ErrorAction SilentlyContinue)) 'The tag fixture still exposes gh.'
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $tagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'An annotated tag of the advertised remote tree reran file-change gates.'
        $lightTagRefs = "refs/tags/light $newBranchHead refs/tags/light $('0' * 40)"
        & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $lightTagRefs 6> $null
        Assert-True ($LASTEXITCODE -eq 0) 'A lightweight tag of the advertised remote tree reran file-change gates.'

        # A stale local tracking ref is not proof that the target is hosted. Only the live
        # remote advertisement above can qualify; unknown targets retain the full-tree gate.
        $unknownTagRefs = "refs/tags/unknown $firstCommit refs/tags/unknown $('0' * 40)"
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs $unknownTagRefs 6> $null } `
            '*GITHUB_ACTOR*' 'A tag not matching an advertised remote branch skipped code checks.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl $tagRemote -PushedRefs "$tagRefs`n$newBranchRefs" 6> $null } `
            '*GITHUB_ACTOR*' 'A known tag suppressed checks for a new branch in the same push.'
        Assert-Throws { & $prePushScript -Root $hookRoot -RemoteUrl (Join-Path $hookRoot 'missing-remote.git') -PushedRefs $tagRefs 6> $null } `
            '*remote branches*' 'An unavailable remote was treated as proof that a tag target was already hosted.'
    } finally {
        $env:PATH = $savedNewBranchPath
        $env:GITHUB_ACTOR = $savedNewBranchActor
        $env:GITHUB_TOKEN = $savedNewBranchToken
    }

    # The build branch, which runs the Gradle gates that hold the Bouncy Castle graphs to the
    # reviewed release. Starting a real build from a contract test would be absurd, so the case
    # reads the first thing that branch does instead: with no GitHub credentials and no gh on
    # the path, it refuses by name, and nothing else in the hook says that. A push that moved
    # only a pin used to take the release path and never reach this.
    $savedPath = $env:PATH
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:PATH = $hookRoot
        $env:GITHUB_ACTOR = $null
        $env:GITHUB_TOKEN = $null
        # The files the tests read from outside the source folders reach the build too: a push
        # that changed only one of them ran the release facts check at most.
        foreach ($pin in @('patches/src/main/kotlin/app/morphe/patches/tiktok/Any.kt',
                'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties',
                'gradle/wrapper/gradle-wrapper.jar', 'gradlew', 'gradlew.bat',
                'gradle/libs.versions.toml', 'gradle/verification-metadata.xml',
                'settings.gradle.kts', 'build.gradle.kts', 'patches/build.gradle.kts',
                'README.md', 'NOTICE', 'patches-list.json', 'patches-bundle.json', 'patches-bundle.png', 'assets/readme-hero.png',
                'concepts/marketing/2026-09-12/selected/hero-final.png')) {
            Remove-Item -LiteralPath $contractsMarker -Force -ErrorAction SilentlyContinue
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($pin) 6> $null } `
                '*GITHUB_ACTOR*' "A push that changed $pin did not reach the build gates."
            if ($pin -notin @('README.md', 'NOTICE', 'patches-bundle.json', 'patches-bundle.png', 'assets/readme-hero.png',
                    'concepts/marketing/2026-09-12/selected/hero-final.png')) {
                Assert-True (Test-Path -LiteralPath $contractsMarker) `
                    "A patch or catalog input $pin skipped the script contracts before its build."
            }
        }
        # And the control: a file the build branch has no interest in must not reach it.
        & $prePushScript -Root $hookRoot -ChangedPaths @('CONTRIBUTING.md') 6> $null
        Assert-True ($LASTEXITCODE -eq 0 -or $null -eq $LASTEXITCODE) `
            'A file no gate reads was routed into the build gates.'
    } finally {
        $env:PATH = $savedPath
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }

    # The build wrapper. HUSHFEED_BUILD_WRAPPER names the script that runs Gradle on this
    # machine, and the hook hands it the repository and the tasks. A stub stands in for it and
    # records what it was given, so no build starts.
    $savedWrapper = $env:HUSHFEED_BUILD_WRAPPER
    $savedActor = $env:GITHUB_ACTOR
    $savedToken = $env:GITHUB_TOKEN
    try {
        $env:GITHUB_ACTOR = 'contract'
        $env:GITHUB_TOKEN = 'contract'
        $wrapperMarker = Join-Path $hookRoot 'wrapper-ran.txt'
        $wrapperStub = Join-Path $hookRoot 'build-wrapper.ps1'
        Set-Content -LiteralPath $wrapperStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            "Set-Content -LiteralPath '$wrapperMarker' -Value (`"dir=`$ProjectDir tasks=`" + (`$Tasks -join ','))",
            'exit 0')
        $env:HUSHFEED_BUILD_WRAPPER = $wrapperStub
        Set-Content -LiteralPath $requireWrapper -Value 'required'
        Remove-Item -LiteralPath $wrapperMarker -Force -ErrorAction SilentlyContinue
        Invoke-Hook -Paths @('patches-bundle.json')
        Assert-True (Test-Path -LiteralPath $wrapperMarker) 'An index change skipped documentation tests.'
        Assert-True (Test-Path -LiteralPath $factsMarker) 'An index change ran no release check.'
        Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=False*') `
            'An index change was allowed to lag behind the published release.'

        $validWrapper = Get-Content -LiteralPath $wrapperStub -Raw
        try {
            Set-Content -LiteralPath $wrapperStub -Value 'exit 1'
            Remove-Item -LiteralPath $factsMarker, $wrapperMarker -Force -ErrorAction SilentlyContinue
            Assert-Throws { Invoke-Hook -Paths @('patches-bundle.json') } '*runtime test build did not pass*' `
                'An index push ignored a failed documentation build.'
            Assert-True (-not (Test-Path -LiteralPath $factsMarker)) `
                'Release validation continued after documentation tests failed.'
        } finally { Set-Content -LiteralPath $wrapperStub -Encoding UTF8 -Value $validWrapper }

        # The order that shipped a dexless bundle: buildAndroid, then any task that reruns
        # :patches:jar, which leaves the plain jar in build/libs under the bundle's own name. The
        # finished bundle sits in build/release, and the index push must be compared against that
        # one. A plain jar left beside it in build/libs is the state :patches:test produces.
        $releaseDirectory = Join-Path $hookRoot 'patches/build/release'
        $libsDirectory = Join-Path $hookRoot 'patches/build/libs'
        New-Item -ItemType Directory -Path $releaseDirectory, $libsDirectory -Force | Out-Null
        # Normalized, because the hook hands over the listing's own full name.
        $releaseCopy = [System.IO.Path]::GetFullPath((Join-Path $releaseDirectory 'patches-9.9.9.mpp'))
        Set-Content -LiteralPath $releaseCopy -Value 'bundle with classes.dex' -Encoding ASCII
        Set-Content -LiteralPath (Join-Path $libsDirectory 'patches-9.9.9.mpp') -Value 'plain jar' -Encoding ASCII
        Remove-Item -LiteralPath $wrapperMarker -Force -ErrorAction SilentlyContinue
        Invoke-Hook -Paths @('patches-bundle.json')
        Assert-True (Test-Path -LiteralPath $wrapperMarker) 'An index change skipped documentation tests.'
        $routed = Get-Content -LiteralPath $factsMarker -Raw
        Assert-True ($routed -like '*verify=True*') `
            'An index push with a built release bundle did not compare it against the published asset.'
        Assert-True ($routed -like "*artifact=$releaseCopy*") `
            "The index push compared something other than the release copy: $routed"
        Remove-Item -LiteralPath (Join-Path $hookRoot 'patches') -Recurse -Force

        # A first push lists the branch's whole tree, and every tree holds patches-bundle.json. That
        # used to route the push as an index push: the strict release check, and no patching at all.
        # This case sees the release check's mode; the patching half reads the same flag.
        $firstPushRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-first-push-" + [guid]::NewGuid().ToString('N'))
        try {
            New-Item -ItemType Directory -Path (Join-Path $firstPushRoot 'scripts') -Force | Out-Null
            # Tracked, because the hook runs its checks from an export of the pushed commit.
            Copy-Item -LiteralPath (Join-Path $hookRoot 'scripts/validate-release-facts.ps1') -Destination (Join-Path $firstPushRoot 'scripts')
            Copy-Item -LiteralPath (Join-Path $hookRoot 'scripts/test-script-contracts.ps1') -Destination (Join-Path $firstPushRoot 'scripts')
            Set-Content -LiteralPath (Join-Path $firstPushRoot 'patches-bundle.json') -Encoding UTF8 -Value '{}'
            & git -C $firstPushRoot init --quiet
            $firstPushGitDir = (& git -C $firstPushRoot rev-parse --absolute-git-dir).Trim()
            Assert-True ([IO.Path]::GetFullPath($firstPushGitDir).TrimEnd('\', '/') -ieq
                [IO.Path]::GetFullPath((Join-Path $firstPushRoot '.git')).TrimEnd('\', '/')) `
                'The first-push fixture resolved outside its temporary repository; refusing to write.'
            & git -C $firstPushRoot config user.name 'Hook Contract'
            & git -C $firstPushRoot config user.email 'hook@example.invalid'
            & git -C $firstPushRoot add -A
            & git -C $firstPushRoot commit --quiet -m 'first'
            $firstPushHead = (& git -C $firstPushRoot rev-parse HEAD).Trim()
            # The hook reads each pushed local ref again once its checks end, so it has to exist.
            $firstPushBranch = (& git -C $firstPushRoot symbolic-ref HEAD).Trim()
            Remove-Item -LiteralPath $factsMarker, $wrapperMarker -Force -ErrorAction SilentlyContinue
            $global:LASTEXITCODE = 0
            & $prePushScript -Root $firstPushRoot -PushedRefs "$firstPushBranch $firstPushHead refs/heads/first $('0' * 40)" 6> $null
            Assert-True (Test-Path -LiteralPath $wrapperMarker) 'A first push skipped documentation tests.'
            Assert-True ($LASTEXITCODE -eq 0) 'A first push of an unchanged index failed its release check.'
            Assert-True ((Get-Content -LiteralPath $factsMarker -Raw) -like 'lag=True*') `
                'A first push was taken for an index push because its tree holds the index.'
        } finally {
            Remove-Item -LiteralPath $firstPushRoot -Recurse -Force -ErrorAction SilentlyContinue
        }
        Remove-Item -LiteralPath $requireWrapper, $wrapperMarker -Force -ErrorAction SilentlyContinue

        & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/tiktok/src/test/java/AnyTest.java') 6> $null
        Assert-True (Test-Path -LiteralPath $wrapperMarker) `
            'The hook did not run the build through the wrapper HUSHFEED_BUILD_WRAPPER names.'
        $wrapped = Get-Content -LiteralPath $wrapperMarker -Raw
        Assert-True ($wrapped -like "dir=$hookRoot tasks=*:extensions:tiktok:test*:patches:test*") `
            "The build wrapper was not handed the repository and the test tasks: $wrapped"

        # Cheap checks first: one build without nativeTest's fixture scans, then the full one. A
        # slip in a two-minute test used to show only after the half-hour partition. A stub that
        # logs every call, and fails the quick one while the fail marker exists.
        $passLog = Join-Path $hookRoot 'wrapper-passes.txt'
        $passFails = Join-Path $hookRoot 'wrapper-quick-fails.txt'
        $passStub = Join-Path $hookRoot 'build-wrapper-passes.ps1'
        Set-Content -LiteralPath $passStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            "Add-Content -LiteralPath '$passLog' -Value (`$Tasks -join ',')",
            "if ((Test-Path -LiteralPath '$passFails') -and `$Tasks -contains '-x') { exit 1 }",
            'exit 0')
        $env:HUSHFEED_BUILD_WRAPPER = $passStub
        try {
            Remove-Item -LiteralPath $passLog, $passFails -Force -ErrorAction SilentlyContinue
            & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/tiktok/src/test/java/AnyTest.java') 6> $null
            $passes = @(Get-Content -LiteralPath $passLog)
            Assert-True ($passes.Count -eq 2) "The gate did not build twice, quick then full: $($passes -join ' | ')"
            Assert-True ($passes[0] -like '*:patches:test*-x,:patches:nativeTest*' -and
                $passes[0] -like '*-x,:patches:verifyPatchTestSelection*' -and $passes[0] -like '*:extensions:tiktok:lint*') `
                "The first build was not the quick one without nativeTest: $($passes[0])"
            Assert-True ($passes[1] -notlike '*-x*' -and $passes[1] -like '*:patches:test*') `
                "The second build was not the full one: $($passes[1])"

            Remove-Item -LiteralPath $passLog -Force -ErrorAction SilentlyContinue
            Set-Content -LiteralPath $passFails -Value 'fail' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/tiktok/src/test/java/AnyTest.java') 6> $null } `
                '*runtime test build did not pass*' 'A failed quick build was ignored.'
            Assert-True (@(Get-Content -LiteralPath $passLog).Count -eq 1) 'The full build ran after the quick one failed.'
        } finally {
            Remove-Item -LiteralPath $passLog, $passFails, $passStub -Force -ErrorAction SilentlyContinue
            $env:HUSHFEED_BUILD_WRAPPER = $wrapperStub
        }

        $env:HUSHFEED_BUILD_WRAPPER = Join-Path $hookRoot 'no-such-wrapper.ps1'
        Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('patches/build.gradle.kts') 6> $null } `
            '*HUSHFEED_BUILD_WRAPPER*' 'A build wrapper that is not there was ignored rather than reported.'

        # A patch source reaches TikTok itself. The build gains :patches:buildAndroid, last, and the
        # bundle it leaves is applied to each declared build's fixture by the root's own
        # verify-all-patches.ps1. de43a43e passed every test with a fingerprint that matched nothing.
        # The stub build leaves a bundle; the stub verify records each call in a file of its own,
        # since the builds are applied at once, and fails while the fail marker exists.
        $savedFixtureDir = $env:HUSHFEED_FIXTURE_DIR
        $savedDesktopJar = $env:HUSHFEED_DESKTOP_JAR
        try {
            $applyCalls = Join-Path $hookRoot 'apply-calls'
            $applyFails = Join-Path $hookRoot 'apply-fails.txt'
            # While the slow marker exists each stub run takes a moment and records when it ran.
            $applySlow = Join-Path $hookRoot 'apply-slow.txt'
            $applySpans = Join-Path $hookRoot 'apply-spans'
            $fixtureStub = Join-Path $hookRoot 'fixtures'
            New-Item -ItemType Directory -Path $applyCalls, $applySpans, $fixtureStub -Force | Out-Null
            # Both file names the fixture tests take, a bundle file that isn't an APK, and a build
            # the catalog doesn't declare.
            foreach ($name in @('com.zhiliaoapp.musically_1.0.3-100_apkmirror.com.apk', 'tiktok-1.1.3.apk',
                    'tiktok-1.1.3-bundle.apkm', 'tiktok-0.9.3.apk')) {
                Set-Content -LiteralPath (Join-Path $fixtureStub $name) -Value 'stub' -Encoding ASCII
            }
            $env:HUSHFEED_FIXTURE_DIR = $fixtureStub
            $env:HUSHFEED_DESKTOP_JAR = Join-Path $hookRoot 'morphe-desktop.jar'
            Set-Content -LiteralPath $env:HUSHFEED_DESKTOP_JAR -Value 'stub' -Encoding ASCII
            $stubCatalog = Join-Path $hookRoot 'patches-list.json'
            Set-Content -LiteralPath $stubCatalog -Encoding UTF8 -Value (@{ patches = @(
                    @{ name = 'A'; compatiblePackages = @{ 'com.zhiliaoapp.musically' = @('1.0.3', '1.1.3') } },
                    @{ name = 'B'; compatiblePackages = @{ 'com.zhiliaoapp.musically' = @('1.0.3', '1.1.3') } })
                } | ConvertTo-Json -Depth 5)
            $bundleStub = [System.IO.Path]::GetFullPath((Join-Path $hookRoot 'patches/build/release/patches-9.9.9.mpp'))
            Set-Content -LiteralPath $wrapperStub -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                "Set-Content -LiteralPath '$wrapperMarker' -Value (`"dir=`$ProjectDir tasks=`" + (`$Tasks -join ','))",
                'if ($Tasks -contains '':patches:buildAndroid'') {',
                "    New-Item -ItemType Directory -Path '$(Split-Path -Parent $bundleStub)' -Force | Out-Null",
                "    Set-Content -LiteralPath '$bundleStub' -Value 'bundle' -Encoding ASCII",
                '}',
                'exit 0')
            $env:HUSHFEED_BUILD_WRAPPER = $wrapperStub
            Set-Content -LiteralPath (Join-Path $hookRoot 'scripts/verify-all-patches.ps1') -Encoding UTF8 -Value @(
                'param([string]$Apk, [string]$DesktopJar, [string]$WorkDir, [string]$Bundle, [string]$PatchList, [string]$KeepIn)',
                "Set-Content -LiteralPath (Join-Path '$applyCalls' ([guid]::NewGuid().ToString('N') + '.txt')) -Value (",
                '    "apk=$(Split-Path -Leaf $Apk) jar=$DesktopJar bundle=$Bundle list=$PatchList keep=$KeepIn")',
                "if (Test-Path -LiteralPath '$applySlow') {",
                '    $started = [DateTime]::UtcNow.Ticks',
                '    Start-Sleep -Milliseconds 1500',
                "    Set-Content -LiteralPath (Join-Path '$applySpans' ([guid]::NewGuid().ToString('N') + '.txt')) -Value `"`$started `$([DateTime]::UtcNow.Ticks)`"",
                '}',
                "if (Test-Path -LiteralPath '$applyFails') { Write-Host '[verify] FAILED stub'; exit 1 }",
                'exit 0')
            function Get-ApplyCalls { return @(Get-ChildItem -LiteralPath $applyCalls -File | ForEach-Object { (Get-Content -LiteralPath $_.FullName -Raw).Trim() } | Sort-Object) }
            function Reset-Apply {
                Remove-Item -LiteralPath $wrapperMarker, $applyFails, $applySlow -Force -ErrorAction SilentlyContinue
                Get-ChildItem -LiteralPath $applyCalls, $applySpans -File | Remove-Item -Force
                Remove-Item -LiteralPath (Join-Path $hookRoot 'patches') -Recurse -Force -ErrorAction SilentlyContinue
            }
            $patchSource = 'patches/src/main/kotlin/app/morphe/patches/tiktok/Any.kt'

            Reset-Apply
            & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource) 6> $null
            Assert-True ($LASTEXITCODE -eq 0) "A patch source push failed with every stub passing (exit $LASTEXITCODE)."
            $wrapped = Get-Content -LiteralPath $wrapperMarker -Raw
            Assert-True ($wrapped.Trim() -like '*:patches:test*,:patches:buildAndroid') `
                "A patch source push did not end its build with :patches:buildAndroid: $wrapped"
            $calls = Get-ApplyCalls
            # Each run is kept by version under the gate root, where the release receipt reads it.
            $keptRoot = Join-Path $hookRoot 'patches/build/fixture-apply'
            $expected = @(@('com.zhiliaoapp.musically_1.0.3-100_apkmirror.com.apk', '1.0.3'), @('tiktok-1.1.3.apk', '1.1.3')) | ForEach-Object {
                "apk=$($_[0]) jar=$env:HUSHFEED_DESKTOP_JAR bundle=$bundleStub list=$stubCatalog keep=$(Join-Path $keptRoot $_[1])"
            }
            Assert-True (($calls -join "`n") -eq ($expected -join "`n")) `
                ("A patch source push did not apply the built bundle to each declared build once, and only those: " + ($calls -join '; '))

            # The patcher pin decides how fingerprints match, so it reaches the fixtures too.
            Reset-Apply
            & $prePushScript -Root $hookRoot -ChangedPaths @('gradle/libs.versions.toml') 6> $null
            Assert-True ((Get-ApplyCalls).Count -eq 2) 'A patcher pin change applied nothing to the fixtures.'
            Assert-True (Test-Path -LiteralPath $signingMarker) 'A signing provider pin change skipped SDK signing fixtures.'
            Remove-Item -LiteralPath $signingMarker -Force
            Reset-Apply
            & $prePushScript -Root $hookRoot -ChangedPaths @('gradle/verification-metadata.xml') 6> $null
            Assert-True (Test-Path -LiteralPath $signingMarker) 'A signing provider checksum change skipped SDK signing fixtures.'

            # HUSHFEED_GATE_SERIAL=1 applies one build at a time: the second run starts only once the
            # first has ended.
            Reset-Apply
            Set-Content -LiteralPath $applySlow -Value 'slow' -Encoding ASCII
            $savedSerial = $env:HUSHFEED_GATE_SERIAL
            $env:HUSHFEED_GATE_SERIAL = '1'
            try {
                & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource) 6> $null
            } finally {
                $env:HUSHFEED_GATE_SERIAL = $savedSerial
            }
            Assert-True ($LASTEXITCODE -eq 0 -and (Get-ApplyCalls).Count -eq 2) `
                "A serial gate did not apply the bundle to each declared build: $((Get-ApplyCalls) -join '; ')"
            $spans = @(Get-ChildItem -LiteralPath $applySpans -File | ForEach-Object {
                    , [long[]]((Get-Content -LiteralPath $_.FullName -Raw).Trim() -split ' ') } | Sort-Object { $_[0] })
            Assert-True ($spans.Count -eq 2 -and $spans[1][0] -ge $spans[0][1]) `
                ('A serial gate applied two builds at once: ' + (($spans | ForEach-Object { $_ -join '-' }) -join ', '))

            # A first push lists the branch's whole tree, which holds patches-bundle.json as well as
            # the patch sources. The release check's half of that is covered above; this is the
            # patching half. A fresh repository with both, pushed as a new branch, must still build
            # the bundle and apply it to each declared build, since the index it holds is unchanged.
            Reset-Apply
            $firstPatchRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-first-patch-" + [guid]::NewGuid().ToString('N'))
            $firstPatchWrapper = Join-Path $hookRoot 'first-patch-wrapper.ps1'
            try {
                New-Item -ItemType Directory -Path (Join-Path $firstPatchRoot 'scripts'),
                    (Join-Path $firstPatchRoot (Split-Path -Parent $patchSource)) -Force | Out-Null
                # Tracked, because the hook runs its checks from the pushed commit's tree.
                foreach ($script in @('validate-release-facts.ps1', 'test-script-contracts.ps1', 'verify-all-patches.ps1')) {
                    Copy-Item -LiteralPath (Join-Path $hookRoot "scripts/$script") -Destination (Join-Path $firstPatchRoot 'scripts')
                }
                Copy-Item -LiteralPath $stubCatalog -Destination (Join-Path $firstPatchRoot 'patches-list.json')
                # An unnamed resource dependency is present in native reports, but is not a
                # separately selectable patch. Use the real report reader in the stub suite.
                Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'patch-report.ps1') -Destination (Join-Path $firstPatchRoot 'scripts')
                $firstCatalog = Join-Path $firstPatchRoot 'patches-list.json'
                $closureCatalog = Get-Content -LiteralPath $firstCatalog -Raw | ConvertFrom-Json
                $closureCatalog.patches[0] | Add-Member -NotePropertyName dependencies -NotePropertyValue @('ResourcePatch')
                $closureCatalog | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $firstCatalog -Encoding UTF8
                $closureMarker = Join-Path $hookRoot 'closure-ran.txt'
                Set-Content -LiteralPath (Join-Path $firstPatchRoot 'scripts/test-script-contracts.ps1') -Encoding UTF8 -Value @(
                    'param([string]$Root)',
                    '. (Join-Path $PSScriptRoot ''patch-report.ps1'')',
                    '$catalog = Get-Content -LiteralPath (Join-Path $Root ''patches-list.json'') -Raw | ConvertFrom-Json',
                    '$names = @($catalog.patches | ForEach-Object { $_.name })',
                    '$closure = @(Get-PatchDependencyNames -PatchList $catalog -RequestedNames $names)',
                    "Add-Content -LiteralPath '$closureMarker' -Value (`"root=`$Root closure=`" + (`$closure -join ','))",
                    'if (-not (Test-ReportedPatchNames -Expected $names -Actual @($names + ''ResourcePatch'') -AllowedDependencies $closure)) { throw ''The unnamed resource dependency is missing from the catalog closure.'' }',
                    'exit 0')
                Set-Content -LiteralPath (Join-Path $firstPatchRoot 'patches-bundle.json') -Encoding UTF8 -Value '{}'
                Set-Content -LiteralPath (Join-Path $firstPatchRoot $patchSource) -Encoding UTF8 -Value '// patch'
                # As in the repository: the build's output is not part of the tree the hook checks.
                Set-Content -LiteralPath (Join-Path $firstPatchRoot '.gitignore') -Encoding UTF8 -Value 'build/'
                # The bundle goes where the hook will look for it: under the tree it was handed.
                Set-Content -LiteralPath $firstPatchWrapper -Encoding UTF8 -Value @(
                    'param([string]$ProjectDir, [string[]]$Tasks)',
                    "Set-Content -LiteralPath '$wrapperMarker' -Value (`"dir=`$ProjectDir tasks=`" + (`$Tasks -join ','))",
                    'if ($Tasks -contains '':patches:buildAndroid'') {',
                    '    $release = Join-Path $ProjectDir ''patches/build/release''',
                    '    New-Item -ItemType Directory -Path $release -Force | Out-Null',
                    '    Set-Content -LiteralPath (Join-Path $release ''patches-9.9.9.mpp'') -Value ''bundle'' -Encoding ASCII',
                    '}',
                    'exit 0')
                $env:HUSHFEED_BUILD_WRAPPER = $firstPatchWrapper
                & git -C $firstPatchRoot init --quiet
                $firstPatchGitDir = (& git -C $firstPatchRoot rev-parse --absolute-git-dir).Trim()
                Assert-True ([IO.Path]::GetFullPath($firstPatchGitDir).TrimEnd('\', '/') -ieq
                    [IO.Path]::GetFullPath((Join-Path $firstPatchRoot '.git')).TrimEnd('\', '/')) `
                    'The first-push patching fixture resolved outside its temporary repository; refusing to write.'
                & git -C $firstPatchRoot config user.name 'Hook Contract'
                & git -C $firstPatchRoot config user.email 'hook@example.invalid'
                & git -C $firstPatchRoot add -A
                & git -C $firstPatchRoot commit --quiet -m 'first'
                $firstPatchHead = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                $firstPatchBranch = (& git -C $firstPatchRoot symbolic-ref HEAD).Trim()
                $global:LASTEXITCODE = 0
                & $prePushScript -Root $firstPatchRoot -PushedRefs "$firstPatchBranch $firstPatchHead refs/heads/first $('0' * 40)" 6> $null
                Assert-True ($LASTEXITCODE -eq 0) "A first push with patch sources failed with every stub passing (exit $LASTEXITCODE)."
                Assert-True ((Get-Content -LiteralPath $wrapperMarker -Raw).Trim() -like '*,:patches:buildAndroid') `
                    'A first push with patch sources did not build the bundle.'
                $firstCalls = @(Get-ApplyCalls | ForEach-Object { ($_ -split ' ')[0] })
                Assert-True (($firstCalls -join ',') -eq 'apk=com.zhiliaoapp.musically_1.0.3-100_apkmirror.com.apk,apk=tiktok-1.1.3.apk') `
                    ("A first push with patch sources did not apply the bundle to each declared build once: " + ((Get-ApplyCalls) -join '; '))
                Assert-True ((Get-Content -LiteralPath $closureMarker -Raw) -like '*closure=ResourcePatch*') `
                    'A new branch did not check its unnamed resource dependency.'

                # No script changes in either pushed range. The valid catalog passes, and the
                # invalid one fails before a build. Changing the working catalog must not make
                # the gate check a different commit from the ref being pushed.
                Add-Content -LiteralPath (Join-Path $firstPatchRoot $patchSource) -Value '// valid patch-only edit'
                & git -C $firstPatchRoot add -- $patchSource
                & git -C $firstPatchRoot commit --quiet -m 'patch-only good'
                $closureGood = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                & git -C $firstPatchRoot branch closure-good $closureGood
                Remove-Item -LiteralPath $closureMarker -Force
                & $prePushScript -Root $firstPatchRoot -PushedRefs "refs/heads/closure-good $closureGood refs/heads/good $firstPatchHead" 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $closureMarker)) `
                    'A patch-only change skipped its valid dependency contracts.'

                $closureCatalog.patches[0].dependencies = @()
                $closureCatalog | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $firstCatalog -Encoding UTF8
                & git -C $firstPatchRoot add patches-list.json
                & git -C $firstPatchRoot commit --quiet -m 'missing closure entry'
                $closureBase = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                Add-Content -LiteralPath (Join-Path $firstPatchRoot $patchSource) -Value '// invalid patch-only edit'
                & git -C $firstPatchRoot add -- $patchSource
                & git -C $firstPatchRoot commit --quiet -m 'patch-only broken'
                $closureBroken = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                & git -C $firstPatchRoot branch closure-broken $closureBroken
                $goodRef = "refs/heads/closure-good $closureGood refs/heads/good $firstPatchHead"
                $badRef = "refs/heads/closure-broken $closureBroken refs/heads/broken $closureBase"
                Remove-Item -LiteralPath $closureMarker -Force
                & $prePushScript -Root $firstPatchRoot -PushedRefs $goodRef 6> $null
                $checkedClosure = Get-Content -LiteralPath $closureMarker -Raw
                Assert-True ($LASTEXITCODE -eq 0 -and $checkedClosure -like '*closure=ResourcePatch*' -and
                    $checkedClosure -notlike "*root=$firstPatchRoot *") `
                    'A patch-only ref read the broken catalog in another checkout instead of its own.'
                Remove-Item -LiteralPath $wrapperMarker -Force -ErrorAction SilentlyContinue
                Assert-Throws { & $prePushScript -Root $firstPatchRoot -PushedRefs $badRef 6> $null } `
                    '*unnamed resource dependency is missing*' 'A missing closure entry passed a patch-only push.'
                Assert-True (-not (Test-Path -LiteralPath $wrapperMarker)) `
                    'A missing dependency closure was checked only after the build started.'
                Remove-Item -LiteralPath $closureMarker -Force
                Assert-Throws { & $prePushScript -Root $firstPatchRoot -PushedRefs "$goodRef`n$badRef" 6> $null } `
                    '*unnamed resource dependency is missing*' 'One good ref hid a broken patch-only ref in the same push.'
                $checkedClosure = Get-Content -LiteralPath $closureMarker -Raw
                Assert-True ($checkedClosure -match 'closure=ResourcePatch' -and $checkedClosure -match 'closure=\s*(\r?\n|$)') `
                    'A multi-ref push did not check both commits with their own catalog states.'

                # Runtime-only refs must apply their own payload, including a new branch and
                # two refs pushed together. HEAD deliberately holds the opposite result.
                & git -C $firstPatchRoot checkout --detach --force --quiet $closureGood
                $runtimePath = 'extensions/tiktok/src/main/java/Runtime.java'
                $runtimeFile = Join-Path $firstPatchRoot $runtimePath
                New-Item -ItemType Directory -Path (Split-Path -Parent $runtimeFile) -Force | Out-Null
                Set-Content -LiteralPath $runtimeFile -Value 'base' -Encoding ASCII
                $runtimeVerifier = Join-Path $firstPatchRoot 'scripts/verify-all-patches.ps1'
                $runtimeStub = Get-Content -LiteralPath $runtimeVerifier -Raw
                $runtimeStub = $runtimeStub.Replace('exit 0', @'
$payload = (Get-Content -LiteralPath (Join-Path $PSScriptRoot '../extensions/tiktok/src/main/java/Runtime.java') -Raw).Trim()
if ($payload -eq 'broken') { Write-Host '[verify] FAILED runtime payload'; exit 1 }
exit 0
'@)
                Set-Content -LiteralPath $runtimeVerifier -Value $runtimeStub -Encoding UTF8
                & git -C $firstPatchRoot add -- $runtimePath scripts/verify-all-patches.ps1
                & git -C $firstPatchRoot commit --quiet -m 'runtime application fixture'
                $runtimeBase = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                Set-Content -LiteralPath $runtimeFile -Value 'good' -Encoding ASCII
                & git -C $firstPatchRoot add -- $runtimePath
                & git -C $firstPatchRoot commit --quiet -m 'runtime good'
                $runtimeGood = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                & git -C $firstPatchRoot branch runtime-good $runtimeGood
                Set-Content -LiteralPath $runtimeFile -Value 'broken' -Encoding ASCII
                & git -C $firstPatchRoot add -- $runtimePath
                & git -C $firstPatchRoot commit --quiet -m 'runtime broken'
                $runtimeBroken = (& git -C $firstPatchRoot rev-parse HEAD).Trim()
                & git -C $firstPatchRoot branch runtime-broken $runtimeBroken
                $runtimeGoodRef = "refs/heads/runtime-good $runtimeGood refs/heads/runtime-good $runtimeBase"
                $runtimeBadRef = "refs/heads/runtime-broken $runtimeBroken refs/heads/runtime-broken $runtimeGood"
                Reset-Apply
                & $prePushScript -Root $firstPatchRoot -PushedRefs "refs/heads/runtime-good $runtimeGood refs/heads/new-runtime $('0' * 40)" 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and (Get-ApplyCalls).Count -eq 2) `
                    'A new runtime branch did not apply its own payload while HEAD was broken.'
                Reset-Apply
                & $prePushScript -Root $firstPatchRoot -PushedRefs $runtimeGoodRef 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and (Get-ApplyCalls).Count -eq 2) `
                    'A runtime-only ref applied the broken working payload instead of its own.'
                & git -C $firstPatchRoot checkout --detach --force --quiet $runtimeGood
                Reset-Apply
                Assert-Throws { & $prePushScript -Root $firstPatchRoot -PushedRefs $runtimeBadRef 6> $null } `
                    '*did not apply to TikTok 1.0.3 and 1.1.3*' 'A good HEAD concealed a broken runtime-only ref.'
                Reset-Apply
                Assert-Throws { & $prePushScript -Root $firstPatchRoot -PushedRefs "$runtimeGoodRef`n$runtimeBadRef" 6> $null } `
                    '*did not apply to TikTok 1.0.3 and 1.1.3*' 'One good runtime ref concealed a bad one in the same push.'
                Assert-True ((Get-ApplyCalls).Count -eq 4) `
                    'A multi-ref runtime push did not apply both commits to every declared host.'
            } finally {
                $env:HUSHFEED_BUILD_WRAPPER = $wrapperStub
                Remove-Item -LiteralPath $firstPatchRoot, $firstPatchWrapper -Recurse -Force -ErrorAction SilentlyContinue
            }

            # These inputs change the shipped DEX payload or its compiler, even without a patch edit.
            foreach ($runtimeInput in @('extensions/tiktok/src/main/java/Any.java',
                    'extensions/tiktok/src/main/l10n/fr.tsv', 'extensions/shared/library/src/main/java/Any.java',
                    'extensions/tiktok/stub/src/main/java/Native.java', 'extensions/tiktok/build.gradle.kts',
                    'extensions/shared/build.gradle.kts', 'extensions/shared/library/build.gradle.kts',
                    'extensions/tiktok/stub/build.gradle.kts', 'extensions/proguard-rules.pro',
                    'patches/build.gradle.kts', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties',
                    'gradle/wrapper/gradle-wrapper.jar', 'gradlew', 'gradlew.bat',
                    'settings.gradle.kts', 'build.gradle.kts', 'gradle/verification-metadata.xml')) {
                Reset-Apply
                & $prePushScript -Root $hookRoot -ChangedPaths @($runtimeInput) 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and ((Get-ApplyCalls) -join "`n") -eq ($expected -join "`n")) `
                    "A bundle input $runtimeInput did not apply the built payload to every declared host."
                Assert-True ((Get-Content -LiteralPath $wrapperMarker -Raw).Trim() -like '*,:patches:buildAndroid') `
                    "A bundle input $runtimeInput did not rebuild the release payload last."
            }

            foreach ($testOrDoc in @('extensions/tiktok/src/test/java/AnyTest.java', 'README.md', 'CONTRIBUTING.md')) {
                Reset-Apply
                & $prePushScript -Root $hookRoot -ChangedPaths @($testOrDoc) 6> $null
                Assert-True ($LASTEXITCODE -eq 0 -and (Get-ApplyCalls).Count -eq 0) `
                    "An input outside the payload $testOrDoc applied the bundle."
                Assert-True (-not (Test-Path -LiteralPath $wrapperMarker) -or
                    (Get-Content -LiteralPath $wrapperMarker -Raw) -notlike '*buildAndroid*') `
                    "An input outside the payload $testOrDoc rebuilt the bundle."
            }

            Reset-Apply
            Set-Content -LiteralPath $applyFails -Value 'fail' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @('extensions/tiktok/src/main/java/Any.java') 6> $null } `
                '*did not apply to TikTok 1.0.3 and 1.1.3*' 'A runtime-only push ignored an application failure.'
            Assert-True ((Get-ApplyCalls).Count -eq 2) 'A failed runtime payload did not check every declared host.'

            # An index push is compared byte for byte with the published bundle, which a rebuild
            # here would restamp, so it leaves the bundle alone.
            Reset-Apply
            & $prePushScript -Root $hookRoot -ChangedPaths @('patches-bundle.json') 6> $null
            Assert-True ((Get-ApplyCalls).Count -eq 0) 'An index push reapplied the published bundle.'
            $indexTasks = Get-Content -LiteralPath $wrapperMarker -Raw
            foreach ($task in @(':extensions:tiktok:test', ':patches:test', ':extensions:shared:library:lint', ':extensions:tiktok:lint')) {
                Assert-True ($indexTasks.Contains($task)) "An index push skipped $task."
            }
            Assert-True (-not $indexTasks.Contains(':patches:buildAndroid')) `
                'An index push rebuilt the bundle it is compared against.'
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource, 'patches-bundle.json') 6> $null } `
                '*Push bundle inputs before the published index*' 'A mixed source/index push skipped payload verification.'

            # The positive control: a bundle that fails to apply stops the push, after every build ran.
            Reset-Apply
            Set-Content -LiteralPath $applyFails -Value 'fail' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource) 6> $null } `
                '*did not apply to TikTok 1.0.3 and 1.1.3*' 'A bundle that failed to apply to the fixtures was pushed.'
            Assert-True ((Get-ApplyCalls).Count -eq 2) 'A failure on one build kept the other from being applied.'

            # A declared build with no fixture stops the push before the build starts.
            Reset-Apply
            Remove-Item -LiteralPath (Join-Path $fixtureStub 'tiktok-1.1.3.apk') -Force
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource) 6> $null } `
                '*0 universal APKs of TikTok 1.1.3*' 'A declared build without a fixture was skipped.'
            Assert-True (-not (Test-Path -LiteralPath $wrapperMarker)) 'The build started before the fixtures were found.'
            Set-Content -LiteralPath (Join-Path $fixtureStub 'tiktok-1.1.3.apk') -Value 'stub' -Encoding ASCII

            # And no desktop CLI stops it by name.
            Reset-Apply
            $env:HUSHFEED_DESKTOP_JAR = Join-Path $hookRoot 'no-such.jar'
            Assert-Throws { & $prePushScript -Root $hookRoot -ChangedPaths @($patchSource) 6> $null } `
                '*HUSHFEED_DESKTOP_JAR*' 'A patch source push without the desktop CLI applied nothing and passed.'
        } finally {
            $env:HUSHFEED_FIXTURE_DIR = $savedFixtureDir
            $env:HUSHFEED_DESKTOP_JAR = $savedDesktopJar
            Remove-Item -LiteralPath (Join-Path $hookRoot 'patches'), (Join-Path $hookRoot 'patches-list.json'),
                (Join-Path $hookRoot 'scripts/verify-all-patches.ps1') -Recurse -Force -ErrorAction SilentlyContinue
        }

        # The gate builds what is pushed, not what happens to be in the working tree. A stub build
        # fails on any tree whose marker says broken, and records the tree it was handed.
        $gateRepo = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-gate-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path (Join-Path $gateRepo 'extensions') -Force | Out-Null
        & git -C $gateRepo init --quiet
        $actualGateGitDir = (& git -C $gateRepo rev-parse --absolute-git-dir).Trim()
        Assert-True ([IO.Path]::GetFullPath($actualGateGitDir).TrimEnd('\', '/') -ieq
            [IO.Path]::GetFullPath((Join-Path $gateRepo '.git')).TrimEnd('\', '/')) `
            'The gate fixture resolved outside its temporary repository; refusing to write.'
        & git -C $gateRepo config user.name 'Gate Contract'
        & git -C $gateRepo config user.email 'gate@example.invalid'
        $gateMarker = Join-Path $hookRoot 'gate-ran.txt'
        $gateStub = Join-Path $hookRoot 'gate-wrapper.ps1'
        Set-Content -LiteralPath $gateStub -Encoding UTF8 -Value @(
            'param([string]$ProjectDir, [string[]]$Tasks)',
            '$state = (Get-Content -LiteralPath (Join-Path $ProjectDir ''extensions/marker.txt'') -Raw).Trim()',
            "Set-Content -LiteralPath '$gateMarker' -Value (`"dir=`$ProjectDir marker=`$state gitdir=`$env:GIT_DIR`")",
            'if ($state -eq ''broken'') { exit 1 }',
            'exit 0')
        $env:HUSHFEED_BUILD_WRAPPER = $gateStub
        $gateFile = Join-Path $gateRepo 'extensions/marker.txt'
        function Save-GateCommit([string]$State) {
            Set-Content -LiteralPath $gateFile -Value $State -Encoding ASCII
            & git -C $gateRepo add extensions/marker.txt
            & git -C $gateRepo commit --quiet -m $State
            return (& git -C $gateRepo rev-parse HEAD).Trim()
        }
        try {
            $good = Save-GateCommit 'good'
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $good refs/heads/main $('0' * 40)" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'An uncommitted edit in the working tree failed a clean commit.'
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "The gate built the working tree instead of the pushed commit: $built"

            $broken = Save-GateCommit 'broken'
            Set-Content -LiteralPath $gateFile -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $broken refs/heads/main $good" 6> $null } `
                '*did not pass*' 'An uncommitted fix in the working tree passed a broken commit.'
            Assert-True ((& git -C $gateRepo status --porcelain) -like '*extensions/marker.txt*') `
                'Building the pushed commit touched the working tree it was kept apart from.'

            # A clean tree still builds in place.
            $fixed = Save-GateCommit 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree with a good commit did not pass.'
            Assert-True ((Get-Content -LiteralPath $gateMarker -Raw) -like "*dir=$gateRepo marker=good*") `
                'A clean tree was not built in place.'

            # A tree that changes while it is built in place: an edit landing mid-build was tested
            # along with the commit, so that build says nothing about the commit alone.
            $meddler = Join-Path $hookRoot 'gate-wrapper-meddles.ps1'
            $meddled = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $meddler -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                'Set-Content -LiteralPath (Join-Path $ProjectDir ''README.md'') -Value ''edited mid-build'' -Encoding ASCII',
                'exit 0')
            $env:HUSHFEED_BUILD_WRAPPER = $meddler
            try {
                Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null } `
                    '*changed while the runtime test build ran in place*' `
                    'A working tree that changed during an in-place build passed on that build.'
            } finally {
                $env:HUSHFEED_BUILD_WRAPPER = $gateStub
                Remove-Item -LiteralPath $meddled -Force -ErrorAction SilentlyContinue
            }
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            Assert-True ($LASTEXITCODE -eq 0) 'A clean tree failed once the mid-build edit was gone.'

            # But only for HEAD. A clean tree whose HEAD is good says nothing about an older commit
            # pushed by name, or another branch, and those used to have HEAD built in their place.
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/other $broken refs/heads/other $good" 6> $null } `
                '*did not pass*' 'A clean tree passed a broken commit that was pushed but is not HEAD.'

            # Uncommitted files anywhere count, not only under the source folders: the tests read
            # README.md and patches-list.json from the root.
            $rootFile = Join-Path $gateRepo 'README.md'
            Set-Content -LiteralPath $rootFile -Value 'uncommitted' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built -like '*marker=good*' -and $built -notlike "*dir=$gateRepo marker*") `
                "An uncommitted root file was built in place with the push: $built"
            Remove-Item -LiteralPath $rootFile -Force

            # Git hands a hook GIT_DIR and GIT_WORK_TREE. Neither may steer the worktree commands
            # into this working tree, nor reach the build.
            Set-Content -LiteralPath $gateFile -Value 'broken' -Encoding ASCII
            $headBefore = (& git -C $gateRepo symbolic-ref HEAD).Trim()
            try {
                $env:GIT_DIR = Join-Path $gateRepo '.git'
                $env:GIT_WORK_TREE = $gateRepo
                & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null
            } finally {
                Remove-Item -LiteralPath Env:\GIT_DIR, Env:\GIT_WORK_TREE -ErrorAction SilentlyContinue
            }
            $built = Get-Content -LiteralPath $gateMarker -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $built.Trim() -like '*marker=good gitdir=') `
                "The build ran with git's hook variables set: $built"
            Assert-True ((Get-Content -LiteralPath $gateFile -Raw).Trim() -eq 'broken' -and
                (& git -C $gateRepo symbolic-ref HEAD).Trim() -eq $headBefore) `
                "With GIT_DIR set, building the pushed commit rewrote the working tree it was kept apart from."

            # One push at a time through the gate worktree. With the lock held here, a hook in
            # another process has to give up rather than check its commit out under a running build.
            $gateHasher = [System.Security.Cryptography.SHA256]::Create()
            try {
                $gateDigest = $gateHasher.ComputeHash([Text.Encoding]::UTF8.GetBytes(
                    [IO.Path]::GetFullPath($gateRepo).ToLowerInvariant()))
            } finally {
                $gateHasher.Dispose()
            }
            $gateKey = -join ($gateDigest[0..5] | ForEach-Object { $_.ToString('x2') })
            $shell = (Get-Process -Id $PID).Path
            $childRefs = "refs/heads/main $fixed refs/heads/main $broken"
            function Invoke-ChildPush {
                # Windows PowerShell stops on a native command's first line of standard error.
                $preference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    return (& $shell -NoProfile -File $prePushScript -Root $gateRepo -PushedRefs $childRefs `
                        -GateLockTimeoutSeconds 1 2>&1 | Out-String)
                } finally {
                    $ErrorActionPreference = $preference
                }
            }
            $held = New-Object System.Threading.Mutex($false, "Local\hushfeed-pre-push-$gateKey")
            Assert-True ($held.WaitOne(0)) 'The contract could not take the gate lock itself.'
            try {
                $waited = Invoke-ChildPush
                Assert-True ($LASTEXITCODE -ne 0 -and $waited -like '*held the gate worktree*') `
                    "A second push used the gate worktree while another push held it: $waited"
            } finally {
                $held.ReleaseMutex()
                $held.Dispose()
            }
            # The control: the same child push, with the lock free, goes through.
            $free = Invoke-ChildPush
            Assert-True ($LASTEXITCODE -eq 0) "The child push failed with the gate lock free: $free"

            # A commit on the pushed branch while the gate runs. Over HTTPS git reads the branch
            # again after the hook, so the new commit would go out unchecked. The stub build
            # commits to the branch in this repository while it builds the worktree; the tree is
            # dirty so the build runs in the worktree and nothing else notices.
            $mover = Join-Path $hookRoot 'gate-wrapper-commits.ps1'
            Set-Content -LiteralPath $mover -Encoding UTF8 -Value @(
                'param([string]$ProjectDir, [string[]]$Tasks)',
                "& git -C '$gateRepo' commit --quiet --allow-empty -m 'made during the gate'",
                'exit 0')
            Set-Content -LiteralPath (Join-Path $gateRepo 'README.md') -Value 'uncommitted' -Encoding ASCII
            $env:HUSHFEED_BUILD_WRAPPER = $mover
            try {
                Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $fixed refs/heads/main $broken" 6> $null } `
                    '*refs/heads/main moved from*' 'A branch that moved while the gate ran was pushed with its unchecked commit.'
            } finally {
                $env:HUSHFEED_BUILD_WRAPPER = $gateStub
                & git -C $gateRepo update-ref refs/heads/main $fixed
                Remove-Item -LiteralPath (Join-Path $gateRepo 'README.md') -Force -ErrorAction SilentlyContinue
            }

            # The release facts half checks the files a push carries as well. A stub check, committed
            # the way the real one is, fails on a README that says broken and records where it ran
            # and whether it read test results. Its own commit is never in a pushed range, so no
            # push below touches scripts/ and asks for contract tests this repository doesn't have.
            $gateFacts = Join-Path $hookRoot 'gate-facts-ran.txt'
            & git -C $gateRepo checkout --quiet -- extensions/marker.txt
            New-Item -ItemType Directory -Path (Join-Path $gateRepo 'scripts') -Force | Out-Null
            Set-Content -LiteralPath (Join-Path $gateRepo 'scripts/validate-release-facts.ps1') -Encoding UTF8 -Value @(
                'param([string]$Root, [switch]$SkipDescriptionTestCount, [switch]$AllowPublishedIndexLag,',
                '    [switch]$VerifyPublishedAsset, [string]$ArtifactPath, [switch]$SkipTestResults)',
                '$state = (Get-Content -LiteralPath (Join-Path $Root ''README.md'') -Raw).Trim()',
                "Set-Content -LiteralPath '$gateFacts' -Value (`"root=`$Root readme=`$state results=`$(-not `$SkipTestResults)`")",
                'if ($state -eq ''broken'') { exit 1 }',
                'exit 0')
            $gateReadme = Join-Path $gateRepo 'README.md'
            function Save-GateReadme([string]$State) {
                Set-Content -LiteralPath $gateReadme -Value $State -Encoding ASCII
                & git -C $gateRepo add README.md
                & git -C $gateRepo commit --quiet -m "readme $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            & git -C $gateRepo add scripts/validate-release-facts.ps1
            $factsBase = Save-GateReadme 'base'
            $factsGood = Save-GateReadme 'good'

            # An uncommitted README that would fail the check doesn't fail a push without it.
            Set-Content -LiteralPath $gateReadme -Value 'broken' -Encoding ASCII
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsGood refs/heads/main $factsBase" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like '*readme=good results=False*' -and
                $checked -notlike "*root=$gateRepo *") `
                "The release facts were read from the working tree instead of the pushed commit: $checked"

            # And an uncommitted fix doesn't pass a push whose own README fails.
            $factsBroken = Save-GateReadme 'broken'
            Set-Content -LiteralPath $gateReadme -Value 'good' -Encoding ASCII
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsBroken refs/heads/main $factsGood" 6> $null } `
                '*release facts do not agree*' 'An uncommitted README fix passed a push whose README fails the release facts.'

            # An index push is checked against the bundle and results this checkout built, so from a
            # dirty tree it is refused by name rather than checked against the wrong files.
            Set-Content -LiteralPath (Join-Path $gateRepo 'patches-bundle.json') -Value '{}' -Encoding ASCII
            & git -C $gateRepo add patches-bundle.json
            & git -C $gateRepo commit --quiet -m 'index'
            $factsIndex = (& git -C $gateRepo rev-parse HEAD).Trim()
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsIndex refs/heads/main $factsBroken" 6> $null } `
                '*clean checkout of the commit it pushes*' 'An index push from a dirty tree was checked against files it does not carry.'

            # The control: a clean tree pushing HEAD is checked in place, results and all.
            & git -C $gateRepo checkout --quiet -- README.md
            $factsFixed = Save-GateReadme 'good'
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $factsFixed refs/heads/main $factsIndex" 6> $null
            $checked = Get-Content -LiteralPath $gateFacts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $checked -like "*root=$gateRepo readme=good results=True*") `
                "A clean tree pushing HEAD was not checked in place: $checked"

            # The script suites are the pushed commit's too, run against that commit: they copy the
            # root files into their fixtures, and ran from the working tree until 2026-09-21. A stub
            # suite records where it ran and the state it was committed with.
            $gateContracts = Join-Path $hookRoot 'gate-contracts-ran.txt'
            $contractsStub = Join-Path $gateRepo 'scripts/test-script-contracts.ps1'
            function Save-GateContracts([string]$State) {
                Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @(
                    'param([string]$Root)',
                    "Set-Content -LiteralPath '$gateContracts' -Value (`"root=`$Root state=$State`")",
                    $(if ($State -eq 'broken') { 'exit 1' } else { 'exit 0' }))
                & git -C $gateRepo add scripts/test-script-contracts.ps1
                & git -C $gateRepo commit --quiet -m "contracts $State"
                return (& git -C $gateRepo rev-parse HEAD).Trim()
            }
            $contractsGood = Save-GateContracts 'good'
            Set-Content -LiteralPath $contractsStub -Encoding UTF8 -Value @('param([string]$Root)', 'exit 1')
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like '*state=good*' -and $ran -notlike "*root=$gateRepo *") `
                "The script contract tests ran from the working tree instead of the pushed commit: $ran"
            & git -C $gateRepo checkout --quiet -- scripts/test-script-contracts.ps1
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsGood refs/heads/main $factsFixed" 6> $null
            $ran = Get-Content -LiteralPath $gateContracts -Raw
            Assert-True ($LASTEXITCODE -eq 0 -and $ran -like "*root=$gateRepo state=good*") `
                "A clean tree pushing HEAD did not run its script contract tests in place: $ran"
            $contractsBroken = Save-GateContracts 'broken'
            Assert-Throws { & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $contractsBroken refs/heads/main $contractsGood" 6> $null } `
                '*script contract tests did not pass*' 'A push whose own script contract tests fail was let through.'
            & git -C $gateRepo checkout --quiet -- .

            # A source file moved out of the source folders. With rename detection the diff
            # named only the new path, so the push read as a docs change and built nothing.
            function Get-GateHead { return (& git -C $gateRepo rev-parse HEAD).Trim() }
            Set-Content -LiteralPath (Join-Path $gateRepo 'extensions/Moved.java') -Value 'class Moved {}' -Encoding ASCII
            & git -C $gateRepo add extensions/Moved.java
            & git -C $gateRepo commit --quiet -m 'a source file'
            $beforeMove = Get-GateHead
            New-Item -ItemType Directory -Path (Join-Path $gateRepo 'docs') -Force | Out-Null
            & git -C $gateRepo mv extensions/Moved.java docs/Moved.java
            & git -C $gateRepo commit --quiet -m 'moved out'
            $afterMove = Get-GateHead
            Remove-Item -LiteralPath $gateMarker -Force -ErrorAction SilentlyContinue
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $afterMove refs/heads/main $beforeMove" 6> $null
            Assert-True ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $gateMarker)) `
                'A push that moved a source file out of extensions/ ran no build.'

            # A name with a letter outside ASCII. git quoted it, and a quoted path matched no route.
            $wideName = 'extensions/' + [char]0x00DC + 'berall.java'
            Set-Content -LiteralPath (Join-Path $gateRepo $wideName) -Value 'class Wide {}' -Encoding ASCII
            & git -C $gateRepo add -- $wideName
            & git -C $gateRepo commit --quiet -m 'a wide name'
            $afterWide = Get-GateHead
            Remove-Item -LiteralPath $gateMarker -Force -ErrorAction SilentlyContinue
            & $prePushScript -Root $gateRepo -PushedRefs "refs/heads/main $afterWide refs/heads/main $afterMove" 6> $null
            Assert-True ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $gateMarker)) `
                'A push that added a source file with a non-ASCII name ran no build.'
        } finally {
            foreach ($line in @(& git -C $gateRepo worktree list --porcelain)) {
                if ($line -like 'worktree *') {
                    $listed = $line.Substring('worktree '.Length)
                    if ([IO.Path]::GetFullPath($listed).TrimEnd('\', '/') -ine [IO.Path]::GetFullPath($gateRepo).TrimEnd('\', '/')) {
                        & git -C $gateRepo worktree remove --force $listed
                    }
                }
            }
            Remove-Item -LiteralPath $gateRepo -Recurse -Force -ErrorAction SilentlyContinue
        }
    } finally {
        $env:HUSHFEED_BUILD_WRAPPER = $savedWrapper
        $env:GITHUB_ACTOR = $savedActor
        $env:GITHUB_TOKEN = $savedToken
    }
} finally {
    $env:HUSHFEED_SKIP_PRE_PUSH = $savedSkip
    foreach ($name in $savedHookGit.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $savedHookGit[$name] }
    Remove-Item -LiteralPath $hookRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] pre-push routing contracts passed'

# --- Test-ChangelogVersions ------------------------------------------------------------------
#
# A released version's heading is the only record a reader has that it shipped. On 2026-09-14 a
# post-release commit renamed "## 0.31.0" to "## Unreleased" and the file then said that release
# never happened; nothing read this file at all. Both directions are exercised here, and the
# real CHANGELOG is the control.

$headingShapes = @"
## 0.32.0
some text
## 0.31.0 (2026-09-14)
more text
## [0.1.5](https://example.invalid/compare/v0.1.4...v0.1.5) (2026-06-01)
older text
"@
$shapes = @(Get-ChangelogVersions -Text $headingShapes)
Assert-True (($shapes -join ',') -eq '0.32.0,0.31.0,0.1.5') `
    "The heading shapes this file uses were not all read: $($shapes -join ',')"
Assert-True (@(Get-ChangelogVersions -Text "## Unreleased`n## Notes").Count -eq 0) `
    'A heading that names no version was read as one.'

$previousChangelog = "## 0.31.0`nshipped`n## 0.30.2`nshipped"
$goodChangelog = "## 0.32.0`nnew`n" + $previousChangelog
$good = Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True $good.Valid "A CHANGELOG that kept every shipped version was refused: $($good.Reason)"

# The 2026-09-14 defect, exactly: the previous version's heading renamed to Unreleased.
$renamed = Test-ChangelogVersions -Current ("## 0.32.0`nnew`n## Unreleased`nshipped`n## 0.30.2`nshipped") `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog -PreviousLabel 'tag v0.31.0'
Assert-True (-not $renamed.Valid) 'A released version renamed to Unreleased was accepted.'
Assert-True ($renamed.Reason -like '*0.31.0*tag v0.31.0*') `
    "The renamed heading was refused for the wrong reason: $($renamed.Reason)"

$dropped = Test-ChangelogVersions -Current "## 0.32.0`nnew`n## 0.31.0`nshipped" `
    -ExpectedVersion '0.32.0' -Previous $previousChangelog
Assert-True (-not $dropped.Valid) 'A shipped version deleted from the CHANGELOG was accepted.'

$noHeading = Test-ChangelogVersions -Current $previousChangelog -ExpectedVersion '0.32.0' `
    -Previous $previousChangelog
Assert-True (-not $noHeading.Valid) 'A CHANGELOG with no heading for the built version was accepted.'
Assert-True ($noHeading.Reason -like '*no heading for 0.32.0*') `
    "The missing heading was refused for the wrong reason: $($noHeading.Reason)"

Assert-True (-not (Test-ChangelogVersions -Current "# Changelog`nnothing here" -ExpectedVersion '0.32.0').Valid) `
    'A CHANGELOG naming no version at all was accepted.'

# With no earlier file to compare against, the version being built is still required and a
# CHANGELOG that has it is still accepted. A first release has no tag behind it.
Assert-True (Test-ChangelogVersions -Current $goodChangelog -ExpectedVersion '0.32.0').Valid `
    'A checkout with no earlier tag was refused.'

# The control. The real file, held to the real version, must pass: every case above is this
# same shape with one heading moved.
$realVersion = Get-BundleVersion -Root $Root
$realChangelog = Get-Content -LiteralPath (Join-Path $Root 'CHANGELOG.md') -Raw
$realTag = "$(& git -C $Root describe --tags --abbrev=0 HEAD 2>$null | Select-Object -First 1)".Trim()
$realPrevious = if ($realTag) { (& git -C $Root show "${realTag}:CHANGELOG.md" 2>$null) -join "`n" } else { '' }
$realCheck = if ([string]::IsNullOrWhiteSpace($realPrevious)) {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion
} else {
    Test-ChangelogVersions -Current $realChangelog -ExpectedVersion $realVersion -Previous $realPrevious
}
Assert-True $realCheck.Valid "This repository's own CHANGELOG was refused: $($realCheck.Reason)"

Write-Host '[scripts] changelog history contracts passed'

# --- Test-ChangelogManagerEntry --------------------------------------------------------------
#
# Morphe Manager skips a heading with no date and flags an app only for bullets scoped to it.
# Every heading from 0.23.0 to 0.40.0 was bare and Manager's list stopped at 0.22.0, while the
# history check above passed them all. Each refusal below is the readable entry with one thing
# taken away, and the real CHANGELOG is the control.

$readable = @"
## Unreleased

* not held to anything yet

## 0.42.0 (2026-09-20)

A sentence about the release.

### Settings

* **TikTok:** one change.
* **TikTok:** another change.

### On the video

* **TikTok:** a third.

## 0.41.0

* an older entry, frozen as it shipped
"@
$entry = Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.42.0'
Assert-True $entry.Valid "A dated entry with scoped bullets was refused: $($entry.Reason)"
Assert-True ($entry.Date -eq '2026-09-20' -and $entry.Bullets -eq 3) `
    "The readable entry was misread: date $($entry.Date), $($entry.Bullets) bullets"
$crlf = Test-ChangelogManagerEntry -Current ($readable -replace "`r?`n", "`r`n") -ExpectedVersion '0.42.0'
Assert-True ($crlf.Valid -and $crlf.Bullets -eq 3) "CRLF line endings changed the reading: $($crlf.Reason)"

$undated = Test-ChangelogManagerEntry -Current ($readable -replace '## 0\.42\.0 \(2026-09-20\)', '## 0.42.0') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $undated.Valid) 'An undated heading, which Manager skips, was accepted.'
Assert-True ($undated.Reason -like '*no date*') "The undated heading was refused for the wrong reason: $($undated.Reason)"

$unscoped = Test-ChangelogManagerEntry -Current ($readable -replace '\* \*\*TikTok:\*\* another', '* another') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $unscoped.Valid) 'A bullet Manager does not scope to TikTok was accepted.'
Assert-True ($unscoped.Reason -like 'Line 12 *') "The unscoped bullet was refused for the wrong reason: $($unscoped.Reason)"

$wrapped = Test-ChangelogManagerEntry -Current ($readable -replace 'one change\.', "one`n  change.") `
    -ExpectedVersion '0.42.0'
Assert-True (-not $wrapped.Valid) 'A wrapped bullet, whose second line Manager drops, was accepted.'
Assert-True ($wrapped.Reason -like '*continues the bullet*') "The wrapped bullet was refused for the wrong reason: $($wrapped.Reason)"

$noBullets = Test-ChangelogManagerEntry -Current "## 0.42.0 (2026-09-20)`n`nOnly prose.`n" -ExpectedVersion '0.42.0'
Assert-True (-not $noBullets.Valid) 'An entry with no scoped bullet, which gets no update badge, was accepted.'

$otherScope = Test-ChangelogManagerEntry -Current ($readable -replace '\*\*TikTok:\*\* a third', '**YouTube:** a third') `
    -ExpectedVersion '0.42.0'
Assert-True (-not $otherScope.Valid) 'A bullet scoped to another app was accepted.'

Assert-True (-not (Test-ChangelogManagerEntry -Current $readable -ExpectedVersion '0.43.0').Valid) `
    'An entry for a version the CHANGELOG does not name was accepted.'

# The control. The real file, held to the real version, must pass.
$realEntry = Test-ChangelogManagerEntry -Current $realChangelog -ExpectedVersion $realVersion
Assert-True $realEntry.Valid "Morphe Manager could not read this repository's own $realVersion entry: $($realEntry.Reason)"

Write-Host '[scripts] changelog Manager contracts passed'

# --- Resolve-D8 ------------------------------------------------------------------------------
#
# The build-tools directory is chosen by version number. Sorted as text, 9.0.0 wins over 37.0.0
# and 100.0.0 loses to it; the installed set happens to be 34 through 37, which is why nobody
# had seen it pick wrong.

. (Join-Path $PSScriptRoot 'injected-register-contracts.ps1')
$sdkRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-sdk-" + [guid]::NewGuid().ToString('N'))
try {
    $checkout = Join-Path $sdkRoot 'checkout'
    $sdk = Join-Path $sdkRoot 'sdk'
    foreach ($version in @('9.0.0', '37.0.0', '100.0.0', 'not-a-version')) {
        $tools = Join-Path (Join-Path $sdk 'build-tools') $version
        New-Item -ItemType Directory -Path $tools -Force | Out-Null
        Set-Content -LiteralPath (Join-Path $tools 'd8.bat') -Value '@echo off' -Encoding ASCII
    }
    # 38.0.0 is there but carries no d8, so it must not be picked over 37.0.0.
    New-Item -ItemType Directory -Path (Join-Path (Join-Path $sdk 'build-tools') '38.0.0') -Force | Out-Null
    New-Item -ItemType Directory -Path $checkout -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $checkout 'local.properties') -Encoding ASCII `
        -Value ('sdk.dir=' + ($sdk -replace '\\', '\\'))

    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*100.0.0*d8.bat') "Resolve-D8 chose $chosen, not the newest build-tools by version."

    Remove-Item -LiteralPath (Join-Path (Join-Path $sdk 'build-tools') '100.0.0') -Recurse -Force
    $chosen = Resolve-D8 -Root $checkout
    Assert-True ($chosen -like '*37.0.0*d8.bat') "Resolve-D8 chose $chosen over 37.0.0; 9.0.0 sorts above it as text."

    $explicit = Join-Path (Join-Path (Join-Path $sdk 'build-tools') '9.0.0') 'd8.bat'
    Assert-True ((Resolve-D8 -Explicit $explicit -Root $checkout) -eq $explicit) `
        'An explicit d8 path was not honoured.'
    Assert-Throws { Resolve-D8 -Root (Join-Path $sdkRoot 'nowhere') } '*local.properties*' `
        'A checkout with no local.properties resolved a d8 from somewhere.'
} finally {
    Remove-Item -LiteralPath $sdkRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] d8 resolution contracts passed'

# --- common.ps1 ------------------------------------------------------------------------------
#
# The helpers four release scripts used to carry copies of. They had already drifted: the
# cleanup helper recursed unconditionally in one script and only on request in another, and two
# of the four version reads were missing -LiteralPath, which turns a repository path holding a
# bracket into a wildcard that matches nothing.

$commonRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-common-" + [guid]::NewGuid().ToString('N'))
$savedJar = $env:HUSHFEED_DESKTOP_JAR
$savedWork = $env:HUSHFEED_WORKDIR
try {
    New-Item -ItemType Directory -Path $commonRoot -Force | Out-Null
    $work = Join-Path $commonRoot 'work'
    New-Item -ItemType Directory -Path $work -Force | Out-Null

    # The path guard, which is what keeps a generated name from reaching outside the directory
    # the caller owns.
    $inside = Resolve-WithinRoot -Path (Join-Path $work 'run/output.apk') -Root $work
    Assert-True ($inside -like "$work*") 'A path inside the work directory was refused.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $commonRoot 'elsewhere.apk') -Root $work } `
        '*outside the work directory*' 'A path outside the work directory was accepted.'
    Assert-Throws { Resolve-WithinRoot -Path (Join-Path $work '..\escape.apk') -Root $work } `
        '*outside the work directory*' 'A path that climbs out with .. was accepted.'
    # The prefix trap: a sibling directory whose name starts with the work directory's name.
    Assert-Throws { Resolve-WithinRoot -Path ($work + '-other\file.apk') -Root $work } `
        '*outside the work directory*' 'A sibling sharing the name prefix was accepted as inside.'

    # Cleanup, recursive by default, and refusing anything outside the work directory.
    $tree = Join-Path $work 'run/deep'
    New-Item -ItemType Directory -Path $tree -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $tree 'leaf.txt') -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path (Join-Path $work 'run') -Root $work
    Assert-True (-not (Test-Path -LiteralPath (Join-Path $work 'run'))) `
        'A generated directory tree was left behind by the default cleanup.'

    $single = Join-Path $work 'one.txt'
    Set-Content -LiteralPath $single -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $single -Root $work -NoRecurse
    Assert-True (-not (Test-Path -LiteralPath $single)) 'A single generated file was not removed.'

    $outside = Join-Path $commonRoot 'keep.txt'
    Set-Content -LiteralPath $outside -Value 'x' -Encoding ASCII
    Remove-GeneratedPath -Path $outside -Root $work -WarningAction SilentlyContinue
    Assert-True (Test-Path -LiteralPath $outside) 'Cleanup deleted a path outside the work directory.'
    # A path that is simply not there is nothing to do, not a failure.
    Remove-GeneratedPath -Path (Join-Path $work 'never-existed') -Root $work

    # The version read. -LiteralPath is the difference that had already drifted, so the fixture
    # directory carries the bracket that makes a wildcard read find nothing.
    $bracketRoot = Join-Path $commonRoot 'repo [1]'
    New-Item -ItemType Directory -Path $bracketRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Encoding ASCII -Value @(
        'org.gradle.caching = true', 'version = 9.9.9', 'android.useAndroidX = true')
    Assert-True ((Get-BundleVersion -Root $bracketRoot) -eq '9.9.9') `
        'The version read failed on a path containing a bracket.'
    Assert-Throws { Get-BundleVersion -Root (Join-Path $commonRoot 'no-such-repo') } `
        '*no gradle.properties*' 'A missing gradle.properties was not reported.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'name = x' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*names no version*' `
        'A gradle.properties with no version was accepted.'
    Set-Content -LiteralPath (Join-Path $bracketRoot 'gradle.properties') -Value 'version =' -Encoding ASCII
    Assert-Throws { Get-BundleVersion -Root $bracketRoot } '*empty version*' `
        'A gradle.properties with an empty version was accepted.'

    # The desktop CLI lookup, which was two functions with different search orders. The jar
    # ships under its version, so the newest by write time is taken: sorting names as text puts
    # 1.9.0 above 1.15.0.
    $env:HUSHFEED_DESKTOP_JAR = $null
    $env:HUSHFEED_WORKDIR = $null
    $tools = Join-Path $commonRoot 'repo/build/morphe-tools'
    New-Item -ItemType Directory -Path $tools -Force | Out-Null
    $older = Join-Path $tools 'morphe-desktop-1.9.0-all.jar'
    $newer = Join-Path $tools 'morphe-desktop-1.15.0-all.jar'
    Set-Content -LiteralPath $older -Value 'old' -Encoding ASCII
    Set-Content -LiteralPath $newer -Value 'new' -Encoding ASCII
    (Get-Item -LiteralPath $older).LastWriteTime = (Get-Date).AddDays(-2)
    (Get-Item -LiteralPath $newer).LastWriteTime = (Get-Date)
    $repoRoot = Join-Path $commonRoot 'repo'
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'The desktop CLI lookup did not take the newest jar by write time.'

    Assert-True ($null -eq (Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty'))) `
        'The lookup invented a jar where there is none.'
    Assert-Throws { Resolve-DesktopCli -Root (Join-Path $commonRoot 'empty') -Required } `
        '*No Morphe desktop CLI*' 'A required lookup with nothing to find did not say so.'
    Assert-Throws { Resolve-DesktopCli -Explicit (Join-Path $commonRoot 'absent.jar') -Root $repoRoot } `
        '*at the path given*' 'A named jar that is not there was quietly replaced by a search.'
    Assert-True ((Resolve-DesktopCli -Explicit $older -Root $repoRoot) -eq $older) `
        'An explicitly named jar was not honoured.'

    $env:HUSHFEED_DESKTOP_JAR = $newer
    Assert-True ((Resolve-DesktopCli -Root $repoRoot) -eq $newer) `
        'HUSHFEED_DESKTOP_JAR was not read.'
} finally {
    $env:HUSHFEED_DESKTOP_JAR = $savedJar
    $env:HUSHFEED_WORKDIR = $savedWork
    Remove-Item -LiteralPath $commonRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] shared helper contracts passed'

# --- kept-run folder (verify-all-patches.ps1 -KeepIn) -----------------------------------------
#
# The folder is emptied before a run, so a mistyped -KeepIn used to take whatever it named with a
# recursive delete. Each unsafe case below is refused before anything moves, and every sentinel
# file is still there, byte for byte, afterwards. Roots and the profile only go through
# Test-KeptRunDirectory, which deletes nothing, so a broken check can't take a drive with it.
$keptRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('hushfeed-kept-' + [guid]::NewGuid().ToString('N'))
$keptLink = Join-Path $keptRoot 'link'
$savedLocation = Get-Location
try {
    $keptRepo = Join-Path $keptRoot 'repo'
    $keptFixtures = Join-Path $keptRoot 'fixtures'
    $keptElsewhere = Join-Path $keptRoot 'elsewhere'
    foreach ($directory in @((Join-Path $keptRepo '.git'), (Join-Path $keptRepo 'patches\build'),
            $keptFixtures, $keptElsewhere)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
    $keptApk = Join-Path $keptFixtures 'stock.apk'
    $keptBundle = Join-Path $keptRepo 'patches\build\bundle.mpp'
    $keptSentinels = @{}
    foreach ($file in @($keptApk, $keptBundle, (Join-Path $keptRepo 'README.md'), (Join-Path $keptElsewhere 'keep.txt'))) {
        [System.IO.File]::WriteAllBytes($file, [byte[]](1..64))
        $keptSentinels[$file] = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    }
    $keptInputs = @($keptApk, $keptBundle)

    $unsafe = [ordered]@{
        'the repository' = $keptRepo
        'the folder holding the fixture APK' = $keptFixtures
        'a folder above the bundle' = (Join-Path $keptRepo 'patches')
        'the folder above every input' = $keptRoot
        'an unowned folder with a file in it' = $keptElsewhere
    }
    foreach ($case in $unsafe.GetEnumerator()) {
        Assert-Throws { Initialize-KeptRunDirectory -Path $case.Value -Inputs $keptInputs -RepositoryRoot $keptRepo } `
            '*Refusing to keep runs*' "-KeepIn on $($case.Key) was accepted."
    }
    foreach ($protected in @([System.IO.Path]::GetPathRoot($keptRoot), [Environment]::GetFolderPath('UserProfile'))) {
        Assert-Throws { Test-KeptRunDirectory -Path $protected -Inputs $keptInputs -RepositoryRoot $keptRepo } `
            '*Refusing to keep runs*' "-KeepIn at $protected was accepted."
    }
    # A relative path resolves from PowerShell's location, not the process's working directory.
    Set-Location -LiteralPath (Join-Path $keptRepo 'patches\build')
    Assert-Throws { Test-KeptRunDirectory -Path '..\..\..\fixtures' -Inputs $keptInputs -RepositoryRoot $keptRepo } `
        '*Refusing to keep runs*' 'A relative -KeepIn naming the fixture folder was accepted.'
    Assert-Throws { Initialize-KeptRunDirectory -Path '..\..' -Inputs $keptInputs -RepositoryRoot $keptRepo } `
        '*Refusing to keep runs*' 'A relative -KeepIn naming the repository was accepted.'
    Set-Location -LiteralPath $savedLocation

    New-Item -ItemType Junction -Path $keptLink -Target $keptElsewhere | Out-Null
    Assert-Throws { Initialize-KeptRunDirectory -Path (Join-Path $keptLink 'kept') -Inputs $keptInputs -RepositoryRoot $keptRepo } `
        '*is a link*' 'A -KeepIn through a junction was accepted.'
    Assert-Throws { Initialize-KeptRunDirectory -Path $keptLink -Inputs $keptInputs -RepositoryRoot $keptRepo } `
        '*Refusing to keep runs*' 'A -KeepIn that is a junction was accepted.'

    # The pre-push gate's own folder: created, filled by a passing run, refreshed by the next.
    $kept = Join-Path $keptRoot 'kept\47.1.4'
    $claimed = Initialize-KeptRunDirectory -Path $kept -Inputs $keptInputs -RepositoryRoot $keptRepo
    Assert-True ($claimed -eq [System.IO.Path]::GetFullPath($kept)) "A fresh kept folder resolved to $claimed."
    foreach ($name in @('patched.apk', 'result.json', 'stamp.json')) {
        Set-Content -LiteralPath (Join-Path $kept $name) -Value $name -Encoding ASCII
    }
    Initialize-KeptRunDirectory -Path $kept -Inputs $keptInputs -RepositoryRoot $keptRepo | Out-Null
    $left = @(Get-ChildItem -LiteralPath $kept -Force | ForEach-Object { $_.Name })
    Assert-True ($left.Count -eq 1 -and $left[0] -eq '.hushfeed-kept-run') `
        "A kept folder wasn't refreshed for the next run: $($left -join ', ')"
    Set-Content -LiteralPath (Join-Path $kept 'notes.txt') -Value 'mine' -Encoding ASCII
    Assert-Throws { Initialize-KeptRunDirectory -Path $kept -Inputs $keptInputs -RepositoryRoot $keptRepo } `
        "*notes.txt isn't part of a kept run*" 'A kept folder holding someone else''s file was emptied.'
    Assert-True (Test-Path -LiteralPath (Join-Path $kept 'notes.txt')) 'A refused kept folder lost a file.'

    foreach ($entry in $keptSentinels.GetEnumerator()) {
        Assert-True ((Test-Path -LiteralPath $entry.Key) -and
            (Get-FileHash -LiteralPath $entry.Key -Algorithm SHA256).Hash -eq $entry.Value) `
            "A refused -KeepIn changed $($entry.Key)."
    }
} finally {
    Set-Location -LiteralPath $savedLocation
    # The junction goes on its own first: a recursive delete through it would reach its target.
    if (Test-Path -LiteralPath $keptLink) { [System.IO.Directory]::Delete($keptLink, $false) }
    Remove-Item -LiteralPath $keptRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host '[scripts] kept-run folder contracts passed'

# --- release bundle path ---------------------------------------------------------------------
#
# Every release step reads the bundle from patches/build/release. The plugin's buildAndroid
# finishes the bundle inside the jar task's own output, so a task run after it that reruns
# :patches:jar put the plain jar back under the same name in build/libs: v0.43.0 shipped with no
# classes.dex that way. buildAndroid copies the finished bundle to build/release, where nothing
# else writes, and the Gradle file and this helper have to agree on that directory.

$bundlePathRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("hushfeed-bundle-path-" + [guid]::NewGuid().ToString('N'))
try {
    New-Item -ItemType Directory -Path $bundlePathRoot -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $bundlePathRoot 'gradle.properties') -Value 'version = 9.9.9' -Encoding ASCII
    $expectedRelease = Join-Path $bundlePathRoot 'patches/build/release/patches-9.9.9.mpp'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot) -eq $expectedRelease) `
        'The release bundle path did not follow gradle.properties into patches/build/release.'
    Assert-True ((Get-ReleaseBundlePath -Root $bundlePathRoot -Version '1.2.3') -eq
        (Join-Path $bundlePathRoot 'patches/build/release/patches-1.2.3.mpp')) `
        'An explicit version was not used for the release bundle path.'
} finally {
    Remove-Item -LiteralPath $bundlePathRoot -Recurse -Force -ErrorAction SilentlyContinue
}

$gradleFile = Get-Content -LiteralPath (Join-Path $Root 'patches/build.gradle.kts') -Raw
Assert-True ($gradleFile -match 'buildDirectory\.dir\("release"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/\$releaseBundleName"\)' -and
    $gradleFile -match 'buildDirectory\.file\("release/bundle\.sha256"\)') `
    'patches/build.gradle.kts no longer writes and verifies the bundle in build/release, where the scripts read it.'

# Code only: a comment may say where the bundle used to be read from.
$libsReaders = New-Object System.Collections.Generic.List[string]
foreach ($script in @(Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1' -File)) {
    if ($script.Name -eq 'test-script-contracts.ps1') { continue }
    $inBlockComment = $false
    $number = 0
    foreach ($line in @(Get-Content -LiteralPath $script.FullName)) {
        $number++
        $trimmed = $line.Trim()
        if ($inBlockComment) {
            if ($trimmed -like '*#>*') { $inBlockComment = $false }
            continue
        }
        if ($trimmed.StartsWith('<#')) {
            if ($trimmed -notlike '*#>*') { $inBlockComment = $true }
            continue
        }
        if ($trimmed.StartsWith('#')) { continue }
        if ($trimmed -match 'build[\\/]+libs') { $libsReaders.Add("$($script.Name):$number") }
    }
}
Assert-True ($libsReaders.Count -eq 0) `
    ("These script lines read patches/build/libs, which :patches:jar rewrites with the plain jar: " +
        ($libsReaders -join ', '))

Write-Host '[scripts] release bundle path contracts passed'

# Every tracked PowerShell file parses. Most scripts here run only on a release or on a phone, so
# a syntax slip would otherwise surface there first: a "$label:" in build-release-receipt.ps1
# passed every contract above and stopped the first receipt that reached it.
$unparsed = New-Object System.Collections.Generic.List[string]
foreach ($tracked in @(& git -C $Root ls-files -- '*.ps1')) {
    $parseErrors = $null
    [void][System.Management.Automation.Language.Parser]::ParseFile((Join-Path $Root $tracked), [ref]$null, [ref]$parseErrors)
    foreach ($parseError in @($parseErrors)) { $unparsed.Add("${tracked}:$($parseError.Extent.StartLineNumber) $($parseError.Message)") }
}
Assert-True ($unparsed.Count -eq 0) ("These scripts don't parse: " + ($unparsed -join '; '))
Write-Host '[scripts] every tracked PowerShell file parses'

# --- tracked files name no machine -----------------------------------------------------------
#
# No tracked file names the working-notes folder .gitignore keeps out, the backup folders on the
# maintainer's machine that share its name, or a phone's adb serial. Four fixture tests fell back
# to one of those folders, which skipped quietly on every other machine and published this one's
# layout, and five scripts carried the test phone's serial. .gitignore is the one exception: it
# has to name what it keeps out. Both patterns are built from parts so this file cannot match
# itself, and the serial is matched by its shape, a Samsung serial being R5C and eight more
# letters or digits.

$assistantPattern = 'cla' + 'ude'
$serialPattern = 'R5' + 'C[A-Z0-9]{8}'
$machineNames = New-Object System.Collections.Generic.List[string]
foreach ($scan in @(@('-i', $assistantPattern), @('-E', $serialPattern))) {
    $hits = @(& git -C $Root grep -n -a $scan[0] -e $scan[1] -- '.' ':!.gitignore' 2>$null)
    # 1 is git grep's "no match". Anything above it means the search did not run, which must not
    # read as a clean tree.
    if ($LASTEXITCODE -gt 1) { throw "git grep could not search the tracked files for $($scan[1])." }
    foreach ($hit in $hits) { $machineNames.Add([string]$hit) }
}
$global:LASTEXITCODE = 0
Assert-True ($machineNames.Count -eq 0) `
    ("Tracked files name the maintainer's machine or phone: " + ($machineNames -join '; '))

Write-Host '[scripts] tracked-file machine name contracts passed'

# --- shared phones keep their log ------------------------------------------------------------
#
# Other sessions read the same phones' log buffers, so no tracked script clears one. The probe
# tools mark the start of each answer instead and read only what follows the last copy of that
# marker. The pattern is built from parts so this file cannot match itself.

. (Join-Path $Root 'tools/verification-probe/probe-log.ps1')
$probeMarker = "hushfeed-probe-$('a' * 32)"
$probeRun = @(Select-ProbeRun -Serial 'SERIAL' -Marker $probeMarker -Lines @(
    "hushfeed-probe-$('b' * 32)", 'ok stripkeys anchors=[earlier] banners=[]',
    $probeMarker, 'ok stripkeys anchors=[ours] banners=[]'))
Assert-True ($probeRun.Count -eq 1 -and $probeRun[0] -eq 'ok stripkeys anchors=[ours] banners=[]') `
    "A probe answer took in another session's lines from before its marker: $($probeRun -join ' | ')"
$probeRun = @(Select-ProbeRun -Serial 'SERIAL' -Marker $probeMarker -Lines @(
    $probeMarker, 'stale', "$probeMarker ", 'fresh'))
Assert-True ($probeRun.Count -eq 1 -and $probeRun[0] -eq 'fresh') 'A probe answer did not start after the last copy of its marker.'
Assert-Throws { Select-ProbeRun -Serial 'SERIAL' -Marker $probeMarker -Lines @('ok stripkeys anchors=[] banners=[]') } `
    '*no longer holds the start of this probe answer*' 'A probe answer whose marker rotated out was read anyway.'

# git grep finds the lines and .NET judges them, since a double quote in a native argument is
# split under Windows PowerShell 5.1. Flags may sit between the command and its clear.
$clearPattern = '(?<![\w-])log' + 'cat[\s''",]+(?:-{1,2}[A-Za-z]+(?:[\s''",]+[A-Za-z0-9:*]+)?[\s''",]+)*(?:-c|--clear)(?![\w-])'
$logCommand = 'log' + 'cat'
$logLines = @(& git -C $Root grep -n -I -e $logCommand -- '.' 2>$null)
if ($LASTEXITCODE -gt 1) { throw 'git grep could not search the tracked files for a log buffer clear.' }
$global:LASTEXITCODE = 0
$clears = @($logLines | Where-Object { $_ -match $clearPattern })
foreach ($sample in @("adb $logCommand -c", "@('-s', `$Serial, '$logCommand', '-c')", "adb $logCommand -b all --clear")) {
    Assert-True ($sample -match $clearPattern) "The log clear check misses: $sample"
}
foreach ($sample in @("adb $logCommand -d -s HushfeedProbe:V", "`$operation -eq '$logCommand' -and `$Arguments[3] -eq '-c'")) {
    Assert-True ($sample -notmatch $clearPattern) "The log clear check flags a read: $sample"
}
Assert-True ($clears.Count -eq 0) ("Tracked files clear a shared phone's log buffer: " + ($clears -join '; '))
foreach ($tool in @('strip-hunt.ps1', 'record-markers.ps1')) {
    Assert-True ((Get-Content -LiteralPath (Join-Path $Root "tools/verification-probe/$tool") -Raw) -match
        "probe-log\.ps1[\s\S]*Invoke-ProbeAction") "tools/verification-probe/$tool does not read the probe through its marker."
}

Write-Host '[scripts] shared phone log contracts passed'

# --- adb runs only under a device lease -----------------------------------------------------
#
# Two tasks must never drive one phone at once, so every script here that runs adb loads
# scripts/device-lease.ps1, itself or through a helper it names, and checks the lease before
# each adb command. The parser finds the commands, so adb in comments and strings doesn't count.
#
# A command counts as checked when an earlier statement in its block, or in a block around it,
# takes, checks or renews the lease, or calls a function of the same file that does. The search
# stops at a loop body and at a function or script block, since a lease checked once before a
# loop can run out by its next pass. Invoke-WithDeviceLease checks before its -Action block runs.

$leaseCalls = @('New-DeviceLease', 'Assert-DeviceLease', 'Update-DeviceLease')
function Get-AdbCommandReport([System.Management.Automation.Language.Ast]$Ast) {
    $adbCommands = @($Ast.FindAll({
        param($node)
        if ($node -isnot [System.Management.Automation.Language.CommandAst]) { return $false }
        $first = $node.CommandElements[0]
        ($first -is [System.Management.Automation.Language.StringConstantExpressionAst] -and $first.Value -match '^(?i)adb(\.exe)?$') -or
            ($first -is [System.Management.Automation.Language.VariableExpressionAst] -and $first.VariablePath.UserPath -match '(?i)adb')
    }, $true))
    $leaseFunctions = @($Ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true) |
        Where-Object {
            @($_.Body.FindAll({
                param($node)
                $node -is [System.Management.Automation.Language.CommandAst] -and $node.GetCommandName() -in $leaseCalls
            }, $false)).Count -gt 0
        } | ForEach-Object { $_.Name })
    $guards = $leaseCalls + $leaseFunctions
    $unchecked = New-Object System.Collections.Generic.List[int]
    foreach ($command in $adbCommands) {
        $checked = $false
        $inner = $command
        for ($outer = $command.Parent; $null -ne $outer -and -not $checked; $outer = $outer.Parent) {
            if ($outer -is [System.Management.Automation.Language.StatementBlockAst] -or $outer -is [System.Management.Automation.Language.NamedBlockAst]) {
                foreach ($statement in $outer.Statements) {
                    if ([object]::ReferenceEquals($statement, $inner)) { break }
                    $pipeline = if ($statement -is [System.Management.Automation.Language.AssignmentStatementAst]) { $statement.Right } else { $statement }
                    if ($pipeline -is [System.Management.Automation.Language.PipelineAst] -and
                        $pipeline.PipelineElements[0] -is [System.Management.Automation.Language.CommandAst] -and
                        $pipeline.PipelineElements[0].GetCommandName() -in $guards) { $checked = $true; break }
                }
            }
            if ($outer -is [System.Management.Automation.Language.LoopStatementAst]) { break }
            if ($outer -is [System.Management.Automation.Language.ScriptBlockAst]) {
                $checked = $checked -or ($outer.Parent -is [System.Management.Automation.Language.ScriptBlockExpressionAst] -and
                    $outer.Parent.Parent -is [System.Management.Automation.Language.CommandAst] -and
                    $outer.Parent.Parent.GetCommandName() -eq 'Invoke-WithDeviceLease')
                break
            }
            $inner = $outer
        }
        if (-not $checked) { $unchecked.Add($command.Extent.StartLineNumber) }
    }
    $helpers = @($Ast.FindAll({
        param($node)
        $node -is [System.Management.Automation.Language.StringConstantExpressionAst] -and $node.Value -match '\.ps1$'
    }, $true) | ForEach-Object { Split-Path -Leaf $_.Value })
    [pscustomobject]@{ Adb = $adbCommands.Count; Unchecked = @($unchecked); Helpers = $helpers }
}
# Positive control: comments and strings aren't commands, and only lines 7, 8 and 9 run adb
# with no check since the last loop pass or function entry.
$sampleReport = Get-AdbCommandReport ([System.Management.Automation.Language.Parser]::ParseInput((@(
    "# adb -s x shell input tap 1 1", "'adb -s x shell'",
    "`$lease = New-DeviceLease -LeaseDir d -Serial s -Project p -ChatIdentity c -Purpose u",
    "& adb -s `$serial shell input tap 1 1",
    "if (`$x) { & `$adbPath -s `$serial pull a b }",
    "function Test-Phone { Assert-DeviceLease -Lease `$lease; & adb -s s shell true }",
    "foreach (`$i in 1) { & adb -s s shell true }",
    "function Send-Tap { & adb -s s shell input tap 1 1 }",
    "@(1) | ForEach-Object { & adb -s s shell true }",
    "for (;;) { Test-Phone; & adb -s s shell true; Invoke-WithDeviceLease -Lease `$lease -Action { & adb -s s shell true } }"
) -join "`n"), [ref]$null, [ref]$null))
Assert-True ($sampleReport.Adb -eq 8 -and ($sampleReport.Unchecked -join ',') -eq '7,8,9') `
    "The adb lease check misreads commands, comments, strings or checks: $($sampleReport.Adb) commands, unchecked lines $($sampleReport.Unchecked -join ',')"
$leaseScripts = @(Get-ChildItem -LiteralPath (Join-Path $Root 'scripts'), (Join-Path $Root 'tools/verification-probe'), (Join-Path $Root 'audit') -Recurse -Filter '*.ps1' -File)
$leaseLoaders = @($leaseScripts | Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match 'device-lease\.ps1' } |
    ForEach-Object { $_.Name })
$adbScripts = New-Object System.Collections.Generic.List[string]
$unleased = New-Object System.Collections.Generic.List[string]
foreach ($scriptFile in $leaseScripts) {
    $relative = $scriptFile.FullName.Substring($Root.Length).TrimStart('\', '/').Replace('\', '/')
    $report = Get-AdbCommandReport ([System.Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$null, [ref]$null))
    if ($report.Adb -eq 0) { continue }
    $adbScripts.Add($relative)
    $loadsLease = $scriptFile.Name -in $leaseLoaders -or @($report.Helpers | Where-Object { $_ -in $leaseLoaders }).Count -gt 0
    if (-not $loadsLease) { $unleased.Add("$relative (doesn't load device-lease.ps1)") }
    elseif ($report.Unchecked.Count -gt 0) { $unleased.Add("$relative lines $($report.Unchecked -join ', ')") }
}
Assert-True ($adbScripts.Count -ge 9 -and 'audit/run-audit.ps1' -in $adbScripts) "The adb lease check found only $($adbScripts.Count) scripts that run adb: $($adbScripts -join ', ')"
Assert-True ($unleased.Count -eq 0) ("Scripts run adb without checking the device lease first: " + ($unleased -join '; '))

Write-Host '[scripts] adb device lease contracts passed'

& (Join-Path $Root 'scripts/test-storage-probe.ps1') -Root $Root
& (Join-Path $Root 'scripts/test-fixture-jobs.ps1') -Root $Root
& (Join-Path $Root 'scripts/test-release-publication.ps1') -Root $Root
& (Join-Path $Root 'scripts/test-device-lease.ps1')
& (Join-Path $Root 'audit/test-evidence.ps1')

$global:LASTEXITCODE = 0
Write-Host '[scripts] report, target, Java and guarded replacement contracts passed'
