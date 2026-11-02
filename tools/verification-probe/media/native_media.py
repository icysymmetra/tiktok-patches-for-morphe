"""Generate owned fixtures and verify pulled ART outputs. Never connects to a device or network."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/download"
INPUTS = {"video-90.mp4": (1, 0), "audio.m4a": (0, 1), "av-90.mp4": (1, 1)}
OUTPUTS = {
    "combined.mp4": ("combine", 1, 1, "video-90.mp4"),
    "silent.mp4": ("videoOnly", 1, 0, "av-90.mp4"),
    "silent-combined.mp4": ("videoOnlyCombined", 1, 0, "video-90.mp4"),
    "sound.m4a": ("audioOnly", 0, 1, None),
}
NEGATIVES = {"missingVideo", "missingAudio", "combineMissingAudio"}
SOURCES = ("TrackMuxer.java", "MediaBudget.java", "VideoDownloads.java")
MAX_MEDIA = 8 * 1024 * 1024
TOKEN = re.compile(r"media-[a-f0-9]{24}\Z")
HASH = re.compile(r"[a-f0-9]{64}\Z")


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise ValueError(reason)


def digest(path: Path, maximum: int = MAX_MEDIA) -> str:
    require(path.is_file() and not path.is_symlink(), "expected an ordinary owned file")
    size = path.stat().st_size
    require(0 < size <= maximum, "file size exceeds the bounded input contract")
    result = hashlib.sha256()
    count = 0
    with path.open("rb") as source:
        while data := source.read(64 * 1024):
            count += len(data)
            require(count <= maximum, "file grew beyond its bound")
            result.update(data)
    require(size == count == path.stat().st_size, "file changed during hashing")
    return result.hexdigest()


def object_pairs(pairs: list[tuple[str, object]]) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON key")
        result[key] = value
    return result


def read_json(path: Path) -> dict:
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= 32768,
            "JSON input is absent or exceeds its bound")
    with path.open("r", encoding="utf-8") as source:
        value = json.load(source, object_pairs_hook=object_pairs)
    require(isinstance(value, dict), "JSON object required")
    return value


def run(arguments: list[str], cwd: Path | None = None) -> bytes:
    result = subprocess.run(arguments, cwd=cwd, capture_output=True, timeout=30, check=False)
    require(result.returncode == 0, f"{Path(arguments[0]).name} failed with exit {result.returncode}")
    require(len(result.stdout) <= 2 * 1024 * 1024, "tool output exceeded its bound")
    return result.stdout


def probe(path: Path, ffprobe: str) -> dict:
    data = json.loads(run([ffprobe, "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)]))
    streams = data.get("streams", [])
    require(1 <= len(streams) <= 2, "unexpected ffprobe track count")
    video = sum(stream.get("codec_type") == "video" for stream in streams)
    audio = sum(stream.get("codec_type") == "audio" for stream in streams)
    require(video + audio == len(streams), "unexpected non-media stream")
    rotations = []
    for stream in streams:
        if stream.get("codec_type") != "video":
            continue
        matrix = [side["rotation"] for side in stream.get("side_data_list", []) if "rotation" in side]
        require(len(matrix) == 1, "video rotation matrix is absent or ambiguous")
        # FFmpeg's display_rotation is counterclockwise; Android's hint is clockwise.
        rotations.append((-int(matrix[0])) % 360)
    duration = float(data["format"]["duration"])
    require(1.7 <= duration <= 2.3, "container duration is not the controlled two seconds")
    return {"videoTracks": video, "audioTracks": audio, "rotation": rotations[0] if rotations else 0,
            "durationSeconds": duration, "codecs": [stream.get("codec_name") for stream in streams]}


def decoded(path: Path, video: bool, ffmpeg: str) -> dict:
    common = [ffmpeg, "-v", "error", "-nostdin", "-threads", "1", "-noautorotate", "-i", str(path)]
    if video:
        raw = run(common + ["-map", "0:v:0", "-an", "-threads", "1", "-f", "framemd5", "-"])
        frames = [line.rsplit(b",", 1)[-1].strip() for line in raw.splitlines() if line and not line.startswith(b"#")]
        require(len(frames) == 24 and all(re.fullmatch(rb"[a-f0-9]{32}", frame) for frame in frames),
                "video did not decode all 24 owned frames")
        return {"frames": len(frames), "frameHashes": [frame.decode("ascii") for frame in frames]}
    raw = run(common + ["-map", "0:a:0", "-vn", "-acodec", "pcm_s16le", "-threads", "1", "-f", "s16le", "-"])
    require(160000 <= len(raw) <= 230000 and any(raw), "owned sine did not decode as nonempty PCM")
    return {"pcmBytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}


def source_hashes() -> dict[str, str]:
    return {name: digest(SOURCE / name) for name in SOURCES}


def prepare(output: Path, ffmpeg: str, ffprobe: str) -> dict:
    output = output.absolute()
    temporary = Path(tempfile.gettempdir()).resolve()
    require(output != temporary and temporary in output.resolve().parents, "fixture output must be beneath the system temporary directory")
    require(output == output.resolve(), "redirected fixture output is refused")
    output.mkdir(exist_ok=False)
    owned: list[Path] = []
    token = "media-" + secrets.token_hex(12)
    commands = [
        ["-f", "lavfi", "-i", "testsrc2=size=160x96:rate=12:duration=2", "-an", "-c:v", "libx264",
         "-profile:v", "baseline", "-pix_fmt", "yuv420p", "-x264-params", "threads=1:keyint=12:min-keyint=12:bframes=0:scenecut=0", "video-base.mp4"],
        ["-display_rotation", "-90", "-i", "video-base.mp4", "-map", "0:v:0", "-c", "copy", "video-90.mp4"],
        ["-f", "lavfi", "-i", "sine=frequency=997:sample_rate=48000:duration=2", "-vn", "-c:a", "aac", "-ac", "1", "-b:a", "96k", "audio.m4a"],
        ["-i", "video-90.mp4", "-i", "audio.m4a", "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-shortest", "av-90.mp4"],
    ]
    try:
        for arguments in commands:
            owned.append(output / arguments[-1])
            run([ffmpeg, "-v", "error", "-nostdin", "-n", "-threads", "1"] + arguments, output)
        inputs = {}
        for name, counts in INPUTS.items():
            path = output / name
            facts = probe(path, ffprobe)
            require((facts["videoTracks"], facts["audioTracks"]) == counts, "fixture track count differs")
            require(facts["rotation"] == (90 if counts[0] else 0), "fixture rotation differs")
            facts["decoded"] = decoded(path, bool(counts[0]), ffmpeg)
            inputs[name] = {"sha256": digest(path), "bytes": path.stat().st_size, **facts}
        (output / "video-base.mp4").unlink()
        manifest = {"schema": 1, "token": token, "provenance": "ffmpeg-lavfi-testsrc2+sine", "rotationConvention": "clockwise",
                    "inputs": inputs, "sourceHashes": source_hashes(),
                    "ffmpegVersion": run([ffmpeg, "-version"]).decode("utf-8").splitlines()[0][:300],
                    "commands": [["ffmpeg", "-v", "error", "-nostdin", "-n", "-threads", "1"] + command for command in commands],
                    "qualification": "Source hashes describe preparation. The installed APK and production classes must be qualified separately."}
        temp = output / "manifest.json.tmp"
        owned.append(temp)
        with temp.open("x", encoding="utf-8", newline="\n") as target:
            target.write(json.dumps(manifest, indent=2, ensure_ascii=True) + "\n")
            target.flush()
            os.fsync(target.fileno())
        final = output / "manifest.json"
        require(not final.exists(), "manifest collision")
        temp.rename(final)
        owned.append(final)
        return {"token": token, "manifestSha256": digest(final, 32768), "inputFiles": list(INPUTS),
                "fixtures": str(output), "deviceSubdirectory": "hushfeed-native-media/" + token,
                "nativeExecuted": False}
    except BaseException:
        for path in reversed(owned):
            if path.is_file() and not path.is_symlink():
                path.unlink()
        try:
            output.rmdir()
        except OSError:
            pass  # Concurrent/unrelated content is preserved; there is no recursive cleanup.
        raise


def validate_receipt(manifest: dict, manifest_hash: str, receipt: dict, apk_hash: str) -> dict[str, dict]:
    require(receipt.get("schema") == 1 and receipt.get("kind") == "controlled-trackmuxer-art", "wrong receipt type")
    require(TOKEN.fullmatch(manifest.get("token", "")) is not None and receipt.get("token") == manifest["token"], "receipt token mismatch")
    require(receipt.get("manifestSha256") == manifest_hash and receipt.get("installedApkSha256") == apk_hash, "receipt artifact identity mismatch")
    require(receipt.get("sourceHashes") == manifest.get("sourceHashes"), "receipt source hashes differ")
    require(receipt.get("package") == "com.zhiliaoapp.musically" and receipt.get("version") == "47.4.3"
            and receipt.get("vm") == "Dalvik" and 23 <= receipt.get("sdk", 0) <= 100, "unqualified ART host")
    require(receipt.get("actualInstalledTrackMuxer") is True and receipt.get("accepted") is True
            and receipt.get("complete") is True, "native muxer did not pass")
    require(receipt.get("nativeDashBranchExercised") is False and receipt.get("servedDashAcceptance") is False
            and receipt.get("compiledSourceQualified") is False, "unsupported acceptance claim")
    require(receipt.get("deadlineMs") == 90000 and 0 <= receipt.get("elapsedMs", -1) < 90000, "late native receipt")
    restoration = receipt.get("restoration", {})
    require(all(restoration.get(key) is True for key in ("inputsUnchanged", "productionDeadlineRestored", "probeResourcesReleased")), "incomplete restoration")
    require(all(restoration.get(key) is False for key in ("settingsTouched", "audioPlaybackUsed", "networkUsed")), "unexpected system or transport changes")
    require(receipt.get("outputsRetainedForHost") is True and receipt.get("ownedPartialOutputsRemoved") is True, "incomplete output ownership")
    operations = receipt.get("operations", [])
    require(isinstance(operations, list) and len(operations) == len(INPUTS) + len(OUTPUTS) + len(NEGATIVES), "operation set differs")
    files = {}
    negatives = set()
    for operation in operations:
        label = operation.get("operation")
        if label in NEGATIVES:
            require(label not in negatives and operation.get("rejected") is True and operation.get("partialOutputRemoved") is True, "negative control failed or repeated")
            negatives.add(label)
            continue
        name = operation.get("file")
        require(name in INPUTS or name in OUTPUTS, "unknown media operation")
        require(name not in files, "duplicate media operation")
        if name in INPUTS:
            wanted_video, wanted_audio = INPUTS[name]
            require(label == "input" and operation.get("sha256") == manifest["inputs"][name]["sha256"], "native input differs")
        else:
            wanted_label, wanted_video, wanted_audio, _ = OUTPUTS[name]
            require(label == wanted_label, "wrong production method")
        require(operation.get("videoTracks") == wanted_video and operation.get("audioTracks") == wanted_audio, "native silent track count differs")
        require(operation.get("rotation") == (90 if wanted_video else 0), "native rotation differs")
        require(1700000 <= operation.get("durationUs", 0) <= 2300000
                and 1700 <= operation.get("containerDurationMs", 0) <= 2300, "native duration differs")
        require(HASH.fullmatch(operation.get("sha256", "")) is not None and 0 < operation.get("bytes", 0) <= MAX_MEDIA, "invalid output identity")
        tracks = operation.get("tracks", [])
        require(len(tracks) == wanted_video + wanted_audio, "native track details missing")
        mimes = []
        for track in tracks:
            mimes.append(track.get("mime"))
            require(0 < track.get("samples", 0) <= 1000 and 0 < track.get("sampleBytes", 0) <= MAX_MEDIA,
                    "native samples missing")
            require(track.get("firstPtsUs", 0) <= track.get("lastPtsUs", -1), "native sample timestamps differ")
            if name in OUTPUTS:
                require(track.get("firstPtsUs", -1) >= 0, "muxed samples retain negative timestamps")
            decode = track.get("decoded", {})
            require(decode.get("eos") is True and 0 < decode.get("buffers", 0) <= 1000
                    and 0 < decode.get("bytes", 0) <= MAX_MEDIA and HASH.fullmatch(decode.get("sha256", "")) is not None,
                    "actual native decoded output missing")
        require(mimes.count("video/avc") == wanted_video and mimes.count("audio/mp4a-latm") == wanted_audio,
                "native codecs differ")
        files[name] = operation
    require(negatives == NEGATIVES and set(files) == set(INPUTS) | set(OUTPUTS), "missing native operation")
    owned = receipt.get("ownedFiles", [])
    require(len(owned) == len(OUTPUTS) and {row.get("file") for row in owned} == set(OUTPUTS), "owned output inventory differs")
    for row in owned:
        require(row.get("sha256") == files[row["file"]]["sha256"], "owned output hash differs")
    return files


def verify(fixtures: Path, results: Path, apk: Path, ffmpeg: str, ffprobe: str) -> dict:
    manifest = read_json(fixtures / "manifest.json")
    require(manifest.get("schema") == 1 and manifest.get("provenance") == "ffmpeg-lavfi-testsrc2+sine"
            and set(manifest.get("inputs", {})) == set(INPUTS), "invalid controlled fixture manifest")
    require(manifest.get("sourceHashes") == source_hashes(), "production sources changed after fixture preparation")
    receipt = read_json(results / "receipt.json")
    native = validate_receipt(manifest, digest(fixtures / "manifest.json", 32768), receipt, digest(apk, 768 * 1024 * 1024))
    host = {}
    for name in INPUTS:
        require(digest(fixtures / name) == manifest["inputs"][name]["sha256"], "owned input changed")
        host[name] = decoded(fixtures / name, bool(INPUTS[name][0]), ffmpeg)
    for name, (_, video, audio, original) in OUTPUTS.items():
        path = results / name
        require(digest(path) == native[name]["sha256"] and path.stat().st_size == native[name]["bytes"], "pulled output differs from native receipt")
        facts = probe(path, ffprobe)
        require((facts["videoTracks"], facts["audioTracks"]) == (video, audio), "ffprobe silence/track count differs")
        require(facts["rotation"] == (90 if video else 0), "ffprobe rotation differs")
        video_decode = decoded(path, True, ffmpeg) if video else None
        if video:
            require(video_decode["frameHashes"] == host[original]["frameHashes"], "decoded video changed during sample copy")
        audio_decode = decoded(path, False, ffmpeg) if audio else None
        host[name] = {**facts, "videoDecoded": video_decode, "audioDecoded": audio_decode}
    return {"accepted": True, "qualification": "controlled installed TrackMuxer on ART plus ffprobe and decoding",
            "token": manifest["token"], "outputs": {name: host[name] for name in OUTPUTS},
            "nativeDashBranchExercised": False, "servedDashAcceptance": False,
            "sourceBuildProvenanceRequiresBuildReceipt": True, "deviceCleanupStillRequired": True}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg") or "ffmpeg")
    parser.add_argument("--ffprobe", default=shutil.which("ffprobe") or "ffprobe")
    modes = parser.add_subparsers(dest="mode", required=True)
    generate = modes.add_parser("prepare")
    generate.add_argument("--out", type=Path, required=True)
    check = modes.add_parser("verify")
    check.add_argument("--fixtures", type=Path, required=True)
    check.add_argument("--results", type=Path, required=True)
    check.add_argument("--apk", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = prepare(args.out, args.ffmpeg, args.ffprobe) if args.mode == "prepare" else verify(
            args.fixtures, args.results, args.apk, args.ffmpeg, args.ffprobe)
        print(json.dumps(report, ensure_ascii=True))
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as failure:
        parser.exit(1, f"native-media: {failure}\n")


if __name__ == "__main__":
    main()
