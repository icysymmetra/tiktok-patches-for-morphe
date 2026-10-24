#!/usr/bin/env python3
"""Exchange settings tables as bilingual XLIFF 1.2 without changing runtime resources."""

import argparse
import csv
import hashlib
import importlib.util
import io
import os
import re
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("gen_l10n", Path(__file__).with_name("gen-l10n.py"))
GEN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GEN)
NS = "urn:oasis:names:tc:xliff:document:1.2"
ET.register_namespace("", NS)
SPACE = {"{http://www.w3.org/XML/1998/namespace}space": "preserve"}


def tag(name):
    return f"{{{NS}}}{name}"


def unit_id(source):
    return hashlib.sha256(source.encode("utf-8")).hexdigest()


def language_code(name):
    name = re.sub(r"-r([A-Z]{2})(?=-|$)", r"-\1", name)
    head, dash, tail = name.partition("-")
    return {"in": "id", "iw": "he", "ji": "yi"}.get(head, head) + dash + tail


def atomic_write(path, text):
    path = Path(path)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="",
                                         dir=path.parent, prefix="." + path.name, delete=False) as handle:
            temporary = Path(handle.name)
            handle.write(text)
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


def table_entries(path):
    if path.suffix not in (".tsv", ".csv"):
        raise ValueError("settings table must be .tsv or .csv")
    return GEN.read(str(path))


def export_xml(table, output):
    entries = table_entries(table)
    root = ET.Element(tag("xliff"), {"version": "1.2"})
    file = ET.SubElement(root, tag("file"), {"source-language": "en", "target-language": language_code(table.stem),
                                           "datatype": "plaintext", "original": "settings"})
    body = ET.SubElement(file, tag("body"))
    for source, target in sorted(entries.items()):
        unit = ET.SubElement(body, tag("trans-unit"), {"id": unit_id(source), **SPACE})
        ET.SubElement(unit, tag("source"), SPACE).text = source
        ET.SubElement(unit, tag("target"), SPACE).text = target
        variant = GEN.PLURAL_VARIANT.match(source)
        if variant:
            ET.SubElement(unit, tag("note")).text = f"CLDR plural form {variant['category']}. Keep the source key unchanged."
    ET.indent(root, space="  ")
    # XML normalizes literal CR characters, even with xml:space. A character reference survives.
    text = ET.tostring(root, encoding="unicode", xml_declaration=True).replace("\r", "&#13;") + "\n"
    # Refuse text XML cannot represent before replacing a previous export.
    ET.fromstring(text)
    atomic_write(output, text)


def read_xml(path, language):
    if path.stat().st_size > 32 * 1024 * 1024:
        raise ValueError("XLIFF exceeds 32 MB")
    text = path.read_text(encoding="utf-8-sig")
    if re.search(r"<!\s*(?:DOCTYPE|ENTITY)", text, re.IGNORECASE):
        raise ValueError("XLIFF must not declare entities or a DTD")
    root = ET.fromstring(text)
    if root.tag != tag("xliff") or root.get("version") != "1.2":
        raise ValueError("expected XLIFF 1.2")
    files = root.findall(tag("file"))
    if len(files) != 1 or files[0].get("source-language") != "en":
        raise ValueError("expected one English-source file")
    actual = files[0].get("target-language", "")
    if language_code(actual).lower() != language_code(language).lower():
        raise ValueError(f"target language {actual!r} does not match {language!r}")
    body = files[0].find(tag("body"))
    if body is None or not len(body):
        raise ValueError("XLIFF has no translation units")
    entries = {}
    ids = set()
    for number, unit in enumerate(body, 1):
        if unit.tag != tag("trans-unit"):
            raise ValueError(f"expected flat translation units, not {unit.tag}")
        sources, targets = unit.findall(tag("source")), unit.findall(tag("target"))
        if len(sources) != 1 or len(targets) != 1 or len(sources[0]) or len(targets[0]):
            raise ValueError(f"unit {number} needs one plain-text source and target")
        source, target = sources[0].text or "", targets[0].text or ""
        identifier = unit.get("id")
        if identifier != unit_id(source) or identifier in ids:
            raise ValueError(f"unit {number} has a changed or duplicate source identity")
        ids.add(identifier)
        if not target.strip():
            raise ValueError(f"unit {number} has an empty translation")
        GEN.add(entries, source, target, str(path), number)
    return entries


def import_xml(source, table):
    original = table_entries(table)
    entries = read_xml(source, table.stem)
    if entries.keys() != original.keys():
        raise ValueError(f"XLIFF source keys differ from the table (missing {len(original.keys() - entries.keys())}, "
                         f"extra {len(entries.keys() - original.keys())})")
    with table.open(encoding="utf-8-sig", newline="") as handle:
        text = handle.read()
    if table.suffix == ".csv":
        rows = list(csv.reader(io.StringIO(text, newline="")))
        for row in rows[1:]:
            if row:
                row[1] = entries[row[0].replace("\\n", "\n")].replace("\n", "\\n")
        output = io.StringIO(newline="")
        csv.writer(output, lineterminator="\n").writerows(rows)
        updated = output.getvalue()
    else:
        rows = []
        for row in text.splitlines():
            if "\t" in row:
                english = row.split("\t", 1)[0]
                target = entries[english.replace("\\n", "\n")]
                if "\r" in target:
                    raise ValueError("TSV cannot carry a carriage return; use CSV")
                row = english + "\t" + target.replace("\n", "\\n")
            rows.append(row)
        updated = "\n".join(rows) + "\n"
    atomic_write(table, updated)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    export = commands.add_parser("export", help="export a TSV or CSV table as XLIFF 1.2")
    export.add_argument("table", type=Path)
    export.add_argument("output", type=Path)
    ingest = commands.add_parser("import", help="validate XLIFF and update its existing table")
    ingest.add_argument("source", type=Path)
    ingest.add_argument("table", type=Path)
    args = parser.parse_args()
    try:
        if args.command == "export":
            export_xml(args.table, args.output)
        else:
            import_xml(args.source, args.table)
    except (OSError, ValueError, ET.ParseError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
