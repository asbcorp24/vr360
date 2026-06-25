package cz.mormegil.openxrquestplayer

object QuestNativeBridge {
    external fun nativeAttachVideoPlayer(videoTexturePlayer: VideoTexturePlayer)
    external fun nativeShowTextMark(
        id: String,
        pixelsArgb: IntArray,
        bitmapWidth: Int,
        bitmapHeight: Int,
        x: Float,
        y: Float,
        z: Float,
        yawDeg: Float,
        pitchDeg: Float,
        markWidth: Float,
        markHeight: Float,
        durationMs: Int
    )
    external fun nativeSendLookAt(
        yawDeg: Float,
        pitchDeg: Float,
        fovDeg: Float,
        durationMs: Int
    )

    external fun nativeSetVideoMode(
        inputLayout: Int,
        inputMode: Int,
        outputMode: Int
    )

    external fun nativeClearTextMarks()
    external fun nativeOnVideoSizeChanged(width: Int, height: Int)

    init {
        System.loadLibrary("openxr_quest_player")
    }
}

enum class InputLayout {
    Mono,
    StereoHoriz,
    StereoVert,
    AnaglyphRedCyan
}

enum class InputMode {
    PlainFov,
    Equirect180,
    Equirect360,
    CubeMap,
    EquiangCubeMap,
    Pyramid,
    Panorama180,
    Panorama360,
    DualFisheye180
}

enum class OutputMode {
    MonoLeft,
    MonoRight,
    CardboardStereo
}
