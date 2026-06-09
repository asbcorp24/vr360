package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer.OnVideoSizeChangedListener
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VideoTexturePlayer — видеоплеер, который принимает MPEG-TS UDP multicast-поток через LibVLC
 * и выводит видео не на обычный View, а в SurfaceTexture.
 *
 * SurfaceTexture привязана к OpenGL-текстуре texName.
 * Потом C++ Renderer использует эту текстуру для отображения 360°/VR-видео.
 */
class VideoTexturePlayer(
    // Android Context нужен для LibVLC и доступа к cacheDir.
    private val context: Context,

    // Listener, которому сообщаем размер видео.
    // В MainActivity это onVideoSizeChanged().
    private val videoSizeChangedListener: OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "VlcTexturePlayer"

        /**
         * Multicast IP HIGH-потока сервера.
         *
         * По твоей схеме:
         * HIGH RTP обычно идёт на 239.0.0.1:5004.
         */
        private const val MULTICAST_IP = "239.0.0.1"

        /**
         * Порт HIGH RTP-потока.
         */
        private const val MULTICAST_PORT = 5004

        /**
         * RTP payload type.
         *
         * Для MPEG-TS этот параметр не используется. Оставлен только для совместимости комментариев.
         */
        private const val RTP_PAYLOAD_TYPE = 96

        /**
         * Кодек MPEG-TS определяется VLC автоматически. Сервер может слать H264 или H265 внутри TS.
         */
        private const val RTP_VIDEO_CODEC = "AUTO_TS"

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

    // VLC MediaPlayer, который открывает SDP и принимает RTP.
    private var vlcPlayer: MediaPlayer? = null

    // Android Wi‑Fi по умолчанию может фильтровать multicast. Этот lock разрешает приём 239.x.x.x.
    private var multicastLock: WifiManager.MulticastLock? = null

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

    /**
     * Текущая позиция видео.
     *
     * Для live multicast перемотки нет, поэтому всегда 0.
     */
    private var videoPosition: Float = 0.0f

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

        // Обязательно включаем multicast-lock ДО UDP probe и ДО запуска VLC.
        acquireMulticastLock()

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
         * VLC будет декодировать RTP/H264 и выводить кадры в этот Surface.
         */
        val videoSurface = Surface(texture)
        surface = videoSurface

        /**
         * Диагностический UDP probe.
         *
         * Перед запуском VLC проверяем, доходят ли multicast UDP-пакеты
         * до телефона.
         *
         * Если в Logcat packets=0, значит проблема не в VLC,
         * а в сети, Wi-Fi, multicast lock, роутере или адресе/порте.
         */
        probeMulticastBeforeVlc()

        /**
         * Опции LibVLC.
         */
        val vlcOptions = arrayListOf(
            "--verbose=2",

            // Использовать IPv4.
            "--ipv4",

            // Отключаем звук. Сейчас принимается только видео HIGH-поток.
            "--no-audio",

            // Не показывать название видео поверх картинки.
            "--no-video-title-show",

            // Буферы для live-сети.
            "--network-caching=300",
            "--live-caching=300",
            "--rtp-caching=300",

            // Минимизируем коррекцию времени VLC.
            "--clock-jitter=0",
            "--clock-synchro=0",

            /**
             * Отключаем аппаратное декодирование.
             *
             * Это удобно для диагностики.
             * Если картинка появилась и работает стабильно,
             * можно попробовать заменить на:
             *
             * "--avcodec-hw=any"
             *
             * Но на разных телефонах аппаратное декодирование RTP/H264
             * может вести себя по-разному.
             */
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
                MediaPlayer.Event.EndReached -> Log.w(TAG, "VLC EndReached")
                MediaPlayer.Event.EncounteredError -> Log.e(TAG, "VLC EncounteredError")

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
         * ВАЖНО: по твоему логу размеры UDP-пакетов 376/564/1316 — это кратно 188 байтам,
         * то есть сервер шлёт MPEG-TS over UDP multicast, а не RTP.
         * Поэтому НЕ открываем SDP/live555. Открываем обычный udp:// multicast URI.
         */
        val mediaPath = "udp://@$MULTICAST_IP:$MULTICAST_PORT"

        Log.d(TAG, "Opening MPEG-TS UDP multicast path: $mediaPath")

        // Создаём VLC Media из udp://@239.0.0.1:5004.
        val media = Media(vlc, Uri.parse(mediaPath))

        // Принудительно указываем TS demux. Для MPEG-TS UDP это правильнее, чем live555/RTP.
        media.addOption(":demux=ts")

        // Дублируем cache-настройки на уровне Media.
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")

        // Звук отключён.
        media.addOption(":no-audio")

        // Назначаем Media плееру.
        player.media = media

        // Media можно release после назначения.
        media.release()

        // Запускаем воспроизведение.
        val result = player.play()
        Log.d(TAG, "VLC play() result=$result")
    }

    /**
     * Перемотка в начало.
     *
     * Для live multicast это невозможно,
     * поэтому функция просто логирует вызов.
     */
    fun rewind() {
        Log.d(TAG, "rewind ignored: live multicast")
    }

    /**
     * Перемотка вперёд/назад.
     *
     * Для live multicast это невозможно.
     */
    fun seek(relSeek: Int) {
        Log.d(TAG, "seek ignored: live multicast relSeek=$relSeek")
    }

    /**
     * Возвращает позицию видео.
     *
     * Для live multicast всегда 0.
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

        Log.d(TAG, "FRAME AVAILABLE")
    }

    /**
     * Activity ушла в паузу.
     *
     * Останавливаем VLC.
     */
    fun onPause() {
        try {
            Log.d(TAG, "onPause")
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

        releaseMulticastLock()
    }

    /**
     * Включить разрешение Wi‑Fi на приём multicast-пакетов.
     * Без этого Android часто фильтрует 239.x.x.x, и VLC получает чёрный экран.
     */
    private fun acquireMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) return

            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifi.createMulticastLock("vr360_rtp_multicast_239_0_0_1")
            multicastLock?.setReferenceCounted(false)
            multicastLock?.acquire()

            Log.d(TAG, "MulticastLock acquired for $MULTICAST_IP:$MULTICAST_PORT")
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock acquire error", e)
        }
    }

    /**
     * Освободить MulticastLock при закрытии плеера.
     */
    private fun releaseMulticastLock() {
        try {
            multicastLock?.let { lock ->
                if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "MulticastLock released")
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock release error", e)
        } finally {
            multicastLock = null
        }
    }

    /**
     * Создать SDP-файл для приёма RTP multicast.
     *
     * VLC открывает не напрямую udp://@239.0.0.1:5004,
     * а SDP-файл, где описан RTP/H264 поток.
     */
    private fun createSdpFile(): File {
        /**
         * SDP близкий к серверному multicast.sdp.
         *
         * Важно:
         * sprop-parameter-sets специально не указан.
         * Это значит, что SPS/PPS должны приходить в самом RTP/H264 потоке.
         *
         * Если сервер не шлёт SPS/PPS периодически,
         * VLC может не начать декодировать видео при подключении "с середины".
         */
        val fmtpLine = if (RTP_VIDEO_CODEC == "H264") {
            "a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1\n"
        } else {
            // Для H265 не добавляем H264-only packetization-mode.
            ""
        }

        val sdpText = """
v=0
o=- 0 0 IN IP4 0.0.0.0
s=RTP Multicast HIGH VR
t=0 0
a=tool:android-libvlc
m=video $MULTICAST_PORT RTP/AVP $RTP_PAYLOAD_TYPE
c=IN IP4 $MULTICAST_IP/16
b=AS:12000
a=framerate:30
a=rtpmap:$RTP_PAYLOAD_TYPE $RTP_VIDEO_CODEC/90000
${fmtpLine}a=recvonly
""".trimIndent()

        val file = File(context.cacheDir, "multicast_high_debug.sdp")

        // Пишем SDP ASCII-файлом.
        file.writeText(sdpText, Charsets.US_ASCII)

        Log.d(TAG, "SDP file: ${file.absolutePath}")
        Log.d(TAG, "\n$sdpText")

        return file
    }

    /**
     * Быстрая проверка multicast-пакетов до запуска VLC.
     *
     * Она:
     * 1. открывает MulticastSocket;
     * 2. joinGroup на 239.0.0.1:5004;
     * 3. слушает 2.5 секунды;
     * 4. считает количество UDP-пакетов.
     *
     * Если packets > 0 — сеть доставляет multicast.
     * Если packets = 0 — VLC почти точно тоже ничего не увидит.
     */
    private fun probeMulticastBeforeVlc() {
        Thread {
            var socket: MulticastSocket? = null

            try {
                Log.d(TAG, "UDP PROBE start: group=$MULTICAST_IP port=$MULTICAST_PORT")

                val group = InetAddress.getByName(MULTICAST_IP)

                /**
                 * Создаём socket без автоматического bind.
                 */
                socket = MulticastSocket(null)

                // Разрешаем переиспользование адреса.
                socket.reuseAddress = true

                // Таймаут receive() 500 мс.
                socket.soTimeout = 500

                // Слушаем порт multicast-потока.
                socket.bind(InetSocketAddress(MULTICAST_PORT))

                /**
                 * Ищем Wi-Fi интерфейс.
                 *
                 * На Android это часто wlan0.
                 */
                val iface = findWifiLikeInterface()

                if (iface != null) {
                    Log.d(TAG, "UDP PROBE networkInterface=${iface.name} ${iface.displayName}")

                    /**
                     * На Android 7+ лучше joinGroup с указанием interface.
                     */
                    if (Build.VERSION.SDK_INT >= 24) {
                        socket.joinGroup(
                            InetSocketAddress(group, MULTICAST_PORT),
                            iface
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        socket.joinGroup(group)
                    }
                } else {
                    Log.w(TAG, "UDP PROBE no wifi interface found, joinGroup without interface")

                    @Suppress("DEPRECATION")
                    socket.joinGroup(group)
                }

                val started = System.currentTimeMillis()
                var packets = 0
                var bytes = 0L
                val buffer = ByteArray(2048)

                /**
                 * Слушаем пакеты 2.5 секунды.
                 */
                while (System.currentTimeMillis() - started < 2500) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)

                        // Блокирующее ожидание UDP-пакета.
                        socket.receive(packet)

                        packets++
                        bytes += packet.length.toLong()

                        if (packets <= 5) {
                            Log.d(
                                TAG,
                                "UDP PROBE packet #$packets len=${packet.length} from=${packet.address.hostAddress}:${packet.port}"
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                        // Это нормально: за 500 мс могло не быть пакета.
                    }
                }

                Log.d(TAG, "UDP PROBE result packets=$packets bytes=$bytes")

                /**
                 * Покидаем multicast-группу.
                 */
                try {
                    if (iface != null && Build.VERSION.SDK_INT >= 24) {
                        socket.leaveGroup(
                            InetSocketAddress(group, MULTICAST_PORT),
                            iface
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        socket.leaveGroup(group)
                    }
                } catch (_: Throwable) {
                }
            } catch (e: Throwable) {
                Log.e(TAG, "UDP PROBE error", e)
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }.apply {
            name = "vr-multicast-probe"
            start()

            try {
                /**
                 * Ждём завершения probe.
                 *
                 * Важно:
                 * Это блокирует initializePlayback примерно на 2.8 секунды.
                 * Для диагностики нормально, но в рабочей версии лучше убрать join()
                 * или сделать probe полностью асинхронным.
                 */
                join(2800)
            } catch (_: InterruptedException) {
            }
        }
    }

    /**
     * Найти сетевой интерфейс, похожий на Wi-Fi.
     *
     * Сначала ищем wlan/wifi.
     * Если не нашли — берём любой активный multicast-интерфейс.
     */
    private fun findWifiLikeInterface(): NetworkInterface? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()

            interfaces.firstOrNull { ni ->
                ni.isUp &&
                        !ni.isLoopback &&
                        ni.supportsMulticast() &&
                        (
                                ni.name.contains("wlan", ignoreCase = true) ||
                                        ni.name.contains("wifi", ignoreCase = true) ||
                                        ni.displayName.contains("wlan", ignoreCase = true) ||
                                        ni.displayName.contains("wifi", ignoreCase = true)
                                )
            } ?: interfaces.firstOrNull { ni ->
                ni.isUp &&
                        !ni.isLoopback &&
                        ni.supportsMulticast()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "findWifiLikeInterface error", e)
            null
        }
    }
}