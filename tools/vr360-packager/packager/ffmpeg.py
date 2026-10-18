from __future__ import annotations

import json
import math
import shutil
import subprocess
from pathlib import Path
from typing import Any

from .models import PackageOptions, Rendition, ProgressCallback, LogCallback


def _tool_exists(tool: str) -> bool:
    candidate = Path(tool)
    if candidate.parent != Path(".") or candidate.is_absolute():
        return candidate.exists()
    return shutil.which(tool) is not None


def _run_json(command: list[str]) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(completed.stderr.strip() or "ffprobe failed")
    return json.loads(completed.stdout)


def probe_media(input_file: Path, ffprobe_path: str = "ffprobe") -> dict[str, Any]:
    if not _tool_exists(ffprobe_path):
        raise RuntimeError(f"ffprobe not found: {ffprobe_path}")

    data = _run_json([
        ffprobe_path,
        "-v", "error",
        "-show_streams",
        "-show_format",
        "-of", "json",
        str(input_file),
    ])

    video_stream = next(
        (stream for stream in data.get("streams", []) if stream.get("codec_type") == "video"),
        None,
    )
    if video_stream is None:
        raise RuntimeError("Input file does not contain a video stream")

    duration = _read_duration(data)
    fps = _read_fps(video_stream)

    return {
        "duration": duration,
        "fps": fps,
        "width": int(video_stream.get("width") or 0),
        "height": int(video_stream.get("height") or 0),
        "codec": str(video_stream.get("codec_name") or ""),
        "has_audio": any(
            stream.get("codec_type") == "audio"
            for stream in data.get("streams", [])
        ),
    }


def _read_duration(data: dict[str, Any]) -> float:
    format_duration = data.get("format", {}).get("duration")
    try:
        value = float(format_duration)
        if math.isfinite(value) and value > 0:
            return value
    except (TypeError, ValueError):
        pass

    durations = []
    for stream in data.get("streams", []):
        try:
            value = float(stream.get("duration"))
            if math.isfinite(value) and value > 0:
                durations.append(value)
        except (TypeError, ValueError):
            pass

    return max(durations, default=0.0)


def _read_fps(video_stream: dict[str, Any]) -> float:
    for key in ("avg_frame_rate", "r_frame_rate"):
        raw = str(video_stream.get(key) or "")
        if "/" in raw:
            numerator, denominator = raw.split("/", 1)
            try:
                n = float(numerator)
                d = float(denominator)
                if d != 0:
                    fps = n / d
                    if math.isfinite(fps) and fps > 0:
                        return fps
            except ValueError:
                pass
        else:
            try:
                fps = float(raw)
                if math.isfinite(fps) and fps > 0:
                    return fps
            except ValueError:
                pass

    return 30.0


def _encoder_args(encoder: str, rendition: Rendition) -> list[str]:
    bitrate = f"{rendition.bitrate_mbps:g}M"
    maxrate = f"{rendition.bitrate_mbps * 1.10:g}M"
    bufsize = f"{rendition.bitrate_mbps * 2.0:g}M"

    common = [
        "-b:v", bitrate,
        "-maxrate", maxrate,
        "-bufsize", bufsize,
        "-pix_fmt", "yuv420p",
    ]

    if encoder == "h264_nvenc":
        return [
            "-c:v", "h264_nvenc",
            "-preset", "p4",
            "-tune", "hq",
            "-profile:v", "high",
            "-rc", "vbr",
            *common,
        ]

    if encoder == "h264_amf":
        return [
            "-c:v", "h264_amf",
            "-quality", "quality",
            "-usage", "transcoding",
            "-profile:v", "high",
            *common,
        ]

    return [
        "-c:v", "libx264",
        "-preset", "medium",
        "-profile:v", "high",
        *common,
    ]


def _build_variant_command(
    options: PackageOptions,
    rendition: Rendition,
    media_info: dict[str, Any],
) -> list[str]:
    fps = float(media_info.get("fps") or 30.0)
    segment_duration = max(0.5, float(options.segment_duration))
    gop = max(1, int(round(fps * segment_duration)))

    rendition_dir = options.output_dir / rendition.id
    rendition_dir.mkdir(parents=True, exist_ok=True)

    command = [
        options.ffmpeg_path,
        "-y",
        "-hide_banner",
        "-loglevel", "warning",
        "-progress", "pipe:1",
        "-nostats",
        "-i", str(options.input_file.resolve()),
        "-map", "0:v:0",
        "-map", "0:a:0?",
        "-vf", f"scale={rendition.width}:{rendition.height}:flags=lanczos",
        *_encoder_args(options.encoder, rendition),
        "-g", str(gop),
        "-keyint_min", str(gop),
        "-sc_threshold", "0",
        "-force_key_frames", f"expr:gte(t,n_forced*{segment_duration:g})",
        "-c:a", "aac",
        "-b:a", f"{options.audio_bitrate_kbps}k",
        "-ac", "2",
        "-ar", "48000",
        "-f", "hls",
        "-hls_time", f"{segment_duration:g}",
        "-hls_playlist_type", "vod",
        "-hls_segment_type", "fmp4",
        "-hls_fmp4_init_filename", "init.mp4",
        "-hls_segment_filename", "seg_%05d.m4s",
        "-hls_flags", "independent_segments",
        "playlist.m3u8",
    ]

    return command


def _write_master_playlist(options: PackageOptions) -> None:
    lines = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        "#EXT-X-INDEPENDENT-SEGMENTS",
    ]

    audio_bps = options.audio_bitrate_kbps * 1000
    for rendition in sorted(options.renditions, key=lambda item: item.bitrate_bps):
        average_bandwidth = rendition.bitrate_bps + audio_bps
        peak_bandwidth = int(average_bandwidth * 1.12)
        lines.append(
            f"#EXT-X-STREAM-INF:BANDWIDTH={peak_bandwidth},"
            f"AVERAGE-BANDWIDTH={average_bandwidth},"
            f"RESOLUTION={rendition.width}x{rendition.height}"
        )
        lines.append(f"{rendition.id}/playlist.m3u8")

    (options.output_dir / "master.m3u8").write_text(
        "\n".join(lines) + "\n",
        encoding="utf-8",
    )


def _write_manifest(
    options: PackageOptions,
    media_info: dict[str, Any],
) -> None:
    manifest = {
        "schema_version": 1,
        "kind": "vr360-hls-package",
        "projection": options.projection,
        "layout": options.layout,
        "segment_duration": options.segment_duration,
        "master_playlist": "master.m3u8",
        "source": {
            "file_name": options.input_file.name,
            "duration_seconds": media_info.get("duration", 0.0),
            "fps": media_info.get("fps", 0.0),
            "width": media_info.get("width", 0),
            "height": media_info.get("height", 0),
            "codec": media_info.get("codec", ""),
            "has_audio": bool(media_info.get("has_audio")),
        },
        "renditions": [
            {
                "id": rendition.id,
                "label": rendition.label,
                "width": rendition.width,
                "height": rendition.height,
                "video_bitrate_bps": rendition.bitrate_bps,
                "playlist": f"{rendition.id}/playlist.m3u8",
            }
            for rendition in sorted(options.renditions, key=lambda item: item.bitrate_bps)
        ],
    }

    (options.output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def package_video(
    options: PackageOptions,
    progress_callback: ProgressCallback | None = None,
    log_callback: LogCallback | None = None,
) -> dict[str, Any]:
    if not options.input_file.exists():
        raise RuntimeError(f"Input file not found: {options.input_file}")
    if not options.renditions:
        raise RuntimeError("Select at least one quality profile")
    if not _tool_exists(options.ffmpeg_path):
        raise RuntimeError(f"ffmpeg not found: {options.ffmpeg_path}")

    options.output_dir.mkdir(parents=True, exist_ok=True)

    media_info = probe_media(options.input_file, options.ffprobe_path)
    duration = max(float(media_info.get("duration") or 0.0), 0.001)
    total_renditions = len(options.renditions)

    if log_callback:
        log_callback(
            f"Source: {media_info['width']}x{media_info['height']} "
            f"{media_info['fps']:.3f} fps, {media_info['duration']:.1f} s"
        )

    for index, rendition in enumerate(
        sorted(options.renditions, key=lambda item: item.bitrate_bps)
    ):
        if log_callback:
            log_callback(
                f"[{index + 1}/{total_renditions}] "
                f"{rendition.label}: {rendition.width}x{rendition.height} "
                f"{rendition.bitrate_mbps:g} Mbps"
            )

        command = _build_variant_command(options, rendition, media_info)
        if log_callback:
            log_callback(" ".join(f'"{part}"' if " " in part else part for part in command))

        rendition_dir = options.output_dir / rendition.id
        for stale in rendition_dir.glob("seg_*.m4s"):
            stale.unlink(missing_ok=True)
        for stale_name in ("init.mp4", "playlist.m3u8"):
            (rendition_dir / stale_name).unlink(missing_ok=True)

        process = subprocess.Popen(
            command,
            cwd=rendition_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )

        last_output = []
        assert process.stdout is not None

        for raw_line in process.stdout:
            line = raw_line.strip()
            if not line:
                continue

            if line.startswith("out_time_us="):
                try:
                    out_time_seconds = int(line.split("=", 1)[1]) / 1_000_000.0
                    current_fraction = min(max(out_time_seconds / duration, 0.0), 1.0)
                    overall = (index + current_fraction) / total_renditions
                    if progress_callback:
                        progress_callback(overall)
                except ValueError:
                    pass
                continue

            if line.startswith("out_time_ms="):
                try:
                    out_time_seconds = int(line.split("=", 1)[1]) / 1_000_000.0
                    current_fraction = min(max(out_time_seconds / duration, 0.0), 1.0)
                    overall = (index + current_fraction) / total_renditions
                    if progress_callback:
                        progress_callback(overall)
                except ValueError:
                    pass
                continue

            if line.startswith(("progress=", "frame=", "fps=", "bitrate=", "speed=")):
                continue

            last_output.append(line)
            last_output = last_output[-25:]
            if log_callback:
                log_callback(line)

        return_code = process.wait()
        if return_code != 0:
            tail = "\n".join(last_output)
            raise RuntimeError(
                f"FFmpeg failed for {rendition.label} with code {return_code}"
                + (f"\n{tail}" if tail else "")
            )

        if progress_callback:
            progress_callback((index + 1) / total_renditions)

    _write_master_playlist(options)
    _write_manifest(options, media_info)

    if log_callback:
        log_callback(f"Done: {options.output_dir}")

    if progress_callback:
        progress_callback(1.0)

    return media_info
