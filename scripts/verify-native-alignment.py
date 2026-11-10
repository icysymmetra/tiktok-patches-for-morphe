#!/usr/bin/env python3
"""Check 16 KB ELF load/RELRO safety and stored .so ZIP offsets in one explicit APK.

Only ELF metadata is read, with an 8 MiB prefix limit per library. This checks
static alignment, not complete ELF/ZIP integrity or Android runtime behavior.
Rules: https://developer.android.com/guide/practices/page-sizes
       https://gabi.xinuos.com/elf/07-pheader.html
       https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT (4.3.7)
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import zipfile
import zlib
from pathlib import Path
from typing import BinaryIO

PAGE_SIZE = 16_384
MAX_METADATA_BYTES = 8 * 1024 * 1024
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


class MetadataReader:
    """Read each prefix byte at most once, including from compressed ZIP members."""

    def __init__(self, stream: BinaryIO, size: int):
        self.stream = stream
        self.size = size
        self.prefix = bytearray()

    def read(self, offset: int, count: int) -> bytes:
        end = offset + count
        if offset < 0 or count < 0 or end > self.size:
            raise ValueError("Truncated ELF metadata: requested range exceeds library size.")
        if end > MAX_METADATA_BYTES:
            raise ValueError("ELF metadata exceeds the 8 MiB inspection limit.")
        if end > len(self.prefix):
            data = self.stream.read(end - len(self.prefix))
            self.prefix.extend(data)
            if len(self.prefix) < end:
                raise ValueError("Truncated ELF metadata: library ended during a read.")
        return bytes(self.prefix[offset:end])


def inspect_elf(stream: BinaryIO, size: int) -> dict:
    """Inspect ELF32/ELF64 headers without reading segment bodies."""
    report = {"elf_class": None, "byte_order": None, "load_segments": [],
              "relro_segments": [], "errors": []}
    reader = MetadataReader(stream, size)
    try:
        ident = reader.read(0, 16)
        if ident[:4] != b"\x7fELF":
            raise ValueError("Missing ELF magic.")
        if ident[4] not in (1, 2) or ident[5] not in (1, 2):
            raise ValueError("Unsupported ELF class or byte order.")
        if ident[6] != 1:
            raise ValueError("Unsupported ELF identification version.")
        bits = 32 if ident[4] == 1 else 64
        endian = "<" if ident[5] == 1 else ">"
        report.update(elf_class=bits, byte_order="little" if endian == "<" else "big")
        header_format = endian + ("HHIIIIIHHHHHH" if bits == 32 else "HHIQQQIHHHHHH")
        header_size = 16 + struct.calcsize(header_format)
        header = struct.unpack(header_format, reader.read(16, header_size - 16))
        if header[2] != 1:
            raise ValueError("Unsupported ELF header version.")
        phoff, ehsize, phentsize, phnum = header[4], header[7], header[8], header[9]
        if ehsize < header_size or ehsize > size:
            raise ValueError("Invalid ELF header size.")
        if phnum == 0xFFFF:
            raise ValueError("Extended ELF program-header counts are not supported.")
        if phnum == 0:
            raise ValueError("No PT_LOAD segments.")
        ph_format = endian + ("IIIIIIII" if bits == 32 else "IIQQQQQQ")
        ph_size = struct.calcsize(ph_format)
        if phentsize < ph_size or phoff < ehsize:
            raise ValueError("Invalid ELF program-header size or offset.")
        table = reader.read(phoff, phentsize * phnum)
        for index in range(phnum):
            values = struct.unpack_from(ph_format, table, index * phentsize)
            if values[0] not in (PT_LOAD, PT_GNU_RELRO):
                continue
            if bits == 32:
                kind, offset, vaddr, _, filesz, memsz, flags, align = values
            else:
                kind, flags, offset, vaddr, _, filesz, memsz, align = values
            label = "PT_LOAD" if kind == PT_LOAD else "PT_GNU_RELRO"
            errors = []
            if kind == PT_LOAD:
                if align < PAGE_SIZE:
                    errors.append(f"p_align={align} is below {PAGE_SIZE}.")
                if align and align & (align - 1):
                    errors.append(f"p_align={align} is not a power of two.")
                if align > 1 and offset % align != vaddr % align:
                    errors.append("p_offset and p_vaddr are not congruent modulo p_align.")
                if filesz > memsz:
                    errors.append("p_filesz exceeds p_memsz.")
            if offset > size or filesz > size - offset:
                errors.append(f"{label} file range exceeds library size.")
            if vaddr + memsz > 1 << bits:
                errors.append(f"{label} virtual address range exceeds ELF{bits} address space.")
            segment = {"index": index, "offset": offset, "vaddr": vaddr,
                       "filesz": filesz, "memsz": memsz, "align": align,
                       "flags": flags, "errors": errors}
            report["load_segments" if kind == PT_LOAD else "relro_segments"].append(segment)
            report["errors"].extend(f"{label}[{index}]: {error}" for error in errors)
        if not report["load_segments"]:
            report["errors"].append("No PT_LOAD segments.")
        relro_ranges = sorted((segment["vaddr"], segment["vaddr"] + segment["memsz"])
                              for segment in report["relro_segments"] if not segment["errors"])
        for relro in report["relro_segments"]:
            start, end = relro["vaddr"], relro["vaddr"] + relro["memsz"]
            page_start = start // PAGE_SIZE * PAGE_SIZE
            page_end = (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
            relro.update(protected_start=page_start, protected_end=page_end,
                         dedicated_load_segments=[], writable_overlaps=[])
            if relro["errors"]:
                continue
            # Android protects every touched page. Exclude declared RELRO bytes,
            # including other RELRO headers, before looking for writable data.
            extra_ranges = []
            cursor = page_start
            for covered_start, covered_end in relro_ranges:
                if covered_end <= cursor:
                    continue
                if covered_start >= page_end:
                    break
                if covered_start > cursor:
                    extra_ranges.append((cursor, covered_start))
                cursor = max(cursor, covered_end)
            if cursor < page_end:
                extra_ranges.append((cursor, page_end))
            for load in report["load_segments"]:
                load_start, load_end = load["vaddr"], load["vaddr"] + load["memsz"]
                if load_start == start and load_end == end:
                    relro["dedicated_load_segments"].append(load["index"])
                if not load["flags"] & PF_W:
                    continue
                for extra_start, extra_end in extra_ranges:
                    overlap_start = max(extra_start, load_start)
                    overlap_end = min(extra_end, load_end)
                    if overlap_start >= overlap_end:
                        continue
                    relro["writable_overlaps"].append({"load_index": load["index"],
                                                      "start": overlap_start, "end": overlap_end})
                    error = (f"16 KB RELRO protection overlaps writable PT_LOAD[{load['index']}] "
                             f"at [0x{overlap_start:x}, 0x{overlap_end:x}). "
                             "Rebuild the library with 16 KB RELRO layout support.")
                    relro["errors"].append(error)
                    report["errors"].append(f"PT_GNU_RELRO[{relro['index']}]: {error}")
    except (ValueError, struct.error) as error:
        report["errors"].append(str(error))
    report["metadata_prefix_bytes"] = len(reader.prefix)
    report["passed"] = not report["errors"]
    return report


def local_data_offset(raw: BinaryIO, entry: zipfile.ZipInfo, apk_size: int) -> int:
    """Use local filename/extra lengths, which need not match the central extra."""
    raw.seek(entry.header_offset)
    header = raw.read(30)
    if len(header) != 30 or header[:4] != b"PK\x03\x04":
        raise ValueError("Missing or truncated ZIP local header.")
    flags, method = struct.unpack_from("<HH", header, 6)
    if flags & 1 or entry.flag_bits & 1:
        raise ValueError("Encrypted native library cannot be inspected.")
    if method != entry.compress_type:
        raise ValueError("ZIP local and central compression methods differ.")
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    variable_length = name_length + extra_length
    offset = entry.header_offset + 30 + variable_length
    if offset > apk_size or len(raw.read(variable_length)) != variable_length:
        raise ValueError("Truncated ZIP local filename or extra field.")
    if entry.compress_size > apk_size - offset:
        raise ValueError("ZIP library data exceeds APK size.")
    if method == zipfile.ZIP_STORED and entry.file_size != entry.compress_size:
        raise ValueError("Stored ZIP library has inconsistent sizes.")
    return offset


def inspect_apk(apk: Path) -> dict:
    """Return per-library metadata and failures, without extraction or modification."""
    report = {"apk": str(apk), "page_size": PAGE_SIZE, "libraries": [], "errors": []}
    try:
        with apk.open("rb") as raw, zipfile.ZipFile(raw) as archive:
            apk_size = os.fstat(raw.fileno()).st_size
            seen = set()
            for entry in archive.infolist():
                if entry.is_dir() or not entry.filename.endswith(".so"):
                    continue
                library = {"name": entry.filename, "compression": entry.compress_type,
                           "zip_data_offset": None, "zip_aligned": None, "errors": []}
                try:
                    if entry.filename in seen:
                        raise ValueError("Duplicate native-library ZIP path.")
                    seen.add(entry.filename)
                    offset = local_data_offset(raw, entry, apk_size)
                    library["zip_data_offset"] = offset
                    if entry.compress_type == zipfile.ZIP_STORED:
                        library["zip_aligned"] = offset % PAGE_SIZE == 0
                        if not library["zip_aligned"]:
                            library["errors"].append(f"Stored ZIP data offset {offset} is not {PAGE_SIZE}-aligned.")
                    with archive.open(entry) as stream:
                        elf = inspect_elf(stream, entry.file_size)
                    library.update({key: value for key, value in elf.items() if key not in ("errors", "passed")})
                    library["errors"].extend(elf["errors"])
                except (OSError, ValueError, EOFError, RuntimeError, NotImplementedError,
                        zipfile.BadZipFile, zlib.error) as error:
                    library["errors"].append(str(error))
                library["passed"] = not library["errors"]
                report["libraries"].append(library)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        report["errors"].append(str(error))
    report["passed"] = not report["errors"] and all(item["passed"] for item in report["libraries"])
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path, help="Explicit APK to inspect. No files are extracted or changed.")
    parser.add_argument("--json", action="store_true", help="Print the complete per-library report as JSON.")
    args = parser.parse_args(argv)
    report = inspect_apk(args.apk)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        for library in report["libraries"]:
            status = "PASS" if library["passed"] else "FAIL"
            name = json.dumps(library["name"])
            packaging = ("stored" if library["compression"] == zipfile.ZIP_STORED
                         else f"compressed(method={library['compression']}, ZIP alignment not required)")
            print(f"{status} {name}: {packaging}, data_offset={library['zip_data_offset']}")
            for segment in library.get("load_segments", []):
                print(f"  PT_LOAD[{segment['index']}] off=0x{segment['offset']:x} "
                      f"vaddr=0x{segment['vaddr']:x} filesz=0x{segment['filesz']:x} "
                      f"memsz=0x{segment['memsz']:x} flags=0x{segment['flags']:x} "
                      f"p_align={segment['align']}")
            for segment in library.get("relro_segments", []):
                print(f"  PT_GNU_RELRO[{segment['index']}] off=0x{segment['offset']:x} "
                      f"vaddr=0x{segment['vaddr']:x} filesz=0x{segment['filesz']:x} "
                      f"memsz=0x{segment['memsz']:x} "
                      f"protect=[0x{segment['protected_start']:x}, 0x{segment['protected_end']:x}) "
                      f"dedicated_loads={segment['dedicated_load_segments']}")
            for error in library["errors"]:
                print(f"  {error}")
        for error in report["errors"]:
            print(f"FAIL: {error}")
        count = len(report["libraries"])
        failed = sum(not item["passed"] for item in report["libraries"])
        print(f"{'PASS' if report['passed'] else 'FAIL'}: {count} native libraries, {failed} failed.")
        print("Static ELF/ZIP alignment inspection. Android runtime acceptance is separate.")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
