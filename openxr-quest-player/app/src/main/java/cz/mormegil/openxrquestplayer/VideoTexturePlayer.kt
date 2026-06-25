package cz.mormegil.openxrquestplayer

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
import java.util.concurrent.atomic.AtomicBoolean

class VideoTexturePlayer(
    private val context: Context,
    private val videoSizeChangedListener: OnVideoSizeChangedListener,
    initialStreamUrl: String,
    private val onPlaybackFailure: (message: String) -> Unit
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "OpenXRVlcPlayer"
        private const val VIDEO_WIDTH = 2048
        private const val VIDEO_HEIGHT = 1024
    }

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private val frameAvailable = AtomicBoolean(false)
    private var videoPosition: Float = 0.0f
    private var initialized = false
    private var currentStreamUrl: String = initialStreamUrl
    private var currentTextureName: Int? = null

    fun initializePlayback(texName: Int) {
        Log.d(TAG, "initializePlayback texName=$texName streamUrl=$currentStreamUrl")
        cleanup()
        initialized = true
        currentTextureName = texName

        val texture = SurfaceTexture(texName)
        texture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        texture.setOnFrameAvailableListener(this)
        surfaceTexture = texture

        val videoSurface = Surface(texture)
        surface = videoSurface

        val vlcOptions = arrayListOf(
            "--verbose=2",
            "--ipv4",
            "--aout=opensles",
            "--no-video-title-show",
            "--network-caching=300",
            "--live-caching=300",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--avcodec-hw=any"
        )

        val vlc = LibVLC(context.applicationContext, vlcOptions)
        libVlc = vlc

        val player = MediaPlayer(vlc)
        vlcPlayer = player
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> Log.d(TAG, "VLC Opening")
                MediaPlayer.Event.Buffering -> Log.d(TAG, "VLC Buffering ${event.buffering}")
                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "VLC Playing")
                    videoSizeChangedListener.onVideoSizeChanged(null, VIDEO_WIDTH, VIDEO_HEIGHT)
                }
                MediaPlayer.Event.Paused -> Log.d(TAG, "VLC Paused")
                MediaPlayer.Event.Stopped -> Log.d(TAG, "VLC Stopped")
                MediaPlayer.Event.EndReached -> Log.w(TAG, "VLC EndReached")
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC EncounteredError")
                    onPlaybackFailure("VLC could not open stream:\n$currentStreamUrl")
                }
                else -> Log.d(TAG, "VLC event=${event.type}")
            }
        }

        player.vlcVout.setVideoSurface(videoSurface, null)
        player.vlcVout.setWindowSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        player.vlcVout.attachViews()

        val media = Media(vlc, Uri.parse(currentStreamUrl))
        media.addOption(":demux=ts")
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        player.media = media
        media.release()

        val result = player.play()
        Log.d(TAG, "VLC play() result=$result")
    }

    fun updateStreamUrl(streamUrl: String) {
        currentStreamUrl = streamUrl.trim()
        val texName = currentTextureName
        if (initialized && texName != null) {
            initializePlayback(texName)
        }
    }

    fun currentStreamUrl(): String = currentStreamUrl

    fun getVideoPosition(): Float = videoPosition

    fun updateIfNeeded() {
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
            } catch (e: Throwable) {
                Log.e(TAG, "updateTexImage error", e)
            }
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
        videoPosition = 0.0f
    }

    fun onPause() {
        try {
            vlcPlayer?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "onPause error", e)
        }
    }

    fun onResume() {
        try {
            if (initialized) {
                vlcPlayer?.play()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onResume error", e)
        }
    }

    fun onDestroy() {
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
        initialized = false
    }
}
