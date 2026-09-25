# Contributing

Feature ideas, bug reports, and pull requests are welcome.

If you open an issue, include:

- the TikTok package name and version, and the Morphe patch bundle version
- the selected patches and relevant in-app toggle values
- steps to reproduce, what you expected, and what actually happened
- whether the same action works with the suspected patch or toggle disabled, if you tested it
- screenshots or a short recording for visual issues; a diagnostic report for crashes

For feed issues, follow the [feed debugging guide](docs/feed-controls-and-debugging.md) and attach the original feed debugger export if you captured one. Start it before reproducing the problem. A report started after an affected item appeared may miss the decisive event. Review reports for personal information before posting them publicly.

Small focused pull requests are easier to test than large mixed changes.

For patch changes, please include what APK version you tested against and what behavior you verified.

## Source notices

Preserve every existing copyright, license, author-credit, and source-origin
notice when modifying or moving a file. Do not remove or replace a notice unless
the attributed code is removed from the file.

New source written specifically for this project may use:

```text
/*
 * Copyright <year> icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
```

For source derived from another project, retain its existing notices and add a
clear `Forked from:` URL when the origin is not already stated. Do not use a
project-only copyright header in a way that implies exclusive ownership of
upstream code.
