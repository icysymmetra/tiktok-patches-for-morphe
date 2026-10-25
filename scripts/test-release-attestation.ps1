<#
.SYNOPSIS
    Exercise immutable release verification without a network or changing any release.
#>
[CmdletBinding()]
param([string]$Root)

$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'common.ps1')
. (Join-Path $PSScriptRoot 'release-attestation.ps1')

$temporaryRoot = [IO.Path]::GetTempPath()
$testRoot = Resolve-WithinRoot -Root $temporaryRoot -Path (
    Join-Path $temporaryRoot ("hushfeed-attestation-$([Guid]::NewGuid())"))
$null = New-Item -ItemType Directory -Path $testRoot
$artifact = Join-Path $testRoot 'bundle with spaces.mpp'
[IO.File]::WriteAllText($artifact, 'local release asset')
$assetHash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
$commit = '0123456789abcdef0123456789abcdef01234567'
$assetName = 'hushfeed-v1.2.3.mpp'
$baseArguments = @{
    Repository = 'example/project'; Tag = 'v1.2.3'; ArtifactPath = $artifact
    AssetName = $assetName; ExpectedCommit = $commit
}
$script:caseCount = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

function Assert-Throws {
    param([scriptblock]$Run, [string]$Pattern)
    $message = $null
    try { & $Run | Out-Null } catch { $message = $_.Exception.Message }
    if ($null -eq $message -or $message -notmatch $Pattern) {
        throw "Expected an error matching '$Pattern', got '$message'."
    }
}

function Test-Case {
    param([string]$Name, [scriptblock]$Run)
    try { & $Run | Out-Null } catch { throw "${Name}: $($_.Exception.Message)" }
    $script:caseCount++
}

function New-Metadata {
    return [pscustomobject]@{ id = 42; tag_name = 'v1.2.3'; draft = $false; immutable = $true }
}

function New-VerifiedResult {
    return [pscustomobject]@{
        verificationResult = [pscustomobject]@{
            statement = [pscustomobject]@{
                _type = 'https://in-toto.io/Statement/v1'
                predicateType = 'https://in-toto.io/attestation/release/v0.2'
                predicate = [pscustomobject]@{
                    repository = 'example/project'; tag = 'v1.2.3'; databaseId = '42'
                    purl = 'pkg:github/example/project@v1.2.3'
                }
                subject = @(
                    [pscustomobject]@{ uri = 'pkg:github/example/project@v1.2.3'; digest = @{ sha1 = $commit } }
                    [pscustomobject]@{ name = $assetName; digest = @{ sha256 = $assetHash } }
                )
            }
        }
    }
}

try {
    # A native command fixture checks argument boundaries, JSON handling and actual exit codes.
    # The asset path contains spaces. The fixture rejects any split or reordered argument.
    $mockGh = Join-Path $testRoot 'mock gh.cmd'
    $nativeCommand = @'
@echo off
if "%~1"=="api" goto metadata
if not "%~1"=="release" exit /b 87
if not "%~3"=="--repo" exit /b 87
if not "%~4"=="example/project" exit /b 87
if not "%~5"=="--format" exit /b 87
if not "%~6"=="json" exit /b 87
if not "%~7"=="--" exit /b 87
if not "%~8"=="v1.2.3" exit /b 87
if "%~2"=="verify" goto verify
if "%~2"=="verify-asset" goto asset
exit /b 87
:metadata
if not "%~2"=="repos/example/project/releases/tags/v1.2.3" exit /b 87
if exist "%~dp0fail-api" exit /b 23
type "%~dp0metadata.json"
exit /b 0
:verify
if not "%~9"=="" exit /b 87
if exist "%~dp0fail-verify" exit /b 24
type "%~dp0verified.json"
exit /b 0
:asset
if not "%~9"=="%~dp0bundle with spaces.mpp" exit /b 87
if exist "%~dp0fail-verify-asset" exit /b 25
type "%~dp0verified.json"
exit /b 0
'@
    [IO.File]::WriteAllText($mockGh, $nativeCommand)
    [IO.File]::WriteAllText((Join-Path $testRoot 'metadata.json'), (New-Metadata | ConvertTo-Json))
    [IO.File]::WriteAllText((Join-Path $testRoot 'verified.json'), (New-VerifiedResult | ConvertTo-Json -Depth 10))
    Test-Case 'Native immutable verification with spaced paths' {
        $result = Test-ReleaseAttestation @baseArguments -GhPath $mockGh -RequireImmutableRelease
        Assert-True ($result.Verified -and $result.Immutable -and $result.Sha256 -ceq $assetHash) 'The immutable file was not verified.'
    }
    foreach ($failure in @('api', 'verify', 'verify-asset')) {
        Test-Case "Native $failure failure cannot pass" {
            $marker = Join-Path $testRoot "fail-$failure"
            [IO.File]::WriteAllText($marker, '')
            try {
                Assert-Throws { Test-ReleaseAttestation @baseArguments -GhPath $mockGh } 'failed \(exit 2[345]\)'
            } finally { Remove-Item -LiteralPath $marker -Force }
        }
    }
    Test-Case 'Native invalid JSON cannot pass' {
        $path = Join-Path $testRoot 'verified.json'
        [IO.File]::WriteAllText($path, 'not JSON')
        try {
            Assert-Throws { Test-ReleaseAttestation @baseArguments -GhPath $mockGh } 'returned invalid JSON'
        } finally { [IO.File]::WriteAllText($path, (New-VerifiedResult | ConvertTo-Json -Depth 10)) }
    }

    # Mock the JSON boundary so each signed identity failure can be isolated in both commands.
    function Invoke-ReleaseAttestationGh {
        param([string]$GhPath, [string[]]$Arguments)
        $script:calls.Add([string[]]$Arguments)
        if ($script:responses.Count -eq 0) { throw 'An unexpected verification command was called.' }
        $response = $script:responses.Dequeue()
        if ($response -is [scriptblock]) { return & $response }
        return $response
    }
    function Set-Responses {
        param([object[]]$Values)
        $script:calls = New-Object 'System.Collections.Generic.List[object]'
        $script:responses = New-Object 'System.Collections.Generic.Queue[object]'
        foreach ($value in $Values) { $script:responses.Enqueue($value) }
    }
    Test-Case 'Mutable release skips both attestation commands' {
        $metadata = New-Metadata
        $metadata.immutable = $false
        Set-Responses @($metadata)
        $result = Test-ReleaseAttestation @baseArguments
        Assert-True (-not $result.Verified -and -not $result.Immutable -and $script:calls.Count -eq 1) 'Mutable release did not skip verification.'
    }
    Test-Case 'Requiring an immutable release rejects a mutable release' {
        $metadata = New-Metadata
        $metadata.immutable = $false
        Set-Responses @($metadata)
        Assert-Throws { Test-ReleaseAttestation @baseArguments -RequireImmutableRelease } 'is mutable'
        Assert-True ($script:calls.Count -eq 1) 'A mutable release invoked an attestation command.'
    }
    foreach ($badMetadata in @(
        @{ Property = 'immutable'; Value = 'false' },
        @{ Property = 'immutable'; Value = $null },
        @{ Property = 'draft'; Value = $true },
        @{ Property = 'tag_name'; Value = 'v1.2.4' },
        @{ Property = 'id'; Value = $null }
    )) {
        Test-Case "Reject incomplete metadata $($badMetadata.Property)=$($badMetadata.Value)" {
            $metadata = New-Metadata
            $metadata.($badMetadata.Property) = $badMetadata.Value
            Set-Responses @($metadata)
            Assert-Throws { Test-ReleaseAttestation @baseArguments } 'incomplete or mismatched release metadata'
            Assert-True ($script:calls.Count -eq 1) 'Invalid metadata reached verification.'
        }
    }
    Test-Case 'Immutable releases invoke both commands against the explicit tag' {
        Set-Responses @((New-Metadata), (New-VerifiedResult), (New-VerifiedResult))
        $result = Test-ReleaseAttestation @baseArguments
        Assert-True ($result.Verified -and $script:calls.Count -eq 3) 'Both attestation commands must run.'
        Assert-True (($script:calls[1] -join '|') -ceq 'release|verify|--repo|example/project|--format|json|--|v1.2.3') 'Release verification arguments changed.'
        Assert-True ($script:calls[2].Count -eq 9 -and $script:calls[2][1] -ceq 'verify-asset' -and $script:calls[2][8] -ceq $artifact) 'Asset verification split the file path.'
    }
    $mismatches = @(
        @{ Name = 'unverified payload'; Pattern = 'verified release statement'; Mutate = {
            param($r) $r.verificationResult.statement = $null
            $r | Add-Member -NotePropertyName attestation -NotePropertyValue @{ statement = (New-VerifiedResult).verificationResult.statement }
        } },
        @{ Name = 'repository'; Pattern = 'different repository, tag or release'; Mutate = { param($r) $r.verificationResult.statement.predicate.repository = 'other/project' } },
        @{ Name = 'tag'; Pattern = 'different repository, tag or release'; Mutate = { param($r) $r.verificationResult.statement.predicate.tag = 'v9.9.9' } },
        @{ Name = 'release id'; Pattern = 'different repository, tag or release'; Mutate = { param($r) $r.verificationResult.statement.predicate.databaseId = '99' } },
        @{ Name = 'package identity'; Pattern = 'different release package identity'; Mutate = { param($r) $r.verificationResult.statement.predicate.purl = 'pkg:github/example/project@v9.9.9' } },
        @{ Name = 'commit'; Pattern = 'different commit'; Mutate = { param($r) $r.verificationResult.statement.subject[0].digest.sha1 = ('f' * 40) } },
        @{ Name = 'asset name'; Pattern = 'different asset hash'; Mutate = { param($r) $r.verificationResult.statement.subject[1].name = 'other.mpp' } },
        @{ Name = 'asset digest'; Pattern = 'different asset hash'; Mutate = { param($r) $r.verificationResult.statement.subject[1].digest.sha256 = ('0' * 64) } },
        @{ Name = 'duplicate release subject'; Pattern = 'no unique release subject'; Mutate = { param($r) $r.verificationResult.statement.subject += $r.verificationResult.statement.subject[0] } },
        @{ Name = 'duplicate asset subject'; Pattern = 'no unique asset subject'; Mutate = { param($r) $r.verificationResult.statement.subject += $r.verificationResult.statement.subject[1] } }
    )
    foreach ($operation in @('verify', 'verify-asset')) {
        foreach ($mismatch in $mismatches) {
            Test-Case "$operation rejects $($mismatch.Name)" {
                $wrong = New-VerifiedResult
                & $mismatch.Mutate $wrong
                if ($operation -eq 'verify') { Set-Responses @((New-Metadata), $wrong) }
                else { Set-Responses @((New-Metadata), (New-VerifiedResult), $wrong) }
                Assert-Throws { Test-ReleaseAttestation @baseArguments } $mismatch.Pattern
            }
        }
    }
    Test-Case 'Asset changes during verification cannot pass' {
        Set-Responses @((New-Metadata), (New-VerifiedResult), {
            [IO.File]::WriteAllText($artifact, 'tampered while verifying')
            return New-VerifiedResult
        })
        Assert-Throws { Test-ReleaseAttestation @baseArguments } 'changed during attestation verification'
    }
    Test-Case 'RequireImmutableRelease cannot silently run without published asset verification' {
        $arguments = @('-NoLogo', '-NoProfile', '-File', (Join-Path $PSScriptRoot 'validate-release-facts.ps1'), '-RequireImmutableRelease')
        $pwsh = (Get-Process -Id $PID).Path
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $output = & $pwsh @arguments 2>&1; $code = $LASTEXITCODE }
        finally { $ErrorActionPreference = $preference }
        Assert-True ($code -ne 0 -and ($output -join ' ') -match 'requires -VerifyPublishedAsset') 'The immutable requirement was silently ignored.'
    }
} finally {
    Remove-GeneratedPath -Path $testRoot -Root $temporaryRoot
}

$global:LASTEXITCODE = 0
Write-Host "[release-attestation] $script:caseCount contracts passed"
