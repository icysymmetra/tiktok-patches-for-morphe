<p align="center">
  <img src="assets/readme-header.png" alt="Metra TikTok Patches" width="290"/>
</p>

<p align="center">
  <a href="LICENSE"><img alt="license" src="https://img.shields.io/badge/license-GPLv3-blue.svg" /></a>
  <a href="https://github.com/MorpheApp/morphe-cli"><img alt="source" src="https://img.shields.io/badge/source-Morphe-00b894.svg" /></a>
  <a href="https://www.android.com/"><img alt="platform" src="https://img.shields.io/badge/platform-Android-3ddc84.svg" /></a>
  <a href="https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-4-3-release/"><img alt="TikTok 47.4.3 dev" src="https://img.shields.io/badge/TikTok-47.4.3_dev-ff0050.svg" /></a>
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

**This dev channel targets global TikTok 47.4.3, package `com.zhiliaoapp.musically`, with the expanded Metra feature set and Morphe Patcher 2.0.**

The goal is to keep the existing patch set usable while adding more TikTok-focused features over time. Some features are small fixes, some are quality-of-life changes, and some need deeper testing because TikTok changes its internals often.

<br>

## Development target

The expanded Metra feature set is adapted from Hushfeed v0.76.0, commit `b68741ca02898c2003751da75df34b44b8943daa`, to global TikTok 47.4.3 (version code 2024704030). It keeps Metra's style, navigation and support links, including the Anti-slop and Feature Gate Lab repairs. The table below describes this dev prerelease. The stable release still targets 46.2.3.

Use Morphe Manager 2.0.0 or newer and enable prerelease source updates to test this bundle. Older Managers cannot load its Patcher 2.0 API.

Validation includes 1,084 passing host tests, strict fingerprint validation of the default and all-patch selections, and before/after DEX and resource audits. Android runtime testing is pending. Please report patching failures with the selected options and patching log.

**[Add Metra to Morphe](https://morphe.software/add-source?github=icysymmetra%2Ftiktok-patches-for-morphe)**. For the manual fallback, paste `https://github.com/icysymmetra/tiktok-patches-for-morphe` in Manager.

## Save recovery

If TikTok closes while saves are still going, the next time you open it a banner names the ones that did not finish, or that Metra cannot confirm, and how many files are missing. Files already in your gallery are left alone. Saves do not resume automatically. The local recovery record holds no links or tokens. On Android 9 and older, an unfinished file is noted by its path, which can include the creator and video ID, until confirmed. The entry is removed when the save ends or after the recovery banner names it.

## Available Patches

Simple mode in Morphe Manager selects 118 patches from this development bundle. The other 18 patches must be selected separately. Selecting a patch makes its controls available; each setting keeps the default described below.



| Patch | Description |
|---|---|
| `AMOLED dark theme` | Makes TikTok's dark mode backgrounds pure black, or a dark color you pick in this patch's options. Black lets OLED screens switch those pixels off. Light mode doesn't change. It's built in while patching, so only patching again without it undoes it. |
| `Accent color` | Swaps the red of TikTok's main buttons and red text for blue, green, purple, orange, teal or a color code you type in this patch's options, or for the color of the phone's wallpaper theme on Android 12 and up. Warnings stay red, and so can icons with the red drawn in. It's built in while patching, so only patching again without it undoes it. |
| `Advanced downloads` | Adds more ways to save: pick the video quality, save full-size photos, the sound on its own or a photo post as one video, hold a profile picture or story to save it, save a story or a video's cover from the share sheet, and pick videos on a profile grid to save in one run. Video quality starts at Highest, retaining Metra's existing default. Configure these controls in Metra settings > Downloads. |
| `Allow Duet and Stitch` | Shows the Duet and Stitch buttons on videos whose creator turned them off, so you can still make one. TikTok may still refuse some videos or the upload. Starts off. Turn it on in Metra settings > Share sheet. |
| `Allow screenshots and Circle to Search` | Lets screenshots, screen recordings and Circle to Search work on TikTok screens that would otherwise come out black. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok. |
| `Always show publish date` | Shows the date a video was posted next to the creator's name, so you can tell how old it is. It can also show the exact time, or dates on profile grids. On by default. Turn it off in Metra settings > Interface. |
| `Always upload in HD` | Posts every video you upload in HD, as if you'd turned on TikTok's own HD upload setting each time. Videos TikTok doesn't count as high quality post as usual. Starts off. Turn it on in Metra settings > App behavior. |
| `App lock` | Asks for your fingerprint, face or PIN before TikTok opens, and again when you come back after a time you pick, so nobody else can open it on your phone. Starts off. Turn it on in Metra settings > App behavior. |
| `Automatic video advance` | Moves on to the next video by itself when one ends, so you can watch hands-free. It can also turn on TikTok's auto scroll in search results. Starts off. Turn it on in Metra settings > App behavior. |
| `Block P2P video relay` | Removes the files TikTok uses to pass videos on to other viewers through your phone's internet connection. The app gets about 3.5 MB smaller. It's built in while patching, so only patching again without it brings them back. |
| `Block author button` | Adds buttons on videos to block the creator, hide them only on your phone, or block the sound, so you can get rid of what you don't want in one tap. Starts off. Turn it on in Metra settings > Interface. |
| `Block contact list access` | Gives TikTok an empty contact list when it tries to read your phone's contacts, so it can't use them to suggest friends. Starts off. Turn it on in Metra settings > App behavior. |
| `Block installed app scanning` | Gives TikTok an empty list when it asks which apps are on your phone, so it learns less about you. Opening another app from TikTok still works. Starts off. Turn it on in Metra settings > App behavior. |
| `Block popups` | Lets you stop TikTok's popups, like friend suggestions and offers. Each one joins a checklist after it first appears, and once ticked it won't come back. Sign-in, age and verification screens are never blocked. Starts off. Turn it on in Metra settings > Interface. |
| `Block suggested video notifications` | Stops TikTok's "Videos you might like" notifications about popular videos it picked for you. Messages, comments, likes and posts from people you follow still come through. On by default. Turn it off in Metra settings > Interface. |
| `Camera and microphone indicator` | Shows a small mark in the top corner while TikTok is using the camera or microphone, so you always know when it is. Starts off. Turn it on in Metra settings > App behavior. |
| `Change app name` | Shows a name you choose under the app icon, so the patched TikTok is easy to tell apart from another copy. Type the name in this patch's options. Inside the app it still says TikTok. |
| `Comment publish diagnostics` | Adds a note to Metra's diagnostic report about how far a comment got when it fails to post, which helps track down comments that vanish without an error. Works as soon as you patch it in, with no switch. |
| `Comment send fix` | Fixes comments that TikTok sometimes drops without any message, so the comment you send actually posts. Works as soon as you patch it in, with no switch. |
| `Comment sort controls` | Shows TikTok's full comment sort menu on every video, with hot, newest, media and creator options, even when your account only got a cut-down version. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Comment tools` | Hides comments with words you pick or from accounts you pick, and adds a search box for a video's comments. It can also make links tappable and show poll results before you vote. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Confirm feed interactions` | Asks for a second tap before Follow, a like, or a quick repost goes through, so a stray tap doesn't count. Starts off. Turn it on in Metra settings > Interface. |
| `Copy bio and IDs` | Lets you long-press a bio to copy it, and adds share sheet buttons that copy a username, user ID or video ID. On by default. Turn it off in Metra settings > App behavior. |
| `Copy comments without username` | When you copy a comment, you get just its text, without the username in front. On by default. Turn it off in Metra settings > Comments and translation. |
| `Custom launcher icon` | Gives TikTok's home screen icon a themed version that takes your wallpaper's color on Android 13 and up. The options can also make it black, plain or your own picture. Only patching again without it puts the old icon back. |
| `Custom offline videos limit` | Lets you pick how many videos TikTok saves for watching offline, from 1 to 10,000, and keep them until you delete them. Handy when you're often without internet. Starts off. Turn it on in Metra settings > Downloads. |
| `Device privacy guard` | Stops TikTok reading what you copied and gives it a blank advertising ID. Can also hide a VPN connection and root status with details about root tools, and give TikTok a new device identity. Each switch starts off in Metra settings > App behavior. Changing Hide root needs a restart. |
| `Diagnostic tools` | Adds logs and reports you can save or share when something goes wrong, so a problem is easier to report and fix. Starts off. Turn it on in Metra settings > Diagnostics. |
| `Disable login requirement` | Stops TikTok from forcing you to sign in before you can keep browsing. Works as soon as you patch it in, with no switch. |
| `Disable screen capture detection` | Stops TikTok from noticing and reacting when you take a screenshot or record the screen. Works as soon as you patch it in, with no switch. |
| `Disable telemetry` | Stops TikTok sending usage statistics and crash reports to TikTok and the tracking services it uses, so less of what you do in the app leaves your phone. Starts off. Turn it on in Metra settings > App behavior. |
| `Disable the long press quick share` | Stops a long press on Share from opening TikTok's quick share, so you don't send a video by accident. Starts off. Turn it on in Metra settings > Interface. |
| `Disable the long press repost` | Stops a long press on Like from opening TikTok's repost option, so you don't repost by accident. Starts off. Turn it on in Metra settings > Interface. |
| `Double-tap controls` | Lets a double tap on a video do nothing, open its comments, copy the link, save the sound or the frame on screen, speed the video up, or set a sleep timer instead of liking it, so you don't like videos by accident. Starts off. Pick an action in Metra settings > Interface. |
| `Downloads` | Saves videos and photos without TikTok's watermark, even when the creator turned downloads off. You can also choose the folders and file names. On by default. Turn it off in Metra settings > Downloads. |
| `Drop the animated image cache` | Lets you make TikTok decode animated stickers and GIFs one frame at a time as they play, instead of a few frames ahead, so they use a little less memory. Starts off. Turn it on in Metra settings > App behavior. |
| `Enable voice comments` | Lets you turn on TikTok's voice comments, so you can record and post a spoken comment, for accounts that don't have them yet. It hasn't been tried on a real account, so it may not work. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Expand activity list` | Shows the whole Activity and New followers lists in the Inbox, so you don't have to tap View all. Starts off. Turn it on in Metra settings > Interface. |
| `Feature Gate Lab` | Adds an advanced page that lists the hidden settings TikTok uses to test features and lets you change them. It's for people who like to experiment. Starts off. Turn it on in Metra settings > Diagnostics. |
| `Feature Gate Recorder` | Records which hidden TikTok settings the app reads while you use it, and which ones changed. It's a research tool and changes nothing in TikTok. Start a recording in Metra settings > Diagnostics. |
| `Feed filter` | Hides ads in the feed, and lets you hide other things you don't want to see, like LIVEs, Shop posts, AI videos or videos with words, creators or sounds you pick. Hiding ads is on by default. Everything else starts off in Metra settings > Feed controls. |
| `Feed tab navigation` | Lets you hide feed and bottom tabs you don't use, rename the bottom tabs, choose the tab TikTok opens on and stop For You reloading when you tap Home. Starts off, except holding Home to open Metra settings. Find it in Metra settings > Feed navigation. |
| `Feed text sizes` | Lets you make video descriptions, creator names and post dates bigger or smaller, so they're easier to read. Starts off. Pick a size in Metra settings > Interface. |
| `Fit the video to the screen` | Shows the whole video instead of cutting off its sides on folding phones, squarer screens and split screen. A second switch does the opposite and fills the screen. Starts off. Turn it on in Metra settings > App behavior. |
| `Fix Google login` | Makes Sign in with Google work in the patched app, where it would otherwise fail. Works as soon as you patch it in, with no switch. |
| `Foldable split comment view` | On a folding phone or tablet, shows comments beside the video instead of over it when the screen is wide enough. Starts off. Turn it on in Metra settings > App behavior. |
| `Follow diagnostics` | Tells you when TikTok quietly turned down a follow, and why, since the app shows it as if it worked. With diagnostic logging on, the details go in the report too. Works as soon as you patch it in, with no switch. |
| `Force show Auto scroll` | Makes TikTok's Auto scroll action available without starting it automatically. Control it in Metra settings > App behavior. |
| `Ghost mode` | Stops TikTok telling people you viewed their story or profile, or that you're typing. What TikTok already recorded stays. A separate switch hides your online status. Starts off. Turn it on in Metra settings > App behavior. |
| `Hide CAPTCHA popups` | Skips the puzzle TikTok shows while you're just browsing. A puzzle right after you like, follow, comment or repost still shows, and so do sign-in, SMS and two-step checks. Starts off. Turn it on in Metra settings > Interface, then restart TikTok. |
| `Hide Play Store update offer` | Stops the Play Store offering to swap the patched TikTok for the store version. The catch: a build with a lower version number won't install over it, and uninstalling to go back can erase TikTok's data on your phone. |
| `Hide already seen videos` | Remembers the videos you've watched and hides them when the feed sends them again, so you only see new ones. The list stays on your phone. Starts off. Turn it on in Metra settings > Feed controls. |
| `Hide comment popup ads` | Stops the brand animation that pops up over the comments when someone types a word or emoji an advertiser paid for. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Hide comment typing suggestions` | Hides the emoji and sticker suggestions that pop up above the comment box while you type. The emoji and sticker buttons still work. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Hide feed LIVE button` | Removes the LIVE button and the side menu button from the top left of the feed, for a cleaner screen. Each has its own switch, and both start off. Turn them on in Metra settings > Interface. |
| `Hide feed follow button` | Removes the + follow button under creators' pictures on the feed, so you don't follow someone by accident. Starts off. Turn it on in Metra settings > Interface. |
| `Hide feed save button` | Removes the save button from the right side of the feed, for a cleaner screen. Starts off. Turn it on in Metra settings > Interface. |
| `Hide feed search button` | Removes the search button from the top right of the feed, for a cleaner screen. Starts off. Turn it on in Metra settings > Interface. |
| `Hide floating promotions` | Hides the floating promotion badges, coins and timers on the feed, and can hide the rewards button on your profile. Starts off. Turn it on in Metra settings > Interface, and App behavior for the rewards button. |
| `Hide inbox items` | Lets you hide Inbox rows you don't use, like message requests, TikTok Shop and the stories row, plus call buttons and suggested replies in chats. Each has its own switch. Also offers search in loaded Activity notifications. Starts off. Turn it on in Metra settings > Interface. |
| `Hide inbox stories` | Hides the row of stories at the top of the Inbox. It uses the same switch as Hide inbox items. Starts off. Turn it on in Metra settings > Interface. |
| `Hide profile shortcuts` | Hides the shortcuts you pick from the row under a profile's bio, like TikTok Studio or Your orders, and can hide the Thoughts bubble. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok. |
| `Hide search suggestions` | Hides the searches TikTok suggests before you type, and can hide the search rewards banner. Your own search history stays. Starts off. Turn it on in Metra settings > App behavior. |
| `Hide suggested accounts` | Hides TikTok's People you may like suggestions in the Inbox, on profiles, on the Friends tab and in the feed. It uses the same switch as Hide inbox items. Starts on, retaining Metra's existing default. Control it in Metra settings > Interface. |
| `Hide the launcher shortcuts` | Empties the menu that pops up when you press and hold TikTok's icon on your home screen. Tapping the icon still opens the app. Starts off. Turn it on in Metra settings > App behavior. |
| `Hide the risk control CAPTCHA` | Lets Hide CAPTCHA popups also skip the puzzle TikTok's account safety check raises while you're just browsing. SMS and two-step checks always show. It sits on TikTok's account checks, which is why it isn't picked for you. |
| `Hide video overlays` | Lets you hide clutter over videos, like the caption, the music line, buttons on the right, surveys and location labels, so you see more of the video. It can also make the music disc spin again or keep it still. Each has its own switch. Starts off. Turn it on in Metra settings > Interface. |
| `Hold-and-slide 2x lock` | Turns on TikTok's own gesture where you hold a video, slide down and let go to keep it playing fast without holding. Starts off. Turn it on in Metra settings > Interface. |
| `In-app browser privacy guard` | Stops websites you open in TikTok's built-in browser from reaching into the TikTok app. TikTok's own pages, like Watch history and checkout, keep working. Starts off. Turn it on in Metra settings > App behavior. |
| `Keep a streak going` | Sends a daily message you choose to people you pick, at a time you set, so your message streaks keep going on days you don't open TikTok. Starts off. Turn it on in Metra settings > Interface. |
| `Keep playing in the background` | Keeps the video playing when you leave TikTok or turn the screen off, with a notification to pause it. It can also start the video over when it ends. Handy for music and talks. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok. |
| `Keep pulled sounds` | Plays the sound on videos TikTok muted for copyright or because of where you live. TikTok may still say the sound isn't available. Starts off. Turn it on in Metra settings > App behavior. |
| `Keep the Favorites tab` | Keeps the Favorites tab and your saved videos on your profile when TikTok tries out a version of the app that hides them, and makes the collections popup you get by holding the Favorites button dark when TikTok is. Both are on by default. Turn them off in Metra settings > App behavior. |
| `Keep the app language` | Lets you keep the language you picked in TikTok's own settings. When TikTok starts and decides the phone's language changed, it drops that pick and follows the phone, which can leave it in the wrong language after a reboot. Starts off. Turn it on in Metra settings > App behavior. |
| `Keep the screen's refresh rate` | Stops TikTok slowing your screen down to the video's frame rate, so scrolling stays smooth on 90 or 120 Hz phones. Starts off. Turn it on in Metra settings > App behavior. |
| `LIVE controls` | Stops a LIVE in the feed from opening by itself after a countdown. It can show a LIVE's exact viewer count instead of a rounded one, offer a quality choice and clear screen in more LIVE rooms, and keep your quality pick after TikTok restarts. Starts off. Turn it on in Metra settings > App behavior. It also keeps a LIVE in the feed from showing the Service+ row of the LIVE before it, and can add a Record button to a LIVE room that saves what you watch from when you tap it. |
| `Lift text length limits` | Lets you write longer comments, repost notes and bios than the app normally allows. TikTok can still refuse one that's too long. Starts off. Turn it on in Metra settings > Comments and translation. |
| `Limit background traffic` | Lets you stop TikTok loading upcoming videos ahead of time, which uses less data in the background, but videos may take a moment longer to start. Starts off. Turn it on in Metra settings > App behavior. |
| `Location access governor` | Gives TikTok no location when it asks, so it can't see where you are from your phone's location services. Starts off. Turn it on in Metra settings > App behavior. |
| `Long-press controls` | Lets a long press on a video like it, open the comments, copy the link, save the sound or the frame on screen, or set a sleep timer, and can give a creator's videos For You's full long-press menu. Starts off. Pick an action in Metra settings > Interface. |
| `Look like the store app` | Makes the patched app answer TikTok's checks as if it came from the Play Store, which can help when follows or likes undo themselves. TikTok has other checks, so it may not be enough. On once picked. Turn it off in Metra settings > App behavior. |
| `Mute feed videos` | Mutes feed videos without changing your phone's volume, so music from another app keeps playing. You can also add a mute button on videos. Starts off. Turn it on in Metra settings > App behavior. |
| `Network proxy` | Sends TikTok's feed, search and comments through a proxy server you set up. Videos and LIVEs still load directly, and so do Metra's own saves and short-link lookups. Other apps aren't affected. Starts off. Turn it on in Metra settings > Region spoof, then restart TikTok. |
| `Network request report` | Counts the requests TikTok sends to its servers and adds the totals to the diagnostic report. It changes nothing in TikTok, but it has no switch and most people don't need it. |
| `Not interested button` | Adds a button on videos that tells TikTok you're not interested in one tap, so it can show you fewer like it. Starts off. Turn it on in Metra settings > Interface. |
| `Notification controls` | Lets you turn off new follower notifications and streak reminders, which TikTok has no switch for, or turn off TikTok's notifications altogether. Starts off. Turn it on in Metra settings > Interface. |
| `Open external links directly` | Opens website links from profiles and stories in your phone's own browser instead of TikTok's built-in one. On by default. Turn it off in Metra settings > App behavior. |
| `Picture-in-picture` | Keeps the video playing in a small window when you leave TikTok, so you can keep watching while you use other apps. Starts off. Turn it on in Metra settings > App behavior. |
| `Play SDR instead of HDR` | Plays the normal version of HDR videos, so your screen doesn't suddenly jump to full brightness. Starts off. Turn it on in Metra settings > App behavior. |
| `Playback quality` | Lets you choose the video quality, like highest, lowest or 720p, and set a lower quality for mobile data to use less data. Starts off. Pick a quality in Metra settings > App behavior. |
| `Playback speed` | Remembers the speed you picked for the next video, and lets you set a default speed, add speeds up to 3x to the menu and choose the speed for press and hold. On by default. Turn it off in Metra settings > App behavior. |
| `Prefer H.264 playback` | Plays videos in an older format your phone decodes more easily, which can help older phones play smoothly and run cooler. Videos without it play as before. Starts off. Turn it on in Metra settings > App behavior. |
| `Profile background` | Opens TikTok's own profile background editor in Edit profile on accounts TikTok hasn't offered it to yet, and shows the backgrounds other profiles have set. TikTok's servers may still refuse to save one for such an account. Starts off. Turn it on in Metra settings > App behavior. |
| `Region spoof` | Makes TikTok's region and time zone match the country you set for SIM spoof. It changes every region check, sign-in included, and its store region switch is experimental, so it isn't picked for you. Starts off. Turn it on in Metra settings > Region spoof. |
| `Remember clear display` | Keeps clear display, TikTok's mode that hides the buttons over a video, on as you swipe to the next video. It can also turn on by itself after a delay you pick, which starts off in Metra settings > Interface. When clear display ends, the Following stories bubble TikTok hid comes back too. |
| `Remove LIVE extras` | Removes the files for LIVE co-hosting, joining a LIVE as a guest, matches, games and gift effects, so the app gets about 3 MB smaller. The catch: in LIVEs, battle scores and guest names can go missing, and joining someone's LIVE by voice or video, co-hosting, games or animated gifts may stop working. |
| `Remove avatar rings` | Takes the story and LIVE rings off profile pictures, so tapping a picture opens the profile instead of a story or LIVE. Starts off. Turn it on in Metra settings > Interface. |
| `Remove content credential and card scanner assets` | Removes TikTok's payment card scanner and content credential files, saving about 11 MB of storage. Anything in TikTok that needs them may stop working. Only patching again without it brings them back. |
| `Remove creation tools` | Removes TikTok's camera, editing and effects files, saving about 38 MB of storage. The catch: the Create tab and every recording, editing and effects tool stop working, and opening one, like effect search, can close TikTok. Leave it out if you make videos. |
| `Remove unused language packs` | Removes the app languages you don't list in this patch's options, saving up to about 26 MB of storage. English is always kept. With no list it keeps them all. Only patching again brings removed ones back. |
| `Repost diagnostics` | With diagnostic logging on, notes when you repost a video and what TikTok answered, to help track down reposts that don't stick. Works as soon as you patch it in, with no switch. |
| `Resource and battery governor` | Stops TikTok reading your phone's motion sensors, which it can use to identify your phone, and stops a background speed test that can use a lot of memory. Starts off. Turn it on in Metra settings > App behavior. |
| `Resume videos after scrolling` | When you scroll back to a video, it picks up where you left off instead of starting over. On by default. Turn it off in Metra settings > App behavior. |
| `Run beside the store app` | Lets a renamed copy of TikTok, made with Morphe's Clone app patch, sign in while the regular TikTok stays installed. Pick it together with Clone app. Google and Facebook sign-in won't work in the copy, so use email or phone. |
| `SIM spoof` | Makes TikTok see the SIM country and carrier you choose. It may not change your region, since TikTok also goes by your internet connection and account. Starts off. Turn it on in Metra settings > Region spoof. |
| `Sanitize sharing links` | Removes the tracking codes from TikTok links you share, so the link carries less about you. It can also swap tiktok.com for another site. On by default. Turn it off in Metra settings > App behavior. |
| `Settings` | Adds the Metra settings page to TikTok, at the top of Settings and privacy. Pressing and holding For You at the top of the feed opens it too. That's where you turn Metra's features on and off. Works as soon as you patch it in, with no switch. |
| `Share sheet tools` | Lets you hide people, apps and buttons you don't use from the share sheet, add apps you do use, and copy a video's link with one press on Share. Starts off. Turn it on in Metra settings > Share sheet. |
| `Show LIVE search` | Adds TikTok's search button inside the LIVE section where TikTok supports it, so you can look for LIVEs. Starts off. Turn it on in Metra settings > App behavior. |
| `Show author region` | Shows the country a video was posted from next to the creator's name. Starts off. Turn it on in Metra settings > Interface. |
| `Show commenter handle and region` | Shows a commenter's @username in place of their display name and the country their account is set to after the comment's time. It can also give each commenter's name a color of its own. Each starts off. Turn them on in Metra settings > Comments and translation. |
| `Show engagement rate` | Shows how many people liked, commented on, shared or saved a video compared with how many watched it, next to the creator's name. Starts off. Turn it on in Metra settings > Interface. |
| `Show exact counts` | Shows likes, comments and other counts as full numbers, like 1,234,567 instead of 1.2M. Starts off. Turn it on in Metra settings > Interface. |
| `Show follow status` | Shows on a profile whether that person follows you back, and marks accounts in your follow lists that don't. On by default. Turn it off in Metra settings > App behavior. Optional local counts and appearance controls apply to your own profile. |
| `Show the progress bar` | Shows the progress bar on videos where TikTok hides it, so you can see how long a video is and skip around. On by default. Turn it off in Metra settings > App behavior, where you can also show the time beside the bar or give it a bigger handle. A seek bar for video stories is there too. |
| `Show the progress bar thumbnail` | Shows a small preview picture while you drag the progress bar, so you can find the part you want. On by default. Turn it off in Metra settings > App behavior. |
| `Skip content warnings` | Plays videos without the warning screen you'd have to tap through first, and can hide the Check sources and Check your sources banners. Starts off. Turn it on in Metra settings > Interface. |
| `Skip first-launch setup` | Skips TikTok's setup screens on a fresh install, like the interest picker and the swipe tutorial. Sign-in and age screens still show. Its switch is on once picked, since setup runs before you can reach settings. Turn it off in Metra settings > App behavior. |
| `Skip passkey sign-in` | Lets you sign in another way, like your password or a code by email or text, when TikTok wants a passkey. Password managers won't hand a passkey to a patched app, so that step can't finish here. With the switch on, TikTok treats your phone as one without passkeys. Starts off. Turn it on in Metra settings > App behavior. |
| `Skip the splash ad` | Lets you stop the full-screen ad TikTok can show while it starts up. Starts off. Turn it on in Metra settings > App behavior. |
| `Skip update checks` | Lets you stop two background tasks TikTok uses to check for updates, one of them when your phone starts. Some in-app update prompts may stop. Play Store updates still work. Starts off. Turn it on in Metra settings > App behavior. |
| `Stay on the video in full screen` | When a video ends in TikTok's full screen view, it stays on that video instead of moving to the next one. Swiping still moves on. Starts off. Turn it on in Metra settings > App behavior. |
| `Stop on-device AI profiling` | Lets you stop TikTok's built-in AI engine from starting, so it doesn't get a copy of everything TikTok logs about your use. TikTok works as if the engine weren't there. Starts off. Turn it on in Metra settings > App behavior. |
| `Stop recording watch history` | Stops TikTok adding the videos you watch to your Watch history. The catch: your views stop counting, and For You learns less about what you like. Starts off. Turn it on in Metra settings > App behavior. |
| `Stop saving search history` | Stops TikTok saving your new searches on your phone. Older searches stay until you delete them, and TikTok's servers may keep their own record. Starts off. Turn it on in Metra settings > App behavior. |
| `Stop search autoplay` | Stops videos in search results from playing by themselves. Each one shows its cover until you open it, so searching stays quiet. Starts off. Turn it on in Metra settings > App behavior. |
| `Stop video looping` | Stops a video at its end instead of playing it again. Starts off. Turn it on in Metra settings > App behavior. |
| `Story controls` | Lets a story replay when it ends instead of moving on, and keeps a photo story on screen until you tap or swipe. Starts off. Turn it on in Metra settings > App behavior. |
| `Subtitle tools` | Lets you make captions bigger, change their background and keep them in clear display, and save subtitle files with downloaded videos. Starts off. Turn it on in Metra settings > Interface, and Downloads for subtitle files. |
| `Swipe-left controls` | Lets a left swipe on a video open its comments or do nothing, instead of opening the creator's profile. Starts off. Pick an action in Metra settings > Interface. |
| `Translate comments` | Translates comments as they load, using TikTok's own translator, so you don't have to tap Translate on each one. Starts off. Turn it on in Metra settings > Comments and translation, where more rows list languages TikTok's automatic translation should leave alone, pick the language translations come out in, and add TikTok's translate button beside the comment count. |
| `Trust user certificates` | Lets TikTok trust security certificates you install yourself, so a tool like mitmproxy can show what the app sends. The risk: anyone who gets a certificate onto your phone can read TikTok's traffic too. Use it only on a test phone. |
| `Turn off haptics` | Stops the little vibrations TikTok makes when you tap or hold things. Your keyboard and your phone's own vibrations stay. Starts off. Turn it on in Metra settings > App behavior. |
| `Turn off screen transitions` | Opens and closes TikTok's screens without the sliding animation, so moving around feels quicker. Swipes still follow your finger. Starts off. Turn it on in Metra settings > App behavior. |
| `Use non-personalized search` | Shows search results that aren't tailored to your account. Starts off. Turn it on in Metra settings > App behavior. |
| `Use system font` | Shows TikTok's text in your phone's own font or in a .ttf or .otf font file you import, instead of TikTok's, and can use your phone's emoji too. Starts off. Turn it on in Metra settings > App behavior, then restart TikTok. |

<br>

## Regional Restrictions

Region spoofing changes the country, SIM, carrier, and related region details that TikTok reads from the app, so it can help with client-side region checks and onboarding. It cannot hide the public IP address that TikTok's servers see from the connection, so it cannot replace a VPN or proxy in a country where TikTok blocks service by IP. The regional popup can be hidden, but that only removes the warning and does not turn a rejected feed request into a successful one. TikTok may also keep a blocked network state for a while after a VPN is disconnected, in which case force-stopping TikTok and clearing its cache can be required before the feed recovers.

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
- Version: [`47.4.3`](https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/tiktok-47-4-3-release/) (dev prerelease)
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

- [Hushfeed v0.76.0](https://github.com/SysAdminDoc/HushFeed/tree/v0.76.0) provides the expanded feature set and test suite, adapted under GPL-3.0. Its source notices and third-party notices are retained.

- Thanks to [@lyyako](https://github.com/lyyako) for the original contributions behind the simplified sanitize sharing links hook, show seekbar patch, anti-recording patch, `Open external links directly`, and `Always show publish date`.
- Thanks to [@oscski](https://github.com/oscski) for the original contribution behind `Disable long-press repost`.
- Thanks to [@HazyArc14](https://github.com/HazyArc14) for the original contributions behind `Share sheet modification` and `Foldable split comment view`.
- Thanks to [@tymmesyde](https://github.com/tymmesyde) for the original contribution behind `Hide suggested accounts`.

## Notes

- The source is based on [RookieEnough/De-Vanced](https://github.com/RookieEnough/De-Vanced) and the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template).
- It is not affiliated with TikTok, ByteDance, or Morphe.
- TikTok changes often, so compatibility is intentionally tied to the exact version and packages listed above.

<br>

## License

This project reuses the GPLv3 licensing from the projects it was built on.

See [LICENSE](LICENSE) and [NOTICE](NOTICE).
