"""Run each release_text command on a temporary tree and read back what it wrote."""

import argparse
import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path

# py -I leaves the script's own folder off the path.
sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_text  # pyright: ignore[reportMissingImports]

CHANGELOG = """# Changelog

## Unreleased

### Features and fixes

* **TikTok:** Hide footnotes is new (#5).

* **TikTok:** About 6.7 MB smaller.

## 0.1.0 (2026-01-01)

### Features and fixes

* **TikTok:** First.
"""
README = """[![v](https://img.shields.io/badge/version-0.1.0-blue)](x)

Hushfeed v0.1.0 contains 1 patches for TikTok 1.0.0. New in this one: things. It needs Morphe Manager 1.34.0 or newer.

Use Morphe Manager 1.34.0 or newer.

The main branch contains 2 patches, one more than v0.1.0.

Manager shows all 1 Hushfeed patches in ten categories.
"""
GUIDE = """# Map

Its version and patch counts were last synced with the source on 2026-01-01, and a check fails when they drift.

Source snapshot: version 0.1.0, 1 patch entries. Those come from gradle.properties and patches-list.json.

Published bundle: version 0.1.0, 1 patches. Those come from patches-bundle.json and the release it names.

The current catalog's 4 patches are grouped as follows:

| Manager category | Patches |
| --- | ---: |
| Feed | 1 |
| Gone | 3 |

The [patch catalog](../patches-list.json) has the complete names.
"""
OLD_APK = "https://www.apkmirror.com/apk/t/tiktok-1-0-0-release/"
NEW_APK = "https://www.apkmirror.com/apk/t/tiktok-1-1-0-release/"
INSTALL = f"""
<a href="m"><img alt="TikTok 1.0.0" src="https://img.shields.io/badge/TikTok-1.0.0-ff0050.svg" /></a>

> Hushfeed targets the global TikTok package, `com.zhiliaoapp.musically`, version [1.0.0]({OLD_APK}). Use that APK.

1. Get the TikTok 1.0.0 APK from APKMirror: [1.0.0]({OLD_APK}).

- Version: [1.0.0]({OLD_APK}), on APKMirror since 2026-01-01.
- Build: version code 100, arm64-v8a, nodpi, minSdk 23
- SHA-256 of the APK every patch was verified against: `{"a" * 64}` (1.0.0)
- Next release: TikTok 1.1.0, [on APKMirror]({NEW_APK}) since 2026-02-02 (version code 110, arm64-v8a, nodpi, minSdk 24), SHA-256 `{"b" * 64}`. Use the build above until then.
- Also PineDrama 1.0.0, which stays as it is.

Google Play only ever serves the newest build, so yours may not be 1.0.0. Take [1.0.0]({OLD_APK}) from APKMirror.

APKMirror also offers some TikTok releases as bundles, so patch the plain 1.0.0 APK. Not 21.0.0 or 1.0.01.
"""
FOLDED = f"""
<a href="m"><img alt="TikTok 1.1.0" src="https://img.shields.io/badge/TikTok-1.1.0-ff0050.svg" /></a>

> Hushfeed targets the global TikTok package, `com.zhiliaoapp.musically`, version [1.1.0]({NEW_APK}). Use that APK.

1. Get the TikTok 1.1.0 APK from APKMirror: [1.1.0]({NEW_APK}).

- Version: [1.1.0]({NEW_APK}), on APKMirror since 2026-02-02.
- Build: version code 110, arm64-v8a, nodpi, minSdk 24
- SHA-256 of the APK every patch was verified against: `{"b" * 64}` (1.1.0)
- Also PineDrama 1.0.0, which stays as it is.

Google Play only ever serves the newest build, so yours may not be 1.1.0. Take [1.1.0]({NEW_APK}) from APKMirror.

APKMirror also offers some TikTok releases as bundles, so patch the plain 1.1.0 APK. Not 21.0.0 or 1.0.01.
"""


def catalog(*versions: str) -> str:
    targets = [{"version": v} for v in versions] + [{"version": "9.9.9", "experimental": True}]
    patch = {"name": "A", "category": "Feed",
             "compatibility": [{"packageName": "com.zhiliaoapp.musically", "targets": targets}]}
    return json.dumps({"version": "v0.2.0", "patches": [patch, dict(patch, name="B", category="Privacy")]})


class ReleaseTextTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory()
        self.root = Path(self.folder.name)
        (self.root / "extensions/tiktok/src/main/l10n/notes").mkdir(parents=True)
        (self.root / ".github/ISSUE_TEMPLATE").mkdir(parents=True)
        (self.root / "concepts").mkdir()
        self.write("concepts/patch-development.md", GUIDE)
        self.write("gradle.properties", "org.gradle.caching = true\nversion = 0.2.0\n")
        self.write("CHANGELOG.md", CHANGELOG)
        self.write("extensions/tiktok/src/main/l10n/notes/en.txt",
                   CHANGELOG[CHANGELOG.index("## Unreleased"):].replace("Hide footnotes is new", "Hide footnotes")
                   .replace("About 6.7 MB smaller", "Saves 6.7 MB"))
        self.write("README.md", README)
        self.write("patches-list.json", catalog("1.0.0", "1.1.0"))
        self.write("patches-bundle.json", json.dumps({"created_at": "", "description": "", "download_url": "",
                                                      "signature_download_url": "", "version": "0.1.0"}))
        self.write(".github/ISSUE_TEMPLATE/bug_report.yml", "      placeholder: Version 0.1.0 for TikTok 1.1.0\n")
        self.saved = (release_text.ROOT, release_text.CHANGELOG, release_text.NOTES)
        release_text.ROOT = self.root
        release_text.CHANGELOG = self.root / "CHANGELOG.md"
        release_text.NOTES = self.root / "extensions/tiktok/src/main/l10n/notes"

    def tearDown(self):
        release_text.ROOT, release_text.CHANGELOG, release_text.NOTES = self.saved
        self.folder.cleanup()

    def write(self, name: str, text: str) -> None:
        (self.root / name).write_text(text, encoding="utf-8", newline="\n")

    def read(self, name: str) -> str:
        return (self.root / name).read_text(encoding="utf-8")

    def run_command(self, command, **values):
        with contextlib.redirect_stdout(io.StringIO()):
            command(argparse.Namespace(**values))

    def cut_grouped_release(self) -> None:
        self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")

    def translate_all(self, bullet_one: str = "* **TikTok:** Hinweise (#5).", bullet_two: str = "* **TikTok:** 6,7 MB.") -> None:
        for lang in release_text.LANGUAGES:
            self.write(f"extensions/tiktok/src/main/l10n/notes/{lang}.txt",
                       f"## Unreleased\n\n{bullet_one}\n\n{bullet_two}\n\n## 0.1.0 (2026-01-01)\n\n* **TikTok:** x\n")

    def test_cut_dates_the_unreleased_bullets_and_renames_every_translated_table(self):
        self.translate_all()
        self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
        changelog = self.read("CHANGELOG.md")
        self.assertIn("## Unreleased\n\n## 0.2.0 (2026-10-09)\n\n### Features and fixes\n\n"
                      "* **TikTok:** Hide footnotes", changelog)
        self.assertEqual(changelog.count("## 0.1.0 (2026-01-01)"), 1)
        for lang in ("en",) + release_text.LANGUAGES:
            self.assertTrue(self.read(f"extensions/tiktok/src/main/l10n/notes/{lang}.txt").startswith("## 0.2.0 (2026-10-09)\n"))

    def test_cut_requires_complete_curated_english_before_writing_any_file(self):
        for text in ("## Unreleased\n", "## Unreleased\n\n* **TikTok:** Wrong (#9).\n",
                     "## Unreleased\n\n* **TikTok:** Wrong (#9).\n\n* **TikTok:** Size.\n"):
            self.write("extensions/tiktok/src/main/l10n/notes/en.txt", text)
            with self.subTest(text=text), self.assertRaises(SystemExit):
                self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
            self.assertEqual(CHANGELOG, self.read("CHANGELOG.md"))
            self.assertEqual(text, self.read("extensions/tiktok/src/main/l10n/notes/en.txt"))

    def test_cut_rejects_inexact_or_duplicate_release_headings_before_writing_any_file(self):
        self.translate_all()
        paths = [self.root / "CHANGELOG.md", release_text.NOTES / "en.txt"]
        paths.extend(release_text.NOTES / f"{lang}.txt" for lang in release_text.LANGUAGES)
        for path in paths:
            original = path.read_text(encoding="utf-8")
            for bad in (original.replace("## Unreleased\n", "## Unreleased (draft)\n", 1),
                        original.replace("## Unreleased\n", "## Unreleased\n\n## Unreleased\n", 1),
                        original.replace("## 0.1.0 (2026-01-01)", "## 0.1.0 draft", 1),
                        original + "\n## 0.1.0 (2026-01-02)\n\n* **TikTok:** Duplicate.\n"):
                path.write_text(bad, encoding="utf-8", newline="\n")
                before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
                with self.subTest(path=path.name, heading=bad.splitlines()[0]), self.assertRaises(SystemExit):
                    self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
                self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})
            path.write_text(original, encoding="utf-8", newline="\n")

    def test_published_summary_check_allows_legacy_changelog_introductions(self):
        self.write("CHANGELOG.md", CHANGELOG.replace("* **TikTok:** First.", "An older release introduction.\n\n* **TikTok:** First."))
        self.run_command(release_text.check_translations, version="0.1.0", unreleased=False)

    def test_cut_preserves_inherited_linked_prerelease_history(self):
        history = "\n## [0.0.1-dev.2](https://example.com/compare/v0.0.1-dev.1...v0.0.1-dev.2) (2025-12-01)\n\n* Old.\n"
        self.write("CHANGELOG.md", CHANGELOG + history)
        self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
        self.assertTrue(self.read("CHANGELOG.md").endswith(history))

    def test_cut_rejects_an_older_version_before_writing_any_file(self):
        self.translate_all()
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit):
            self.run_command(release_text.cut, version="0.0.9", date="2026-10-09")
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_cut_refuses_unscoped_bullets_and_partial_translations_without_writing(self):
        self.write("CHANGELOG.md", CHANGELOG.replace("* **TikTok:** About", "* **Docs:** About"))
        with self.assertRaises(SystemExit):
            self.run_command(release_text.cut, version="0.2.0", date=None)
        self.write("CHANGELOG.md", CHANGELOG)
        self.write("extensions/tiktok/src/main/l10n/notes/de.txt", "## Unreleased\n\n* **TikTok:** a\n\n* **TikTok:** b\n")
        with self.assertRaises(SystemExit):
            self.run_command(release_text.cut, version="0.2.0", date=None)
        # Every table translated, but one lost a bullet since: the cut checks them against the English.
        self.translate_all()
        self.write("extensions/tiktok/src/main/l10n/notes/tr.txt", "## Unreleased\n\n* **TikTok:** Notlar (#5).\n")
        with self.assertRaises(SystemExit) as caught:
            self.run_command(release_text.cut, version="0.2.0", date=None)
        self.assertIn("tr: 1 bullets", str(caught.exception))
        self.assertEqual(self.read("CHANGELOG.md"), CHANGELOG)
        self.assertTrue(self.read("extensions/tiktok/src/main/l10n/notes/de.txt").startswith("## Unreleased\n"))

    def test_check_translations_passes_consistent_tables_and_names_each_slip(self):
        self.translate_all()
        self.run_command(release_text.check_translations, version=None, unreleased=True)
        for bad, why in ((("* **TikTok:** Hinweise.", "* **TikTok:** 6,7 MB."), "issue references"),
                         (("* **TikTok:** Hinweise (#5).", "* **TikTok:** 6.7 MB."), "English number"),
                         (("* **TikTok:** Hinweise (#5) — x.", "* **TikTok:** 6,7 MB."), "dash")):
            self.translate_all(*bad)
            with self.assertRaises(SystemExit) as caught:
                self.run_command(release_text.check_translations, version=None, unreleased=True)
            self.assertIn(why, str(caught.exception))

    def test_github_notes_carry_every_bullet_without_the_scope_tag(self):
        self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
        self.write("intro.md", "Intro line.\n")
        self.write("checked.md", "* Checked on a phone.\n")
        out = self.root / "notes.md"
        self.run_command(release_text.github_notes, version="0.2.0", intro=str(self.root / "intro.md"),
                         checked=str(self.root / "checked.md"), out=str(out))
        notes = out.read_text(encoding="utf-8")
        self.assertIn("## What's changed\n\n* Hide footnotes is new (#5).\n* About 6.7 MB smaller.\n", notes)
        self.assertNotIn("* Hide footnotes (#5).", notes)
        self.assertIn("* **TikTok:** Hide footnotes (#5).", self.read("extensions/tiktok/src/main/l10n/notes/en.txt"))
        self.assertNotIn("**TikTok:**", notes)
        self.assertIn("Morphe Manager 1.34.0 or newer", notes)
        self.assertTrue(notes.startswith("Intro line.\n\n") and notes.endswith("* Checked on a phone.\n"))

    def test_index_points_the_bundle_readme_and_bug_form_at_the_release(self):
        self.cut_grouped_release()
        for name, text in (("readme.txt", "a fade.\n"), ("bundle.txt", "Fade is new.\n")):
            self.write(name, text)
        self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                         readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                         device_line="Checked on a phone.", runtime=10, patch=4)
        bundle = json.loads(self.read("patches-bundle.json"))
        self.assertEqual(bundle["version"], "0.2.0")
        self.assertEqual(bundle["created_at"], "2026-10-09T17:03:42")
        self.assertTrue(bundle["download_url"].endswith("/v0.2.0/patches-0.2.0.mpp"))
        self.assertIn("2 patches for global TikTok 1.0.0 and 1.1.0", bundle["description"])
        self.assertIn("10 runtime tests passed and 4 patch tests passed", bundle["description"])
        self.assertIn("applied to all two supported APKs", bundle["description"])
        self.assertNotIn("9.9.9", bundle["description"])
        readme = self.read("README.md")
        self.assertIn("Hushfeed v0.2.0 contains 2 patches for TikTok 1.0.0 and 1.1.0. New in this one: a fade.", readme)
        self.assertIn("The main branch contains 2 patches, the same set as v0.2.0.", readme)
        self.assertIn("shows all 2 Hushfeed patches", readme)
        self.assertIn("placeholder: Version 0.2.0 for TikTok", self.read(".github/ISSUE_TEMPLATE/bug_report.yml"))
        guide = self.read("concepts/patch-development.md")
        self.assertIn("\nPublished bundle: version 0.2.0, 2 patches. Those come from", guide)
        # The source line belongs to prepare, which runs before the bundle exists.
        self.assertIn("\nSource snapshot: version 0.1.0, 1 patch entries.", guide)

    def test_index_carries_the_current_releases_grouped_changelog(self):
        self.write("CHANGELOG.md", CHANGELOG.replace(
            "* **TikTok:** About 6.7 MB smaller.",
            "### Size\n\n* **TikTok:** About 6.7 MB smaller.", 1))
        self.cut_grouped_release()
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                         readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                         device_line="", runtime=10, patch=4)
        description = json.loads(self.read("patches-bundle.json"))["description"]
        facts, groups = description.split("\n\n### ", 1)
        self.assertNotIn("Hide footnotes", facts)
        self.assertEqual(groups, "Features and fixes\n\n* **TikTok:** Hide footnotes is new (#5).\n"
                         "\n### Size\n\n* **TikTok:** About 6.7 MB smaller.")

    def test_grouped_changelog_refuses_loose_empty_and_duplicate_groups(self):
        cases = {
            "loose": "* **TikTok:** Loose.",
            "empty": "### Empty\n\n### Fixes\n\n* **TikTok:** Fixed.",
            "duplicate": ("### Fixes\n\n* **TikTok:** One.\n\n"
                          "### Fixes\n\n* **TikTok:** Two."),
        }
        for label, body in cases.items():
            with self.subTest(label=label), self.assertRaises(SystemExit):
                release_text.grouped_changelog(body, label)

    def test_cut_refuses_unreleased_without_changelog_groups(self):
        self.write("CHANGELOG.md", CHANGELOG.replace("### Features and fixes\n\n", "", 1))
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit) as caught:
            self.run_command(release_text.cut, version="0.2.0", date="2026-10-09")
        self.assertIn("no ### groups", str(caught.exception))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_index_refuses_malformed_cut_groups_without_writing(self):
        self.cut_grouped_release()
        self.write("CHANGELOG.md", self.read("CHANGELOG.md").replace(
            "### Features and fixes", "### Empty\n\n### Features and fixes", 1))
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit) as caught:
            self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                             readme_summary=str(self.root / "readme.txt"),
                             bundle_summary=str(self.root / "bundle.txt"), device_line="", runtime=10, patch=4)
        self.assertIn("empty ### Empty group", str(caught.exception))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_index_writes_no_file_when_any_line_it_owns_is_missing(self):
        self.cut_grouped_release()
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        self.write("concepts/patch-development.md", GUIDE.replace("Published bundle:", "Published:"))
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit) as caught:
            self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                             readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                             device_line="", runtime=1, patch=1)
        self.assertIn("patch-development.md", str(caught.exception))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_guide_holds_the_source_line_to_gradle_properties_and_the_catalog(self):
        self.run_command(release_text.guide, date="2026-10-09")
        guide = self.read("concepts/patch-development.md")
        self.assertIn("\nSource snapshot: version 0.2.0, 2 patch entries. Those come from", guide)
        self.assertIn("last synced with the source on 2026-10-09, and", guide)
        # The published line waits for the index, after the bundle is out.
        self.assertIn("\nPublished bundle: version 0.1.0, 1 patches.", guide)
        # validate-release-facts.ps1 holds the category table to the catalog too.
        self.assertIn("catalog's 2 patches are grouped as follows:\n\n| Manager category | Patches |\n"
                      "| --- | ---: |\n| Feed | 1 |\n| Privacy | 1 |\n\nThe [patch catalog]", guide)

    def test_guide_changes_nothing_without_a_source_version_or_its_line(self):
        for name, text in (("gradle.properties", "version = 0.2\n"),
                           ("concepts/patch-development.md", GUIDE.replace("Source snapshot:", "Snapshot:")),
                           ("concepts/patch-development.md", GUIDE.replace("last synced with", "synced with")),
                           ("concepts/patch-development.md", GUIDE.replace("| Manager category", "| Category"))):
            self.write("gradle.properties", "version = 0.2.0\n")
            self.write("concepts/patch-development.md", GUIDE)
            self.write(name, text)
            before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
            with self.subTest(name=name, text=text[:30]), self.assertRaises(SystemExit):
                self.run_command(release_text.guide, date="2026-10-09")
            self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def test_index_names_one_target_as_the_supported_apk(self):
        self.cut_grouped_release()
        self.write("patches-list.json", catalog("1.1.0"))
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                         readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                         device_line="", runtime=1, patch=1)
        self.assertIn("applied to the supported APK", json.loads(self.read("patches-bundle.json"))["description"])

    def test_index_names_tiktoks_targets_and_leaves_pinedramas_out(self):
        self.cut_grouped_release()
        data = json.loads(catalog("1.1.0"))
        data["patches"][0]["compatibility"].append(
            {"packageName": "com.ss.android.ttmd.video", "targets": [{"version": "1.0.0"}]})
        self.write("patches-list.json", json.dumps(data))
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                         readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                         device_line="", runtime=1, patch=1)
        description = json.loads(self.read("patches-bundle.json"))["description"]
        self.assertIn("2 patches for global TikTok 1.1.0 (com.zhiliaoapp.musically)", description)
        self.assertIn("applied to the supported APK", description)
        self.assertIn("Hushfeed v0.2.0 contains 2 patches for TikTok 1.1.0. New in this one", self.read("README.md"))

    def test_index_refuses_a_catalog_with_no_package_every_patch_declares(self):
        self.cut_grouped_release()
        data = json.loads(catalog("1.1.0"))
        data["patches"][1]["compatibility"] = [{"packageName": "com.ss.android.ttmd.video", "targets": [{"version": "1.0.0"}]}]
        self.write("patches-list.json", json.dumps(data))
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        before = {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()}
        with self.assertRaises(SystemExit) as caught:
            self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                             readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                             device_line="", runtime=1, patch=1)
        self.assertIn("0 packages every patch declares", str(caught.exception))
        self.assertEqual(before, {p: p.read_bytes() for p in self.root.rglob("*") if p.is_file()})

    def index_with_install_steps(self, install: str) -> None:
        self.cut_grouped_release()
        self.write("patches-list.json", catalog("1.1.0"))
        self.write("README.md", README + install)
        self.write(".github/ISSUE_TEMPLATE/bug_report.yml", "      placeholder: Version 0.1.0 for TikTok 1.0.0\n")
        for name in ("readme.txt", "bundle.txt"):
            self.write(name, "x.\n")
        self.run_command(release_text.index, version="0.2.0", created="2026-10-09T17:03:42",
                         readme_summary=str(self.root / "readme.txt"), bundle_summary=str(self.root / "bundle.txt"),
                         device_line="", runtime=1, patch=1)

    def assert_index_refuses(self, install: str, message: str) -> None:
        with self.assertRaises(SystemExit) as caught:
            self.index_with_install_steps(install)
        self.assertIn(message, str(caught.exception))
        self.assertEqual(README + install, self.read("README.md"))
        self.assertEqual("      placeholder: Version 0.1.0 for TikTok 1.0.0\n", self.read(".github/ISSUE_TEMPLATE/bug_report.yml"))
        self.assertEqual("", json.loads(self.read("patches-bundle.json"))["description"])

    def test_fold_moves_the_install_steps_onto_the_next_release(self):
        self.assertEqual(FOLDED, release_text.fold_next_release(INSTALL, ["1.1.0"]))

    def test_fold_leaves_a_readme_that_already_installs_the_targets(self):
        self.assertEqual(FOLDED, release_text.fold_next_release(FOLDED, ["1.1.0"]))

    def test_index_moves_the_install_steps_onto_the_build_it_describes(self):
        self.index_with_install_steps(INSTALL)
        self.assertTrue(self.read("README.md").endswith(FOLDED), self.read("README.md"))
        self.assertIn("Hushfeed v0.2.0 contains 2 patches for TikTok 1.1.0. New in this one", self.read("README.md"))
        self.assertIn("2 patches for global TikTok 1.1.0 (com.zhiliaoapp.musically)",
                      json.loads(self.read("patches-bundle.json"))["description"])
        self.assertEqual("      placeholder: Version 0.2.0 for TikTok 1.1.0\n", self.read(".github/ISSUE_TEMPLATE/bug_report.yml"))

    def test_index_refuses_install_steps_with_no_next_release_for_the_targets(self):
        install = "".join(line for line in INSTALL.splitlines(keepends=True) if not line.startswith("- Next release:"))
        self.assert_index_refuses(install, "installs TikTok 1.0.0 and has no '- Next release:' line for TikTok 1.1.0")

    def test_index_refuses_a_next_release_for_another_build(self):
        self.assert_index_refuses(INSTALL.replace("Next release: TikTok 1.1.0", "Next release: TikTok 1.2.0"),
                                  "names TikTok 1.2.0, but the patches declare TikTok 1.1.0")

    def test_index_refuses_a_next_release_line_it_cannot_read(self):
        self.assert_index_refuses(INSTALL.replace(" since 2026-02-02 (", " soon ("),
                                  "has a '- Next release:' line that doesn't read")

    def test_index_refuses_a_next_release_beside_a_version_line_it_cannot_read(self):
        self.assert_index_refuses(INSTALL.replace("on APKMirror since 2026-01-01.", "on APKMirror since 2026-01-01. "),
                                  "needs one Supported target line reading")

    def test_index_refuses_install_steps_linking_the_old_build_elsewhere(self):
        self.assert_index_refuses(
            INSTALL.replace("Take [1.0.0](" + OLD_APK, "Take [1.0.0](https://www.apkmirror.com/apk/t/tiktok-1-0-0-other/"),
            "links TikTok 1.0.0 somewhere other than its Supported target link")


if __name__ == "__main__":
    unittest.main()
