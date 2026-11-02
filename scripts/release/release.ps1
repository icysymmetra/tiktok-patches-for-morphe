<#
.SYNOPSIS
    Cuts a Hushfeed release in stages: prepare, preflight, build, publish, index.

.DESCRIPTION
    One command per stage, in this order. Each stage checks that the one before it finished and
    refuses to run otherwise, so a stage can be re-run after a fix without redoing the others.

      prepare    CHANGELOG cut, version bump, patch list, What's new, translation and facts checks.
                 Then review and commit "chore(release): prepare X.Y.Z for publication".
      preflight  The fast checks a release gate would otherwise reach late (about five minutes):
                 the patch test partition without the fixture-heavy tests, What's new and L10n
                 tests, lint, the notes generator's own tests and the facts check. Then push with
                 BUILD_QUEUE_PRIORITY=release so the full pre-push gate runs.
      build      From the pushed, clean release commit: the bundle (timestamp pinned, classes.dex
                 present), the receipt over every declared fixture and SHA256SUMS.txt, into
                 build/release-assets/<version>.
      publish    Notes from the whole CHANGELOG section and a lightweight tag. Download and
                 compare all three assets from a draft before publishing it.
      index      patches-bundle.json, the README intro and counts, the bug form and the repository
                 description. Then commit "chore(release): point Manager to the verified X.Y.Z
                 bundle" and push (pre-push compares the hosted bundle byte for byte).

    Heavy steps run inside the machine build queue BUILD_QUEUE_SCRIPT names, at release
    priority; Gradle goes through HUSHFEED_BUILD_WRAPPER as the pre-push gate does.

.EXAMPLE
    scripts/release/release.ps1 -Stage prepare -Version 0.70.0
    scripts/release/release.ps1 -Stage publish -Version 0.70.0 -Intro intro.md -Checked checked.md
    scripts/release/release.ps1 -Stage index -Version 0.70.0 -ReadmeSummary readme.txt -BundleSummary bundle.txt -DeviceLine "..."
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidateSet('prepare', 'preflight', 'build', 'publish', 'index')][string]$Stage,
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [string]$Date,
    [string]$Intro,
    [string]$Checked,
    [string]$ReadmeSummary,
    [string]$BundleSummary,
    [string]$DeviceLine = '',
    [string]$Repository = 'SysAdminDoc/HushFeed',
    [switch]$RequireImmutableRelease
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$tag = "v$Version"
$assets = Join-Path $root "build/release-assets/$Version"
$bundleName = "patches-$Version.mpp"
$receiptName = "release-receipt-$Version.json"
. (Join-Path $root 'scripts/common.ps1')
. (Join-Path $root 'scripts/patch-target.ps1')
. (Join-Path $root 'scripts/release-attestation.ps1')
if ($RequireImmutableRelease -and $Stage -notin @('publish', 'index')) {
    throw '-RequireImmutableRelease applies to publish or index.'
}

foreach ($name in 'HUSHFEED_FIXTURE_DIR', 'HUSHFEED_BUILD_WRAPPER', 'HUSHFEED_DEVICE_SERIAL', 'HUSHFEED_DESKTOP_JAR', 'BUILD_QUEUE_SCRIPT') {
    if (-not (Get-Item "env:$name" -ErrorAction SilentlyContinue)) {
        $value = [Environment]::GetEnvironmentVariable($name, 'User')
        if ($value) { Set-Item "env:$name" $value }
    }
}
# A machine that shares its cores between several builds names its queue script here; the
# script defines Invoke-InBuildQueue. Unset, the heavy steps run straight away.
$env:BUILD_QUEUE_PRIORITY = 'release'
if ($env:BUILD_QUEUE_SCRIPT -and (Test-Path -LiteralPath $env:BUILD_QUEUE_SCRIPT -PathType Leaf)) { . $env:BUILD_QUEUE_SCRIPT }

function Step([string]$Text) { Write-Host "[release] $Text" -ForegroundColor Cyan }

function Invoke-Native {
    param([string]$What, [scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit $LASTEXITCODE)" }
}

function Invoke-Gradle {
    param([string[]]$Tasks)
    if (-not $env:HUSHFEED_BUILD_WRAPPER) { throw 'HUSHFEED_BUILD_WRAPPER is not set; heavy builds go through the machine wrapper.' }
    # Set JAVA_HOME rather than putting a JDK first on PATH: Resolve-Java's contract case reads
    # a PATH java as a bare name, and a newer PATH java once failed the gate in seconds.
    if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 or newer (Android Studio''s jbr works).' }
    if (-not $env:GITHUB_TOKEN) {
        $env:GITHUB_ACTOR = (gh api user --jq .login)
        $env:GITHUB_TOKEN = (gh auth token)
    }
    Invoke-Native "Gradle $($Tasks -join ' ')" { & $env:HUSHFEED_BUILD_WRAPPER -ProjectDir $root -Tasks $Tasks }
}

function Invoke-Queued {
    param([string]$Label, [scriptblock]$Command)
    if (Get-Command Invoke-InBuildQueue -ErrorAction SilentlyContinue) {
        $code = Invoke-InBuildQueue -Label "hushfeed $Label" -Priority release -ScriptBlock $Command
        if ($code -ne 0) { throw "$Label failed (exit $code)" }
    } else {
        & $Command
    }
}

function Assert-Clean {
    if (git -C $root status --porcelain --untracked-files=all) { throw 'The working tree has changes. Commit or move them first.' }
}

function Assert-PushedHead {
    Assert-Clean
    $head = (git -C $root rev-parse HEAD).Trim()
    $remote = ((git -C $root ls-remote origin refs/heads/main) -split '\s+')[0]
    if ($head -ne $remote) { throw "HEAD $head isn't origin/main $remote. Push the release commit first." }
    return $head
}

function Get-SourceVersion { ((Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) -match '^version\s*=' -replace '^version\s*=\s*', '').Trim() }

function Get-TestCount([string[]]$Directories) {
    $count = 0
    foreach ($directory in $Directories) {
        $files = @(Get-ChildItem -LiteralPath (Join-Path $root $directory) -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
        if ($files.Count -eq 0) { throw "No test results in $directory. The release gate writes them; run it in this checkout." }
        foreach ($file in $files) { $count += @(([xml](Get-Content -LiteralPath $file.FullName -Raw)).testsuite.testcase).Count }
    }
    return $count
}

function Invoke-PublicationCommand {
    param([string]$File, [string[]]$Arguments, [switch]$AllowFailure)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    try {
        $output = @(& $File @Arguments 2>&1)
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    $text = $output -join "`n"
    if ($code -ne 0 -and -not $AllowFailure) { throw "$File $($Arguments[0]) failed (exit $code): $text" }
    return [pscustomobject]@{ ExitCode = $code; Text = $text }
}

function Get-PublicationApi {
    param([string]$Endpoint, [switch]$AllowMissing, [string[]]$Parameters = @())
    # Only an actual HTTP 404 means absent. Auth, transport and invalid JSON failures must stop.
    $response = Invoke-PublicationCommand gh (@('api', $Endpoint, '--include') + $Parameters) -AllowFailure
    $text = $response.Text.Replace("`r`n", "`n")
    $status = [regex]::Match($text, '(?m)^HTTP/\S+ (\d{3})\b')
    if ($response.ExitCode -ne 0 -and $AllowMissing -and $status.Groups[1].Value -eq '404') { return $null }
    if ($response.ExitCode -ne 0) { throw "gh api $Endpoint failed (exit $($response.ExitCode)): $text" }
    $body = $text.IndexOf("`n`n")
    if (-not $status.Success -or $status.Groups[1].Value -ne '200' -or $body -lt 0) {
        throw "gh api $Endpoint returned incomplete HTTP metadata."
    }
    try { return $text.Substring($body + 2) | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "gh api $Endpoint returned invalid JSON: $($_.Exception.Message)" }
}

function Get-PublicationRelease {
    param([string]$Repository, [string]$Tag, [switch]$AllowMissing)
    $release = Get-PublicationApi "repos/$Repository/releases/tags/$([Uri]::EscapeDataString($Tag))" -AllowMissing
    if ($null -eq $release) {
        # REST's tag endpoint only finds published releases. The CLI also locates pending
        # draft tags with this GraphQL query, then reads the draft through its database ID.
        $owner, $name = $Repository -split '/', 2
        $query = 'query($owner: String!, $name: String!, $tag: String!) { repository(owner: $owner, name: $name) { nameWithOwner release(tagName: $tag) { databaseId isDraft } } }'
        $lookup = Get-PublicationApi graphql -Parameters @('-f', "query=$query", '-f', "owner=$owner", '-f', "name=$name", '-f', "tag=$Tag")
        if ($lookup.errors -or $lookup.data.repository.nameWithOwner -ine $Repository) { throw "Draft release lookup failed for $Repository $Tag." }
        $pending = $lookup.data.repository.release
        if ($null -eq $pending) {
            if ($AllowMissing) { return $null }
            throw "Release $Tag was not found."
        }
        if ([string]$pending.databaseId -notmatch '^[1-9][0-9]*$' -or $pending.isDraft -isnot [bool]) { throw "Invalid draft release identity for $Tag." }
        $release = Get-PublicationApi "repos/$Repository/releases/$($pending.databaseId)"
        if ([string]$release.id -cne [string]$pending.databaseId) { throw "Draft release identity changed for $Tag." }
    }
    # A published release always says whether it's immutable. A draft can't be immutable yet and
    # GitHub may leave the field out of it, which reads as not immutable.
    if ($null -eq $release -or $release.tag_name -cne $Tag -or [string]$release.id -notmatch '^[1-9][0-9]*$' -or
        $release.draft -isnot [bool] -or $release.prerelease -isnot [bool] -or
        ($release.immutable -isnot [bool] -and -not ($release.draft -and $null -eq $release.immutable)) -or
        $release.assets -isnot [array]) {
        throw "GitHub returned incomplete or mismatched release metadata for $Tag."
    }
    Assert-SingleTagRelease -Repository $Repository -Tag $Tag -ReleaseId ([string]$release.id)
    return $release
}

function Assert-SingleTagRelease {
    param([string]$Repository, [string]$Tag, [string]$ReleaseId)
    # GitHub doesn't keep draft tags unique. Asset calls below go by database ID, but a stray
    # second release on this tag means the lookup above may not be the one that was meant.
    $same = @()
    for ($page = 1; $page -le 10; $page++) {
        # Windows PowerShell 5.1's ConvertFrom-Json hands a JSON array on as one object, so @()
        # alone would hold the whole page as a single release. ForEach-Object unrolls it there.
        $batch = @(Get-PublicationApi "repos/$Repository/releases?per_page=100&page=$page" | ForEach-Object { $_ })
        $same += @($batch | Where-Object { $_.tag_name -ceq $Tag })
        if ($batch.Count -lt 100) { break }
        if ($page -eq 10) { throw "Too many releases to check $Tag for duplicates." }
    }
    if ($same.Count -ne 1 -or [string]$same[0].id -cne $ReleaseId) {
        throw "More than one release has the tag $Tag ($(@($same | ForEach-Object { $_.id }) -join ', ')). Delete the stray draft, then rerun."
    }
}

function Get-PublishedReleaseDate {
    param([object]$Release, [string]$Tag)
    if ($null -eq $Release -or $Release.tag_name -cne $Tag -or $Release.draft -isnot [bool] -or
        $Release.draft -or -not $Release.published_at) { throw "$Tag isn't published. Run -Stage publish first." }
    # PowerShell 7's ConvertFrom-Json already returns a UTC DateTime. Parsing its culture string
    # again reads it as local time and moves the index date by the machine's UTC offset.
    $at = $Release.published_at
    $utc = if ($at -is [DateTime]) { $at.ToUniversalTime() } else {
        [DateTime]::Parse([string]$at, [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::AdjustToUniversal -bor [Globalization.DateTimeStyles]::AssumeUniversal)
    }
    return $utc.ToString('yyyy-MM-ddTHH:mm:ss')
}

function Assert-ImmutablePublicationEnabled {
    param([string]$Repository)
    # Read only. Requiring immutability must not publish into a repo where it is off.
    $setting = Get-PublicationApi "repos/$Repository/immutable-releases" -AllowMissing
    if ($null -eq $setting -or $setting.enabled -isnot [bool] -or -not $setting.enabled) {
        throw 'Immutable releases must already be enabled before publishing with -RequireImmutableRelease.'
    }
}

function Assert-PublicationTag {
    param([string]$Root, [string]$Repository, [string]$Tag, [string]$ExpectedCommit, [switch]$CreateIfMissing)
    $head = (Invoke-PublicationCommand git @('-C', $Root, 'rev-parse', '--verify', 'HEAD')).Text.Trim()
    if ($head -ine $ExpectedCommit) { throw "HEAD changed from $ExpectedCommit to $head." }
    $local = (Invoke-PublicationCommand git @('-C', $Root, 'tag', '--list', $Tag)).Text.Trim()
    if ($local) {
        $commit = (Invoke-PublicationCommand git @('-C', $Root, 'rev-parse', "refs/tags/$Tag^{commit}")).Text.Trim()
        if ($local -cne $Tag -or $commit -ine $ExpectedCommit) { throw "Local tag $Tag doesn't point at $ExpectedCommit." }
    }
    $remote = (Invoke-PublicationCommand git @('-C', $Root, 'ls-remote', '--tags', 'origin', "refs/tags/$Tag", "refs/tags/$Tag^{}")).Text
    $refs = @{}
    foreach ($line in @($remote -split "`n" | Where-Object { $_.Trim() })) {
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -ne 2 -or $parts[0] -notmatch '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$' -or
            $parts[1] -cnotin @("refs/tags/$Tag", "refs/tags/$Tag^{}") -or $refs.ContainsKey($parts[1])) {
            throw "Invalid remote tag metadata for $Tag."
        }
        $refs[$parts[1]] = $parts[0]
    }
    if ($refs.Count) {
        $remoteCommit = if ($refs.ContainsKey("refs/tags/$Tag^{}")) { $refs["refs/tags/$Tag^{}"] } else { $refs["refs/tags/$Tag"] }
        if (-not $refs.ContainsKey("refs/tags/$Tag") -or $remoteCommit -ine $ExpectedCommit) {
            throw "Remote tag $Tag doesn't point at $ExpectedCommit."
        }
    } else {
        if (-not $CreateIfMissing) { throw "Remote tag $Tag is missing for an existing release." }
        if (-not $local) { Invoke-PublicationCommand git @('-C', $Root, 'tag', $Tag, $ExpectedCommit) | Out-Null }
        Invoke-PublicationCommand git @('-C', $Root, 'push', 'origin', "refs/tags/${Tag}:refs/tags/$Tag") | Out-Null
    }
    # The selected GitHub repository must resolve the tag too, even if origin names another repo.
    $hosted = (Invoke-PublicationCommand gh @('api', "repos/$Repository/commits/$([Uri]::EscapeDataString($Tag))", '--jq', '.sha')).Text.Trim()
    if ($hosted -ine $ExpectedCommit) { throw "GitHub tag $Tag doesn't point at $ExpectedCommit in $Repository." }
}

function Assert-PublicationRelease {
    param([object]$Release, [string]$Tag, [string]$ExpectedCommit, [string]$Notes,
        [hashtable]$Files, [hashtable]$Hashes, [bool]$IsDraft, [string]$ReleaseId, [switch]$Complete,
        [switch]$SkipUnfinished)
    # GitHub ignores target_commitish when the tag already exists, and this flow pushes the tag
    # before it creates the draft, so a draft can name the default branch here or nothing at all
    # (older published releases recorded a branch too). The tag's commit, resolved locally, on
    # origin and through GitHub before every write, is the authority. Only a different full commit
    # here contradicts it.
    $target = [string]$Release.target_commitish
    $targetMismatch = $target -match '^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$' -and $target -ine $ExpectedCommit
    if ($Release.draft -ne $IsDraft -or ($ReleaseId -and [string]$Release.id -cne $ReleaseId) -or
        $targetMismatch -or $Release.name -cne $Tag -or $Release.prerelease -or
        ([string]$Release.body).Replace("`r`n", "`n").TrimEnd("`n") -cne $Notes.Replace("`r`n", "`n").TrimEnd("`n") -or
        ($IsDraft -and $Release.immutable)) { throw "$Tag has an unexpected release identity, target, notes or draft state." }
    $seen = @{}
    $ids = @{}
    $fingerprints = foreach ($asset in $Release.assets) {
        $name = [string]$asset.name
        # Left for Remove-UnfinishedDraftAsset, which takes only these off the draft.
        if ($SkipUnfinished -and $IsDraft -and $asset.state -cne 'uploaded' -and $name -cin @($Files.Keys)) { continue }
        if ($name -cnotin @($Files.Keys) -or $seen.ContainsKey($name) -or [string]$asset.id -notmatch '^[1-9][0-9]*$' -or
            $ids.ContainsKey([string]$asset.id) -or $asset.state -cne 'uploaded' -or $asset.size -ne $Files[$name].Length -or
            ($asset.digest -and $asset.digest -ine "sha256:$($Hashes[$name])")) {
            throw "$Tag has an unexpected, incomplete or mismatched asset: $name"
        }
        $seen[$name] = $true
        $ids[[string]$asset.id] = $true
        "$($asset.id)|$name|$($asset.size)|$($asset.digest)"
    }
    if ($Complete -and $seen.Count -ne 3) { throw "$Tag must contain exactly the three release assets." }
    return (($fingerprints | Sort-Object) -join "`n")
}

function Remove-UnfinishedDraftAsset {
    param([object]$Release, [string]$Repository, [string]$Tag, [string[]]$Names)
    # An upload cut off partway leaves its asset on the draft unfinished, under the very name the
    # rerun has to upload, and GitHub refuses a second asset by that name: every rerun stopped at
    # the same draft. Only an unfinished asset named as one of this release's files goes, from the
    # draft whose identity was just checked, by its own ID. Anything else still stops the run.
    if (-not $Release.draft) { return $false }
    $removed = $false
    foreach ($asset in @($Release.assets)) {
        if ($asset.state -ceq 'uploaded' -or [string]$asset.name -cnotin $Names) { continue }
        if ([string]$asset.id -notmatch '^[1-9][0-9]*$') { throw "$Tag has an unfinished asset without a valid ID: $($asset.name)" }
        Step "removing the unfinished upload of $($asset.name) from draft $Tag"
        Invoke-PublicationCommand gh @('api', "repos/$Repository/releases/assets/$($asset.id)", '--method', 'DELETE') | Out-Null
        $removed = $true
    }
    return $removed
}

function Save-PublicationAsset {
    param([string]$Repository, [object]$Asset, [string]$Path)
    # Windows PowerShell 5.1 re-encodes a redirected native stream as text, so copy the bytes.
    $gh = (Get-Command gh -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
    $info = [Diagnostics.ProcessStartInfo]::new($gh, "api -H `"Accept: application/octet-stream`" repos/$Repository/releases/assets/$($Asset.id)")
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($info)
    try {
        $errors = $process.StandardError.ReadToEndAsync()
        $stream = [IO.File]::Create($Path)
        try { $process.StandardOutput.BaseStream.CopyTo($stream) } finally { $stream.Dispose() }
        $process.WaitForExit()
        if ($process.ExitCode -ne 0) { throw "gh api asset $($Asset.id) failed (exit $($process.ExitCode)): $($errors.Result)" }
    } finally { $process.Dispose() }
}

function Assert-PublicationDownloads {
    param([object]$Release, [string]$Repository, [string]$Tag, [hashtable]$Hashes, [string]$Work)
    $check = Resolve-WithinRoot -Root $Work -Path (Join-Path $Work ([guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $check
    # By asset ID from the verified release. `gh release download <tag>` reselects by tag.
    foreach ($asset in $Release.assets) {
        $name = [string]$asset.name
        if ([IO.Path]::GetFileName($name) -cne $name) { throw "$Tag has an unsafe asset name: $name" }
        Save-PublicationAsset -Repository $Repository -Asset $asset -Path (Join-Path $check $name)
    }
    $downloaded = @(Get-ChildItem -LiteralPath $check -Force)
    if ($downloaded.Count -ne $Release.assets.Count -or @($downloaded | Where-Object { $_.PSIsContainer -or $_.Name -cnotin @($Release.assets.name) }).Count) {
        throw "$Tag download is missing assets or contains unexpected files."
    }
    foreach ($file in $downloaded) {
        if ((Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash -ine $Hashes[$file.Name]) {
            throw "The hosted $($file.Name) differs from the local one."
        }
        Step "$($file.Name) matches the hosted copy"
    }
}

function Invoke-ReleasePublication {
    [CmdletBinding()]
    param([Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')][string]$Repository,
        [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
        [Parameter(Mandatory = $true)][ValidatePattern('^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$')][string]$ExpectedCommit,
        [Parameter(Mandatory = $true)][string]$Assets, [Parameter(Mandatory = $true)][string]$NotesPath,
        [switch]$RequireImmutableRelease)
    $tag = "v$Version"
    $names = @("patches-$Version.mpp", "release-receipt-$Version.json", 'SHA256SUMS.txt')
    $files = @{}
    $hashes = @{}
    foreach ($name in $names) {
        $file = Get-Item -LiteralPath (Join-Path $Assets $name) -ErrorAction Stop
        if ($file.PSIsContainer) { throw "Release asset isn't a file: $name" }
        $files[$name] = $file
        $hashes[$name] = (Get-FileHash -Algorithm SHA256 -LiteralPath $file.FullName).Hash.ToLowerInvariant()
    }
    $notes = [IO.File]::ReadAllText((Get-Item -LiteralPath $NotesPath -ErrorAction Stop).FullName)
    $receipt = Get-Content -LiteralPath $files[$names[1]].FullName -Raw | ConvertFrom-Json
    if ([string]$receipt.source.commit -ine $ExpectedCommit) { throw "The receipt isn't for $ExpectedCommit." }
    $expectedSums = ($names[0..1] | ForEach-Object { "$($hashes[$_])  $_" }) -join "`n"
    if ([IO.File]::ReadAllText($files[$names[2]].FullName).Replace("`r`n", "`n").TrimEnd("`n") -cne $expectedSums) {
        throw 'SHA256SUMS.txt does not match the two local release assets.'
    }
    $release = Get-PublicationRelease -Repository $Repository -Tag $tag -AllowMissing
    $alreadyPublished = $null -ne $release -and -not $release.draft
    if ($RequireImmutableRelease) {
        if ($alreadyPublished) {
            if (-not $release.immutable) { throw "$tag is mutable. An immutable release is required." }
        } else {
            Assert-ImmutablePublicationEnabled -Repository $Repository
        }
    }
    Assert-PublicationTag -Root $Root -Repository $Repository -Tag $tag -ExpectedCommit $ExpectedCommit -CreateIfMissing:($null -eq $release)
    if ($null -eq $release) {
        Step "creating draft $tag"
        Invoke-PublicationCommand gh @('release', 'create', $tag, '-R', $Repository, '--draft', '--verify-tag',
            '--target', $ExpectedCommit, '--title', $tag, '--notes-file', $NotesPath) | Out-Null
        $release = Get-PublicationRelease -Repository $Repository -Tag $tag
        if (-not $release.draft) { throw "$tag was not created as a draft." }
    }
    $identity = @{
        Tag = $tag; ExpectedCommit = $ExpectedCommit; Notes = $notes; Files = $files; Hashes = $hashes
        ReleaseId = [string]$release.id; IsDraft = (-not $alreadyPublished)
    }
    if (-not $alreadyPublished) {
        # The draft's identity and every finished asset pass first, so an unfinished upload is
        # only ever taken off the draft this run owns.
        Assert-PublicationRelease -Release $release @identity -SkipUnfinished | Out-Null
        if (Remove-UnfinishedDraftAsset -Release $release -Repository $Repository -Tag $tag -Names $names) {
            $release = Get-PublicationRelease -Repository $Repository -Tag $tag
        }
    }
    $fingerprint = Assert-PublicationRelease -Release $release @identity -Complete:$alreadyPublished
    $temporaryRoot = [IO.Path]::GetTempPath()
    $work = Resolve-WithinRoot -Root $temporaryRoot -Path (Join-Path $temporaryRoot ("hushfeed-publication-$Version-" + [guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $work
    try {
        if ($release.assets.Count) {
            Assert-PublicationDownloads -Release $release -Repository $Repository -Tag $tag -Hashes $hashes -Work $work
            $release = Get-PublicationRelease -Repository $Repository -Tag $tag
            if ((Assert-PublicationRelease -Release $release @identity -Complete:$alreadyPublished) -cne $fingerprint) {
                throw "$tag assets changed during download verification."
            }
        }
        if (-not $alreadyPublished) {
            $missing = @($names | Where-Object { $_ -cnotin @($release.assets.name) })
            if ($missing.Count) {
                # To the verified release ID, one asset per call. `gh release upload <tag>` reselects by tag.
                foreach ($name in $missing) {
                    $url = "https://uploads.github.com/repos/$Repository/releases/$($identity.ReleaseId)/assets?name=$([Uri]::EscapeDataString($name))"
                    Invoke-PublicationCommand gh @('api', $url, '--method', 'POST', '-H', 'Content-Type: application/octet-stream',
                        '--input', $files[$name].FullName) | Out-Null
                }
                $release = Get-PublicationRelease -Repository $Repository -Tag $tag
                $fingerprint = Assert-PublicationRelease -Release $release @identity -Complete
                Assert-PublicationDownloads -Release $release -Repository $Repository -Tag $tag -Hashes $hashes -Work $work
                $release = Get-PublicationRelease -Repository $Repository -Tag $tag
                if ((Assert-PublicationRelease -Release $release @identity -Complete) -cne $fingerprint) {
                    throw "$tag assets changed during download verification."
                }
            }
        }
        foreach ($name in $names) {
            if ((Get-FileHash -Algorithm SHA256 -LiteralPath $files[$name].FullName).Hash -ine $hashes[$name]) {
                throw "Local release asset changed during verification: $name"
            }
        }
        if ([IO.File]::ReadAllText($NotesPath) -cne $notes) { throw 'Local release notes changed during verification.' }
        Assert-PublicationTag -Root $Root -Repository $Repository -Tag $tag -ExpectedCommit $ExpectedCommit
        if (-not $alreadyPublished) {
            if ($RequireImmutableRelease) { Assert-ImmutablePublicationEnabled -Repository $Repository }
            $release = Get-PublicationRelease -Repository $Repository -Tag $tag
            if ((Assert-PublicationRelease -Release $release @identity -Complete) -cne $fingerprint) {
                throw "$tag assets changed before publication."
            }
            Step "publishing verified draft $tag"
            # Patch the checked database ID. Editing by tag would resolve it again and
            # could publish a replacement draft that never passed these asset checks.
            Get-PublicationApi "repos/$Repository/releases/$($identity.ReleaseId)" -Parameters @('--method', 'PATCH', '-F', 'draft=false') | Out-Null
            $release = Get-PublicationRelease -Repository $Repository -Tag $tag
            $identity.IsDraft = $false
            if ((Assert-PublicationRelease -Release $release @identity -Complete) -cne $fingerprint) { throw "$tag assets changed at publication." }
            Assert-PublicationTag -Root $Root -Repository $Repository -Tag $tag -ExpectedCommit $ExpectedCommit
        }
        Get-PublishedReleaseDate -Release $release -Tag $tag | Out-Null
        if ($release.immutable -or $RequireImmutableRelease) {
            foreach ($name in $names) {
                Test-ReleaseAttestation -Repository $Repository -Tag $tag -ArtifactPath $files[$name].FullName -AssetName $name `
                    -ExpectedCommit $ExpectedCommit -RequireImmutableRelease | Out-Null
            }
        } else { Step "$Repository $tag is mutable; release attestation verification skipped" }
        $release = Get-PublicationRelease -Repository $Repository -Tag $tag
        if ((Assert-PublicationRelease -Release $release @identity -Complete) -cne $fingerprint) {
            throw "$tag assets changed during final verification."
        }
        Get-PublishedReleaseDate -Release $release -Tag $tag | Out-Null
        Assert-PublicationTag -Root $Root -Repository $Repository -Tag $tag -ExpectedCommit $ExpectedCommit
        return [pscustomobject]@{
            Tag = $tag; Commit = $ExpectedCommit; ReleaseId = [string]$release.id; Immutable = $release.immutable
            AlreadyPublished = $alreadyPublished; VerifiedAssets = 3
        }
    } finally { Remove-GeneratedPath -Root $temporaryRoot -Path $work }
}

switch ($Stage) {
    'prepare' {
        Assert-Clean
        if ((git -C $root rev-parse --abbrev-ref HEAD).Trim() -ne 'main') { throw 'Releases are cut on main.' }
        if (git -C $root tag --list $tag) { throw "$tag already exists." }
        Step "cutting the CHANGELOG for $Version"
        $cutArgs = @('cut', '--version', $Version) + $(if ($Date) { @('--date', $Date) } else { @() })
        Invoke-Native 'CHANGELOG cut' { py -3.13 (Join-Path $root 'tools/release_text.py') @cutArgs }
        Step 'version in gradle.properties and the README badge'
        $properties = Join-Path $root 'gradle.properties'
        $text = [IO.File]::ReadAllText($properties) -replace '(?m)^version = \d+\.\d+\.\d+$', "version = $Version"
        [IO.File]::WriteAllText($properties, $text)
        $readme = Join-Path $root 'README.md'
        $text = [IO.File]::ReadAllText($readme) -replace 'badge/version-\d+\.\d+\.\d+-', "badge/version-$Version-"
        [IO.File]::WriteAllText($readme, $text)
        Step 'patch list, developer guide and What''s new'
        Invoke-Gradle @(':patches:generatePatchesList')
        $guideArgs = @('guide') + $(if ($Date) { @('--date', $Date) } else { @() })
        Invoke-Native 'developer guide' { py -3.13 (Join-Path $root 'tools/release_text.py') @guideArgs }
        Invoke-Native 'What''s new' { py -3.13 (Join-Path $root 'tools/gen-release-notes.py') }
        Invoke-Native 'translation check' { py -3.13 (Join-Path $root 'tools/release_text.py') check-translations --version $Version }
        Invoke-Native 'facts precheck' {
            & (Join-Path $root 'scripts/validate-release-facts.ps1') -Root $root -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipTestResults
        }
        Step "review the diff, commit it as 'chore(release): prepare $Version for publication', then run -Stage preflight"
    }
    'preflight' {
        Assert-Clean
        if ((Get-SourceVersion) -ne $Version) { throw "gradle.properties says $(Get-SourceVersion). Run -Stage prepare first." }
        $started = Get-Date
        Invoke-Native 'release text tests' { py -3.13 -m unittest discover -s (Join-Path $root 'tools') -p 'test_*.py' }
        Invoke-Native 'translation check' { py -3.13 (Join-Path $root 'tools/release_text.py') check-translations --version $Version }
        Invoke-Gradle @(':patches:test', '-x', ':patches:nativeTest', '-x', ':patches:documentationTest', '-x', ':patches:verifyPatchTestSelection')
        Invoke-Gradle @(':extensions:tiktok:testDebugUnitTest', '--tests', '*ReleaseNotesTest', '--tests', '*SettingsL10nTest', '--tests', '*L10nQuantityTest', ':extensions:tiktok:lintDebug')
        Invoke-Native 'facts precheck' {
            & (Join-Path $root 'scripts/validate-release-facts.ps1') -Root $root -SkipDescriptionTestCount -AllowPublishedIndexLag -SkipTestResults
        }
        Step ("preflight passed in {0:N0} min. Push with BUILD_QUEUE_PRIORITY=release so the full gate runs, then -Stage build" -f ((Get-Date) - $started).TotalMinutes)
    }
    'build' {
        $head = Assert-PushedHead
        if ((Get-SourceVersion) -ne $Version) { throw "gradle.properties says $(Get-SourceVersion), not $Version." }
        # A rebuild overwrites the local assets that -Stage index later copies from.
        $existing = Get-PublicationRelease -Repository $Repository -Tag $tag -AllowMissing
        if ($null -ne $existing -and -not $existing.draft) { throw "$tag is already published. Its assets are final, so a rebuild would only diverge from them." }
        Step "bundle from $head"
        Invoke-Gradle @(':patches:buildAndroid')
        $bundle = Join-Path $root "patches/build/release/$bundleName"
        if (-not (Test-Path -LiteralPath $bundle)) { throw "No bundle at $bundle." }
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [IO.Compression.ZipFile]::OpenRead($bundle)
        try {
            $reader = [IO.StreamReader]::new($zip.GetEntry('META-INF/MANIFEST.MF').Open())
            $manifest = $reader.ReadToEnd(); $reader.Dispose()
            $hasDex = [bool]($zip.Entries | Where-Object FullName -EQ 'classes.dex')
        } finally { $zip.Dispose() }
        $stamp = [regex]::Match($manifest, 'Timestamp:\s*(\d+)').Groups[1].Value
        $seconds = (git -C $root log -1 --format=%ct).Trim()
        if ($stamp -ne "${seconds}000") { throw "The bundle's Timestamp $stamp isn't pinned to the release commit ($seconds)." }
        if (-not $hasDex) { throw 'The bundle has no classes.dex, so Manager would load no patches.' }
        Step 'receipt over every declared fixture'
        # The bundle's own target, the builds the gate applied: PineDrama (#156) declares its own
        # version beside it, and no TikTok APK of that version exists.
        $target = Get-PatchTarget -PatchList (Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json)
        $fixtures = foreach ($v in @($target.PackageVersions)) {
            $match = @(Get-ChildItem -LiteralPath $env:HUSHFEED_FIXTURE_DIR -Filter "$($target.PackageName)_$v-*.apk" -File)
            if ($match.Count -ne 1) { throw "Expected one $v APK in $env:HUSHFEED_FIXTURE_DIR, found $($match.Count)." }
            $match[0].FullName
        }
        $work = Join-Path ([IO.Path]::GetTempPath()) "hushfeed-receipt-$Version"
        New-Item -ItemType Directory -Force -Path $work | Out-Null
        try {
            # The release gate kept its fixture runs; the receipt reads any made with this bundle.
            Invoke-Queued 'receipt' {
                & (Join-Path $root 'scripts/build-release-receipt.ps1') -WorkDir $work -Fixture $fixtures -Bundle $bundle `
                    -AppliedDir (Join-Path $root 'patches/build/fixture-apply')
            }
        } finally { Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Force -Path $assets | Out-Null
        Copy-Item -LiteralPath $bundle, (Join-Path $root $receiptName) -Destination $assets -Force
        $sums = foreach ($name in $bundleName, $receiptName) {
            '{0}  {1}' -f (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assets $name)).Hash.ToLowerInvariant(), $name
        }
        [IO.File]::WriteAllText((Join-Path $assets 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
        $sums | ForEach-Object { Step $_ }
        Step "assets in $assets. Write the intro and checked lists, then -Stage publish"
    }
    'publish' {
        $head = Assert-PushedHead
        if (-not $Intro -or -not $Checked) { throw '-Intro and -Checked are the notes paragraphs above and below the CHANGELOG bullets.' }
        foreach ($name in $bundleName, $receiptName, 'SHA256SUMS.txt') {
            if (-not (Test-Path -LiteralPath (Join-Path $assets $name))) { throw "No $name in $assets. Run -Stage build first." }
        }
        $receipt = Get-Content -LiteralPath (Join-Path $assets $receiptName) -Raw | ConvertFrom-Json
        if ($receipt.source.commit -and $receipt.source.commit -ne $head) { throw "The receipt is for $($receipt.source.commit), HEAD is $head." }
        $notes = Join-Path $assets 'notes.md'
        Invoke-Native 'release notes' { py -3.13 (Join-Path $root 'tools/release_text.py') github-notes --version $Version --intro $Intro --checked $Checked --out $notes }
        Invoke-ReleasePublication -Root $root -Repository $Repository -Version $Version -ExpectedCommit $head `
            -Assets $assets -NotesPath $notes -RequireImmutableRelease:$RequireImmutableRelease | Out-Null
        Step 'published. Write the README and bundle summaries, then -Stage index'
    }
    'index' {
        $head = Assert-PushedHead
        if (-not $ReadmeSummary -or -not $BundleSummary) { throw '-ReadmeSummary and -BundleSummary are the release''s README line and Manager text.' }
        $published = Get-PublicationRelease -Repository $Repository -Tag $tag
        $created = Get-PublishedReleaseDate -Release $published -Tag $tag
        # Read before anything is written: a failed read used to come back empty and was then
        # written over the live description.
        $description = [string](gh repo view $Repository --json description --jq .description)
        if ($LASTEXITCODE -ne 0) { throw "Couldn't read the $Repository description (exit $LASTEXITCODE), so nothing was indexed." }
        # The receipt is copied into the repo below, so it has to be the one that was published.
        $hostedReceipt = @($published.assets | Where-Object { $_.name -ceq $receiptName })
        $localReceipt = (Get-FileHash -Algorithm SHA256 -LiteralPath (Join-Path $assets $receiptName)).Hash.ToLowerInvariant()
        if ($hostedReceipt.Count -ne 1 -or -not $hostedReceipt[0].digest) { throw "$tag has no single hosted $receiptName with a digest to compare." }
        if ($hostedReceipt[0].digest -ine "sha256:$localReceipt") {
            throw "The local $receiptName ($localReceipt) differs from the hosted one ($($hostedReceipt[0].digest)). Don't index a rebuilt receipt."
        }
        if ($RequireImmutableRelease) {
            foreach ($name in $bundleName, $receiptName, 'SHA256SUMS.txt') {
                Test-ReleaseAttestation -Repository $Repository -Tag $tag -ArtifactPath (Join-Path $assets $name) `
                    -AssetName $name -ExpectedCommit $head -RequireImmutableRelease | Out-Null
            }
        }
        $runtime = Get-TestCount @('extensions/tiktok/build/test-results/testDebugUnitTest')
        $patch = Get-TestCount @('patches/build/test-results/test', 'patches/build/test-results/nativeTest', 'patches/build/test-results/documentationTest')
        Invoke-Native 'index' {
            py -3.13 (Join-Path $root 'tools/release_text.py') index --version $Version --created $created `
                --readme-summary $ReadmeSummary --bundle-summary $BundleSummary --device-line $DeviceLine --runtime $runtime --patch $patch
        }
        Copy-Item -LiteralPath (Join-Path $assets $receiptName) -Destination (Join-Path $root $receiptName) -Force
        $list = Get-Content -LiteralPath (Join-Path $root 'patches-list.json') -Raw | ConvertFrom-Json
        $count = @($list.patches).Count
        $updated = $description -replace 'Hushfeed v\d+\.\d+\.\d+', "Hushfeed v$Version" -replace '\b\d+ patches\b', "$count patches"
        if ($updated -ne $description) { Invoke-Native 'repository description' { gh repo edit $Repository --description $updated } }
        Step "index points at $tag ($runtime runtime tests, $patch patch tests). Commit it as 'chore(release): point Manager to the verified $Version bundle' and push"
    }
}
