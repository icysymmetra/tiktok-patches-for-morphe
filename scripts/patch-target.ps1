<#
.SYNOPSIS
    Read the app package and the exact versions every patch in a generated catalog declares.

.DESCRIPTION
    Device patching, fixture verification, heap measurements and release validation all need
    the package and versions that the bundle supports. Keeping that fact in patches-list.json,
    which is generated from AppCompatibilities.kt, prevents those callers from drifting apart.
    Every patch has to declare the same versions: the bundle is held to each of them, and a
    patch that left one out would be missing from that build in the Manager without a word.
    PackageVersions lists them oldest first; PackageVersion is the newest, for one-line text.
#>

function Get-PatchTarget {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][object]$PatchList)

    $patches = @($PatchList.patches)
    if ($patches.Count -eq 0) { throw 'patches-list.json contains no patches.' }

    $targets = @{}
    $versionSets = @{}
    $declaredBy = @{}
    foreach ($patch in $patches) {
        if ($null -eq $patch) { throw 'patches-list.json contains a null patch.' }
        $nameProperty = $patch.PSObject.Properties['name']
        $patchName = if ($null -ne $nameProperty -and -not [string]::IsNullOrWhiteSpace([string]$nameProperty.Value)) {
            [string]$nameProperty.Value
        } else {
            '<unnamed patch>'
        }
        $compatible = $patch.PSObject.Properties['compatiblePackages']
        if ($null -eq $compatible -or $null -eq $compatible.Value) {
            throw "$patchName has no compatible package in patches-list.json."
        }
        $packages = @($compatible.Value.PSObject.Properties)
        if ($packages.Count -eq 0) {
            throw "$patchName has no compatible package in patches-list.json."
        }
        foreach ($property in $packages) {
            $versions = @($property.Value | ForEach-Object { [string]$_ } |
                Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
            if ($versions.Count -eq 0) {
                throw "$patchName has no exact compatible version for $($property.Name)."
            }
            if (-not $targets.ContainsKey($property.Name)) {
                $targets[$property.Name] = @()
                $versionSets[$property.Name] = @{}
                $declaredBy[$property.Name] = 0
            }
            $targets[$property.Name] += $versions
            $declaredBy[$property.Name]++
            $set = (@($versions | Sort-Object -Unique) -join ', ')
            if (-not $versionSets[$property.Name].ContainsKey($set)) { $versionSets[$property.Name][$set] = $patchName }
        }
    }

    # The bundle's target is the package every patch declares. Another app built from the same code
    # (PineDrama, #156) is declared only by the patches that apply to it, and is listed beside it.
    $packages = @($targets.Keys | Sort-Object)
    $everywhere = @($packages | Where-Object { $declaredBy[$_] -eq $patches.Count })
    if ($everywhere.Count -ne 1) {
        $found = if ($everywhere.Count -eq 0) { 'none' } else { $everywhere -join ', ' }
        throw "Expected one package every patch declares, found $found among $($packages -join ', ')."
    }
    $packageName = $everywhere[0]
    foreach ($package in $packages) {
        if ($versionSets[$package].Count -ne 1) {
            $sets = @($versionSets[$package].Keys | Sort-Object | ForEach-Object { "$($versionSets[$package][$_]) declares $_" })
            throw "Expected every patch to declare the same versions of ${package}: $($sets -join '; ')."
        }
    }
    $versions = @($targets[$packageName] | Sort-Object -Unique | Sort-Object { [version]$_ })
    return [pscustomobject]@{
        PackageName = $packageName
        PackageVersions = $versions
        PackageVersion = $versions[-1]
        OtherPackages = @($packages | Where-Object { $_ -ne $packageName })
    }
}

<#
.SYNOPSIS
    Versions as a sentence names them: "47.1.4", "47.1.4 and 47.2.3", "a, b and c".
#>
function Format-VersionList {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][AllowEmptyCollection()][string[]]$Versions)

    $list = @($Versions)
    if ($list.Count -le 1) { return ($list -join '') }
    return (@($list[0..($list.Count - 2)]) -join ', ') + ' and ' + $list[-1]
}

<#
.SYNOPSIS
    The declared version a patching report is held to: the one it names, when the catalog
    declares it, and otherwise the newest declared one, so a run on any other build fails the
    report check by name instead of passing as though it were declared.
#>
function Get-DeclaredReportVersion {
    [CmdletBinding()]
    param([AllowNull()][object]$Report, [Parameter(Mandatory = $true)][object]$Target)

    $named = if ($null -ne $Report) { [string]$Report.packageVersion } else { '' }
    if ($named -cin @($Target.PackageVersions)) { return $named }
    return [string]$Target.PackageVersion
}

function Resolve-PatchVerificationTarget {
    <# An alternate package is an explicit forced qualification probe, never declared support. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][object]$Stock,
        [Parameter(Mandatory = $true)][object]$Target,
        [switch]$Force,
        [string]$ProbePackage
    )

    if ([string]::IsNullOrWhiteSpace([string]$Stock.versionName)) {
        throw 'The stock APK carries no versionName.'
    }
    $probe = -not [string]::IsNullOrEmpty($ProbePackage)
    if ($probe) {
        if (-not $Force) { throw '-ProbePackage requires -Force for an undeclared-package qualification.' }
        if ($ProbePackage -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$') {
            throw '-ProbePackage is not an Android package name.'
        }
        if ([string]$Stock.package -cne $ProbePackage) {
            throw "The stock APK is $($Stock.package), not the requested probe package $ProbePackage."
        }
        if ($ProbePackage -ceq [string]$Target.PackageName) {
            throw '-ProbePackage must name an undeclared package. Use -Force alone for a version probe.'
        }
    } elseif ([string]$Stock.package -cne [string]$Target.PackageName) {
        throw "The stock APK is $($Stock.package), not the catalog's target $($Target.PackageName)."
    }
    $forced = $probe -or [string]$Stock.versionName -cnotin @($Target.PackageVersions)
    if ($forced -and -not $Force) {
        throw "The stock APK version $($Stock.versionName) is not declared. Pass -Force to qualify it."
    }
    return [pscustomobject]@{
        PackageName = [string]$Stock.package
        PackageVersion = [string]$Stock.versionName
        Forced = [bool]$forced
        Probe = [bool]$probe
    }
}
