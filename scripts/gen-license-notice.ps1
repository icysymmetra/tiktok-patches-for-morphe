# Writes the NOTICE file into a Java constant the payload can show, because a patched APK has no
# way to read a file out of this repository. A test holds the two together, so this only has to
# be run again when NOTICE changes.
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$notice = [IO.File]::ReadAllText((Join-Path $repo 'NOTICE')).Replace("`r`n", "`n").TrimEnd()

$lines = $notice -split "`n"
$java = New-Object System.Collections.Generic.List[string]
$java.Add('/*')
$java.Add(' * Copyright 2026 Hushfeed contributors')
$java.Add(' * https://github.com/SysAdminDoc/hushfeed')
$java.Add(' *')
$java.Add(' * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).')
$java.Add(' */')
$java.Add('package app.morphe.extension.tiktok.settings.preference;')
$java.Add('')
$java.Add('/**')
$java.Add(' * The repository''s NOTICE file, as text the payload can show.')
$java.Add(' *')
$java.Add(' * <p>Morphe''s Section 7b asks that derivative works carry its notice and provide it to the')
$java.Add(' * person using the software. A patched APK cannot read a file out of the repository, so the')
$java.Add(' * text is carried here and shown by the Licences row under About.')
$java.Add(' *')
$java.Add(' * <p>Generated from NOTICE. Do not edit it here: edit NOTICE, then run')
$java.Add(' * scripts/gen-license-notice.ps1. {@code LicenseNoticeTest} fails if the two drift, which is')
$java.Add(' * what stops the app showing a notice the repository no longer carries.')
$java.Add(' */')
$java.Add('public final class LicenseNotice {')
$java.Add('    private LicenseNotice() {}')
$java.Add('')
$java.Add('    /** Where the full licence text lives, for the button beside the notice. */')
$java.Add('    public static final String LICENSE_URL =')
$java.Add('            "https://github.com/SysAdminDoc/hushfeed/blob/main/LICENSE";')
$java.Add('')
$java.Add('    /** The NOTICE file, verbatim. */')
$java.Add('    public static final String TEXT =')

for ($i = 0; $i -lt $lines.Count; $i++) {
    $escaped = $lines[$i].Replace('\', '\\').Replace('"', '\"')
    $terminator = if ($i -eq $lines.Count - 1) { ';' } else { ' +' }
    $newline = if ($i -eq $lines.Count - 1) { '' } else { '\n' }
    $java.Add('            "' + $escaped + $newline + '"' + $terminator)
}
$java.Add('}')

$target = Join-Path $repo 'extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/LicenseNotice.java'
[IO.File]::WriteAllText($target, ($java -join "`n") + "`n", [Text.UTF8Encoding]::new($false))
"wrote $($lines.Count) lines of NOTICE into LicenseNotice.java"
