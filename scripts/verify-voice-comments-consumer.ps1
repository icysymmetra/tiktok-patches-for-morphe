param(
    [Parameter(Mandatory = $true)]
    [string]$DexDirectory,

    [Parameter(Mandatory = $true)]
    [string]$DexInspectDirectory,

    [Parameter(Mandatory = $true)]
    [string]$MorpheJar
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $DexDirectory -PathType Container)) {
    throw "DEX directory not found: $DexDirectory"
}
if (-not (Test-Path -LiteralPath $MorpheJar -PathType Leaf)) {
    throw "Morphe jar not found: $MorpheJar"
}

$classPath = $DexInspectDirectory + [IO.Path]::PathSeparator + $MorpheJar
$failures = [System.Collections.Generic.List[string]]::new()

function Invoke-DexTool {
    param([string]$Tool, [string[]]$Arguments)

    $output = & java -cp $classPath $Tool @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Tool failed: $($output -join [Environment]::NewLine)"
    }
    return $output -join [Environment]::NewLine
}

function Find-DexForClass {
    param([string]$ClassNeedle)

    $match = Invoke-DexTool 'FindDexClass' @($DexDirectory, $ClassNeedle)
    $firstLine = ($match -split "`r?`n" | Select-Object -First 1)
    if (-not $firstLine) {
        $failures.Add("Missing target class: $ClassNeedle")
        return $null
    }
    return Join-Path $DexDirectory ($firstLine -split "`t")[0]
}

function Require-Text {
    param([string]$Text, [string]$Pattern, [string]$Message)

    if ($Text -notmatch $Pattern) {
        $failures.Add($Message)
    }
}

$cellDex = Find-DexForClass 'BaseCommentCell'
$modelDex = Find-DexForClass 'OptJsonAdapterFor$com$ss$android$ugc$aweme$comment$model$Comment'

if ($cellDex) {
    $bind = Invoke-DexTool 'DumpDexMethod' @($cellDex, 'BaseCommentCell', 'O6')
    $render = Invoke-DexTool 'DumpDexMethod' @($cellDex, 'BaseCommentCell', 'd8')

    Require-Text $bind 'BaseCommentCell;->d8\(\)V' `
        'The normal full-bind path no longer invokes the audio-comment renderer.'
    Require-Text $render 'Comment;->getAudioStruct\(\)Lcom/ss/android/ugc/aweme/comment/model/CmtAudioStruct;' `
        'The renderer no longer reads the received comment audio structure.'
    Require-Text $render 'LX/0nwb;->LIZJ\(Lcom/ss/android/ugc/aweme/comment/model/CmtAudioStruct;\)' `
        'The renderer no longer converts received audio data into an AudioCommentModel.'
    Require-Text $render 'LX/0nwR;->LJII\(' `
        'The renderer no longer binds the audio model to TikTok audio-comment UI.'
    Require-Text $render 'Landroid/view/View;->setVisibility\(I\)V' `
        'The renderer no longer exposes the native audio-comment view.'
}

if ($modelDex) {
    $adapter = Invoke-DexTool 'DumpDexMethod' @(
        $modelDex,
        'OptJsonAdapterFor$com$ss$android$ugc$aweme$comment$model$Comment',
        'LIZJ'
    )
    Require-Text $adapter 'const-string(?:/jumbo)?\s+v\d+\s+; cmt_audio_struct' `
        'The comment JSON adapter no longer recognizes the server cmt_audio_struct field.'
    Require-Text $adapter 'Comment;->audioStruct:Lcom/ss/android/ugc/aweme/comment/model/CmtAudioStruct;' `
        'The comment JSON adapter no longer stores received audio data on Comment.audioStruct.'
}

if ($failures.Count -gt 0) {
    Write-Host 'VOICE_COMMENT_CONSUMER_CHECK=FAIL'
    foreach ($failure in $failures) {
        Write-Host (' - ' + $failure)
    }
    exit 1
}

Write-Host 'VOICE_COMMENT_CONSUMER_CHECK=PASS'
Write-Host 'CONSUMER_CONTRACT=cmt_audio_struct -> Comment.audioStruct -> BaseCommentCell.d8 -> native audio view'
Write-Host 'BOUNDARY=If a fetched comment has no cmt_audio_struct, this client has no audio payload to display.'
