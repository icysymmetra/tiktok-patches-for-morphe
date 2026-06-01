<p align="center">
  <img src="assets/readme-header.png" alt="TikTok Patches for Morphe" width="290"/>
</p>

<p align="center">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-GPLv3-blue.svg" /></a>
  <a href="https://github.com/MorpheApp/morphe-cli"><img alt="source" src="https://img.shields.io/badge/source-Morphe-00b894.svg" /></a>
  <a href="https://www.android.com/"><img alt="platform" src="https://img.shields.io/badge/platform-Android-3ddc84.svg" /></a>
  <a href="https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-43-8-3-release/tiktok-43-8-3-2-android-apk-download/"><img alt="TikTok 43.8.3" src="https://img.shields.io/badge/TikTok-43.8.3-ff0050.svg" /></a>
</p>

<p align="center">
  <a href="https://ko-fi.com/P5P5YOUU7">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Support my work on Ko-fi" />
  </a>
</p>

<p align="center">
  <sub><em>If you are using these patches and want to support the work, it helps me keep testing, improving, and keeping the patches maintained.</em></sub>
</p>

# TikTok Patches for Morphe

<br>

**This repository is a Morphe patch source for TikTok.**

**It continues the work from earlier community TikTok patch sets, including ReVanced, with the patches adapted for Morphe and tested against newer TikTok builds. The current target is the global TikTok package, `com.zhiliaoapp.musically`, on [TikTok `43.8.3`](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-43-8-3-release/tiktok-43-8-3-2-android-apk-download/).**

The goal is to keep the existing patch set usable while adding more TikTok-focused features over time. Some features are small fixes, some are quality-of-life changes, and some need deeper testing because TikTok changes its internals often.

<br>

## Available Patches

- `Settings`: Adds the Morphe settings screen inside TikTok.
- `Enable Open Debug`: Uses TikTok's hidden Open Debug settings cell as the entry point for Morphe settings.
- `Disable login requirement`: Lets supported TikTok flows load without the normal login wall first.
- `Fix Google login`: Restores Google sign-in behavior after patching.
- `Feed filter`: Filters feed items such as ads, livestreams, stories, shop content, image videos, and videos outside configured view or like ranges.
- `Downloads`: Downloads videos without watermark and adds download-related controls.
- `Playback speed`: Adds playback speed controls.
- `Remember clear display`: Keeps the clear-display state across videos.
- `SIM spoof`: Spoofs SIM-related values used by TikTok region logic.
- `Sanitize sharing links`: Cleans TikTok share links before they leave the app.
- `Show seekbar`: Shows the video seekbar where TikTok would normally hide it.

<br>

## Add Source

Open this link on the Android device where Morphe is installed:

[Add TikTok Patches for Morphe](https://morphe.software/add-source?github=icysymmetra/tiktok-patches-for-morphe)

You can also copy this repository URL and paste it into Morphe's add source field:

[https://github.com/icysymmetra/tiktok-patches-for-morphe](https://github.com/icysymmetra/tiktok-patches-for-morphe)

<br>

## Planned Work

### In Progress

- [~] Drama mini series feed video filter
- [~] Custom offline video download limit

### Planned

- [ ] Remove create button
- [ ] Remove Tako AI
- [ ] Copy comments without the original commenter's username
- [ ] Feed tab navigation toggles for Friends, Explore, Following, and For You

<br>

## Supported Target

- App: TikTok
- Version: [`43.8.3`](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok/tiktok-43-8-3-2-release/tiktok-43-8-3-4-android-apk-download/)
- Package: `com.zhiliaoapp.musically`

Only the global package is declared in Morphe compatibility metadata. The JP package may share some internals, but it is not advertised as supported unless it gets its own proof pass again.

## Building

Build the Morphe patch bundle and metadata:

```bash
./gradlew :patches:buildAndroid :patches:generatePatchesList
```

The generated bundle is written to:

```text
patches/build/libs/patches-0.1.0.mpp
```

Morphe reads `patches-bundle.json` from this repository, downloads the `.mpp` release asset listed there, and loads the patch metadata from that bundle.

<br>

## Project Structure

- `patches/`: Kotlin patch definitions, fingerprints, and shared patch utilities.
- `extensions/`: Java extension code injected into TikTok by the patches.
- `patches-list.json`: Generated patch metadata.
- `patches-bundle.json`: Morphe source metadata for the published release bundle.

## Notes

- The source is based on [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced) and the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
- It is not affiliated with TikTok, ByteDance, or Morphe.
- TikTok changes often, so compatibility is intentionally tied to the exact version and packages listed above.

<br>

## License

This project reuses the GPLv3 licensing from the projects it was built on.

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
