<#
.SYNOPSIS
    Shared guarded ADB operations for a replacement install.
#>

. (Join-Path $PSScriptRoot 'device-lease.ps1')

# The package's installed APK paths, none when it isn't installed. On API 36 pm path exits 1 with
# no output for a package that isn't there, which only a device adb still reaches tells apart from
# a broken connection, so a silent failure counts as absent only when get-state answers "device".
function Get-AndroidPackagePaths {
    param([string]$Adb, [string]$Serial, [string]$PackageName, [AllowNull()]$Lease)

    # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop,
    # even redirected, and adb's "daemon not running; starting now" is on stderr.
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        Assert-DeviceLease -Lease $Lease -Serial $Serial
        $output = @(& $Adb -s $Serial shell pm path $PackageName 2>&1)
        $status = $LASTEXITCODE
        $state = ''
        if ($status -ne 0) {
            Assert-DeviceLease -Lease $Lease -Serial $Serial
            $state = (@(& $Adb -s $Serial get-state 2>&1) -join ' ').Trim()
        }
    } finally {
        $ErrorActionPreference = $preference
    }
    $said = @($output | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ })
    if ($status -ne 0 -and ($said.Count -gt 0 -or $state -ne 'device')) {
        $detail = $said -join ' '
        if (-not $detail) { $detail = "exit $status" }
        throw "adb could not check $PackageName on ${Serial}: $detail"
    }
    @($said | Where-Object { $_.StartsWith('package:', [StringComparison]::Ordinal) } |
        ForEach-Object { $_.Substring(8) })
}

function Assert-InstalledApkSigner {
    param(
        [string]$Adb, [string]$Serial, [string]$PackageName, $SigningSession, [AllowNull()]$Lease,
        [switch]$RequireInstalled
    )

    $preference = $ErrorActionPreference
    $checkDirectory = $null
    try {
        $paths = @(Get-AndroidPackagePaths -Adb $Adb -Serial $Serial -PackageName $PackageName -Lease $Lease)
        if ($paths.Count -eq 0) {
            if ($RequireInstalled) { throw "$PackageName must be installed before installing its verification probe." }
            return
        }
        $base = @($paths | Where-Object { $_ -match '/base\.apk$' })
        if ($base.Count -eq 0 -and $paths.Count -eq 1) { $base = $paths }
        if ($base.Count -ne 1) { throw "adb returned no unique base APK for $PackageName." }
        $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        $checkDirectory = Resolve-WithinRoot -Root $temporaryRoot `
            -Path (Join-Path $temporaryRoot ('hushfeed-signer-' + [Guid]::NewGuid().ToString('N')))
        New-Item -ItemType Directory -Path $checkDirectory | Out-Null
        $installedApk = Join-Path $checkDirectory 'installed.apk'
        $ErrorActionPreference = 'Continue'
        Assert-DeviceLease -Lease $Lease -Serial $Serial
        $pullOutput = @(& $Adb -s $Serial pull $base[0] $installedApk 2>&1)
        $status = $LASTEXITCODE
        $ErrorActionPreference = $preference
        if ($status -ne 0) {
            $detail = (@($pullOutput | ForEach-Object { ([string]$_).Trim() } | Where-Object { $_ }) -join ' ')
            if (-not $detail) { $detail = "exit $status" }
            throw "adb could not read the installed base APK for ${PackageName}: $detail"
        }
        if ((Get-ApkSigningCertificate -Session $SigningSession -Apk $installedApk) -ne $SigningSession.Certificate) {
            throw "The installed $PackageName has a different signing certificate. Export its signing key before an in-place update."
        }
    } finally {
        $ErrorActionPreference = $preference
        if ($checkDirectory -and (Test-Path -LiteralPath $checkDirectory)) {
            [void](Resolve-WithinRoot -Root ([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) -Path $checkDirectory)
            Remove-Item -LiteralPath $checkDirectory -Recurse -Force
        }
    }
}

function Remove-AndroidPackageIfInstalled {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][string]$Adb,
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][string]$PackageName,
        [AllowNull()]$Lease
    )

    $installedPaths = @(Get-AndroidPackagePaths -Adb $Adb -Serial $Serial -PackageName $PackageName -Lease $Lease)
    if ($installedPaths.Count -eq 0) {
        Write-Host "[device] $PackageName is not installed on $Serial; skipping uninstall"
        return $false
    }

    Write-Host "[device] uninstalling $PackageName on $Serial"
    Assert-DeviceLease -Lease $Lease -Serial $Serial
    & $Adb -s $Serial uninstall $PackageName | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "adb uninstall failed on $Serial." }
    return $true
}
