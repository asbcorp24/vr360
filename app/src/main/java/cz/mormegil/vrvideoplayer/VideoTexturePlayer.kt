package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Media3/ExoPlayer transport for the existing OpenGL/Cardboard renderer.
 *
 * Supports HLS/CMAF adaptive bitrate, disk cache and the old HTTP MPEG-TS
 * stream during migration. Decoded frames still go through SurfaceTexture
 * into the existing C++ Renderer.cpp.
 */
class VideoTexturePlayer(
    private val context: Context,
    private val videoSizeChangedListener: MediaPlayer.OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "Media3TexturePlayer"
        private const val DEFAULT_STREAM_URL =
            "http://192.168.10.1:7088/udp/239.0.0.1:5004"
        private const val FALLBACK_WIDTH = 2048
        private const val FALLBACK_HEIGHT = 1024
        private const val CACHE_SIZE_BYTES = 6L * 1024L * 1024L * 1024L

        @Volatile
        private var sharedCache: SimpleCache? = null

        @Volatile
        private var sharedDatabaseProvider: StandaloneDatabaseProvider? = null

        private val precacheExecutor = Executors.newSingleThreadExecutor()

        private fun getCache(context: Context): SimpleCache {
            sharedCache?.let { return it }

            synchronized(this) {
                sharedCache?.let { return it }

                val appContext = context.applicationContext
                val databaseProvider =
                    sharedDatabaseProvider ?: StandaloneDatabaseProvider(appContext).also {
                        sharedDatabaseProvider = it
                    }

                val cache = SimpleCache(
                    File(appContext.noBackupFilesDir, "vr360-media3-cache"),
                    LeastRecentlyUsedCacheEvictor(CACHE_SIZE_BYTES),
                    databaseProvider
                )

                sharedCache = cache
                return cache
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var player: ExoPlayer? = null

    private val frameAvailable = AtomicBoolean(false)

    @Volatile
    private var normalizedVideoPosition = 0.0f

    @Volatile
    private var currentPositionMs = 0L

    @Volatile
    private var bufferedPositionMs = 0L

    @Volatile
    private var lowPrecachePercent = 0

    private var streamSourceUrl = DEFAULT_STREAM_URL
    private var initialized = false
    private var shouldResumePlayback = true
    private var reconnectRunnable: Runnable? = null

    private val positionUpdateRunnable = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                try {
                    currentPositionMs = p.currentPosition.coerceAtLeast(0L)
                    bufferedPositionMs = p.bufferedPosition.coerceAtLeast(0L)

                    val durationMs = p.duration
                    normalizedVideoPosition =
                        if (durationMs > 0L) {
                            (currentPositionMs.toDouble() / durationMs.toDouble())
                                .coerceIn(0.0, 1.0)
                                .toFloat()
                        } else {
                            0.0f
                        }
                } catch (e: Throwable) {
                    Log.w(TAG, "position update failed", e)
                }
            }

            if (initialized) {
                mainHandler.postDelayed(this, 250L)
            }
        }
    }

    /**
     * Called from Renderer.cpp on GLSurfaceView's GL thread.
     */
    fun initializePlayback(texName: Int) {
        Log.d(TAG, "initializePlayback texName=$texName")

        initialized = true
        frameAvailable.set(false)

        try {
            surface?.release()
        } catch (_: Throwable) {
        }

        try {
            surfaceTexture?.release()
        } catch (_: Throwable) {
        }

        val texture = SurfaceTexture(texName)
        texture.setDefaultBufferSize(FALLBACK_WIDTH, FALLBACK_HEIGHT)
        texture.setOnFrameAvailableListener(this)

        val videoSurface = Surface(texture)
        surfaceTexture = texture
        surface = videoSurface

        mainHandler.removeCallbacks(positionUpdateRunnable)
        mainHandler.post {
            buildPlayerAndOpen(videoSurface)
            mainHandler.post(positionUpdateRunnable)
        }
    }

    private fun buildPlayerAndOpen(videoSurface: Surface) {
        if (!initialized) return

        releasePlayerInternal()

        val appContext = context.applicationContext
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VR360-Android/2.0")
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        val cacheFactory = CacheDataSource.Factory()
            .setCache(getCache(appContext))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
            .setDataSourceFactory(cacheFactory)

        val exo = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING ->
                        Log.d(TAG, "BUFFERING pos=" + exo.currentPosition + " buffered=" + exo.bufferedPosition)

                    Player.STATE_READY -> {
                        cancelPendingReconnect()
                        Log.d(TAG, "READY hls=" + isHlsUrl(streamSourceUrl) + " duration=" + exo.duration)
                    }

                    Player.STATE_ENDED -> {
                        Log.d(TAG, "ENDED")
                        if (!isHlsUrl(streamSourceUrl)) {
                            scheduleStreamReconnect("legacy stream ended")
                        }
                    }

                    Player.STATE_IDLE -> Log.d(TAG, "IDLE")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    TAG,
                    "Player error code=" + error.errorCode + " message=" + error.message,
                    error
                )
                scheduleStreamReconnect("player error " + error.errorCode)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return

                Log.d(TAG, "video size=" + videoSize.width + "x" + videoSize.height)
                videoSizeChangedListener.onVideoSizeChanged(
                    null,
                    videoSize.width,
                    videoSize.height
                )
            }

            override fun onRenderedFirstFrame() {
                Log.d(TAG, "first frame rendered")
            }
        })

        exo.setVideoSurface(videoSurface)
        player = exo

        openStream(0L, shouldResumePlayback)
    }

    private fun createMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))

        when {
            isHlsUrl(url) -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            isLegacyTransportStreamUrl(url) -> builder.setMimeType(MimeTypes.VIDEO_MP2T)
        }

        return builder.build()
    }

    private fun openStream(startPositionMs: Long, autoPlay: Boolean) {
        val p = player ?: return
        val url = streamSourceUrl.trim()

        if (url.isBlank()) {
            Log.e(TAG, "openStream ignored: empty URL")
            return
        }

        cancelPendingReconnect()

        Log.d(
            TAG,
            "Opening " + if (isHlsUrl(url)) "HLS ABR: $url" else "HTTP TS/progressive: $url"
        )

        p.setMediaItem(createMediaItem(url), startPositionMs.coerceAtLeast(0L))
        p.prepare()
        p.playWhenReady = autoPlay
    }

    fun rewind() {
        seekTo(0L)
    }

    fun seek(relSeek: Int) {
        runOnPlayerThread {
            val p = player ?: return@runOnPlayerThread
            val target = (p.currentPosition + relSeek.toLong()).coerceAtLeast(0L)
            p.seekTo(target)
            Log.d(TAG, "relative seek $relSeek -> $target")
        }
    }

    fun seekTo(positionMs: Long) {
        runOnPlayerThread {
            player?.seekTo(positionMs.coerceAtLeast(0L))
            Log.d(TAG, "seekTo $positionMs")
        }
    }

    fun playFromServer(positionMs: Long? = null) {
        shouldResumePlayback = true

        runOnPlayerThread {
            val p = player ?: return@runOnPlayerThread
            if (positionMs != null) {
                p.seekTo(positionMs.coerceAtLeast(0L))
            }
            p.play()
        }
    }

    fun pauseFromServer(positionMs: Long? = null) {
        shouldResumePlayback = false

        runOnPlayerThread {
            val p = player ?: return@runOnPlayerThread
            if (positionMs != null) {
                p.seekTo(positionMs.coerceAtLeast(0L))
            }
            p.pause()
        }
    }

    /**
     * maxHeight <= 0 means unrestricted AUTO ABR.
     */
    fun setMaxVideoHeight(maxHeight: Int) {
        runOnPlayerThread {
            val p = player ?: return@runOnPlayerThread
            val builder = p.trackSelectionParameters.buildUpon()

            if (maxHeight <= 0) {
                builder.clearVideoSizeConstraints()
            } else {
                builder.setMaxVideoSize(Int.MAX_VALUE, maxHeight)
            }

            p.trackSelectionParameters = builder.build()
            Log.d(TAG, "ABR maxHeight=$maxHeight")
        }
    }

    fun getCurrentPositionMs(): Long = currentPositionMs

    fun getBufferedPositionMs(): Long = bufferedPositionMs

    /**
     * Historical Renderer progress value: normalized 0..1.
     */
    fun getVideoPosition(): Float = normalizedVideoPosition

    /**
     * Called from the GL thread before rendering.
     */
    fun updateIfNeeded() {
        if (!frameAvailable.getAndSet(false)) return

        try {
            surfaceTexture?.updateTexImage()
        } catch (e: Throwable) {
            Log.e(TAG, "updateTexImage error", e)
        }
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }

    fun onPause() {
        cancelPendingReconnect()

        runOnPlayerThread {
            val p = player
            if (p != null) {
                shouldResumePlayback = p.playWhenReady
                p.pause()
            }
        }
    }

    fun onResume() {
        if (!initialized) return

        runOnPlayerThread {
            if (shouldResumePlayback) {
                player?.play()
            }
        }
    }

    fun onDestroy() {
        initialized = false
        cancelPendingReconnect()
        mainHandler.removeCallbacks(positionUpdateRunnable)

        runOnPlayerThread {
            releasePlayerInternal()

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
    }

    /**
     * Load a lesson/stream from the teacher without forcing immediate playback.
     * This is the entry point for prepare_lesson/load over WebSocket.
     */
    fun loadFromServer(url: String, positionMs: Long = 0L, autoPlay: Boolean = false) {
        val normalized = normalizeHttpStreamUrl(url)
        if (normalized.isBlank()) return

        streamSourceUrl = normalized
        shouldResumePlayback = autoPlay

        Log.d(
            TAG,
            "loadFromServer url=$streamSourceUrl position=$positionMs autoPlay=$autoPlay"
        )

        if (isHlsUrl(normalized)) {
            precacheLowVariant(normalized)
        }

        if (initialized) {
            runOnPlayerThread {
                openStream(positionMs.coerceAtLeast(0L), autoPlay)
            }
        }
    }

    /**
     * Accepts both old udpxy HTTP transport and new HLS master.m3u8.
     */
    fun setStreamSourceUrl(url: String) {
        val normalized = normalizeHttpStreamUrl(url)
        if (normalized == streamSourceUrl) return

        streamSourceUrl = normalized
        Log.d(TAG, "Updated stream source: $streamSourceUrl")

        if (initialized) {
            runOnPlayerThread {
                openStream(0L, shouldResumePlayback)
            }
        }
    }

    fun getLowPrecachePercent(): Int = lowPrecachePercent

    /**
     * Our packager always creates low/playlist.m3u8 next to master.m3u8.
     * We preload the complete LOW variant into the same SimpleCache that ExoPlayer reads.
     */
    private fun precacheLowVariant(masterUrl: String) {
        lowPrecachePercent = 0

        precacheExecutor.execute {
            try {
                val masterUri = URI(masterUrl)
                val lowPlaylistUrl = masterUri.resolve("low/playlist.m3u8").toString()

                Log.d(TAG, "LOW precache playlist=$lowPlaylistUrl")

                val connection = URL(lowPlaylistUrl).openConnection().apply {
                    connectTimeout = 5_000
                    readTimeout = 15_000
                    setRequestProperty("User-Agent", "VR360-Android/2.0")
                }

                val playlistText = connection.getInputStream()
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }

                val lowBase = URI(lowPlaylistUrl)
                val resources = LinkedHashSet<String>()
                resources += masterUrl
                resources += lowPlaylistUrl

                Regex("""#EXT-X-MAP:.*URI="([^"]+)"""")
                    .findAll(playlistText)
                    .forEach { match ->
                        resources += lowBase.resolve(match.groupValues[1]).toString()
                    }

                playlistText.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .forEach { relative ->
                        resources += lowBase.resolve(relative).toString()
                    }

                if (resources.isEmpty()) {
                    lowPrecachePercent = 100
                    return@execute
                }

                resources.forEachIndexed { index, url ->
                    cacheUrl(url)
                    lowPrecachePercent = (((index + 1) * 100.0) / resources.size)
                        .toInt()
                        .coerceIn(0, 100)

                    Log.d(
                        TAG,
                        "LOW precache $lowPrecachePercent% (" +
                            (index + 1) + "/" + resources.size + ")"
                    )
                }

                lowPrecachePercent = 100
                Log.d(TAG, "LOW precache complete")
            } catch (e: Throwable) {
                Log.e(TAG, "LOW precache failed", e)
            }
        }
    }

    private fun cacheUrl(url: String) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("VR360-Android/2.0")
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(20_000)
            .setAllowCrossProtocolRedirects(true)

        val cacheDataSource = CacheDataSource.Factory()
            .setCache(getCache(context.applicationContext))
            .setUpstreamDataSourceFactory(httpFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            .createDataSource()

        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .build()

        CacheWriter(
            cacheDataSource,
            dataSpec,
            null,
            null
        ).cache()
    }

    private fun scheduleStreamReconnect(reason: String) {
        if (!initialized) return

        cancelPendingReconnect()

        val resumePosition = currentPositionMs
        val runnable = Runnable {
            reconnectRunnable = null
            if (!initialized) return@Runnable

            Log.w(TAG, "Reconnecting: $reason")
            openStream(
                if (isHlsUrl(streamSourceUrl)) resumePosition else 0L,
                shouldResumePlayback
            )
        }

        reconnectRunnable = runnable
        mainHandler.postDelayed(runnable, 2_000L)
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let(mainHandler::removeCallbacks)
        reconnectRunnable = null
    }

    private fun releasePlayerInternal() {
        cancelPendingReconnect()

        try {
            player?.clearVideoSurface()
            player?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "player release error", e)
        }

        player = null
    }

    private fun runOnPlayerThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun normalizeHttpStreamUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        return if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else {
            "http://$value"
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        val clean = url.substringBefore('?').substringBefore('#')
        return clean.endsWith(".m3u8", ignoreCase = true)
    }

    private fun isLegacyTransportStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/udp/") ||
            lower.endsWith(".ts") ||
            lower.contains(".ts?")
    }
}
