[CmdletBinding()]
param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
if (!$Sdk) { $Sdk = Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$javac = if ($JavaHome) { Join-Path $JavaHome 'bin/javac.exe' } else { (Get-Command javac -ErrorAction Stop).Source }
$java = if ($JavaHome) { Join-Path $JavaHome 'bin/java.exe' } else { (Get-Command java -ErrorAction Stop).Source }
$platform = Join-Path $Sdk 'platforms/android-36/android.jar'
foreach ($path in @($javac, $java, $platform)) {
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Required compile input is missing: $path" }
}
$output = Join-Path ([IO.Path]::GetTempPath()) ('hushfeed-focus-contracts-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $output -ErrorAction Stop | Out-Null
$source = Join-Path $PSScriptRoot 'src/app/hushfeed/verification'
$tests = Join-Path $PSScriptRoot 'tests/app/hushfeed/verification'
$names = @('BudgetStateSnapshot', 'PcmFocusFixture', 'RestorationRetry', 'VerifiedJournal',
    'EdgeDragPlan', 'EdgeDragRestoration')
$inputs = @($names | ForEach-Object { Join-Path $source ($_ + '.java') })
$inputs += @($names | ForEach-Object { Join-Path $tests ($_ + 'Contract.java') })
& $javac --release 11 -d $output @inputs
if ($LASTEXITCODE) { throw 'Focus contract compilation failed.' }
foreach ($name in $names) {
    & $java -cp $output ('app.hushfeed.verification.' + $name + 'Contract')
    if ($LASTEXITCODE) { throw "$name contract failed." }
}
$all = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'src') -Recurse -Filter '*.java' | ForEach-Object FullName)
$all += Join-Path $PSScriptRoot '../../extensions/tiktok/src/main/java/app/morphe/extension/tiktok/inbox/InboxRenderedModels.java'
& $javac --release 11 -cp $platform -d $output @all
if ($LASTEXITCODE) { throw 'API 36 probe compilation failed.' }
Write-Output "Six probe contract suites and API 36 compilation passed. No device actions. Output: $output"
