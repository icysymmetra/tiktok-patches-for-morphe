<# Build a small self-targeted APK around an explicit, byte-identical production payload. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Payload,
    [Parameter(Mandatory = $true)][string]$SharedPayload,
    [Parameter(Mandatory = $true)][string]$VendorApk,
    [Parameter(Mandatory = $true)][string]$OutDir,
    [Parameter(Mandatory = $true)][ValidateLength(10, 400)][string]$SourceQualification,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildTools = '36.1.0',
    [string]$Java,
    [string]$Dexlib,
    [string]$Guava,
    [switch]$Unsigned,
    [string]$Keystore = "$HOME\.android\sideload-release.jks",
    [string]$KeyAlias = 'sideload',
    [ValidateSet('BKS', 'JKS', 'PKCS12')][string]$KeystoreType,
    [AllowEmptyString()][string]$KeystorePassword,
    [AllowEmptyString()][string]$KeyPassword
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))
. (Join-Path $root 'scripts/common.ps1')
. (Join-Path $root 'scripts/Resolve-Java.ps1')
. (Join-Path $root 'scripts/apk-signing.ps1')

function Invoke-ArtTool([string]$File, [string[]]$Arguments) {
    $command = Get-Command $File -CommandType Application -ErrorAction Stop
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        & $command.Source @Arguments
        $status = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($status -ne 0) { throw "ART helper tool failed (exit $status): $File" }
}
function Resolve-ArtJar([string]$Explicit, [string]$Relative, [string]$Name) {
    if ($Explicit) { return (Resolve-Path -LiteralPath $Explicit -ErrorAction Stop).Path }
    $cacheRoot = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $HOME '.gradle' }
    $candidates = @(Get-ChildItem -LiteralPath (Join-Path $cacheRoot "caches/modules-2/files-2.1/$Relative") -Recurse -File -Filter $Name)
    if ($candidates.Count -ne 1) { throw "Pass an explicit cached $Name path." }
    return $candidates[0].FullName
}
function Get-ArtHash([string]$Path) { return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }

$Payload = (Resolve-Path -LiteralPath $Payload -ErrorAction Stop).Path
$SharedPayload = (Resolve-Path -LiteralPath $SharedPayload -ErrorAction Stop).Path
$VendorApk = (Resolve-Path -LiteralPath $VendorApk -ErrorAction Stop).Path
$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw 'Use a fresh ART helper output directory. Existing files are preserved.' }
$Java = Resolve-Java -Explicit $Java
$javac = Join-Path (Split-Path -Parent (Get-Command $Java -ErrorAction Stop).Source) 'javac.exe'
$android = Join-Path $Sdk 'platforms/android-23/android.jar'
$tools = Join-Path $Sdk "build-tools/$BuildTools"
$pin = (Get-Content -LiteralPath (Join-Path $root 'gradle/libs.versions.toml') | Where-Object { $_ -match '^smali\s*=\s*"([^"]+)"' } | Select-Object -First 1)
if (-not $pin -or $pin -notmatch '^smali\s*=\s*"([^"]+)"') { throw 'No local smali pin.' }
$smaliVersion = $Matches[1]
$Dexlib = Resolve-ArtJar $Dexlib "com.github.MorpheApp.smali/smali-dexlib2/$smaliVersion" "smali-dexlib2-$smaliVersion.jar"
$Guava = Resolve-ArtJar $Guava 'com.google.guava/guava/32.1.3-android' 'guava-32.1.3-android.jar'
$productionCompile = @(
    (Join-Path $root 'extensions/tiktok/build/intermediates/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar'),
    (Join-Path $root 'extensions/shared/library/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar'),
    (Join-Path $root 'extensions/tiktok/stub/build/intermediates/runtime_library_classes_jar/debug/bundleLibRuntimeToJarDebug/classes.jar')
)
foreach ($inputPath in @($android, $javac, $Dexlib, $Guava) + $productionCompile) {
    if (-not (Test-Path -LiteralPath $inputPath -PathType Leaf)) { throw "Missing local compiler input: $inputPath" }
}
$null = New-Item -ItemType Directory -Path $OutDir
foreach ($child in @('host', 'classes', 'dex', 'assets', 'compiled-res')) { $null = New-Item -ItemType Directory -Path (Join-Path $OutDir $child) }
$hostClassPath = (Join-Path $OutDir 'host') + [IO.Path]::PathSeparator + $Dexlib + [IO.Path]::PathSeparator + $Guava
Invoke-ArtTool $javac @('-J-Xmx128m', '--release', '11', '-encoding', 'UTF-8', '-classpath', "$Dexlib;$Guava",
    '-d', (Join-Path $OutDir 'host'), (Join-Path $PSScriptRoot 'VendorSlice.java'), (Join-Path $PSScriptRoot 'ArtifactContracts.java'))
Invoke-ArtTool $Java @('-Xmx256m', '-cp', $hostClassPath, 'VendorSlice', 'payload', $Payload, $SharedPayload, (Join-Path $OutDir 'payload.json'))
Invoke-ArtTool $Java @('-Xmx256m', '-cp', $hostClassPath, 'VendorSlice', 'slice', $VendorApk,
    (Join-Path $OutDir 'vendor-slice.dex'), (Join-Path $OutDir 'vendor-slice.json'))
$payloadReceipt = Get-Content -LiteralPath (Join-Path $OutDir 'payload.json') -Raw | ConvertFrom-Json
$sharedSlice = Join-Path $OutDir 'payload.json.shared.dex'
$vendorReceipt = Get-Content -LiteralPath (Join-Path $OutDir 'vendor-slice.json') -Raw | ConvertFrom-Json
$sourceHashes = [ordered]@{}
foreach ($source in @('captions/CaptionStyle.java', 'feedfilter/FeedItemsFilter.java')) {
    $sourceHashes[$source] = Get-ArtHash (Join-Path $root "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/$source")
}
$qualification = [ordered]@{
    schema = 1; helperVersion = '0.1.0'; payloadSha256 = $payloadReceipt.payloadSha256
    sharedPayloadSha256 = $payloadReceipt.sharedPayloadSha256
    sharedSliceSha256 = $payloadReceipt.sharedSliceSha256; sharedSliceClasses = $payloadReceipt.sharedSliceClasses
    identicalSharedDuplicatesOmitted = $payloadReceipt.identicalSharedDuplicatesOmitted
    payloadClasses = $payloadReceipt.classCount; vendorSliceSha256 = $vendorReceipt.sliceSha256
    vendorApkSha256 = $vendorReceipt.vendorApkSha256; vendorClasses = $vendorReceipt.classes
    recordedProductionSources = $sourceHashes; suppliedPayloadQualification = $SourceQualification
    payloadBuildAttestation = $false
    qualificationLimit = 'Source hashes identify the current target files. They do not prove which source compiled the supplied payload.'
    dexlibSha256 = (Get-ArtHash $Dexlib); guavaSha256 = (Get-ArtHash $Guava)
    fullTikTokAcceptance = $false
}
$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $OutDir 'assets/qualification.json'), ($qualification | ConvertTo-Json -Depth 8), $utf8)
$sources = @((Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' -File).FullName)
Invoke-ArtTool $javac (@('-J-Xmx128m', '--release', '8', '-encoding', 'UTF-8', '-classpath',
    (($productionCompile + @($android)) -join [IO.Path]::PathSeparator), '-d', (Join-Path $OutDir 'classes')) + $sources)
Invoke-ArtTool $javac @('-J-Xmx128m', '--release', '8', '-classpath', ((@((Join-Path $OutDir 'classes')) + $productionCompile) -join [IO.Path]::PathSeparator), '-d',
    (Join-Path $OutDir 'host'), (Join-Path $PSScriptRoot 'ContractChecksTest.java'), (Join-Path $PSScriptRoot 'HookBindingsTest.java'))
Invoke-ArtTool $Java @('-Xmx64m', '-cp', ((Join-Path $OutDir 'host') + [IO.Path]::PathSeparator + (Join-Path $OutDir 'classes')),
    'app.hushfeed.verification.api23.ContractChecksTest')
Invoke-ArtTool $Java @('-Xmx64m', '-cp', ((@((Join-Path $OutDir 'host'), (Join-Path $OutDir 'classes')) + $productionCompile) -join [IO.Path]::PathSeparator),
    'app.hushfeed.verification.api23.HookBindingsTest')
$classes = @((Get-ChildItem -LiteralPath (Join-Path $OutDir 'classes') -Recurse -Filter '*.class' -File).FullName)
Invoke-ArtTool (Join-Path $tools 'd8.bat') (@('--thread-count', '2', '--lib', $android, '--min-api', '23',
    '--classpath', $productionCompile[0], '--classpath', $productionCompile[1], '--classpath', $productionCompile[2],
    '--output', (Join-Path $OutDir 'dex')) + $classes)
Invoke-ArtTool (Join-Path $tools 'aapt2.exe') @('compile', '--dir', (Join-Path $PSScriptRoot 'res'), '-o', (Join-Path $OutDir 'compiled-res'))
$flat = @((Get-ChildItem -LiteralPath (Join-Path $OutDir 'compiled-res') -Filter '*.flat' -File).FullName)
$unsignedApk = Join-Path $OutDir 'api23-art-unsigned.apk'
Invoke-ArtTool (Join-Path $tools 'aapt2.exe') (@('link', '-I', $android, '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'),
    '-A', (Join-Path $OutDir 'assets'), '-o', $unsignedApk) + $flat)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsignedApk, 'Update')
try {
    foreach ($entry in @(@((Join-Path $OutDir 'dex/classes.dex'), 'classes.dex'), @($Payload, 'classes2.dex'),
            @($sharedSlice, 'classes3.dex'), @((Join-Path $OutDir 'vendor-slice.dex'), 'classes4.dex'))) {
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $entry[0], $entry[1])
    }
} finally { $archive.Dispose() }
$aligned = Join-Path $OutDir 'api23-art-aligned.apk'
Invoke-ArtTool (Join-Path $tools 'zipalign.exe') @('-f', '4', $unsignedApk, $aligned)
$apk = $aligned
if (-not $Unsigned) {
    $session = New-ApkSigningSession -BoundParameters $PSBoundParameters -Root $root -Sdk $Sdk -Java $Java `
        -Keystore $Keystore -KeyAlias $KeyAlias -KeystoreType $KeystoreType -OutputDirectory $OutDir
    try {
        $apk = Join-Path $OutDir 'hushfeed-api23-art-probe.apk'
        Invoke-ApkSigning -Session $session -InputApk $aligned -OutputApk $apk
    } finally { Close-ApkSigningSession -Session $session }
}
Invoke-ArtTool $Java @('-Xmx128m', '-cp', $hostClassPath, 'VendorSlice', 'verify-apk', $apk, $Payload, $sharedSlice, (Join-Path $OutDir 'vendor-slice.dex'))
Invoke-ArtTool $Java @('-Xmx128m', '-cp', $hostClassPath, 'ArtifactContracts', $apk, $Payload, $sharedSlice,
    (Join-Path $OutDir 'vendor-slice.dex'), (Join-Path $OutDir 'artifact-contracts'))
$result = [ordered]@{ schema = 1; apk = $apk; apkSha256 = (Get-ArtHash $apk); signed = (-not $Unsigned)
    payloadSha256 = $payloadReceipt.payloadSha256; vendorSliceSha256 = $vendorReceipt.sliceSha256
    sharedPayloadSha256 = $payloadReceipt.sharedPayloadSha256
    sharedSliceSha256 = $payloadReceipt.sharedSliceSha256
    nativeExecuted = $false; fullTikTokAcceptance = $false }
[IO.File]::WriteAllText((Join-Path $OutDir 'build-receipt.json'), ($result | ConvertTo-Json), $utf8)
Write-Output "[api23-art] $apk"
