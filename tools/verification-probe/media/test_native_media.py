"""Host contracts only. Fabricated receipts never qualify native ART execution."""

import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import native_media as media


class NativeMediaTests(unittest.TestCase):
    def setUp(self):
        self.manifest = {"token": "media-" + "a" * 24, "sourceHashes": {name: "c" * 64 for name in media.SOURCES},
                         "inputs": {name: {"sha256": "d" * 64} for name in media.INPUTS}}
        self.receipt = {"schema": 1, "kind": "controlled-trackmuxer-art", "token": self.manifest["token"],
                        "manifestSha256": "b" * 64, "installedApkSha256": "e" * 64,
                        "sourceHashes": self.manifest["sourceHashes"].copy(),
                        "package": "com.zhiliaoapp.musically", "version": "47.4.3", "vm": "Dalvik", "sdk": 23,
                        "actualInstalledTrackMuxer": True, "accepted": True, "complete": True, "nativeDashBranchExercised": False,
                        "servedDashAcceptance": False, "compiledSourceQualified": False, "deadlineMs": 90000,
                        "elapsedMs": 1000, "restoration": {"inputsUnchanged": True, "productionDeadlineRestored": True,
                        "probeResourcesReleased": True, "settingsTouched": False, "audioPlaybackUsed": False, "networkUsed": False},
                        "outputsRetainedForHost": True, "ownedPartialOutputsRemoved": True,
                        "operations": [], "ownedFiles": []}
        for name, (video, audio) in media.INPUTS.items():
            self.receipt["operations"].append(self.operation(name, "input", video, audio, "d" * 64))
        for name, (label, video, audio, _) in media.OUTPUTS.items():
            self.receipt["operations"].append(self.operation(name, label, video, audio, "f" * 64))
            self.receipt["ownedFiles"].append({"file": name, "sha256": "f" * 64})
        for label in media.NEGATIVES:
            self.receipt["operations"].append({"operation": label, "rejected": True, "partialOutputRemoved": True})

    @staticmethod
    def operation(name, label, video, audio, value):
        tracks = []
        for mime in ["video/avc"] * video + ["audio/mp4a-latm"] * audio:
            tracks.append({"mime": mime, "samples": 24, "sampleBytes": 5000, "firstPtsUs": 0, "lastPtsUs": 1900000,
                           "decoded": {"eos": True, "buffers": 24, "bytes": 40000, "sha256": "1" * 64}})
        return {"operation": label, "file": name, "videoTracks": video, "audioTracks": audio,
                "rotation": 90 if video else 0, "durationUs": 2000000, "containerDurationMs": 2000,
                "sha256": value, "bytes": 6000, "tracks": tracks}

    def validate(self, receipt=None):
        return media.validate_receipt(self.manifest, "b" * 64, receipt or self.receipt, "e" * 64)

    def test_complete_contract_and_negative_input_lead_in(self):
        self.receipt["operations"][1]["tracks"][0]["firstPtsUs"] = -21333
        self.assertEqual(set(media.INPUTS) | set(media.OUTPUTS), set(self.validate()))

    def test_rejects_missing_duplicate_and_unknown_operations(self):
        variants = [self.receipt["operations"][:-1], self.receipt["operations"][1:] + [self.receipt["operations"][1]],
                    self.receipt["operations"][:-1] + [{"operation": "invented", "file": "unknown.mp4"}]]
        for operations in variants:
            with self.subTest(operations=operations):
                changed = copy.deepcopy(self.receipt)
                changed["operations"] = operations
                with self.assertRaises(ValueError):
                    self.validate(changed)

    def test_rejects_track_rotation_duration_and_decode_failures(self):
        mutations = [("audioTracks", 1), ("videoTracks", 0), ("rotation", 0), ("durationUs", 100),
                     ("containerDurationMs", 3000), ("bytes", 0), ("sha256", "bad"), ("tracks", [])]
        for key, value in mutations:
            with self.subTest(key=key):
                changed = copy.deepcopy(self.receipt)
                changed["operations"][4][key] = value  # silent.mp4
                with self.assertRaises(ValueError):
                    self.validate(changed)
        for key, value in [("eos", False), ("buffers", 0), ("bytes", 0), ("sha256", "bad")]:
            with self.subTest(decode_key=key):
                changed = copy.deepcopy(self.receipt)
                changed["operations"][4]["tracks"][0]["decoded"][key] = value
                with self.assertRaises(ValueError):
                    self.validate(changed)
        changed = copy.deepcopy(self.receipt)
        changed["operations"][4]["tracks"][0]["firstPtsUs"] = -1
        with self.assertRaises(ValueError):
            self.validate(changed)

    def test_rejects_identity_late_completion_and_overclaims(self):
        for key, value in [("token", "../escape"), ("installedApkSha256", "0" * 64), ("manifestSha256", "0" * 64),
                           ("sourceHashes", {}), ("version", "47.0.3"), ("vm", "OpenJDK"), ("sdk", 22),
                           ("accepted", False), ("actualInstalledTrackMuxer", False), ("elapsedMs", 90000),
                           ("nativeDashBranchExercised", True), ("servedDashAcceptance", True),
                           ("compiledSourceQualified", True), ("outputsRetainedForHost", False)]:
            with self.subTest(key=key):
                changed = copy.deepcopy(self.receipt)
                changed[key] = value
                with self.assertRaises(ValueError):
                    self.validate(changed)

    def test_rejects_incomplete_restoration_negative_controls_and_ownership(self):
        for key in self.receipt["restoration"]:
            with self.subTest(key=key):
                changed = copy.deepcopy(self.receipt)
                changed["restoration"][key] = not changed["restoration"][key]
                with self.assertRaises(ValueError):
                    self.validate(changed)
        changed = copy.deepcopy(self.receipt)
        changed["operations"][-1]["partialOutputRemoved"] = False
        with self.assertRaises(ValueError):
            self.validate(changed)
        changed = copy.deepcopy(self.receipt)
        changed["ownedFiles"][0]["sha256"] = "0" * 64
        with self.assertRaises(ValueError):
            self.validate(changed)

    def test_hash_is_bounded_and_does_not_read_directories(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owned.bin"
            path.write_bytes(b"owned-fixture")
            self.assertEqual(hashlib.sha256(b"owned-fixture").hexdigest(), media.digest(path))
            with self.assertRaises(ValueError):
                media.digest(path, 2)
            with self.assertRaises(ValueError):
                media.digest(Path(directory))

    def test_duplicate_json_keys_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "receipt.json"
            path.write_text('{"accepted":false,"accepted":true}', encoding="utf-8")
            with self.assertRaises(ValueError):
                media.read_json(path)

    def test_ffprobe_rotation_is_converted_to_android_clockwise(self):
        facts = {"format": {"duration": "2.000000"}, "streams": [{"codec_type": "video", "codec_name": "h264",
                 "side_data_list": [{"rotation": -90}]}]}
        with patch.object(media, "run", return_value=json.dumps(facts).encode()):
            self.assertEqual(90, media.probe(Path("owned.mp4"), "ffprobe")["rotation"])
        facts["streams"][0]["side_data_list"] = []
        with patch.object(media, "run", return_value=json.dumps(facts).encode()), self.assertRaises(ValueError):
            media.probe(Path("owned.mp4"), "ffprobe")

    def test_host_verification_requires_decoded_frames_and_silence(self):
        def fake_hash(path, maximum=media.MAX_MEDIA):
            if path.name == "manifest.json":
                return "b" * 64
            if path.name == "target.apk":
                return "e" * 64
            return "d" * 64 if path.parent.name == "fixtures" else "f" * 64

        self.manifest.update(schema=1, provenance="ffmpeg-lavfi-testsrc2+sine")

        def fake_decode(path, video, ffmpeg):
            return {"frameHashes": ["different" if path.name == "silent.mp4" else "frame"]}

        def fake_probe(path, ffprobe):
            _, video, audio, _ = media.OUTPUTS[path.name]
            return {"videoTracks": video, "audioTracks": audio, "rotation": 90 if video else 0}

        with patch.object(media, "read_json", side_effect=[self.manifest, self.receipt]), \
                patch.object(media, "source_hashes", return_value=self.manifest["sourceHashes"]), \
                patch.object(media, "digest", side_effect=fake_hash), \
                patch.object(media.Path, "stat", return_value=type("Size", (), {"st_size": 6000})()), \
                patch.object(media, "probe", side_effect=fake_probe), patch.object(media, "decoded", side_effect=fake_decode), \
                self.assertRaisesRegex(ValueError, "decoded video changed"):
            media.verify(Path("fixtures"), Path("results"), Path("target.apk"), "ffmpeg", "ffprobe")

        def unexpected_audio(path, ffprobe):
            facts = fake_probe(path, ffprobe)
            if path.name == "silent.mp4":
                facts["audioTracks"] = 1
            return facts

        with patch.object(media, "read_json", side_effect=[self.manifest, self.receipt]), \
                patch.object(media, "source_hashes", return_value=self.manifest["sourceHashes"]), \
                patch.object(media, "digest", side_effect=fake_hash), \
                patch.object(media.Path, "stat", return_value=type("Size", (), {"st_size": 6000})()), \
                patch.object(media, "probe", side_effect=unexpected_audio), \
                patch.object(media, "decoded", return_value={"frameHashes": ["frame"]}), \
                self.assertRaisesRegex(ValueError, "ffprobe silence/track count differs"):
            media.verify(Path("fixtures"), Path("results"), Path("target.apk"), "ffmpeg", "ffprobe")

    @staticmethod
    def fake_generate(arguments, cwd=None):
        if cwd is not None:
            (cwd / arguments[-1]).write_bytes(b"owned-generated-fixture")
        return b"ffmpeg local contract fixture\n"

    def test_atomic_manifest_failures_leave_no_final_file_and_can_retry(self):
        def facts(path, executable):
            video, audio = media.INPUTS[path.name]
            return {"videoTracks": video, "audioTracks": audio, "rotation": 90 if video else 0}

        with tempfile.TemporaryDirectory() as directory, \
                patch.object(media, "run", side_effect=self.fake_generate), \
                patch.object(media, "probe", side_effect=facts), \
                patch.object(media, "decoded", return_value={"frames": 24}), \
                patch.object(media, "source_hashes", return_value=self.manifest["sourceHashes"]):
            output = Path(directory) / "owned"
            for target in ("fsync", "rename"):
                injection = patch.object(media.os, "fsync", side_effect=OSError("injected finalize failure")) if target == "fsync" \
                    else patch.object(media.Path, "rename", side_effect=OSError("injected finalize failure"))
                with injection, self.assertRaisesRegex(OSError, "injected finalize failure"):
                    media.prepare(output, "ffmpeg", "ffprobe")
                self.assertFalse(output.exists())
            original_open = Path.open

            class PartialWriter:
                def __init__(self, path):
                    self.inner = original_open(path, "x", encoding="utf-8")

                def __enter__(self):
                    return self

                def write(self, content):
                    self.inner.write(content[:12])
                    self.inner.flush()
                    raise OSError("injected partial write")

                def __exit__(self, *args):
                    self.inner.close()

            def partial_open(path, *args, **kwargs):
                return PartialWriter(path) if path.name == "manifest.json.tmp" else original_open(path, *args, **kwargs)

            with patch.object(Path, "open", new=partial_open), self.assertRaisesRegex(OSError, "injected partial write"):
                media.prepare(output, "ffmpeg", "ffprobe")
            self.assertFalse(output.exists())
            report = media.prepare(output, "ffmpeg", "ffprobe")
            self.assertFalse(report["nativeExecuted"])
            self.assertTrue((output / "manifest.json").is_file())

    def test_prepare_preserves_existing_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            sentinel = path / "unrelated.txt"
            sentinel.write_text("keep", encoding="utf-8")
            with self.assertRaises(FileExistsError), patch.object(media, "run") as execute:
                media.prepare(path, "ffmpeg", "ffprobe")
            execute.assert_not_called()
            self.assertEqual("keep", sentinel.read_text(encoding="utf-8"))

    def test_prepare_failure_removes_owned_partial_files_and_allows_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owned"

            def fail(arguments, cwd):
                (cwd / arguments[-1]).write_bytes(b"partial")
                raise ValueError("controlled failure")

            for _ in range(2):
                with self.assertRaisesRegex(ValueError, "controlled failure"), patch.object(media, "run", side_effect=fail):
                    media.prepare(path, "ffmpeg", "ffprobe")
                self.assertFalse(path.exists())

    def test_prepare_failure_preserves_concurrent_unrelated_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "owned"

            def fail(arguments, cwd):
                (cwd / arguments[-1]).write_bytes(b"partial")
                (cwd / "unrelated.txt").write_text("keep", encoding="utf-8")
                raise ValueError("controlled failure")

            with self.assertRaises(ValueError), patch.object(media, "run", side_effect=fail):
                media.prepare(path, "ffmpeg", "ffprobe")
            self.assertEqual(["unrelated.txt"], [file.name for file in path.iterdir()])
            self.assertEqual("keep", (path / "unrelated.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
