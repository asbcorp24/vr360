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
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Media3/ExoPlayer transport for the existing OpenGL/Cardboard renderer.
 *
 * Supports HLS/CMAF adaptive bitrate and disk cache. Decoded frames still
 * go through SurfaceTexture
 * into the existing C++ Renderer.cpp.
 */
class VideoTexturePlayer(
    private val context: Context,
    private val videoSizeChangedListener: MediaPlayer.OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "Media3TexturePlayer"
        private const val FALLBACK_WIDTH = 2048
        private const val FALLBACK_HEIGHT = 1024
        private const val CACHE_HARD_BUDGET_BYTES = 12L * 1024L * 1024L * 1024L
        private const val CACHE_MIN_BUDGET_BYTES = 4L * 1024L * 1024L * 1024L
        private const val CACHE_FREE_SPACE_RESERVE_BYTES = 2L * 1024L * 1024L * 1024L
        private const val CACHE_META_PREFS = "vr360_cache_meta"
        private const val CACHE_META_LESSONS = "lesson_bases"

        private const val LOW_BITRATE_BPS = 2_000_000L + 128_000L
        private const val TWO_K_BITRATE_BPS = 4_000_000L + 128_000L
        private const val THREE_K_BITRATE_BPS = 8_000_000L + 128_000L

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
                    NoOpCacheEvictor(),
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
    private val precacheGeneration = AtomicLong(0L)

    @Volatile
    private var normalizedVideoPosition = 0.0f

    @Volatile
    private var currentPositionMs = 0L

    @Volatile
    private var bufferedPositionMs = 0L

    @Volatile
    private var lowPrecachePercent = 0

    @Volatile
    private var precacheStatusText = "LOW 0%"

    private var streamSourceUrl = ""
    private var initialized = false
    private var shouldResumePlayback = false
    private var reconnectRunnable: Runnable? = null

    @Volatile
    private var currentVideoHeight = 0

    @Volatile
    private var maxVideoHeight = 0

    @Volatile
    private var playbackSpeed = 1.0f

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

                currentVideoHeight = videoSize.height
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
        exo.setPlaybackSpeed(playbackSpeed)
        player = exo

        if (streamSourceUrl.isNotBlank()) {
            openStream(0L, shouldResumePlayback)
        } else {
            Log.d(TAG, "Player ready; waiting for HLS load command")
        }
    }

    private fun createMediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(Uri.parse(url))

        if (isHlsUrl(url)) {
            builder.setMimeType(MimeTypes.APPLICATION_M3U8)
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

    fun stopFromServer() {
        shouldResumePlayback = false
        currentPositionMs = 0L
        bufferedPositionMs = 0L
        normalizedVideoPosition = 0.0f

        runOnPlayerThread {
            player?.stop()
            player?.clearMediaItems()
            Log.d(TAG, "stopFromServer")
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        playbackSpeed = safeSpeed

        runOnPlayerThread {
            player?.setPlaybackSpeed(safeSpeed)
            Log.d(TAG, "playbackSpeed=$safeSpeed")
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
            this.maxVideoHeight = maxHeight.coerceAtLeast(0)
            Log.d(TAG, "ABR maxHeight=$maxHeight")
        }
    }

    fun getCurrentPositionMs(): Long = currentPositionMs

    fun getBufferedPositionMs(): Long = bufferedPositionMs

    fun getCurrentVideoHeight(): Int = currentVideoHeight

    fun getMaxVideoHeight(): Int = maxVideoHeight

    fun getPlaybackSpeed(): Float = playbackSpeed

    fun getStreamSourceUrl(): String = streamSourceUrl

    fun isPlaying(): Boolean = player?.isPlaying == true

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
        markLessonUsed(normalized)

        Log.d(
            TAG,
            "loadFromServer url=$streamSourceUrl position=$positionMs autoPlay=$autoPlay"
        )

        if (isHlsUrl(normalized)) {
            precacheAdaptiveVariants(normalized)
        }

        if (initialized) {
            runOnPlayerThread {
                openStream(positionMs.coerceAtLeast(0L), autoPlay)
            }
        }
    }

    /**
     * Updates the current HTTP source. The classroom server normally sends
     * an HLS master.m3u8 URL over WebSocket.
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

    fun getPrecacheStatusText(): String = precacheStatusText

    /**
     * Pre-cache policy for classroom playback:
     *   LOW -> always first and highest priority
     *   2K  -> after LOW if storage permits
     *   3K  -> after 2K if storage permits
     *   4K  -> never pre-cached; it stays normal read-through ABR cache
     *
     * Old lessons are deleted as complete /abr/<id>/ groups when space is
     * needed. The current lesson is never selected as an eviction victim.
     */
    private fun precacheAdaptiveVariants(masterUrl: String) {
        lowPrecachePercent = 0
        precacheStatusText = "LOW 0%"
        val generation = precacheGeneration.incrementAndGet()
        val lessonBase = lessonBaseUrl(masterUrl)
        markLessonUsed(masterUrl)

        precacheExecutor.execute {
            try {
                val variants = listOf(
                    VariantPrecachePlan("low", "LOW", LOW_BITRATE_BPS, true),
                    VariantPrecachePlan("2k", "2K", TWO_K_BITRATE_BPS, false),
                    VariantPrecachePlan("3k", "3K", THREE_K_BITRATE_BPS, false)
                )
                val completed = mutableListOf<String>()

                for (variant in variants) {
                    if (generation != precacheGeneration.get()) {
                        Log.d(TAG, "Precache cancelled: newer lesson loaded")
                        return@execute
                    }

                    val playlistUrl =
                        URI(masterUrl).resolve("${variant.id}/playlist.m3u8").toString()
                    val playlistText = downloadText(playlistUrl)
                    val resources = collectHlsResources(
                        masterUrl = masterUrl,
                        playlistUrl = playlistUrl,
                        playlistText = playlistText
                    )

                    val durationSec = parsePlaylistDurationSec(playlistText)
                    val estimatedBytes = estimateVariantBytes(
                        bitrateBps = variant.bitrateBps,
                        durationSec = durationSec
                    )
                    val variantBase = URI(playlistUrl).resolve("./").toString()
                    val alreadyCached = cachedBytesForPrefix(variantBase)
                    val additionalBytes =
                        (estimatedBytes - alreadyCached).coerceAtLeast(0L)

                    val hasSpace = ensureSpaceForVariant(
                        currentLessonBase = lessonBase,
                        additionalBytes = additionalBytes,
                        priority = variant.priority
                    )

                    if (!hasSpace) {
                        Log.w(
                            TAG,
                            "${variant.label} precache skipped: not enough storage " +
                                "estimated=${formatBytes(estimatedBytes)} " +
                                "additional=${formatBytes(additionalBytes)}"
                        )
                        if (!variant.priority) {
                            precacheStatusText =
                                completed.joinToString("+").ifBlank { "LOW" } +
                                    " · ${variant.label}: нет места"
                            return@execute
                        }
                    }

                    precacheStatusText = "${variant.label} 0%"

                    Log.d(
                        TAG,
                        "${variant.label} precache start resources=${resources.size} " +
                            "duration=${"%.1f".format(durationSec)}s " +
                            "estimated=${formatBytes(estimatedBytes)}"
                    )

                    resources.forEachIndexed { index, url ->
                        if (generation != precacheGeneration.get()) {
                            Log.d(TAG, "Precache cancelled during ${variant.label}")
                            return@execute
                        }

                        cacheUrl(url)

                        val variantPercent =
                            (((index + 1) * 100.0) / resources.size.coerceAtLeast(1))
                                .toInt()
                                .coerceIn(0, 100)
                        precacheStatusText = "${variant.label} $variantPercent%"

                        if (variant.id == "low") {
                            lowPrecachePercent = variantPercent

                            Log.d(
                                TAG,
                                "LOW precache $lowPrecachePercent% (" +
                                    (index + 1) + "/" + resources.size + ")"
                            )
                        }
                    }

                    if (variant.id == "low") {
                        lowPrecachePercent = 100
                    }

                    completed += variant.label
                    precacheStatusText = completed.joinToString("+")
                    markLessonUsed(masterUrl)
                    Log.d(TAG, "${variant.label} precache complete")
                }
            } catch (e: Throwable) {
                precacheStatusText = "ошибка кэша"
                Log.e(TAG, "Adaptive precache failed", e)
            }
        }
    }

    private data class VariantPrecachePlan(
        val id: String,
        val label: String,
        val bitrateBps: Long,
        val priority: Boolean
    )

    private fun downloadText(url: String): String {
        val connection = URL(url).openConnection().apply {
            connectTimeout = 5_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "VR360-Android/2.0")
        }

        return connection.getInputStream()
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun collectHlsResources(
        masterUrl: String,
        playlistUrl: String,
        playlistText: String
    ): LinkedHashSet<String> {
        val base = URI(playlistUrl)
        val resources = LinkedHashSet<String>()

        resources += masterUrl
        resources += playlistUrl

        Regex("""#EXT-X-MAP:.*URI="([^"]+)"""")
            .findAll(playlistText)
            .forEach { match ->
                resources += base.resolve(match.groupValues[1]).toString()
            }

        playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { relative ->
                resources += base.resolve(relative).toString()
            }

        return resources
    }

    private fun parsePlaylistDurationSec(playlistText: String): Double {
        return Regex("""#EXTINF:([0-9]+(?:\.[0-9]+)?)""")
            .findAll(playlistText)
            .sumOf { it.groupValues[1].toDoubleOrNull() ?: 0.0 }
            .coerceAtLeast(0.0)
    }

    private fun estimateVariantBytes(
        bitrateBps: Long,
        durationSec: Double
    ): Long {
        if (durationSec <= 0.0) return 0L

        return ((bitrateBps.toDouble() * durationSec / 8.0) * 1.10)
            .toLong()
            .coerceAtLeast(0L)
    }

    private fun lessonBaseUrl(masterUrl: String): String {
        return try {
            val uri = URI(masterUrl)
            val path = uri.path.orEmpty()
            val basePath =
                if (path.contains("/")) path.substringBeforeLast("/") + "/" else "/"

            URI(
                uri.scheme,
                uri.userInfo,
                uri.host,
                uri.port,
                basePath,
                null,
                null
            ).toString()
        } catch (_: Throwable) {
            masterUrl.substringBeforeLast("/", masterUrl) + "/"
        }
    }

    private fun markLessonUsed(masterUrl: String) {
        if (!isHlsUrl(masterUrl)) return

        val base = lessonBaseUrl(masterUrl)
        val prefs = context.applicationContext
            .getSharedPreferences(CACHE_META_PREFS, Context.MODE_PRIVATE)
        val lessons = prefs.getStringSet(CACHE_META_LESSONS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()

        lessons += base
        prefs.edit()
            .putStringSet(CACHE_META_LESSONS, lessons)
            .putLong("last::$base", System.currentTimeMillis())
            .apply()
    }

    private fun maxManagedCacheBytes(): Long {
        val cacheDir = File(context.applicationContext.noBackupFilesDir, "vr360-media3-cache")
        val total = cacheDir.parentFile?.totalSpace?.takeIf { it > 0L }
            ?: CACHE_HARD_BUDGET_BYTES

        return minOf(
            CACHE_HARD_BUDGET_BYTES,
            maxOf(CACHE_MIN_BUDGET_BYTES, total / 3L)
        )
    }

    private fun ensureSpaceForVariant(
        currentLessonBase: String,
        additionalBytes: Long,
        priority: Boolean
    ): Boolean {
        if (additionalBytes <= 0L) return true

        val cache = getCache(context.applicationContext)
        val cacheDir = File(context.applicationContext.noBackupFilesDir, "vr360-media3-cache")
        val softBudget = maxManagedCacheBytes()

        fun enoughPhysicalSpace(): Boolean =
            cacheDir.usableSpace >= additionalBytes + CACHE_FREE_SPACE_RESERVE_BYTES

        fun enoughManagedBudget(): Boolean =
            cache.cacheSpace + additionalBytes <= softBudget

        if (enoughPhysicalSpace() && (priority || enoughManagedBudget())) {
            return true
        }

        removeOldestLessonsUntilSpace(
            currentLessonBase = currentLessonBase,
            additionalBytes = additionalBytes,
            requireManagedBudget = !priority
        )

        val physicalOk = enoughPhysicalSpace()
        val budgetOk = priority || enoughManagedBudget()

        if (!physicalOk || !budgetOk) {
            Log.w(
                TAG,
                "Storage still insufficient current=${formatBytes(cache.cacheSpace)} " +
                    "need=${formatBytes(additionalBytes)} " +
                    "free=${formatBytes(cacheDir.usableSpace)} " +
                    "budget=${formatBytes(softBudget)} priority=$priority"
            )
        }

        return physicalOk && budgetOk
    }

    private fun removeOldestLessonsUntilSpace(
        currentLessonBase: String,
        additionalBytes: Long,
        requireManagedBudget: Boolean
    ) {
        val appContext = context.applicationContext
        val cache = getCache(appContext)
        val cacheDir = File(appContext.noBackupFilesDir, "vr360-media3-cache")
        val prefs = appContext.getSharedPreferences(CACHE_META_PREFS, Context.MODE_PRIVATE)
        val knownLessons = prefs.getStringSet(CACHE_META_LESSONS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        val budget = maxManagedCacheBytes()

        fun stillNeedsSpace(): Boolean {
            val physical =
                cacheDir.usableSpace < additionalBytes + CACHE_FREE_SPACE_RESERVE_BYTES
            val managed =
                requireManagedBudget && cache.cacheSpace + additionalBytes > budget
            return physical || managed
        }

        val victims = knownLessons
            .filter { it != currentLessonBase }
            .sortedBy { prefs.getLong("last::$it", 0L) }

        for (victim in victims) {
            if (!stillNeedsSpace()) break

            val freed = removeLessonFromCache(victim)
            knownLessons.remove(victim)
            prefs.edit()
                .remove("last::$victim")
                .putStringSet(CACHE_META_LESSONS, knownLessons)
                .apply()

            Log.i(
                TAG,
                "Evicted old lesson cache base=$victim freed=${formatBytes(freed)}"
            )
        }

        if (stillNeedsSpace()) {
            val knownPrefixes = knownLessons.toList()
            val orphanKeys = cache.keys
                .filter { key ->
                    !key.startsWith(currentLessonBase) &&
                        knownPrefixes.none { prefix -> key.startsWith(prefix) }
                }

            var freed = 0L
            for (key in orphanKeys) {
                if (!stillNeedsSpace()) break
                freed += cachedBytesForKey(key)
                removeCacheKey(cache, key)
            }

            if (freed > 0L) {
                Log.i(TAG, "Evicted legacy cache resources freed=${formatBytes(freed)}")
            }
        }
    }

    private fun removeLessonFromCache(base: String): Long {
        val cache = getCache(context.applicationContext)
        val keys = cache.keys.filter { it.startsWith(base) }
        var freed = 0L

        for (key in keys) {
            freed += cachedBytesForKey(key)
            removeCacheKey(cache, key)
        }

        return freed
    }

    private fun removeCacheKey(cache: SimpleCache, key: String) {
        val spans = cache.getCachedSpans(key).toList()
        for (span in spans) {
            try {
                cache.removeSpan(span)
            } catch (e: Throwable) {
                Log.w(TAG, "Cannot remove cache span key=$key file=${span.file}", e)
            }
        }
    }

    private fun cachedBytesForPrefix(prefix: String): Long {
        val cache = getCache(context.applicationContext)
        return cache.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .sumOf { cachedBytesForKey(it) }
    }

    private fun cachedBytesForKey(key: String): Long {
        return getCache(context.applicationContext)
            .getCachedSpans(key)
            .sumOf { it.length.coerceAtLeast(0L) }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return "%.2f GiB".format(gb)
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
                resumePosition,
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

}
