param(
    [string]$ProbeLogRoot = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
if ([string]::IsNullOrWhiteSpace($ProbeLogRoot)) {
    $ProbeLogRoot = (Resolve-Path (Join-Path $repoRoot "..\..\artifacts\logs")).Path
}
$outputRoot = Join-Path $repoRoot "build\hide-ai-harness"
New-Item -ItemType Directory -Force -Path $outputRoot | Out-Null

$sourceRoot = Join-Path $repoRoot "extensions\tiktok\src\main\java\app\morphe\extension\tiktok\feedfilter"
$stubRoot = Join-Path $PSScriptRoot "stubs"
$javaSources = @(
    (Join-Path $sourceRoot "AiContentClassifier.java"),
    (Join-Path $sourceRoot "AiObservation.java"),
    (Join-Path $sourceRoot "AiContentFilter.java"),
    (Join-Path $sourceRoot "ContentListFilter.java"),
    (Join-Path $stubRoot "com\ss\android\ugc\aweme\feed\AIGCInfo.java"),
    (Join-Path $stubRoot "com\ss\android\ugc\aweme\feed\model\ModerationAigcInfo.java"),
    (Join-Path $stubRoot "com\ss\android\ugc\aweme\feed\model\Aweme.java"),
    (Join-Path $PSScriptRoot "AiContentClassifierHarness.java"),
    (Join-Path $PSScriptRoot "AiContentFilterHarness.java"),
    (Join-Path $PSScriptRoot "FeedListRoutingHarness.java"),
    (Join-Path $PSScriptRoot "ProbeReplayHarness.java")
)

& javac -encoding UTF-8 -d $outputRoot $javaSources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

foreach ($harness in @(
    "AiContentClassifierHarness",
    "AiContentFilterHarness",
    "FeedListRoutingHarness"
)) {
    & java -cp $outputRoot "app.morphe.extension.tiktok.feedfilter.$harness"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$firstTsv = Join-Path $outputRoot "e8479b45.tsv"
$secondTsv = Join-Path $outputRoot "ce2498b0.tsv"
& python (Join-Path $PSScriptRoot "replay_probe_ai.py") `
    --input (Join-Path $ProbeLogRoot "feed-probe-e8479b45-decoded.json") `
    --output $firstTsv
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& python (Join-Path $PSScriptRoot "replay_probe_ai.py") `
    --input (Join-Path $ProbeLogRoot "feed-probe-ce2498b0-decoded.json") `
    --output $secondTsv
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp $outputRoot app.morphe.extension.tiktok.feedfilter.ProbeReplayHarness `
    $firstTsv 2 $secondTsv 0
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$settings = Get-Content (Join-Path $repoRoot "extensions\tiktok\src\main\java\app\morphe\extension\tiktok\settings\Settings.java") -Raw
$fragment = Get-Content (Join-Path $repoRoot "extensions\tiktok\src\main\java\app\morphe\extension\tiktok\settings\preference\TikTokPreferenceFragment.java") -Raw
$category = Get-Content (Join-Path $repoRoot "extensions\tiktok\src\main\java\app\morphe\extension\tiktok\settings\preference\categories\FeedFilterPreferenceCategory.java") -Raw
$booleanSetting = Get-Content (Join-Path $repoRoot "extensions\shared\library\src\main\java\app\morphe\extension\shared\settings\BooleanSetting.java") -Raw
$setting = Get-Content (Join-Path $repoRoot "extensions\shared\library\src\main\java\app\morphe\extension\shared\settings\Setting.java") -Raw
if ($settings -notmatch 'HIDE_AI_CONTENT\s*=\s*new BooleanSetting\("hide_ai_content", FALSE\)') {
    throw "Hide AI setting is not using the live, importable two-argument constructor"
}
if ($booleanSetting -notmatch 'BooleanSetting\(String key, Boolean defaultValue\)\s*\{\s*super\(key, defaultValue\);') {
    throw "BooleanSetting two-argument constructor no longer delegates to the Setting default contract"
}
if ($setting -notmatch 'Setting\(String key, T defaultValue\)\s*\{\s*this\(key, defaultValue, false, true, null, null\);') {
    throw "Setting default contract no longer means rebootApp=false and includeWithImportExport=true"
}
if ($category -notmatch 'TogglePreference\(\s*context,\s*"Hide AI content"[\s\S]*?Settings\.HIDE_AI_CONTENT') {
    throw "Hide AI preference is missing from the Feed filter category"
}
if ([regex]::Matches($fragment, 'Settings\.HIDE_AI_CONTENT\.get\(\)').Count -ne 1) {
    throw "Feed filter master count must include Hide AI content exactly once"
}

Write-Output "Hide AI host verification OK"
