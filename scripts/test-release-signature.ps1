[CmdletBinding()]
param([string]$Root)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-signature.ps1')
$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-signature-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot | Out-Null
$artifact = Join-Path $fixtureRoot 'bundle.mpp'
$signature = Join-Path $fixtureRoot 'bundle.sigstore.json'
$publicKey = Join-Path $fixtureRoot 'cosign.pub'
$executable = Join-Path $fixtureRoot 'cosign.exe'
$script:calls = [Collections.Generic.List[object]]::new()
$script:mode = 'valid'
$script:assertions = 0
function Assert-True([bool]$Value, [string]$Message) {
    $script:assertions++
    if (-not $Value) { throw $Message }
}
function Assert-Throws([scriptblock]$Action, [string]$Pattern) {
    $script:assertions++
    try { & $Action | Out-Null }
    catch {
        if ($_.Exception.Message -like $Pattern) { return }
        throw "Expected '$Pattern', got '$($_.Exception.Message)'."
    }
    throw "Expected '$Pattern', got success."
}
# A command boundary fixture checks policy and arguments. Real cryptographic verification is
# required separately when the held production public key and signature become available.
function Invoke-CosignCommand {
    param([string]$Executable, [string[]]$Arguments)
    $script:calls.Add(@($Arguments))
    if ($Arguments[0] -eq 'version') {
        switch ($script:mode) {
            'old' { return [pscustomobject]@{ ExitCode = 0; Output = '{"gitVersion":"v3.1.2"}' } }
            'prerelease' { return [pscustomobject]@{ ExitCode = 0; Output = '{"gitVersion":"v3.2.0-rc.1"}' } }
            'bad-json' { return [pscustomobject]@{ ExitCode = 0; Output = 'not JSON' } }
            'version-error' { return [pscustomobject]@{ ExitCode = 2; Output = 'cannot start' } }
            default { return [pscustomobject]@{ ExitCode = 0; Output = '{"gitVersion":"v3.1.3"}' } }
        }
    }
    if ($script:mode -eq 'reject') { return [pscustomobject]@{ ExitCode = 1; Output = 'signature mismatch' } }
    if ($script:mode -eq 'changed') { Add-Content -LiteralPath $artifact -Value 'changed' }
    return [pscustomobject]@{ ExitCode = 0; Output = 'Verified OK' }
}
try {
    foreach ($file in @($artifact, $signature, $publicKey, $executable)) { Set-Content -LiteralPath $file -Value 'fixture' }
    $parameters = @{ ArtifactPath = $artifact; SignaturePath = $signature; PublicKeyPath = $publicKey; Cosign = $executable }
    $result = Test-CosignBlobSignature @parameters
    Assert-True ($result.Verified -and $result.CosignVersion -eq 'v3.1.3') 'A successful verification was not reported.'
    Assert-True ($script:calls.Count -eq 2 -and ($script:calls[1] -join '|') -eq
        ('verify-blob|--key|' + $publicKey + '|--bundle|' + $signature + '|' + $artifact)) 'Verification did not pin the key, bundle and exact artifact.'
    Assert-True (-not ($script:calls[1] -join ' ').Contains('insecure-ignore')) 'Normal transparency verification was disabled.'
    foreach ($mode in @('old', 'prerelease', 'bad-json', 'version-error', 'reject', 'changed')) {
        $script:mode = $mode
        $pattern = switch ($mode) {
            'old' { '*unsupported*' }
            'prerelease' { '*unsupported*' }
            'bad-json' { '*readable version JSON*' }
            'version-error' { '*Could not read cosign version*' }
            'reject' { '*signature verification failed*signature mismatch*' }
            'changed' { '*changed during verification*' }
        }
        Assert-Throws { Test-CosignBlobSignature @parameters } $pattern
    }
    $script:mode = 'valid'
    foreach ($key in @('ArtifactPath', 'SignaturePath', 'PublicKeyPath', 'Cosign')) {
        $missing = $parameters.Clone()
        $missing[$key] = Join-Path $fixtureRoot 'missing'
        Assert-Throws { Test-CosignBlobSignature @missing } $(if ($key -eq 'Cosign') { '*Cosign 3.1.3*required*' } else { '*input is missing*' })
    }
    Assert-Throws { & (Join-Path $PSScriptRoot 'validate-release-facts.ps1') -RequireBundleSignature } `
        '*-RequireBundleSignature requires -VerifyPublishedAsset*'
} finally {
    $resolved = [IO.Path]::GetFullPath($fixtureRoot)
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $resolved.StartsWith($temporaryRoot, [StringComparison]::OrdinalIgnoreCase)) { throw 'Unsafe test cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

$global:LASTEXITCODE = 0
Write-Host "[signature] $script:assertions offline policy contracts passed"
