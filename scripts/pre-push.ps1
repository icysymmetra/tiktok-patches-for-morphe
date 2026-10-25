<#
.SYNOPSIS
    Checks a push before it leaves the machine.

.DESCRIPTION
    This project builds nothing on GitHub, so a push is the last point at which anything can
    be checked. Its only reported failure so far was a source index that advertised a bundle
    the release did not carry, which the release check catches when it is actually run.

    Called by .git/hooks/pre-push with the remote name and URL, reading the pushed refs from
    standard input the way git supplies them. Run scripts/install-hooks.ps1 once to wire it up.

    Only what changed is checked: runtime tests when extension or patch sources move, every patch
    applied to each declared TikTok build when bundle inputs move, and the release facts when a
    published file moves. Set HUSHFEED_SKIP_PRE_PUSH=1 to push anyway.
#>
[CmdletBinding()]
param(
    [Parameter(Position = 0)][string]$RemoteName,
    [Parameter(Position = 1)][string]$RemoteUrl,
    [string]$Root,
    [string[]]$ChangedPaths,
    [string]$PushedRefs,
    # How long a push waits for another push from this checkout to finish with the gate worktree.
    [int]$GateLockTimeoutSeconds = 3600
)

$ErrorActionPreference = 'Stop'

# Not a parameter default. Windows PowerShell leaves $PSScriptRoot empty while it evaluates the
# defaults of an advanced script started with -File, and any [CmdletBinding()] or [Parameter(...)]
# attribute makes a script advanced, so the default threw and the hook failed before it checked
# anything. The hook prefers pwsh, which does not have this, and falls back to Windows PowerShell
# wherever pwsh is off the PATH: a git hook runs with git's environment, so that is the ordinary
# case rather than the rare one.
if (-not $Root) { $Root = Split-Path -Parent $PSScriptRoot }
# Get-PatchTarget and Format-VersionList: the declared builds the fixture gate applies patches to.
. (Join-Path $PSScriptRoot 'patch-target.ps1')

# A hook runs with git's own environment. User environment variables set after the shell
# launched, or set in the user scope only, may be absent. Import the ones this script and
# its suites need from the registry so a gate worktree can find the desktop CLI, the
# fixture folder, the build governor and the device serial, and knows whether to apply the
# fixtures one at a time.
foreach ($envName in @('HUSHFEED_DESKTOP_JAR', 'HUSHFEED_FIXTURE_DIR',
        'HUSHFEED_BUILD_WRAPPER', 'HUSHFEED_DEVICE_SERIAL', 'HUSHFEED_GATE_SERIAL')) {
    if (-not (Test-Path "Env:\$envName")) {
        $regValue = [Environment]::GetEnvironmentVariable($envName, [EnvironmentVariableTarget]::User)
        if ($regValue) { Set-Item -LiteralPath "Env:\$envName" -Value $regValue }
    }
}

$zeroObject = '0' * 40
# The commits this push carries, peeled, filled in by Get-PushedPaths. The build gate builds each
# of these, and never whatever else the working tree holds.
$script:pushedCommits = New-Object System.Collections.Generic.List[string]
# Each pushed local ref with the object the gates were given for it, checked again at the end.
# Not $pushedRefs: variable names ignore case, and that one is a parameter of this script.
$script:checkedRefs = New-Object System.Collections.Generic.List[object]
# Whether a real diff, not a new branch's whole tree, moved the source index. Every tree holds
# patches-bundle.json, so a first push used to read as an index push and skip patching.
$script:indexChanged = $false

function Write-Step {
    param([string]$Message)
    Write-Host "[pre-push] $Message"
}

function Get-PushedPaths {
    <#
        Git writes "<local ref> <local sha> <remote ref> <remote sha>" per ref on stdin. A remote
        sha of all zeroes means the branch is new there. A new branch is checked conservatively
        from its complete resulting tree, so a stale local tracking ref cannot hide a code path.

        Read from the console rather than $input: a script started with -File binds stdin to its
        parameters, so piping into it fails to bind and leaves $input empty, which made the hook
        report that a push changed nothing.
    #>
    param([string]$Text)

    $paths = New-Object System.Collections.Generic.HashSet[string]
    foreach ($line in ($Text -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $parts = $line.Trim() -split '\s+'
        if ($parts.Count -lt 4) { continue }
        $localSha = $parts[1]
        $remoteSha = $parts[3]
        if ($localSha -eq $zeroObject) { continue }

        if ($remoteSha -eq $zeroObject) {
            # A new tag of an already hosted branch adds no files. In particular it does not
            # rewrite the source index, which must keep naming the previous working bundle
            # until the new release asset exists. Trust a live advertisement, not tracking refs.
            if ($parts[2] -like 'refs/tags/*' -and $RemoteUrl) {
                $target = Invoke-GitQuietly @('rev-parse', '--verify', "$localSha^{commit}")
                if ($LASTEXITCODE -ne 0) { throw "Could not resolve tag target $localSha." }
                $advertised = @(Invoke-GitQuietly @('ls-remote', '--heads', $RemoteUrl))
                if ($LASTEXITCODE -ne 0) { throw 'Could not read remote branches to verify the tag target.' }
                $targetPattern = '^' + [regex]::Escape(([string]$target).Trim()) + '\s+refs/heads/'
                if (@($advertised | Where-Object { $_ -match $targetPattern }).Count -gt 0) {
                    Write-Step 'new tag names an advertised remote branch commit; no file changes'
                    continue
                }
            }
            # This intentionally runs the relevant gate for any matching path in the tree, even
            # when the branch changed only documentation. A first push is rare, and a complete
            # tree cannot be made incomplete by a deleted or force-updated remote-tracking ref.
            $range = "$localSha complete branch tree"
            $names = Invoke-GitQuietly @('ls-tree', '-r', '--name-only', '-z', $localSha)
        } else {
            $range = "$remoteSha..$localSha"
            # --no-renames: a rename is its old path and its new one. With rename detection
            # only the new path came back, so a file moved out of extensions/ read as a docs
            # change and built nothing. -z: paths come back as they are, where git otherwise
            # wraps a name with a non-ASCII letter in quotes that no pattern below matches.
            $names = Invoke-GitQuietly @('diff', '--name-only', '--no-renames', '-z', $remoteSha, $localSha)
            if (@((@($names) -join "`n") -split "`0" | Where-Object { $_.Trim() -eq 'patches-bundle.json' }).Count -gt 0) {
                $script:indexChanged = $true
            }
        }
        if ($LASTEXITCODE -ne 0) {
            throw "Could not read what $range changes. Fetch the remote and try again."
        }
        foreach ($name in @((@($names) -join "`n") -split "`0")) {
            if (-not [string]::IsNullOrWhiteSpace($name)) { [void]$paths.Add($name.Trim()) }
        }
        $script:checkedRefs.Add([pscustomobject]@{ LocalRef = $parts[0]; LocalSha = $localSha })
        $commit = Invoke-GitQuietly @('rev-parse', '--verify', "$localSha^{commit}")
        if ($LASTEXITCODE -eq 0 -and $commit -and -not $script:pushedCommits.Contains(([string]$commit).Trim())) {
            $script:pushedCommits.Add(([string]$commit).Trim())
        }
    }
    return $paths
}

function Invoke-GitQuietly {
    <#
        git in this repository with its standard error dropped, returning standard output and
        leaving $LASTEXITCODE for the caller to read. Windows PowerShell 5.1 turns a native
        command's standard error into a terminating error under Stop even when it is redirected,
        so a warning, or the "fatal:" a missing object prints, stopped the hook with a
        NativeCommandError before the caller could say what went wrong.
    #>
    param([string[]]$Arguments)
    $preference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & git @Arguments 2>$null
    } finally {
        $ErrorActionPreference = $preference
    }
}

function Invoke-WithoutGitEnvironment {
    <#
        Runs $Action with every GIT_* variable removed, and puts them back after. Git exports
        GIT_DIR and its relatives to a hook, and git -C does not override them, so a worktree
        command run with them set would act on this repository's own working tree rather than the
        one it names. A build started with them set would read this checkout's git state too.
    #>
    param([Parameter(Mandatory = $true)][scriptblock]$Action)
    $saved = @{}
    foreach ($variable in @(Get-ChildItem Env: | Where-Object { $_.Name -like 'GIT_*' })) {
        $saved[$variable.Name] = $variable.Value
        Remove-Item -LiteralPath ('Env:\' + $variable.Name)
    }
    try {
        & $Action
    } finally {
        foreach ($name in $saved.Keys) { Set-Item -LiteralPath ('Env:\' + $name) -Value $saved[$name] }
    }
}

function Invoke-HookGit {
    <#
        git without the hook's own GIT_* environment, returning standard output and throwing on
        failure. Windows PowerShell 5.1 turns a native command's standard error into a terminating
        error under Stop, so that preference is relaxed here.
    #>
    param([string[]]$Arguments)
    Invoke-WithoutGitEnvironment {
        $preference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $errors = New-Object System.Collections.Generic.List[string]
            $output = @(& git @Arguments 2>&1 | ForEach-Object {
                if ($_ -is [System.Management.Automation.ErrorRecord]) { $errors.Add($_.ToString()) } else { $_ }
            })
            if ($LASTEXITCODE -ne 0) { throw "git $($Arguments -join ' ') failed: $($errors -join ' ')" }
            return $output
        } finally {
            $ErrorActionPreference = $preference
        }
    }
}

function Get-GateKey {
    # Names this checkout's gate worktree and lock, so two checkouts never share either.
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $digest = $hasher.ComputeHash([Text.Encoding]::UTF8.GetBytes([IO.Path]::GetFullPath($Root).ToLowerInvariant()))
    } finally {
        $hasher.Dispose()
    }
    return -join ($digest[0..5] | ForEach-Object { $_.ToString('x2') })
}

function Enter-GateLock {
    <#
        One push at a time through this checkout's gate worktree. Two pushes from one checkout,
        two agents sharing a tree for instance, would otherwise share it, and the second one's
        checkout could land while the first one's build was still reading the tree, so the first
        verdict would describe the second commit. Returns the held mutex; release it when done.
    #>
    $mutex = New-Object System.Threading.Mutex($false, "Local\hushfeed-pre-push-$(Get-GateKey)")
    $owned = $false
    try {
        $owned = $mutex.WaitOne([TimeSpan]::FromSeconds($GateLockTimeoutSeconds))
    } catch [System.Threading.AbandonedMutexException] {
        # The last holder ended without letting go. The worktree is reset before every build.
        $owned = $true
    }
    if (-not $owned) {
        $mutex.Dispose()
        throw ("Another push from this checkout held the gate worktree for $GateLockTimeoutSeconds " +
            'seconds. Let it finish and push again.')
    }
    return $mutex
}

function Get-GateWorktree {
    <#
        A clean worktree of $Commit in the temp directory, reused between pushes so its build
        folder stays warm. Hold Enter-GateLock while using it.
    #>
    param([Parameter(Mandatory = $true)][string]$Commit)
    $tree = Join-Path ([IO.Path]::GetTempPath()) "hushfeed-pre-push-$(Get-GateKey)"
    if (Test-Path -LiteralPath (Join-Path $tree '.git')) {
        Invoke-HookGit @('-C', $tree, 'checkout', '--detach', '--force', '--quiet', $Commit) | Out-Null
        Invoke-HookGit @('-C', $tree, 'clean', '-fdxq', '-e', 'build', '-e', '.gradle', '-e', 'local.properties') | Out-Null
    } else {
        if (Test-Path -LiteralPath $tree) { Remove-Item -LiteralPath $tree -Recurse -Force }
        Invoke-HookGit @('-C', $Root, 'worktree', 'prune') | Out-Null
        Invoke-HookGit @('-C', $Root, 'worktree', 'add', '--detach', '--quiet', $tree, $Commit) | Out-Null
    }
    $properties = Join-Path $Root 'local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        Copy-Item -LiteralPath $properties -Destination (Join-Path $tree 'local.properties') -Force
    }
    $at = ([string](Invoke-HookGit @('-C', $tree, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
    if ($at -ne $Commit) { throw "The gate worktree $tree is at $at, not $Commit." }
    $left = @(Invoke-HookGit @('-C', $tree, 'status', '--porcelain'))
    if ($left.Count -gt 0) { throw "The gate worktree $tree is not clean: $($left -join '; ')" }
    return $tree
}

function Get-DeclaredFixtures {
    <#
        The universal APK of each TikTok build the pushed commit's catalog declares, from the folder
        HUSHFEED_FIXTURE_DIR names, found the way the fixture tests find one (Fixtures.apkOf): the
        APKMirror name holds "_<version>-" and the short one is "tiktok-<version>.apk". A declared
        build without exactly one stops the push, since a skipped build is the gap this closes.
    #>
    param([Parameter(Mandatory = $true)][string]$GateRoot)
    $folder = $env:HUSHFEED_FIXTURE_DIR
    if (-not $folder -or -not (Test-Path -LiteralPath $folder -PathType Container)) {
        throw ('Patch sources changed, so every patch is applied to each declared TikTok build, and ' +
            "HUSHFEED_FIXTURE_DIR names no folder ('$folder'). Point it at the vendor APKs.")
    }
    if (-not $env:HUSHFEED_DESKTOP_JAR -or -not (Test-Path -LiteralPath $env:HUSHFEED_DESKTOP_JAR -PathType Leaf)) {
        throw ('Patch sources changed, so every patch is applied to each declared TikTok build, and ' +
            "HUSHFEED_DESKTOP_JAR names no file ('$env:HUSHFEED_DESKTOP_JAR'). Point it at the Morphe desktop CLI.")
    }
    $catalogPath = Join-Path $GateRoot 'patches-list.json'
    if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) { throw "No patch list at $catalogPath." }
    $target = Get-PatchTarget -PatchList (Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json)
    $apks = @(Get-ChildItem -LiteralPath $folder -Filter '*.apk' -File)
    foreach ($version in @($target.PackageVersions)) {
        $found = @($apks | Where-Object { $_.Name.Contains("_$version-") -or $_.Name -eq "tiktok-$version.apk" })
        if ($found.Count -ne 1) {
            throw ("HUSHFEED_FIXTURE_DIR ($folder) holds $($found.Count) universal APKs of TikTok $version, " +
                'not one, and the pushed catalog declares that build.')
        }
        [pscustomobject]@{ Version = $version; Apk = $found[0].FullName }
    }
}

function Invoke-FixturePatching {
    <#
        Applies every patch of the bundle :patches:buildAndroid just left in the gate root to each
        declared build, with that root's own verify-all-patches.ps1 and the desktop CLI, all builds
        at once. No test under patches/src/test runs the patcher: de43a43e passed the whole gate with
        a fingerprint that matched nothing on 47.0.3, and only the release receipt would have applied
        it. Every build is waited for, so one failure doesn't hide another.
    #>
    param([Parameter(Mandatory = $true)][string]$GateRoot, [Parameter(Mandatory = $true)][object[]]$Fixtures)
    $verify = Join-Path $GateRoot 'scripts/verify-all-patches.ps1'
    if (-not (Test-Path -LiteralPath $verify -PathType Leaf)) { throw "The pushed commit has no $verify to apply its patches with." }
    $bundles = @(Get-ChildItem -LiteralPath (Join-Path $GateRoot 'patches/build/release') -Filter '*.mpp' -File -ErrorAction SilentlyContinue)
    if ($bundles.Count -ne 1) {
        throw "patches/build/release in $GateRoot holds $($bundles.Count) bundles after :patches:buildAndroid, not one."
    }
    $patchList = Join-Path $GateRoot 'patches-list.json'
    $work = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-pre-push-apply-' + [guid]::NewGuid().ToString('N'))
    # Each passing run is kept, stamped, for the release receipt (build-release-receipt.ps1
    # -AppliedDir), so a release patches each fixture once. A stale run from an earlier bundle
    # goes first: its stamp would not match anyway.
    $kept = Join-Path $GateRoot 'patches/build/fixture-apply'
    if (Test-Path -LiteralPath $kept) { Remove-Item -LiteralPath $kept -Recurse -Force }
    Write-Step ("patch sources changed, applying $($bundles[0].Name) to TikTok " +
        (Format-VersionList -Versions @($Fixtures | ForEach-Object { $_.Version })) + ' with the desktop CLI')
    $runs = New-Object 'System.Collections.Generic.List[object]'
    try {
        Invoke-WithoutGitEnvironment {
            foreach ($fixture in $Fixtures) {
                $job = Start-Job -ArgumentList $verify, $fixture.Apk, $env:HUSHFEED_DESKTOP_JAR,
                    (Join-Path $work $fixture.Version), $bundles[0].FullName, $patchList, (Join-Path $kept $fixture.Version) -ScriptBlock {
                    param($Verify, $Apk, $Jar, $WorkDir, $Bundle, $PatchList, $KeepIn)
                    $global:LASTEXITCODE = 0
                    try {
                        & $Verify -Apk $Apk -DesktopJar $Jar -WorkDir $WorkDir -Bundle $Bundle -PatchList $PatchList -KeepIn $KeepIn *>&1 |
                            ForEach-Object { "$_" }
                        $code = $LASTEXITCODE
                    } catch {
                        "$_"
                        $code = 1
                    }
                    [pscustomobject]@{ ExitCode = $code }
                }
                $runs.Add([pscustomobject]@{ Version = $fixture.Version; Job = $job })
                # A desktop CLI run can take a quarter of the machine's memory, so a machine short of
                # it sets HUSHFEED_GATE_SERIAL=1 and applies one build at a time.
                if ($env:HUSHFEED_GATE_SERIAL -eq '1') { Wait-Job -Job $job | Out-Null }
            }
        }
        $failed = @()
        foreach ($run in $runs) {
            $code = 1
            try {
                foreach ($item in @(Receive-Job -Job $run.Job -Wait -ErrorAction Stop)) {
                    if ($null -ne $item -and $item.PSObject.Properties['ExitCode']) { $code = [int]$item.ExitCode } else { Write-Host "[$($run.Version)] $item" }
                }
            } catch {
                Write-Host "[$($run.Version)] Could not collect fixture result: $_"
                $code = 1
            }
            if ($code -ne 0) { $failed += $run.Version }
        }
        if ($failed.Count -gt 0) {
            throw ("The bundle this push builds did not apply to TikTok $(Format-VersionList -Versions $failed). " +
                "Read the [verify] lines above; the results are in $work.")
        }
    } finally {
        # Receive-Job -AutoRemoveJob can remove a job twice when it completes during the initial
        # backlog drain. This invocation owns disposal, including partially started batches.
        $cleanupErrors = New-Object 'System.Collections.Generic.List[string]'
        foreach ($run in $runs) {
            try {
                if ($run.Job.State -notin @('Completed', 'Failed', 'Stopped')) {
                    Stop-Job -Job $run.Job -ErrorAction Stop
                }
                Remove-Job -Job $run.Job -ErrorAction Stop
            } catch {
                $cleanupErrors.Add("[$($run.Version)] $_")
            }
        }
        if ($cleanupErrors.Count -gt 0) { throw "Fixture-job cleanup failed: $($cleanupErrors -join '; ')" }
    }
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}

if ($env:HUSHFEED_SKIP_PRE_PUSH -eq '1') {
    Write-Step 'skipped by HUSHFEED_SKIP_PRE_PUSH'
    exit 0
}

Push-Location $Root
try {
    if ($PSBoundParameters.ContainsKey('ChangedPaths')) {
        $paths = New-Object System.Collections.Generic.HashSet[string]
        foreach ($name in @($ChangedPaths)) { [void]$paths.Add($name) }
        # Paths given by hand are a diff, so the index among them moved.
        $script:indexChanged = $paths.Contains('patches-bundle.json')
    } else {
        $refs = ''
        if ($PSBoundParameters.ContainsKey('PushedRefs')) {
            $refs = $PushedRefs
        } elseif (-not [Console]::IsInputRedirected) {
            throw 'No pushed refs on standard input. Run this from the pre-push hook, or pass -ChangedPaths.'
        } else {
            $refs = [Console]::In.ReadToEnd()
        }
        $paths = Get-PushedPaths -Text $refs
    }

    if ($paths.Count -eq 0) {
        Write-Step 'nothing to check'
        exit 0
    }

    $touchesCode = @($paths | Where-Object {
        $_ -like 'extensions/*' -or $_ -like 'patches/*' -or
        # The pins and the reviewed checksums. Two Gradle tasks hold the Bouncy Castle graphs to
        # the reviewed release, and they only run on the way to a test task; a push that moved
        # the pin alone ran the release facts check, which knows nothing about them.
        $_ -eq 'gradle/libs.versions.toml' -or $_ -eq 'gradle/verification-metadata.xml' -or
        $_ -eq 'settings.gradle.kts' -or $_ -eq 'build.gradle.kts' -or
        # What the tests read from outside the source folders: the README's patch table and
        # hero, the catalog, NOTICE and the artwork. A push that changed only one of them ran
        # the release facts check at most, and never the test that reads it.
        $_ -eq 'README.md' -or $_ -eq 'NOTICE' -or $_ -eq 'patches-list.json' -or $_ -eq 'patches-bundle.json' -or
        $_ -eq 'patches-bundle.png' -or $_ -like 'assets/readme-*' -or $_ -like 'concepts/marketing/*'
    }).Count -gt 0
    $touchesScripts = @($paths | Where-Object { $_ -like 'scripts/*' -or $_ -like 'audit/*.ps1' -or $_ -eq 'audit/catalog.json' }).Count -gt 0
    $touchesAttestation = @($paths | Where-Object {
        $_ -in @('scripts/release-attestation.ps1', 'scripts/test-release-attestation.ps1',
            'scripts/validate-release-facts.ps1')
    }).Count -gt 0
    $touchesBundleSignature = @($paths | Where-Object {
        $_ -in @('scripts/release-signature.ps1', 'scripts/test-release-signature.ps1',
            'scripts/validate-release-facts.ps1', 'cosign.pub')
    }).Count -gt 0
    $apkSigningPaths = @(
        'scripts/apk-signing.ps1', 'scripts/SigningCertificateCheck.java',
        'scripts/SigningKeyFixtures.java', 'scripts/test-apk-signing.ps1',
        'scripts/patch-for-device.ps1', 'scripts/device-install.ps1', 'scripts/device-lease.ps1',
        'scripts/common.ps1', 'scripts/Resolve-Java.ps1',
        'scripts/patch-target.ps1', 'scripts/patch-report.ps1',
        'tools/verification-probe/build.ps1', 'tools/verification-probe/AndroidManifest.xml',
        'gradle/libs.versions.toml', 'gradle/verification-metadata.xml'
    )
    $touchesApkSigning = @($paths | Where-Object {
        $_ -in $apkSigningPaths -or $_ -like 'tools/verification-probe/src/*'
    }).Count -gt 0
    # The probe's device scripts share a log-marker helper whose contracts live with the scripts'.
    $touchesProbeTests = @($paths | Where-Object {
        $_ -like 'tools/verification-probe/tests/*' -or $_ -like 'tools/verification-probe/*.ps1'
    }).Count -gt 0
    $touchesSearchLynxEvidence = @($paths | Where-Object {
        $_ -like 'tools/verification-probe/src/app/hushfeed/verification/SearchLynx*' -or
        $_ -like 'tools/verification-probe/tests/app/hushfeed/verification/SearchLynx*' -or
        $_ -eq 'tools/verification-probe/test-search-lynx.ps1'
    }).Count -gt 0
    # Declarations, catalog generation and its consumed build pins can change the unnamed
    # dependency closure without changing a script. Check that closure before starting a build.
    $touchesCatalog = @($paths | Where-Object {
        $_ -like 'patches/src/main/*' -or $_ -eq 'patches-list.json' -or
        $_ -eq 'patches/build.gradle.kts' -or $_ -eq 'gradle.properties' -or
        $_ -eq 'gradle/libs.versions.toml' -or $_ -eq 'gradle/verification-metadata.xml' -or
        $_ -eq 'settings.gradle.kts' -or $_ -eq 'build.gradle.kts' -or
        $_ -eq 'gradle/wrapper/gradle-wrapper.properties' -or
        $_ -eq 'gradle/wrapper/gradle-wrapper.jar' -or $_ -eq 'gradlew' -or $_ -eq 'gradlew.bat'
    }).Count -gt 0
    # The extension DEX payload and its build inputs reach TikTok through the same bundle as the
    # patch definitions. Tests alone don't prove that payload can be built or injected.
    $touchesBundle = $touchesCatalog -or @($paths | Where-Object {
        $_ -like 'extensions/*/src/main/*' -or $_ -like 'extensions/*/build.gradle.kts' -or
        $_ -eq 'extensions/proguard-rules.pro'
    }).Count -gt 0
    $touchesCode = $touchesCode -or $touchesBundle
    # An index push compares the existing release bundle byte for byte with the published one.
    # Refuse a mixed source/index push rather than skip source verification or restamp that asset.
    if ($touchesBundle -and $script:indexChanged) {
        throw 'Push bundle inputs before the published index. A mixed source/index push cannot rebuild and preserve the published artifact.'
    }
    $injectedRegisterVerifierPaths = @(
        'scripts/DexDiff.java',
        'scripts/injected-register-contracts.ps1',
        'scripts/injected-register-removal-allowlist.txt',
        'scripts/test-injected-registers.ps1',
        'scripts/verify-injected-registers.ps1'
    )
    $touchesInjectedRegisterVerifier = @($paths | Where-Object {
        $_ -in $injectedRegisterVerifierPaths
    }).Count -gt 0
    $resourceTableCheckPaths = @(
        'scripts/ResourceTableCheck.java',
        'scripts/test-resource-table-check.ps1',
        'scripts/verify-all-patches.ps1'
    )
    $touchesResourceTableCheck = @($paths | Where-Object {
        $_ -in $resourceTableCheckPaths
    }).Count -gt 0
    $injectedRegisterDevicePaths = @(
        'scripts/injected-register-device.ps1',
        'scripts/test-injected-register-device.ps1',
        'scripts/verify-injected-registers.ps1'
    )
    $touchesInjectedRegisterDevice = @($paths | Where-Object {
        $_ -in $injectedRegisterDevicePaths
    }).Count -gt 0
    $touchesRelease = @($paths | Where-Object {
        $_ -eq 'patches-bundle.json' -or $_ -eq 'patches-list.json' -or
        $_ -eq 'gradle.properties' -or $_ -eq 'README.md' -or
        # The pins the release check holds the bundle and the README to. A push that moved
        # only one of these ran no gate at all.
        $_ -eq 'gradle/libs.versions.toml' -or $_ -eq 'settings.gradle.kts' -or
        $_ -eq 'gradle/verification-metadata.xml' -or
        $_ -eq 'gradle/wrapper/gradle-wrapper.properties' -or
        # The receipt is the file the release check holds a release to, and the allowlist is
        # what decides which manifest changes it accepts. A push that moved only one of those
        # ran the script contract tests at most, and never the check that reads them.
        $_ -like 'release-receipt-*.json' -or
        $_ -eq 'scripts/manifest-delta-allowlist.txt' -or
        # A released version's heading is the only record a reader has that it shipped, and one
        # was renamed away by a post-release commit that no gate read.
        $_ -eq 'CHANGELOG.md' -or $_ -eq 'cosign.pub' -or
        # Its version placeholders are held to the target and the published index.
        $_ -eq '.github/ISSUE_TEMPLATE/bug_report.yml' -or
        # Its current version and patch counts are held to the catalog and the index.
        $_ -eq 'concepts/patch-development.md'
    }).Count -gt 0

    # Every gate below checks each pushed commit, and checks it in place only when it is HEAD and
    # nothing in the working tree differs from it. Everything else goes to a clean worktree of the
    # commit. Uncommitted work that isn't in the push can fail it (another agent's did, twice on
    # 2026-09-21) or pass it, and a push of anything but HEAD would otherwise have HEAD checked in
    # its place. The whole tree counts: the tests read README.md, patches-list.json, the artwork
    # and NOTICE as well as the sources, the release facts are those same files, and the script
    # contract tests copy them into their fixtures.
    if ($PSBoundParameters.ContainsKey('ChangedPaths')) {
        # A run by hand names its paths itself and checks this working tree as it stands.
        $head = $null
        $dirty = @()
        $gateCommits = @($null)
    } elseif ($touchesCode -or $touchesRelease -or $touchesScripts -or $touchesApkSigning) {
        $head = ([string](Invoke-HookGit @('-C', $Root, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
        $dirty = @(Invoke-HookGit @('-C', $Root, 'status', '--porcelain', '--untracked-files=all'))
        $gateCommits = @($script:pushedCommits)
        if ($gateCommits.Count -eq 0) { $gateCommits = @($head) }
        if ($dirty.Count -gt 0) {
            $shown = @($dirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
            Write-Step ("uncommitted changes in the working tree ($shown), so each pushed commit is " +
                'checked in a clean worktree instead of this working tree')
        }
    }
    # A commit is checked in place when it is HEAD of a clean tree, or when a run by hand names none.
    function Test-InPlace([string]$Commit) { return -not $Commit -or ($dirty.Count -eq 0 -and $Commit -eq $head) }
    # An in-place check reads this working tree, and another agent can change it during a build
    # that takes minutes. The result then covers the commit plus that edit, so the tree is read
    # again after each in-place step and the push stops if it moved. A run by hand checks the
    # tree as it stands and has nothing to compare.
    function Assert-TreeUnchanged([string]$Step) {
        if (-not $head) { return }
        $nowHead = ([string](Invoke-HookGit @('-C', $Root, 'rev-parse', 'HEAD') | Select-Object -Last 1)).Trim()
        $nowDirty = @(Invoke-HookGit @('-C', $Root, 'status', '--porcelain', '--untracked-files=all'))
        if ($nowHead -eq $head -and $nowDirty.Count -eq 0) { return }
        $moved = if ($nowHead -ne $head) { "HEAD moved from $head to $nowHead" } else {
            @($nowDirty | Select-Object -First 5 | ForEach-Object { $_.Trim() }) -join '; '
        }
        throw ("The working tree changed while $Step ran in place ($moved), so the result covers " +
            'more than the pushed commit. Push again: a tree with uncommitted changes is checked ' +
            'in a clean worktree of the commit instead.')
    }

    if ($touchesScripts -or $touchesCatalog -or $touchesApkSigning -or $touchesProbeTests -or $touchesBundleSignature) {
        # Script, notice, failure message. The two injected-register suites and the resource
        # table check's run only when their own files moved; each one is the pushed commit's
        # copy, run against that commit.
        $suites = @(, @('scripts/test-script-contracts.ps1', 'scripts or catalog inputs changed, running their contract tests',
            'The script contract tests did not pass.'))
        if ($touchesAttestation) {
            $suites += , @('scripts/test-release-attestation.ps1', 'release attestation inputs changed, running identity and asset contracts',
                'The release attestation contracts did not pass.')
        }
        if ($touchesBundleSignature) {
            $suites += , @('scripts/test-release-signature.ps1', 'bundle signature inputs changed, running pinned-key verification contracts',
                'The bundle signature contracts did not pass.')
        }
        if ($touchesApkSigning) {
            $suites += , @('scripts/test-apk-signing.ps1', 'device builder inputs changed, running SDK signing and cleanup fixtures',
                'The device builder signing and cleanup fixtures did not pass.')
        }
        if ($touchesSearchLynxEvidence) {
            $suites += , @('tools/verification-probe/test-search-lynx.ps1', 'probe Search Lynx evidence changed, running its snapshot and redaction contracts',
                'The probe Search Lynx contracts did not pass.')
        }
        if ($touchesInjectedRegisterVerifier) {
            $suites += , @('scripts/test-injected-registers.ps1', 'injected-register verifier changed, running its fixture tests',
                'The injected-register verifier fixture tests did not pass.')
        }
        if ($touchesResourceTableCheck) {
            $suites += , @('scripts/test-resource-table-check.ps1', 'resource table check changed, running its fixture tests',
                'The resource table check fixture tests did not pass.')
        }
        if ($touchesInjectedRegisterDevice) {
            $suites += , @('scripts/test-injected-register-device.ps1', 'injected-register device helper changed, running its cleanup fixtures',
                'The injected-register device cleanup fixtures did not pass.')
        }
        $scriptsLock = $null
        try {
            foreach ($scriptsCommit in $gateCommits) {
                if (Test-InPlace $scriptsCommit) {
                    $scriptsRoot = $Root
                } else {
                    if (-not $scriptsLock) { $scriptsLock = Enter-GateLock }
                    $scriptsRoot = Get-GateWorktree -Commit $scriptsCommit
                }
                try {
                foreach ($suite in $suites) {
                    $suiteScript = Join-Path $scriptsRoot $suite[0]
                    if (-not (Test-Path -LiteralPath $suiteScript -PathType Leaf)) {
                        if ($paths.Contains($suite[0])) {
                            throw "$($suite[0]) was deleted in this push. The gate scripts must not lose their tests."
                        }
                        Write-Step "$($suite[0]) is not in $scriptsCommit, so it has nothing to run there"
                        continue
                    }
                    $where = if ($scriptsRoot -eq $Root) { '' } else { " for $scriptsCommit in $scriptsRoot" }
                    Write-Step ($suite[1] + $where)
                    $global:LASTEXITCODE = 0
                    Invoke-WithoutGitEnvironment { & $suiteScript -Root $scriptsRoot }
                    if ($LASTEXITCODE -ne 0) { throw $suite[2] }
                }
                } finally {
                    if ($scriptsRoot -eq $Root) { Assert-TreeUnchanged 'the script tests' }
                }
            }
        } finally {
            if ($scriptsLock) {
                $scriptsLock.ReleaseMutex()
                $scriptsLock.Dispose()
            }
        }
    }

    if ($touchesCode) {
        Write-Step 'extension or patch sources changed, running the runtime tests and the API level check'

        # The Morphe settings plugin resolves from GitHub Packages, which needs a reader token.
        # A hook runs with git's environment, not the shell's, so these are usually absent and
        # the build fails while applying the plugin, long before a test runs.
        if (-not $env:GITHUB_ACTOR -or -not $env:GITHUB_TOKEN) {
            if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
                throw ('Set GITHUB_ACTOR and GITHUB_TOKEN, or install the gh CLI: the patches ' +
                    'plugin resolves from GitHub Packages and cannot be applied without them.')
            }
            $login = (& gh api user --jq .login 2>$null)
            $token = (& gh auth token 2>$null)
            if ([string]::IsNullOrWhiteSpace($login) -or [string]::IsNullOrWhiteSpace($token)) {
                throw 'gh is not signed in, so the patches plugin cannot be resolved. Run gh auth login.'
            }
            $env:GITHUB_ACTOR = $login
            $env:GITHUB_TOKEN = $token
        }

        # The lint runs alongside the tests because the tests cannot see this class of defect at
        # all: they run on a desktop JVM, where every java.util method exists whatever the
        # payload's floor says. Only the API level check reads minSdk, and it reads the SDK_INT
        # guards with it, so a call that is properly guarded stays quiet.
        # The patch module has tests of its own, on the register helpers and the anchors, and
        # nothing before a push ran them: they only ran on the way to generatePatchesList.
        $tasks = @(
            ':extensions:tiktok:test',
            ':patches:test',
            ':extensions:shared:library:lint',
            ':extensions:tiktok:lint'
        )
        # The bundle the fixture gate applies. Last, because :patches:test reruns :patches:jar, and
        # buildAndroid's verifyBundle also fails a catalog that no longer matches the patches. It
        # writes patches/build/release only, never the tracked patches-list.json.
        if ($touchesBundle) { $tasks += ':patches:buildAndroid' }
        # Everything but nativeTest's fixture scans runs first, so a slip in a two-minute test or a
        # lint stops the push in minutes: 0.69.0's second gate failed ObfuscatedIdentityTest after
        # 47 minutes, behind nativeTest. The full run after it finds those tasks up to date. The
        # finalizer waits for the full run, since it reads all three partitions' results and a
        # fresh gate worktree has no nativeTest results yet.
        $quickTasks = @($tasks | Where-Object { $_ -ne ':patches:buildAndroid' }) +
            @('-x', ':patches:nativeTest', '-x', ':patches:verifyPatchTestSelection')
        # HUSHFEED_BUILD_WRAPPER names a PowerShell script that runs Gradle on this machine,
        # called as <wrapper> -ProjectDir <repository> -Tasks <task>...: a machine that shares its
        # CPU and memory between several builds points it at a governor. Unset, the Gradle
        # wrapper in the repository runs the tasks directly.
        $wrapper = $env:HUSHFEED_BUILD_WRAPPER
        if ($wrapper -and -not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
            throw "HUSHFEED_BUILD_WRAPPER names $wrapper, which is not there."
        }

        $gateLock = $null
        try {
            foreach ($gateCommit in $gateCommits) {
                if (Test-InPlace $gateCommit) {
                    $gateRoot = $Root
                } else {
                    if (-not $gateLock) { $gateLock = Enter-GateLock }
                    $gateRoot = Get-GateWorktree -Commit $gateCommit
                    Write-Step "building $gateCommit in $gateRoot"
                }
                try {
                # Before the build, so a missing fixture stops the push in seconds, not after it.
                $fixtures = @()
                if ($touchesBundle) { $fixtures = @(Get-DeclaredFixtures -GateRoot $gateRoot) }
                foreach ($passTasks in @(, $quickTasks) + @(, $tasks)) {
                    $global:LASTEXITCODE = 0
                    Invoke-WithoutGitEnvironment {
                        if ($wrapper) {
                            & $wrapper -ProjectDir $gateRoot -Tasks $passTasks
                        } else {
                            & (Join-Path $gateRoot 'gradlew.bat') -p $gateRoot @passTasks
                        }
                    }
                    if ($LASTEXITCODE -ne 0) {
                        throw ('The runtime test build did not pass. Read the output above: it says whether a ' +
                            'test failed, an API level above the payload floor was reached, or the build could ' +
                            'not start. Push anyway with HUSHFEED_SKIP_PRE_PUSH=1.')
                    }
                }
                if ($fixtures.Count -gt 0) { Invoke-FixturePatching -GateRoot $gateRoot -Fixtures $fixtures }
                } finally {
                    if ($gateRoot -eq $Root) { Assert-TreeUnchanged 'the runtime test build' }
                }
            }
        } finally {
            if ($gateLock) {
                $gateLock.ReleaseMutex()
                $gateLock.Dispose()
            }
        }
    }

    if ($touchesRelease) {
        Write-Step 'a published file changed, checking the release facts'
        # The description's test count belongs to the release it describes. Holding this tree to
        # it only means something while the description is being rewritten, which is when
        # patches-bundle.json is one of the files that moved.
        $describesThisTree = $script:indexChanged
        $factsFailed = 'The release facts do not agree. Fix them or push with HUSHFEED_SKIP_PRE_PUSH=1.'
        if ($describesThisTree) {
            # The index push holds the bundle and the test results this checkout built to the new
            # description, and those exist only here, so it has to be a clean checkout of the
            # commit it pushes: anything else would check other files than the ones going out.
            $elsewhere = @($gateCommits | Where-Object { -not (Test-InPlace $_) })
            if ($elsewhere.Count -gt 0) {
                throw ('An index push checks the bundle and test results this checkout built, so it ' +
                    'has to come from a clean checkout of the commit it pushes. Commit or stash the ' +
                    'rest, check out ' + ($elsewhere -join ', ') + ' and push again.')
            }
            $validate = Join-Path $Root 'scripts/validate-release-facts.ps1'
            try {
            $global:LASTEXITCODE = 0
            # The release copy buildAndroid leaves in patches/build/release, which no other task
            # writes. patches/build/libs was read here until 2026-09-21: the patch tests this hook
            # runs rerun :patches:jar, which put the plain jar back over the bundle under the same
            # name, and the sources and javadoc jars share the .mpp extension there as well.
            $artifacts = @(Get-ChildItem -LiteralPath (Join-Path $Root 'patches/build/release') `
                -Filter '*.mpp' -File -ErrorAction SilentlyContinue)
            if ($artifacts.Count -eq 1) {
                # The bundle this checkout built, so the indexed URL, its hash and the hosted
                # checksum entry can all be compared against something real.
                & $validate -Root $Root -VerifyPublishedAsset -ArtifactPath $artifacts[0].FullName
            } else {
                if ($artifacts.Count -gt 1) {
                    Write-Step "found $($artifacts.Count) bundles, so the hosted artifact is not compared"
                } else {
                    Write-Step 'no local bundle here, so the hosted artifact is not compared'
                }
                & $validate -Root $Root
            }
            if ($LASTEXITCODE -ne 0) { throw $factsFailed }
            } finally {
                Assert-TreeUnchanged 'the release facts check'
            }
        } else {
            # Every other push checks the files it carries, against the published index it leaves
            # alone. The indexed URL is still fetched; only the byte-for-byte hash comparison needs
            # a local bundle, and at any time but an index push build/release holds a bundle built
            # from whatever the tree was then. A release source commit reaches GitHub before its tag
            # and bundle exist, so the unchanged index may keep naming the previous release.
            $factsLock = $null
            try {
                foreach ($factsCommit in $gateCommits) {
                    $arguments = @{ SkipDescriptionTestCount = $true; AllowPublishedIndexLag = $true }
                    if (Test-InPlace $factsCommit) {
                        $factsRoot = $Root
                    } else {
                        if (-not $factsLock) { $factsLock = Enter-GateLock }
                        $factsRoot = Get-GateWorktree -Commit $factsCommit
                        Write-Step "checking the release facts of $factsCommit in $factsRoot"
                    }
                    # The pushed commit's own check, which reads its own helpers. In the worktree its
                    # build folders can hold another commit's test results, so they are left unread
                    # there, by any check that knows how.
                    $validate = Join-Path $factsRoot 'scripts/validate-release-facts.ps1'
                    if ($factsRoot -ne $Root -and (Get-Command $validate).Parameters.ContainsKey('SkipTestResults')) {
                        $arguments['SkipTestResults'] = $true
                    }
                    try {
                    $global:LASTEXITCODE = 0
                    & $validate -Root $factsRoot @arguments
                    if ($LASTEXITCODE -ne 0) { throw $factsFailed }
                    } finally {
                        if ($factsRoot -eq $Root) { Assert-TreeUnchanged 'the release facts check' }
                    }
                }
            } finally {
                if ($factsLock) {
                    $factsLock.ReleaseMutex()
                    $factsLock.Dispose()
                }
            }
        }
    }

    if (-not $touchesScripts -and -not $touchesCode -and -not $touchesRelease) {
        Write-Step 'no code or published file changed'
    }
    # Over HTTPS git starts send-pack only after this hook returns, and send-pack reads a named
    # branch again then. Commits made on it while the gates ran went out unchecked on 2026-09-27
    # (55556b10 to 64d2844e, behind a gate that had checked abda9b30), with the tracking ref
    # still saying abda9b30. So every pushed branch still has to name what was checked.
    foreach ($pushed in $script:checkedRefs) {
        if ($pushed.LocalRef -notlike 'refs/*') { continue }
        $now = ([string](Invoke-GitQuietly @('rev-parse', '--verify', '--quiet', $pushed.LocalRef))).Trim()
        if ($now -ne $pushed.LocalSha) {
            throw ("$($pushed.LocalRef) moved from $($pushed.LocalSha) to $now while the checks ran, and git " +
                'would push the new commits unchecked. Push again, and leave the branch alone until the push ends.')
        }
    }
    Write-Step 'ok'
    exit 0
} finally {
    Pop-Location
}
