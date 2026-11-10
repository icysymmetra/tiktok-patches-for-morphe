[CmdletBinding()]
param([string]$JavaHome = $env:JAVA_HOME, [string]$Sdk = $env:ANDROID_HOME)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { $JavaHome = 'C:/Program Files/Android/Android Studio/jbr' }
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$javac = Join-Path $JavaHome 'bin/javac.exe'
$java = Join-Path $JavaHome 'bin/java.exe'
$platform = Join-Path $Sdk 'platforms/android-36/android.jar'
foreach ($file in @($javac, $java, $platform)) {
    if (!(Test-Path -LiteralPath $file -PathType Leaf)) { throw "Required contract input missing: $file" }
}
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
$output = Join-Path $tempRoot ('hushfeed-inbox-query-contracts-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
try {
    $inputs = @(
        (Join-Path $PSScriptRoot '../../extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxRenderedModels.java'),
        (Join-Path $PSScriptRoot 'src/app/hushfeed/verification/InboxQuerySnapshot.java'),
        (Join-Path $PSScriptRoot 'tests/app/morphe/extension/tiktok/inbox/InboxRenderedModelsContract.java'),
        (Join-Path $PSScriptRoot 'tests/app/hushfeed/verification/InboxQuerySnapshotContract.java')
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & $javac -J-Xmx192m -J-XX:ActiveProcessorCount=2 --release 11 -encoding UTF-8 -cp $platform -d $output @inputs
        $compileStatus = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previous }
    if ($compileStatus) { throw 'Inbox query contract compilation failed.' }
    foreach ($type in @('app.morphe.extension.tiktok.inbox.InboxRenderedModelsContract', 'app.hushfeed.verification.InboxQuerySnapshotContract')) {
        & $java -Xmx128m -XX:ActiveProcessorCount=2 -cp "$output;$platform" $type
        if ($LASTEXITCODE) { throw "Inbox query contract failed: $type" }
    }
    Write-Output 'Shared Inbox list-reader and snapshot contracts passed. No device actions.'
} finally {
    $absolute = [IO.Path]::GetFullPath($output)
    if (!$absolute.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) `
            -or [IO.Path]::GetFileName($absolute) -notmatch '^hushfeed-inbox-query-contracts-[a-f0-9]{32}$') {
        throw 'Owned temporary cleanup guard failed.'
    }
    Remove-Item -LiteralPath $absolute -Recurse -Force
}
