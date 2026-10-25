<#
.SYNOPSIS
    Check public release facts against the generated patch list.

.DESCRIPTION
    The generated patches-list.json is the local source for the release version, target
    package, target version and patch count. This check makes README.md, patches-bundle.json
    and the recorded runtime and patch test counts agree before a release is published. It also checks
    the canonical Morphe add-source link in the README and verifies that its landing page is live. A guarded
    preparation mode lets the source commit reach GitHub while the public index still points
    at the previous working bundle. Published-asset verification remains strict.
#>
[CmdletBinding()]
param(
    [string]$Root,
    [switch]$VerifyPublishedAsset,
    # For a future immutable publication. Requires -VerifyPublishedAsset.
    [switch]$RequireImmutableRelease,
    # For a future signed publication. Requires -VerifyPublishedAsset.
    [switch]$RequireBundleSignature,
    [string]$Cosign,
    [string]$ArtifactPath,
    # The Morphe desktop CLI, the only thing that can read a patch list back out of a bundle
    # this checkout did not build. Falls back to HUSHFEED_DESKTOP_JAR.
    [string]$DesktopJar,
    # The JDK that runs it. Falls back to HUSHFEED_JAVA, then JAVA_HOME.
    [string]$Java,
    # Only for running the rest of the checks with no network. Nothing in the repo
    # passes it; the pre-push escape hatch is HUSHFEED_SKIP_PRE_PUSH=1.
    [switch]$SkipUrlCheck,
    # The repository description to hold to the facts instead of reading it through gh. Only the
    # script contract tests pass it, beside -SkipUrlCheck, so the description check (and the
    # lagging-index path that decides what it wants) runs without a network. Unset, -SkipUrlCheck
    # skips the description as before.
    [string]$DescriptionText,
    # The published bundle description quotes a test count, which is a fact about the release it
    # describes rather than about the working tree. The two agree at the moment the description
    # is written and drift apart with the next test anyone adds, so a push that only touches
    # README would fail on it for the rest of the release cycle. scripts/pre-push.ps1 passes this
    # when patches-bundle.json is not among the changed files; a release, which rewrites that
    # file, does not. With it, a checkout that has no test results at all (a fresh clone pushing
    # a README edit, which runs no tests) is not held to a run it had no reason to make; any
    # runtime results that are there are still checked for age, completeness, failures and skips.
    # The patch test results are read only without it: their fixture tests skip on any machine
    # with no HUSHFEED_FIXTURE_DIR, and a skip matters only to a count a description quotes. A
    # release and a run by hand check everything.
    [switch]$SkipDescriptionTestCount,
    # Leaves the test results unread. The pre-push hook passes it when it checks a pushed commit in
    # its gate worktree, whose build folders can hold another commit's results. Only with
    # -SkipDescriptionTestCount, since a description's counts are read off those results.
    [switch]$SkipTestResults,
    # Release source changes have to reach GitHub before their tag and bundle can be published.
    # During that preparation, patches-bundle.json still describes the working release. This
    # includes a newer source version and an unreleased catalog change held at the current version.
    # The pre-push gate uses this only when the index itself did not change. Asset verification is
    # refused until the index catches up.
    [switch]$AllowPublishedIndexLag,
    # The release provenance receipt. Defaults to release-receipt-<version>.json in the repo
    # root; checked when it is there, and required for a release.
    [string]$Receipt
)

$ErrorActionPreference = 'Stop'

if ($RequireImmutableRelease -and -not $VerifyPublishedAsset) {
    throw '-RequireImmutableRelease requires -VerifyPublishedAsset.'
}
if ($RequireBundleSignature -and -not $VerifyPublishedAsset) {
    throw '-RequireBundleSignature requires -VerifyPublishedAsset.'
}

# Not a parameter default. Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File, and any [CmdletBinding()] or
# [Parameter(...)] attribute makes a script advanced. PowerShell 7 does not do this, and
# the body reads $PSScriptRoot correctly in both.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }

. (Join-Path $PSScriptRoot 'Resolve-Java.ps1')
. (Join-Path $PSScriptRoot 'patch-target.ps1')
. (Join-Path $PSScriptRoot 'release-receipt.ps1')
. (Join-Path $PSScriptRoot 'common.ps1')

function Read-JsonFile {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required file is missing: $Path"
    }
    try {
        return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    } catch {
        throw "Could not read JSON from ${Path}: $($_.Exception.Message)"
    }
}

function Require-Match {
    param(
        [string]$Text,
        [string]$Pattern,
        [string]$Description
    )
    if ($Text -notmatch $Pattern) {
        throw "${Description} does not match the generated release facts."
    }
}

function Assert-UrlReachable {
    param(
        [Uri]$Uri,
        [string]$Description,
        [string]$FailureHint
    )
    # -SkipHttpErrorCheck is PowerShell 7 only, and the pre-push hook runs whichever shell it
    # found, so a 404 has to be read out of the thrown response instead. That is the answer this
    # check exists for: the index once named a tag that did not exist yet.
    $status = 0
    try {
        # -UseBasicParsing because Windows PowerShell otherwise hands the reply to the IE
        # parser, which throws a null reference on a HEAD with no body. PowerShell 7 accepts
        # the switch and ignores it.
        $response = Invoke-WebRequest -Uri $Uri -Method Head -MaximumRedirection 5 `
            -TimeoutSec 60 -UseBasicParsing
        $status = [int]$response.StatusCode
    } catch {
        $failed = $_.Exception.Response
        if ($failed -and $failed.StatusCode) {
            $status = [int]$failed.StatusCode
        } else {
            throw ("Could not reach the ${Description} ${Uri}: $($_.Exception.Message). " +
                'If the network is down, push with HUSHFEED_SKIP_PRE_PUSH=1 and run this again later.')
        }
    }
    if ($status -ne 200) {
        throw ("The ${Description} ${Uri} answered HTTP ${status}. " + $FailureHint)
    }
    Write-Host ("[release] ${Description} answers 200: " + $Uri)
}

$rootPath = (Resolve-Path -LiteralPath $Root).Path
$patchListPath = Join-Path $rootPath 'patches-list.json'
$bundlePath = Join-Path $rootPath 'patches-bundle.json'
$propertiesPath = Join-Path $rootPath 'gradle.properties'
$readmePath = Join-Path $rootPath 'README.md'

$patchList = Read-JsonFile $patchListPath
$bundle = Read-JsonFile $bundlePath
# Manager's MorpheAsset uses kotlinx.datetime.LocalDateTime. An Instant-style Z or
# offset makes JSON decoding fail before Manager can read the working download URL.
# Read the literal value: PowerShell 7 can otherwise turn it into a DateTime and
# conceal the suffix that caused the failure. Publish UTC clock time without a zone.
$createdAtMatches = [regex]::Matches(
    (Get-Content -LiteralPath $bundlePath -Raw), '(?<!\\)"created_at"\s*:\s*"([^"\\]*)"')
$createdAt = if ($createdAtMatches.Count -eq 1) { $createdAtMatches[0].Groups[1].Value } else { '' }
$parsedCreatedAt = [datetime]::MinValue
if (-not [datetime]::TryParseExact($createdAt, "yyyy-MM-dd'T'HH:mm:ss",
        [Globalization.CultureInfo]::InvariantCulture, [Globalization.DateTimeStyles]::None,
        [ref]$parsedCreatedAt)) {
    throw 'patches-bundle.json created_at must be a valid UTC clock time in yyyy-MM-ddTHH:mm:ss format, without Z or an offset, for Morphe Manager.'
}
$readme = Get-Content -LiteralPath $readmePath -Raw
$properties = Get-Content -LiteralPath $propertiesPath -Raw

$sourceVersion = [string]$patchList.version
if ($sourceVersion -notmatch '^v\d+\.\d+\.\d+$') {
    throw "patches-list.json has an invalid version: $sourceVersion"
}
$releaseVersion = $sourceVersion.Substring(1)
$propertyMatch = [regex]::Match($properties, '(?m)^\s*version\s*=\s*(\S+)\s*$')
if (-not $propertyMatch.Success -or $propertyMatch.Groups[1].Value -ne $releaseVersion) {
    throw "gradle.properties does not match $sourceVersion."
}

$patches = @($patchList.patches)
if ($patches.Count -eq 0) { throw 'patches-list.json contains no patches.' }
$patchCount = $patches.Count

$target = Get-PatchTarget -PatchList $patchList
$targetPackage = $target.PackageName
$targetVersion = $target.PackageVersion
$targetVersions = @($target.PackageVersions)
$targetVersionText = Format-VersionList -Versions $targetVersions

$bundleVersion = [string]$bundle.version
$bundleDescription = [string]$bundle.description
# The facts are the prose before the first Manager changelog group. A change bullet may
# legitimately name an older TikTok build, patch count or test count.
$bundleFacts = ($bundleDescription -split '(?m)^### ', 2)[0]
$publishedPatchMatch = [regex]::Match($bundleFacts, '\b(\d+) patches\b')
# "TikTok 47.1.4", or with more than one target "TikTok 47.1.4 and 47.2.3".
$publishedTargetMatch = [regex]::Match($bundleFacts,
    'TikTok\s+(\d+(?:\.\d+)+(?:(?:,\s*|,?\s+and\s+)\d+(?:\.\d+)+)*)')
# @() around the whole if: an if hands back one match as a plain string, and indexing that
# string reads its first character (the index named "TikTok 4" on 2026-09-26).
$publishedTargets = @(if ($publishedTargetMatch.Success) {
    [regex]::Matches($publishedTargetMatch.Groups[1].Value, '\d+(?:\.\d+)+') | ForEach-Object { $_.Value }
})
$publishedFactsDifferAtSameVersion = $AllowPublishedIndexLag -and
    $bundleVersion -eq $releaseVersion -and
    $publishedPatchMatch.Success -and $publishedTargetMatch.Success -and
    ([int]$publishedPatchMatch.Groups[1].Value -ne $patchCount -or
        (@($publishedTargets | Sort-Object) -join ',') -ne (@($targetVersions | Sort-Object) -join ','))
$indexLagsSource = $bundleVersion -ne $releaseVersion -or $publishedFactsDifferAtSameVersion
if ($indexLagsSource) {
    if (-not $AllowPublishedIndexLag) {
        throw "patches-bundle.json version does not match $sourceVersion."
    }
    if ($bundleVersion -notmatch '^\d+\.\d+\.\d+$') {
        throw "patches-bundle.json has an invalid published version: $bundleVersion"
    }
    if ($bundleVersion -ne $releaseVersion -and [version]$releaseVersion -le [version]$bundleVersion) {
        throw ("patches-bundle.json may lag only while a newer release is being prepared. " +
            "Source is $releaseVersion and the published index is $bundleVersion.")
    }
    if ($VerifyPublishedAsset) {
        throw 'A source artifact cannot be checked against the previous published index. Publish the new release and update patches-bundle.json first.'
    }
    if ($publishedFactsDifferAtSameVersion) {
        Write-Host ("[release] source $releaseVersion has an unreleased catalog while the working index " +
            "remains on its published facts")
    } else {
        Write-Host ("[release] source $releaseVersion is being prepared while the working index remains on $bundleVersion")
    }
}
$publishedVersion = if ($indexLagsSource) { $bundleVersion } else { $releaseVersion }
$bundleGroupMatch = [regex]::Match($bundleDescription, '(?m)^### ')
if ($bundleGroupMatch.Success) {
    $changelog = Get-Content -LiteralPath (Join-Path $rootPath 'CHANGELOG.md') -Raw
    $expectedGroups = Get-ManagerChangelogGroups -Text $changelog -Version $publishedVersion
    $indexedGroups = ($bundleDescription.Substring($bundleGroupMatch.Index) -replace "`r`n", "`n") `
        -replace '[\r\n]+\z', ''
    if ($indexedGroups -cne $expectedGroups) {
        throw "patches-bundle.json Manager changelog does not match CHANGELOG.md $publishedVersion."
    }
    Write-Host "[release] the indexed Manager changelog matches CHANGELOG.md $publishedVersion"
}
Require-Match -Text ([string]$bundle.download_url) -Pattern "/v$([regex]::Escape($publishedVersion))/patches-$([regex]::Escape($publishedVersion))\.mpp$" -Description 'patches-bundle.json download URL'

# Matching the pattern only proves the index spells the version right. Reaching the address is
# what catches an index pointed at a tag nobody published, which is how the bundle went missing
# once already, and the hash comparison further down runs only when this checkout built a bundle.
# So the asset URL and the README's Morphe landing page are fetched on every run, not only on a release.
$assetUri = [Uri]$bundle.download_url
if ($assetUri.Scheme -ne 'https') {
    throw "The published bundle URL must use HTTPS: $($bundle.download_url)"
}
$assetName = [IO.Path]::GetFileName($assetUri.AbsolutePath)
if ($assetName -ne "patches-$publishedVersion.mpp") {
    throw "The published bundle URL names $assetName instead of patches-$publishedVersion.mpp."
}
if ($assetUri.Host -ne 'github.com') {
    throw "The indexed bundle URL must be on github.com: $assetUri"
}
$segments = @($assetUri.AbsolutePath.Trim('/') -split '/')
if ($segments.Count -lt 2) {
    throw "Could not read an owner and repository out of the indexed bundle URL: $assetUri"
}
$slug = $segments[0] + '/' + $segments[1]
$encodedSlug = [Uri]::EscapeDataString($slug)
$addSourceUrl = "https://morphe.software/add-source?github=$encodedSlug"
Require-Match -Text $readme -Pattern ([regex]::Escape($addSourceUrl)) -Description 'README Morphe add-source link'
if ($SkipUrlCheck) {
    Write-Host '[release] the indexed URL and Morphe add-source page were not fetched because -SkipUrlCheck was given'
} else {
    Assert-UrlReachable -Uri $assetUri -Description 'indexed bundle URL' `
        -FailureHint 'The Manager fetches that address, so the release it names has to exist first.'
    Assert-UrlReachable -Uri ([Uri]$addSourceUrl) -Description 'Morphe add-source page' `
        -FailureHint 'The README sends Android users through that page, so it must be available before release.'
}
Require-Match -Text $readme -Pattern "\b$patchCount patches\b" -Description 'README patch count'
Require-Match -Text $readme -Pattern ([regex]::Escape($targetPackage)) -Description 'README package name'
Require-Match -Text $readme -Pattern "TikTok\s+$([regex]::Escape($targetVersions[0]))(?!\d|\.\d)" -Description 'README target version'
foreach ($version in $targetVersions) {
    Require-Match -Text $readme -Pattern "(?<![\d.])$([regex]::Escape($version))(?!\d|\.\d)" -Description "README target version $version"
}
$descriptionVersion = $sourceVersion
$descriptionPatchCount = $patchCount
$descriptionTargetVersions = @($targetVersions)
if ($indexLagsSource) {
    if (-not $publishedPatchMatch.Success -or -not $publishedTargetMatch.Success) {
        throw 'The published bundle description does not name its patch count and TikTok target.'
    }
    Require-Match -Text $bundleFacts -Pattern "\bv$([regex]::Escape($publishedVersion))\b" -Description 'published bundle description version'
    $descriptionVersion = "v$publishedVersion"
    $descriptionPatchCount = [int]$publishedPatchMatch.Groups[1].Value
    $descriptionTargetVersions = @($publishedTargets)
} else {
    Require-Match -Text $bundleFacts -Pattern "\b$patchCount patches\b" -Description 'bundle description patch count'
    foreach ($version in $targetVersions) {
        Require-Match -Text $bundleFacts -Pattern "(?<![\d.])$([regex]::Escape($version))(?!\d|\.\d)" -Description "bundle description target version $version"
    }
}
$descriptionTargetText = Format-VersionList -Versions $descriptionTargetVersions

# concepts/patch-development.md states the current version and patch counts in prose, and it went
# a release stale (0.70.0 and 131 entries over a 0.71.x checkout) with nothing reading it. The
# source line is held to gradle.properties and patches-list.json. The published line is held to
# the index, which may lag the source, and to the release it names. The category table has to add
# up to the catalog it describes.
$guidePath = Join-Path $rootPath 'concepts/patch-development.md'
if (-not (Test-Path -LiteralPath $guidePath -PathType Leaf)) {
    throw 'concepts/patch-development.md is missing, so its version and patch counts cannot be checked.'
}
$guide = Get-Content -LiteralPath $guidePath -Raw
$guideSource = [regex]::Match($guide, '(?m)^Source snapshot: version (\d+\.\d+\.\d+), (\d+) patch entries\.')
if (-not $guideSource.Success) {
    throw 'concepts/patch-development.md has no "Source snapshot: version X.Y.Z, N patch entries." line.'
}
if ($guideSource.Groups[1].Value -ne $releaseVersion -or [int]$guideSource.Groups[2].Value -ne $patchCount) {
    throw ("concepts/patch-development.md says source $($guideSource.Groups[1].Value) with " +
        "$($guideSource.Groups[2].Value) patch entries, but gradle.properties and patches-list.json say " +
        "$releaseVersion with $patchCount. Update its Source snapshot line.")
}
$guidePublished = [regex]::Match($guide, '(?m)^Published bundle: version (\d+\.\d+\.\d+), (\d+) patches\.')
if (-not $guidePublished.Success) {
    throw 'concepts/patch-development.md has no "Published bundle: version X.Y.Z, N patches." line.'
}
if ($guidePublished.Groups[1].Value -ne $publishedVersion -or [int]$guidePublished.Groups[2].Value -ne $descriptionPatchCount) {
    throw ("concepts/patch-development.md says the published bundle is $($guidePublished.Groups[1].Value) with " +
        "$($guidePublished.Groups[2].Value) patches, but patches-bundle.json says $publishedVersion with " +
        "$descriptionPatchCount. Update its Published bundle line.")
}
$guideCatalog = [regex]::Match($guide,
    '(?s)catalog''s (\d+) patches are grouped as follows:\s*\r?\n\s*\r?\n\| Manager category[^\r\n]*\r?\n[^\r\n]*\r?\n((?:\|[^\r\n]*(?:\r?\n|$))+)')
if (-not $guideCatalog.Success) {
    throw 'concepts/patch-development.md has no "catalog''s N patches are grouped as follows" category table.'
}
if ([int]$guideCatalog.Groups[1].Value -ne $patchCount) {
    throw ("concepts/patch-development.md groups $($guideCatalog.Groups[1].Value) patches, " +
        "but patches-list.json has $patchCount.")
}
$guideCategories = @{}
foreach ($row in [regex]::Matches($guideCatalog.Groups[2].Value, '(?m)^\|\s*([^|]+?)\s*\|\s*(\d+)\s*\|')) {
    $guideCategories[$row.Groups[1].Value] = [int]$row.Groups[2].Value
}
$catalogCategories = @{}
foreach ($patch in $patches) { $catalogCategories[[string]$patch.category] = 1 + [int]$catalogCategories[[string]$patch.category] }
$categoryDrift = @(($guideCategories.Keys + $catalogCategories.Keys) | Sort-Object -Unique | Where-Object {
    [int]$guideCategories[$_] -ne [int]$catalogCategories[$_]
} | ForEach-Object { "$_ is $([int]$guideCategories[$_]) there and $([int]$catalogCategories[$_]) in patches-list.json" })
if ($categoryDrift.Count -gt 0) {
    throw ('concepts/patch-development.md has a stale category table: ' + ($categoryDrift -join '; ') + '.')
}

# The one line GitHub shows above the README, which is also what search results, the awesome
# lists and the Manager's community button repeat. Nothing here read it until now, and it had
# gone two releases and two patches stale before anyone noticed. The repository it reads is the
# one the index points at, so this cannot drift onto some other fork.
if ($SkipUrlCheck -and -not $DescriptionText) {
    Write-Host '[release] the repository description was not read because -SkipUrlCheck was given'
} else {
    if ($DescriptionText) {
        $description = $DescriptionText.Trim()
    } else {
        if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
            throw ('The gh CLI is needed to read the repository description of ' + $slug +
                '. Install it, or pass -SkipUrlCheck to run the rest with no network.')
        }
        # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $description = (& gh api "repos/$slug" --jq '.description' 2>$null)
        } finally {
            $ErrorActionPreference = $preference
        }
        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($description)) {
            throw ("Could not read the description of $slug through gh. Run gh auth login, or pass " +
                '-SkipUrlCheck to run the rest with no network.')
        }
        $description = $description.Trim()
    }

    # A description may leave the release facts out (the Hush repos' descriptions dropped them on
    # 2026-10-14), so only a fact it states is held to the release: a version, a patch count or a
    # TikTok version that is there has to be the current one.
    $statesTarget = $description -match 'TikTok\s+\d+\.\d+'
    $wanted = @(
        @{ States = $description -match '\bv\d+\.\d+\.\d+\b'; Pattern = "\b$([regex]::Escape($descriptionVersion))\b"; Wanted = $descriptionVersion }
        @{ States = $description -match '\b\d+ patches\b'; Pattern = "\b$descriptionPatchCount patches\b"; Wanted = "$descriptionPatchCount patches" }
        @{ States = $statesTarget; Pattern = "TikTok\s+$([regex]::Escape($descriptionTargetVersions[0]))(?!\d|\.\d)"; Wanted = "TikTok $($descriptionTargetVersions[0])" }
    )
    $wanted += @($descriptionTargetVersions | Select-Object -Skip 1 | ForEach-Object {
        @{ States = $statesTarget; Pattern = "(?<![\d.])$([regex]::Escape($_))(?!\d|\.\d)"; Wanted = $_ }
    })
    $missing = @($wanted | Where-Object { $_.States -and $description -notmatch $_.Pattern } | ForEach-Object { $_.Wanted })
    if ($missing.Count -gt 0) {
        throw ("The GitHub description of $slug does not say " + ($missing -join ', ') + '. It reads: ' +
            $description + [Environment]::NewLine +
            'Set it with: gh repo edit ' + $slug + ' --description "Hushfeed ' + $descriptionVersion +
            ': ... ' + $descriptionPatchCount + ' patches for TikTok ' + $descriptionTargetText + '."')
    }
    if (@($wanted | Where-Object { $_.States }).Count -eq 0) {
        Write-Host ("[release] the GitHub description of " + $slug + " names no version, patch count or TikTok version, so nothing in it can go stale")
    } else {
        Write-Host ("[release] the GitHub description of " + $slug + " states only current facts (" + $descriptionVersion +
            ", " + $descriptionPatchCount + " patches, TikTok " + $descriptionTargetText + ")")
    }
}

$testRoot = Join-Path $rootPath 'extensions/tiktok/build/test-results/testDebugUnitTest'
if ($SkipTestResults -and -not $SkipDescriptionTestCount) {
    throw '-SkipTestResults leaves nothing to hold the description test counts to. Pass -SkipDescriptionTestCount with it.'
}
$testFiles = @(if (-not $SkipTestResults) {
    Get-ChildItem -LiteralPath $testRoot -Filter '*.xml' -File -ErrorAction SilentlyContinue
})
if ($testFiles.Count -eq 0) {
    if (-not $SkipDescriptionTestCount) {
        throw "No runtime test results found under $testRoot. Run :extensions:tiktok:test first."
    }
    if ($SkipTestResults) {
        Write-Host '[release] the test results here were left unread, since they can belong to another commit'
    } else {
        Write-Host ('[release] no runtime test results here, and this push rewrites no release ' +
            'description, so there is no run to check')
    }
}

# Gradle leaves the previous run's XML in place, so results from before the last edit satisfy
# every check below and a release can be validated against code that was never tested.
#
# Only the trees the test task actually reads are compared. patches/src is not one of them:
# :extensions:tiktok:test depends on the shared library and the stub, so editing a patch leaves
# the task up to date, no XML is rewritten, and comparing against it would refuse every release
# from then on with no rerun that could clear it.
#
# The newest result is the one to compare: it is the last evidence of a run, and an older one
# could predate the latest source edit. Orphaned results from deleted or renamed test classes
# are caught separately below.
$sourceRoots = @('extensions/tiktok/src', 'extensions/tiktok/stub/src', 'extensions/shared/library/src') |
    ForEach-Object { Join-Path $rootPath $_ } |
    Where-Object { Test-Path -LiteralPath $_ }
$newestSource = $sourceRoots |
    ForEach-Object { Get-ChildItem -LiteralPath $_ -Recurse -File -ErrorAction SilentlyContinue } |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1
if ($null -ne $newestSource -and $testFiles.Count -gt 0) {
    $newestResult = $testFiles | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
    if ($newestResult.LastWriteTimeUtc -lt $newestSource.LastWriteTimeUtc) {
        throw ("Runtime test results are older than the sources. The newest result " +
            "$($newestResult.Name) was written $($newestResult.LastWriteTimeUtc.ToString('u')) but " +
            "$($newestSource.FullName) changed $($newestSource.LastWriteTimeUtc.ToString('u')). " +
            'Run :extensions:tiktok:testDebugUnitTest --rerun. A checkout that only moves a file''s ' +
            'date leaves Gradle calling the tests up to date, and naming :extensions:tiktok:test reruns ' +
            'only that umbrella task.')
    }
}
# Gradle clears the results directory on every run and writes only the classes that ran, so a
# filtered run such as --tests *SomeTest leaves that one XML and nothing else. Every count below
# then describes part of a run: on 2026-09-08 this summed 83 tests from six files while the index
# said 682. The failure named the index rather than the filtered run, and anyone reconciling the
# index to match would have written down a number no full run ever produced.
$testSourceRoot = Join-Path $rootPath 'extensions/tiktok/src/test'
if ($testFiles.Count -gt 0 -and (Test-Path -LiteralPath $testSourceRoot)) {
    $sourceClasses = @(Get-ChildItem -LiteralPath $testSourceRoot -Recurse -File -Filter '*Test.java' |
        ForEach-Object { $_.BaseName })
    # TEST-<package>.<Class>.xml, and the package is not needed to tell one class from another.
    $ranClasses = @($testFiles | ForEach-Object { ($_.BaseName -replace '^TEST-', '') -replace '^.*\.', '' })
    $missing = @($sourceClasses | Where-Object { $ranClasses -notcontains $_ } | Sort-Object)
    if ($missing.Count -gt 0) {
        throw ("Runtime test results are missing " + $missing.Count + " of " + $sourceClasses.Count +
            " test classes, so the counts here describe part of a run: " +
            (($missing | Select-Object -First 8) -join ', ') +
            ". Run :extensions:tiktok:test unfiltered.")
    }
    # The other way round: a class deleted or renamed since the last run leaves its results until
    # the tests run again, and nothing above notices. No remaining source is newer than the
    # results, and every class that is left has one, so its tests would be counted as passing.
    $orphaned = @($ranClasses | Where-Object { $sourceClasses -notcontains $_ } | Sort-Object)
    if ($orphaned.Count -gt 0) {
        throw ("Runtime test results include " + $orphaned.Count + " test class(es) with no source " +
            "any more, left from a run before they were deleted or renamed: " +
            (($orphaned | Select-Object -First 8) -join ', ') +
            ". Run :extensions:tiktok:testDebugUnitTest --rerun.")
    }
}

$testCount = 0
foreach ($file in $testFiles) {
    try {
        $results = [xml](Get-Content -LiteralPath $file.FullName -Raw)
    } catch {
        throw "Could not read test results from $($file.FullName): $($_.Exception.Message)"
    }
    foreach ($suite in @($results.testsuite)) {
        $failed = [int]$suite.failures
        $errors = [int]$suite.errors
        $skipped = [int]$suite.skipped
        if ($failed -gt 0 -or $errors -gt 0 -or $skipped -gt 0) {
            throw "Runtime test suite $($file.Name) has failures=$failed, errors=$errors, skipped=$skipped."
        }
    }
    $testCount += @($results.testsuite.testcase).Count
}
$testFacts = if ($testFiles.Count -gt 0) { "$testCount runtime tests" } else { 'no runtime test results here' }
if ($SkipDescriptionTestCount) {
    $ranHere = if ($testFiles.Count -gt 0) { "; $testCount tests ran here" } else { '' }
    Write-Host ("[release] patches-bundle.json did not change, so its description is left " +
        "against the release it describes" + $ranHere)
} else {
    Require-Match -Text $bundleFacts -Pattern "\b$testCount runtime tests passed\b" -Description 'bundle description test count'
}

# The patch module's tests, which the description quotes as "All N patch tests passed". Until
# 2026-09-21 nothing read that number; it was typed by hand. Their fixture tests skip when
# HUSHFEED_FIXTURE_DIR is unset, and Gradle counts a skip as a pass, so a release could quote a
# run that never opened a TikTok APK. Only a check that holds the description to its counts reads
# them: they are a fact about the release, and a push that rewrites no description has no count
# to compare them with and no reason to have run them.
if (-not $SkipDescriptionTestCount) {
    $patchTestFiles = @()
    # Each partition writes a content receipt beside its XML. A timestamp alone rejects a
    # byte-identical rewrite even when Gradle correctly reuses the earlier result.
    foreach ($partition in @('test', 'nativeTest', 'documentationTest')) {
        $patchTestRoot = Join-Path $rootPath "patches/build/test-results/$partition"
        $partitionFiles = @(Get-ChildItem -LiteralPath $patchTestRoot -Filter '*.xml' -File -ErrorAction SilentlyContinue)
        if ($partitionFiles.Count -eq 0) {
            throw ("No patch test results found under $patchTestRoot. Run :patches:test with " +
                'HUSHFEED_FIXTURE_DIR set first.')
        }
        $inputPaths = @('patches/src', 'patches-list.json')
        if ($partition -eq 'documentationTest') {
            $inputPaths += @('README.md', 'patches-bundle.json', 'assets/readme-hero.png',
                'patches-bundle.png', 'concepts/marketing/2026-09-12')
        } elseif ($partition -eq 'test') {
            # nativeTest leaves the extensions out, so a runtime-only edit reuses its results.
            $inputPaths += 'extensions'
        }
        $currentInputs = @($inputPaths | ForEach-Object { Join-Path $rootPath $_ } |
            Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
                if (Test-Path -LiteralPath $_ -PathType Container) { Get-ChildItem -LiteralPath $_ -Recurse -File }
                else { Get-Item -LiteralPath $_ }
            } | ForEach-Object {
                $relative = $_.FullName.Substring($rootPath.TrimEnd('\', '/').Length).TrimStart('\', '/').Replace('\', '/')
                if ($relative.StartsWith('extensions/') -and
                    ($relative -notmatch '/src/' -or $relative -match '/build/')) { return }
                '{0} {1}' -f (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $relative
            })
        $inputReceipt = Join-Path $patchTestRoot 'source-inputs.sha256'
        if (-not (Test-Path -LiteralPath $inputReceipt -PathType Leaf)) {
            throw "No patch input receipt found for $partition. Run :patches:test."
        }
        $testedInputs = @(Get-Content -LiteralPath $inputReceipt -Encoding UTF8 | Where-Object { $_ })
        if ($testedInputs.Count -ne $currentInputs.Count -or
            @(Compare-Object -ReferenceObject $currentInputs -DifferenceObject $testedInputs -CaseSensitive).Count -gt 0) {
            throw "Patch test inputs changed in $partition. Run :patches:test."
        }
        $patchTestFiles += $partitionFiles
    }
    $duplicates = @($patchTestFiles | Group-Object BaseName | Where-Object Count -gt 1)
    if ($duplicates.Count -gt 0) {
        throw ("Duplicate patch test results across partitions: " + (($duplicates | ForEach-Object Name) -join ', '))
    }
    $patchTestSourceRoot = Join-Path $rootPath 'patches/src/test'
    if (Test-Path -LiteralPath $patchTestSourceRoot) {
        $patchClasses = @(Get-ChildItem -LiteralPath $patchTestSourceRoot -Recurse -File -Filter '*Test.kt' |
            ForEach-Object { $_.BaseName })
        $ranPatchClasses = @($patchTestFiles | ForEach-Object { ($_.BaseName -replace '^TEST-', '') -replace '^.*\.', '' })
        $missing = @($patchClasses | Where-Object { $ranPatchClasses -notcontains $_ } | Sort-Object)
        if ($missing.Count -gt 0) {
            throw ("Patch test results are missing " + $missing.Count + " of " + $patchClasses.Count +
                " test classes, so the counts here describe part of a run: " +
                (($missing | Select-Object -First 8) -join ', ') + ". Run :patches:test unfiltered.")
        }
        $orphaned = @($ranPatchClasses | Where-Object { $patchClasses -notcontains $_ } | Sort-Object)
        if ($orphaned.Count -gt 0) {
            throw ("Patch test results include " + $orphaned.Count + " test class(es) with no source " +
                "any more, left from a run before they were deleted or renamed: " +
                (($orphaned | Select-Object -First 8) -join ', ') + ". Run :patches:test --rerun.")
        }
    }
    $patchTestCount = 0
    foreach ($file in $patchTestFiles) {
        try {
            $results = [xml](Get-Content -LiteralPath $file.FullName -Raw)
        } catch {
            throw "Could not read test results from $($file.FullName): $($_.Exception.Message)"
        }
        foreach ($suite in @($results.testsuite)) {
            $failed = [int]$suite.failures
            $errors = [int]$suite.errors
            $skipped = [int]$suite.skipped
            if ($skipped -gt 0) {
                throw ("Patch test suite $($file.Name) skipped $skipped test(s). A fixture test skips " +
                    'when HUSHFEED_FIXTURE_DIR is not set, and a release quotes only a run that read the fixtures.')
            }
            if ($failed -gt 0 -or $errors -gt 0) {
                throw "Patch test suite $($file.Name) has failures=$failed, errors=$errors."
            }
        }
        $patchTestCount += @($results.testsuite.testcase).Count
    }
    Require-Match -Text $bundleFacts -Pattern "\b$patchTestCount patch tests passed\b" `
        -Description 'bundle description patch test count'
    $testFacts += ", $patchTestCount patch tests"
}

if ($VerifyPublishedAsset) {
    if ([string]::IsNullOrWhiteSpace($ArtifactPath)) {
        $ArtifactPath = Get-ReleaseBundlePath -Root $rootPath -Version $releaseVersion
    }
    if (-not (Test-Path -LiteralPath $ArtifactPath -PathType Leaf)) {
        throw "The local release artifact is missing: $ArtifactPath"
    }

    $temporaryArtifact = Join-Path ([IO.Path]::GetTempPath()) ("hushfeed-$([Guid]::NewGuid()).mpp")
    try {
        try {
            $assetResponse = Invoke-WebRequest -Uri $assetUri -OutFile $temporaryArtifact -MaximumRedirection 5 -TimeoutSec 60 -PassThru
        } catch {
            throw "Could not download the indexed bundle URL: $($_.Exception.Message)"
        }
        if ($assetResponse.StatusCode -ne 200) {
            throw "The indexed bundle URL returned HTTP $($assetResponse.StatusCode)."
        }

        # Every other check here reads patches-list.json, which describes the bundle this
        # checkout just built. That moves the moment a patch is added; the file people download
        # does not. The index once advertised 70 patches while the published asset carried 68 and
        # nothing complained, because nothing had read the published asset. This does.
        $localHash = (Get-FileHash -LiteralPath $ArtifactPath -Algorithm SHA256).Hash.ToLowerInvariant()
        $publishedHash = (Get-FileHash -LiteralPath $temporaryArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($localHash -ne $publishedHash) {
            throw "The hosted bundle hash $publishedHash does not match the local artifact hash $localHash."
        }

        $checksumUri = [Uri]::new($assetUri, 'SHA256SUMS.txt')
        try {
            $checksumResponse = Invoke-WebRequest -Uri $checksumUri -MaximumRedirection 5 -TimeoutSec 60
        } catch {
            throw "Could not download the hosted SHA256SUMS.txt: $($_.Exception.Message)"
        }
        if ($checksumResponse.StatusCode -ne 200) {
            throw "The hosted SHA256SUMS.txt returned HTTP $($checksumResponse.StatusCode)."
        }
        $checksumText = [Text.Encoding]::UTF8.GetString([byte[]]$checksumResponse.Content)
        $checksumMatch = [regex]::Match(
            $checksumText,
            "(?im)^\s*([0-9a-f]{64})\s+\*?$([regex]::Escape($assetName))\s*$"
        )
        if (-not $checksumMatch.Success) {
            throw "SHA256SUMS.txt has no entry for $assetName."
        }
        $listedHash = $checksumMatch.Groups[1].Value.ToLowerInvariant()
        if ($listedHash -ne $publishedHash) {
            throw "SHA256SUMS.txt lists $listedHash for $assetName, but the hosted artifact is $publishedHash."
        }
        $signatureUrl = [string]$bundle.signature_download_url
        if ([string]::IsNullOrWhiteSpace($signatureUrl)) {
            if ($RequireBundleSignature) { throw 'The indexed release does not advertise a bundle signature.' }
            Write-Host '[release] signature verification skipped: the indexed release has no signature.'
        } else {
            $expectedSignatureUri = [Uri]::new($assetUri, ($assetName -replace '\.mpp$', '.sigstore.json'))
            if ($signatureUrl -cne $expectedSignatureUri.AbsoluteUri) {
                throw "The signature URL must name $expectedSignatureUri beside the indexed bundle."
            }
            $signaturePath = "$temporaryArtifact.sigstore.json"
            try {
                $signatureResponse = Invoke-WebRequest -Uri $expectedSignatureUri -OutFile $signaturePath `
                    -MaximumRedirection 5 -TimeoutSec 60 -PassThru
                if ($signatureResponse.StatusCode -ne 200) { throw "Signature download returned HTTP $($signatureResponse.StatusCode)." }
                . (Join-Path $PSScriptRoot 'release-signature.ps1')
                $null = Test-CosignBlobSignature -ArtifactPath $temporaryArtifact -SignaturePath $signaturePath `
                    -PublicKeyPath (Join-Path $rootPath 'cosign.pub') -Cosign $Cosign
            } finally {
                if (Test-Path -LiteralPath $signaturePath) { Remove-Item -LiteralPath $signaturePath -Force }
            }
        }
        # A matching hash proves the published file is the one this checkout built. It does not
        # prove either of them is what the released commit builds, and on v0.28.0 the two came
        # apart: the bundle was built while HEAD was still two commits back, was published, and
        # then the release commit was made. The hashes agreed at the time and the README's offer
        # to rebuild the bundle and compare checksums was false for the rest of the release.
        #
        # A release is built from the source commit, then its public index is updated in a later
        # commit. HEAD is therefore the wrong comparison during that index push. Resolve the
        # published version's remote tag and hold the local artifact to that commit instead.
        # Held together with the hash comparison above, this gives the whole claim: the hosted
        # bundle is byte-for-byte local and reproducible from the release tag.
        #
        # SOURCE_DATE_EPOCH is deliberately not consulted. It is the same variable the build
        # reads, so accepting it as the expected value would compare the builder's own input
        # against itself and agree whichever commit the bundle came from.
        $releaseTagRef = "refs/tags/v$publishedVersion"
        $peeledTagRef = "$releaseTagRef^{}"
        $remoteUrl = "https://github.com/$slug.git"
        # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $remoteTags = @(& git ls-remote $remoteUrl $releaseTagRef $peeledTagRef 2>$null)
            $remoteStatus = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $preference
        }
        if ($remoteStatus -ne 0) {
            throw "Could not read v$publishedVersion from $remoteUrl."
        }
        $tagLine = $remoteTags |
            Where-Object { $_ -match "\s+$([regex]::Escape($peeledTagRef))$" } |
            Select-Object -First 1
        if (-not $tagLine) {
            $tagLine = $remoteTags |
                Where-Object { $_ -match "\s+$([regex]::Escape($releaseTagRef))$" } |
                Select-Object -First 1
        }
        $tagCommitMatch = [regex]::Match([string]$tagLine, '^([0-9a-fA-F]{40,64})\s+')
        if (-not $tagCommitMatch.Success) {
            throw "The published release tag v$publishedVersion does not exist on $remoteUrl."
        }
        $releaseCommit = $tagCommitMatch.Groups[1].Value.ToLowerInvariant()
        $releaseEpoch = (Invoke-RepoGit -Root $rootPath -Arguments @('log', '-1', '--format=%ct', $releaseCommit) |
            Select-Object -First 1)
        $releaseEpoch = "$releaseEpoch".Trim()
        if ($releaseEpoch -notmatch '^\d+$') {
            throw "Could not read tagged release commit $releaseCommit in this checkout."
        }
        $expectedStamp = [long]$releaseEpoch * 1000
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $localStamp = $null
        $zip = [System.IO.Compression.ZipFile]::OpenRead($ArtifactPath)
        try {
            $manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
            if ($null -eq $manifestEntry) { throw "The local $assetName has no META-INF/MANIFEST.MF." }
            $reader = New-Object IO.StreamReader($manifestEntry.Open())
            try { $manifestText = $reader.ReadToEnd() } finally { $reader.Dispose() }
            $stampMatch = [regex]::Match($manifestText, '(?m)^Timestamp: (\d+)')
            if (-not $stampMatch.Success) { throw "The local $assetName manifest has no Timestamp line." }
            $localStamp = [long]$stampMatch.Groups[1].Value
        } finally { $zip.Dispose() }
        if ($localStamp -ne $expectedStamp) {
            throw ("The bundle at $ArtifactPath is pinned to $localStamp but release tag " +
                "v$publishedVersion ($releaseCommit) is $expectedStamp. Build the bundle from " +
                'the tagged commit so rebuilding from the tag reproduces the published hash.')
        }
        $publishedStamp = $localStamp
        Write-Host ("[release] published bundle is pinned to v$publishedVersion ($releaseCommit); timestamp=" +
            $publishedStamp)
        Write-Host ("[release] verified " + $assetName + " from the indexed URL; sha256=" + $publishedHash)
        . (Join-Path $PSScriptRoot 'release-attestation.ps1')
        $null = Test-ReleaseAttestation -Repository $slug -Tag "v$publishedVersion" `
            -ArtifactPath $ArtifactPath -AssetName $assetName -ExpectedCommit $releaseCommit `
            -RequireImmutableRelease:$RequireImmutableRelease
        # No caller passed -DesktopJar and nothing in the repo set the variable, so this check
        # printed "NOT COUNTED" and passed on every run it has ever had. A switch named
        # -VerifyPublishedAsset that quietly skips the only check reading the published asset is
        # worse than no switch: look for the CLI, and say so plainly when there is none.
        #
        # Last of the three on purpose. It is the only one needing a tool from outside the
        # repository, so running it first meant a machine without that tool also lost the hash
        # and checksum comparisons, which need nothing but the download.
        # Morphe Manager loads patches from the bundle's classes.dex (loadPatchesFromDex), not from
        # the .class files the desktop CLI and verifyBundle read. A bundle whose classes.dex was
        # dropped, which a stray :patches:jar run after buildAndroid does, still lists every patch
        # to those JVM readers while Manager shows zero. v0.43.0 shipped exactly that. So the
        # published asset is opened here and its classes.dex is required, non-empty, the one thing
        # that reproduces what Manager sees.
        Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue
        $assetZip = [System.IO.Compression.ZipFile]::OpenRead($temporaryArtifact)
        try {
            $classesDex = $assetZip.GetEntry('classes.dex')
            if ($null -eq $classesDex) {
                throw ('The published bundle has no classes.dex, so Morphe Manager will load zero ' +
                    'patches from it. It was built without its patch dex (a :patches:jar run after ' +
                    ':patches:buildAndroid strips it). Rebuild with :patches:buildAndroid last and ' +
                    'republish.')
            }
            if ($classesDex.Length -le 0) {
                throw 'The published bundle carries an empty classes.dex, so Morphe Manager loads zero patches.'
            }
            Write-Host ("[release] the published bundle carries classes.dex (" +
                "$($classesDex.Length) bytes), which is what Morphe Manager loads")
        } finally {
            $assetZip.Dispose()
        }

        $countJar = Resolve-DesktopCli -Explicit $DesktopJar -Root $Root
        if (-not $countJar) {
            throw ('The published bundle was downloaded but its patches cannot be counted: no ' +
                'Morphe desktop CLI was found. Pass -DesktopJar, set HUSHFEED_DESKTOP_JAR, or put ' +
                'morphe-desktop*.jar under HUSHFEED_WORKDIR or build/morphe-tools, so the ' +
                $patchCount + ' patches the index describes can be compared against the asset.')
        } elseif (-not (Test-Path -LiteralPath $countJar -PathType Leaf)) {
            throw "The Morphe desktop CLI is missing: $countJar"
        } else {
            $javaCommand = Resolve-Java -Explicit $Java
            $listing = Join-Path ([IO.Path]::GetTempPath()) ("hushfeed-$([Guid]::NewGuid()).txt")
            try {
                # --out keeps the list clear of the CLI's own log lines, which share stdout.
                $global:LASTEXITCODE = 0
                # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
                $preference = $ErrorActionPreference
                $ErrorActionPreference = 'Continue'
                try {
                    $cliOutput = & $javaCommand '-jar' $countJar 'list-patches' "--patches=$temporaryArtifact" `
                        '-d=false' '-i=false' "--out=$listing" 2>&1
                } finally {
                    $ErrorActionPreference = $preference
                }
                if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $listing -PathType Leaf)) {
                    throw ("Could not list the patches in the published bundle: " +
                        ($cliOutput -join ' '))
                }
                $publishedCount = @(Get-Content -LiteralPath $listing |
                    Where-Object { $_ -match '^Name:\s*\S' }).Count
                if ($publishedCount -eq 0) {
                    throw 'Read no patch names out of the published bundle, so its count could not be compared.'
                }
                if ($publishedCount -ne $patchCount) {
                    throw ("The published bundle carries $publishedCount patches but the index " +
                        "describes $patchCount. Publish the built bundle before the index advertises " +
                        'what the release does not serve.')
                }
                Write-Host "[release] the published bundle carries $publishedCount patches, as described"
            } finally {
                Remove-Item -LiteralPath $listing -Force -ErrorAction SilentlyContinue
            }
        }

    } finally {
        Remove-Item -LiteralPath $temporaryArtifact -Force -ErrorAction SilentlyContinue
    }
}

# Manager refuses a bundle whose Patcher-Version is newer than its own patcher, and the README
# names the Manager release that first shipped the pinned one. The manifest is written by the
# Gradle plugin from the resolved patcher, so a catalog pin that drifts from it means the README
# names the wrong floor and the bundle is refused by a Manager the README says is new enough.
$catalogPath = Join-Path $rootPath 'gradle/libs.versions.toml'
if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
    throw "The version catalog is missing: $catalogPath"
}
$catalogText = Get-Content -LiteralPath $catalogPath -Raw
$workingToolchain = Read-CatalogToolchain -Text $catalogText -Source 'gradle/libs.versions.toml'
$pinnedPatcher = $workingToolchain.PatcherVersion
$managerFloor = $workingToolchain.ManagerFloor
$managerFloorPattern = "\bMorphe Manager\s+$([regex]::Escape($managerFloor))\s+or newer\b"
Require-Match -Text $readme -Pattern $managerFloorPattern -Description 'README Manager floor'
Write-Host "[release] README requires Morphe Manager $managerFloor or newer for patcher $pinnedPatcher"

# The bug form's placeholders are what a reporter copies when unsure what to write, and they had
# drifted a long way: TikTok 46.2.3, Manager 1.29.0 and Hushfeed 0.29.0 while the bundle targeted
# 47.0.3. The version line reads the way Hushfeed's settings card does, with the version the index
# publishes, and the manager line names the Manager floor.
$bugFormPath = Join-Path $rootPath '.github/ISSUE_TEMPLATE/bug_report.yml'
if (-not (Test-Path -LiteralPath $bugFormPath -PathType Leaf)) {
    throw "The bug report form is missing: $bugFormPath"
}
$bugForm = Get-Content -LiteralPath $bugFormPath -Raw
# While the index lags, the form names the build the published bundle targets, not the source's.
$bugFormTarget = if ($indexLagsSource -and $publishedTargets.Count -gt 0) {
    @($publishedTargets | Sort-Object { [version]$_ })[-1]
} else { $targetVersion }
$bugFormVersions = "Version $publishedVersion for TikTok $bugFormTarget"
Require-Match -Text $bugForm -Pattern "(?m)^\s*placeholder:\s*$([regex]::Escape($bugFormVersions))\s*$" `
    -Description 'bug report form version placeholder'
Require-Match -Text $bugForm -Pattern "(?m)^\s*placeholder:\s*Morphe Manager $([regex]::Escape($managerFloor))\s*$" `
    -Description 'bug report form Manager placeholder'
Write-Host "[release] the bug report form's placeholders say `"$bugFormVersions`" and Morphe Manager $managerFloor"

function Test-ChangelogHere {
    <#
    .SYNOPSIS
        The CHANGELOG still describes what it described at the last tag, and describes this
        version in a form Morphe Manager can show.
    .DESCRIPTION
        Held against the file as it stood at the most recent tag reachable from HEAD, which is
        read with git. A checkout with no tag, or one where that tag carried no CHANGELOG, is
        still held to naming the version it builds; there is simply nothing older to compare.
    #>
    $path = Join-Path $rootPath 'CHANGELOG.md'
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "There is no CHANGELOG.md at $path, so no release can be described."
    }
    $current = Get-Content -LiteralPath $path -Raw

    $previous = $null
    $label = 'the last release'
    $tag = (Invoke-RepoGit -Root $rootPath -Arguments @('describe', '--tags', '--abbrev=0', 'HEAD') | Select-Object -First 1)
    $tag = "$tag".Trim()
    if ($tag) {
        # 2>$null on its own leaves the error in $LASTEXITCODE, and a tag from before this file
        # existed is a legitimate miss rather than a failure, so the text is what decides.
        $text = (Invoke-RepoGit -Root $rootPath -Arguments @('show', "${tag}:CHANGELOG.md")) -join "`n"
        if (-not [string]::IsNullOrWhiteSpace($text)) {
            $previous = $text
            $label = "tag $tag"
        }
    }

    $arguments = @{ Current = $current; ExpectedVersion = $releaseVersion }
    if ($null -ne $previous) {
        $arguments['Previous'] = $previous
        $arguments['PreviousLabel'] = $label
    }
    $check = Test-ChangelogVersions @arguments
    if (-not $check.Valid) { throw "The CHANGELOG does not describe this release: $($check.Reason)" }

    $manager = Test-ChangelogManagerEntry -Current $current -ExpectedVersion $releaseVersion
    if (-not $manager.Valid) { throw "Morphe Manager cannot show this release: $($manager.Reason)" }
    Write-Host ("[release] Morphe Manager can read the $releaseVersion entry: dated " +
        "$($manager.Date), $($manager.Bullets) bullets scoped TikTok")

    $described = @(Get-ChangelogVersions -Text $current)
    if ($null -eq $previous) {
        Write-Host ("[release] the CHANGELOG describes $releaseVersion and " +
            "$($described.Count) versions in all; there is no earlier tag to compare with")
    } else {
        Write-Host ("[release] the CHANGELOG describes $releaseVersion and keeps every version " +
            "$label described, $($described.Count) in all")
    }
}
Test-ChangelogHere

function Test-ReleaseReceiptHere {
    <#
    .SYNOPSIS
        Checks the provenance receipt this checkout has, if it has one.
    .DESCRIPTION
        A function so both exits run it. It used to sit after the bundle stamp check, which
        returns early when there is no built bundle, so on a clean checkout or any push that did
        not build one, none of the receipt was checked at all: exactly the case the early return
        exists to serve.
    #>
    param([string]$BundleForComparison)

    $receiptPath = if ($Receipt) { $Receipt } else {
        Join-Path $rootPath "release-receipt-$releaseVersion.json"
    }
    if (-not (Test-Path -LiteralPath $receiptPath -PathType Leaf)) {
        if ($VerifyPublishedAsset) {
            throw ("There is no release provenance receipt at $receiptPath. Run " +
                "scripts/build-release-receipt.ps1 against the retained fixtures first.")
        }
        Write-Host "[release] no receipt at $receiptPath, so its facts are not compared"
        return
    }

    # Not $receipt: PowerShell variable names are case-insensitive, so that is the -Receipt
    # parameter, and it is typed [string]. Assigning the parsed document to it coerces the whole
    # object to its string form, and every field then reads as empty.
    $receiptDocument = Read-JsonFile $receiptPath
    $approvedDelta = Read-ManifestDeltaAllowlist -Path (Join-Path $PSScriptRoot 'manifest-delta-allowlist.txt')

    # The commit the receipt names, checked against git rather than against the receipt's own
    # other field. Its timestamp and the bundle stamp both come out of the same document, so on
    # their own they prove only that the document agrees with itself; a receipt kept from an
    # earlier release satisfies that and names the wrong commit in the summary line below.
    $receiptCommit = [string]$receiptDocument.release.commit
    $actualEpoch = 0L
    if ($receiptCommit -match '^[0-9a-f]{40}$') {
        $known = (Invoke-RepoGit -Root $rootPath -Arguments @('cat-file', '-t', $receiptCommit) | Select-Object -First 1)
        if ("$known".Trim() -ne 'commit') {
            throw ("The release provenance receipt names commit $receiptCommit, which is not in " +
                "this repository.")
        }
        $epochText = (Invoke-RepoGit -Root $rootPath -Arguments @('log', '-1', '--format=%ct', $receiptCommit) |
            Select-Object -First 1)
        if ("$epochText".Trim() -match '^\d+$') { $actualEpoch = [long]"$epochText".Trim() }
    }
    # Only a release is held to the receipt describing a particular commit, and that commit is
    # the one the published tag names, not HEAD. A release is built from its source commit and
    # its index is pushed in a later commit, so on the push this runs for, HEAD is one past the
    # commit the bundle and the receipt were made from. The tag's commit was resolved from the
    # remote above, which is the same commit the local bundle's stamp is already held to. On an
    # ordinary push the receipt legitimately describes the commit it was generated at.
    $expectedCommit = $null
    if ($VerifyPublishedAsset) {
        if ([string]::IsNullOrWhiteSpace($releaseCommit)) {
            throw ('The published tag was not resolved before the receipt check, so the receipt ' +
                'cannot be held to the release commit.')
        }
        $expectedCommit = $releaseCommit
    }

    # The toolchain a receipt is held to is the one its own commit pinned, not the one pinned
    # now. A receipt describes a release that has already shipped; moving the patcher pin
    # afterwards does not make that receipt wrong, and holding it to the working catalog made
    # every later source push fail with "The receipt was stamped by patcher 1.12.0; the catalog
    # pins 1.13.0" on a machine where the receipt and the catalog were each correct. The only
    # checkout that has a receipt at all is the one that cut the release, so the gate was
    # stopping exactly the machine that did the work. On a release push the receipt's commit is
    # the release commit, so this reads the same catalog as before and nothing is relaxed.
    $resolved = Resolve-ReceiptToolchain -Root $rootPath -Commit $receiptCommit `
        -WorkingToolchain $workingToolchain
    $expectedToolchain = $resolved.Toolchain
    if ($resolved.Note) { Write-Host "[release] $($resolved.Note)" }
    # And the patch list its own commit carried, for the same reason: a patch added or renamed
    # after the release doesn't make the release's receipt wrong.
    $resolvedList = Resolve-ReceiptCatalog -Root $rootPath -Commit $receiptCommit -WorkingPatchList $patchList
    if ($resolvedList.Note) { Write-Host "[release] $($resolvedList.Note)" }
    $receiptTarget = Get-PatchTarget -PatchList $resolvedList.PatchList

    $receiptCheck = Test-ReleaseReceipt -Receipt $receiptDocument -ExpectedVersion $releaseVersion `
        -ExpectedPatchNames @($resolvedList.PatchList.patches | ForEach-Object { [string]$_.name }) `
        -ExpectedPatcherVersion $expectedToolchain.PatcherVersion `
        -ExpectedManagerFloor $expectedToolchain.ManagerFloor `
        -ExpectedPackageName $receiptTarget.PackageName -ExpectedPackageVersions @($receiptTarget.PackageVersions) `
        -BundlePath $BundleForComparison -ReceiptPath $receiptPath -ApprovedManifestDelta $approvedDelta `
        -ActualCommitTimestamp $actualEpoch -ExpectedCommit $expectedCommit
    if (-not $receiptCheck.Valid) {
        throw "The release provenance receipt does not describe this release: $($receiptCheck.Reason)"
    }
    $proved = @($receiptDocument.targets | ForEach-Object { "$($_.source.versionName)" })
    Write-Host ("[release] the receipt proves $($receiptDocument.release.patchCount) patches on " +
        ($proved -join ', ') + " from commit " + $receiptCommit.Substring(0, 8) +
        ", with no unreviewed manifest change")
}


$bundlePath = if ($ArtifactPath) { $ArtifactPath } else {
    Get-ReleaseBundlePath -Root $rootPath -Version $releaseVersion
}
if (-not (Test-Path -LiteralPath $bundlePath -PathType Leaf)) {
    # The stamp is a fact about a built bundle, and only the hash comparison needs one built
    # here. The pre-push hook reaches this after saying "no local bundle here, so the hosted
    # artifact is not compared", so a clean checkout pushing a README edit must not die on the
    # line after that. A release run passes -VerifyPublishedAsset and is held to it.
    if ($VerifyPublishedAsset) {
        throw ("The built bundle is missing, so its patcher version cannot be checked against " +
            "the $pinnedPatcher the catalog pins: $bundlePath. Run :patches:buildAndroid first.")
    }
    Write-Host ("[release] no built bundle at $bundlePath, so its patcher stamp is not compared " +
        "against the catalog pin $pinnedPatcher")
    # No bundle to compare bytes against, but everything else the receipt says is
    # still checked.
    Test-ReleaseReceiptHere
    Write-Host ("[facts] " + $sourceVersion + ": " + $patchCount + " patches for " + $targetPackage + " " + $targetVersionText + "; " + $testFacts)
    exit 0
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$manifestText = $null
$bundleZip = [System.IO.Compression.ZipFile]::OpenRead($bundlePath)
try {
    $manifestEntry = $bundleZip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
    if (-not $manifestEntry) { throw "The bundle has no META-INF/MANIFEST.MF: $bundlePath" }
    $manifestReader = New-Object System.IO.StreamReader($manifestEntry.Open())
    try { $manifestText = $manifestReader.ReadToEnd() } finally { $manifestReader.Dispose() }
} finally {
    $bundleZip.Dispose()
}
# Manifest lines wrap at 72 characters with a leading space on the continuation.
$manifestText = $manifestText -replace "\r?\n ", ''
$stampMatch = [regex]::Match($manifestText, '(?m)^Patcher-Version:\s*(\S+)\s*$')
if (-not $stampMatch.Success) {
    throw "The bundle manifest has no Patcher-Version: $bundlePath"
}
if ($stampMatch.Groups[1].Value -ne $pinnedPatcher) {
    throw ("The bundle stamps Patcher-Version " + $stampMatch.Groups[1].Value + " but the catalog " +
        "pins morphe-patcher " + $pinnedPatcher + ". The README floor is held separately to " +
        "Morphe Manager $managerFloor, so the patcher pin or built bundle is now wrong.")
}
Write-Host "[release] the bundle stamps patcher $pinnedPatcher, as the catalog pins"

Test-ReleaseReceiptHere -BundleForComparison $(if ($VerifyPublishedAsset) { $bundlePath } else { $null })

Write-Host ("[facts] " + $sourceVersion + ": " + $patchCount + " patches for " + $targetPackage + " " + $targetVersionText + "; " + $testFacts)

# Callers check the exit code, and a script invoked with & leaves the previous native
# command's code in $LASTEXITCODE, so a clean run has to say so itself.
exit 0
