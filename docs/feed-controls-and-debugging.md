# Feed controls and debugging

## Patch selection and toggles

Select `Feed filter` to add controls for ads, LIVE, Shop, stories, photo posts, and view/like-count ranges. Select `Hide AI content` and `Hide FYP unpersonalized slop videos` independently if you want either of those controls. All three use shared feed hooks, but neither standalone patch requires selecting `Feed filter`. The relevant in-app toggle or count range must also be set for a filter to act.

With fresh settings, Remove feed ads is on when `Feed filter` is installed. Hide livestreams, Hide AI content, and Hide FYP unpersonalized slop videos are off. View and like ranges initially allow the full range. Existing saved settings may differ, so report the actual values on the affected device.

`Hide AI content` checks TikTok's AI label and moderation fields on feed items. It can remove posts carrying the supported AI-generated or AI-modified signals. It cannot identify AI content that TikTok has not marked in those fields.

`Hide FYP unpersonalized slop videos` targets the observed For You batch source `for_you_page_999` when an item has no recommendation-reasons structure. This condition is narrower than all posts without a visible “Why this post” reason, and it does not classify content by language or quality. It can include photo posts.

## Capture a feed issue

Select `Diagnostic tools` when patching the APK. In TikTok, open Morphe settings > Diagnostics > Feed debugger, choose Start new, then choose Shallow or Nested capture. Start before reproducing the issue. Once it happens, choose Stop and save. The file is saved in `Downloads/Morphe` as `morphe-feed-probe-<session>.txt` on supported Android versions.

The feed debugger and the ordinary diagnostic report are separate exports. The feed debugger records sampled item/list observations, filter decisions, and some recommendation-reason paths. The ordinary report contains categorized Morphe events and errors if Enable diagnostic logging was turned on before reproduction. For a feed bug, save the feed debugger file and export a diagnostic report including Feed and navigation and Errors. Include the approximate time, route (For You, Following, profile, search), and whether the item was already on screen when capture started.

The feed debugger retains roughly 4 MiB of recent encoded events, not a fixed number of minutes. Long captures evict older events. Frequently called getter hooks are sampled, and the export records skipped or partial captures. A missing item or decision is therefore not proof that a hook did not run. A captured item is not by itself proof it was displayed. Save soon after reproduction and include the original file, since its header and gap counters explain what was actually observed.

For a report, include the TikTok package/version and Morphe bundle version, selected patches, the relevant toggle values and count ranges, exact reproduction steps, and the result of disabling the suspected patch or toggle if you tried that comparison. Review exported files for personal information before uploading them to a public issue.
