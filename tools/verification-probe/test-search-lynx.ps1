[CmdletBinding()]
param([string]$Root, [string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME, [string]$JsonJar)
# Runs the probe's Search Lynx snapshot and redaction contracts on the desktop JVM. The probe
# compiles against android.jar, whose org.json is a stub, so the contracts run on the real
# org.json from the Gradle cache. With the extension compiled (any Gradle build), the template
# redaction is also checked against the extension's own SearchLynxCards.lynxTemplate.
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { $JavaHome = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if (!$JsonJar) {
    $JsonJar = Get-ChildItem (Join-Path $HOME '.gradle/caches/modules-2/files-2.1/org.json/json/*/*/json-*.jar') -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (!$JsonJar) { throw 'No org.json jar in the Gradle cache. Run any Gradle build once, or pass -JsonJar.' }
}
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$platform = Join-Path $Sdk 'platforms/android-36/android.jar'
foreach ($file in @($javac, $java, $platform, $JsonJar)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required contract input missing: $file" }
}
# -Root is the checkout the pre-push gate passes every suite; the sources come from beside this file.
$repository = if ($Root) { [IO.Path]::GetFullPath($Root) } else { [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..')) }
$extensionClasses = Join-Path $repository 'extensions/tiktok/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$sharedClasses = Join-Path $repository 'extensions/shared/library/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$output = Join-Path $tempRoot ('hushfeed-search-lynx-contracts-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
try {
    $inputs = @(
        (Join-Path $PSScriptRoot 'src/app/hushfeed/verification/SearchLynxEvidence.java'),
        (Join-Path $PSScriptRoot 'src/app/hushfeed/verification/SearchLynxSnapshot.java'),
        (Join-Path $PSScriptRoot 'tests/app/hushfeed/verification/SearchLynxEvidenceContract.java')
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $javac -J-Xmx192m -J-XX:ActiveProcessorCount=2 --release 11 -encoding UTF-8 -cp $platform -d $output @inputs
        $compileStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($compileStatus) { throw 'Search Lynx contract compilation failed.' }
    $type = 'app.hushfeed.verification.SearchLynxEvidenceContract'
    # The real org.json comes before android.jar, whose org.json classes only throw "Stub!".
    & $java -Xmx128m -XX:ActiveProcessorCount=2 -cp "$output;$JsonJar" $type
    if ($LASTEXITCODE) { throw 'Search Lynx contract failed.' }
    if ((Test-Path -LiteralPath $extensionClasses) -and (Test-Path -LiteralPath $sharedClasses)) {
        & $java -Xmx128m -XX:ActiveProcessorCount=2 -cp "$output;$JsonJar;$extensionClasses;$sharedClasses;$platform" $type --native-template
        if ($LASTEXITCODE) { throw 'Search Lynx contract failed against the extension template.' }
    } else {
        Write-Warning 'Extension classes not built, so the extension template comparison was skipped. Any Gradle build of the extension enables it.'
    }
    Write-Output 'Search Lynx snapshot and redaction contracts passed. No device actions.'
} finally {
    $absolute = [IO.Path]::GetFullPath($output)
    if (!$absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) `
            -or [IO.Path]::GetFileName($absolute) -notmatch '^hushfeed-search-lynx-contracts-[a-f0-9]{32}$') {
        throw 'Owned temporary cleanup guard failed.'
    }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
