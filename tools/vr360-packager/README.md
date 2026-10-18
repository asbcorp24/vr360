# VR360 Video Packager

Desktop tool for preparing adaptive 180/360 video for the VR360 Android client.

The first version produces **HLS VOD with CMAF/fMP4 segments** and several synchronized H.264 quality levels. All renditions use the same segment duration and forced keyframe cadence so the Android player can switch quality and seek on the same timeline.

## What it creates

Example:

```text
lesson_hls/
├── manifest.json
├── master.m3u8
├── low/
│   ├── init.mp4
│   ├── playlist.m3u8
│   ├── seg_00000.m4s
│   └── ...
├── 2k/
├── 3k/
└── 4k/
```

Default ladder:

| Profile | Resolution | Video bitrate |
|---|---:|---:|
| LOW | 1280x640 | 2 Mbps |
| 2K | 2048x1024 | 4 Mbps |
| 3K | 2880x1440 | 8 Mbps |
| 4K | 3840x1920 | 14 Mbps |

The bitrate values can be edited directly in the GUI.

## Requirements

- Windows 10/11 or Linux
- Python 3.10+
- FFmpeg and ffprobe available in PATH
- Python package PySide6

The current workstation FFmpeg build is suitable as long as it contains the selected encoder:

- CPU: `libx264`
- NVIDIA: `h264_nvenc`
- AMD: `h264_amf`

Check encoders:

```bash
ffmpeg -hide_banner -encoders
```

## Run on Windows

From this folder:

```bat
run.bat
```

The script creates `.venv`, installs PySide6 and starts the GUI.

Manual start:

```bat
py -3 -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python main.py
```

## Run on Linux

```bash
chmod +x run.sh
./run.sh
```

## Packaging rules

The packager currently uses:

- H.264 video
- AAC stereo audio, 48 kHz
- HLS VOD
- CMAF/fMP4 segments (`.m4s`)
- default 2 second segments
- `#EXT-X-INDEPENDENT-SEGMENTS`
- forced keyframes at the segment boundary
- fixed GOP calculated from source FPS × segment duration

This is intentional. The Android client will later be able to:

1. cache LOW completely before a lesson;
2. cache higher qualities ahead of playback;
3. switch quality automatically;
4. seek from the teacher WebSocket timeline;
5. start immediately from cached LOW if a high-quality segment is not yet local.

## manifest.json

`manifest.json` is metadata for our application, separate from HLS:

```json
{
  "schema_version": 1,
  "kind": "vr360-hls-package",
  "projection": "equirect360",
  "layout": "mono",
  "segment_duration": 2.0,
  "master_playlist": "master.m3u8"
}
```

It also contains source information and the list of available renditions.

## Next integration stage

The next step is the Android player:

```text
HTTP server
   ↓
master.m3u8 + CMAF segments
   ↓
AndroidX Media3 / ExoPlayer
   ↓
SimpleCache + DownloadManager
   ↓
Surface
   ↓
existing OpenGL/Cardboard Renderer.cpp
```

Teacher control remains separate over WebSocket:

```text
prepare_lesson
play
pause
seek
quality_limit
sync
```

The video delivery path and control path are intentionally independent.
