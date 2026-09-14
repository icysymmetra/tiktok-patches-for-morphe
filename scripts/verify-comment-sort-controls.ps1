param(
    [Parameter(Mandatory = $true)]
    [string]$DexDirectory,
    [string]$ApkPath,
    [string]$DexInspectDirectory = 'C:\Users\USER\Downloads\projects\morphe-tiktok-patches\tools\dexinspect',
    [string]$MorpheJar = 'C:\Users\USER\Downloads\projects\morphe-tiktok-patches\tools\morphe-cli\morphe-desktop-1.13.2-all.jar'
)

$ErrorActionPreference = 'Stop'
$failures = [System.Collections.Generic.List[string]]::new()
$repoRoot = Split-Path -Parent $PSScriptRoot
$commentSortRoot = Join-Path $repoRoot 'patches/src/main/kotlin/app/morphe/patches/tiktok/misc/commentsort'
$patchPath = Join-Path $commentSortRoot 'CommentSortControlsPatch.kt'
$fingerprintsPath = Join-Path $commentSortRoot 'Fingerprints.kt'
$extensionPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/commentsort/CommentSortControls.java'
$settingsPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/Settings.java'
$settingsStatusPath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/SettingsStatus.java'
$commentsPreferencePath = Join-Path $repoRoot 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/categories/CommentsPreferenceCategory.java'
$dexPath = Join-Path (Resolve-Path -LiteralPath $DexDirectory).Path 'classes17.dex'
$dexClasspath = $DexInspectDirectory + [IO.Path]::PathSeparator + $MorpheJar

function Assert-TextContains {
    param(
        [string]$Text,
        [string]$Needle,
        [string]$Description
    )

    if (-not $Text.Contains($Needle)) {
        $failures.Add($Description)
        Write-Host "FAIL: $Description"
        return
    }
    Write-Host "PASS: $Description"
}

function Assert-FileContains {
    param(
        [string]$Path,
        [string]$Needle,
        [string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        $failures.Add("$Description (missing file: $Path)")
        Write-Host "FAIL: $Description (missing file)"
        return
    }
    Assert-TextContains (Get-Content -Raw -LiteralPath $Path) $Needle $Description
}

$enumCode = (java -cp $dexClasspath DumpDexMethod $dexPath 'LX/0nqo;' '<clinit>') -join "`n"
foreach ($sortName in @('DEFAULT_SORT', 'TIME_SORT', 'MEDIA_SORT', 'CREATOR_SORT')) {
    Assert-TextContains $enumCode $sortName "TikTok native enum contains $sortName"
}

$telemetryCode = (java -cp $dexClasspath DumpDexMethod $dexPath 'LX/0nsA;' 'LIZ') -join "`n"
foreach ($wireName in @('hot', 'time', 'media', 'creator')) {
    Assert-TextContains $telemetryCode "; $wireName" "TikTok maps a native sort mode to '$wireName'"
}

$menuCode = (java -cp $dexClasspath DumpDexMethod $dexPath 'Lcom/ss/android/ugc/aweme/commentv2/commentlist/ui/CommentPowerListAssem;' 'Ye0') -join "`n"
foreach ($sortName in @('DEFAULT_SORT', 'TIME_SORT', 'MEDIA_SORT', 'CREATOR_SORT')) {
    Assert-TextContains $menuCode "LX/0nqo;->$sortName" "TikTok native sort sheet builds the $sortName row"
}
Assert-TextContains $menuCode 'Lkotlin/jvm/internal/AwS289S0300000_22;-><init>' 'native sort rows retain TikTok click callbacks'
Assert-TextContains $menuCode 'hasMediaComment' 'media row remains guarded by TikTok content capability'
Assert-TextContains $menuCode 'hasCreatorComment' 'creator row remains guarded by TikTok content capability'

$styleCode = (java -cp $dexClasspath DumpDexMethod $dexPath 'Lkotlin/jvm/internal/AFwS216S0000000_22;' 'invoke$200') -join "`n"
Assert-TextContains $styleCode 'comment_sort_opt_style' 'comment sorter presentation A/B setting is present'
Assert-TextContains $styleCode 'literal=0x1' 'stock comment sorter presentation defaults to style 1'

$eligibilityCode = (java -cp $dexClasspath DumpDexMethod $dexPath 'LX/0nmj;' 'LIZ') -join "`n"
Assert-TextContains $eligibilityCode 'AwemeExtKt;->getAuthorUid' 'stock sorter eligibility reads the video author'
Assert-TextContains $eligibilityCode 'LX/0NqH;->LIZLLL(Ljava/lang/String;)Z' 'stock sorter eligibility checks the current user against the author'

Assert-FileContains $settingsPath 'COMMENT_SORT_FORCE_SHOW' 'force-show comment sorting setting is declared'
Assert-FileContains $settingsStatusPath 'enableCommentSortControls' 'comment sort settings status is registered'
Assert-FileContains $commentsPreferencePath 'Force show comment sorting' 'force-show toggle is exposed in Comments settings'
Assert-FileContains $fingerprintsPath 'comment_sort_opt_style' 'sort presentation A/B evaluator is fingerprinted'
Assert-FileContains $fingerprintsPath 'LX/0nmj;' 'sort eligibility method is fingerprinted'
Assert-FileContains $patchPath 'forceOptionStyle' 'sort presentation A/B result is hooked'
Assert-FileContains $patchPath 'shouldForceSortEligibility' 'sort eligibility is hooked'
Assert-FileContains $extensionPath 'return force ? FULL_SORT_SHEET_STYLE : originalStyle' 'enabled setting selects TikTok full sort-sheet style 2'

if ($ApkPath) {
    $resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
    $apkAnalyzer = Join-Path $env:LOCALAPPDATA 'Android/Sdk/cmdline-tools/latest/bin/apkanalyzer.bat'
    if (-not (Test-Path -LiteralPath $apkAnalyzer)) {
        $failures.Add("apkanalyzer was not found at $apkAnalyzer")
        Write-Host 'FAIL: apkanalyzer is available'
    } else {
        $styleLambdaApkCode = (& $apkAnalyzer dex code --class 'kotlin.jvm.internal.AFwS216S0000000_22' $resolvedApk) -join "`n"
        $eligibilityApkCode = (& $apkAnalyzer dex code --class 'X.0nmj' $resolvedApk) -join "`n"
        $extensionApkCode = (& $apkAnalyzer dex code --class 'app.morphe.extension.tiktok.commentsort.CommentSortControls' $resolvedApk) -join "`n"

        Assert-TextContains $styleLambdaApkCode 'CommentSortControls;->forceOptionStyle(I)I' 'patched APK overrides only the sorter A/B style result'
        Assert-TextContains $eligibilityApkCode 'CommentSortControls;->shouldForceSortEligibility()Z' 'patched APK hooks the shared sorter eligibility method'
        Assert-TextContains $extensionApkCode 'COMMENT_SORT_FORCE_SHOW' 'patched APK extension reads the user setting'

        if ($extensionApkCode.Contains('LX/0nqo;') -or $extensionApkCode.Contains('CommentContextSource;')) {
            $failures.Add('extension must not construct or depend on TikTok sort models')
            Write-Host 'FAIL: extension does not construct or depend on TikTok sort models'
        } else {
            Write-Host 'PASS: extension does not construct or depend on TikTok sort models'
        }
    }
}

if ($failures.Count -gt 0) {
    throw "Comment sort controls verification failed ($($failures.Count) failure(s))."
}

Write-Host 'Comment sort controls verification passed.'
