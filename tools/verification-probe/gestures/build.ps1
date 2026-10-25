<# Build the separate gesture APK with SDK tools and the existing local test key. #>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutDir,
    [string]$Sdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$BuildTools = '36.1.0',
    [string]$Java,
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

function Invoke-GestureTool([string]$File, [string[]]$Arguments) {
    $command = Get-Command $File -CommandType Application -ErrorAction Stop
    $preference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $global:LASTEXITCODE = -1
        & $command.Source @Arguments
        $status = $LASTEXITCODE
    } finally { $ErrorActionPreference = $preference }
    if ($status -ne 0) { throw "Gesture build tool failed (exit $status): $File" }
}

$OutDir = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutDir)
if (Test-Path -LiteralPath $OutDir) { throw 'Use a fresh gesture output directory.' }
$Java = Resolve-Java -Explicit $Java
$javac = Join-Path (Split-Path -Parent (Get-Command $Java -ErrorAction Stop).Source) 'javac.exe'
$androidJar = Join-Path $Sdk 'platforms/android-36/android.jar'
$tools = Join-Path $Sdk "build-tools/$BuildTools"
$session = New-ApkSigningSession -BoundParameters $PSBoundParameters -Root $root -Sdk $Sdk -Java $Java `
    -Keystore $Keystore -KeyAlias $KeyAlias -KeystoreType $KeystoreType -OutputDirectory $OutDir
try {
    $null = New-Item -ItemType Directory -Path $OutDir
    $null = New-Item -ItemType Directory -Path (Join-Path $OutDir 'classes'), (Join-Path $OutDir 'dex')
    $sources = @('GestureSequence.java', 'GestureSequenceContract.java', 'GestureAudio.java',
        'GestureAudioContract.java', 'GestureDriver.java', 'GestureDriverContract.java', 'GestureProbe.java') |
        ForEach-Object { Join-Path $PSScriptRoot $_ }
    Invoke-GestureTool $javac (@('--release', '11', '-encoding', 'UTF-8', '-classpath', $androidJar,
        '-d', (Join-Path $OutDir 'classes')) + $sources)
    Invoke-GestureTool $Java @('-cp', (Join-Path $OutDir 'classes'),
        'app.hushfeed.verification.gestures.GestureSequenceContract')
    Invoke-GestureTool $Java @('-cp', (Join-Path $OutDir 'classes'),
        'app.hushfeed.verification.gestures.GestureAudioContract')
    Invoke-GestureTool $Java @('-cp', (Join-Path $OutDir 'classes'),
        'app.hushfeed.verification.gestures.GestureDriverContract')
    $classes = @(Get-ChildItem (Join-Path $OutDir 'classes') -Recurse -Filter '*.class' | ForEach-Object FullName)
    Invoke-GestureTool (Join-Path $tools 'd8.bat') (@('--lib', $androidJar, '--min-api', '34',
        '--output', (Join-Path $OutDir 'dex')) + $classes)
    $unsigned = Join-Path $OutDir 'gesture-unsigned.apk'
    Invoke-GestureTool (Join-Path $tools 'aapt2.exe') @('link', '-I', $androidJar,
        '--manifest', (Join-Path $PSScriptRoot 'AndroidManifest.xml'), '-o', $unsigned)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::Open($unsigned, 'Update')
    try {
        $null = [IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $archive, (Join-Path $OutDir 'dex/classes.dex'), 'classes.dex')
    } finally { $archive.Dispose() }
    $aligned = Join-Path $OutDir 'gesture-aligned.apk'
    Invoke-GestureTool (Join-Path $tools 'zipalign.exe') @('-f', '-p', '4', $unsigned, $aligned)
    $apk = Join-Path $OutDir 'hushfeed-gesture-probe.apk'
    Invoke-ApkSigning -Session $session -InputApk $aligned -OutputApk $apk
    Write-Output "[gesture-probe] $apk"
} finally { Close-ApkSigningSession -Session $session }
