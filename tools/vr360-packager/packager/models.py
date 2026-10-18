from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Iterable


@dataclass(frozen=True)
class Rendition:
    id: str
    label: str
    width: int
    height: int
    bitrate_mbps: float

    @property
    def bitrate_bps(self) -> int:
        return int(self.bitrate_mbps * 1_000_000)


DEFAULT_RENDITIONS: tuple[Rendition, ...] = (
    Rendition("low", "LOW", 1280, 640, 2.0),
    Rendition("2k", "2K", 2048, 1024, 4.0),
    Rendition("3k", "3K", 2880, 1440, 8.0),
    Rendition("4k", "4K", 3840, 1920, 14.0),
)


@dataclass
class PackageOptions:
    input_file: Path
    output_dir: Path
    projection: str = "equirect360"
    layout: str = "mono"
    encoder: str = "libx264"
    segment_duration: float = 2.0
    audio_bitrate_kbps: int = 128
    renditions: list[Rendition] = field(default_factory=lambda: list(DEFAULT_RENDITIONS))
    ffmpeg_path: str = "ffmpeg"
    ffprobe_path: str = "ffprobe"


ProgressCallback = Callable[[float], None]
LogCallback = Callable[[str], None]
