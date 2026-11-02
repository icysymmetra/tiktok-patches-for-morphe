<#
.SYNOPSIS
    The helpers the release and verification scripts share.

.DESCRIPTION
    Dot-source this beside patch-target.ps1 and patch-report.ps1:

        . (Join-Path $PSScriptRoot 'common.ps1')

    Each of these existed in two to four copies that had already drifted apart. The path guard
    was identical in three scripts; the cleanup helper recursed unconditionally in one and only
    on request in another; the version read appeared four times, twice without -LiteralPath; and
    the desktop CLI was looked up by two functions with different search orders, one returning
    $null and one throwing. Copies of a guard drift in the direction of whichever caller was
    edited last, which is the direction nobody checked.
#>

function Resolve-WithinRoot {
    <#
    .SYNOPSIS
        A generated path, proved to be inside the work directory, or a throw.
    .DESCRIPTION
        Every path these scripts hand to the patcher or delete afterwards goes through this, so
        a run identifier that came out wrong cannot reach outside the directory the caller owns.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root
    )
    $candidate = [System.IO.Path]::GetFullPath($Path)
    $prefix = $Root.TrimEnd('\') + '\'
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to use a generated path outside the work directory: $candidate"
    }
    return $candidate
}

function Remove-GeneratedPath {
    <#
    .SYNOPSIS
        Delete something this run generated, inside the work directory, warning rather than
        failing when it will not go.
    .DESCRIPTION
        Recursive by default, because every caller is deleting a run directory and the one copy
        that made it optional had its single caller pass -Recurse anyway. Pass -NoRecurse for a
        single file. A failure here is reported and swallowed on purpose: leaving scratch behind
        is not a reason to fail a run that has already produced its answer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Root,
        [switch]$NoRecurse
    )
    try {
        $safe = Resolve-WithinRoot -Path $Path -Root $Root
        if (-not (Test-Path -LiteralPath $safe)) { return }
        if ($NoRecurse) { Remove-Item -LiteralPath $safe -Force -ErrorAction Stop }
        else { Remove-Item -LiteralPath $safe -Recurse -Force -ErrorAction Stop }
    } catch {
        Write-Warning "Could not remove generated path: $($_.Exception.Message)"
    }
}

$script:KeptRunMarker = '.hushfeed-kept-run'
$script:KeptRunFiles = @('.hushfeed-kept-run', 'patched.apk', 'result.json', 'stamp.json')

function Test-KeptRunDirectory {
    <#
    .SYNOPSIS
        The folder a passing verify-all-patches.ps1 run is kept in, resolved, or a throw. Reads
        only; nothing is deleted here.
    .DESCRIPTION
        -KeepIn gets emptied, so a mistyped value used to take whatever it named with a recursive
        delete: a fixture folder, the repository, a drive root. A folder qualifies when it's
        missing, empty, or one this claimed before (its marker names it) holding nothing but a
        kept run. Roots, the profile, a repository, a folder holding any input, and any path
        through a junction or symbolic link are refused. A relative path resolves from the
        current location.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string[]]$Inputs = @(),
        [string]$RepositoryRoot
    )
    $resolved = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    $absolute = [IO.Path]::GetFullPath($resolved).TrimEnd('\')
    if ($absolute.StartsWith('\\?\') -or $absolute.StartsWith('\\.\')) {
        throw "Refusing to keep runs in ${Path}: it's a device path."
    }
    $protected = @([IO.Path]::GetPathRoot($absolute), [Environment]::GetFolderPath('UserProfile'), $RepositoryRoot) + $Inputs
    foreach ($entry in $protected) {
        if (-not $entry) { continue }
        $full = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($entry)).TrimEnd('\')
        if ([string]::Equals($full, $absolute, [StringComparison]::OrdinalIgnoreCase) -or
                $full.StartsWith($absolute + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to keep runs in ${absolute}: emptying it would delete $full."
        }
    }
    if (Test-Path -LiteralPath (Join-Path $absolute '.git')) {
        throw "Refusing to keep runs in ${absolute}: it's a repository."
    }
    # GetFullPath is lexical, so every existing ancestor is checked for a link before it's trusted.
    $cursor = $absolute
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Refusing to keep runs in ${absolute}: $cursor is a link."
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
    if (-not (Test-Path -LiteralPath $absolute)) { return $absolute }
    if (-not (Test-Path -LiteralPath $absolute -PathType Container)) {
        throw "Refusing to keep runs in ${absolute}: it's a file."
    }
    $children = @(Get-ChildItem -LiteralPath $absolute -Force)
    if ($children.Count -eq 0) { return $absolute }
    $marker = Join-Path $absolute $script:KeptRunMarker
    $owned = (Test-Path -LiteralPath $marker -PathType Leaf) -and
        [string]::Equals([IO.File]::ReadAllText($marker), "hushfeed-kept-run-v1`n$absolute", [StringComparison]::OrdinalIgnoreCase)
    if (-not $owned) {
        throw "Refusing to keep runs in ${absolute}: it holds files this script didn't put there. Choose an empty folder."
    }
    foreach ($child in $children) {
        if (($child.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $child.PSIsContainer -or
                -not ($script:KeptRunFiles -contains $child.Name)) {
            throw "Refusing to keep runs in ${absolute}: $($child.Name) isn't part of a kept run."
        }
    }
    return $absolute
}

function Initialize-KeptRunDirectory {
    <#
    .SYNOPSIS
        Empty the kept-run folder Test-KeptRunDirectory accepted and claim it with a marker.
        Returns its absolute path.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [string[]]$Inputs = @(),
        [string]$RepositoryRoot
    )
    $absolute = Test-KeptRunDirectory -Path $Path -Inputs $Inputs -RepositoryRoot $RepositoryRoot
    if (Test-Path -LiteralPath $absolute) {
        # Only the files a kept run holds are here, checked one level deep with no links.
        foreach ($child in @(Get-ChildItem -LiteralPath $absolute -Force)) {
            Remove-Item -LiteralPath $child.FullName -Force
        }
    } else {
        New-Item -ItemType Directory -Path $absolute | Out-Null
    }
    [IO.File]::WriteAllText((Join-Path $absolute $script:KeptRunMarker), "hushfeed-kept-run-v1`n$absolute")
    return $absolute
}

function Get-BundleVersion {
    <#
    .SYNOPSIS
        The version in gradle.properties, which every generated name follows.
    .DESCRIPTION
        -LiteralPath, which two of the four copies of this read were missing: a repository path
        holding a bracket is read as a wildcard otherwise, and the read silently finds nothing.
    #>
    param([Parameter(Mandatory = $true)][string]$Root)

    $path = Join-Path $Root 'gradle.properties'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "There is no gradle.properties at $path, so the bundle version is unknown."
    }
    $line = @(Get-Content -LiteralPath $path | Where-Object { $_ -match '^\s*version\s*=' }) |
        Select-Object -First 1
    if (-not $line) { throw "gradle.properties names no version: $path" }
    $version = ($line -replace '^\s*version\s*=\s*', '').Trim()
    if (-not $version) { throw "gradle.properties has an empty version: $path" }
    return $version
}

function Get-ReleaseBundlePath {
    <#
    .SYNOPSIS
        Where :patches:buildAndroid leaves the bundle a release publishes.
    .DESCRIPTION
        patches/build/release, never patches/build/libs. The Morphe plugin's buildAndroid adds the
        DEX payload to the jar task's own output in place, so any later task that reruns
        :patches:jar (:patches:test does) wrote the plain jar back over the finished bundle under
        the same name. v0.43.0 shipped that jar, and on 2026-09-21 it happened again between the
        build and the index push. buildAndroid now ends by copying the finished bundle here, where
        no other task writes, with bundle.sha256 beside it.
    #>
    param([Parameter(Mandatory = $true)][string]$Root, [string]$Version)

    if (-not $Version) { $Version = Get-BundleVersion -Root $Root }
    return Join-Path $Root "patches/build/release/patches-$Version.mpp"
}

function Get-SourcesNewerThanBundle {
    <#
    .SYNOPSIS
        The source files written after the bundle was built, newest first.
    .DESCRIPTION
        Only :patches:buildAndroid writes the release bundle, and :patches:test rebuilds
        build/libs without it, so a device build made after a patch change and a test run
        patched with the previous hooks (2026-09-23, Swipe-left controls). Counted: the sources of
        the patches module and its submodules (patches/src/main, patches/<submodule>/src/main,
        the compile-only stubs among them, whose constants can be inlined into patch code), the
        sources of every extension module (extensions/<module>/src/main and
        extensions/<module>/<submodule>/src/main), the Gradle files that shape them, and the R8
        rules (*.pro at extensions/ and in each module, extensions/proguard-rules.pro being the
        one every extension's R8 step reads). Build output is never under src/main, so it is not
        walked.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$Bundle
    )

    $built = (Get-Item -LiteralPath $Bundle).LastWriteTimeUtc
    $sourceRoots = @()
    $gradleFiles = @('gradle.properties', 'settings.gradle.kts', 'build.gradle.kts', 'gradle/libs.versions.toml') |
        ForEach-Object { Join-Path $Root $_ }
    $ruleDirs = @()
    $patches = Join-Path $Root 'patches'
    $moduleDirs = @()
    if (Test-Path -LiteralPath $patches -PathType Container) {
        $moduleDirs += @(Get-Item -LiteralPath $patches) + @(Get-ChildItem -LiteralPath $patches -Directory |
            Where-Object { $_.Name -notin @('src', 'build') })
    }
    $extensions = Join-Path $Root 'extensions'
    if (Test-Path -LiteralPath $extensions -PathType Container) {
        $ruleDirs += $extensions
        foreach ($module in Get-ChildItem -LiteralPath $extensions -Directory) {
            $moduleDirs += @($module) + @(Get-ChildItem -LiteralPath $module.FullName -Directory |
                Where-Object { $_.Name -notin @('src', 'build') })
        }
    }
    foreach ($dir in $moduleDirs) {
        $sourceRoots += Join-Path $dir.FullName 'src/main'
        $gradleFiles += Join-Path $dir.FullName 'build.gradle.kts'
        $ruleDirs += $dir.FullName
    }
    foreach ($dir in $ruleDirs) {
        $gradleFiles += @(Get-ChildItem -LiteralPath $dir -File -Filter '*.pro' -ErrorAction SilentlyContinue |
            ForEach-Object FullName)
    }
    $newer = New-Object System.Collections.Generic.List[System.IO.FileInfo]
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container)) { continue }
        foreach ($file in Get-ChildItem -LiteralPath $sourceRoot -File -Recurse) {
            if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
        }
    }
    foreach ($path in $gradleFiles) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { continue }
        $file = Get-Item -LiteralPath $path
        if ($file.LastWriteTimeUtc -gt $built) { $newer.Add($file) }
    }
    return @($newer | Sort-Object LastWriteTimeUtc -Descending)
}

function Resolve-DesktopCli {
    <#
    .SYNOPSIS
        The Morphe desktop CLI jar, or $null, or a throw when the caller cannot do without it.
    .DESCRIPTION
        One search order for both callers: -Explicit, HUSHFEED_DESKTOP_JAR, HUSHFEED_WORKDIR,
        then the repository's own build/morphe-tools. Newest by write time rather than by name,
        because the jar ships under its version and sorting those as text puts 1.9.0 above
        1.15.0. -Required turns "nothing found" into a throw naming what to set, which is what
        the second copy of this did and the first did not.
    #>
    param([string]$Explicit, [string]$Root, [switch]$Required)

    $found = $null
    if ($Explicit -and (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
        $found = [System.IO.Path]::GetFullPath($Explicit)
    } elseif ($Explicit) {
        # Named and not there: say so rather than quietly searching somewhere else.
        throw "No Morphe desktop CLI at the path given: $Explicit"
    } elseif ($env:HUSHFEED_DESKTOP_JAR -and
            (Test-Path -LiteralPath $env:HUSHFEED_DESKTOP_JAR -PathType Leaf)) {
        $found = [System.IO.Path]::GetFullPath($env:HUSHFEED_DESKTOP_JAR)
    } else {
        $directories = @($env:HUSHFEED_WORKDIR)
        if ($Root) { $directories += (Join-Path $Root 'build/morphe-tools') }
        foreach ($directory in $directories) {
            if (-not $directory -or -not (Test-Path -LiteralPath $directory -PathType Container)) { continue }
            $candidate = @(Get-ChildItem -LiteralPath $directory -Filter 'morphe-desktop*.jar' -File `
                -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending |
                Select-Object -First 1)
            if ($candidate.Count -eq 1) { $found = $candidate[0].FullName; break }
        }
    }

    if (-not $found -and $Required) {
        throw ('No Morphe desktop CLI. Pass -DesktopJar, or set HUSHFEED_DESKTOP_JAR or ' +
            'HUSHFEED_WORKDIR, or put the jar in build/morphe-tools.')
    }
    return $found
}

function Get-ManagerChangelogGroups {
    <#
    .SYNOPSIS
        The ### groups of one dated CHANGELOG.md section, as patches-bundle.json quotes them.
    .DESCRIPTION
        validate-release-facts.ps1 holds the index to this, and the script contracts build their
        copied index from it, so the copy and the check can't disagree about what a group is.
    #>
    param(
        [string]$Text,
        [string]$Version
    )

    $release = [regex]::Match($Text,
        "(?ms)^## $([regex]::Escape($Version)) \([^\r\n)]+\)\r?\n(?<Body>.*?)(?=^## |\z)")
    if (-not $release.Success) {
        throw "CHANGELOG.md has no dated $Version section for the indexed Manager changelog."
    }

    $output = New-Object System.Collections.Generic.List[string]
    $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    $heading = $null
    $items = 0
    foreach ($line in @($release.Groups['Body'].Value -split '\r?\n' | Where-Object { $_.Trim() })) {
        if ($line -match '^### (.+)$') {
            if ($null -ne $heading -and $items -eq 0) {
                throw "CHANGELOG.md $Version has an empty ### $heading group."
            }
            $heading = $Matches[1].Trim()
            if (-not $heading -or -not $seen.Add($heading)) {
                throw "CHANGELOG.md $Version has an empty or duplicate ### group."
            }
            if ($output.Count -gt 0) { $output.Add('') }
            $output.Add("### $heading")
            $output.Add('')
            $items = 0
            continue
        }
        if ($line -notmatch '^\* \*\*TikTok:\*\* ') {
            throw "CHANGELOG.md $Version has text outside a TikTok-scoped ### group: $line"
        }
        if ($null -eq $heading) {
            throw "CHANGELOG.md $Version has a loose bullet before its first ### group."
        }
        $output.Add($line)
        $items++
    }
    if ($null -eq $heading) {
        throw "CHANGELOG.md $Version has no ### groups for the indexed Manager changelog."
    }
    if ($items -eq 0) {
        throw "CHANGELOG.md $Version has an empty ### $heading group."
    }
    return ($output -join "`n")
}
