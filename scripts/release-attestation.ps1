<#
.SYNOPSIS
    Verify an immutable release's signed tag, commit and asset identity with GitHub CLI.
.DESCRIPTION
    Mutable releases have no release attestation and are skipped unless explicitly required.
    This verifies release identity and file integrity. It does not attest to compilation.
#>

function Invoke-ReleaseAttestationGh {
    param([string]$GhPath, [string[]]$Arguments)

    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    $global:LASTEXITCODE = 0
    try {
        $output = & $GhPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($exitCode -ne 0) {
        throw "gh $($Arguments[0]) $($Arguments[1]) failed (exit $exitCode): $($output -join ' ')"
    }
    try {
        return ($output -join "`n") | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "gh $($Arguments[0]) $($Arguments[1]) returned invalid JSON: $($_.Exception.Message)"
    }
}

function Assert-ReleaseAttestationStatement {
    param(
        [object]$Result,
        [string]$Repository,
        [string]$Tag,
        [string]$ReleaseId,
        [string]$ExpectedCommit,
        [string]$AssetName,
        [string]$AssetHash,
        [string]$Operation
    )

    # Inspect only the CLI's cryptographically verified statement, never the raw bundle payload.
    $statement = $Result.verificationResult.statement
    if ($null -eq $statement -or $statement._type -cne 'https://in-toto.io/Statement/v1' -or
        $statement.predicateType -cne 'https://in-toto.io/attestation/release/v0.2') {
        throw "$Operation did not return a verified release statement."
    }
    $predicate = $statement.predicate
    if ($predicate.repository -ine $Repository -or $predicate.tag -cne $Tag -or
        [string]$predicate.databaseId -cne $ReleaseId) {
        throw "$Operation attests to a different repository, tag or release."
    }
    $purl = "pkg:github/$($predicate.repository)@$Tag"
    if ($predicate.purl -cne $purl) {
        throw "$Operation has a different release package identity."
    }
    $releaseSubjects = @($statement.subject | Where-Object { $_.uri -ceq $purl })
    $commitAlgorithm = if ($ExpectedCommit.Length -eq 40) { 'sha1' } else { 'sha256' }
    if ($releaseSubjects.Count -ne 1 -or
        [string]$releaseSubjects[0].digest.$commitAlgorithm -ine $ExpectedCommit) {
        throw "$Operation attests to a different commit or has no unique release subject."
    }
    $assetSubjects = @($statement.subject | Where-Object { $_.name -ceq $AssetName })
    if ($assetSubjects.Count -ne 1 -or [string]$assetSubjects[0].digest.sha256 -ine $AssetHash) {
        throw "$Operation attests to a different asset hash or has no unique asset subject."
    }
}

function Test-ReleaseAttestation {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
        [string]$Repository,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$Tag,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$ArtifactPath,
        [Parameter(Mandatory = $true)][ValidateNotNullOrEmpty()][string]$AssetName,
        [Parameter(Mandatory = $true)]
        [ValidatePattern('^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$')]
        [string]$ExpectedCommit,
        [switch]$RequireImmutableRelease,
        [string]$GhPath = 'gh'
    )

    $artifact = Get-Item -LiteralPath $ArtifactPath -ErrorAction Stop
    if ($artifact.PSIsContainer) { throw "The release asset is not a file: $ArtifactPath" }
    $endpoint = "repos/$Repository/releases/tags/$([Uri]::EscapeDataString($Tag))"
    $release = Invoke-ReleaseAttestationGh -GhPath $GhPath -Arguments @('api', $endpoint)
    if ($release.tag_name -cne $Tag -or $release.draft -isnot [bool] -or $release.draft -or
        $release.immutable -isnot [bool] -or [string]$release.id -notmatch '^[1-9][0-9]*$') {
        throw "GitHub returned incomplete or mismatched release metadata for $Repository $Tag."
    }
    if (-not $release.immutable) {
        if ($RequireImmutableRelease) {
            throw "Release $Repository $Tag is mutable. An immutable release is required."
        }
        Write-Host "[release] $Repository $Tag is mutable; release attestation verification skipped"
        return [pscustomobject]@{ Immutable = $false; Verified = $false; Repository = $Repository; Tag = $Tag }
    }

    $assetHash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $expected = @{
        Repository = $Repository; Tag = $Tag; ReleaseId = [string]$release.id
        ExpectedCommit = $ExpectedCommit; AssetName = $AssetName; AssetHash = $assetHash
    }
    $verifiedRelease = Invoke-ReleaseAttestationGh -GhPath $GhPath -Arguments @(
        'release', 'verify', '--repo', $Repository, '--format', 'json', '--', $Tag
    )
    Assert-ReleaseAttestationStatement -Result $verifiedRelease @expected -Operation 'gh release verify'
    $verifiedAsset = Invoke-ReleaseAttestationGh -GhPath $GhPath -Arguments @(
        'release', 'verify-asset', '--repo', $Repository, '--format', 'json', '--', $Tag, $artifact.FullName
    )
    Assert-ReleaseAttestationStatement -Result $verifiedAsset @expected -Operation 'gh release verify-asset'
    # A file changed during the verification must not be reported as the file that was verified.
    if ((Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash -ine $assetHash) {
        throw 'The release asset changed during attestation verification.'
    }
    Write-Host "[release] immutable release attestation verifies $Tag ($ExpectedCommit) and $AssetName; sha256=$assetHash"
    return [pscustomobject]@{
        Immutable = $true; Verified = $true; Repository = $Repository; Tag = $Tag
        Commit = $ExpectedCommit; Asset = $AssetName; Sha256 = $assetHash
    }
}
