<# Exercise the actual publication flow without a network, tags or releases. #>
[CmdletBinding()]
param([string]$Root, [string]$ReleaseScript)

$ErrorActionPreference = 'Stop'
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
if (-not $ReleaseScript) { $ReleaseScript = Join-Path $Root 'scripts/release/release.ps1' }
. (Join-Path $Root 'scripts/common.ps1')
. (Join-Path $Root 'scripts/release-attestation.ps1')
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile($ReleaseScript, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors -join "`n") }
foreach ($name in @('Step', 'Invoke-PublicationCommand', 'Get-PublicationApi', 'Get-PublicationRelease',
        'Assert-PublicationTag', 'Assert-PublicationRelease', 'Get-PublishedReleaseDate', 'Assert-ImmutablePublicationEnabled',
        'Assert-PublicationDownloads', 'Assert-SingleTagRelease', 'Remove-UnfinishedDraftAsset', 'Invoke-ReleasePublication')) {
    $definition = $ast.Find({ param($node)
        $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name
    }, $false)
    if (-not $definition) { throw "Missing publication function $name" }
    . ([scriptblock]::Create($definition.Extent.Text))
}

function Assert-True([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}
function Assert-Throws([scriptblock]$Run, [string]$Pattern) {
    $message = $null
    try { & $Run | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message -or $message -notmatch $Pattern) {
        throw "Expected '$Pattern', got '$message'."
    }
}

$temporaryRoot = [IO.Path]::GetTempPath()
$testRoot = Resolve-WithinRoot -Root $temporaryRoot -Path (
    Join-Path $temporaryRoot ('hushfeed-publication-test-' + [guid]::NewGuid().ToString('N')))
$null = New-Item -ItemType Directory -Path $testRoot
$controlRoot = Resolve-WithinRoot -Root $temporaryRoot -Path (
    Join-Path $temporaryRoot ('hushfeed-publication-control-' + [guid]::NewGuid().ToString('N')))
$null = New-Item -ItemType Directory -Path $controlRoot
$controlFile = Join-Path $controlRoot 'preserve.txt'
[IO.File]::WriteAllText($controlFile, 'unrelated to publication work directories')
$script:caseCount = 0
$commit = '0123456789abcdef0123456789abcdef01234567'
$names = @('patches-1.2.3.mpp', 'release-receipt-1.2.3.json', 'SHA256SUMS.txt')

function Reset-Case {
    $caseRoot = Resolve-WithinRoot -Root $testRoot -Path (Join-Path $testRoot ([guid]::NewGuid().ToString('N')))
    $null = New-Item -ItemType Directory -Path $caseRoot
    $assetDir = Join-Path $caseRoot 'assets with spaces'
    $null = New-Item -ItemType Directory -Path $assetDir
    [IO.File]::WriteAllText((Join-Path $assetDir $names[0]), 'local bundle bytes')
    [IO.File]::WriteAllText((Join-Path $assetDir $names[1]), (@{ source = @{ commit = $commit } } | ConvertTo-Json -Depth 3))
    $sums = foreach ($name in $names[0..1]) {
        '{0}  {1}' -f (Get-FileHash -LiteralPath (Join-Path $assetDir $name) -Algorithm SHA256).Hash.ToLowerInvariant(), $name
    }
    [IO.File]::WriteAllText((Join-Path $assetDir $names[2]), (($sums -join "`n") + "`n"))
    $notes = Join-Path $caseRoot 'notes with spaces.md'
    [IO.File]::WriteAllText($notes, "Release notes.`n")
    $script:state = @{
        Release = $null; LocalTag = $null; RemoteTag = $null; ApiTagCommit = $commit; Head = $commit
        ImmutableEnabled = $false; ImmutableSettingMissing = $false; UploadFailureAfter = 0; DownloadFailure = $false; PublishFailure = $false
        OmitDownload = ''; CorruptDownload = ''; ExtraDownload = $false; MetadataMutation = $null
        MetadataReads = 0; ApiFailure = 0; InvalidApiJson = $false; GraphQLFailure = $false; GitFailure = ''; NextAssetId = 100
        RemoteTagObject = ''; SkipUpload = ''; BeforePublish = $null; ExtraReleases = @(); SecondPageReleases = @(); Uploaded = 0
        AfterCreate = $null; AfterUpload = $null
        Calls = (New-Object 'System.Collections.Generic.List[object]')
        DownloadDirectories = (New-Object 'System.Collections.Generic.List[string]')
        RemoteBytes = @{}; LastDownload = @(); AttestationFailure = ''
        Description = 'Hushfeed v1.2.2 for TikTok, 130 patches'; DescriptionFailure = $false
    }
    $script:invoke = @{
        Root = $caseRoot; Version = '1.2.3'; Repository = 'example/project'; ExpectedCommit = $commit
        Assets = $assetDir; NotesPath = $notes
    }
}
function New-Release([bool]$Draft = $true) {
    return [pscustomobject]@{
        id = 42; tag_name = 'v1.2.3'; target_commitish = $commit; name = 'v1.2.3'; body = "Release notes.`n"
        draft = $Draft; prerelease = $false; immutable = $false; published_at = $null; assets = @()
    }
}
function Add-RemoteAsset([string]$Name, [byte[]]$Bytes) {
    if (-not $Bytes) { $Bytes = [IO.File]::ReadAllBytes((Join-Path $script:invoke.Assets $Name)) }
    $script:state.RemoteBytes[$Name] = $Bytes
    $sha = [Security.Cryptography.SHA256]::Create()
    try { $hash = ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
    $script:state.Release.assets += [pscustomobject]@{
        id = $script:state.NextAssetId++; name = $Name; state = 'uploaded'; size = $Bytes.Length; digest = "sha256:$hash"
    }
}
function Seed-Release([string[]]$AssetNames = $names, [switch]$Published, [switch]$Immutable) {
    $script:state.LocalTag = $commit
    $script:state.RemoteTag = $commit
    $script:state.Release = New-Release -Draft (-not $Published)
    if ($Published) { $script:state.Release.published_at = '2026-10-12T12:00:00Z' }
    $script:state.Release.immutable = [bool]$Immutable
    foreach ($name in $AssetNames) { Add-RemoteAsset $name }
}
function Get-Option([string[]]$Arguments, [string]$Name) {
    $index = [Array]::IndexOf($Arguments, $Name)
    if ($index -lt 0 -or $index + 1 -ge $Arguments.Count) { throw "Missing mock option $Name" }
    return $Arguments[$index + 1]
}
function Record-Command([string]$File, [string[]]$Arguments) {
    $script:state.Calls.Add([pscustomobject]@{ File = $File; Arguments = $Arguments })
    # The one removal allowed: an asset by its ID, which the gh mock then holds to an unfinished
    # upload of one of the release's own files on the verified draft.
    $assetDelete = $File -ceq 'gh' -and $Arguments.Count -eq 4 -and $Arguments[0] -ceq 'api' -and
        $Arguments[1] -match '^repos/example/project/releases/assets/[1-9][0-9]*$' -and ($Arguments[2..3] -join '|') -ceq '--method|DELETE'
    if ($Arguments -contains '--clobber' -or $Arguments -contains 'delete-asset' -or
        (($Arguments -contains 'delete') -and -not $assetDelete)) {
        throw 'The publication flow attempted a destructive command.'
    }
    $global:LASTEXITCODE = 0
}
function Write-ApiResponse([object]$Value, [bool]$Include, [int]$Status = 200) {
    if ($Include) { "HTTP/2.0 $Status`nContent-Type: application/json`n`n" }
    $Value | ConvertTo-Json -Depth 12 -Compress
    if ($Status -ne 200) { $global:LASTEXITCODE = 1 }
}
function New-Attestation {
    $subjects = @([pscustomobject]@{ uri = 'pkg:github/example/project@v1.2.3'; digest = @{ sha1 = $commit } })
    foreach ($asset in $script:state.Release.assets) {
        $subjects += [pscustomobject]@{ name = $asset.name; digest = @{ sha256 = $asset.digest.Substring(7) } }
    }
    return @{ verificationResult = @{ statement = @{
        _type = 'https://in-toto.io/Statement/v1'; predicateType = 'https://in-toto.io/attestation/release/v0.2'
        predicate = @{ repository = 'example/project'; tag = 'v1.2.3'; databaseId = '42'; purl = 'pkg:github/example/project@v1.2.3' }
        subject = $subjects
    } } }
}

# These boundaries intercept every native command used by the real function. Unexpected commands
# throw, so the test cannot fall through to a real tag push or GitHub mutation.
function git {
    $a = [string[]]$args
    Record-Command 'git' $a
    Assert-True ($a.Count -ge 3 -and $a[0] -ceq '-C' -and $a[1] -ceq $script:invoke.Root) 'Git lost its explicit working directory.'
    $command = $a[2..($a.Count - 1)] -join '|'
    if ($command -ceq $script:state.GitFailure) { $global:LASTEXITCODE = 23; return }
    switch -Exact ($command) {
        'rev-parse|--verify|HEAD' { $script:state.Head; return }
        'tag|--list|v1.2.3' { if ($script:state.LocalTag) { 'v1.2.3' }; return }
        'rev-parse|refs/tags/v1.2.3^{commit}' { $script:state.LocalTag; return }
        'ls-remote|--tags|origin|refs/tags/v1.2.3|refs/tags/v1.2.3^{}' {
            if ($script:state.RemoteTagObject) {
                "$($script:state.RemoteTagObject)`trefs/tags/v1.2.3"
                "$($script:state.RemoteTag)`trefs/tags/v1.2.3^{}"
            } elseif ($script:state.RemoteTag) { "$($script:state.RemoteTag)`trefs/tags/v1.2.3" }
            return
        }
        "tag|v1.2.3|$commit" { $script:state.LocalTag = $commit; return }
        'push|origin|refs/tags/v1.2.3:refs/tags/v1.2.3' { $script:state.RemoteTag = $script:state.LocalTag; return }
        default { throw "Unexpected git command: $command" }
    }
}
function gh {
    $a = [string[]]$args
    Record-Command 'gh' $a
    if ($a[0] -ceq 'api') {
        $endpoint = $a[1]
        $include = $a -contains '--include'
        if ($script:state.ApiFailure) {
            Write-ApiResponse @{ message = 'Forbidden' } $include $script:state.ApiFailure
            return
        }
        if ($script:state.InvalidApiJson) { "HTTP/2.0 200 OK`n`nnot JSON"; return }
        switch -Exact ($endpoint) {
            'repos/example/project/releases/tags/v1.2.3' {
                $script:state.MetadataReads++
                if ($script:state.MetadataMutation) { & $script:state.MetadataMutation }
                if (-not $script:state.Release -or $script:state.Release.draft) { Write-ApiResponse @{ message = 'Not Found' } $include 404 }
                else { Write-ApiResponse $script:state.Release $include }
                return
            }
            'graphql' {
                Assert-True ((Get-Option $a '-f') -match '^query=query\(' -and ($a -join '|') -notmatch 'mutation') 'Draft lookup must be a read-only GraphQL query.'
                Assert-True ($a -contains 'owner=example' -and $a -contains 'name=project' -and $a -contains 'tag=v1.2.3') 'Draft lookup lost its explicit repository/tag.'
                if ($script:state.GraphQLFailure) { Write-ApiResponse @{ errors = @(@{ message = 'Not authorized' }) } $include; return }
                $pending = if ($script:state.Release) { @{ databaseId = $script:state.Release.id; isDraft = $script:state.Release.draft } } else { $null }
                Write-ApiResponse @{ data = @{ repository = @{ nameWithOwner = 'example/project'; release = $pending } } } $include
                return
            }
            { $_ -match '^repos/example/project/releases/[1-9][0-9]*$' } {
                if ($a -contains '--method') {
                    Assert-True (($a[2..6] -join '|') -ceq '--include|--method|PATCH|-F|draft=false' -and $a.Count -eq 7) 'Publication must only PATCH draft=false on the verified release ID.'
                    if ($script:state.BeforePublish) { & $script:state.BeforePublish }
                    if (-not $script:state.Release -or $endpoint -cne "repos/example/project/releases/$($script:state.Release.id)") {
                        Write-ApiResponse @{ message = 'Not Found' } $include 404
                        return
                    }
                    Complete-MockPublication
                    if ($LASTEXITCODE -ne 0) { return }
                }
                Assert-True ($script:state.Release -and $endpoint -ceq "repos/example/project/releases/$($script:state.Release.id)") 'Draft lookup requested the wrong release database ID.'
                Write-ApiResponse $script:state.Release $include
                return
            }
            { $_ -match '^repos/example/project/releases/assets/[1-9][0-9]*$' } {
                Assert-True (($a -join '|') -ceq "api|$endpoint|--method|DELETE") 'Only an asset removal may name an asset endpoint here.'
                $id = [int]($endpoint -split '/')[-1]
                $asset = @($script:state.Release.assets | Where-Object { $_.id -eq $id })
                Assert-True ($script:state.Release -and $script:state.Release.draft -and $asset.Count -eq 1) 'A removal targeted an asset outside the verified draft.'
                Assert-True ($asset[0].state -cne 'uploaded' -and $asset[0].name -cin $names) 'A removal targeted a finished or foreign asset.'
                $script:state.Release.assets = @($script:state.Release.assets | Where-Object { $_.id -ne $id })
                $script:state.RemoteBytes.Remove([string]$asset[0].name)
                return
            }
            { $_ -match '^repos/example/project/releases\?per_page=100&page=[1-9][0-9]*$' } {
                $items = @()
                if ($script:state.Release -and $endpoint -match 'page=1$') { $items += $script:state.Release }
                if ($endpoint -match 'page=1$') { $items += @($script:state.ExtraReleases) }
                if ($endpoint -match 'page=2$') { $items += @($script:state.SecondPageReleases) }
                if ($include) { "HTTP/2.0 200`nContent-Type: application/json`n`n" }
                ConvertTo-Json -InputObject $items -Depth 12 -Compress
                return
            }
            { $_ -match '^https://uploads\.github\.com/' } {
                $target = [regex]::Match($endpoint, '^https://uploads\.github\.com/repos/example/project/releases/([1-9][0-9]*)/assets\?name=([^&]+)$')
                Assert-True $target.Success 'Upload must go to the repository''s uploads host by release ID and asset name.'
                $name = [Uri]::UnescapeDataString($target.Groups[2].Value)
                Assert-True ((Get-Option $a '--method') -ceq 'POST' -and (Get-Option $a '-H') -ceq 'Content-Type: application/octet-stream') 'Upload lost its method or content type.'
                $path = Get-Option $a '--input'
                Assert-True ($script:state.Release -and $script:state.Release.draft -and $target.Groups[1].Value -ceq [string]$script:state.Release.id) 'An upload did not target the verified draft ID.'
                Assert-True ($name -cin $names -and (Split-Path -Leaf $path) -ceq $name -and -not $script:state.RemoteBytes.ContainsKey($name)) 'Upload would duplicate or replace an asset.'
                if ($name -ceq $script:state.SkipUpload) { return }
                Add-RemoteAsset $name ([IO.File]::ReadAllBytes($path))
                $script:state.Uploaded++
                if ($script:state.AfterUpload) { & $script:state.AfterUpload }
                if ($script:state.UploadFailureAfter -eq $script:state.Uploaded) { $global:LASTEXITCODE = 23 }
                return
            }
            'repos/example/project/immutable-releases' {
                if ($script:state.ImmutableSettingMissing) { Write-ApiResponse @{ message = 'Not Found' } $include 404 }
                else { Write-ApiResponse @{ enabled = [bool]$script:state.ImmutableEnabled; enforced_by_owner = $false } $include }
                return
            }
            'repos/example/project/commits/v1.2.3' {
                Assert-True ((Get-Option $a '--jq') -ceq '.sha') 'Tag lookup requested unnecessary commit data.'
                $script:state.ApiTagCommit
                return
            }
            default { throw "Unexpected GitHub endpoint: $endpoint" }
        }
    }
    if ($a[0] -ceq 'repo') {
        Assert-True (($a -join '|') -ceq 'repo|view|example/project|--json|description|--jq|.description') 'Description read lost its explicit repository.'
        if ($script:state.DescriptionFailure) { $global:LASTEXITCODE = 1; return }
        $script:state.Description
        return
    }
    Assert-True ($a[0] -ceq 'release') 'Unexpected GitHub command family.'
    if ($a[1] -in @('verify', 'verify-asset')) {
        Assert-True (($a[2..7] -join '|') -ceq '--repo|example/project|--format|json|--|v1.2.3') 'Attestation lost its explicit repository/tag.'
        if ($a[1] -ceq 'verify-asset') {
            Assert-True ($a.Count -eq 9 -and (Split-Path -Leaf $a[8]) -cin $names) 'Attestation lost its asset path boundary.'
        }
        if ($script:state.AttestationFailure -ceq $a[1]) { $global:LASTEXITCODE = 24; return }
        New-Attestation | ConvertTo-Json -Depth 12 -Compress
        return
    }
    Assert-True ($a.Count -ge 5 -and $a[2] -ceq 'v1.2.3' -and (Get-Option $a '-R') -ceq 'example/project') 'Publication lost its explicit repository/tag.'
    switch -Exact ($a[1]) {
        'create' {
            Assert-True (-not $script:state.Release -and $a -contains '--draft' -and $a -contains '--verify-tag') 'Create must only create a new draft with an existing tag.'
            Assert-True ((Get-Option $a '--target') -ceq $commit -and (Get-Option $a '--title') -ceq 'v1.2.3') 'Create target/title differs.'
            Assert-True ((Get-Option $a '--notes-file') -ceq $script:invoke.NotesPath -and $a.Count -eq 13) 'Create must not upload or publish implicitly.'
            $script:state.Release = New-Release
            if ($script:state.AfterCreate) { & $script:state.AfterCreate }
        }
        'edit' {
            # Reproduce the old CLI boundary, which reselected a draft by tag before PATCH.
            if ($script:state.BeforePublish) { & $script:state.BeforePublish }
            Assert-True ($script:state.Release.draft -and $a -contains '--draft=false' -and $a -contains '--verify-tag' -and $a.Count -eq 7) 'Only a verified existing draft may be published.'
            Complete-MockPublication
        }
        default { throw "Unexpected GitHub release command: $($a[1])" }
    }
}
function Save-PublicationAsset {
    param([string]$Repository, [object]$Asset, [string]$Path)
    $script:state.Calls.Add([pscustomobject]@{ File = 'gh'; Arguments = @('api', "repos/$Repository/releases/assets/$($Asset.id)", '-H', 'Accept: application/octet-stream') })
    Assert-True ($Repository -ceq 'example/project' -and $Asset.id -cin @($script:state.Release.assets.id)) 'Download did not go by an asset ID on the verified release.'
    $directory = Split-Path -Parent $Path
    if (-not $script:state.DownloadDirectories.Contains($directory)) {
        $script:state.DownloadDirectories.Add($directory)
        $script:state.LastDownload = @()
    }
    if ($script:state.DownloadFailure) { throw "gh api asset $($Asset.id) failed (exit 23): " }
    $name = [string]$Asset.name
    if ($name -ceq $script:state.OmitDownload) { return }
    $bytes = $script:state.RemoteBytes[$name]
    if ($name -ceq $script:state.CorruptDownload) { $bytes = [Text.Encoding]::UTF8.GetBytes('corrupt download') }
    [IO.File]::WriteAllBytes($Path, $bytes)
    $script:state.LastDownload += $Path
    if ($script:state.ExtraDownload) { [IO.File]::WriteAllText((Join-Path $directory 'unexpected.txt'), 'unexpected') }
}
function Complete-MockPublication {
    Assert-True $script:state.Release.draft 'Publication reached an already public release.'
    Assert-True ($script:state.LastDownload.Count -eq 3) 'Publication ran before all three assets downloaded.'
    foreach ($path in $script:state.LastDownload) {
        $expected = Join-Path $script:invoke.Assets (Split-Path -Leaf $path)
        Assert-True ((Get-FileHash -LiteralPath $path).Hash -ceq (Get-FileHash -LiteralPath $expected).Hash) 'Publication ran before matching download checks.'
    }
    if ($script:state.PublishFailure) { $global:LASTEXITCODE = 23; return }
    $script:state.Release.draft = $false
    # A draft may have come without the field at all; a published release always has it.
    $script:state.Release | Add-Member -NotePropertyName immutable -NotePropertyValue $script:state.ImmutableEnabled -Force
    $script:state.Release.published_at = '2026-10-12T12:00:00Z'
}
function Get-ReleaseWrites {
    return @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and (
        ($_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -in @('create', 'upload', 'edit')) -or
        ($_.Arguments[0] -ceq 'api' -and ($_.Arguments -ccontains 'PATCH' -or $_.Arguments -ccontains 'POST' -or $_.Arguments -ccontains 'DELETE'))) })
}
function Get-WriteKind([object]$Call) {
    if ($Call.Arguments[0] -ceq 'api') {
        if ($Call.Arguments -ccontains 'PATCH') { return 'publish' }
        if ($Call.Arguments -ccontains 'DELETE') { return 'delete' }
        return 'upload'
    }
    return $Call.Arguments[1]
}
function Assert-NoPublish {
    Assert-True (@(Get-ReleaseWrites | Where-Object { $_.Arguments[1] -ceq 'edit' -or $_.Arguments -contains 'PATCH' }).Count -eq 0) 'A failed check reached publication.'
    if ($script:state.Release) { Assert-True $script:state.Release.draft 'A failure changed a draft into a public release.' }
}
function Test-Case([string]$CaseName, [scriptblock]$Action) {
    Reset-Case
    try {
        & $Action 6>$null | Out-Null
        Assert-True (Test-Path -LiteralPath $controlFile -PathType Leaf) 'Publication cleanup removed an unrelated directory.'
        foreach ($directory in $script:state.DownloadDirectories) {
            Assert-True (-not (Test-Path -LiteralPath (Split-Path -Parent $directory))) 'Owned verification work directory leaked.'
        }
    } catch { throw "${CaseName}: $($_.Exception.Message)" }
    $script:caseCount++
}

try {
    Test-Case 'A replaced draft at publication cannot redirect the verified release ID' {
        Seed-Release
        $script:state.BeforePublish = {
            $script:state.Release = New-Release
            $script:state.Release.id = 43
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed|unexpected release identity'
        Assert-True ($script:state.Release.id -eq 43 -and $script:state.Release.draft -and $script:state.Release.assets.Count -eq 0) 'Publication exposed an unverified replacement draft.'
        $patches = @(Get-ReleaseWrites | Where-Object { $_.Arguments -contains 'PATCH' })
        Assert-True ($patches.Count -eq 1 -and $patches[0].Arguments[1] -ceq 'repos/example/project/releases/42') 'Publication did not target only the verified release ID.'
    }
    Test-Case 'A hosted tag moving at publication cannot return success' {
        $script:state.BeforePublish = { $script:state.ApiTagCommit = ('f' * 40) }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } "GitHub tag .* doesn't point at"
        Assert-True (-not $script:state.Release.draft) 'The tag movement was not injected at publication.'
    }
    Test-Case 'A hosted tag moving after attestation cannot return verification success' {
        Seed-Release -Published -Immutable
        $script:state.MetadataMutation = {
            if ($script:state.MetadataReads -eq 3) { $script:state.ApiTagCommit = ('f' * 40) }
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } "GitHub tag .* doesn't point at"
        $verified = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -ceq 'verify-asset' })
        Assert-True ($verified.Count -eq 3 -and (Get-ReleaseWrites).Count -eq 0) 'Final tag movement did not follow all asset verifications or caused a mutation.'
    }
    Test-Case 'New release downloads and verifies its draft before publication' {
        $result = Invoke-ReleasePublication @script:invoke
        Assert-True (-not $script:state.Release.draft -and -not $result.AlreadyPublished -and $result.VerifiedAssets -eq 3) 'New release was not verified and published.'
        Assert-True (((Get-ReleaseWrites | ForEach-Object { Get-WriteKind $_ }) -join '|') -ceq 'create|upload|upload|upload|publish') 'Publication writes changed order.'
    }
    foreach ($name in $names) {
        Test-Case "Missing local $name prevents remote writes" {
            Remove-Item -LiteralPath (Join-Path $script:invoke.Assets $name)
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'Cannot find path'
            Assert-True ($script:state.Calls.Count -eq 0) 'A missing local asset reached a command.'
        }
    }
    Test-Case 'Receipt must bind the expected source commit' {
        [IO.File]::WriteAllText((Join-Path $script:invoke.Assets $names[1]), '{"source":{"commit":"ffffffffffffffffffffffffffffffffffffffff"}}')
        Assert-Throws { Invoke-ReleasePublication @script:invoke } "receipt isn't for"
        Assert-True ($script:state.Calls.Count -eq 0) 'A mismatched receipt reached a command.'
    }
    Test-Case 'Checksum manifest must match both local assets' {
        [IO.File]::WriteAllText((Join-Path $script:invoke.Assets $names[2]), 'incorrect sums')
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'SHA256SUMS.txt does not match'
        Assert-True ($script:state.Calls.Count -eq 0) 'Incorrect sums reached a command.'
    }
    foreach ($name in $names) {
        Test-Case "Missing downloaded $name keeps the release draft" {
            $script:state.OmitDownload = $name
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'download is missing assets'
            Assert-NoPublish
        }
        Test-Case "Mismatched downloaded $name keeps the release draft" {
            $script:state.CorruptDownload = $name
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'hosted .* differs from the local'
            Assert-NoPublish
        }
    }
    Test-Case 'Extra downloaded files reject publication' {
        $script:state.ExtraDownload = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected files'
        Assert-NoPublish
    }
    Test-Case 'Download command failure keeps the release draft' {
        $script:state.DownloadFailure = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed \(exit 23\)'
        Assert-NoPublish
    }
    Test-Case 'Successful upload reporting a missing asset cannot publish' {
        $script:state.SkipUpload = $names[1]
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'exactly the three release assets'
        Assert-NoPublish
    }
    foreach ($count in @(0, 1, 2, 3)) {
        Test-Case "Existing draft with $count assets resumes without replacement" {
            $present = @($names | Select-Object -First $count)
            Seed-Release -AssetNames $present
            $result = Invoke-ReleasePublication @script:invoke
            Assert-True (-not $result.AlreadyPublished -and $script:state.Release.assets.Count -eq 3) 'Resumed draft did not publish all three assets.'
            $writes = @(Get-ReleaseWrites)
            Assert-True (@($writes | Where-Object { (Get-WriteKind $_) -ceq 'create' }).Count -eq 0) 'An existing draft was recreated.'
            $uploads = @($writes | Where-Object { (Get-WriteKind $_) -ceq 'upload' })
            if ($count -eq 3) { Assert-True ($uploads.Count -eq 0) 'A complete draft was uploaded again.' }
            else {
                Assert-True ($uploads.Count -eq (3 - $count)) 'Resume did not upload only missing assets.'
                foreach ($upload in $uploads) {
                    Assert-True ((Split-Path -Leaf (Get-Option $upload.Arguments '--input')) -cnotin $present) 'An existing draft asset was replaced.'
                }
            }
        }
    }
    Test-Case 'Corrupt partial draft asset prevents further uploads' {
        Seed-Release -AssetNames @($names[0])
        $script:state.Release.assets[0].digest = $null
        $script:state.RemoteBytes[$names[0]] = [Text.Encoding]::UTF8.GetBytes('wrong bundle bytes')
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'hosted .* differs from the local'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A corrupt partial draft received more uploads.'
        Assert-NoPublish
    }
    Test-Case 'Interrupted partial upload resumes safely on a second invocation' {
        $script:state.UploadFailureAfter = 1
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed \(exit 23\)'
        Assert-True ($script:state.Release.assets.Count -eq 1) 'The partial upload was not retained.'
        Assert-NoPublish
        $script:state.UploadFailureAfter = 0
        Invoke-ReleasePublication @script:invoke
        $writes = @(Get-ReleaseWrites)
        Assert-True (@($writes | Where-Object { (Get-WriteKind $_) -ceq 'create' }).Count -eq 1) 'Retry recreated the draft.'
        Assert-True (@($writes | Where-Object { (Get-WriteKind $_) -ceq 'upload' }).Count -eq 3) 'Retry did not resume the upload (one failed call, then the two missing assets).'
        Assert-True ($script:state.Release.assets.Count -eq 3 -and -not $script:state.Release.draft) 'Retry failed to publish the complete draft.'
    }
    Test-Case 'Unexpected existing asset stops without deleting or uploading' {
        Seed-Release -AssetNames @($names[0])
        Add-RemoteAsset 'unexpected.zip' ([byte[]]@(1, 2, 3))
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected, incomplete or mismatched asset'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'An unexpected asset caused a mutation.'
        Assert-NoPublish
    }
    foreach ($mutation in @(
        @{ Name = 'duplicate asset'; Change = { $script:state.Release.assets += $script:state.Release.assets[0] } },
        @{ Name = 'wrong size'; Change = { $script:state.Release.assets[0].size++ } },
        @{ Name = 'wrong digest'; Change = { $script:state.Release.assets[0].digest = ('sha256:' + ('0' * 64)) } },
        @{ Name = 'wrong filename case'; Change = { $script:state.Release.assets[0].name = $names[0].ToUpperInvariant() } }
    )) {
        Test-Case "Existing draft rejects $($mutation.Name)" {
            Seed-Release
            & $mutation.Change
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected, incomplete or mismatched asset'
            Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Invalid asset metadata caused a mutation.'
            Assert-NoPublish
        }
    }
    foreach ($mutation in @(
        @{ Name = 'target commit'; Change = { $script:state.Release.target_commitish = ('f' * 40) } },
        @{ Name = 'title'; Change = { $script:state.Release.name = 'unexpected title' } },
        @{ Name = 'notes'; Change = { $script:state.Release.body = 'unexpected notes' } },
        @{ Name = 'prerelease'; Change = { $script:state.Release.prerelease = $true } },
        @{ Name = 'immutable draft'; Change = { $script:state.Release.immutable = $true } }
    )) {
        Test-Case "Existing draft rejects unexpected $($mutation.Name)" {
            Seed-Release
            & $mutation.Change
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected release identity, target, notes or draft state'
            Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Unexpected draft identity caused a mutation.'
            Assert-NoPublish
        }
    }
    # GitHub ignores target_commitish once the tag exists, and the tag goes up before the draft.
    foreach ($shape in @(
        @{ Name = 'names the default branch'; Change = { $script:state.Release.target_commitish = 'main' } },
        @{ Name = 'leaves out its target'; Change = { $script:state.Release.PSObject.Properties.Remove('target_commitish') } },
        @{ Name = 'leaves out immutable'; Change = { $script:state.Release.PSObject.Properties.Remove('immutable') } }
    )) {
        Test-Case "A draft that $($shape.Name) is published by its verified tag" {
            Seed-Release -AssetNames @($names[0])
            & $shape.Change
            $result = Invoke-ReleasePublication @script:invoke
            Assert-True (-not $result.AlreadyPublished -and -not $script:state.Release.draft -and $script:state.Release.assets.Count -eq 3) 'A documented draft shape stopped publication.'
            Assert-True (((Get-ReleaseWrites | ForEach-Object { Get-WriteKind $_ }) -join '|') -ceq 'upload|upload|publish') 'A documented draft shape changed the writes.'
        }
    }
    Test-Case 'A draft created on an existing tag that reports the default branch publishes' {
        $script:state.LocalTag = $commit
        $script:state.RemoteTag = $commit
        $script:state.AfterCreate = { $script:state.Release.target_commitish = 'main'; $script:state.Release.PSObject.Properties.Remove('immutable') }
        $result = Invoke-ReleasePublication @script:invoke
        Assert-True (-not $script:state.Release.draft -and -not $result.AlreadyPublished) 'A fresh draft on an existing tag did not publish.'
        Assert-True (((Get-ReleaseWrites | ForEach-Object { Get-WriteKind $_ }) -join '|') -ceq 'create|upload|upload|upload|publish') 'Publication writes changed order.'
    }
    Test-Case 'A published release still has to say whether it is immutable' {
        Seed-Release -Published
        $script:state.Release.PSObject.Properties.Remove('immutable')
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'incomplete or mismatched release metadata'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A published release without immutability caused a write.'
    }
    # Not $state: at this scope that's the mock's own $script:state.
    foreach ($assetState in @('starter', 'open')) {
        Test-Case "An upload cut off as $assetState is removed from the draft and sent again" {
            Seed-Release
            $script:state.Release.assets[1].state = $assetState
            Assert-True ($script:state.Release.assets[1].state -ceq $assetState) 'The unfinished state was not set.'
            $unfinished = $script:state.Release.assets[1].id
            $result = Invoke-ReleasePublication @script:invoke
            Assert-True (-not $result.AlreadyPublished -and -not $script:state.Release.draft -and $script:state.Release.assets.Count -eq 3) 'The resumed draft did not publish all three assets.'
            Assert-True (@($script:state.Release.assets | Where-Object { $_.state -cne 'uploaded' -or $_.id -eq $unfinished }).Count -eq 0) 'The unfinished asset survived.'
            $writes = @(Get-ReleaseWrites)
            Assert-True ((($writes | ForEach-Object { Get-WriteKind $_ }) -join '|') -ceq 'delete|upload|publish') 'Resume did more than replace the unfinished upload.'
            Assert-True ($writes[0].Arguments[1] -ceq "repos/example/project/releases/assets/$unfinished") 'The removal missed the unfinished asset.'
            Assert-True ((Split-Path -Leaf (Get-Option $writes[1].Arguments '--input')) -ceq $names[1]) 'The wrong file was sent again.'
        }
    }
    Test-Case 'An interrupted upload left unfinished resumes on a second invocation' {
        $script:state.UploadFailureAfter = 2
        $script:state.AfterUpload = { if ($script:state.Uploaded -eq 2) { $script:state.Release.assets[-1].state = 'starter' } }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed \(exit 23\)'
        Assert-NoPublish
        $script:state.UploadFailureAfter = 0
        $script:state.AfterUpload = $null
        Invoke-ReleasePublication @script:invoke
        Assert-True (-not $script:state.Release.draft -and $script:state.Release.assets.Count -eq 3) 'The interrupted draft did not publish.'
        Assert-True (@(Get-ReleaseWrites | Where-Object { (Get-WriteKind $_) -ceq 'delete' }).Count -eq 1) 'The unfinished asset was not replaced exactly once.'
    }
    Test-Case 'An unfinished asset under another name still stops the run untouched' {
        Seed-Release -AssetNames @($names[0])
        Add-RemoteAsset 'unexpected.zip' ([byte[]]@(1, 2, 3))
        $script:state.Release.assets[-1].state = 'starter'
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected, incomplete or mismatched asset'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A foreign unfinished asset caused a mutation.'
        Assert-NoPublish
    }
    Test-Case 'An unfinished asset on a draft with other notes is never removed' {
        Seed-Release
        $script:state.Release.assets[0].state = 'starter'
        $script:state.Release.body = 'somebody else''s draft'
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected release identity'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'An asset left a draft whose identity failed.'
        Assert-NoPublish
    }
    Test-Case 'An unfinished asset on a published release is never removed' {
        Seed-Release -Published
        $script:state.Release.assets[0].state = 'starter'
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected, incomplete or mismatched asset'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A published release was changed.'
    }
    foreach ($property in @('LocalTag', 'RemoteTag', 'ApiTagCommit', 'Head')) {
        Test-Case "Reject mismatched $property before mutation" {
            Seed-Release
            $script:state[$property] = ('f' * 40)
            Assert-Throws { Invoke-ReleasePublication @script:invoke } "doesn't point at|HEAD changed"
            Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A mismatched tag/commit caused a release mutation.'
            Assert-NoPublish
        }
    }
    Test-Case 'Missing remote tag on an existing release is never recreated' {
        Seed-Release
        $script:state.RemoteTag = $null
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'Remote tag .* is missing'
        Assert-True (@($script:state.Calls | Where-Object { $_.File -ceq 'git' -and $_.Arguments[2] -ceq 'push' }).Count -eq 0) 'An existing release caused a tag push.'
        Assert-NoPublish
    }
    Test-Case 'Annotated remote tag is checked by its peeled commit' {
        Seed-Release
        $script:state.RemoteTagObject = ('a' * 40)
        Invoke-ReleasePublication @script:invoke
        Assert-True (-not $script:state.Release.draft) 'An annotated tag with the right commit was rejected.'
    }
    Test-Case 'Git errors cannot become absent-tag success' {
        $script:state.GitFailure = 'ls-remote|--tags|origin|refs/tags/v1.2.3|refs/tags/v1.2.3^{}'
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed \(exit 23\)'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A git error caused release creation.'
    }
    Test-Case 'API errors other than 404 cannot create another release' {
        $script:state.ApiFailure = 403
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'gh api .* failed'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'An API error caused release creation.'
    }
    Test-Case 'Invalid API JSON cannot create a release' {
        $script:state.InvalidApiJson = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'invalid JSON'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Invalid JSON caused release creation.'
    }
    Test-Case 'GraphQL errors cannot become an absent draft' {
        $script:state.GraphQLFailure = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'Draft release lookup failed'
        Assert-True ((Get-ReleaseWrites).Count -eq 0 -and -not $script:state.LocalTag) 'A draft lookup failure caused a mutation.'
    }
    foreach ($mutation in @(
        @{ Name = 'release id'; Change = { $script:state.Release.id = 43 } },
        @{ Name = 'asset id'; Change = { $script:state.Release.assets[0].id = 999 } },
        @{ Name = 'notes'; Change = { $script:state.Release.body = 'changed during download' } }
    )) {
        Test-Case "Reject changed $($mutation.Name) after downloads" {
            Seed-Release
            $script:state.MetadataMutation = {
                if ($script:state.MetadataReads -eq 2) { & $mutation.Change }
            }
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'unexpected release identity|assets changed during download'
            Assert-NoPublish
        }
    }
    foreach ($published in @($false, $true)) {
        Test-Case "Local file changing during verification fails (published=$published)" {
            Seed-Release -Published:$published
            $script:state.MetadataMutation = {
                if ($script:state.MetadataReads -eq 2) {
                    [IO.File]::WriteAllText((Join-Path $script:invoke.Assets $names[0]), 'changed during verification')
                }
            }
            Assert-Throws { Invoke-ReleasePublication @script:invoke } 'Local release asset changed'
            Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A local file change caused a remote mutation.'
        }
    }
    Test-Case 'Failed publish command leaves a verified draft that can be retried' {
        $script:state.PublishFailure = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'failed \(exit 23\)'
        Assert-True ($script:state.Release.draft -and $script:state.Release.assets.Count -eq 3) 'Failed publication lost the verified draft.'
        $script:state.PublishFailure = $false
        Invoke-ReleasePublication @script:invoke
        Assert-True (@(Get-ReleaseWrites | Where-Object { (Get-WriteKind $_) -ceq 'upload' }).Count -eq 3) 'Publication retry uploaded assets again.'
    }
    Test-Case 'Existing published release is verified without mutations' {
        Seed-Release -Published
        $result = Invoke-ReleasePublication @script:invoke
        Assert-True ($result.AlreadyPublished -and $result.VerifiedAssets -eq 3) 'Published release was not verified.'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Published release was mutated.'
        Assert-True (@($script:state.Calls | Where-Object { $_.File -ceq 'git' -and $_.Arguments[2] -in @('push', 'tag') -and $_.Arguments[3] -cne '--list' }).Count -eq 0) 'Published release changed a tag.'
    }
    Test-Case 'Legacy published branch target uses the verified tag commit without mutation' {
        Seed-Release -Published
        $script:state.Release.target_commitish = 'main'
        $result = Invoke-ReleasePublication @script:invoke
        Assert-True ($result.AlreadyPublished -and (Get-ReleaseWrites).Count -eq 0) 'Legacy target metadata prevented read-only published verification.'
    }
    Test-Case 'Published tag changing during downloads is rejected without mutation' {
        Seed-Release -Published
        $script:state.MetadataMutation = {
            if ($script:state.MetadataReads -eq 2) { $script:state.RemoteTag = ('f' * 40) }
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } "Remote tag .* doesn't point at"
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A changed published tag caused a mutation.'
    }
    Test-Case 'Published release changing during final verification is rejected without mutation' {
        Seed-Release -Published
        $script:state.MetadataMutation = {
            if ($script:state.MetadataReads -eq 3) { $script:state.Release.assets[0].id = 999 }
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'assets changed during final verification'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Changed final metadata caused a mutation.'
    }
    Test-Case 'Incomplete published release is rejected without uploads' {
        Seed-Release -Published -AssetNames @($names[0], $names[1])
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'exactly the three release assets'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'An incomplete published release received assets.'
    }
    Test-Case 'Repeated publication verifies rather than publishing twice' {
        Invoke-ReleasePublication @script:invoke
        $writes = (Get-ReleaseWrites).Count
        $result = Invoke-ReleasePublication @script:invoke
        Assert-True ($result.AlreadyPublished -and (Get-ReleaseWrites).Count -eq $writes) 'Repeated publication mutated the published release.'
    }
    Test-Case 'Explicit immutable requirement stops before writes when setting is disabled' {
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } 'must already be enabled'
        Assert-True ((Get-ReleaseWrites).Count -eq 0 -and -not $script:state.LocalTag -and -not $script:state.RemoteTag) 'Disabled immutability still changed tags or releases.'
    }
    Test-Case 'Explicit immutable requirement stops before writes when the setting endpoint is missing' {
        $script:state.ImmutableSettingMissing = $true
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } 'must already be enabled'
        Assert-True ((Get-ReleaseWrites).Count -eq 0 -and -not $script:state.LocalTag -and -not $script:state.RemoteTag) 'Missing immutability setting still changed tags or releases.'
    }
    Test-Case 'Disabling immutability during verification keeps the release draft' {
        $script:state.ImmutableEnabled = $true
        $script:state.MetadataMutation = {
            if ($script:state.MetadataReads -eq 4) { $script:state.ImmutableEnabled = $false }
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } 'must already be enabled'
        Assert-NoPublish
    }
    Test-Case 'Explicit immutable requirement rejects an existing mutable release' {
        Seed-Release -Published
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } 'is mutable'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Immutable requirement mutated an existing mutable release.'
    }
    foreach ($require in @($false, $true)) {
        Test-Case "Immutable publication verifies all three signed asset identities (required=$require)" {
            $script:state.ImmutableEnabled = $true
            $result = Invoke-ReleasePublication @script:invoke -RequireImmutableRelease:$require
            $verified = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -ceq 'verify' })
            $assetsVerified = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -ceq 'verify-asset' })
            Assert-True ($result.Immutable -and $verified.Count -eq 3 -and $assetsVerified.Count -eq 3) 'Immutable attestation did not verify every asset.'
        }
    }
    Test-Case 'Mutable publication skips attestation commands' {
        Invoke-ReleasePublication @script:invoke
        Assert-True (@($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -in @('verify', 'verify-asset') }).Count -eq 0) 'A mutable release invoked unavailable attestation commands.'
    }
    Test-Case 'Attestation failure can be retried without changing the published release' {
        Seed-Release -Published -Immutable
        $script:state.AttestationFailure = 'verify-asset'
        Assert-Throws { Invoke-ReleasePublication @script:invoke -RequireImmutableRelease } 'failed \(exit 24\)'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'An attestation failure mutated a published release.'
        $script:state.AttestationFailure = ''
        $result = Invoke-ReleasePublication @script:invoke -RequireImmutableRelease
        Assert-True ($result.AlreadyPublished -and $result.Immutable -and (Get-ReleaseWrites).Count -eq 0) 'Attestation retry changed the release.'
    }
    function Add-StrayDraft([int]$Id = 77) {
        $stray = New-Release
        $stray.id = $Id
        $script:state.ExtraReleases += $stray
    }
    function Assert-NoAssetTransfer {
        $transfers = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and ($_.Arguments -contains 'POST' -or
            $_.Arguments[1] -match '^repos/example/project/releases/assets/') })
        Assert-True ($transfers.Count -eq 0) 'Assets moved although the tag has two releases.'
    }
    Test-Case 'A stray second draft on the tag stops a resume before any download or upload' {
        Seed-Release -AssetNames @($names[0])
        Add-StrayDraft
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'More than one release has the tag v1.2.3 \(.*77'
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A duplicate draft still received writes.'
        Assert-NoAssetTransfer
        Assert-NoPublish
    }
    Test-Case 'A stray draft beside a published release stops verification' {
        Seed-Release -Published
        Add-StrayDraft
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'More than one release has the tag'
        Assert-NoAssetTransfer
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A duplicate beside a published release caused a write.'
    }
    Test-Case 'A second draft appearing after the downloads blocks publication' {
        Seed-Release
        $script:state.MetadataMutation = { if ($script:state.MetadataReads -eq 2) { Add-StrayDraft } }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'More than one release has the tag'
        Assert-NoPublish
    }
    function New-OtherRelease([int]$Id) {
        $other = New-Release -Draft $false
        $other.id = $Id
        $other.tag_name = "v0.0.$Id"
        $other.name = $other.tag_name
        return $other
    }
    # Windows PowerShell 5.1 hands the parsed release list on as one object; read as one release,
    # every other tag in it looked like a duplicate of this one.
    Test-Case 'Releases under other tags leave the duplicate check alone' {
        $script:state.ExtraReleases = @((New-OtherRelease 41), (New-OtherRelease 40))
        Invoke-ReleasePublication @script:invoke
        Assert-True (@(Get-ReleaseWrites | Where-Object { (Get-WriteKind $_) -ceq 'publish' }).Count -eq 1) 'Other tags in the list stopped publication.'
    }
    Test-Case 'A stray draft on the second page of releases still stops publication' {
        Seed-Release -AssetNames @($names[0])
        $script:state.ExtraReleases = @(1..99 | ForEach-Object { New-OtherRelease $_ })
        $stray = New-Release
        $stray.id = 177
        $script:state.SecondPageReleases = @($stray)
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'More than one release has the tag v1.2.3 \(.*177'
        Assert-NoAssetTransfer
        Assert-NoPublish
    }
    Test-Case 'Uploads and downloads go to the verified release and asset IDs, never the tag' {
        Invoke-ReleasePublication @script:invoke
        $byTag = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[0] -ceq 'release' -and $_.Arguments[1] -in @('upload', 'download') })
        Assert-True ($byTag.Count -eq 0) 'A release upload or download reselected the release by tag.'
        $uploads = @(Get-ReleaseWrites | Where-Object { (Get-WriteKind $_) -ceq 'upload' })
        Assert-True ($uploads.Count -eq 3 -and @($uploads | Where-Object { $_.Arguments[1] -notmatch '/releases/42/assets\?name=' }).Count -eq 0) 'An upload missed the verified release ID.'
        $downloads = @($script:state.Calls | Where-Object { $_.File -ceq 'gh' -and $_.Arguments[1] -match '^repos/example/project/releases/assets/[1-9][0-9]*$' })
        Assert-True ($downloads.Count -ge 3) 'Hosted assets were not downloaded by asset ID.'
    }
    function Get-StageAction([string]$Stage) {
        $switch = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.SwitchStatementAst] -and $node.Condition.Extent.Text -ceq '$Stage'
        }, $false)
        $clause = @($switch.Clauses | Where-Object { $_.Item1.Extent.Text -ceq "'$Stage'" })[0]
        $body = $clause.Item2.Extent.Text
        return [scriptblock]::Create($body.Substring(1, $body.Length - 2))
    }
    foreach ($case in @(
        @{ Name = 'a published tag'; Seed = { Seed-Release -Published }; Refuses = $true },
        @{ Name = 'an unpublished draft'; Seed = { Seed-Release }; Refuses = $false },
        @{ Name = 'no release yet'; Seed = { }; Refuses = $false }
    )) {
        Test-Case "Actual build stage vs $($case.Name)" {
            & $case.Seed
            $Repository = 'example/project'
            $tag = 'v1.2.3'
            $Version = '1.2.3'
            function Assert-PushedHead { return $commit }
            function Get-SourceVersion { return '1.2.3' }
            function Invoke-Gradle { throw 'REACHED-GRADLE' }
            $buildAction = Get-StageAction 'build'
            if ($case.Refuses) { Assert-Throws { & $buildAction } 'already published' }
            else { Assert-Throws { & $buildAction } 'REACHED-GRADLE' }
            Assert-True ((Get-ReleaseWrites).Count -eq 0) 'The build stage wrote to the release.'
        }
    }
    function Initialize-IndexCase {
        $script:Repository = 'example/project'
        $script:tag = 'v1.2.3'
        $script:Version = '1.2.3'
        $script:ReadmeSummary = 'summary.txt'
        $script:BundleSummary = 'summary.txt'
        $script:root = $script:invoke.Root
        $script:assets = $script:invoke.Assets
        $script:receiptName = $names[1]
        [IO.File]::WriteAllText((Join-Path $script:root 'patches-list.json'), '{"patches":[{},{}]}')
    }
    Test-Case 'A rebuilt receipt stops the index before it is copied into the repo' {
        Seed-Release -Published
        Initialize-IndexCase
        [IO.File]::WriteAllText((Join-Path $assets $names[1]), (@{ source = @{ commit = $commit }; rebuilt = $true } | ConvertTo-Json -Depth 3))
        function Assert-PushedHead { return $commit }
        function Get-TestCount { return 1 }
        function Invoke-Native { throw 'A rebuilt receipt reached an index mutation.' }
        Assert-Throws { & (Get-StageAction 'index') } 'differs from the hosted one'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root $names[1]))) 'A rebuilt receipt was copied into the repo.'
    }
    Test-Case 'A hosted receipt without a digest cannot be compared, so the index stops' {
        Seed-Release -Published
        Initialize-IndexCase
        $script:state.Release.assets | Where-Object { $_.name -ceq $names[1] } | ForEach-Object { $_.digest = $null }
        function Assert-PushedHead { return $commit }
        function Get-TestCount { return 1 }
        function Invoke-Native { throw 'An uncomparable receipt reached an index mutation.' }
        Assert-Throws { & (Get-StageAction 'index') } 'no single hosted'
        Assert-True (-not (Test-Path -LiteralPath (Join-Path $root $names[1]))) 'An uncomparable receipt was copied into the repo.'
    }
    Test-Case 'Index rejects a draft even when it has a published timestamp' {
        $release = New-Release
        $release.published_at = '2026-10-12T12:00:00Z'
        Assert-Throws { Get-PublishedReleaseDate -Release $release -Tag 'v1.2.3' } "isn't published"
        $release.draft = $false
        Assert-True ((Get-PublishedReleaseDate -Release $release -Tag 'v1.2.3') -ceq '2026-10-12T12:00:00') 'Published index date changed.'
    }
    Test-Case 'Index date stays in UTC whether the JSON reader returns a date or a string' {
        # PowerShell 7 turns published_at into a DateTime and 5.1 leaves a string, so cover both shapes
        # explicitly instead of depending on which shell is running.
        $parsed = '{"tag_name":"v1.2.3","draft":false,"published_at":"2026-10-12T12:00:00Z"}' | ConvertFrom-Json
        $instant = [DateTime]::new(2026, 10, 12, 12, 0, 0, [DateTimeKind]::Utc)
        foreach ($value in @($parsed.published_at, '2026-10-12T12:00:00Z', $instant, $instant.ToLocalTime())) {
            $release = [pscustomobject]@{ tag_name = 'v1.2.3'; draft = $false; published_at = $value }
            Assert-True ((Get-PublishedReleaseDate -Release $release -Tag 'v1.2.3') -ceq '2026-10-12T12:00:00') "Index date moved for a $($value.GetType().Name) published_at."
        }
    }
    function Get-IndexAction {
        $switch = $ast.Find({ param($node)
            $node -is [Management.Automation.Language.SwitchStatementAst] -and $node.Condition.Extent.Text -ceq '$Stage'
        }, $false)
        $clause = @($switch.Clauses | Where-Object { $_.Item1.Extent.Text -ceq "'index'" })[0]
        $body = $clause.Item2.Extent.Text
        return [scriptblock]::Create($body.Substring(1, $body.Length - 2))
    }
    Test-Case 'Actual index stage rejects drafts before indexing or building' {
        Seed-Release
        $script:state.Release.published_at = '2026-10-12T12:00:00Z'
        $Repository = 'example/project'
        $tag = 'v1.2.3'
        $ReadmeSummary = 'summary.txt'
        $BundleSummary = 'summary.txt'
        function Assert-PushedHead { return $commit }
        function Get-TestCount { throw 'A draft reached test-result collection.' }
        function Invoke-Native { throw 'A draft reached an index mutation.' }
        $indexAction = Get-IndexAction
        Assert-Throws { & $indexAction } "isn't published"
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'Draft indexing caused a release mutation.'
    }
    Test-Case 'A failed description read stops the index before anything is written' {
        Seed-Release -Published
        $script:state.DescriptionFailure = $true
        $Repository = 'example/project'
        $tag = 'v1.2.3'
        $ReadmeSummary = 'summary.txt'
        $BundleSummary = 'summary.txt'
        function Assert-PushedHead { return $commit }
        function Get-TestCount { throw 'A failed description read reached test-result collection.' }
        function Invoke-Native { throw 'A failed description read reached an index mutation.' }
        $indexAction = Get-IndexAction
        Assert-Throws { & $indexAction } "Couldn't read the example/project description"
        Assert-True ((Get-ReleaseWrites).Count -eq 0) 'A failed description read changed the release.'
    }
    Test-Case 'Index stage writes the UTC date and the new version into the description' {
        Seed-Release -Published
        $Repository = 'example/project'
        $tag = 'v1.2.3'
        $Version = '1.2.3'
        $ReadmeSummary = 'summary.txt'
        $BundleSummary = 'summary.txt'
        $root = $script:invoke.Root
        $assets = $script:invoke.Assets
        $receiptName = $names[1]
        [IO.File]::WriteAllText((Join-Path $root 'patches-list.json'), '{"patches":[{},{}]}')
        $native = New-Object 'System.Collections.Generic.List[string]'
        function Assert-PushedHead { return $commit }
        function Get-TestCount { return 1 }
        function Invoke-Native([string]$What, [scriptblock]$Command) { $native.Add($What) }
        . (Get-IndexAction)
        Assert-True ($created -ceq '2026-10-12T12:00:00') "Index date was $created."
        Assert-True ($updated -ceq 'Hushfeed v1.2.3 for TikTok, 2 patches') "Description became '$updated'."
        Assert-True (($native -join '|') -ceq 'index|repository description') 'Index stage skipped or reordered its writes.'
    }
    Test-Case 'Changed local release notes stop before publication' {
        Seed-Release
        $script:state.MetadataMutation = {
            if ($script:state.MetadataReads -eq 2) { [IO.File]::WriteAllText($script:invoke.NotesPath, 'changed notes') }
        }
        Assert-Throws { Invoke-ReleasePublication @script:invoke } 'Local release notes changed'
        Assert-NoPublish
    }
    Test-Case 'Native command exit codes and stderr cannot pass as success' {
        $native = Join-Path $script:invoke.Root 'native boundary with spaces.cmd'
        [IO.File]::WriteAllText($native, @'
@echo off
if not "%~1"=="argument with spaces" exit /b 87
echo native-stdout
echo native-stderr 1>&2
exit /b 23
'@)
        $response = Invoke-PublicationCommand $native @('argument with spaces') -AllowFailure
        Assert-True ($response.ExitCode -eq 23 -and $response.Text -match 'native-stdout' -and $response.Text -match 'native-stderr') 'The native boundary lost output or its exit code.'
        Assert-Throws { Invoke-PublicationCommand $native @('argument with spaces') } 'failed \(exit 23\)'
    }
} finally {
    Remove-GeneratedPath -Root $temporaryRoot -Path $testRoot
    Remove-GeneratedPath -Root $temporaryRoot -Path $controlRoot
}
Write-Host "[release-publication] $script:caseCount contracts passed"
