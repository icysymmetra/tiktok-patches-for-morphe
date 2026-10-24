<#
.SYNOPSIS
    Finds a JDK new enough to run the Morphe desktop CLI.

.DESCRIPTION
    The CLI is built for a newer JDK than the java that is usually first on PATH here, and it
    says so through an UnsupportedClassVersionError on a class nobody recognises. Every script
    that shells out to it shares this, so they all fail the same readable way.

    Candidates are tried in order and each one is asked its version, so a JAVA_HOME pinned to an
    old JDK for some other build cannot shadow a new enough java already on PATH.
#>

function Get-JavaMajorVersion {
    <# Returns the major version of a java, or 0 if it will not run. #>
    param([string]$Java)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # Windows PowerShell promotes a native program's stderr to an ErrorRecord under Stop.
        # java -version writes its ordinary version banner to stderr, so probe it under Continue
        # and merge that stream into the captured output.
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = 0
        $output = & $Java '-version' 2>&1
        $exitCode = $LASTEXITCODE
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) { return 0 }
    foreach ($line in @($output)) {
        # Both shapes: `openjdk version "21.0.12"` and the older `java version "1.8.0_401"`.
        if ("$line" -match 'version "1\.([0-9]+)') { return [int]$Matches[1] }
        if ("$line" -match 'version "([0-9]+)') { return [int]$Matches[1] }
    }
    return 0
}

function Resolve-Java {
    param([string]$Explicit, [int]$Minimum = 21)

    $candidates = [System.Collections.Generic.List[string]]::new()
    $preferredCandidates = @(
        [pscustomobject]@{ Path = $Explicit; Explicit = $true }
        [pscustomobject]@{ Path = $env:HUSHFEED_JAVA; Explicit = $false }
    )
    foreach ($entry in $preferredCandidates) {
        $candidate = $entry.Path
        if (-not $candidate) { continue }
        # A JDK directory is what the error message asks for, so take it as one when it is one.
        if (Test-Path -LiteralPath $candidate -PathType Container) {
            $found = $false
            foreach ($leaf in @('bin/java.exe', 'bin/java')) {
                $inside = Join-Path $candidate $leaf
                if (Test-Path -LiteralPath $inside -PathType Leaf) {
                    $candidates.Add($inside)
                    $found = $true
                    break
                }
            }
            if (-not $found) {
                if ($entry.Explicit) {
                    throw "The Java directory passed with -Java contains no bin/java.exe or bin/java: $candidate"
                }
                # Preserve environment fallback behavior, but retain the rejected location in
                # the final diagnostic when no later candidate works.
                $candidates.Add((Join-Path $candidate 'bin/java.exe'))
            }
            continue
        }
        $candidates.Add($candidate)
    }
    # Ahead of JAVA_HOME on purpose: a JAVA_HOME held at an old JDK for another build must not
    # turn a machine whose PATH java is new enough into a failing one.
    $candidates.Add('java')
    foreach ($root in @($env:JAVA_HOME, $env:JDK_HOME)) {
        if (-not $root) { continue }
        foreach ($leaf in @('bin/java.exe', 'bin/java')) {
            $path = Join-Path $root $leaf
            if (Test-Path -LiteralPath $path -PathType Leaf) {
                $candidates.Add($path)
                break
            }
        }
    }

    $seen = @{}
    $rejected = @()
    foreach ($candidate in $candidates) {
        if ($seen.ContainsKey($candidate)) { continue }
        $seen[$candidate] = $true
        $version = Get-JavaMajorVersion -Java $candidate
        if ($version -ge $Minimum) { return $candidate }
        $rejected += if ($version -gt 0) { "$candidate is Java $version" } else { "$candidate would not run" }
    }

    throw ("No Java $Minimum or newer was found, and the Morphe desktop CLI needs one. Set " +
        'HUSHFEED_JAVA to a JDK, or pass -Java. Tried: ' + ($rejected -join '; ') + '.')
}
