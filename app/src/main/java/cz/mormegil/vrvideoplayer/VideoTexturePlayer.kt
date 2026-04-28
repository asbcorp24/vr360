package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer.OnVideoSizeChangedListener
import android.net.Uri
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VideoTexturePlayer(
    private val context: Context,
    private val videoSizeChangedListener: OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "VlcTexturePlayer"

        /*
         * Твои реальные параметры сервера:
         * HIGH : rtp://239.0.0.1:5004
         * payload_type=96
         * resolution=2048x1024
         */
        private const val MULTICAST_IP = "239.0.0.1"
        private const val MULTICAST_PORT = 5004

        private const val VIDEO_WIDTH = 2048
        private const val VIDEO_HEIGHT = 1024

        private const val RTP_PAYLOAD_TYPE = 96
    }

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null

    private val frameAvailable = AtomicBoolean(false)

    private var videoPosition: Float = 0.0f
    private var initialized = false

    fun initializePlayback(texName: Int) {
        Log.d(TAG, "initializePlayback texName=$texName")

        cleanup()

        initialized = true

        val texture = SurfaceTexture(texName)
        texture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)

        surfaceTexture = texture
        texture.setOnFrameAvailableListener(this)

        val videoSurface = Surface(texture)
        surface = videoSurface

        val vlcOptions = arrayListOf(
            "--verbose=2",

            "--network-caching=150",
            "--live-caching=150",
            "--rtp-caching=150",

            "--clock-jitter=0",
            "--clock-synchro=0",

            "--drop-late-frames",
            "--skip-frames",

            "--no-audio",

            /*
             * На некоторых телефонах аппаратный декодер через VLC + SurfaceTexture
             * может давать чёрный экран. Если будет чёрный экран при VLC Playing,
             * замени any на disabled.
             */
            "--avcodec-hw=any"
        )

        val vlc = LibVLC(context.applicationContext, vlcOptions)
        libVlc = vlc

        val player = MediaPlayer(vlc)
        vlcPlayer = player

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> {
                    Log.d(TAG, "VLC Opening")
                }

                MediaPlayer.Event.Buffering -> {
                    Log.d(TAG, "VLC Buffering ${event.buffering}")
                }

                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "VLC Playing")

                    videoSizeChangedListener.onVideoSizeChanged(
                        null,
                        VIDEO_WIDTH,
                        VIDEO_HEIGHT
                    )
                }

                MediaPlayer.Event.Paused -> {
                    Log.d(TAG, "VLC Paused")
                }

                MediaPlayer.Event.Stopped -> {
                    Log.d(TAG, "VLC Stopped")
                }

                MediaPlayer.Event.EndReached -> {
                    Log.w(TAG, "VLC EndReached")
                }

                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC EncounteredError")
                }

                else -> {
                    Log.d(TAG, "VLC event=${event.type}")
                }
            }
        }

        player.vlcVout.setVideoSurface(videoSurface, null)
        player.vlcVout.setWindowSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        player.vlcVout.attachViews()

        val sdpFile = createSdpFile()
        val mediaPath = "file://${sdpFile.absolutePath}"

        Log.d(TAG, "Opening SDP path: $mediaPath")

        val media = Media(vlc, Uri.parse(mediaPath))

        media.addOption(":network-caching=150")
        media.addOption(":live-caching=150")
        media.addOption(":rtp-caching=150")

        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")

        media.addOption(":drop-late-frames")
        media.addOption(":skip-frames")

        media.addOption(":no-audio")

        /*
         * ВАЖНО:
         * Это помогает LibVLC правильно обработать RTP SDP.
         */
        media.addOption(":demux=live555")

        player.media = media
        media.release()

        val result = player.play()
        Log.d(TAG, "VLC play() result=$result")
    }

    fun rewind() {
        Log.d(TAG, "rewind ignored: live multicast")
    }

    fun seek(relSeek: Int) {
        Log.d(TAG, "seek ignored: live multicast relSeek=$relSeek")
    }

    fun getVideoPosition(): Float {
        return videoPosition
    }

    fun updateIfNeeded() {
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
            } catch (e: Throwable) {
                Log.e(TAG, "updateTexImage error", e)
            }
        }
    }

    override fun onFrameAvailable(tex: SurfaceTexture?) {
        frameAvailable.set(true)
        videoPosition = 0.0f

        /*
         * Эту строку специально оставляем.
         * Если она появляется в Logcat — кадры реально доходят до OpenGL texture.
         */
        Log.d(TAG, "FRAME AVAILABLE")
    }

    fun onPause() {
        try {
            Log.d(TAG, "onPause")

            /*
             * Для live-потока лучше stop, чем pause,
             * потому что multicast дальше идёт, а буфер устаревает.
             */
            vlcPlayer?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "onPause error", e)
        }
    }

    fun onResume() {
        try {
            Log.d(TAG, "onResume initialized=$initialized")

            /*
             * Не запускаем VLC здесь, если initializePlayback ещё не был вызван нативным рендером.
             */
            if (initialized) {
                vlcPlayer?.play()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onResume error", e)
        }
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy")
        cleanup()
    }

    private fun cleanup() {
        try {
            vlcPlayer?.setEventListener(null)
            vlcPlayer?.stop()
            vlcPlayer?.vlcVout?.detachViews()
            vlcPlayer?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "VLC cleanup error", e)
        }

        vlcPlayer = null

        try {
            libVlc?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "LibVLC release error", e)
        }

        libVlc = null

        try {
            surface?.release()
        } catch (_: Throwable) {
        }

        surface = null

        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }

        surfaceTexture = null
    }

    private fun createSdpFile(): File {
        val sdpText = """
v=0
o=- 0 0 IN IP4 0.0.0.0
s=RTP Multicast HIGH VR
t=0 0
m=video $MULTICAST_PORT RTP/AVP $RTP_PAYLOAD_TYPE
c=IN IP4 $MULTICAST_IP
b=AS:6000
a=framerate:30
a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000
a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1;profile-level-id=42C028;sprop-parameter-sets=Z0LAKNoB4B5oQAAAAwBAAAAPOJAAC3GwABbjeKSAHjBlQA==,aM48gA==
a=recvonly
""".trimIndent()

        val file = File(context.cacheDir, "multicast_high_only.sdp")
        file.writeText(sdpText, Charsets.US_ASCII)

        Log.d(TAG, "SDP file: ${file.absolutePath}")
        Log.d(TAG, "\n$sdpText")

        return file
    }
}