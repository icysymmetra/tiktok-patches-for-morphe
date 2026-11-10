#!/usr/bin/env python3
"""Check local dev-index generation, Manager selection and altered negative controls."""

import copy
import importlib.util
import json
import os
import socket
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("prepare-dev-index.py")
SPEC = importlib.util.spec_from_file_location("dev_index", SCRIPT)
DEV = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DEV)
FIXTURES = SCRIPT.parent / "fixtures" / "dev-index"


class DevIndexTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.stable_path = self.root / "patches-bundle.json"
        self.stable_bytes = (FIXTURES / "stable.json").read_bytes()
        self.stable_path.write_bytes(self.stable_bytes)
        self.stable = DEV.read_index(self.stable_path)
        self.dev = DEV.read_index(FIXTURES / "dev.json")
        self.prerelease = DEV.read_index(FIXTURES / "dev-prerelease.json")
        self.promoted = DEV.read_index(FIXTURES / "promoted-stable.json")
        self.output = self.root / "local output"
        self.description = self.root / "description.txt"
        self.description.write_text("Local development preview.\n", encoding="utf-8")

    def bundle(self, version="0.71.3", manifest=None):
        path = self.root / f"patches-{version}.mpp"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", manifest or f"Manifest-Version: 1.0\r\nVersion: {version}\r\n\r\n")
        return path

    def prepare(self, bundle=None, **changes):
        args = {"bundle": bundle or self.bundle(), "stable_path": self.stable_path,
                "description": "Local development preview.", "created": "2026-10-12T06:00:00", "output": self.output}
        args.update(changes)
        return DEV.prepare(**args)

    def run_cli(self, *arguments):
        return subprocess.run([sys.executable, str(SCRIPT), *map(str, arguments)],
                              capture_output=True, text=True, encoding="utf-8", timeout=10, check=False)

    def test_fixture_contracts_and_exact_main_dev_endpoints(self):
        for data, branch in ((self.stable, "main"), (self.dev, "dev"),
                             (self.prerelease, "dev"), (self.promoted, "main")):
            DEV.validate_index(data, branch)
            self.assertNotIn("channel", data)
            self.assertNotIn("prerelease", data)
            self.assertEqual(DEV.source_endpoint(DEV.REPOSITORY, branch),
                             f"https://raw.githubusercontent.com/SysAdminDoc/HushFeed/{branch}/patches-bundle.json")

    def test_current_version_bundle_prepares_isolated_dev_without_relabeling(self):
        bundle = self.bundle()
        original_bundle = bundle.read_bytes()
        path = self.prepare(bundle)
        data = DEV.read_index(path)
        self.assertEqual(path, self.output / "patches-bundle.json")
        self.assertEqual(data["version"], "0.71.3")
        self.assertEqual(data["download_url"], self.dev["download_url"])
        DEV.validate_index(data, "dev")
        DEV.validate_bundle(data, bundle)
        self.assertEqual(bundle.read_bytes(), original_bundle)
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_numbered_prerelease_preserves_its_actual_bundle_identity(self):
        bundle = self.bundle("0.71.3-dev.1")
        data = DEV.read_index(self.prepare(bundle))
        self.assertEqual(data["version"], self.prerelease["version"])
        self.assertEqual(data["download_url"], self.prerelease["download_url"])
        DEV.validate_bundle(data, bundle)

    def test_stable_promotion_beats_same_core_prerelease(self):
        self.assertEqual(DEV.selected_branch(self.stable, self.prerelease), "dev")
        self.assertEqual(DEV.selected_branch(self.promoted, self.prerelease), "main")
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_promoted_metadata_can_be_copied_to_dev_without_channel_fields(self):
        copied_dev = copy.deepcopy(self.promoted)
        self.assertEqual(DEV.selected_branch(self.promoted, copied_dev), "dev")
        self.assertEqual(copied_dev, self.promoted)
        DEV.validate_index(copied_dev, "dev")

    def test_equal_version_tie_selects_dev_even_when_stable_date_is_newer(self):
        self.assertGreater(self.promoted["created_at"], self.dev["created_at"])
        self.assertEqual(DEV.selected_branch(self.promoted, self.dev), "dev")

    def test_older_dev_falls_back_to_main_and_cannot_generate_an_ignored_candidate(self):
        self.assertEqual(DEV.selected_branch(self.dev, self.stable), "main")
        self.stable_path.write_text(json.dumps(self.promoted), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "newer main index"):
            self.prepare(self.bundle("0.71.2"))
        self.assertFalse(self.output.exists())

    def test_manager_compares_numbered_suffixes_numerically(self):
        pairs = (("0.71.3-dev.9", "0.71.3-dev.10"),
                 ("0.71.3-dev.999", "0.71.3"), ("0.71.3", "0.72.0-dev.1"),
                 ("0.71.3-beta.5", "0.71.3-dev.6"))
        for older, newer in pairs:
            with self.subTest(older=older, newer=newer):
                self.assertLess(DEV.version_key(older), DEV.version_key(newer))

    def test_stable_branch_keeps_production_stable_version_rule(self):
        with self.assertRaisesRegex(ValueError, "stable version"):
            DEV.validate_index(self.prerelease, "main")
        for branch in ("feature", "refs/heads/dev", "main/dev"):
            with self.subTest(branch=branch), self.assertRaisesRegex(ValueError, "main or dev"):
                DEV.validate_index(self.dev, branch)

    def test_versions_outside_the_supported_numeric_contract_are_rejected(self):
        for version in ("v0.71.3", "0.71", "0.71.3-dev", "0.71.3+build", "2147483648.0.0",
                        "0.71.3-dev.9223372036854775808"):
            with self.subTest(version=version), self.assertRaisesRegex(ValueError, "Unsupported"):
                DEV.version_key(version)

    def test_manager_optional_fields_accept_absent_empty_and_null(self):
        for value in (None, ""):
            data = dict(self.dev, signature_download_url=value, page_url=value)
            DEV.validate_index(data, "dev")
        data.pop("signature_download_url")
        data.pop("page_url")
        DEV.validate_index(data, "dev")
        data["page_url"] = "https://github.com/SysAdminDoc/HushFeed/releases/tag/v0.71.3"
        DEV.validate_index(data, "dev")

    def test_unknown_missing_or_wrong_type_fields_are_rejected(self):
        mutations = (lambda data: data.update(channel="dev"), lambda data: data.pop("version"),
                     lambda data: data.update(version=71), lambda data: data.update(description=" "),
                     lambda data: data.update(signature_download_url=False),
                     lambda data: data.update(page_url="http://example.test/"),
                     lambda data: data.update(page_url="https://user:password@example.test/"))
        for mutate in mutations:
            data = copy.deepcopy(self.dev)
            mutate(data)
            with self.subTest(data=data), self.assertRaises(ValueError):
                DEV.validate_index(data, "dev")

    def test_duplicate_json_fields_and_non_object_are_rejected(self):
        for text in ('{"version":"0.71.2","version":"0.71.3"}', "[]", "null"):
            self.stable_path.write_text(text, encoding="utf-8")
            with self.subTest(text=text), self.assertRaises((ValueError, TypeError)):
                DEV.read_index(self.stable_path)

    def test_invalid_clock_and_timezone_suffixes_are_rejected_before_output(self):
        for created in ("2026-10-12T06:00:00Z", "2026-10-12T06:00:00+00:00",
                        "2026-02-29T06:00:00", "2026-10-12 06:00:00", "2026-10-12T25:00:00"):
            with self.subTest(created=created), self.assertRaises(ValueError):
                self.prepare(created=created)
            self.assertFalse(self.output.exists())
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_wrong_repository_tag_filename_or_url_shape_is_rejected(self):
        url = self.dev["download_url"]
        invalid = (url.replace("HushFeed", "Other"), url.replace("v0.71.3/", "v0.71.2/"),
                   url.replace("patches-0.71.3", "patches-0.71.2"), url.replace("/releases/", "/RELEASES/"),
                   url.replace("https:", "http:"), url + "?token=test", url + "#fragment",
                   url.replace("github.com", "github.com.example.test"),
                   url.replace("github.com", "user:password@github.com"))
        for candidate in invalid:
            with self.subTest(url=candidate), self.assertRaisesRegex(ValueError, "download_url"):
                DEV.validate_index(dict(self.dev, download_url=candidate), "dev")
        DEV.validate_index(dict(self.dev, download_url=url.replace("HushFeed", "hushfeed")), "dev")

    def test_renaming_a_bundle_cannot_relabel_its_version(self):
        bundle = self.bundle().rename(self.root / "patches-0.71.3-dev.1.mpp")
        with self.assertRaisesRegex(ValueError, "filename"):
            self.prepare(bundle)
        self.assertFalse(self.output.exists())

    def test_index_and_bundle_version_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "actual bundle manifest"):
            DEV.validate_bundle(self.prerelease, self.bundle())

    def test_manifest_missing_duplicate_or_named_section_version_is_rejected(self):
        invalid = ("Manifest-Version: 1.0\n\nName: a\nVersion: 0.71.3\n",
                   "Version: 0.71.3\nversion: 0.71.2\n\n", "Version: bogus\n\n")
        for manifest in invalid:
            with self.subTest(manifest=manifest), self.assertRaises(ValueError):
                self.prepare(self.bundle(manifest=manifest))
            self.assertFalse(self.output.exists())

    def test_manifest_continuations_are_unfolded(self):
        bundle = self.bundle(manifest="Manifest-Version: 1.0\r\nVersion: 0.71.\r\n 3\r\n\r\n")
        self.assertEqual(DEV.bundle_version(bundle), "0.71.3")

    def test_duplicate_zip_manifest_and_truncated_archive_are_rejected(self):
        bundle = self.bundle()
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(bundle, "a") as archive:
                archive.writestr("META-INF/MANIFEST.MF", "Version: 0.71.3\n")
        with self.assertRaisesRegex(ValueError, "one bounded"):
            self.prepare(bundle)
        bundle.write_bytes(b"PK truncated")
        with self.assertRaises(zipfile.BadZipFile):
            self.prepare(bundle)
        self.assertFalse(self.output.exists())

    def test_metadata_and_manifest_reads_are_bounded(self):
        self.description.write_bytes(b"x" * (DEV.LIMIT + 1))
        with self.assertRaisesRegex(ValueError, "exceeds"):
            DEV.read_text(self.description)
        with self.assertRaisesRegex(ValueError, "one bounded"):
            self.prepare(self.bundle(manifest="Version: 0.71.3\n" + "x" * DEV.LIMIT))
        with self.assertRaisesRegex(ValueError, "generated index"):
            self.prepare(description="x" * DEV.LIMIT)
        self.assertFalse(self.output.exists())

    def test_existing_output_and_real_stable_directory_cannot_be_overwritten(self):
        self.output.mkdir()
        control = self.output / "keep.txt"
        control.write_bytes(b"unrelated")
        for output in (self.output, self.root):
            with self.subTest(output=output), self.assertRaises(FileExistsError):
                self.prepare(output=output)
        self.assertEqual(control.read_bytes(), b"unrelated")
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def assert_retry_after_write_failure(self):
        self.assertFalse(self.output.exists())
        path = self.prepare()
        DEV.validate_bundle(DEV.read_index(path), self.root / "patches-0.71.3.mpp")
        self.assertEqual(list(self.output.iterdir()), [path])
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_partial_write_failure_cleans_only_owned_temp_and_allows_retry(self):
        write = os.write
        calls = []

        def partial_then_fail(descriptor, data):
            self.assertFalse((self.output / "patches-bundle.json").exists())
            calls.append(len(data))
            if len(calls) == 1:
                return write(descriptor, data[:12])
            raise OSError("simulated full disk")

        with patch.object(DEV.os, "write", side_effect=partial_then_fail), \
                self.assertRaisesRegex(OSError, "simulated full disk"):
            self.prepare()
        self.assertEqual(len(calls), 2)
        self.assert_retry_after_write_failure()

    def test_short_writes_finish_before_the_final_name_appears(self):
        write = os.write
        counts = []

        def short_write(descriptor, data):
            self.assertFalse((self.output / "patches-bundle.json").exists())
            count = write(descriptor, data[:17])
            counts.append(count)
            return count

        with patch.object(DEV.os, "write", side_effect=short_write):
            path = self.prepare()
        self.assertGreater(len(counts), 1)
        DEV.validate_bundle(DEV.read_index(path), self.root / "patches-0.71.3.mpp")
        self.assertEqual(list(self.output.iterdir()), [path])

    def test_zero_progress_write_cannot_publish_a_truncated_preview(self):
        with patch.object(DEV.os, "write", return_value=0), \
                self.assertRaisesRegex(OSError, "no progress"):
            self.prepare()
        self.assert_retry_after_write_failure()

    def test_fsync_failure_has_no_final_file_and_allows_retry(self):
        def fail_sync(descriptor):
            self.assertFalse((self.output / "patches-bundle.json").exists())
            self.assertGreater(os.fstat(descriptor).st_size, 0)
            raise OSError("simulated fsync failure")

        with patch.object(DEV.os, "fsync", side_effect=fail_sync), \
                self.assertRaisesRegex(OSError, "simulated fsync failure"):
            self.prepare()
        self.assert_retry_after_write_failure()

    def test_close_failure_before_or_after_close_is_retryable(self):
        close = os.close
        for after_close in (False, True):
            calls = []
            output = self.root / f"close-{after_close}"

            def fail_close(descriptor, calls=calls, after_close=after_close):
                calls.append(descriptor)
                if len(calls) == 1:
                    if after_close:
                        close(descriptor)
                    raise OSError("simulated close failure")
                close(descriptor)

            with self.subTest(after_close=after_close), patch.object(DEV.os, "close", side_effect=fail_close), \
                    self.assertRaisesRegex(OSError, "simulated close failure"):
                self.prepare(output=output)
            self.assertFalse(output.exists())
            self.assertTrue(self.prepare(output=output).is_file())
            self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_failed_close_does_not_close_a_reused_unrelated_descriptor(self):
        close = os.close
        control_path = self.root / "unrelated-open-file"
        control_descriptors = []

        def close_and_reuse(descriptor):
            close(descriptor)
            replacement = os.open(control_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            control_descriptors.append(replacement)
            self.assertEqual(replacement, descriptor)
            raise OSError("simulated error after descriptor reuse")

        try:
            with patch.object(DEV.os, "close", side_effect=close_and_reuse), \
                    self.assertRaisesRegex(OSError, "descriptor reuse"):
                self.prepare()
            self.assertEqual(os.write(control_descriptors[0], b"still open"), 10)
        finally:
            for descriptor in control_descriptors:
                close(descriptor)
        self.assertEqual(control_path.read_bytes(), b"still open")
        self.assert_retry_after_write_failure()

    def test_finalize_failure_before_or_after_rename_is_retryable(self):
        rename = DEV.rename_preview
        for after_rename in (False, True):
            output = self.root / f"rename-{after_rename}"

            def fail_rename(temporary, final, after_rename=after_rename):
                if after_rename:
                    rename(temporary, final)
                raise OSError("simulated rename failure")

            with self.subTest(after_rename=after_rename), \
                    patch.object(DEV, "rename_preview", side_effect=fail_rename), \
                    self.assertRaisesRegex(OSError, "simulated rename failure"):
                self.prepare(output=output)
            self.assertFalse(output.exists())
            self.assertTrue(self.prepare(output=output).is_file())
            self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_temporary_creation_failure_cleans_the_empty_owned_directory(self):
        with patch.object(DEV.tempfile, "mkstemp", side_effect=OSError("simulated temp creation failure")), \
                self.assertRaisesRegex(OSError, "temp creation failure"):
            self.prepare()
        self.assert_retry_after_write_failure()

    def test_failure_preserves_a_concurrent_unrelated_file(self):
        def fail_sync(descriptor):
            (self.output / "keep.txt").write_bytes(b"concurrent unrelated content")
            raise OSError("simulated write finalization failure")

        with patch.object(DEV.os, "fsync", side_effect=fail_sync), \
                self.assertRaisesRegex(OSError, "write finalization failure"):
            self.prepare()
        self.assertEqual([path.name for path in self.output.iterdir()], ["keep.txt"])
        self.assertEqual((self.output / "keep.txt").read_bytes(), b"concurrent unrelated content")
        with self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_concurrent_final_name_cannot_be_replaced_or_deleted(self):
        rename = DEV.rename_preview

        def competing_file(temporary, final):
            final.write_bytes(b"unrelated final-name file")
            rename(temporary, final)

        with patch.object(DEV, "rename_preview", side_effect=competing_file), self.assertRaises(FileExistsError):
            self.prepare()
        self.assertEqual(list(self.output.iterdir()), [self.output / "patches-bundle.json"])
        self.assertEqual((self.output / "patches-bundle.json").read_bytes(), b"unrelated final-name file")
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_replaced_output_directory_is_not_cleaned_through_the_old_path(self):
        moved = self.root / "moved-owned-output"

        def replace_directory(temporary, final):
            self.output.rename(moved)
            self.output.mkdir()
            (self.output / "keep.txt").write_bytes(b"replacement directory")
            raise OSError("simulated directory replacement")

        with patch.object(DEV, "rename_preview", side_effect=replace_directory), \
                self.assertRaisesRegex(OSError, "directory replacement"):
            self.prepare()
        self.assertEqual(list(self.output.iterdir()), [self.output / "keep.txt"])
        self.assertEqual((self.output / "keep.txt").read_bytes(), b"replacement directory")
        self.assertTrue(moved.is_dir())
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_invalid_ports_and_authorities_are_rejected_in_optional_urls(self):
        authorities = ("example.test:invalid", "example.test:99999", "example.test:-1", "example.test:",
                       "example.test:443:80", "[invalid]", "user:secret@example.test")
        for field in ("signature_download_url", "page_url"):
            for authority in authorities:
                with self.subTest(field=field, authority=authority), self.assertRaisesRegex(ValueError, field):
                    DEV.validate_index(dict(self.dev, **{field: f"https://{authority}/asset"}), "dev")
            for authority in ("example.test", "example.test:443", "example.test:65535", "[::1]:443"):
                DEV.validate_index(dict(self.dev, **{field: f"https://{authority}/asset"}), "dev")

    def test_url_whitespace_and_control_characters_are_rejected_before_parsing(self):
        for field in ("download_url", "signature_download_url", "page_url"):
            url = self.dev["download_url"] if field == "download_url" else "https://example.test/asset"
            invalid = ("\x00" + url, "\x1f" + url, " " + url, url + "\x7f", url + "\x85",
                       url.replace("https://", "https:\n//"), url.replace("https://", "https:\t//"), url + "\\file")
            for value in invalid:
                with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, field):
                    DEV.validate_index(dict(self.dev, **{field: value}), "dev")

    def test_invalid_repository_components_and_traversing_index_urls_are_rejected(self):
        invalid = ("owner/..", "../repo", "./repo", "owner/.", "owner/../repo", "owner//repo", "owner/",
                   "/repo", "owner/repo/path", "owner\\repo", "owner/%2e%2e", "owner/ repo", "owner/\nrepo",
                   "_owner/repo", "owner-/repo", "owner--name/repo")
        for repository in invalid:
            with self.subTest(repository=repository), self.assertRaisesRegex(ValueError, "repository"):
                DEV.source_endpoint(repository, "dev")
            data = dict(self.dev, download_url=f"https://github.com/{repository}/releases/download/v0.71.3/patches-0.71.3.mpp")
            with self.subTest(repository=repository), self.assertRaisesRegex(ValueError, "repository"):
                DEV.validate_index(data, "dev", repository)
            with self.subTest(repository=repository), self.assertRaisesRegex(ValueError, "repository"):
                self.prepare(repository=repository)
            self.assertFalse(self.output.exists())
        for repository in ("SysAdminDoc/HushFeed", "MorpheApp/morphe-manager", "owner-123/repo.name", "a/.github"):
            DEV.source_endpoint(repository, "dev")
            data = dict(self.dev, download_url=DEV.release_url(repository, self.dev["version"]))
            DEV.validate_index(data, "dev", repository)

    def test_preparation_has_no_network_or_command_boundary(self):
        bundle = self.bundle()
        with patch.object(socket, "socket", side_effect=AssertionError("network")), \
                patch.object(subprocess, "run", side_effect=AssertionError("command")):
            path = self.prepare(bundle)
        self.assertTrue(path.is_file())

    def test_cli_exercises_generation_validation_and_selection(self):
        bundle = self.bundle()
        result = self.run_cli("prepare", "--bundle", bundle, "--stable-index", self.stable_path,
                              "--description-file", self.description, "--created-at", "2026-10-12T06:00:00",
                              "--out-dir", self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Local preview", result.stdout)
        self.assertIn("No publication or runtime acceptance", result.stdout)
        index = self.output / "patches-bundle.json"
        result = self.run_cli("validate", "--branch", "dev", "--index", index, "--bundle", bundle)
        self.assertEqual(result.returncode, 0, result.stderr)
        result = self.run_cli("select", "--stable-index", self.stable_path, "--dev-index", index)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("selects dev 0.71.3", result.stdout)
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)

    def test_cli_failure_is_nonzero_without_writing_a_preview(self):
        result = self.run_cli("prepare", "--bundle", self.bundle(), "--stable-index", self.stable_path,
                              "--description-file", self.description, "--created-at", "2026-10-12T06:00:00Z",
                              "--out-dir", self.output)
        self.assertEqual(result.returncode, 1)
        self.assertIn("without a timezone suffix", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertEqual(self.stable_path.read_bytes(), self.stable_bytes)


if __name__ == "__main__":
    unittest.main()
