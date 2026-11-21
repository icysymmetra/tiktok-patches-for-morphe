# Recovery report trial

Use this only when a maintainer asks for a freeze or crash report. It requires Android 11 or later. The patch option is off by default and does not turn on ordinary debug logging or upload anything.

1. Patch TikTok 47.4.3 in Morphe with **Diagnostic tools** selected. Open that patch's options and enable **Automatic recovery report trial**, then install the update normally.
2. Open TikTok promptly and reproduce the problem. If Android offers **Close app**, close it and reopen TikTok once. Allow about 30 seconds for recovery, even if the screen freezes again. Opening Metra settings is unnecessary.
3. In your Files app, open **Downloads/Morphe** and share the newest report whose name begins with `Metra-issue215-`. A report saying that no trace was available is still useful. Send the newest file if there is more than one.

If no file appears, reopen once more and check again. Tell the maintainer if nothing was saved. Re-patching with the option enabled starts a fresh trial without clearing TikTok's data or signing you out. A maintainer may ask for a comparison build after reading the report.

## Capture limits

The trial tracks up to three main-process starts and makes at most four recovery checks, including a final check for the third start's failure. The 30-minute window begins at the first main-process start; background starts can consume it. A reboot, clock change or expired window produces a status report when possible. Android can omit or overwrite a trace, and failures before the startup hook cannot be recovered.

Reports contain selected stack frames, thread/lock metadata and patch build details. Raw system dumps, arbitrary thread names and messages are excluded. Sanitized evidence is kept privately before export; clearing diagnostics suppresses replay of old exits, and Undo can restore the cleared snapshot.

Automatic file export is limited to 24 hours and three attempts per report payload. A blocked Android storage service may prevent saving in that process; retained private evidence can be retried on relaunch or included in a manual full report. A previously completed public file is preserved if later readback disagrees with its contents. Completed receipts record a past successful save; they do not monitor whether a file was subsequently moved or deleted.
