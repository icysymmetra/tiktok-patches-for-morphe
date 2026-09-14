param(
    [string]$RepositoryRoot = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

$preferenceRoot = Join-Path $RepositoryRoot 'extensions\tiktok\src\main\java\app\morphe\extension\tiktok\settings\preference'
$settingsUiPath = Join-Path $preferenceRoot 'SettingsUi.java'
$qualityPath = Join-Path $preferenceRoot 'DownloadQualityPreference.java'
$headerPath = Join-Path $preferenceRoot 'SettingsHeaderPreference.java'
$supportUiPath = Join-Path $preferenceRoot 'SupportUi.java'
$categoryRoot = Join-Path $preferenceRoot 'categories'

$failures = [System.Collections.Generic.List[string]]::new()

function Require-Match {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Message
    )

    $content = Get-Content -LiteralPath $Path -Raw
    if ($content -notmatch $Pattern) {
        $failures.Add($Message)
    }
}

function Reject-Match {
    param(
        [string]$Path,
        [string]$Pattern,
        [string]$Message
    )

    $content = Get-Content -LiteralPath $Path -Raw
    if ($content -match $Pattern) {
        $failures.Add($Message)
    }
}

Reject-Match $settingsUiPath '\.setCheckMarkDrawable\s*\(' `
    'Shared dialog styling must preserve the platform selector drawable; replacing it creates a second selector.'
Reject-Match $settingsUiPath 'class\s+DialogCheckMarkDrawable\b' `
    'The custom dialog check-mark drawable must not coexist with the platform single-choice selector.'
Require-Match $qualityPath '\.setSingleChoiceItems\s*\(' `
    'Video quality must remain a native single-choice dialog.'
Require-Match $qualityPath 'SettingsUi\.styleStandardAlertDialog\s*\(' `
    'Video quality must use the shared alert-dialog styling path.'

$selectorFiles = Get-ChildItem -LiteralPath $preferenceRoot -Filter '*.java' -Recurse |
    Where-Object { (Get-Content -LiteralPath $_.FullName -Raw) -match '\.set(Single|Multi)ChoiceItems\s*\(' }
foreach ($selectorFile in $selectorFiles) {
    Reject-Match $selectorFile.FullName '\.setCheckMarkDrawable\s*\(' `
        "Selector popup $($selectorFile.Name) must not add a second check-mark drawable."
}

Require-Match $headerPath 'createSupportPill\s*\(' `
    'Metra settings headers must expose the shared Support pill.'
Require-Match $supportUiPath 'SUPPORT_URL\s*=\s*"https://ko-fi\.com/P5P5YOUU7"' `
    'The Support pill and support row must share the canonical support destination.'

$expectedGroups = [ordered]@{
    'DownloadsPreferenceCategory.java' = @('Video downloads', 'Story downloads', 'Save locations', 'File names', 'Offline viewing')
    'FeedFilterPreferenceCategory.java' = @('Content types', 'Popularity limits', 'Offline fallback')
    'FeedNavigationPreferenceCategory.java' = @('Feed tabs', 'Bottom navigation', 'Other navigation')
    'InterfacePreferenceCategory.java' = @('Feed controls', 'Promotions and dialogs', 'Video information')
    'CommentsPreferenceCategory.java' = @('Translation', 'Comment actions', 'Large-screen layout')
    'SimSpoofPreferenceCategory.java' = @('Region selection', 'Manual operator values')
    'ShareSheetPreferenceCategory.java' = @('Quick share', 'Sharing apps', 'Video actions')
    'ExtensionPreferenceCategory.java' = @('Links and sharing', 'Playback', 'Gestures', 'Discovery and search')
    'DebugPreferenceCategory.java' = @('Logging and crash capture', 'Reports and stored data')
}
foreach ($entry in $expectedGroups.GetEnumerator()) {
    $categoryPath = Join-Path $categoryRoot $entry.Key
    foreach ($group in $entry.Value) {
        Require-Match $categoryPath ([regex]::Escape('group(context, "' + $group + '")')) `
            "$($entry.Key) is missing the '$group' section."
    }
}

if ($failures.Count -gt 0) {
    Write-Host 'SETTINGS_UI_REGRESSION_CHECK=FAIL'
    foreach ($failure in $failures) {
        Write-Host (' - ' + $failure)
    }
    exit 1
}

Write-Host 'SETTINGS_UI_REGRESSION_CHECK=PASS'
Write-Host ('SELECTOR_POPUPS_AUDITED=' + $selectorFiles.Count)
