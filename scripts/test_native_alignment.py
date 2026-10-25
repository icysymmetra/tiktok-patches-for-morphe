#!/usr/bin/env python3
"""Exercise ELF and ZIP alignment with valid fixtures and altered negative controls."""

import importlib.util
import io
import json
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
import zlib
from pathlib import Path

SCRIPT = Path(__file__).with_name("verify-native-alignment.py")
SPEC = importlib.util.spec_from_file_location("native_alignment", SCRIPT)
ALIGNMENT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ALIGNMENT)


def elf_image(bits=64, endian="<", segments=None, stride=None):
    """Build small ELF images directly from the published header layouts."""
    if segments is None:
        segments = [(1, 0, 0, 128, 128, 16_384),
                    (1, 0x123, 0x4123, 8, 16, 16_384)]
    header_format = endian + ("HHIIIIIHHHHHH" if bits == 32 else "HHIQQQIHHHHHH")
    program_format = endian + ("IIIIIIII" if bits == 32 else "IIQQQQQQ")
    header_size = 16 + struct.calcsize(header_format)
    program_size = struct.calcsize(program_format)
    stride = stride or program_size
    ident = b"\x7fELF" + bytes([1 if bits == 32 else 2, 1 if endian == "<" else 2, 1]) + bytes(9)
    image = bytearray(ident + struct.pack(header_format, 3, 40 if bits == 32 else 183,
                                        1, 0, header_size, 0, 0, header_size,
                                        stride, len(segments), 0, 0, 0))
    for kind, offset, vaddr, filesz, memsz, align, *permissions in segments:
        flags = permissions[0] if permissions else 5
        fields = ((kind, offset, vaddr, 0, filesz, memsz, flags, align) if bits == 32
                  else (kind, flags, offset, vaddr, 0, filesz, memsz, align))
        image.extend(struct.pack(program_format, *fields) + bytes(stride - program_size))
    required_size = max([len(image)] + [segment[1] + segment[3] for segment in segments])
    image.extend(bytes(required_size - len(image)))
    return bytes(image)


def apk_image(elf, *, stored=True, aligned=True, central_extra=b""):
    """Construct local and central ZIP records independently, including local-only padding."""
    name = b"lib/arm64-v8a/libtest.so"
    method = 0 if stored else 8
    compressor = zlib.compressobj(wbits=-15)
    payload = elf if stored else compressor.compress(elf) + compressor.flush()
    padding = (-(30 + len(name))) % 16_384 if aligned else 0
    extra = struct.pack("<HH", 0xCAFE, padding - 4) + bytes(padding - 4) if padding else b""
    crc = zlib.crc32(elf)
    local = struct.pack("<I5H3I2H", 0x04034B50, 20, 0, method, 0, 0,
                        crc, len(payload), len(elf), len(name), len(extra)) + name + extra
    central = struct.pack("<I6H3I5H2I", 0x02014B50, 20, 20, 0, method, 0, 0,
                          crc, len(payload), len(elf), len(name), len(central_extra),
                          0, 0, 0, 0, 0) + name + central_extra
    end = struct.pack("<I4H2IH", 0x06054B50, 0, 0, 1, 1, len(central),
                      len(local) + len(payload), 0)
    return local + payload + central + end


class NativeAlignmentTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.apk = Path(temporary.name) / "fixture.apk"

    def inspect(self, image):
        self.apk.write_bytes(image)
        return ALIGNMENT.inspect_apk(self.apk)

    def inspect_elf(self, image):
        return ALIGNMENT.inspect_elf(io.BytesIO(image), len(image))

    def test_elf32_and_elf64_both_byte_orders_allow_congruent_nonzero_offsets(self):
        for bits in (32, 64):
            for endian in ("<", ">"):
                with self.subTest(bits=bits, endian=endian):
                    report = self.inspect_elf(elf_image(bits, endian))
                    self.assertTrue(report["passed"], report)
                    self.assertEqual(bits, report["elf_class"])
                    self.assertEqual(2, len(report["load_segments"]))
                    self.assertEqual(0x123, report["load_segments"][1]["offset"])

    def test_every_load_segment_is_checked_and_small_or_nonpower_alignment_fails(self):
        for alignment in (0, 1, 4_096, 8_192, 24_576):
            with self.subTest(alignment=alignment):
                image = elf_image(segments=[(1, 0, 0, 128, 128, 16_384),
                                            (1, 0x123, 0x123, 8, 8, alignment)])
                report = self.inspect_elf(image)
                self.assertFalse(report["passed"])
                self.assertEqual([], report["load_segments"][0]["errors"])
                self.assertTrue(report["load_segments"][1]["errors"])

    def test_congruence_uses_declared_alignment_not_only_16kb(self):
        # Both addresses have the same 16 KB residue, but different 64 KB residues.
        image = elf_image(segments=[(1, 0x123, 0x4123, 8, 8, 65_536)])
        report = self.inspect_elf(image)
        self.assertFalse(report["passed"])
        self.assertIn("congruent", " ".join(report["errors"]))
        self.assertTrue(self.inspect_elf(elf_image(segments=[(1, 0x123, 0x10123, 8, 8, 65_536)]))["passed"])

    def test_truncated_and_malformed_identification_or_headers_fail(self):
        good = elf_image()
        mutations = [good[:15], good[:63], b"NOPE" + good[4:]]
        for offset, value in ((4, 3), (5, 3), (6, 0)):
            changed = bytearray(good)
            changed[offset] = value
            mutations.append(bytes(changed))
        for offset, format_string, value in ((20, "<I", 0), (52, "<H", 63),
                                             (54, "<H", 55), (32, "<Q", 1),
                                             (56, "<H", 0xFFFF)):
            changed = bytearray(good)
            struct.pack_into(format_string, changed, offset, value)
            mutations.append(bytes(changed))
        mutations.append(good[:100])
        for index, image in enumerate(mutations):
            with self.subTest(index=index):
                self.assertFalse(self.inspect_elf(image)["passed"])

    def test_no_program_headers_or_only_note_headers_fail(self):
        for segments in ([], [(4, 0, 0, 0, 0, 1)]):
            with self.subTest(segments=segments):
                report = self.inspect_elf(elf_image(segments=segments))
                self.assertFalse(report["passed"])
                self.assertIn("No PT_LOAD", " ".join(report["errors"]))

    def test_segment_file_range_and_memory_size_are_checked(self):
        too_small_memory = elf_image(segments=[(1, 0, 0, 128, 127, 16_384)])
        self.assertIn("p_filesz exceeds", " ".join(self.inspect_elf(too_small_memory)["errors"]))
        truncated = elf_image()[:-1]
        self.assertIn("file range exceeds", " ".join(self.inspect_elf(truncated)["errors"]))

    def test_larger_header_stride_is_respected(self):
        self.assertTrue(self.inspect_elf(elf_image(stride=64))["passed"])

    def test_relro_end_alignment_and_dedicated_load_in_all_elf_formats(self):
        for bits in (32, 64):
            for endian in ("<", ">"):
                for case, relro_size, load_size, passed in (
                    ("aligned", 0x3980, 0x4000, True),
                    ("unsafe-shared", 0x980, 0x4000, False),
                    ("safe-dedicated", 0x980, 0x980, True),
                ):
                    with self.subTest(bits=bits, endian=endian, case=case):
                        # The dedicated layout matches the Android guidance example.
                        image = elf_image(bits, endian, segments=[
                            (1, 0, 0, 0x680, 0x680, 0x4000, 5),
                            (1, 0x680, 0x4680, 0x1D8, load_size, 0x4000, 6),
                            (0x6474E552, 0x680, 0x4680, 0x1D8, relro_size, 1, 4),
                        ])
                        report = self.inspect_elf(image)
                        self.assertEqual(passed, report["passed"], report)
                        self.assertEqual([], report["load_segments"][1]["errors"])
                        self.assertEqual(6, report["load_segments"][1]["flags"])
                        relro = report["relro_segments"][0]
                        self.assertEqual(2, relro["index"])
                        self.assertEqual(0x680, relro["offset"])
                        self.assertEqual(0x1D8, relro["filesz"])
                        self.assertEqual(0x4000, relro["protected_start"])
                        self.assertEqual(0x8000, relro["protected_end"])
                        self.assertEqual([1] if case == "safe-dedicated" else [],
                                         relro["dedicated_load_segments"])
                        if not passed:
                            self.assertEqual([{"load_index": 1, "start": 0x5000, "end": 0x8000}],
                                             relro["writable_overlaps"])
                            self.assertIn("PT_GNU_RELRO[2]", " ".join(report["errors"]))
                            self.assertIn("PT_LOAD[1]", " ".join(report["errors"]))

    def test_dedicated_relro_still_checks_other_writable_loads_on_the_end_page(self):
        for address, passed in ((0x5000, False), (0x7FFF, False), (0x8000, True)):
            with self.subTest(address=address):
                report = self.inspect_elf(elf_image(segments=[
                    (1, 0x680, 0x4680, 0x1D8, 0x980, 0x4000, 6),
                    (0x6474E552, 0x680, 0x4680, 0x1D8, 0x980, 1, 4),
                    (1, address % 0x4000, address, 1, 1, 0x4000, 6),
                ]))
                self.assertEqual(passed, report["passed"], report)
                self.assertEqual([0], report["relro_segments"][0]["dedicated_load_segments"])
                if not passed:
                    self.assertEqual([{"load_index": 2, "start": address, "end": address + 1}],
                                     report["relro_segments"][0]["writable_overlaps"])

    def test_relro_ignores_readonly_tail_but_checks_writable_zero_fill(self):
        for flags, passed in ((4, True), (5, True), (6, False), (7, False)):
            with self.subTest(flags=flags):
                report = self.inspect_elf(elf_image(segments=[
                    (0x6474E552, 0x680, 0x4680, 0x1D8, 0x980, 1, 4),
                    (1, 0x680, 0x4680, 0x1D8, 0x980, 0x4000, 6),
                    (1, 0x2000, 0x6000, 0, 0x100, 0x4000, flags),
                ]))
                self.assertEqual(passed, report["passed"], report)

    def test_relro_checks_writable_data_before_its_unaligned_start(self):
        report = self.inspect_elf(elf_image(segments=[
            (1, 0, 0x4000, 0x680, 0x4000, 0x4000, 6),
            (0x6474E552, 0x680, 0x4680, 0, 0x3980, 1, 4),
        ]))
        self.assertFalse(report["passed"], report)
        self.assertEqual([{"load_index": 0, "start": 0x4000, "end": 0x4680}],
                         report["relro_segments"][0]["writable_overlaps"])

    def test_multiple_relro_ranges_are_not_treated_as_writable_data(self):
        report = self.inspect_elf(elf_image(segments=[
            (1, 0x680, 0x4680, 0x1D8, 0x980, 0x4000, 6),
            (0x6474E552, 0x680, 0x4680, 0x1D8, 0x480, 1, 4),
            (0x6474E552, 0x858, 0x4B00, 0, 0x500, 1, 4),
        ]))
        self.assertTrue(report["passed"], report)
        self.assertEqual(2, len(report["relro_segments"]))
        self.assertTrue(all(not segment["writable_overlaps"] for segment in report["relro_segments"]))

    def test_absent_relro_does_not_invent_an_alignment_requirement(self):
        report = self.inspect_elf(elf_image(segments=[
            (1, 0x680, 0x4680, 0x1D8, 0x980, 0x4000, 6),
        ]))
        self.assertTrue(report["passed"], report)
        self.assertEqual([], report["relro_segments"])

    def test_relro_address_overflow_and_truncated_file_range_are_reported(self):
        for bits in (32, 64):
            with self.subTest(bits=bits):
                report = self.inspect_elf(elf_image(bits, segments=[
                    (1, 0, 0, 128, 128, 0x4000),
                    (0x6474E552, 0, (1 << bits) - 1, 0, 2, 1, 4),
                ]))
                self.assertFalse(report["passed"], report)
                self.assertIn("virtual address range", " ".join(report["errors"]))
        image = elf_image(segments=[
            (1, 0, 0, 128, 128, 0x4000),
            (0x6474E552, 0x100, 0x100, 8, 8, 1, 4),
        ])[:-1]
        report = self.inspect_elf(image)
        self.assertFalse(report["passed"], report)
        self.assertIn("PT_GNU_RELRO[1]: PT_GNU_RELRO file range exceeds", " ".join(report["errors"]))

    def test_relro_hazards_fail_cli_with_library_and_range_diagnostics(self):
        self.apk.write_bytes(apk_image(elf_image(segments=[
            (1, 0x680, 0x4680, 0x1D8, 0x4000, 0x4000, 6),
            (0x6474E552, 0x680, 0x4680, 0x1D8, 0x980, 1, 4),
        ]), stored=False, aligned=False))
        for arguments in ([], ["--json"]):
            with self.subTest(arguments=arguments):
                completed = subprocess.run([sys.executable, str(SCRIPT), str(self.apk), *arguments],
                                           capture_output=True, text=True, timeout=15, check=False)
                self.assertEqual(1, completed.returncode, completed.stdout + completed.stderr)
                self.assertIn("lib/arm64-v8a/libtest.so", completed.stdout)
                self.assertIn("PT_LOAD[0]", completed.stdout)
                self.assertIn("PT_GNU_RELRO[1]", completed.stdout)
                self.assertIn("0x5000", completed.stdout)
                self.assertIn("0x8000", completed.stdout)
                if arguments:
                    report = json.loads(completed.stdout)
                    self.assertFalse(report["passed"])
                    self.assertIsNone(report["libraries"][0]["zip_aligned"])

    def test_metadata_only_reads_are_bounded_even_with_a_huge_declared_offset(self):
        class GuardedStream(io.BytesIO):
            count = 0

            def read(self, count=-1):
                if count < 0 or count > 512 or self.count + count > 512:
                    raise AssertionError("Unbounded or segment-body read")
                self.count += count
                return super().read(count)

        good = elf_image() + bytes(1024 * 1024)
        stream = GuardedStream(good)
        self.assertTrue(ALIGNMENT.inspect_elf(stream, len(good))["passed"])
        self.assertLess(stream.count, 512)
        changed = bytearray(good[:64])
        struct.pack_into("<Q", changed, 32, 1 << 40)
        stream = GuardedStream(changed)
        report = ALIGNMENT.inspect_elf(stream, 1 << 50)
        self.assertFalse(report["passed"])
        self.assertIn("inspection limit", " ".join(report["errors"]))
        self.assertEqual(64, stream.count)

    def test_stored_zip_uses_actual_local_extra_not_central_extra(self):
        report = self.inspect(apk_image(elf_image(), central_extra=b""))
        self.assertTrue(report["passed"], report)
        library = report["libraries"][0]
        self.assertEqual(16_384, library["zip_data_offset"])
        self.assertTrue(library["zip_aligned"])
        with zipfile.ZipFile(self.apk) as archive:
            entry = archive.infolist()[0]
            self.assertEqual(0, entry.header_offset)
            self.assertEqual(b"", entry.extra)
            self.assertNotEqual(0, (entry.header_offset + 30 + len(entry.filename)) % 16_384)

    def test_stored_zip_with_valid_elf_but_unaligned_payload_fails(self):
        report = self.inspect(apk_image(elf_image(), aligned=False))
        self.assertFalse(report["passed"])
        self.assertFalse(report["libraries"][0]["zip_aligned"])
        self.assertEqual(2, len(report["libraries"][0]["load_segments"]))
        self.assertIn("Stored ZIP data offset", " ".join(report["libraries"][0]["errors"]))

    def test_compressed_zip_skips_only_zip_alignment_not_elf_checks(self):
        report = self.inspect(apk_image(elf_image(), stored=False, aligned=False))
        self.assertTrue(report["passed"], report)
        self.assertIsNone(report["libraries"][0]["zip_aligned"])
        bad = elf_image(segments=[(1, 0, 0, 128, 128, 4_096)])
        report = self.inspect(apk_image(bad, stored=False, aligned=False))
        self.assertFalse(report["passed"])
        self.assertIn("p_align=4096", " ".join(report["libraries"][0]["errors"]))

    def test_bad_local_signature_extra_length_and_method_are_reported(self):
        good = apk_image(elf_image(), stored=False, aligned=False)
        for offset, format_string, value in ((0, "<I", 0), (28, "<H", 65_535), (8, "<H", 0)):
            with self.subTest(offset=offset):
                changed = bytearray(good)
                struct.pack_into(format_string, changed, offset, value)
                report = self.inspect(changed)
                self.assertFalse(report["passed"])
                self.assertTrue(report["libraries"][0]["errors"])

    def test_damaged_compressed_library_is_a_reported_failure(self):
        changed = bytearray(apk_image(elf_image(), stored=False, aligned=False))
        compressed_size = struct.unpack_from("<I", changed, 18)[0]
        name_length, extra_length = struct.unpack_from("<HH", changed, 26)
        offset = 30 + name_length + extra_length
        changed[offset:offset + compressed_size] = b"\xff" * compressed_size
        report = self.inspect(changed)
        self.assertFalse(report["passed"])
        self.assertTrue(report["libraries"][0]["errors"])

    def test_absent_load_segments_and_invalid_apk_fail_through_cli_without_content(self):
        self.apk.write_bytes(apk_image(elf_image(segments=[(4, 0, 0, 0, 0, 1)])))
        completed = subprocess.run([sys.executable, str(SCRIPT), str(self.apk), "--json"],
                                   capture_output=True, text=True, timeout=15, check=False)
        self.assertEqual(1, completed.returncode)
        report = json.loads(completed.stdout)
        self.assertFalse(report["passed"])
        self.assertIn("No PT_LOAD", " ".join(report["libraries"][0]["errors"]))
        self.apk.write_bytes(b"private unrelated asset content")
        completed = subprocess.run([sys.executable, str(SCRIPT), str(self.apk)],
                                   capture_output=True, text=True, timeout=15, check=False)
        self.assertEqual(1, completed.returncode)
        self.assertNotIn("private unrelated", completed.stdout + completed.stderr)

    def test_cli_success_and_explicit_path_requirement(self):
        self.apk.write_bytes(apk_image(elf_image()))
        completed = subprocess.run([sys.executable, str(SCRIPT), str(self.apk)],
                                   capture_output=True, text=True, timeout=15, check=False)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertIn("p_align=16384", completed.stdout)
        self.assertIn("1 native libraries, 0 failed", completed.stdout)
        completed = subprocess.run([sys.executable, str(SCRIPT)],
                                   capture_output=True, text=True, timeout=15, check=False)
        self.assertEqual(2, completed.returncode)


if __name__ == "__main__":
    unittest.main()
