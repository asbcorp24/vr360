package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer.OnVideoSizeChangedListener
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoTexturePlayer — видеоплеер, который принимает MPEG-TS поток через udpxy HTTP.
 *
 * Раньше Android сам слушал multicast udp://@239.0.0.1:5004.
 * Теперь multicast принимает роутер, а телефон получает обычный HTTP unicast:
 * http://192.168.10.1:7088/udp/239.0.0.1:5004
 *
 * Видео выводится в SurfaceTexture, а аудио воспроизводится штатным audio output LibVLC.
 * SurfaceTexture привязана к OpenGL-текстуре texName.
 * Потом C++ Renderer использует эту текстуру для отображения 360°/VR-видео.
 */
class VideoTexturePlayer(
    // Android Context нужен для LibVLC.
    private val context: Context,

    // Listener, которому сообщаем размер видео.
    // В MainActivity это onVideoSizeChanged().
    private val videoSizeChangedListener: OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "VlcTexturePlayer"

        /**
         * HTTP-поток от udpxy на роутере.
         *
         * Роутер принимает multicast:
         *   udp://239.0.0.1:5004
         *
         * Android получает HTTP:
         *   http://192.168.10.1:7088/udp/239.0.0.1:5004
         */
        private const val UDPXY_HTTP_URL = "http://192.168.10.1:7088/udp/239.0.0.1:5004"

        /**
         * Размер буфера SurfaceTexture.
         *
         * Это не обязательно реальное разрешение входного видео.
         * Это размер, который SurfaceTexture сообщает декодеру/VLC.
         */
        private const val VIDEO_WIDTH = 2048
        private const val VIDEO_HEIGHT = 1024
    }

    // SurfaceTexture привязана к OpenGL texture id.
    private var surfaceTexture: SurfaceTexture? = null

    // Surface создаётся поверх SurfaceTexture.
    // LibVLC выводит декодированное видео именно сюда.
    private var surface: Surface? = null

    // Главный объект LibVLC.
    private var libVlc: LibVLC? = null

    // VLC MediaPlayer, который открывает HTTP MPEG-TS поток от udpxy.
    private var vlcPlayer: MediaPlayer? = null

    /**
     * Флаг: появился новый кадр.
     *
     * onFrameAvailable() вызывается Android'ом,
     * когда в SurfaceTexture пришёл новый кадр.
     *
     * Потом в OpenGL-потоке вызывается updateIfNeeded(),
     * который делает surfaceTexture.updateTexImage().
     */
    private val frameAvailable = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    /**
     * Текущая позиция видео.
     *
     * Для live-потока перемотки нет, поэтому всегда 0.
     */
    private var videoPosition: Float = 0.0f
    private var streamSourceUrl: String = UDPXY_HTTP_URL

    /**
     * Флаг: плеер уже инициализирован.
     *
     * Нужен, чтобы onResume() понимал, можно ли снова вызвать play().
     */
    private var initialized = false

    /**
     * Инициализация воспроизведения.
     *
     * texName — OpenGL texture id, созданный в C++.
     * SurfaceTexture будет писать видеокадры прямо в эту OpenGL-текстуру.
     */
    fun initializePlayback(texName: Int) {
        Log.d(TAG, "initializePlayback texName=$texName")

        // Сначала очищаем старый VLC, Surface и SurfaceTexture.
        cleanup()

        // Через udpxy телефон НЕ принимает multicast напрямую.
        // MulticastLock и UDP probe больше не нужны.
        Log.d(TAG, "Using HTTP stream source, multicast lock/probe skipped")

        initialized = true

        /**
         * Создаём SurfaceTexture на базе OpenGL texture id.
         *
         * texName приходит из C++ рендера.
         */
        val texture = SurfaceTexture(texName)

        // Задаём размер буфера.
        texture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)

        surfaceTexture = texture

        // Подписываемся на событие прихода нового кадра.
        texture.setOnFrameAvailableListener(this)

        /**
         * Создаём Surface для VLC.
         *
         * VLC будет декодировать HTTP MPEG-TS и выводить кадры в этот Surface.
         */
        val videoSurface = Surface(texture)
        surface = videoSurface

        /**
         * Опции LibVLC.
         */
        val vlcOptions = arrayListOf(
            "--verbose=2",

            // Использовать IPv4.
            "--ipv4",

            // Звук НЕ отключаем. VLC сам возьмёт аудио из MPEG-TS потока,
            // если сервер кладёт audio track внутрь этого же потока.
            "--aout=opensles",

            // Не показывать название видео поверх картинки.
            "--no-video-title-show",

            // Буферы для live-сети.
            "--network-caching=300",
            "--live-caching=300",

            // Минимизируем коррекцию времени VLC.
            "--clock-jitter=0",
            "--clock-synchro=0",

            // Аппаратное декодирование. Для 360/4K на телефоне обычно нужно именно оно.
            "--avcodec-hw=any"
        )

        // Создаём LibVLC.
        val vlc = LibVLC(context.applicationContext, vlcOptions)
        libVlc = vlc

        // Создаём VLC MediaPlayer.
        val player = MediaPlayer(vlc)
        vlcPlayer = player

        /**
         * Слушатель событий VLC.
         *
         * Полезен для диагностики:
         * Opening, Buffering, Playing, Error и т.д.
         */
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> Log.d(TAG, "VLC Opening")

                MediaPlayer.Event.Buffering ->
                    Log.d(TAG, "VLC Buffering ${event.buffering}")

                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "VLC Playing")

                    /**
                     * Сообщаем MainActivity/C++ рендереру размер видео.
                     *
                     * Сейчас передаётся фиксированный размер VIDEO_WIDTH/VIDEO_HEIGHT,
                     * а не реальный размер из потока.
                     */
                    videoSizeChangedListener.onVideoSizeChanged(
                        null,
                        VIDEO_WIDTH,
                        VIDEO_HEIGHT
                    )
                }

                MediaPlayer.Event.Paused -> Log.d(TAG, "VLC Paused")
                MediaPlayer.Event.Stopped -> Log.d(TAG, "VLC Stopped")
                MediaPlayer.Event.EndReached -> {
                    Log.w(TAG, "VLC EndReached")
                    scheduleStreamReconnect("end reached")
                }
                MediaPlayer.Event.EncounteredError -> {
                    Log.e(TAG, "VLC EncounteredError")
                    scheduleStreamReconnect("decoder/stream error")
                }

                else -> Log.d(TAG, "VLC event=${event.type}")
            }
        }

        /**
         * Привязываем Surface к VLC video output.
         */
        player.vlcVout.setVideoSurface(videoSurface, null)

        // Сообщаем VLC размер окна.
        player.vlcVout.setWindowSize(VIDEO_WIDTH, VIDEO_HEIGHT)

        // Активируем video output.
        player.vlcVout.attachViews()

        /**
         * Теперь открываем MPEG-TS поток через udpxy.
         *
         * Роутер принимает multicast UDP:
         *   udp://239.0.0.1:5004
         *
         * А Android получает обычный HTTP:
         *   http://192.168.5.1:4022/udp/239.0.0.1:5004
         */
        openStream(player, vlc)
    }

    /**
     * Перемотка в начало.
     *
     * Для live-потока это невозможно,
     * поэтому функция просто логирует вызов.
     */
    fun rewind() {
        Log.d(TAG, "rewind ignored: live HTTP udpxy stream")
    }

    /**
     * Перемотка вперёд/назад.
     *
     * Для live-потока это невозможно.
     */
    fun seek(relSeek: Int) {
        Log.d(TAG, "seek ignored: live HTTP udpxy stream relSeek=$relSeek")
    }

    /**
     * Возвращает позицию видео.
     *
     * Для live-потока всегда 0.
     */
    fun getVideoPosition(): Float = videoPosition

    /**
     * Обновить OpenGL-текстуру, если пришёл новый кадр.
     *
     * Это должно вызываться из GL-потока перед отрисовкой кадра.
     */
    fun updateIfNeeded() {
        if (frameAvailable.getAndSet(false)) {
            try {
                /**
                 * Забираем последний кадр из SurfaceTexture
                 * и обновляем связанную OpenGL-текстуру.
                 */
                surfaceTexture?.updateTexImage()
            } catch (e: Throwable) {
                Log.e(TAG, "updateTexImage error", e)
            }
        }
    }

    /**
     * Callback от SurfaceTexture.
     *
     * Android вызывает его, когда VLC положил новый видеокадр в Surface.
     */
    override fun onFrameAvailable(tex: SurfaceTexture?) {
        frameAvailable.set(true)

        // Для live-потока позиции нет.
        videoPosition = 0.0f

        // Не логируем каждый кадр, иначе Logcat сам может создавать тормоза.
        // Log.d(TAG, "FRAME AVAILABLE")
    }

    /**
     * Activity ушла в паузу.
     *
     * Останавливаем VLC.
     */
    fun onPause() {
        try {
            Log.d(TAG, "onPause")
            cancelPendingReconnect()
            vlcPlayer?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "onPause error", e)
        }
    }

    /**
     * Activity вернулась.
     *
     * Если плеер уже был инициализирован — снова запускаем VLC.
     */
    fun onResume() {
        try {
            Log.d(TAG, "onResume initialized=$initialized")
            if (initialized) {
                cancelPendingReconnect()
                vlcPlayer?.play()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onResume error", e)
        }
    }

    /**
     * Полное уничтожение плеера.
     */
    fun onDestroy() {
        Log.d(TAG, "onDestroy")
        cleanup()
    }

    fun setStreamSourceUrl(url: String) {
        val normalized = normalizeHttpStreamUrl(url)
        if (normalized == streamSourceUrl) {
            return
        }

        streamSourceUrl = normalized
        Log.d(TAG, "Updated HTTP stream source: $streamSourceUrl")

        if (initialized) {
            cancelPendingReconnect()
            reconnectStream("stream source changed")
        }
    }

    /**
     * Освободить все ресурсы.
     *
     * Важно:
     * - остановить VLC;
     * - detachViews;
     * - release MediaPlayer;
     * - release LibVLC;
     * - release Surface;
     * - release SurfaceTexture.
     */
    private fun cleanup() {
        cancelPendingReconnect()

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

    private fun openStream(player: MediaPlayer, vlc: LibVLC) {
        val mediaPath = streamSourceUrl

        Log.d(TAG, "Opening HTTP MPEG-TS stream: $mediaPath")

        val media = Media(vlc, Uri.parse(mediaPath))

        // Внутри HTTP всё равно MPEG-TS, поэтому явно указываем TS demux.
        media.addOption(":demux=ts")

        // Дублируем cache-настройки на уровне Media.
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")

        // Звук не отключаем: для MPEG-TS VLC должен сам найти audio track.
        // ВАЖНО: если сервер запускается с -an или отправляет аудио отдельным портом,
        // звука здесь не будет — аудио должно быть внутри этого же MPEG-TS потока.
        player.media = media
        media.release()

        val result = player.play()
        Log.d(TAG, "VLC play() result=$result")
    }

    private fun normalizeHttpStreamUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) {
            return UDPXY_HTTP_URL
        }

        return if (
            value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else {
            "http://$value"
        }
    }

    private fun scheduleStreamReconnect(reason: String) {
        if (!initialized) return
        if (reconnectRunnable != null) return

        reconnectRunnable = Runnable {
            reconnectRunnable = null
            reconnectStream(reason)
        }

        Log.w(TAG, "Scheduling VLC reconnect: $reason")
        mainHandler.postDelayed(reconnectRunnable!!, 1200)
    }

    private fun reconnectStream(reason: String) {
        if (!initialized) return

        val player = vlcPlayer ?: return
        val vlc = libVlc ?: return

        try {
            Log.w(TAG, "Reconnecting VLC stream after $reason")
            player.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "VLC stop before reconnect failed", e)
        }

        try {
            openStream(player, vlc)
        } catch (e: Throwable) {
            Log.e(TAG, "VLC reconnect failed", e)
            scheduleStreamReconnect("retry after reconnect failure")
        }
    }

    private fun cancelPendingReconnect() {
        reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
        reconnectRunnable = null
    }
}
