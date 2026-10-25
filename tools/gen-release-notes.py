"""Embed curated release summaries from 0.60.0 onward in the Android extension.

Translated sections live in extensions/tiktok/src/main/l10n/notes/<table>.txt, one file per
settings table, with en.txt holding the English summaries. Each CHANGELOG bullet has one
summary in the same order under its original release heading. A release
is translated for every table or for none (#91), so What's new never shows one language a
release in its own words and the next language the English.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHANGELOG = ROOT / "CHANGELOG.md"
OUTPUT = ROOT / "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/preference/ReleaseNotesData.java"
NOTES = ROOT / "extensions/tiktok/src/main/l10n/notes"
LANGUAGES = ("az", "de", "es", "in", "it", "pt-rBR", "ru", "tr")
START = (0, 60, 0)
HEADING = re.compile(r"^## (\d+)\.(\d+)\.(\d+) \(", re.MULTILINE)


def published(text: str) -> str:
    text = text.replace("\r\n", "\n")
    matches = list(HEADING.finditer(text))
    if not matches:
        raise ValueError("CHANGELOG has no published release headings")
    sections = []
    previous = None
    for index, match in enumerate(matches):
        version = tuple(map(int, match.groups()))
        if previous is not None and version >= previous:
            raise ValueError("CHANGELOG releases are not newest first")
        previous = version
        if version < START:
            break
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        sections.append(text[match.start():end].strip())
    if not sections:
        raise ValueError("CHANGELOG has no release at or above 0.60.0")
    return "\n\n".join(sections) + "\n"


def releases(text: str) -> dict:
    """Each "## X.Y.Z (" section of the text, keyed by its version."""
    text = text.replace("\r\n", "\n")
    matches = list(HEADING.finditer(text))
    found = {}
    previous = None
    headings = [line for line in text.split("\n") if line.startswith("## ")]
    if "## Unreleased" in headings and (headings[0] != "## Unreleased" or headings.count("## Unreleased") != 1):
        raise ValueError("Unreleased must appear once before published releases")
    for line in headings:
        if line != "## Unreleased" and not re.fullmatch(r"## \d+\.\d+\.\d+ \([^\n)]+\)", line):
            raise ValueError(f"invalid release heading: {line}")
    for index, match in enumerate(matches):
        version = tuple(map(int, match.groups()))
        if previous is not None and version >= previous:
            raise ValueError("release headings must be unique and newest first")
        previous = version
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        found[version] = text[match.start():end].strip()
    return found


def bullets(section: str) -> int:
    return sum(1 for line in section.split("\n") if line.startswith("* "))


def name(version: tuple) -> str:
    return ".".join(map(str, version))


def summary_section(summary: str, source: str, where: str) -> None:
    if summary.splitlines()[0] != source.splitlines()[0]:
        raise ValueError(f"{where}: release heading differs")
    items = [line for line in summary.split("\n")[1:] if line.strip()]
    original = [line for line in source.split("\n") if line.startswith("* ")]
    if len(items) != len(original):
        raise ValueError(f"{where}: {len(items)} bullets, expected {len(original)}")
    for number, (item, full) in enumerate(zip(items, original), 1):
        if not item.startswith("* **TikTok:** "):
            raise ValueError(f"{where} bullet {number}: expected one TikTok-scoped line")
        if re.findall(r"#\d+", item) != re.findall(r"#\d+", full):
            raise ValueError(f"{where} bullet {number}: issue references differ")


def table(path: Path) -> dict:
    raw = path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf") or b"\r" in raw:
        raise ValueError(f"{path.name}: BOM or CR line endings")
    return releases(raw.decode("utf-8"))


def summaries(english: str) -> str:
    """Require one authored English summary for every embedded CHANGELOG bullet."""
    shipped = releases(english)
    path = NOTES / "en.txt"
    sections = table(path)
    if sections.keys() != shipped.keys():
        missing = sorted(shipped.keys() - sections.keys(), reverse=True)
        extra = sorted(sections.keys() - shipped.keys(), reverse=True)
        raise ValueError(f"en.txt release coverage differs: missing {list(map(name, missing))}, "
                         f"extra {list(map(name, extra))}")
    for version, section in sections.items():
        summary_section(section, shipped[version], f"en.txt {name(version)}")
    return "\n\n".join(sections.values()) + "\n"


def translations(english: str) -> dict:
    """The translated notes by table, after checking every release is in every table or none."""
    shipped = releases(english)
    found = {}
    if NOTES.is_dir():
        for path in sorted(NOTES.glob("*.txt")):
            if path.stem == "en":
                continue
            if path.stem not in LANGUAGES:
                raise ValueError(f"{path.name} has no settings table to go with it")
            sections = table(path)
            if not sections:
                raise ValueError(f"{path.name} has no release heading")
            for version, section in sections.items():
                if version not in shipped:
                    raise ValueError(f"{path.name} translates {name(version)}, which the CHANGELOG "
                                     "hasn't published")
                summary_section(section, shipped[version], f"{path.name} {name(version)}")
            found[path.stem] = sections
    for version in sorted({v for sections in found.values() for v in sections}, reverse=True):
        missing = [language for language in LANGUAGES if version not in found.get(language, {})]
        if missing:
            raise ValueError(f"{name(version)} is translated for some tables but not "
                             f"{', '.join(missing)}: translate it for every table or none")
    return {language: "\n\n".join(sections[v] for v in sorted(sections, reverse=True)) + "\n"
            for language, sections in found.items()}


def builder(notes: str) -> list:
    """The text as StringBuilder appends of at most 30,000 class-file bytes each."""
    # CONSTANT_Utf8 counts NUL twice and supplementary characters as two surrogates.
    parts = []
    start = 0
    size = 0
    for index, character in enumerate(notes):
        code = ord(character)
        width = 1 if 0 < code < 128 else 2 if code < 2048 else 3 if code <= 65535 else 6
        if size + width > 30_000:
            parts.append(notes[start:index])
            start = index
            size = 0
        size += width
    parts.append(notes[start:])
    lines = []
    for part in parts:
        escaped = "".join(
            "\\\\" if character == "\\" else '\\"' if character == '"'
            else f"\\{ord(character):03o}" if ord(character) < 32 or ord(character) == 127
            else character for character in part
        )
        lines.append(f'            .append("{escaped}")')
    return lines


def constant(language: str) -> str:
    return "NOTES_" + language.upper().replace("-", "_")


def metra_notes(text: str) -> str:
    """Normalize Metra's semantic-release headings, excluding prereleases and duplicate stubs."""
    headings = list(re.finditer(r"(?m)^#{1,2} (.+)$", text))
    result = []
    seen = set()
    for index, heading in enumerate(headings):
        release = re.fullmatch(r"\[(\d+\.\d+\.\d+)\]\([^\n]+\) \((\d{4}-\d{2}-\d{2})\)", heading[1])
        if not release or release[1] in seen:
            continue
        end = headings[index + 1].start() if index + 1 < len(headings) else len(text)
        body = text[heading.end():end].strip()
        if not body:
            continue
        seen.add(release[1])
        # Commit links have no meaning in the compact in-app text; the full changelog stays linked.
        body = re.sub(r"\s*\(\[[0-9a-f]+\]\(https://github\.com/[^)]+\)\)", "", body)
        body = re.sub(r"(?m)^### [^\n]+\n*", "", body)
        body = re.sub(r"\*\*tiktok:\*\*", "**TikTok:**", body, flags=re.I)
        result.append(f"## {release[1]} ({release[2]})\n\n{body}\n")
    if not result:
        raise ValueError("Metra CHANGELOG has no published stable releases")
    return "\n".join(result)


def main() -> None:
    source = CHANGELOG.read_text(encoding="utf-8")
    if re.search(r"(?m)^#{1,2} \[\d+\.\d+\.\d+\]\(https://github\.com/icysymmetra/", source):
        notes = metra_notes(source)
        (NOTES / "en.txt").write_text(notes, encoding="utf-8", newline="\n")
        # Upstream's translated release history describes different releases. Fall back to the
        # actual Metra English notes until translations of those releases are supplied.
        translated = {}
    else:
        notes = summaries(published(source))
        translated = translations(notes)
    lines = [
        "/*",
        " * Copyright 2026 Hushfeed contributors",
        " * https://github.com/SysAdminDoc/hushfeed",
        " *",
        " * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).",
        " */",
        "package app.morphe.extension.tiktok.settings.preference;",
        "",
        "/** Curated published summaries carried inside the app. Run tools/gen-release-notes.py after release edits. */",
        "public final class ReleaseNotesData {",
        "    private ReleaseNotesData() {}",
        "    public static final String TEXT = new StringBuilder()",
    ]
    lines += builder(notes)
    lines.append("            .toString();")
    lines += [
        "",
        "    /** The translated notes for a settings table tag (lower case, as L10n reads it), or null. */",
        "    public static String translated(String tag) {",
        "        if (tag == null) return null;",
        "        switch (tag) {",
    ]
    for language in sorted(translated):
        # L10nTranslations reads Indonesian's table for both of Java's codes.
        for tag in [language.lower()] + (["id"] if language == "in" else []):
            lines.append(f'            case "{tag}":')
        lines.append(f'                return {constant(language)};')
    lines += ["            default: return null;", "        }", "    }"]
    for language in sorted(translated):
        lines.append("")
        lines.append(f"    private static final String {constant(language)} = new StringBuilder()")
        lines += builder(translated[language])
        lines.append("            .toString();")
    lines.append("}")
    OUTPUT.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")
    print(f"embedded {bullets(notes)} published summaries and "
          f"{len(translated)} translations")


if __name__ == "__main__":
    main()
