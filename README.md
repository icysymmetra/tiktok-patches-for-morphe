<p align="center">
  <img src="assets/readme-header.png" alt="Metra TikTok Patches" width="290"/>
</p>

<p align="center">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-GPLv3-blue.svg" /></a>
  <a href="https://github.com/MorpheApp/morphe-cli"><img alt="source" src="https://img.shields.io/badge/source-Morphe-00b894.svg" /></a>
  <a href="https://www.android.com/"><img alt="platform" src="https://img.shields.io/badge/platform-Android-3ddc84.svg" /></a>
  <a href="https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-46-2-3-release/tiktok-46-2-3-android-apk-download/"><img alt="TikTok 46.2.3" src="https://img.shields.io/badge/TikTok-46.2.3-ff0050.svg" /></a>
</p>

<p align="center">
  <a href="https://ko-fi.com/P5P5YOUU7">
    <img height="42" src="https://storage.ko-fi.com/cdn/kofi2.png?v=3" alt="Support my work on Ko-fi" />
  </a>
</p>

<p align="center">
  <sub><em>If you are using these patches and want to support the work, it helps me keep testing, improving, and keeping the patches maintained.</em></sub>
</p>

# Metra TikTok Patches

<br>

**This repository is a Morphe patch source for TikTok.**

**It continues the work from earlier community TikTok patch sets, including ReVanced, with the patches adapted for Morphe and tested against newer TikTok builds. The current target is the global TikTok package, `com.zhiliaoapp.musically`, on [TikTok `46.2.3`](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-46-2-3-release/tiktok-46-2-3-android-apk-download/).**

The goal is to keep the existing patch set usable while adding more TikTok-focused features over time. Some features are small fixes, some are quality-of-life changes, and some need deeper testing because TikTok changes its internals often.

<br>

## Available Patches

| Patch | Description |
|---|---|
| `Always show publish date` | Keeps the video's publish date visible in its author information. |
| `Copy comments without username` | Copies only the comment text without including the creator's username. |
| `Custom offline videos limit` | Adds a custom entry to TikTok's offline videos menu with a configurable limit from 1 to 1000 videos. Values outside the range use the nearest valid limit. |
| `Disable login requirement` | Removes TikTok's mandatory login gate from supported flows. |
| `Disable long-press quick share` | Keeps long-pressing Share from opening TikTok's quick-share interaction. |
| `Disable long-press repost` | Keeps holding Like from opening TikTok's repost action without disabling TikTok's wider repost and upvote systems. |
| `Disable screen capture detection` | Prevents TikTok from detecting screenshots and screen recordings. |
| `Diagnostic tools` | Adds optional structured Morphe logs, TikTok crash capture, and clipboard or file report export. |
| `Downloads` | Adds watermark-free downloads, filename templates, and comment sticker saving with animated-media preservation. |
| `Enable Live search` | Shows TikTok's search entry in the Live drawer where supported. |
| `Enable non-personalized search` | Uses TikTok's non-personalized search mode instead of its saved account choice. |
| `Feature Gate Lab` | Adds a searchable menu for viewing and overriding supported TikTok feature flags and configuration values. Client-side overrides cannot bypass server enforcement. |
| `Feed filter` | Hides feed ads, TikTok Shop items, livestreams, stories, photo posts, and videos outside configured view or like ranges, with optional filtering of cached and offline FYP fallback videos. |
| `Feed tab navigation` | Controls which loaded top and bottom navigation tabs remain visible, blocks newly added tabs when requested, and can hide the Tako AI bubble. |
| `Fix Google login` | Restores Google account sign-in after patching. |
| `Hide CAPTCHA popups` | Hides non-account verification puzzle dialogs, including those shown while browsing LIVE. Account verification remains available, and server checks are not bypassed. |
| `Hide floating promotions` | Removes floating promotional badges, coin icons, and timer banners from the Home feed. |
| `Hide feed follow button` | Adds an option to hide the + follow button below creator avatars in video feeds. |
| `Hide feed LIVE button` | Adds an option to hide the LIVE button at the top left of video feeds. |
| `Hide feed save button` | Adds an option to hide the save/favourites button from video feeds. |
| `Hide feed search button` | Adds an option to hide the search button at the top right of video feeds. |
| `Hide quick comment reactions` | Hides TikTok's exposed quick emoji row in supported comment inputs. |
| `Hold-and-slide 2x lock` | Enables TikTok's native hold, slide down, and release gesture for locking playback at 2x speed. |
| `Open external links directly` | Opens profile and story website links in the system browser instead of TikTok's in-app browser. |
| `Playback speed` | Enables playback-speed controls for all videos and remembers the selected speed between videos. |
| `Remember clear display` | Remembers TikTok's clear-display state between videos. |
| `Resume videos after scrolling` | Restores a video's prior playback position when returning to it in the feed. |
| `Region spoof` | Adds in-app controls for changing the region identity values TikTok reads. TikTok may still use IP address, account history, language, and other region signals. |
| `Sanitize sharing links` | Removes tracking parameters from TikTok links before they are shared. |
| `Settings` | Adds the Metra patches settings screen inside TikTok. |
| `Show seekbar` | Shows TikTok's native video seekbar where it would normally be hidden. |
| `Show seekbar thumbnail` | Shows TikTok's video preview thumbnail while dragging the seekbar. |
| `Stop video looping` | Stops a completed video instead of automatically replaying it. |
| `Translate comments` | Adds comment translation controls using TikTok's translation system, with selectable language exclusions. |

<br>

## Add Source

Open this link on the Android device where Morphe is installed:

[Add Metra TikTok Patches](https://morphe.software/add-source?github=icysymmetra/tiktok-patches-for-morphe)

You can also copy this repository URL and paste it into Morphe's add source field:

[https://github.com/icysymmetra/tiktok-patches-for-morphe](https://github.com/icysymmetra/tiktok-patches-for-morphe)

<br>

## Planned Work

Open to feature requests.

<br>

## Supported Target

- App: TikTok
- Version: [`46.2.3`](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-46-2-3-release/tiktok-46-2-3-android-apk-download/)
- Package: `com.zhiliaoapp.musically`

Only the global package is declared in Morphe compatibility metadata. The JP package may share some internals, but it is not advertised as supported unless it gets its own proof pass again.

## Building

Build the Morphe patch bundle and metadata:

```bash
./gradlew :patches:buildAndroid :patches:generatePatchesList
```

The generated bundle is written to:

```text
patches/build/libs/patches-<version>.mpp
```

Morphe reads `patches-bundle.json` from this repository, downloads the `.mpp` release asset listed there, and loads the patch metadata from that bundle.

<br>

## Project Structure

- `patches/`: Kotlin patch definitions, fingerprints, and shared patch utilities.
- `extensions/`: Java extension code injected into TikTok by the patches.
- `patches-list.json`: Generated patch metadata.
- `patches-bundle.json`: Morphe source metadata for the published release bundle.

## Credits

- Thanks to [@lyyako](https://github.com/lyyako) for the original contributions behind the simplified sanitize sharing links hook, show seekbar patch, anti-recording patch, `Open external links directly`, and `Always show publish date`.
- Thanks to [@oscski](https://github.com/oscski) for the original contribution behind `Disable long-press repost`.

## Notes

- The source is based on [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced) and the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
- It is not affiliated with TikTok, ByteDance, or Morphe.
- TikTok changes often, so compatibility is intentionally tied to the exact version and packages listed above.

<br>

## License

This project reuses the GPLv3 licensing from the projects it was built on.

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
