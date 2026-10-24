function Resolve-D8 {
    <#
    .SYNOPSIS
        The d8 that compiles the fixture classes, or a throw saying what to pass.
    .DESCRIPTION
        Taken from -Explicit, else the newest build-tools directory of the SDK named in the
        checkout's local.properties. Newest by version number, not by text: sorting the paths as
        strings put 9.0.0 above 37.0.0, and would put 100.0.0 below it.
    #>
    param([string]$Explicit, [string]$Root)

    if ($Explicit -and (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
        return [System.IO.Path]::GetFullPath($Explicit)
    }
    $properties = Join-Path $Root 'local.properties'
    $sdkLine = Get-Content -LiteralPath $properties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (-not $sdkLine) { throw 'No Android SDK path in local.properties. Pass -D8.' }
    $sdk = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\\\', '\'
    $found = @(Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') -Directory `
        -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^\d+\.\d+\.\d+$' -and
            (Test-Path -LiteralPath (Join-Path $_.FullName 'd8.bat') -PathType Leaf)
        } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1)
    if ($found.Count -ne 1) { throw 'No d8.bat found in the configured Android SDK. Pass -D8.' }
    return Join-Path $found[0].FullName 'd8.bat'
}

function Compare-VerifierTallies {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Clean,
        [Parameter(Mandatory = $true)][System.Collections.IDictionary]$Patched
    )

    $cleanCounts = [System.Collections.Generic.Dictionary[string, int]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($entry in $Clean.GetEnumerator()) {
        $cleanCounts[[string]$entry.Key] = [int]$entry.Value
    }
    $patchedCounts = [System.Collections.Generic.Dictionary[string, int]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($entry in $Patched.GetEnumerator()) {
        $patchedCounts[[string]$entry.Key] = [int]$entry.Value
    }

    $cleanTotal = 0
    foreach ($count in $cleanCounts.Values) { $cleanTotal += $count }
    $patchedTotal = 0
    foreach ($count in $patchedCounts.Values) { $patchedTotal += $count }

    $deltas = @()
    $messageSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($message in $cleanCounts.Keys) { [void]$messageSet.Add($message) }
    foreach ($message in $patchedCounts.Keys) { [void]$messageSet.Add($message) }
    $messages = @($messageSet | Sort-Object -CaseSensitive)
    foreach ($message in $messages) {
        $before = if ($cleanCounts.ContainsKey($message)) { $cleanCounts[$message] } else { 0 }
        $after = if ($patchedCounts.ContainsKey($message)) { $patchedCounts[$message] } else { 0 }
        if ($before -eq $after) { continue }
        $deltas += [pscustomobject]@{
            Kind = if ($after -gt $before) { 'extra' } else { 'missing' }
            Message = "$message"
            Clean = $before
            Patched = $after
            Difference = [Math]::Abs($after - $before)
        }
    }

    [pscustomobject]@{
        Valid = $cleanTotal -gt 0 -and $patchedTotal -gt 0 -and $deltas.Count -eq 0
        CleanTotal = $cleanTotal
        PatchedTotal = $patchedTotal
        HasEvidence = $cleanTotal -gt 0 -and $patchedTotal -gt 0
        Deltas = $deltas
    }
}
