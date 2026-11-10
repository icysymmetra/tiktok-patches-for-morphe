#!/usr/bin/env python3
"""Exercise translation exchange on temporary tables, including the real generator."""

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SCRIPT = Path(__file__).with_name("l10n-xml.py")
SPEC = importlib.util.spec_from_file_location("l10n_xml", SCRIPT)
XML = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(XML)


class TranslationXmlTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.table = self.root / "ru.tsv"
        self.original = ("# context: Results on the search page\n"
                         "1 result\t1 результат\n"
                         "%1$d results\t%1$d результатов\n"
                         "%1$d results|one\t%1$d результат\n"
                         "%1$d results|few\t%1$d результата\n"
                         "%1$d results|many\t%1$d результатов\n"
                         "Hello %1$s, count %2$d\tКоличество %2$d, привет %1$s\n"
                         "Line\\n& < > \" ' 😀\tСтрока\\n& < > \" ' 😀\n")
        self.table.write_text(self.original, encoding="utf-8")
        self.exchange = self.root / "ru.xlf"

    def edit_xml(self, mutate):
        XML.export_xml(self.table, self.exchange)
        tree = ET.parse(self.exchange)
        mutate(tree.getroot())
        tree.write(self.exchange, encoding="utf-8", xml_declaration=True)

    def units(self, root):
        return root.findall(".//" + XML.tag("trans-unit"))

    def assert_rejected_without_write(self, mutate, pattern):
        self.edit_xml(mutate)
        with self.assertRaisesRegex((ValueError, SystemExit, ET.ParseError), pattern):
            XML.import_xml(self.exchange, self.table)
        self.assertEqual(self.original, self.table.read_text(encoding="utf-8"))

    def test_changed_translation_reaches_generated_java_and_plurals_survive(self):
        def change(root):
            for unit in self.units(root):
                if unit.find(XML.tag("source")).text == "1 result":
                    unit.find(XML.tag("target")).text = "Один результат"
        self.edit_xml(change)
        subprocess.run([sys.executable, str(SCRIPT), "import", str(self.exchange), str(self.table)],
                       capture_output=True, text=True, encoding="utf-8", check=True)
        entries = XML.table_entries(self.table)
        self.assertEqual("Один результат", entries["1 result"])
        self.assertEqual("%1$d результата", entries["%1$d results|few"])
        self.assertIn("# context: Results on the search page", self.table.read_text(encoding="utf-8"))
        old = XML.GEN.L10N, XML.GEN.JAVA
        XML.GEN.L10N, XML.GEN.JAVA = str(self.root), str(self.root / "L10nTranslations.java")
        try:
            with contextlib.redirect_stdout(io.StringIO()):
                XML.GEN.main()
        finally:
            XML.GEN.L10N, XML.GEN.JAVA = old
        # The lookup and each language's table are separate generated files since 2026-10-12.
        self.assertIn("L10nTranslationsRu.build()", (self.root / "L10nTranslations.java").read_text(encoding="utf-8"))
        generated = (self.root / "L10nTranslationsRu.java").read_text(encoding="utf-8")
        self.assertIn('"Один результат"', generated)
        self.assertIn('"%1$d results|few"', generated)
        self.assertIn('"Количество %2$d, привет %1$s"', generated)

    def test_unicode_entities_newlines_and_export_order_round_trip(self):
        XML.export_xml(self.table, self.exchange)
        first = self.exchange.read_bytes()
        XML.export_xml(self.table, self.exchange)
        self.assertEqual(first, self.exchange.read_bytes())
        XML.import_xml(self.exchange, self.table)
        self.assertEqual(self.original, self.table.read_text(encoding="utf-8"))

    def test_every_shipped_language_round_trips_without_losing_entries(self):
        folder = Path(XML.GEN.L10N)
        tables = sorted(list(folder.glob("*.tsv")) + list(folder.glob("*.csv")))
        self.assertGreaterEqual(len(tables), 8)
        for source in tables:
            if source.stem == "en":
                continue
            with self.subTest(language=source.stem):
                table = self.root / source.name
                table.write_bytes(source.read_bytes())
                before = XML.table_entries(table)
                self.assertGreater(len(before), 100)
                XML.export_xml(table, self.exchange)
                XML.import_xml(self.exchange, table)
                self.assertEqual(before, XML.table_entries(table))

    def test_android_language_alias_and_region_use_standard_xml_tags(self):
        for filename, language in (("in.tsv", "id"), ("pt-rBR.tsv", "pt-BR"),
                                   ("zh-Hans.tsv", "zh-Hans")):
            with self.subTest(filename=filename):
                table = self.root / filename
                table.write_text("Hello\tOlá\n", encoding="utf-8")
                XML.export_xml(table, self.exchange)
                root = ET.parse(self.exchange).getroot()
                self.assertEqual(language, root.find(XML.tag("file")).get("target-language"))
                XML.import_xml(self.exchange, table)
                self.assertEqual({"Hello": "Olá"}, XML.table_entries(table))

    def test_csv_context_columns_quoted_commas_and_carriage_returns_survive(self):
        self.table = self.root / "in.csv"
        self.original = 'source,target,context\n"Hello, %s","Hai, %s",greeting\n"CR\rkey","CR\rvalue",detail\n'
        with self.table.open("w", encoding="utf-8", newline="") as handle:
            handle.write(self.original)
        before = XML.table_entries(self.table)
        XML.export_xml(self.table, self.exchange)
        XML.import_xml(self.exchange, self.table)
        self.assertEqual(before, XML.table_entries(self.table))
        self.assertIn(",greeting", self.table.read_text(encoding="utf-8"))

    def test_missing_added_or_renamed_placeholder_is_rejected(self):
        for value in ("Нет числа", "%2$d результатов", "%1$d %2$s"):
            with self.subTest(value=value):
                def change(root, value=value):
                    unit = next(u for u in self.units(root) if u.find(XML.tag("source")).text == "%1$d results")
                    unit.find(XML.tag("target")).text = value
                self.assert_rejected_without_write(change, "placeholders")

    def test_missing_duplicate_and_changed_source_keys_are_rejected(self):
        def missing(root):
            body = root.find(".//" + XML.tag("body"))
            body.remove(body[0])
        def duplicate(root):
            body = root.find(".//" + XML.tag("body"))
            body.append(ET.fromstring(ET.tostring(body[0])))
        def changed(root):
            self.units(root)[0].find(XML.tag("source")).text = "Changed key"
        for mutate, pattern in ((missing, "source keys differ"), (duplicate, "duplicate"), (changed, "source identity")):
            with self.subTest(mutate=mutate.__name__):
                self.assert_rejected_without_write(mutate, pattern)

    def test_wrong_language_inline_markup_and_blank_translation_are_rejected(self):
        def wrong_language(root):
            root.find(XML.tag("file")).set("target-language", "de")
        def markup(root):
            ET.SubElement(self.units(root)[0].find(XML.tag("target")), XML.tag("ph")).text = "%s"
        def blank(root):
            self.units(root)[0].find(XML.tag("target")).text = ""
        for mutate, pattern in ((wrong_language, "target language"), (markup, "plain-text"), (blank, "empty translation")):
            with self.subTest(mutate=mutate.__name__):
                self.assert_rejected_without_write(mutate, pattern)

    def test_malformed_xml_and_entity_declarations_do_not_replace_the_table(self):
        for document in ("<xliff", '<!DOCTYPE xliff [<!ENTITY x "text">]><xliff/>'):
            with self.subTest(document=document):
                self.exchange.write_text(document, encoding="utf-8")
                with self.assertRaises((ValueError, ET.ParseError)):
                    XML.import_xml(self.exchange, self.table)
                self.assertEqual(self.original, self.table.read_text(encoding="utf-8"))

    def test_cli_failure_returns_nonzero_and_preserves_destination(self):
        self.exchange.write_text("existing export", encoding="utf-8")
        self.table.write_text("Count %d\tНеправильно %s\n", encoding="utf-8")
        result = subprocess.run([sys.executable, str(SCRIPT), "export", str(self.table), str(self.exchange)],
                                capture_output=True, text=True, encoding="utf-8", check=False)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("placeholders", result.stderr)
        self.assertEqual("existing export", self.exchange.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
