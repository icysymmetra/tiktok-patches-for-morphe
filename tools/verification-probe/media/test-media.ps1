[CmdletBinding()]
param([string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { $JavaHome = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$platform = Join-Path $Sdk 'platforms/android-36/android.jar'
foreach ($file in @($javac, $java, $platform)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required compile input missing: $file" }
}
$probe = Split-Path -Parent $PSScriptRoot
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$output = Join-Path $tempRoot ('hushfeed-native-media-contracts-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
try {
    $inputs = @(Get-ChildItem -LiteralPath (Join-Path $probe 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
    $inputs += Join-Path $probe '../../extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxRenderedModels.java'
    $inputs += Join-Path $probe 'tests/app/hushfeed/verification/NativeMediaProbeContract.java'
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $javac -J-Xmx192m -J-XX:ActiveProcessorCount=2 --release 11 -encoding UTF-8 -cp $platform -d $output @inputs
        $compileStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($compileStatus) { throw 'Native media probe compilation failed.' }
    & $java -Xmx128m -XX:ActiveProcessorCount=2 -cp "$output;$platform" app.hushfeed.verification.NativeMediaProbeContract
    if ($LASTEXITCODE) { throw 'Native media host contracts failed.' }
    Write-Output 'Probe Java compilation and native-media host contracts passed. No ART or device actions.'
} finally {
    $absolute = [IO.Path]::GetFullPath($output)
    if (!$absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) `
            -or [IO.Path]::GetFileName($absolute) -notmatch '^hushfeed-native-media-contracts-[a-f0-9]{32}$') {
        throw 'Owned temporary cleanup guard failed.'
    }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
