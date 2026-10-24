#!/usr/bin/env python3
"""Prepare and check isolated Manager index previews without network or git writes.

The output is local metadata, not publication or patching acceptance. The existing
release and pre-push gates remain authoritative for any later publication.
"""

import argparse
import json
import os
import re
import sys
import tempfile
import zipfile
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

# Contract checked at MorpheApp/morphe-manager da8545c92c94b83e23adf7b573b46b282fa2b062:
# network/dto/MorpheAsset.kt, domain/bundles/RemotePatchBundle.kt, util/VersionUtils.kt.
REPOSITORY = "SysAdminDoc/HushFeed"
LIMIT = 65_536
VERSION = re.compile(r"(\d+)\.(\d+)\.(\d+)(?:-(dev|alpha|beta|rc|preview)\.(\d+))?\Z")
REQUIRED = {"download_url", "created_at", "description", "version"}
OPTIONAL = {"signature_download_url", "page_url"}


def read_text(path: Path) -> str:
    with path.open("rb") as stream:
        data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError(f"Metadata exceeds {LIMIT} bytes: {path.name}")
    return data.decode("utf-8")


def unique_fields(pairs):
    result = {}
    for name, value in pairs:
        if name in result:
            raise ValueError(f"Duplicate JSON field: {name}")
        result[name] = value
    return result


def read_index(path: Path) -> dict:
    value = json.loads(read_text(path), object_pairs_hook=unique_fields)
    if not isinstance(value, dict):
        raise TypeError("An index must be a JSON object.")
    return value


def version_key(version: str) -> tuple:
    """Manager ordering for the project's three-part versions and numbered suffixes."""
    match = VERSION.fullmatch(version)
    if not match:
        raise ValueError(f"Unsupported index version: {version}")
    core = tuple(int(match[i]) for i in range(1, 4))
    number = int(match[5] or 0)
    if max(core) > 2_147_483_647 or number > 9_223_372_036_854_775_807:
        raise ValueError("Unsupported version component outside Manager's numeric range.")
    # Manager compares any two numbered prerelease labels by their trailing number.
    return core + (int(match[4] is None), number)


def validate_repository(repository: str) -> None:
    parts = repository.split("/")
    if (len(parts) != 2 or not re.fullmatch(r"[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*", parts[0]) or
            not re.fullmatch(r"[A-Za-z0-9_.-]+", parts[1]) or parts[1] in {".", ".."}):
        raise ValueError("The repository must contain a valid GitHub owner and repository name.")


def source_endpoint(repository: str, branch: str) -> str:
    if branch not in {"main", "dev"}:
        raise ValueError("The source branch must be main or dev.")
    validate_repository(repository)
    return f"https://raw.githubusercontent.com/{repository}/{branch}/patches-bundle.json"


def release_url(repository: str, version: str) -> str:
    validate_repository(repository)
    return f"https://github.com/{repository}/releases/download/v{version}/patches-{version}.mpp"


def https_url(value: str, field: str):
    # urlsplit silently strips leading C0 characters and embedded tabs/newlines.
    if any(ord(char) < 33 or 127 <= ord(char) <= 159 or char.isspace() for char in value) or "\\" in value:
        raise ValueError(f"{field} must not contain whitespace or control characters.")
    try:
        parsed = urlsplit(value)
        _ = parsed.port  # Evaluating the property rejects nonnumeric and out-of-range ports.
    except ValueError as error:
        raise ValueError(f"{field} has an invalid URL authority.") from error
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password or
            parsed.netloc.endswith(":")):
        raise ValueError(f"{field} must use an HTTPS URL without credentials and with a valid authority.")
    return parsed


def validate_index(index: dict, branch: str, repository: str = REPOSITORY) -> None:
    source_endpoint(repository, branch)
    if REQUIRED - index.keys() or index.keys() - REQUIRED - OPTIONAL:
        raise ValueError("The index must contain only Manager's required and optional asset fields.")
    if any(not isinstance(index[name], str) or not index[name].strip() for name in REQUIRED):
        raise ValueError("Required Manager fields must be nonempty strings.")
    version_key(index["version"])
    # Existing stable release-facts validation permits only X.Y.Z on main. Dev's
    # endpoint can legitimately contain either that form or a numbered prerelease.
    if branch == "main" and "-" in index["version"]:
        raise ValueError("The main index must keep a stable version.")
    created = index["created_at"]
    if not re.fullmatch(r"\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d", created):
        raise ValueError("created_at must be yyyy-MM-ddTHH:mm:ss without a timezone suffix.")
    datetime.strptime(created, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=UTC)
    expected_url = urlsplit(release_url(repository, index["version"]))
    actual_url = https_url(index["download_url"], "download_url")
    expected_parts, actual_parts = expected_url.path.split("/"), actual_url.path.split("/")
    if (actual_url.scheme != "https" or actual_url.netloc != expected_url.netloc or
            [part.casefold() for part in actual_parts[:3]] != [part.casefold() for part in expected_parts[:3]] or
            actual_parts[3:] != expected_parts[3:] or
            actual_url.query or actual_url.fragment or
            len(actual_parts) != len(expected_parts)):
        raise ValueError("download_url must name this repository's same-version tag and bundle.")
    for name in OPTIONAL & index.keys():
        value = index[name]
        if value is not None and not isinstance(value, str):
            raise ValueError(f"{name} must be a string or null.")
        if value:
            https_url(value, name)


def bundle_version(path: Path) -> str:
    with zipfile.ZipFile(path) as archive:
        entries = [entry for entry in archive.infolist() if entry.filename == "META-INF/MANIFEST.MF"]
        if len(entries) != 1 or entries[0].file_size > LIMIT:
            raise ValueError("The bundle must have one bounded META-INF/MANIFEST.MF.")
        with archive.open(entries[0]) as stream:
            data = stream.read(LIMIT + 1)
    if len(data) > LIMIT:
        raise ValueError("The bundle manifest exceeds the metadata limit.")
    manifest = data.decode("utf-8").replace("\r\n", "\n")
    main_section = manifest.split("\n\n", 1)[0]
    main_section = main_section.replace("\n ", "")
    versions = re.findall(r"^Version: ([^\n]+)$", main_section, re.MULTILINE | re.IGNORECASE)
    if len(versions) != 1:
        raise ValueError("The bundle must declare exactly one main manifest Version.")
    version = versions[0]
    version_key(version)
    if path.name != f"patches-{version}.mpp":
        raise ValueError("The bundle filename must match its manifest version.")
    return version


def validate_bundle(index: dict, bundle: Path) -> None:
    if bundle_version(bundle) != index["version"]:
        raise ValueError("The index version differs from the actual bundle manifest version.")


def selected_branch(stable: dict, dev: dict, repository: str = REPOSITORY) -> str:
    validate_index(stable, "main", repository)
    validate_index(dev, "dev", repository)
    # JsonPatchBundle.getLatestInfo chooses dev on equal versions, regardless of date.
    return "dev" if version_key(dev["version"]) >= version_key(stable["version"]) else "main"


def identity(path_or_descriptor):
    """Return filesystem identity only when it can be checked without following links."""
    try:
        details = os.fstat(path_or_descriptor) if isinstance(path_or_descriptor, int) else path_or_descriptor.lstat()
        return details.st_dev, details.st_ino
    except OSError:
        return None


def rename_preview(temporary: Path, final: Path) -> None:
    if os.name == "nt":
        os.rename(temporary, final)  # Windows rename refuses to replace an existing target.
    else:
        # POSIX rename replaces existing files; exclusive linking preserves concurrent content.
        os.link(temporary, final)
        temporary.unlink()


def write_preview(payload: bytes, output: Path) -> Path:
    output.mkdir(parents=True, exist_ok=False)
    directory_id = identity(output)
    final = output / "patches-bundle.json"
    descriptor = temporary = file_id = None
    complete = False
    try:
        if directory_id is None:
            raise OSError("Cannot identify the newly created preview directory.")
        descriptor, temporary_name = tempfile.mkstemp(prefix=".patches-bundle-", suffix=".tmp", dir=output)
        temporary = Path(temporary_name)
        file_id = identity(descriptor)
        if file_id is None:
            raise OSError("Cannot identify the temporary preview file.")
        remaining = memoryview(payload)
        while remaining:
            count = os.write(descriptor, remaining)
            if count <= 0:
                raise OSError("The temporary preview write made no progress.")
            remaining = remaining[count:]
        os.fsync(descriptor)
        os.close(descriptor)
        descriptor = None
        if identity(output) != directory_id or identity(temporary) != file_id:
            raise OSError("Preview path ownership changed during writing.")
        rename_preview(temporary, final)
        complete = True
        return final
    finally:
        # A close may fail before or after closing. Never close a descriptor reused for another file.
        if descriptor is not None and (file_id is None or identity(descriptor) == file_id):
            try:
                os.close(descriptor)
            except OSError:
                pass
        if directory_id is not None and identity(output) == directory_id:
            for path in (temporary,) if complete else (temporary, final):
                if path is not None and file_id is not None and identity(path) == file_id:
                    try:
                        path.unlink()
                    except FileNotFoundError:
                        pass
            if not complete:
                try:
                    output.rmdir()  # Nonempty directories, including concurrent content, stay intact.
                except OSError:
                    pass


def prepare(bundle: Path, stable_path: Path, description: str, created: str,
            output: Path, repository: str = REPOSITORY) -> Path:
    stable = read_index(stable_path)
    version = bundle_version(bundle)
    index = {"created_at": created, "description": description,
             "download_url": release_url(repository, version),
             "signature_download_url": "", "version": version}
    if selected_branch(stable, index, repository) != "dev":
        raise ValueError("Manager would select the newer main index instead of this dev candidate.")
    # A fresh directory makes overwriting the real source index or any previous output impossible.
    payload = (json.dumps(index, indent=2, ensure_ascii=False) + "\n").encode("utf-8")
    if len(payload) > LIMIT:
        raise ValueError("The generated index exceeds the metadata limit.")
    return write_preview(payload, output)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", default=REPOSITORY)
    commands = parser.add_subparsers(dest="command", required=True)
    command = commands.add_parser("prepare", help="Write only a new local directory.")
    command.add_argument("--bundle", type=Path, required=True)
    command.add_argument("--stable-index", type=Path, default=Path(__file__).resolve().parents[1] / "patches-bundle.json")
    command.add_argument("--description-file", type=Path, required=True)
    command.add_argument("--created-at", required=True)
    command.add_argument("--out-dir", type=Path, required=True)
    command = commands.add_parser("validate", help="Validate local metadata and optional bundle identity.")
    command.add_argument("--index", type=Path, required=True)
    command.add_argument("--branch", choices=("main", "dev"), required=True)
    command.add_argument("--bundle", type=Path)
    command = commands.add_parser("select", help="Predict Manager's local main/dev version selection.")
    command.add_argument("--stable-index", type=Path, required=True)
    command.add_argument("--dev-index", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            path = prepare(args.bundle, args.stable_index, read_text(args.description_file).strip(),
                           args.created_at, args.out_dir, args.repository)
            print(f"[dev-index] Local preview written to {path}. No publication or runtime acceptance checked.")
            print(f"[dev-index] Planned dev endpoint: {source_endpoint(args.repository, 'dev')}")
        elif args.command == "validate":
            index = read_index(args.index)
            validate_index(index, args.branch, args.repository)
            if args.bundle:
                validate_bundle(index, args.bundle)
            print(f"[dev-index] Local {args.branch} metadata valid for {index['version']}.")
        else:
            stable, dev = read_index(args.stable_index), read_index(args.dev_index)
            branch = selected_branch(stable, dev, args.repository)
            print(f"[dev-index] Prerelease preference selects {branch} {(dev if branch == 'dev' else stable)['version']}.")
        return 0
    except (OSError, ValueError, TypeError, zipfile.BadZipFile, RuntimeError) as error:
        print(f"[dev-index] {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
