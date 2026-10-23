"""Release text for Hushfeed: the CHANGELOG cut, translation checks, GitHub notes and the index.

  py -3.13 tools/release_text.py cut --version 0.70.0 [--date 2026-10-09]
  py -3.13 tools/release_text.py check-translations (--version 0.70.0 | --unreleased)
  py -3.13 tools/release_text.py github-notes --version 0.70.0 --intro FILE --checked FILE --out FILE
  py -3.13 tools/release_text.py guide [--date 2026-10-09]
  py -3.13 tools/release_text.py index --version 0.70.0 --created 2026-10-09T17:03:42
        --readme-summary FILE --bundle-summary FILE --device-line TEXT --runtime N --patch N

Each command checks its inputs and changes nothing when a check fails. scripts/release/release.ps1
runs them in order; they can also run alone. The indexed bundle description ends with the cut
release's ### groups so Morphe Manager can show the current release's full change list.

Between releases the README's install steps and the bug form name the TikTok build the published
release patches, and a "- Next release:" line under Supported target names the one main declares.
index moves both onto that build and drops the line.
"""

import argparse
import datetime
import json
import re
from pathlib import Path
from typing import NoReturn

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
NOTES = ROOT / "extensions/tiktok/src/main/l10n/notes"
LANGUAGES = ("az", "de", "es", "in", "it", "pt-rBR", "ru", "tr")
SCOPE = "* **TikTok:** "
VERSION = re.compile(r"^\d+\.\d+\.\d+$")
DASHES = ("—", "–")
GUIDE = "concepts/patch-development.md"
# Letters only another table uses: a translation answered from the wrong table shows up here.
FOREIGN = {
    "az": r"[Ѐ-ӿ]|ñ|ã|õ|ß|ä",
    "de": r"[Ѐ-ӿ]|ə|ğ|ı|ş|ñ|ã|õ|ç",
    "es": r"[Ѐ-ӿ]|ə|ğ|ı|ş|ß|ä|ã|õ",
    "in": r"[Ѐ-ӿ]|ə|ğ|ı|ş|ß|ä|ñ|ã|õ|ç|é|ü|ö",
    "it": r"[Ѐ-ӿ]|ə|ğ|ı|ş|ß|ä|ñ|ã|õ|ç",
    "pt-rBR": r"[Ѐ-ӿ]|ə|ğ|ı|ş|ß|ä|ñ",
    "ru": r"ə|ğ|ı|ş|ß|ä|ñ|ã|õ|ç|é|ü|ö",
    "tr": r"[Ѐ-ӿ]|ə|ß|ä|ñ|ã|õ",
}
# Every table writes decimals with a comma, so an English "10,000" or "6.7 MB" is untranslated.
ENGLISH_NUMBER = re.compile(r"\b\d{1,3}(?:,\d{3})+\b|\b\d+\.\d+ ?(?:MB|GB|KB|K|M|x|%)(?!\w)")
# The README's Supported target lines, and the line naming the build the next release patches.
SUPPORTED_VERSION = re.compile(
    r"^- Version: \[(?P<version>\d+\.\d+\.\d+)\]\((?P<url>[^)\s]+)\), on APKMirror since (?P<date>\d{4}-\d{2}-\d{2})\.$",
    re.MULTILINE)
SUPPORTED_BUILD = re.compile(r"^- Build: version code .*$", re.MULTILINE)
SUPPORTED_SHA = re.compile(r"^(- SHA-256 of the APK every patch was verified against: )`[0-9a-f]{64}` \(\d+\.\d+\.\d+\)$",
                           re.MULTILINE)
NEXT_RELEASE = re.compile(
    r"^- Next release: TikTok (?P<version>\d+\.\d+\.\d+), \[on APKMirror\]\((?P<url>[^)\s]+)\) since "
    r"(?P<date>\d{4}-\d{2}-\d{2}) \((?P<build>version code [^)]+)\), SHA-256 `(?P<sha>[0-9a-f]{64})`\..*\n",
    re.MULTILINE)
# The README lines that tell someone which TikTok build to patch: the badge, the Important box,
# install step 1 and the two paragraphs on fetching the APK.
INSTALL_CLAIMS = ('<img alt="TikTok ', "> Hushfeed targets the global TikTok package", "1. Get the TikTok ",
                  "Google Play only ever serves", "APKMirror also offers some TikTok releases")


def fail(message: str) -> NoReturn:
    raise SystemExit(f"[release] {message}")


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8").replace("\r\n", "\n")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def section(text: str, heading: str, *, legacy: bool = False) -> str | None:
    """The body under a `## <heading>` line up to the next `## ` line, or None."""
    seen = set()
    previous = None
    for index, line in enumerate(re.findall(r"^## [^\n]*", text, re.MULTILINE)):
        if line == "## Unreleased":
            key = "Unreleased"
            if index != 0:
                fail("## Unreleased must precede published releases")
        elif legacy and re.fullmatch(r"## \[\d+\.\d+\.\d+(?:-[^\]]+)?\]\(https://[^)]+\) \([^\n)]+\)", line):
            # Keep the inherited linked/prerelease history unchanged in the full CHANGELOG.
            continue
        else:
            match = re.fullmatch(r"## (\d+\.\d+\.\d+) \([^\n)]+\)", line)
            if not match:
                fail(f"invalid release heading: {line}")
            key = match.group(1)
            version = tuple(map(int, key.split(".")))
            if previous is not None and version >= previous:
                fail("published release headings must be unique and newest first")
            previous = version
        if key in seen:
            fail(f"duplicate ## {key} heading")
        seen.add(key)
    if heading == "Unreleased":
        pattern = r"^## Unreleased(?:\n|$)"
    else:
        version = heading.removesuffix(" (")
        pattern = (rf"^## {re.escape(version)} \([^\n)]+\)(?:\n|$)" if VERSION.fullmatch(version)
                   else rf"^## {re.escape(heading)}(?:\n|$)")
    matches = list(re.finditer(pattern, text, re.MULTILINE))
    if not matches:
        return None
    match = matches[0]
    rest = text[match.end():]
    following = re.search(r"^## ", rest, re.MULTILINE)
    return rest[: following.start()] if following else rest


def bullets(body: str) -> list[str]:
    lines = [line for line in body.split("\n") if line.strip()]
    stray = [line for line in lines if not line.startswith("* ") and not line.startswith("### ")]
    if stray:
        fail("a bullet runs over more than one line, join it: " + stray[0][:80])
    return [line for line in lines if line.startswith("* ")]


def grouped_changelog(body: str, label: str) -> str:
    """Canonical ### groups for Manager, refusing loose bullets or empty/duplicate groups."""
    groups: list[tuple[str, list[str]]] = []
    seen = set()
    heading = None
    items: list[str] = []
    for line in (line for line in body.split("\n") if line.strip()):
        if line.startswith("### "):
            if heading is not None:
                if not items:
                    fail(f"{label} has an empty ### {heading} group")
                groups.append((heading, items))
            heading = line[4:].strip()
            if not heading or heading in seen:
                fail(f"{label} has an empty or duplicate ### group")
            seen.add(heading)
            items = []
        elif line.startswith("* "):
            if heading is None:
                fail(f"{label} has loose bullets and no ### groups")
            if not line.startswith(SCOPE):
                fail(f"{label} has a bullet that doesn't start {SCOPE.strip()}")
            items.append(line)
        else:
            fail(f"{label} has text outside its ### groups: {line[:80]}")
    if heading is not None:
        if not items:
            fail(f"{label} has an empty ### {heading} group")
        groups.append((heading, items))
    if not groups:
        fail(f"{label} has no ### groups for the description")
    return "\n\n".join(f"### {name}\n\n" + "\n".join(group) for name, group in groups)


def heading_of(version: str) -> str:
    """The CHANGELOG heading line of a published version."""
    match = re.search(rf"^## {re.escape(version)} \([^)]*\)$", read(CHANGELOG), re.MULTILINE)
    if not match:
        fail(f"CHANGELOG has no published {version} heading")
    return match.group(0)


def cut(args) -> None:
    if not VERSION.match(args.version):
        fail(f"{args.version} isn't X.Y.Z")
    date = args.date or datetime.datetime.now().astimezone().date().isoformat()
    text = read(CHANGELOG)
    if re.search(rf"^## {re.escape(args.version)} ", text, re.MULTILINE):
        fail(f"CHANGELOG already has {args.version}")
    body = section(text, "Unreleased", legacy=True)
    if body is None:
        fail("CHANGELOG has no ## Unreleased")
    newest = re.search(r"^## (\d+\.\d+\.\d+) \(", text, re.MULTILINE)
    if newest and tuple(map(int, args.version.split("."))) <= tuple(map(int, newest.group(1).split("."))):
        fail(f"{args.version} must be newer than {newest.group(1)}")
    items = bullets(body)
    if not items:
        fail("Unreleased is empty")
    grouped = grouped_changelog(body, "Unreleased")
    unscoped = [i for i in items if not i.startswith(SCOPE)]
    if unscoped:
        fail("Manager shows only TikTok-scoped bullets, retag these: " + "; ".join(i[:60] for i in unscoped))
    heading = f"## {args.version} ({date})"
    new = "## Unreleased\n\n" + heading + "\n\n" + grouped + "\n\n"
    start = text.index("## Unreleased\n")
    end = start + len("## Unreleased\n") + len(body)
    tables = {lang: NOTES / f"{lang}.txt" for lang in LANGUAGES}
    drafted = {lang: section(read(p), "Unreleased") for lang, p in tables.items() if p.exists()}
    have = [lang for lang, b in drafted.items() if b is not None and b.strip()]
    if have and len(have) != len(LANGUAGES):
        fail("translated in some tables only (" + ", ".join(have) + "): translate every table or none")
    # Curated English is mandatory, with translations held to those same summaries.
    check_translations(argparse.Namespace(version=None, unreleased=True))
    write(CHANGELOG, text[:start] + new + text[end:].lstrip("\n"))
    for path in [NOTES / "en.txt"] + [tables[lang] for lang in have]:
        table = read(path)
        write(path, table.replace("## Unreleased\n", heading + "\n", 1))
    print(f"[release] {len(items)} bullets under {heading}; "
          + (f"What's new translated in {len(have)} tables" if have else "What's new is English only"))


def check_translations(args) -> None:
    english_text = read(CHANGELOG)
    key = "Unreleased" if args.unreleased else args.version
    english_body = section(english_text, key, legacy=True)
    if english_body is None:
        fail(f"CHANGELOG has no {key} section")
    # Older published sections have introductory paragraphs as well as their change bullets.
    full = bullets(english_body) if args.unreleased else [line for line in english_body.split("\n") if line.startswith("* ")]
    problems = []
    english_path = NOTES / "en.txt"
    if not english_path.exists():
        fail("en.txt is missing: write one concise summary per CHANGELOG bullet")
    raw = english_path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf") or b"\r" in raw:
        problems.append("en: BOM or CR line endings")
    summary_body = section(raw.decode("utf-8"), key)
    if summary_body is None or not summary_body.strip():
        fail(f"en.txt has no {key} summaries")
    english = bullets(summary_body)
    if len(english) != len(full):
        problems.append(f"en: {len(english)} summaries, the CHANGELOG has {len(full)} bullets")
    for number, (original, summary) in enumerate(zip(full, english), 1):
        if not summary.startswith(SCOPE):
            problems.append(f"en bullet {number}: doesn't start {SCOPE.strip()}")
        if re.findall(r"#\d+", original) != re.findall(r"#\d+", summary):
            problems.append(f"en bullet {number}: issue references differ")
        if any(d in summary for d in DASHES):
            problems.append(f"en bullet {number}: em or en dash")
    if not args.unreleased:
        expected_heading = heading_of(args.version)
        for path in [english_path] + [NOTES / f"{lang}.txt" for lang in LANGUAGES]:
            if (path.exists() and section(read(path), key)
                    and expected_heading not in read(path).splitlines()):
                problems.append(f"{path.stem}: release heading differs")
    found = 0
    for lang in LANGUAGES:
        path = NOTES / f"{lang}.txt"
        if not path.exists():
            continue
        raw = path.read_bytes()
        if raw.startswith(b"\xef\xbb\xbf") or b"\r" in raw:
            problems.append(f"{lang}: BOM or CR line endings")
        body = section(raw.decode("utf-8"), key)
        if body is None or not body.strip():
            continue
        found += 1
        items = bullets(body)
        if len(items) != len(english):
            problems.append(f"{lang}: {len(items)} bullets, English has {len(english)} summaries")
        pattern = re.compile(FOREIGN[lang])
        for number, (a, b) in enumerate(zip(english, items), 1):
            where = f"{lang} bullet {number}"
            if not b.startswith(SCOPE):
                problems.append(f"{where}: doesn't start {SCOPE.strip()}")
            if re.findall(r"#\d+", a) != re.findall(r"#\d+", b):
                problems.append(f"{where}: issue references differ")
            if a.count("`") != b.count("`"):
                problems.append(f"{where}: backtick count differs")
            if any(d in b for d in DASHES):
                problems.append(f"{where}: em or en dash")
            if any(e in b for e in ("&gt;", "&lt;", "&amp;")):
                problems.append(f"{where}: HTML entity")
            hit = pattern.search(b)
            if hit:
                problems.append(f"{where}: letter {hit.group()!r} from another table")
            numbers = ENGLISH_NUMBER.findall(b)
            if numbers:
                problems.append(f"{where}: English number format {sorted(set(numbers))}")
    if found not in (0, len(LANGUAGES)):
        problems.append(f"{found} of {len(LANGUAGES)} tables translate {key}: every table or none")
    if problems:
        fail("translation check failed:\n  " + "\n  ".join(problems[:40]))
    print(f"[release] {key}: {len(english)} summaries, {found} tables translated and consistent")


MANAGER = re.compile(r"Use Morphe Manager (\d+\.\d+\.\d+) or newer")


def manager_floor() -> str:
    match = MANAGER.search(read(ROOT / "README.md"))
    if not match:
        fail("README doesn't say which Morphe Manager to use")
    return match.group(1)


def github_notes(args) -> None:
    body = section(read(CHANGELOG), args.version + " (", legacy=True)
    if body is None:
        fail(f"CHANGELOG has no published {args.version}")
    items = bullets(body)
    stripped = ["* " + i[len(SCOPE):] if i.startswith(SCOPE) else i for i in items]
    intro = read(Path(args.intro)).strip()
    checked = read(Path(args.checked)).strip()
    update = (f"Refresh the Hushfeed source in Morphe Manager {manager_floor()} or newer and patch TikTok "
              "again. Keep the manager's existing signing key so your login and settings carry over. "
              "The .mpp is a patch bundle, not a TikTok APK.")
    notes = (intro + "\n\n## What's changed\n\n" + "\n".join(stripped) + "\n\n## Update\n\n" + update
             + "\n\n## Checked\n\n" + checked + "\n")
    if any(d in notes for d in DASHES):
        fail("the notes have an em or en dash")
    if notes.count("\n* ") < len(items):
        fail("a CHANGELOG bullet didn't make it into the notes")
    write(Path(args.out), notes)
    print(f"[release] notes for {args.version}: {len(stripped)} of {len(items)} CHANGELOG bullets, {len(notes)} characters")


def catalog_patches() -> list:
    data = json.loads(read(ROOT / "patches-list.json"))
    return data["patches"] if isinstance(data, dict) else data


def catalog() -> tuple[int, list[str]]:
    """The patch count and the TikTok versions of the package every patch declares, as
    scripts/patch-target.ps1 reads them. PineDrama is declared only by the patches that apply
    to it (#156), so its build never joins the targets the index and README name."""
    patches = catalog_patches()
    versions: dict[str, set[str]] = {}
    declared_by: dict[str, int] = {}
    for patch in patches:
        for app in patch.get("compatibility") or []:
            package = str(app.get("packageName") or "")
            declared_by[package] = declared_by.get(package, 0) + 1
            versions.setdefault(package, set()).update(
                t["version"] for t in app.get("targets") or [] if t.get("version") and not t.get("experimental"))
    everywhere = [package for package, count in declared_by.items() if count == len(patches)]
    if len(everywhere) != 1:
        fail(f"patches-list.json has {len(everywhere)} packages every patch declares, not one: "
             f"{', '.join(sorted(declared_by)) or 'none'}")
    return len(patches), sorted(versions[everywhere[0]], key=lambda v: tuple(map(int, v.split("."))))


def spoken(items: list[str]) -> str:
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " and " + items[-1]


SMALL = ("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten")


def counted(n: int) -> str:
    """A count as the prose around it writes one: words up to ten, digits above."""
    return SMALL[n] if 0 <= n < len(SMALL) else str(n)


def swap(edits: dict, path: Path, pattern: str, replacement: str) -> None:
    """One line rewritten into `edits` (path to new text), so a later miss leaves every file as it was."""
    text = edits.get(path)
    new, count = re.subn(pattern, replacement, read(path) if text is None else text, count=1, flags=re.MULTILINE)
    if count != 1:
        fail(f"{path.name}: nothing matched {pattern[:60]!r}")
    edits[path] = new


def source_version() -> str:
    match = re.search(r"^version = (\d+\.\d+\.\d+)$", read(ROOT / "gradle.properties"), re.MULTILINE)
    if not match:
        fail("gradle.properties has no version = X.Y.Z line")
    return match.group(1)


def guide(args) -> None:
    """The developer guide's Source snapshot line and category table, held to gradle.properties
    and patches-list.json, which is what validate-release-facts.ps1 compares them with."""
    version = source_version()
    count, _ = catalog()
    categories: dict[str, int] = {}
    for patch in catalog_patches():
        name = str(patch.get("category") or "")
        categories[name] = categories.get(name, 0) + 1
    rows = "".join(f"| {name} | {categories[name]} |\n" for name in sorted(categories))
    date = args.date or datetime.datetime.now().astimezone().date().isoformat()
    path = ROOT / GUIDE
    edits = {}
    swap(edits, path, r"^Source snapshot: version \d+\.\d+\.\d+, \d+ patch entries\.",
         f"Source snapshot: version {version}, {count} patch entries.")
    swap(edits, path, r"last synced with the source on \d{4}-\d{2}-\d{2}", f"last synced with the source on {date}")
    swap(edits, path, r"catalog's \d+ patches are grouped as follows:\n\n(\| Manager category[^\n]*\n[^\n]*\n)(?:\|[^\n]*\n)+",
         f"catalog's {count} patches are grouped as follows:\n\n\\g<1>{rows}")
    write(path, edits[path])
    print(f"[release] {GUIDE} reads source {version} with {count} patch entries")


def fold_next_release(text: str, targets: list[str]) -> str:
    """The README with its install steps moved onto the build the release being indexed patches.

    A README with neither a Supported target '- Version:' line nor a '- Next release:' line (the
    test fixtures) comes back as it was, and so does one that already installs the targets and has
    no '- Next release:' line."""
    supported = list(SUPPORTED_VERSION.finditer(text))
    announced = re.findall(r"^- Next release:", text, re.MULTILINE)
    if not supported and not announced and not re.search(r"^- Version: ", text, re.MULTILINE):
        return text
    if len(supported) != 1 or len(re.findall(r"^- Version: ", text, re.MULTILINE)) != 1:
        fail("README.md needs one Supported target line reading '- Version: [X.Y.Z](url), on APKMirror since "
             "YYYY-MM-DD.'")
    current = supported[0]
    upcoming = list(NEXT_RELEASE.finditer(text))
    if len(upcoming) != len(announced):
        fail("README.md has a '- Next release:' line that doesn't read 'TikTok X.Y.Z, [on APKMirror](url) since "
             "YYYY-MM-DD (version code ...), SHA-256 `...`.'")
    if not upcoming:
        if [current["version"]] != targets:
            fail(f"README.md installs TikTok {current['version']} and has no '- Next release:' line for TikTok "
                 f"{spoken(targets)}, the build the patches declare")
        return text
    if len(upcoming) > 1:
        fail("README.md has more than one '- Next release:' line")
    upcoming = upcoming[0]
    if [upcoming["version"]] != targets:
        fail(f"README.md's '- Next release:' line names TikTok {upcoming['version']}, but the patches declare "
             f"TikTok {spoken(targets)}")
    if upcoming["version"] == current["version"]:
        fail(f"README.md already installs TikTok {current['version']}, so its '- Next release:' line is left over")
    folded = text[:upcoming.start()] + text[upcoming.end():]
    folded = SUPPORTED_VERSION.sub(
        lambda _: f"- Version: [{upcoming['version']}]({upcoming['url']}), on APKMirror since {upcoming['date']}.",
        folded, count=1)
    folded, builds = SUPPORTED_BUILD.subn(lambda _: f"- Build: {upcoming['build']}", folded)
    folded, hashes = SUPPORTED_SHA.subn(lambda m: f"{m.group(1)}`{upcoming['sha']}` ({upcoming['version']})", folded)
    if builds != 1 or hashes != 1:
        fail("README.md needs one Supported target '- Build:' line and one '- SHA-256 of the APK' line")
    old = re.compile(rf"(?<![\d.]){re.escape(current['version'])}(?!\.?\d)")
    # APKMirror writes the version into its links with dashes, so a link the swap above missed shows as that.
    old_link = re.compile(rf"tiktok-{re.escape(current['version'].replace('.', '-'))}(?![\d])")
    lines, seen = [], set()
    for line in folded.split("\n"):
        claim = next((c for c in INSTALL_CLAIMS
                      if (c in line if c == INSTALL_CLAIMS[0] else line.startswith(c))), None)
        if claim:
            seen.add(claim)
            line = old.sub(lambda _: upcoming["version"], line.replace(current["url"], upcoming["url"]))
            if old_link.search(line):
                fail(f"README.md links TikTok {current['version']} somewhere other than its Supported target link: "
                     f"{line[:80]}")
        lines.append(line)
    missing = [c for c in INSTALL_CLAIMS if c not in seen]
    if missing:
        fail(f"README.md has no install line with {missing[0]!r} to move onto TikTok {upcoming['version']}")
    return "\n".join(lines)


def index(args) -> None:
    count, targets = catalog()
    if not targets:
        fail("patches-list.json names no TikTok versions")
    manager = manager_floor()
    readme_summary = read(Path(args.readme_summary)).strip()
    bundle_summary = read(Path(args.bundle_summary)).strip()
    release_body = section(read(CHANGELOG), args.version, legacy=True)
    if release_body is None:
        fail(f"CHANGELOG has no dated {args.version} heading. Run cut first.")
    groups = grouped_changelog(release_body, args.version)
    apks = "the supported APK" if len(targets) == 1 else f"all {counted(len(targets))} supported APKs"
    description = (
        f"Hushfeed v{args.version} has {count} patches for global TikTok {spoken(targets)} (com.zhiliaoapp.musically).\n\n"
        + bundle_summary + "\n\n"
        + f"Validation: {args.runtime} runtime tests passed and {args.patch} patch tests passed locally. Both Android "
        + f"lint checks passed. All {count} patches applied to {apks} with resource and manifest checks. The "
        + "attached release receipt identifies the exact source and bundle."
        + (f" {args.device_line.strip()}" if args.device_line else "") + "\n\n"
        + f"Needs Morphe Manager {manager} or newer. Refresh the Hushfeed source to update. The .mpp is a patch "
        + "bundle, not a TikTok APK. Keep the manager's existing signing key when updating."
        + "\n\n" + groups
    )
    if any(d in description + readme_summary for d in DASHES):
        fail("the summaries have an em or en dash")
    bundle_path = ROOT / "patches-bundle.json"
    bundle = json.loads(read(bundle_path))
    bundle.update({
        "created_at": args.created,
        "description": description,
        "download_url": f"https://github.com/SysAdminDoc/hushfeed/releases/download/v{args.version}/patches-{args.version}.mpp",
        "version": args.version,
    })
    readme = ROOT / "README.md"
    edits = {readme: fold_next_release(read(readme), targets)}
    swap(edits, readme, r"^Hushfeed v\d+\.\d+\.\d+ contains \d+ patches for TikTok .*$",
         f"Hushfeed v{args.version} contains {count} patches for TikTok {spoken(targets)}. New in this one: "
         f"{readme_summary} It needs Morphe Manager {manager} or newer.")
    swap(edits, readme, r"^The main branch contains \d+ patches, .*$",
         f"The main branch contains {count} patches, the same set as v{args.version}.")
    swap(edits, readme, r"shows all \d+ Hushfeed patches", f"shows all {count} Hushfeed patches")
    swap(edits, ROOT / ".github/ISSUE_TEMPLATE/bug_report.yml",
         r"placeholder: Version \d+\.\d+\.\d+ for TikTok \d+\.\d+\.\d+(?!\.?\d)",
         f"placeholder: Version {args.version} for TikTok {targets[-1]}")
    swap(edits, ROOT / GUIDE, r"^Published bundle: version \d+\.\d+\.\d+, \d+ patches\.",
         f"Published bundle: version {args.version}, {count} patches.")
    for path, text in edits.items():
        write(path, text)
    write(bundle_path, json.dumps(bundle, indent=2, ensure_ascii=False) + "\n")
    print(f"[release] index, README, bug form and guide point at v{args.version} ({count} patches, TikTok {spoken(targets)})")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("cut")
    p.add_argument("--version", required=True)
    p.add_argument("--date")
    p.set_defaults(run=cut)
    p = sub.add_parser("check-translations")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--version")
    group.add_argument("--unreleased", action="store_true")
    p.set_defaults(run=check_translations)
    p = sub.add_parser("github-notes")
    for name in ("--version", "--intro", "--checked", "--out"):
        p.add_argument(name, required=True)
    p.set_defaults(run=github_notes)
    p = sub.add_parser("guide")
    p.add_argument("--date")
    p.set_defaults(run=guide)
    p = sub.add_parser("index")
    for name in ("--version", "--created", "--readme-summary", "--bundle-summary"):
        p.add_argument(name, required=True)
    p.add_argument("--device-line", default="")
    p.add_argument("--runtime", type=int, required=True)
    p.add_argument("--patch", type=int, required=True)
    p.set_defaults(run=index)
    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
