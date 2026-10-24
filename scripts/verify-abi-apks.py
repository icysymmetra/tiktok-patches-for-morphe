"""Exercise every patch on complete ABI subsets and reject a changed native library."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ABIS = ("arm64-v8a", "armeabi-v7a")
P2P_LIBRARY = "libavmdlp2pv2.so"


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def make_variant(source: Path, destination: Path, abi: str, *, changed: bool = False) -> dict:
    """Copy ZIP entries without changing retained data, except the explicit negative control."""
    retained = removed = 0
    library = f"lib/{abi}/{P2P_LIBRARY}"
    with zipfile.ZipFile(source) as original:
        entries = original.infolist()
        names = [entry.filename for entry in entries]
        if len(names) != len(set(names)):
            raise ValueError("The source APK has duplicate ZIP paths.")
        if any(f"lib/{candidate}/{P2P_LIBRARY}" not in names for candidate in ABIS):
            raise ValueError("Use a complete universal APK with both reviewed ABIs.")
        with destination.open("xb") as output, zipfile.ZipFile(output, "w") as variant:
            for entry in entries:
                if entry.filename.startswith("lib/"):
                    parts = entry.filename.split("/")
                    if len(parts) > 2 and parts[1] != abi:
                        removed += not entry.is_dir()
                        continue
                    retained += not entry.is_dir()
                data = original.read(entry)
                if changed and entry.filename == library:
                    if not data:
                        raise ValueError("The negative-control library is empty.")
                    data = bytes([data[0] ^ 1]) + data[1:]
                variant.writestr(copy.copy(entry), data)
    return {
        "abi": abi,
        "apk": str(destination),
        "sha256": sha256(destination),
        "retainedNativeFiles": retained,
        "removedNativeFiles": removed,
        "changedLibrary": library if changed else None,
    }


def verify(args: argparse.Namespace, apk: Path, directory: Path, *, changed: bool) -> dict:
    directory.mkdir()
    command = [
        args.pwsh, "-NoProfile", "-File", str(Path(__file__).with_name("verify-all-patches.ps1")),
        "-Apk", str(apk), "-DesktopJar", str(args.desktop_jar),
        "-Bundle", str(args.bundle), "-PatchList", str(args.patch_list),
        "-WorkDir", str(directory),
    ]
    if args.java:
        command.extend(["-Java", args.java])
    if args.aapt2:
        command.extend(["-Aapt2", args.aapt2])
    started = time.monotonic()
    with (directory / "verification.log").open("w", encoding="utf-8") as log:
        completed = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=False)
    reports = list(directory.glob("verify-all-result-*.json"))
    if len(reports) != 1:
        raise ValueError(f"Expected one patching report. Read {directory / 'verification.log'}.")
    report = json.loads(reports[0].read_text(encoding="utf-8-sig"))
    failures = report.get("failedPatches", [])
    if changed:
        expected = (
            len(failures) == 1
            and failures[0].get("patch", {}).get("name") == "Block P2P video relay"
            and "target resources do not match a reviewed TikTok build" in failures[0].get("reason", "")
        )
        passed = completed.returncode != 0 and expected
    else:
        # The invoked gate checks patch inventory, CLI steps, APK output and the resource table.
        passed = completed.returncode == 0 and not failures
    return {
        "passed": passed,
        "exitCode": completed.returncode,
        "seconds": round(time.monotonic() - started, 2),
        "applied": len(report.get("appliedPatches", [])),
        "failed": len(failures),
        "report": str(reports[0]),
        "log": str(directory / "verification.log"),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True, help="A new directory. Existing output is never deleted.")
    parser.add_argument("--desktop-jar", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--patch-list", type=Path, default=Path(__file__).resolve().parents[1] / "patches-list.json")
    parser.add_argument("--pwsh", default="pwsh")
    parser.add_argument("--java")
    parser.add_argument("--aapt2")
    args = parser.parse_args()
    for name in ("apk", "desktop_jar", "bundle", "patch_list"):
        try:
            path = getattr(args, name).resolve(strict=True)
        except OSError as error:
            parser.error(f"{name}: {error}")
        if not path.is_file():
            parser.error(f"{name} is not a file.")
        setattr(args, name, path)
    args.out = args.out.absolute()
    # mkdir without exist_ok also refuses a directory link or a previous verification output.
    try:
        args.out.mkdir(parents=True)
    except OSError as error:
        parser.error(f"out: {error}")
    evidence = {
        "sourceApk": str(args.apk), "sourceSha256": sha256(args.apk),
        "bundle": str(args.bundle), "bundleSha256": sha256(args.bundle),
        "catalogSha256": sha256(args.patch_list), "cases": [], "passed": False,
    }
    try:
        for abi, changed in ((ABIS[0], False), (ABIS[1], False), (ABIS[0], True)):
            label = abi + ("-changed-library" if changed else "")
            apk = args.out / f"{label}.apk"
            case = make_variant(args.apk, apk, abi, changed=changed)
            print(f"[ABI] {label}: verifying real APK", flush=True)
            case.update(verify(args, apk, args.out / label, changed=changed))
            evidence["cases"].append(case)
            print(f"[ABI] {label}: {'passed' if case['passed'] else 'FAILED'}", flush=True)
            if not case["passed"]:
                raise ValueError(f"{label} failed. Read {case['log']}.")
        for name, path in (("sourceSha256", args.apk), ("bundleSha256", args.bundle), ("catalogSha256", args.patch_list)):
            if sha256(path) != evidence[name]:
                raise ValueError("An input changed during verification.")
        evidence["passed"] = True
        return 0
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        evidence["error"] = str(error)
        print(f"[ABI] {error}", file=sys.stderr)
        return 1
    finally:
        (args.out / "abi-verification.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())
