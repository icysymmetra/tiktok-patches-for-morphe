<#
.SYNOPSIS
    The release provenance receipt: how it is built, and what makes one valid.

.DESCRIPTION
    Dot-sourced by build-release-receipt.ps1 and validate-release-facts.ps1. A checksum on its
    own says a file has not changed since somebody hashed it. It cannot say which APK the patches
    were proved against, which commit built the bundle, which toolchain stamped it, or what the
    patches did to the Android manifest, and those are the facts that decide whether a local
    patch run is the one the release describes.

    Everything here is read back out of files rather than taken on trust: the APK hashes off the
    fixtures, the bundle hash and size off the .mpp, the patch verdicts out of the desktop CLI's
    own result report, and the manifest delta out of aapt2's reading of both APKs.

    The manifest delta is the part that needs a human. A patch that adds a permission or exports
    a component changes what the patched app can do and what other apps can reach, so every entry
    has to be written down in scripts/manifest-delta-allowlist.txt and stays until somebody takes
    it out. An entry nothing produces any more fails the run too: an allowlist that outlives its
    reason stops being a review.
#>

function Get-ReleaseReceiptSchemaVersion {
    <#
    .SYNOPSIS
        Bumped when the shape changes in a way a reader has to know about.
    .DESCRIPTION
        Validation refuses a receipt written to a different version rather than guessing which
        fields moved. A function rather than a variable because this file is dot-sourced into
        several scripts, and a script-scoped variable in a dot-sourced file belongs to whichever
        one sourced it.
    #>
    return 2
}

function Test-BuildSourceFacts {
    <# A timestamp is reproducible, but only the producer's source facts establish eligibility. #>
    param($Source, [Parameter(Mandatory = $true)][string]$ExpectedCommit)

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }
    if ($null -eq $Source) { return Fail 'The build has no source provenance record.' }
    if ([string]$Source.commit -notmatch '^[0-9a-f]{40}$' -or $Source.commit -ne $ExpectedCommit) {
        return Fail "The build source commit is $($Source.commit), not $ExpectedCommit."
    }
    if ($Source.clean -isnot [bool] -or -not $Source.clean) {
        return Fail 'The build source was dirty or could not be read; it is not known to be clean.'
    }
    if ([string]$Source.startFingerprint -notmatch '^[0-9A-F]{64}$' -or
            [string]$Source.endFingerprint -notmatch '^[0-9A-F]{64}$') {
        return Fail 'The build has no readable starting and ending source fingerprints.'
    }
    if ($Source.startFingerprint -ne $Source.endFingerprint) {
        return Fail 'The source inputs changed during the build.'
    }
    return [pscustomobject]@{ Valid = $true; Reason = $null }
}

function Test-FrozenLegacyReceipt {
    <#
        Only the exact already-published schema 1 documents may use the historical reader.
        Neither an old version label nor a supplied switch can exempt a new artifact.
    #>
    param($Receipt, [string]$ReceiptPath)

    if (-not $ReceiptPath -or -not (Test-Path -LiteralPath $ReceiptPath -PathType Leaf)) {
        return $false
    }
    $identityPath = Join-Path $PSScriptRoot 'legacy-release-receipts.tsv'
    $receiptHash = Get-Sha256Hex -Path $ReceiptPath
    $identity = @(Get-Content -LiteralPath $identityPath | Where-Object {
        $_ -and -not $_.StartsWith('#') -and ($_ -split "`t")[0] -eq $receiptHash
    })
    if ($identity.Count -ne 1) { return $false }
    $fields = $identity[0] -split "`t"
    if ($fields.Count -ne 4 -or $Receipt.bundle.sha256 -ne $fields[1] -or
            $Receipt.release.commit -ne $fields[2] -or $Receipt.release.tag -ne $fields[3]) {
        return $false
    }
    # The caller's parsed object must describe those exact bytes too, not a different document
    # accompanied by the path of a valid historical one.
    $frozen = Get-Content -LiteralPath $ReceiptPath -Raw | ConvertFrom-Json
    return ($frozen | ConvertTo-Json -Depth 20 -Compress) -ceq
        ($Receipt | ConvertTo-Json -Depth 20 -Compress)
}

function Get-CliOutputTail {
    <#
    .SYNOPSIS
        What the desktop CLI printed last, for the message of a fixture run that failed.
    .DESCRIPTION
        Its first and last few error lines wherever they fell, then its last lines. A run that
        dies before its result report exists leaves nothing else behind. The last error lines
        count as much as the first: with --continue-on-error a run can log many failed patches
        before the one that ends it, and a long stack trace pushes that one out of the tail.
    #>
    param([object[]]$Output, [int]$Last = 20, [int]$Errors = 8)

    $lines = @(@($Output) | ForEach-Object { [string]$_ } | Where-Object { $_.Trim() })
    $errorLines = @($lines | Where-Object { $_ -match 'SEVERE|ERROR|Exception|OutOfMemory' })
    $half = [Math]::Max(1, [int][Math]::Floor($Errors / 2))
    $picked = if ($errorLines.Count -le $Errors) { $errorLines } else {
        @($errorLines | Select-Object -First $half) + @($errorLines | Select-Object -Last $half)
    }
    $kept = New-Object System.Collections.Generic.List[string]
    foreach ($line in @($picked) + @($lines | Select-Object -Last $Last)) {
        if (-not $kept.Contains($line)) { $kept.Add($line) }
    }
    if ($kept.Count -eq 0) { return '(the CLI printed nothing)' }
    return ($kept -join "`n")
}

function Get-Sha256Hex {
    <#
    .SYNOPSIS
        A file's SHA-256 in upper-case hex, which is the form the release notes use.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Cannot hash a file that is not there: $Path"
    }
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToUpperInvariant()
}

function Get-BundleManifestFacts {
    <#
    .SYNOPSIS
        Version, timestamp, patcher and source facts out of META-INF/MANIFEST.MF.
    .DESCRIPTION
        The Gradle plugin pins the timestamp to the release commit's time in milliseconds, which
        is one input to reproducing a published hash. The separate source fields identify the
        commit and whether its inputs were clean and unchanged at the build boundaries.
    #>
    param([Parameter(Mandatory = $true)][string]$BundlePath)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $text = $null
    $archive = [System.IO.Compression.ZipFile]::OpenRead($BundlePath)
    try {
        $entry = $archive.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
        if (-not $entry) { throw "The bundle has no META-INF/MANIFEST.MF: $BundlePath" }
        $reader = New-Object System.IO.StreamReader($entry.Open())
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }

    # Manifest lines wrap at 72 characters with a leading space on the continuation.
    $text = $text -replace "\r?\n ", ''
    $timestamp = [regex]::Match($text, '(?m)^Timestamp:\s*(\d+)\s*$')
    $version = [regex]::Match($text, '(?m)^Version:\s*(\S+)\s*$')
    $patcher = [regex]::Match($text, '(?m)^Patcher-Version:\s*(\S+)\s*$')
    if (-not $timestamp.Success) { throw "The bundle manifest has no Timestamp: $BundlePath" }
    if (-not $version.Success) { throw "The bundle manifest has no Version: $BundlePath" }
    if (-not $patcher.Success) { throw "The bundle manifest has no Patcher-Version: $BundlePath" }

    $sourceFields = @{}
    foreach ($name in @('Commit', 'Clean', 'Start', 'End')) {
        $matches = [regex]::Matches($text, "(?m)^Hushfeed-Source-${name}:([^`r`n]*)")
        if ($matches.Count -gt 1) { throw "The bundle repeats source field ${name}: $BundlePath" }
        $sourceFields[$name] = if ($matches.Count -eq 1) { $matches[0].Groups[1].Value.Trim() } else { '' }
    }

    return [pscustomobject]@{
        version        = $version.Groups[1].Value
        timestamp      = [long]$timestamp.Groups[1].Value
        patcherVersion = $patcher.Groups[1].Value
        source         = [pscustomobject]@{
            commit = $sourceFields.Commit
            clean = $sourceFields.Clean -ceq 'true'
            startFingerprint = $sourceFields.Start
            endFingerprint = $sourceFields.End
        }
    }
}

function Resolve-Aapt2 {
    <#
    .SYNOPSIS
        The aapt2 that reads a binary AndroidManifest.xml, or a throw saying where to get one.
    .DESCRIPTION
        Taken in order from -Explicit, HUSHFEED_AAPT2, and the newest build-tools directory of
        the SDK named in local.properties or ANDROID_HOME. Newest by version number, not by text:
        sorting build-tools as strings puts 9.0.0 above 37.0.0.
    #>
    param([string]$Explicit, [string]$Root)

    if ($Explicit) {
        if (-not (Test-Path -LiteralPath $Explicit -PathType Leaf)) {
            throw "No aapt2 at the path given: $Explicit"
        }
        return $Explicit
    }
    if ($env:HUSHFEED_AAPT2 -and (Test-Path -LiteralPath $env:HUSHFEED_AAPT2 -PathType Leaf)) {
        return $env:HUSHFEED_AAPT2
    }

    $sdk = $env:ANDROID_HOME
    $localProperties = if ($Root) { Join-Path $Root 'local.properties' } else { $null }
    if ($localProperties -and (Test-Path -LiteralPath $localProperties -PathType Leaf)) {
        $line = @(Get-Content -LiteralPath $localProperties |
            Where-Object { $_ -match '^\s*sdk\.dir\s*=' }) | Select-Object -First 1
        if ($line) { $sdk = ($line -replace '^\s*sdk\.dir\s*=\s*', '') -replace '\\\\', '\' }
    }
    if (-not $sdk) { throw 'No Android SDK: set ANDROID_HOME, sdk.dir or HUSHFEED_AAPT2.' }

    $buildTools = Join-Path $sdk 'build-tools'
    if (-not (Test-Path -LiteralPath $buildTools -PathType Container)) {
        throw "The SDK has no build-tools directory: $buildTools"
    }
    $candidate = Get-ChildItem -LiteralPath $buildTools -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw "No versioned build-tools under $buildTools." }

    $aapt2 = Join-Path $candidate.FullName 'aapt2.exe'
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        $aapt2 = Join-Path $candidate.FullName 'aapt2'
    }
    if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) {
        throw "build-tools $($candidate.Name) carries no aapt2."
    }
    return $aapt2
}

function ConvertFrom-XmlTreeValue {
    <#
    .SYNOPSIS
        The value of one aapt2 xmltree attribute, without its decoration.
    .DESCRIPTION
        aapt2 prints a string as `="text" (Raw: "text")` and everything else bare. The quoted
        half is taken when it is there, because the raw half repeats it and an attribute whose
        value contains a quote would otherwise be cut at the wrong place.
    #>
    param([string]$Text)

    if ($null -eq $Text) { return $null }
    $value = $Text.Trim()
    $rawAt = $value.IndexOf(' (Raw: ')
    if ($rawAt -ge 0) { $value = $value.Substring(0, $rawAt).Trim() }
    if ($value.Length -ge 2 -and $value.StartsWith('"') -and $value.EndsWith('"')) {
        return $value.Substring(1, $value.Length - 2)
    }
    return $value
}

function Get-ApkManifestFacts {
    <#
    .SYNOPSIS
        Package identity, requested permissions and exported components, read off an APK.
    .DESCRIPTION
        Components count as exported only when the manifest says so. Every target this project
        patches is above API 31, where an intent filter without an explicit android:exported is
        refused at install time, so there is no implicit case left to infer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Apk,
        [Parameter(Mandatory = $true)][string]$Aapt2
    )

    if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK not found: $Apk" }

    # Relaxed for the call: Windows PowerShell 5.1 throws on a native command's stderr under Stop, even redirected.
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $dump = @(& $Aapt2 dump xmltree --file AndroidManifest.xml $Apk 2>&1)
    } finally {
        $ErrorActionPreference = $preference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "aapt2 could not read the manifest of ${Apk}: $($dump -join ' ')"
    }
    return ConvertFrom-ManifestXmlTree -Lines $dump -Source (Split-Path -Leaf $Apk)
}

function ConvertFrom-ManifestXmlTree {
    <#
    .SYNOPSIS
        The same reading, from lines already in hand, so the parser can be tested without an APK.
    #>
    param([string[]]$Lines, [string]$Source)

    $componentElements = @('activity', 'activity-alias', 'service', 'receiver', 'provider')
    $packageName = $null
    $versionName = $null
    $versionCode = $null
    $permissions = New-Object System.Collections.Generic.List[string]
    $exported = New-Object System.Collections.Generic.List[string]

    $element = $null
    $componentName = $null
    $componentExported = $false

    function Complete-Component {
        param($Name, $Kind, $IsExported, $List)
        if ($Kind -and $IsExported -and $Name) { $List.Add("${Kind}:${Name}") }
    }

    foreach ($raw in $Lines) {
        $line = [string]$raw
        $elementMatch = [regex]::Match($line, '^\s*E:\s*([A-Za-z0-9_\-]+)\s*\(line=')
        if ($elementMatch.Success) {
            Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported
            $element = $elementMatch.Groups[1].Value
            $componentName = $null
            $componentExported = $false
            continue
        }

        $attributeMatch = [regex]::Match($line,
            '^\s*A:\s*(?:http://schemas\.android\.com/apk/res/android:)?([A-Za-z0-9_\-]+)(?:\(0x[0-9a-fA-F]+\))?=(.*)$')
        if (-not $attributeMatch.Success) { continue }
        $name = $attributeMatch.Groups[1].Value
        $value = ConvertFrom-XmlTreeValue -Text $attributeMatch.Groups[2].Value

        switch ($element) {
            'manifest' {
                if ($name -eq 'package') { $packageName = $value }
                elseif ($name -eq 'versionName') { $versionName = $value }
                elseif ($name -eq 'versionCode') { $versionCode = $value }
            }
            'uses-permission' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            'uses-permission-sdk-23' {
                if ($name -eq 'name' -and $value) { $permissions.Add($value) }
            }
            default {
                if ($componentElements -contains $element) {
                    if ($name -eq 'name') { $componentName = $value }
                    elseif ($name -eq 'exported') { $componentExported = ($value -eq 'true') }
                }
            }
        }
    }
    Complete-Component -Name $componentName -Kind $element -IsExported $componentExported -List $exported

    if (-not $packageName) { throw "No package name in the manifest of $Source." }

    # A component written as .Name is inside the package, and the two APKs have to spell the
    # same component the same way or every one of them reads as both added and removed.
    $qualified = New-Object System.Collections.Generic.List[string]
    foreach ($entry in $exported) {
        $kind, $name = $entry -split ':', 2
        if ($name.StartsWith('.')) { $name = $packageName + $name }
        elseif ($name -notmatch '\.') { $name = $packageName + '.' + $name }
        $qualified.Add("${kind}:${name}")
    }

    return [pscustomobject]@{
        package     = $packageName
        versionName = $versionName
        versionCode = $versionCode
        permissions = @($permissions | Sort-Object -Unique -CaseSensitive)
        exported    = @($qualified | Sort-Object -Unique -CaseSensitive)
    }
}

function Get-ManifestDelta {
    <#
    .SYNOPSIS
        What patching did to the manifest, as four sorted lists.
    #>
    param([Parameter(Mandatory = $true)]$Stock, [Parameter(Mandatory = $true)]$Patched)

    return [pscustomobject]@{
        permissionsAdded          = @(Compare-Sets -Left $Patched.permissions -Right $Stock.permissions)
        permissionsRemoved        = @(Compare-Sets -Left $Stock.permissions -Right $Patched.permissions)
        exportedComponentsAdded   = @(Compare-Sets -Left $Patched.exported -Right $Stock.exported)
        exportedComponentsRemoved = @(Compare-Sets -Left $Stock.exported -Right $Patched.exported)
    }
}

function Compare-Sets {
    <#
    .SYNOPSIS
        Everything in Left that Right does not have, sorted.
    #>
    param([string[]]$Left, [string[]]$Right)

    $other = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($Right), [System.StringComparer]::Ordinal)
    return @(@($Left) | Where-Object { -not $other.Contains($_) } | Sort-Object -Unique -CaseSensitive)
}

function ConvertTo-ManifestDeltaEntries {
    <#
    .SYNOPSIS
        One delta as the flat `kind value` lines the allowlist is written in.
    #>
    param([Parameter(Mandatory = $true)]$Delta)

    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($value in @($Delta.permissionsAdded)) { $entries.Add("permission-added $value") }
    foreach ($value in @($Delta.permissionsRemoved)) { $entries.Add("permission-removed $value") }
    foreach ($value in @($Delta.exportedComponentsAdded)) { $entries.Add("exported-added $value") }
    foreach ($value in @($Delta.exportedComponentsRemoved)) { $entries.Add("exported-removed $value") }
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

function Read-ManifestDeltaAllowlist {
    <#
    .SYNOPSIS
        The reviewed manifest changes. Blank lines and # comments are ignored.
    #>
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "The manifest delta allowlist is missing: $Path"
    }
    $entries = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -LiteralPath $Path) {
        $text = ([string]$line).Trim()
        if (-not $text -or $text.StartsWith('#')) { continue }
        if ($text -notmatch '^(permission-added|permission-removed|exported-added|exported-removed) \S+$') {
            throw "The manifest delta allowlist has a line that is not `"<kind> <value>`": $text"
        }
        $entries.Add($text)
    }
    # An allowlist with no entries comes back as $null, not an empty array: a PowerShell function
    # returning @() hands back nothing. Comma wrapping it would fix that and break every
    # `@(Read-ManifestDeltaAllowlist ...)` call site instead, which would then see one array
    # inside an array. Test-ReleaseReceipt drops the null on the way in.
    return @($entries | Sort-Object -Unique -CaseSensitive)
}

function Get-ChangelogVersions {
    <#
    .SYNOPSIS
        The versions a CHANGELOG names, in the order it names them.
    .DESCRIPTION
        Both shapes this file carries: a bare "## 0.32.0", a dated "## 0.14.0 (2026-09-05)", and
        the linked "## [0.1.5](compare/...) (2026-06-01)" the upstream generator wrote. Anything
        else under a level-two heading, "Unreleased" among them, is not a version and is ignored
        here; it is the absence of a version that this exists to notice.
    #>
    param([string]$Text)

    $found = New-Object System.Collections.Generic.List[string]
    foreach ($match in [regex]::Matches($Text, '(?m)^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')) {
        $found.Add($match.Groups[1].Value)
    }
    # No comma wrap. Every caller writes @(Get-ChangelogVersions ...), and @(,$array) is an
    # array holding one array: the membership test then finds nothing and the first element
    # prints as the whole list.
    return $found.ToArray()
}

function Test-ChangelogVersions {
    <#
    .SYNOPSIS
        Whether the CHANGELOG still describes every version it described at the last release,
        and describes the version being released now.
    .DESCRIPTION
        A released version's heading is the only record a reader has that it shipped. On
        2026-09-14 a post-release commit renamed "## 0.31.0" to "## Unreleased", so the file
        said that release never happened, and nothing noticed until the next release was cut by
        hand. No gate read this file at all.

        Held against the CHANGELOG as it stood at the last tag rather than against the tag list,
        so it needs no list of exceptions for versions that never had an entry: whatever was
        described then has to still be described now. Answers @{ Valid; Reason }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        # The same file at the last release tag. Absent on a checkout with no tag yet, in which
        # case only the version being released is checked.
        [string]$Previous,
        [string]$PreviousLabel = 'the last release'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    $now = @(Get-ChangelogVersions -Text $Current)
    if ($now.Count -eq 0) { return Fail 'The CHANGELOG names no version at all.' }
    if ($now -notcontains $ExpectedVersion) {
        return Fail ("The CHANGELOG has no heading for $ExpectedVersion, the version this " +
            "checkout builds. It names $($now[0]) first.")
    }

    if ($PSBoundParameters.ContainsKey('Previous') -and $null -ne $Previous) {
        $then = @(Get-ChangelogVersions -Text $Previous)
        $present = [System.Collections.Generic.HashSet[string]]::new(
            [string[]]$now, [System.StringComparer]::Ordinal)
        $lost = @($then | Where-Object { -not $present.Contains($_) })
        if ($lost.Count -gt 0) {
            return Fail ("The CHANGELOG described " + ($lost -join ', ') + " at $PreviousLabel " +
                "and does not now. A shipped version cannot stop having an entry.")
        }
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok' }
}

function Test-ChangelogManagerEntry {
    <#
    .SYNOPSIS
        Whether Morphe Manager can show the entry for the version being released.
    .DESCRIPTION
        Manager fetches this file from main and reads it with its own parser (ChangelogParser,
        Manager 1.30.0). A heading only counts when it ends in a date, "## 0.41.0 (2026-09-18)",
        and an app only gets its update badge when a bullet is scoped to it, "* **TikTok:** ...".
        Scoped lines are kept one line at a time, so a bullet wrapped onto a second line loses
        the rest. Every heading from 0.23.0 to 0.40.0 was bare, Manager's list stopped at 0.22.0
        and every update showed nothing, and no gate noticed, because Test-ChangelogVersions
        reads its own heading pattern, which the bare headings matched.

        Only the section being released is held to this. Older sections are frozen as shipped.
        Answers @{ Valid; Reason; Date; Bullets }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Current,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [string]$App = 'TikTok'
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason; Date = $null; Bullets = 0 } }

    # Manager's VERSION_HEADING and BULLET_SCOPE_RE, as its source spells them.
    $managerHeading = '^#{1,3}\s+(?:\S+\s+)?(?:\[([^\]]+)\]\([^)]*\)|([^\s\[(]+))\s+\((\d{4}-\d{2}-\d{2})\)'
    $managerScope = '^\* \*\*(.+?):\*\*'

    $lines = @($Current -split '\r?\n')
    $start = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $version = [regex]::Match($lines[$i], '^##\s+\[?v?(\d+\.\d+\.\d+(?:-[0-9A-Za-z.]+)?)')
        if ($version.Success -and $version.Groups[1].Value -eq $ExpectedVersion) { $start = $i; break }
    }
    if ($start -lt 0) { return Fail "The CHANGELOG has no heading for $ExpectedVersion." }

    $heading = [regex]::Match($lines[$start], $managerHeading)
    if (-not $heading.Success) {
        return Fail ("The $ExpectedVersion heading has no date, so Morphe Manager skips the whole " +
            "entry. Write it as ""## $ExpectedVersion (YYYY-MM-DD)"".")
    }
    $named = if ($heading.Groups[1].Success) { $heading.Groups[1].Value } else { $heading.Groups[2].Value }
    if ($named.TrimStart('v') -ne $ExpectedVersion) {
        return Fail ("Morphe Manager reads the $ExpectedVersion heading as version $named.")
    }

    $bullets = 0
    $previousWasBullet = $false
    for ($i = $start + 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        # The next level-one or level-two heading ends the section. "### Settings" inside it
        # groups bullets and is read by Manager as an ordinary line.
        if ($line -match '^#{1,2}(?!#)\s') { break }
        if ($line -match '^\s*[*+-]\s') {
            $scope = [regex]::Match($line, $managerScope)
            if (-not $scope.Success -or -not ($scope.Groups[1].Value -eq $App -or
                    $scope.Groups[1].Value.StartsWith("$App - "))) {
                return Fail ("Line $($i + 1) is a $ExpectedVersion bullet Morphe Manager does not " +
                    "scope to $App, so it is not counted as a $App change. Start it with " +
                    """* **${App}:** "": $line")
            }
            $bullets++
            $previousWasBullet = $true
            continue
        }
        if ($previousWasBullet -and -not [string]::IsNullOrWhiteSpace($line) -and $line -notmatch '^#') {
            return Fail ("Line $($i + 1) continues the bullet above it, and Morphe Manager keeps " +
                "scoped lines one at a time, so it would drop this text. Join it onto one line: $line")
        }
        $previousWasBullet = $false
    }
    if ($bullets -eq 0) {
        return Fail ("The $ExpectedVersion entry has no ""* **${App}:** "" bullet, so Morphe " +
            "Manager shows no update for $App.")
    }

    return [pscustomobject]@{ Valid = $true; Reason = 'ok'; Date = $heading.Groups[3].Value; Bullets = $bullets }
}

function Invoke-RepoGit {
    <#
    .SYNOPSIS
        git against the repository a caller names, with no inherited git environment.
    .DESCRIPTION
        `git -C <path>` sets the working directory and does not override GIT_DIR. Every script
        here runs from the pre-push hook, and a hook is a git child process with GIT_DIR and
        GIT_WORK_TREE already in its environment, so without this a -Root parameter is a
        suggestion rather than an instruction: git reads whichever repository the hook came from.
        On 2026-09-15 that turned a temporary fixture into three commits on the real branch.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name) -ErrorAction SilentlyContinue
    }
    # Windows PowerShell 5.1 turns a native command's stderr into a terminating error under
    # Stop even when it is redirected. Relax for the call and restore afterwards.
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        return & git -C $Root @Arguments 2>$null
    } finally {
        $ErrorActionPreference = $preference
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Read-CatalogToolchain {
    <#
    .SYNOPSIS
        The patcher pin and Manager floor out of a version catalog's text.
    .DESCRIPTION
        Takes text rather than a path, so the same reading applies to the catalog in the working
        tree and to the one at an older commit. Every failure names where the text came from,
        because "does not pin morphe-patcher" means something different in the working tree than
        at a commit from a release that shipped months ago.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][string]$Source
    )

    $patcherMatch = [regex]::Match($Text, '(?m)^\s*morphe-patcher\s*=\s*"([^"]+)"')
    if (-not $patcherMatch.Success) { throw "$Source does not pin morphe-patcher." }
    $floorMatch = [regex]::Match($Text, '(?m)^\s*manager-floor\s*=\s*"([^"]+)"')
    if (-not $floorMatch.Success) {
        throw "$Source does not pin manager-floor beside morphe-patcher."
    }
    $floor = $floorMatch.Groups[1].Value
    if ($floor -notmatch '^\d+\.\d+\.\d+$') {
        throw "$Source has an invalid manager-floor: $floor"
    }
    return [pscustomobject]@{
        PatcherVersion = $patcherMatch.Groups[1].Value
        ManagerFloor   = $floor
    }
}

function Resolve-ReceiptToolchain {
    <#
    .SYNOPSIS
        The toolchain a receipt should be held to: the one its own commit pinned.
    .DESCRIPTION
        A receipt describes a release that has already shipped. Moving the patcher pin afterwards
        does not make it wrong, but holding it to the working catalog said it was: after the pin
        moved to 1.13.0 while the tree still carried the receipt for the 0.32.0 release, every
        source push failed with "The receipt was stamped by patcher 1.12.0; the catalog pins
        1.13.0", with both files correct. Only the checkout that cut the release has a receipt at
        all, so the gate stopped exactly the machine that had done the work, and the way through
        was to move the receipt out of the tree, which turns the check off altogether.

        On a release push the receipt's commit is the release commit, so this reads the same
        catalog the working tree has and nothing is relaxed.

        Answers @{ Toolchain; Note }, where Note is a line worth printing or $null when the
        answer is simply the working catalog.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingToolchain
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ Toolchain = $WorkingToolchain; Note = $null }
    }

    # Stderr is dropped inside the helper, because git writes "path does not exist in commit"
    # there and an empty result here has to mean "no catalog at that commit", not "git said
    # something".
    $catalogAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:gradle/libs.versions.toml")) -join "`n"
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($catalogAtCommit)) {
        return [pscustomobject]@{
            Toolchain = $WorkingToolchain
            Note = "commit $short has no version catalog, so the receipt is held to the working one"
        }
    }

    $atCommit = Read-CatalogToolchain -Text $catalogAtCommit `
        -Source "gradle/libs.versions.toml at $short"
    if ($atCommit.PatcherVersion -eq $WorkingToolchain.PatcherVersion -and
        $atCommit.ManagerFloor -eq $WorkingToolchain.ManagerFloor) {
        return [pscustomobject]@{ Toolchain = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        Toolchain = $atCommit
        Note = ("the receipt is held to patcher $($atCommit.PatcherVersion) and Manager floor " +
            "$($atCommit.ManagerFloor), which its own commit $short pinned; the catalog now pins " +
            "$($WorkingToolchain.PatcherVersion) and $($WorkingToolchain.ManagerFloor)")
    }
}

function Resolve-ReceiptCatalog {
    <#
    .SYNOPSIS
        The patch list a receipt should be held to: the one its own commit carried.
    .DESCRIPTION
        The same reasoning as Resolve-ReceiptToolchain, for the patch names and the target. A patch
        added or renamed after a release doesn't make that release's receipt wrong, but holding
        the receipt to the working patch list said it was. While the source version stays on the
        released one, as it did through the hold after 0.58.0, every such push failed on the one
        machine that has a receipt: "The receipt reports com.zhiliaoapp.musically 46.2.3 patch Hide
        quick comment reactions, which the catalog does not list."

        On a release push the receipt's commit is the release commit, so this reads the list the
        working tree has and nothing is relaxed.

        Answers @{ PatchList; Note }: the parsed patches-list.json to hold the receipt to, and a
        line worth printing, or $null when the answer is simply the working list.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [string]$Commit,
        [Parameter(Mandatory = $true)]$WorkingPatchList
    )

    if ($Commit -notmatch '^[0-9a-f]{40}$') {
        return [pscustomobject]@{ PatchList = $WorkingPatchList; Note = $null }
    }
    $listAtCommit = (Invoke-RepoGit -Root $Root -Arguments @('show', "${Commit}:patches-list.json")) -join "`n"
    $short = $Commit.Substring(0, 8)
    if ([string]::IsNullOrWhiteSpace($listAtCommit)) {
        return [pscustomobject]@{
            PatchList = $WorkingPatchList
            Note = "commit $short has no patch list, so the receipt is held to the working one"
        }
    }
    try {
        $atCommit = $listAtCommit | ConvertFrom-Json
    } catch {
        throw "patches-list.json at $short is not readable: $($_.Exception.Message)"
    }
    $namesThen = @($atCommit.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $namesNow = @($WorkingPatchList.patches | ForEach-Object { [string]$_.name } | Sort-Object) -join "`n"
    $targetsThen = ($atCommit.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    $targetsNow = ($WorkingPatchList.patches | ForEach-Object { $_.compatiblePackages | ConvertTo-Json -Compress -Depth 4 } | Sort-Object -Unique) -join "`n"
    if ($namesThen -eq $namesNow -and $targetsThen -eq $targetsNow) {
        return [pscustomobject]@{ PatchList = $atCommit; Note = $null }
    }
    return [pscustomobject]@{
        PatchList = $atCommit
        Note = ("the receipt is held to the $(@($atCommit.patches).Count) patches and the target its " +
            "own commit $short carried; the working list has $(@($WorkingPatchList.patches).Count)")
    }
}

function Test-ReleaseReceipt {
    <#
    .SYNOPSIS
        Whether a receipt describes this checkout's release, and whether its manifest changes
        were reviewed.
    .DESCRIPTION
        Answers @{ Valid; Reason }. Every failure names the one fact that did not line up,
        because the point of the receipt is to say which input was wrong, not that something was.
    #>
    param(
        [Parameter(Mandatory = $true)]$Receipt,
        [Parameter(Mandatory = $true)][string]$ExpectedVersion,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPatchNames,
        [Parameter(Mandatory = $true)][string]$ExpectedPatcherVersion,
        [Parameter(Mandatory = $true)][string]$ExpectedManagerFloor,
        # The package and versions the catalog declares. Without them a receipt built only from
        # forced runs against newer builds reads as proof of the release, when nothing in it was
        # patched the way a user's Manager patches it. Each declared version needs its own run.
        [Parameter(Mandatory = $true)][string]$ExpectedPackageName,
        [Parameter(Mandatory = $true)][string[]]$ExpectedPackageVersions,
        [string]$BundlePath,
        [string]$ReceiptPath,
        [string[]]$ApprovedManifestDelta = @(),
        # When the commit the receipt names was made, read out of git by the caller. Without it
        # the receipt's commit and its timestamp are only checked against each other, which any
        # receipt agrees with, including one left over from an earlier release.
        [long]$ActualCommitTimestamp = 0,
        # The commit this run is about. Only a release is held to it: on an ordinary push the
        # receipt legitimately describes the commit it was generated at, not HEAD.
        [string]$ExpectedCommit
    )

    function Fail { param([string]$Reason) return [pscustomobject]@{ Valid = $false; Reason = $Reason } }

    if ($null -eq $Receipt) { return Fail 'There is no receipt to check.' }
    $legacy = [int]$Receipt.schemaVersion -eq 1
    if ($legacy -and -not (Test-FrozenLegacyReceipt -Receipt $Receipt -ReceiptPath $ReceiptPath)) {
        return Fail 'Schema 1 is read-only for exact published receipts; a new receipt requires build-time source provenance.'
    }
    if (-not $legacy -and [int]$Receipt.schemaVersion -ne (Get-ReleaseReceiptSchemaVersion)) {
        return Fail ("The receipt is schema version $($Receipt.schemaVersion); this checkout " +
            "reads version $(Get-ReleaseReceiptSchemaVersion).")
    }
    if ($Receipt.release.version -ne $ExpectedVersion) {
        return Fail "The receipt is for $($Receipt.release.version), not $ExpectedVersion."
    }
    if ($Receipt.release.tag -ne "v$ExpectedVersion") {
        return Fail "The receipt names tag $($Receipt.release.tag) for version $ExpectedVersion."
    }
    if ($Receipt.release.commit -notmatch '^[0-9a-f]{40}$') {
        return Fail "The receipt has no full commit: $($Receipt.release.commit)"
    }
    if ([long]$Receipt.release.commitTimestamp -le 0) {
        return Fail 'The receipt does not say when the commit it names was made.'
    }
    if ($ExpectedCommit -and $Receipt.release.commit -ne $ExpectedCommit) {
        return Fail ("The receipt describes commit $($Receipt.release.commit); this release is " +
            "$ExpectedCommit.")
    }
    # Against git, not against the receipt's own other field. The bundle stamp check below
    # compares two numbers that both came out of this document, so on its own it proves only
    # that the document agrees with itself.
    if ($ActualCommitTimestamp -gt 0 -and
            [long]$Receipt.release.commitTimestamp -ne $ActualCommitTimestamp) {
        return Fail ("The receipt says commit $($Receipt.release.commit) was made at " +
            "$($Receipt.release.commitTimestamp); git says $ActualCommitTimestamp.")
    }
    if ([int]$Receipt.release.patchCount -ne $ExpectedPatchNames.Count) {
        return Fail ("The receipt counts $($Receipt.release.patchCount) patches; the catalog " +
            "has $($ExpectedPatchNames.Count).")
    }
    if ($Receipt.toolchain.patcherVersion -ne $ExpectedPatcherVersion) {
        return Fail ("The receipt was stamped by patcher $($Receipt.toolchain.patcherVersion); " +
            "the catalog pins $ExpectedPatcherVersion.")
    }
    if ($Receipt.toolchain.managerFloor -ne $ExpectedManagerFloor) {
        return Fail ("The receipt names Manager floor $($Receipt.toolchain.managerFloor); " +
            "the catalog pins $ExpectedManagerFloor.")
    }
    if (-not $legacy) {
        $sourceCheck = Test-BuildSourceFacts -Source $Receipt.source -ExpectedCommit $Receipt.release.commit
        if (-not $sourceCheck.Valid) { return Fail $sourceCheck.Reason }
    }

    if ($BundlePath) {
        if (-not (Test-Path -LiteralPath $BundlePath -PathType Leaf)) {
            return Fail "The receipt cannot be checked against a bundle that is not there: $BundlePath"
        }
        $actualSize = (Get-Item -LiteralPath $BundlePath).Length
        if ([long]$Receipt.bundle.sizeBytes -ne $actualSize) {
            return Fail ("The receipt says the bundle is $($Receipt.bundle.sizeBytes) bytes; " +
                "$BundlePath is $actualSize.")
        }
        $actualHash = Get-Sha256Hex -Path $BundlePath
        if ([string]$Receipt.bundle.sha256 -ne $actualHash) {
            return Fail ("The receipt says the bundle hashes to $($Receipt.bundle.sha256); " +
                "$BundlePath hashes to $actualHash.")
        }

        $manifest = Get-BundleManifestFacts -BundlePath $BundlePath
        if ($manifest.version -ne $ExpectedVersion) {
            return Fail "The bundle's manifest says version $($manifest.version), not $ExpectedVersion."
        }
        if ($manifest.patcherVersion -ne $ExpectedPatcherVersion) {
            return Fail ("The bundle was stamped by patcher $($manifest.patcherVersion); the " +
                "catalog pins $ExpectedPatcherVersion.")
        }
        if ([long]$Receipt.bundle.timestamp -ne $manifest.timestamp) {
            return Fail ("The receipt says the bundle is stamped $($Receipt.bundle.timestamp); " +
                "$BundlePath is stamped $($manifest.timestamp).")
        }
        # The release timestamp policy remains a separate reproducibility check. Equality alone
        # cannot identify a source commit, and SOURCE_DATE_EPOCH can set it on a dirty build.
        $expectedStamp = [long]$Receipt.release.commitTimestamp * 1000
        if ($manifest.timestamp -ne $expectedStamp) {
            return Fail ("The bundle is stamped $($manifest.timestamp) but the commit it is " +
                "attributed to was made at $expectedStamp. A different commit or configured " +
                "SOURCE_DATE_EPOCH can cause this reproducibility mismatch.")
        }
        if (-not $legacy) {
            $sourceCheck = Test-BuildSourceFacts -Source $manifest.source -ExpectedCommit $Receipt.release.commit
            if (-not $sourceCheck.Valid) { return Fail $sourceCheck.Reason }
            if ($manifest.source.startFingerprint -ne $Receipt.source.startFingerprint -or
                    $manifest.source.endFingerprint -ne $Receipt.source.endFingerprint) {
                return Fail 'The receipt source fingerprints do not match the built bundle.'
            }
        }
    }

    # Nulls dropped first. A receipt with the key missing altogether gives $null here, and
    # @($null) is an array holding one null, so the count guard below would walk past a truncated
    # document and then blame the nameless entry it found instead of the missing section.
    $payloads = @(@($Receipt.extension.dexPayloads) | Where-Object { $null -ne $_ })
    if ($payloads.Count -eq 0) {
        return Fail 'The receipt records no extension DEX payload, so it identifies no extension.'
    }
    foreach ($payload in $payloads) {
        if (-not $payload.name) { return Fail 'A recorded DEX payload has no name.' }
        if ([long]$payload.sizeBytes -le 0) {
            return Fail "The recorded DEX payload $($payload.name) is empty."
        }
        if ([string]$payload.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The recorded DEX payload $($payload.name) has no SHA-256."
        }
    }

    $targets = @(@($Receipt.targets) | Where-Object { $null -ne $_ })
    if ($targets.Count -eq 0) { return Fail 'The receipt records no target, so nothing was proved.' }

    $expected = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$ExpectedPatchNames, [System.StringComparer]::Ordinal)
    $produced = New-Object System.Collections.Generic.List[string]
    $provedVersions = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
    foreach ($target in $targets) {
        $label = "$($target.source.package) $($target.source.versionName)"
        if ([string]$target.source.sha256 -notmatch '^[0-9A-F]{64}$') {
            return Fail "The receipt records no source APK hash for $label."
        }
        if ([string]$target.source.package -ne $ExpectedPackageName) {
            return Fail ("The receipt records a run against $($target.source.package); the " +
                "catalog targets $ExpectedPackageName.")
        }
        if ([string]::IsNullOrWhiteSpace([string]$target.source.versionName)) {
            return Fail "The receipt records a $ExpectedPackageName run with no version name."
        }
        # Whether the CLI was told to ignore the declared version. Recorded as a boolean by the
        # builder; a receipt that leaves it out cannot say which of its runs were the real one.
        $forcedProperty = $target.source.PSObject.Properties['forced']
        if ($null -eq $forcedProperty -or $forcedProperty.Value -isnot [bool]) {
            return Fail "The receipt does not say whether $label was patched under -f."
        }
        $atDeclaredVersion = [string]$target.source.versionName -cin $ExpectedPackageVersions
        if ($forcedProperty.Value -eq $atDeclaredVersion) {
            return Fail ("The receipt says $label was " +
                $(if ($forcedProperty.Value) { 'forced past' } else { 'patched without -f at' }) +
                " a declared version, but the catalog targets $($ExpectedPackageVersions -join ', ').")
        }
        if ($atDeclaredVersion) { [void]$provedVersions.Add([string]$target.source.versionName) }
        $verdicts = @($target.patches)
        if ($verdicts.Count -ne $ExpectedPatchNames.Count) {
            return Fail ("The receipt records $($verdicts.Count) patch verdicts for $label; " +
                "the catalog has $($ExpectedPatchNames.Count).")
        }
        $seen = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::Ordinal)
        foreach ($verdict in $verdicts) {
            $name = [string]$verdict.name
            if (-not $expected.Contains($name)) {
                return Fail "The receipt reports $label patch $name, which the catalog does not list."
            }
            if (-not $seen.Add($name)) {
                return Fail "The receipt reports $label patch $name twice."
            }
            if (-not $verdict.applied) {
                return Fail "The receipt reports $label patch $name as not applied."
            }
        }
        foreach ($entry in ConvertTo-ManifestDeltaEntries -Delta $target.manifestDelta) {
            $produced.Add($entry)
        }
    }
    $unproved = @($ExpectedPackageVersions | Where-Object { -not $provedVersions.Contains($_) })
    if ($unproved.Count -gt 0) {
        $ran = @($targets | ForEach-Object { [string]$_.source.versionName }) -join ', '
        return Fail ("No target in the receipt is the declared $ExpectedPackageName " +
            "$($unproved -join ', ') patched without -f; it only records $ran.")
    }

    # A PowerShell function that returns an empty array hands back nothing, so an allowlist with
    # no entries arrives here as $null, and @($null) is an array holding one null. Left alone,
    # that null is an approved entry no delta produces and every clean run fails on it.
    $approvedEntries = @(@($ApprovedManifestDelta) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $approved = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]$approvedEntries, [System.StringComparer]::Ordinal)
    $unapproved = @(@($produced | Sort-Object -Unique -CaseSensitive) | Where-Object { -not $approved.Contains($_) })
    if ($unapproved.Count -gt 0) {
        return Fail ('The patched manifest changed in ways nobody reviewed: ' +
            ($unapproved -join ', '))
    }
    $seenEntries = [System.Collections.Generic.HashSet[string]]::new(
        [string[]]@($produced), [System.StringComparer]::Ordinal)
    $stale = @($approvedEntries | Where-Object { -not $seenEntries.Contains($_) })
    if ($stale.Count -gt 0) {
        return Fail ('The manifest delta allowlist approves changes no patch makes any more: ' +
            ($stale -join ', '))
    }

    return [pscustomobject]@{ Valid = $true; Reason = $null }
}
