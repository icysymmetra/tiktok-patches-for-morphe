param(
    [string]$ApkPath
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$fingerprintsPath = Join-Path $repoRoot 'patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/autoscroll/Fingerprints.kt'
$patchPath = Join-Path $repoRoot 'patches/src/main/kotlin/app/morphe/patches/tiktok/interaction/autoscroll/ForceShowAutoScrollPatch.kt'
$controlsPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/autoscroll/AutoScrollControls.java'
$settingsPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/Settings.java'
$settingsStatusPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsStatus.java'
$preferencePath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/categories/ExtensionPreferenceCategory.java'
$shareSheetPreferencePath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/categories/ShareSheetPreferenceCategory.java'
$shareSheetFingerprintsPath = Join-Path $repoRoot 'patches/src/main/kotlin/app/morphe/patches/tiktok/misc/sharesheet/Fingerprints.kt'
$shareSheetPatchPath = Join-Path $repoRoot 'patches/src/main/kotlin/app/morphe/patches/tiktok/misc/sharesheet/ShareSheetPatch.kt'
$shareSheetFilterPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/sharesheet/ShareSheetFilter.java'
$patchesListPath = Join-Path $repoRoot 'patches-list.json'

function Assert-Contains {
    param(
        [string]$Path,
        [string]$Needle,
        [string]$Description
    )

    $content = Get-Content -Raw -LiteralPath $Path
    if (-not $content.Contains($Needle)) {
        throw "FAIL: $Description"
    }
    Write-Host "PASS: $Description"
}

function Assert-NotContains {
    param(
        [string]$Path,
        [string]$Needle,
        [string]$Description
    )

    $content = Get-Content -Raw -LiteralPath $Path
    if ($content.Contains($Needle)) {
        throw "FAIL: $Description"
    }
    Write-Host "PASS: $Description"
}

Assert-Contains $settingsPath 'FORCE_SHOW_AUTO_SCROLL' 'force-show setting is declared independently of share-sheet settings'
Assert-Contains $preferencePath 'Force show Auto scroll' 'force-show toggle is exposed in App behavior settings'
Assert-Contains $preferencePath 'SettingsStatus.autoScrollEnabled' 'force-show toggle visibility follows the Auto scroll patch'
if ((Get-Content -Raw -LiteralPath $shareSheetPreferencePath).Contains('Force show Auto scroll')) {
    throw 'FAIL: force-show toggle remains exposed in Share sheet settings'
}
Write-Host 'PASS: force-show toggle is no longer exposed in Share sheet settings'
Assert-Contains $settingsStatusPath 'autoScrollEnabled' 'Auto scroll has an independent patch-status flag'
Assert-Contains $settingsStatusPath 'enableAutoScroll' 'Auto scroll has an independent patch-status initializer'
Assert-Contains $fingerprintsPath '"fyp_auto_scroll"' 'TikTok fyp_auto_scroll gate is fingerprinted'
Assert-Contains $fingerprintsPath '"panel_auto_scroll"' 'TikTok panel_auto_scroll gate is fingerprinted'
Assert-Contains $patchPath 'name = "Force show Auto scroll"' 'Auto scroll is declared as its own selectable patch'
Assert-Contains $patchPath 'enableAutoScroll()V' 'Auto scroll patch exposes only its own setting'
Assert-NotContains $patchPath 'shareSheetPatch' 'Auto scroll patch does not depend on Share sheet modification'
Assert-Contains $patchPath 'AutoScrollFeatureGateFingerprint' 'account rollout gate is hooked'
Assert-Contains $patchPath 'AutoScrollActionFactoryFingerprint' 'panel rollout gate is hooked'
Assert-Contains $controlsPath 'shouldForceAutoScroll' 'force-show logic reads the setting'
Assert-Contains $controlsPath 'forceAutoScrollPanelAvailability' 'panel availability preserves the stock result'
Assert-Contains $patchesListPath '"name": "Force show Auto scroll"' 'generated metadata lists Auto scroll separately'
Assert-NotContains $shareSheetFingerprintsPath 'AutoScroll' 'Share sheet fingerprints contain no Auto scroll hooks'
Assert-NotContains $shareSheetPatchPath 'AutoScroll' 'Share sheet patch contains no Auto scroll hooks'
Assert-NotContains $shareSheetFilterPath 'AutoScroll' 'Share sheet extension contains no Auto scroll logic'

if ($ApkPath) {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $apkAnalyzer = Join-Path $env:LOCALAPPDATA 'Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat'
    if (-not (Test-Path -LiteralPath $apkAnalyzer)) {
        throw "FAIL: apkanalyzer was not found at $apkAnalyzer"
    }

    $featureGateCode = (& $apkAnalyzer dex code --class 'czc.o1' $resolvedApk) -join "`n"
    $actionFactoryCode = (& $apkAnalyzer dex code --class 'X.0oi7' $resolvedApk) -join "`n"
    $extensionCode = (& $apkAnalyzer dex code --class 'app.morphe.extension.tiktok.autoscroll.AutoScrollControls' $resolvedApk) -join "`n"
    $settingsStatusCode = (& $apkAnalyzer dex code --class 'app.morphe.extension.tiktok.settings.SettingsStatus' $resolvedApk) -join "`n"

    if (-not $featureGateCode.Contains('AutoScrollControls;->shouldForceAutoScroll()Z')) {
        throw 'FAIL: patched fyp_auto_scroll gate hook is absent from the APK'
    }
    Write-Host 'PASS: patched fyp_auto_scroll gate hook is present in the APK'

    if (-not $actionFactoryCode.Contains('AutoScrollControls;->forceAutoScrollPanelAvailability(Z)Z')) {
        throw 'FAIL: patched panel_auto_scroll gate hook is absent from the APK'
    }
    Write-Host 'PASS: patched panel_auto_scroll gate hook is present in the APK'

    if (-not $actionFactoryCode.Contains('IAutoAScrollAbility;')) {
        throw 'FAIL: TikTok native Auto scroll ability path is absent from the patched action factory'
    }
    Write-Host 'PASS: TikTok native Auto scroll ability path remains in the APK'

    if (-not $settingsStatusCode.Contains(
        'invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAutoScroll()V'
    )) {
        throw 'FAIL: Auto scroll patch does not independently expose its setting in the APK'
    }
    Write-Host 'PASS: Auto scroll patch independently exposes its setting in the APK'

    if ($extensionCode.Contains('IAutoAScrollAbility;') -or $extensionCode.Contains('LX/0oe7;')) {
        throw 'FAIL: extension directly references TikTok Auto scroll ability or action types'
    }
    Write-Host 'PASS: extension does not construct a replacement Auto scroll action'
}

Write-Host 'Force-show Auto scroll verification passed.'
