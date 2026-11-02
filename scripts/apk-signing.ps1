<#
.SYNOPSIS
    Sign device-only APKs and check their certificates without converting or copying keys.
#>

function Resolve-ApkSigningTools {
    param([string]$Root, [string]$Sdk, [string]$KeystoreType)

    $buildTools = Get-ChildItem -LiteralPath (Join-Path $Sdk 'build-tools') -Directory |
        Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } -Descending |
        Select-Object -First 1
    if (-not $buildTools) { throw "No build-tools under $Sdk." }
    $signerJar = Join-Path $buildTools.FullName 'lib/apksigner.jar'
    if (-not (Test-Path -LiteralPath $signerJar -PathType Leaf)) { throw "No apksigner.jar under $($buildTools.FullName)." }
    $classPath = $signerJar
    if ($KeystoreType -eq 'BKS') {
        $pin = Get-Content -LiteralPath (Join-Path $Root 'gradle/libs.versions.toml') |
            Where-Object { $_ -match '^bouncycastle\s*=\s*"([^"]+)"' } | Select-Object -First 1
        if (-not $pin -or $pin -notmatch '^bouncycastle\s*=\s*"([^"]+)"') { throw 'No Bouncy Castle version pin.' }
        $version = $Matches[1]
        $artifactName = "bcprov-jdk18on-$version.jar"
        [xml]$metadata = Get-Content -LiteralPath (Join-Path $Root 'gradle/verification-metadata.xml') -Raw
        $hashes = @($metadata.SelectNodes("//*[local-name()='component' and @group='org.bouncycastle' and @name='bcprov-jdk18on' and @version='$version']/*[local-name()='artifact' and @name='$artifactName']/*[local-name()='sha256']") |
            ForEach-Object { $_.value.ToLowerInvariant() })
        if ($hashes.Count -eq 0) { throw "No verified checksum for $artifactName." }
        $gradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
        $cache = Join-Path $gradleHome "caches/modules-2/files-2.1/org.bouncycastle/bcprov-jdk18on/$version"
        $provider = $null
        foreach ($candidate in @(Get-ChildItem -LiteralPath $cache -Recurse -Filter $artifactName -File -ErrorAction SilentlyContinue)) {
            $providerHash = [Security.Cryptography.SHA256]::Create()
            $providerStream = [IO.File]::OpenRead($candidate.FullName)
            try { $checksum = [BitConverter]::ToString($providerHash.ComputeHash($providerStream)).Replace('-', '').ToLowerInvariant() }
            finally { $providerStream.Dispose(); $providerHash.Dispose() }
            if ($checksum -in $hashes) {
                $provider = $candidate.FullName
                break
            }
        }
        if (-not $provider) { throw "No checksum-verified $artifactName in the Gradle cache. Resolve the project's dependencies first." }
        $classPath += [IO.Path]::PathSeparator + $provider
    }
    return [pscustomobject]@{
        BuildTools = $buildTools.FullName
        ClassPath = $classPath
        Checker = Join-Path $Root 'scripts/SigningCertificateCheck.java'
    }
}

function Test-ApkSigningKeyCollision {
    param($Session, [string]$Path)
    $absolute = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Path)
    if ($absolute -eq $Session.Keystore) { return $true }
    if (-not (Test-Path -LiteralPath $absolute -PathType Leaf)) { return $false }
    $stream = [IO.File]::OpenRead($absolute)
    $hash = $null
    try {
        if ($stream.Length -ne $Session.KeyLock.Length) { return $false }
        $hash = [Security.Cryptography.SHA256]::Create()
        $digest = [BitConverter]::ToString($hash.ComputeHash($stream))
        # Includes hard links, symbolic aliases and copies of the key, without treating a
        # filename extension as proof that a generated file cannot contain private key bytes.
        return $digest -eq $Session.KeyDigest
    } finally {
        $stream.Dispose()
        if ($hash) { $hash.Dispose() }
    }
}

function New-ApkSigningSession {
    param(
        [Collections.IDictionary]$BoundParameters,
        [string]$Root, [string]$Sdk, [string]$Java, [string]$Keystore, [string]$KeyAlias,
        [string]$KeystoreType, [string]$OutputDirectory
    )

    $Keystore = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Keystore)
    if ($OutputDirectory) {
        $outputRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory).TrimEnd('\')
        if ($outputRoot -eq [IO.Path]::GetPathRoot($outputRoot).TrimEnd('\')) {
            throw 'Refusing signing output at a filesystem root.'
        }
        if ($Keystore -eq $outputRoot -or $Keystore.StartsWith($outputRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Refusing a signing key inside the build output directory. Keep the key outside -OutDir.'
        }
        # Lexical separation cannot establish safety through a junction or symbolic link.
        foreach ($path in @($Keystore, $outputRoot)) {
            $cursor = $path
            while ($cursor) {
                if (Test-Path -LiteralPath $cursor) {
                    $item = Get-Item -LiteralPath $cursor -Force
                    if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                        throw 'Refusing signing through a linked path.'
                    }
                }
                $cursor = Split-Path -Parent $cursor
            }
        }
    }
    $variables = [Environment]::GetEnvironmentVariables([EnvironmentVariableTarget]::Process)
    if ($BoundParameters.ContainsKey('KeystorePassword')) { $storePassword = [string]$BoundParameters['KeystorePassword'] }
    elseif ($variables.Contains('HUSHFEED_SIDELOAD_KEYSTORE_PASSWORD')) { $storePassword = [string]$variables['HUSHFEED_SIDELOAD_KEYSTORE_PASSWORD'] }
    else { $storePassword = 'sideload' }
    if ($BoundParameters.ContainsKey('KeyPassword')) { $entryPassword = [string]$BoundParameters['KeyPassword'] }
    elseif ($variables.Contains('HUSHFEED_SIDELOAD_KEY_PASSWORD')) { $entryPassword = [string]$variables['HUSHFEED_SIDELOAD_KEY_PASSWORD'] }
    else { $entryPassword = $storePassword }
    $tools = Resolve-ApkSigningTools -Root $Root -Sdk $Sdk -KeystoreType $KeystoreType
    $session = [pscustomobject]@{
        Java = $Java; Tools = $tools; Keystore = $Keystore; KeyAlias = $KeyAlias; KeystoreType = $KeystoreType
        StorePasswordSpec = 'pass:'; EntryPasswordSpec = 'pass:'; Environment = @{}; Certificate = $null; KeyLock = $null; KeyDigest = $null
    }
    $preference = $ErrorActionPreference
    try {
        # Prevent writes/deletion through an alias too, for the whole build and its cleanup.
        $session.KeyLock = [IO.File]::Open($Keystore, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
        $keyHash = [Security.Cryptography.SHA256]::Create()
        try { $session.KeyDigest = [BitConverter]::ToString($keyHash.ComputeHash($session.KeyLock)) }
        finally { $keyHash.Dispose(); $session.KeyLock.Position = 0 }
        if ($OutputDirectory -and (Test-Path -LiteralPath $outputRoot -PathType Container)) {
            $pending = New-Object 'Collections.Generic.Stack[string]'
            $pending.Push($outputRoot)
            while ($pending.Count -gt 0) {
                foreach ($child in @(Get-ChildItem -LiteralPath $pending.Pop() -Force)) {
                    if ($child.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Refusing signing through a linked path.' }
                    if ($child.PSIsContainer) { $pending.Push($child.FullName) }
                    elseif (Test-ApkSigningKeyCollision -Session $session -Path $child.FullName) {
                        throw 'Refusing a signing key inside the build output directory. Keep the key outside -OutDir.'
                    }
                }
            }
        }
        foreach ($entry in @(
            @{ Value = $storePassword; Property = 'StorePasswordSpec' }
            @{ Value = $entryPassword; Property = 'EntryPasswordSpec' }
        )) {
            if ($entry.Value.Length -eq 0) { continue }
            $name = 'HUSHFEED_SIGNING_' + [Guid]::NewGuid().ToString('N')
            $session.Environment[$name] = [Environment]::GetEnvironmentVariable($name, [EnvironmentVariableTarget]::Process)
            [Environment]::SetEnvironmentVariable($name, $entry.Value, [EnvironmentVariableTarget]::Process)
            $session.($entry.Property) = "env:$name"
        }
        $type = if ($KeystoreType) { $KeystoreType } else { 'default' }
        $ErrorActionPreference = 'Continue'
        # Only stdout belongs to the certificate protocol. JVM diagnostics may accompany success.
        $output = @(& $Java --class-path $tools.ClassPath $tools.Checker key $Keystore $KeyAlias $type `
            $session.StorePasswordSpec $session.EntryPasswordSpec 2>$null)
        $status = $LASTEXITCODE
        $ErrorActionPreference = $preference
        if ($status -ne 0 -or $output.Count -ne 1 -or [string]$output[0] -notmatch '^[a-f0-9]{64}$') {
            throw 'Could not unlock the signing key. Check its type, alias, and passwords.'
        }
        $session.Certificate = [string]$output[0]
        return $session
    } catch {
        Close-ApkSigningSession -Session $session
        throw
    } finally { $ErrorActionPreference = $preference }
}

function Close-ApkSigningSession {
    param($Session)
    if (-not $Session) { return }
    if ($Session.KeyLock) { $Session.KeyLock.Dispose(); $Session.KeyLock = $null }
    foreach ($name in $Session.Environment.Keys) {
        $previous = $Session.Environment[$name]
        # PowerShell coerces $null to an empty string for this overload. On recent .NET,
        # that creates a present empty variable; NullString removes an absent original.
        if ($null -eq $previous) { $previous = [NullString]::Value }
        [Environment]::SetEnvironmentVariable($name, $previous, [EnvironmentVariableTarget]::Process)
    }
}

function Get-ApkSigningCertificate {
    param($Session, [string]$Apk)
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Session.Java --class-path $Session.Tools.ClassPath $Session.Tools.Checker apk $Apk 2>$null)
        $status = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($status -ne 0 -or $output.Count -ne 1 -or [string]$output[0] -notmatch '^[a-f0-9]{64}(,[a-f0-9]{64})*$') {
        throw 'Could not verify the APK signing certificate.'
    }
    return [string]$output[0]
}

function Invoke-ApkSigning {
    param($Session, [string]$InputApk, [string]$OutputApk)
    if (-not $Session.KeyLock) { throw 'The signing session is closed.' }
    $InputApk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($InputApk)
    $OutputApk = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputApk)
    foreach ($path in @($InputApk, $OutputApk, ($OutputApk + '.idsig'))) {
        if (Test-ApkSigningKeyCollision -Session $Session -Path $path) {
            throw 'Refusing an APK or signing sidecar path that overlaps the signing key.'
        }
    }
    $arguments = @('--class-path', $Session.Tools.ClassPath, 'com.android.apksigner.ApkSignerTool',
        'sign', '--ks', $Session.Keystore, '--ks-pass', $Session.StorePasswordSpec,
        '--ks-key-alias', $Session.KeyAlias, '--key-pass', $Session.EntryPasswordSpec,
        '--min-sdk-version', '23', '--out', $OutputApk)
    if ($Session.KeystoreType) { $arguments += @('--ks-type', $Session.KeystoreType) }
    if ($Session.KeystoreType -eq 'BKS') {
        $arguments += @('--ks-provider-class', 'org.bouncycastle.jce.provider.BouncyCastleProvider')
    }
    $arguments += $InputApk
    $preference = $ErrorActionPreference
    $verified = $false
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& $Session.Java @arguments 2>&1)
        $status = $LASTEXITCODE
        $ErrorActionPreference = $preference
        if ($status -ne 0) { throw 'APK signing failed. Check the signing key and passwords.' }
        if ((Get-ApkSigningCertificate -Session $Session -Apk $OutputApk) -ne $Session.Certificate) {
            throw 'The signed APK does not match the requested signing certificate.'
        }
        $verified = $true
    } finally {
        $ErrorActionPreference = $preference
        if (-not $verified -and (Test-Path -LiteralPath $OutputApk -PathType Leaf)) {
            Remove-Item -LiteralPath $OutputApk -Force
        }
    }
}
