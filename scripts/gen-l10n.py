"""Turn the settings translation tables into Java classes the extension carries.

Reads every extensions/tiktok/src/main/l10n/<lang>.tsv (English TAB translation, one entry
per line, # comments) or <lang>.csv (a source,target header and one row per entry, which is
the form Weblate hosts) and writes, in
extensions/tiktok/src/main/java/app/morphe/extension/tiktok/settings/:

  L10nTranslations.java          the language list and the lookup
  L10nTranslations<Lang>.java    one language's table each (L10nTranslationsDe.java, ...)

The English text in the code is the lookup key, so only the translations are stored. They
travel in the extension's own DEX rather than in TikTok's resources: merging a few hundred
strings into an app whose resource table already holds 74,765 of them costs more than 250 MB
of patching memory, which is more than Morphe Manager gives by default (measured 2026-09-06).

Each language's table is filled by several methods so no single one approaches the 64 KB
bytecode limit. One file holding every language reached 2,500 KB on 2026-10-12, the most
Android Lint reads, and the lint gate failed on it; a file per language keeps each under 450 KB.

Anything it cannot make sense of stops the run with the file and line rather than being dropped
on the way past: a row with no source text, a key that begins with the comment character, and a
translation whose placeholders are not the ones its key carries. The placeholder comparison is
the same one SettingsL10nTest makes of the generated class, brought forward so that running this
by hand cannot report "wrote N translations" over a table that would fail the build.

The English base it writes is the union of the language tables, so a code string nobody has
translated yet reaches Weblate only once it is in one of them. What holds every code string to
a table is SettingsL10nTest.everySettingsStringHasATranslationEntry, which builds the real
preference rows and demands each rendered string be a key. This script cannot see those rows.

Context. A .tsv table may carry a comment line above a row, "# context: what the row is and
where it shows", and the reader skips it; a key is a whole sentence or phrase, never a lone
unit or conjunction, so a translator sees the sentence the words sit in ("%1$s days", not
"days"; "%1$s, %2$s or %3$s", not "or").

Plurals. A count is written as two English keys, the one form ("1 result") and the other form
("%1$d results"), and L10n.quantity picks between them by the phone language's CLDR plural rule
rather than by count == 1. Android 6 uses the shipped languages' integer rules from CLDR 48;
newer Android versions keep their platform ICU rules. Russian already uses extra forms.
A language with more forms keeps each extra form as a row keyed by the other
form plus "|" and the CLDR category name: "%1$d results|few" and "%1$d results|many". The one
form says 1 outright, so a language whose one category takes other counts too (Russian's 21 and
101) adds "%1$d results|one" for those. Such a row is a translation of the other form, so it is
held to that form's placeholders and it is left out of the English base, which lists source
strings only.

Run from the repository root: python scripts/gen-l10n.py
"""
import csv
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
L10N = os.path.join(ROOT, "extensions", "tiktok", "src", "main", "l10n")
JAVA = os.path.join(ROOT, "extensions", "tiktok", "src", "main", "java", "app", "morphe",
                    "extension", "tiktok", "settings", "L10nTranslations.java")

# Entries per generated method. 60 pairs is roughly 2 KB of bytecode, well inside the limit
# and few enough methods to stay readable.
CHUNK = 60

# Three languages have two ISO codes, and the two runtimes disagree about which one to give
# back. Android's Locale.getLanguage() answers with the legacy code, a JVM since 17 answers
# with the new one, so the same table has to be reachable under both or the tests and the
# phone read different tables.
ALIASES = {"in": "id", "id": "in", "iw": "he", "he": "iw", "ji": "yi", "yi": "ji"}

# The keys are English, so there is no English translation table. This file is the list of what
# there is to translate, which is what Weblate calls a monolingual base.
ENGLISH_BASE = "en"


# The header Weblate writes and expects. A third column is allowed and ignored: Weblate adds
# "context" and the key here is the English text itself, so there is nothing for it to carry.
CSV_HEADER = ["source", "target"]

# Both spellings a format string can use, numbered and bare. Matching only "%1$s" let a plain
# "%s" through, and an invented placeholder with it.
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z]")

# What a comment line starts with. A key legitimately beginning with it cannot be told from a
# comment, so an entry that starts with it is refused rather than guessed at.
COMMENT = "#"

# A plural form English does not have: the other form's key, a bar and the CLDR category. It is
# a translation of that other form, not a source string of its own.
PLURAL_VARIANT = re.compile(r"^(?P<base>.+)\|(?P<category>zero|one|two|few|many)$")


def plural_base(english):
    """The other form a |category row belongs to, or the key itself for an ordinary row."""
    match = PLURAL_VARIANT.match(english)
    return match.group("base") if match else english


def read(path):
    """One table, from either form. A newline is the two characters \\n in both."""
    if path.endswith(".csv"):
        return read_csv(path)
    return read_tsv(path)


def add(entries, english, translated, path, number):
    english = english.replace("\\n", "\n")
    translated = translated.replace("\\n", "\n")
    if not english.strip():
        sys.exit(f"{path}:{number}: no source text")
    if english.startswith(COMMENT):
        sys.exit(f"{path}:{number}: a key cannot begin with {COMMENT}: it cannot be told from a comment")
    if english in entries:
        sys.exit(f"{path}:{number}: duplicate entry: {english}")
    # A dropped, added, renumbered or retyped placeholder, in one comparison. The runtime hands
    # the same arguments to whichever table is loaded, so a translation that asks for different
    # ones formats the wrong value or throws on the phone.
    #
    # When every placeholder is numbered (%1$d, %2$s), a translation may use them in any order
    # and the set must match. Unnumbered placeholders (%d, %s) keep their order, because
    # String.format fills them positionally.
    wanted = PLACEHOLDER.findall(plural_base(english))
    given = PLACEHOLDER.findall(translated)
    all_numbered = all("$" in p for p in wanted) if wanted else False
    shown = english.replace("\n", "\\n")
    if all_numbered:
        if sorted(wanted) != sorted(given):
            sys.exit(f"{path}:{number}: numbered placeholders {wanted} became {given} in: {shown}")
    elif wanted != given:
        sys.exit(f"{path}:{number}: placeholders {wanted} became {given} in: {shown}")
    entries[english] = translated


def read_tsv(path):
    entries = {}
    # utf-8-sig, not utf-8: Excel writes a byte order mark on any round trip, and it would
    # otherwise become part of the first key.
    with open(path, encoding="utf-8-sig") as handle:
        for number, line in enumerate(handle, 1):
            line = line.rstrip("\r\n")
            if not line:
                continue
            # A comment has no tab. One that does is an entry whose key starts with the comment
            # character, and add() refuses it by name rather than letting the line disappear.
            if line.startswith(COMMENT) and "\t" not in line:
                continue
            if "\t" not in line:
                sys.exit(f"{path}:{number}: no tab")
            english, translated = line.split("\t", 1)
            add(entries, english, translated, path, number)
    return entries


def read_csv(path):
    """The Weblate form: a source,target header, then one row per entry.

    Comma delimited with a header on purpose. Weblate's own documentation calls its dialect
    auto-detection unreliable, so the file says what it is rather than leaving it to be guessed.
    """
    entries = {}
    with open(path, encoding="utf-8-sig", newline="") as handle:
        rows = csv.reader(handle)
        header = next(rows, None)
        if header is None:
            sys.exit(f"{path}: no header")
        if [column.strip().lower() for column in header[:2]] != CSV_HEADER:
            sys.exit(f"{path}:1: the header has to start source,target, not {','.join(header)}")
        for number, row in enumerate(rows, 2):
            # A blank line between entries is nothing. A row with columns but no source text is
            # a row that lost its key, which add() reports rather than skipping.
            if not row:
                continue
            if len(row) < 2:
                sys.exit(f"{path}:{number}: no target column")
            add(entries, row[0], row[1], path, number)
    return entries


def write_english_base(tables, path):
    """The monolingual base Weblate reads to know what there is to translate.

    Every key any table carries, with itself as the target. The gate refuses a language table
    that is missing a key or carries one nothing shows any more, so the union is the whole set.
    """
    keys = set()
    for entries in tables.values():
        # A |category row is a language's extra plural form of a key the base already lists.
        keys.update(english for english in entries if not PLURAL_VARIANT.match(english))
    with open(path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\n")
        writer.writerow(CSV_HEADER)
        for english in sorted(keys):
            escaped = english.replace("\n", "\\n")
            writer.writerow([escaped, escaped])
    return len(keys)


def literal(text):
    out = text.replace("\\", "\\\\").replace('"', '\\"')
    out = out.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
    return f'"{out}"'


def java_name(lang):
    return lang.replace("-", "_").replace("+", "_")


def method_name(lang):
    """The suffix naming a table's class. Only the first letter is raised: capitalize() lowered
    the rest, so pt-rBR became Pt_rbr and two tags differing in case would have collided."""
    name = java_name(lang)
    return name[:1].upper() + name[1:]


def class_name(lang):
    return os.path.splitext(os.path.basename(JAVA))[0] + method_name(lang)


HEADER = [
    "/*",
    " * Copyright 2026 Hushfeed contributors",
    " * https://github.com/SysAdminDoc/hushfeed",
    " *",
    " * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).",
    " */",
    "package app.morphe.extension.tiktok.settings;",
    "",
]


def language_class(lang, entries):
    """One language's table, filled in chunks so no method approaches the 64 KB bytecode limit."""
    pairs = [(english, entries[english]) for english in sorted(entries)]
    chunks = [pairs[at:at + CHUNK] for at in range(0, len(pairs), CHUNK)]
    name = class_name(lang)
    lines = HEADER + [
        "import java.util.HashMap;",
        "import java.util.Map;",
        "",
        f"/** The {lang} table of {{@link L10nTranslations}}, generated by scripts/gen-l10n.py. Do not edit by hand. */",
        f"final class {name} {{",
        f"    private {name}() {{",
        "    }",
        "",
        "    static Map<String, String> build() {",
        f"        Map<String, String> table = new HashMap<>({len(pairs) * 2});",
    ]
    for index in range(len(chunks)):
        lines.append(f"        fill{index}(table);")
    lines.append("        return table;")
    lines.append("    }")
    for index, chunk in enumerate(chunks):
        lines.append("")
        lines.append(f"    private static void fill{index}(Map<String, String> table) {{")
        for english, translated in chunk:
            lines.append(f"        table.put({literal(english)},")
            lines.append(f"                {literal(translated)});")
        lines.append("    }")
    lines.append("}")
    return lines


def write_java(path, lines):
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write("\n".join(lines) + "\n")


def main():
    tables = {}
    seen = {}
    for name in sorted(os.listdir(L10N)):
        if not name.endswith(".tsv") and not name.endswith(".csv"):
            continue
        lang = name[:-4]
        # en.csv is the base Weblate translates from, not a translation of anything.
        if lang == ENGLISH_BASE:
            continue
        if lang in seen:
            sys.exit(f"{lang} is in both {seen[lang]} and {name}: keep one form per language")
        seen[lang] = name
        tables[lang] = read(os.path.join(L10N, name))
    if not tables:
        sys.exit("no .tsv or .csv tables in " + L10N)

    lines = HEADER + [
        "import java.util.Map;",
        "",
        "/**",
        " * The settings text in every language the bundle carries.",
        " *",
        " * Generated by scripts/gen-l10n.py from extensions/tiktok/src/main/l10n. Do not edit by",
        " * hand: edit the table under src/main/l10n and run the script.",
        " *",
        " * The translations live here rather than in TikTok's resources because merging them into",
        " * a resource table of 74,765 strings costs more patching memory than Morphe Manager gives",
        " * by default. Each language's table is its own generated class beside this one, since one",
        " * file holding all of them grew past the 2,500 KB Android Lint reads.",
        " */",
        "public final class L10nTranslations {",
        "    private L10nTranslations() {",
        "    }",
        "",
    ]

    languages = sorted(tables)
    lines.append("    /** The language tags with a table, lower case, in the order they are tried. */")
    tags = ", ".join(literal(lang.lower()) for lang in languages)
    lines.append(f"    static final String[] LANGUAGES = {{{tags}}};")
    lines.append("")
    lines.append("    /**")
    lines.append("     * The table for one language tag, or null when nothing was translated into it.")
    lines.append("     * A language with two ISO codes answers to both, because Android reports the")
    lines.append("     * legacy one and a desktop JVM reports the new one.")
    lines.append("     */")
    lines.append("    static Map<String, String> of(String language) {")
    lines.append("        switch (language) {")
    known = [other.lower() for other in languages]
    for lang in languages:
        name = lang.lower()
        labels = [name]
        # The alias is on the language, so a regional table answers to both spellings too.
        head, dash, tail = name.partition("-")
        alias = ALIASES.get(head)
        if alias and alias + dash + tail not in known:
            labels.append(alias + dash + tail)
        for label in labels:
            lines.append(f"            case {literal(label)}:")
        lines.append(f"                return {class_name(lang)}.build();")
    lines.append("            default:")
    lines.append("                return null;")
    lines.append("        }")
    lines.append("    }")

    lines.append("}")

    folder = os.path.dirname(JAVA)
    os.makedirs(folder, exist_ok=True)
    write_java(JAVA, lines)
    total = 0
    written = {os.path.basename(JAVA)}
    for lang in languages:
        total += len(tables[lang])
        path = os.path.join(folder, class_name(lang) + ".java")
        write_java(path, language_class(lang, tables[lang]))
        written.add(os.path.basename(path))
    # A language whose table was removed must not leave its class behind.
    stem = os.path.splitext(os.path.basename(JAVA))[0]
    for name in os.listdir(folder):
        if name.startswith(stem) and name.endswith(".java") and name not in written:
            os.remove(os.path.join(folder, name))
    base = write_english_base(tables, os.path.join(L10N, ENGLISH_BASE + ".csv"))
    print(f"wrote {total} translations for {', '.join(languages)}, and {base} source strings to {ENGLISH_BASE}.csv")


if __name__ == "__main__":
    main()
