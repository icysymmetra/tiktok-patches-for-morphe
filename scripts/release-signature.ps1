function Invoke-CosignCommand {
    param([Parameter(Mandatory)][string]$Executable, [Parameter(Mandatory)][string[]]$Arguments)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = @(& $Executable @Arguments 2>&1)
        $status = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $preference
    }
    return [pscustomobject]@{ ExitCode = $status; Output = ($output -join "`n") }
}

function Test-CosignBlobSignature {
    <# The pinned public key authenticates the artifact; normal Sigstore log verification stays enabled. #>
    [CmdletBinding()]
    param(
        [Parameter(Mandatory)][string]$ArtifactPath,
        [Parameter(Mandatory)][string]$SignaturePath,
        [Parameter(Mandatory)][string]$PublicKeyPath,
        [string]$Cosign
    )

    foreach ($path in @($ArtifactPath, $SignaturePath, $PublicKeyPath)) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Signature verification input is missing: $path" }
    }
    if (-not $Cosign) {
        $command = Get-Command cosign -ErrorAction SilentlyContinue
        if ($null -ne $command) { $Cosign = $command.Source }
        elseif ($env:LOCALAPPDATA) {
            $Cosign = Join-Path $env:LOCALAPPDATA 'Programs/Cosign/cosign.exe'
        }
    }
    if (-not $Cosign -or -not (Test-Path -LiteralPath $Cosign -PathType Leaf)) {
        throw 'Cosign 3.1.3 or newer is required to verify an advertised signature. Pass -Cosign or put cosign on PATH.'
    }
    $versionResult = Invoke-CosignCommand -Executable $Cosign -Arguments @('version', '--json')
    if ($versionResult.ExitCode -ne 0) { throw "Could not read cosign version: $($versionResult.Output)" }
    try { $versionText = [string](($versionResult.Output | ConvertFrom-Json).gitVersion) }
    catch { throw 'Cosign did not return readable version JSON.' }
    if ($versionText -notmatch '^v?(\d+\.\d+\.\d+)$' -or [version]$Matches[1] -lt [version]'3.1.3') {
        throw "Cosign $versionText is unsupported. Use stable 3.1.3 or newer (GHSA-fx35-mq7g-6g98)."
    }
    $inputs = @($ArtifactPath, $SignaturePath, $PublicKeyPath | ForEach-Object {
        [pscustomobject]@{ Path = (Resolve-Path -LiteralPath $_).Path; Hash = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash }
    })
    $result = Invoke-CosignCommand -Executable $Cosign -Arguments @(
        'verify-blob', '--key', $inputs[2].Path, '--bundle', $inputs[1].Path, $inputs[0].Path
    )
    if ($result.ExitCode -ne 0) { throw "Cosign signature verification failed: $($result.Output)" }
    foreach ($inputFile in $inputs) {
        if ((Get-FileHash -LiteralPath $inputFile.Path -Algorithm SHA256).Hash -cne $inputFile.Hash) {
            throw "A signature verification input changed during verification: $($inputFile.Path)"
        }
    }
    Write-Host "[release] verified cosign signature with pinned public key; sha256=$($inputs[0].Hash.ToLowerInvariant())"
    return [pscustomobject]@{
        Verified = $true
        ArtifactSha256 = $inputs[0].Hash.ToLowerInvariant()
        PublicKeySha256 = $inputs[2].Hash.ToLowerInvariant()
        CosignVersion = $versionText
    }
}
