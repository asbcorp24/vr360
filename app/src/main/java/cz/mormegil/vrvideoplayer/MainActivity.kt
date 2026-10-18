package cz.mormegil.vrvideoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.text.InputType
import android.text.Spanned
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cz.mormegil.vrvideoplayer.databinding.ActivityMainBinding
import java.util.UUID
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(),
    MediaPlayer.OnVideoSizeChangedListener,
    SensorEventListener {

    companion object {
        private const val TAG = "VRVideoPlayer"
        private const val PREFS_NAME = "vr360_settings"
        private const val KEY_CONTROL_SERVER_ENDPOINT = "control_server_ip"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_CLIENT_ID = "client_id"
        private const val DEFAULT_CONTROL_SERVER_ENDPOINT = "192.168.1.106"

        /*
         * Чувствительность Android SensorManager.
         * В режиме CardboardStereo основной трекер головы находится в Renderer.cpp
         * через CardboardHeadTracker. Эти значения нужны как fallback для MonoLeft/MonoRight.
         */
        private const val YAW_SENSITIVITY = 4.0f
        private const val PITCH_SENSITIVITY = 4.0f
        private const val ROLL_SENSITIVITY = 1.0f

    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var glView: GLSurfaceView
    private lateinit var videoTexturePlayer: VideoTexturePlayer
    private lateinit var controller: Controller

    private var nativeApp: Long = 0L
    private var teacherControlClient: TeacherControlClient? = null

    /*
     * IP компьютера/сервера управления.
     * 127.0.0.1 на телефоне означает сам телефон, поэтому сюда нужен LAN IP компьютера.
     * Значение сохраняется в SharedPreferences и может быть изменено через диалог,
     * если сервер управления недоступен.
     */
    private var controlServerEndpoint = DEFAULT_CONTROL_SERVER_ENDPOINT
    private var serverIpDialogShown = false
    private lateinit var clientId: String

    private val statusHandler = Handler(Looper.getMainLooper())
    private var controlConnected = false

    private var sensorYaw = 0.0f
    private var sensorPitch = 0.0f
    private var sensorRoll = 0.0f
    private var touchYawOffset = 0.0f
    private var touchPitchOffset = 0.0f
    private var touchStartX = 0.0f
    private var touchStartY = 0.0f
    private var touchStartYawOffset = 0.0f
    private var touchStartPitchOffset = 0.0f

    /*
     * VR WebSocket на сервере:
     * ws://host:8071/vr-view-ws
     */

    /*
     * Стартовый режим приложения.
     * При каждом запуске сразу включаем обычное 360° mono-видео в режиме двух глаз.
     * Если сервер потом пришлёт stereo_horiz/stereo_vert — переключимся без перезапуска.
     */
    private var inputLayout: InputLayout = InputLayout.Mono
    private var inputMode: InputMode = InputMode.Equirect360
    private var outputMode: OutputMode = OutputMode.MonoLeft

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate()")

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        controlServerEndpoint = loadSavedControlServerEndpoint()
        clientId = loadOrCreateClientId()
        outputMode = loadSavedDisplayMode()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        Log.d(TAG, "rotationSensor=${rotationSensor?.name}")

        /*
         * Важно: сначала создаём VideoTexturePlayer, Controller и nativeApp.
         * Потом подключаем GLSurfaceView renderer.
         */
        videoTexturePlayer = VideoTexturePlayer(
            context = this,
            videoSizeChangedListener = this
        )

        controller = Controller(
            getSystemService(AudioManager::class.java),
            videoTexturePlayer
        )

        nativeApp = NativeLibrary.nativeInit(
            this,
            assets,
            videoTexturePlayer,
            controller
        )

        /*
         * Первый стартовый setOptions. Он нужен до первого кадра.
         */
        applyStartDisplayModeDirect()

        glView = binding.surfaceView
        glView.setEGLContextClientVersion(3)

        val renderer = Renderer()
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glView.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchStartX = event.x
                    touchStartY = event.y
                    touchStartYawOffset = touchYawOffset
                    touchStartPitchOffset = touchPitchOffset
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (outputMode != OutputMode.CardboardStereo) {
                        val w = view.width.coerceAtLeast(1).toFloat()
                        val h = view.height.coerceAtLeast(1).toFloat()
                        val dx = event.x - touchStartX
                        val dy = event.y - touchStartY

                        touchYawOffset =
                            touchStartYawOffset - (dx / w) * (Math.PI * 2.0).toFloat()
                        touchPitchOffset =
                            (touchStartPitchOffset - (dy / h) * Math.PI.toFloat())
                                .coerceIn(-1.45f, 1.45f)

                        applyPhoneRotation()
                    }
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> true

                else -> false
            }
        }

        binding.btnPhoneMode.setOnClickListener {
            setDisplayMode(cardboard = false)
        }
        binding.btnCardboardMode.setOnClickListener {
            setDisplayMode(cardboard = true)
        }
        binding.btnServerSettings.setOnClickListener {
            showControlServerIpDialog("Настройки подключения")
        }
        updateModeButtons()

        NativeLibrary.nativeOnResume(nativeApp)

        /*
         * Второй принудительный setOptions уже через GL-поток.
         * Это исправляет ситуацию, когда после nativeOnResume Renderer остаётся в дефолтном MONO_LEFT/PLAIN_FOV.
         */
        applyStartDisplayModeOnGlThread()

        startTeacherControlWithReachabilityCheck()
        startPlayerStatusUpdates()

        enterImmersiveMode()

        val layout = window.attributes
        layout.screenBrightness = 1f
        window.attributes = layout

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC

        Log.d(
            TAG,
            "Started with inputLayout=$inputLayout inputMode=$inputMode outputMode=$outputMode"
        )
        Log.d(
            TAG,
            "360 display mode is Mono + Equirect360 + CardboardStereo; 180 stereo is enabled only by explicit video_mode command"
        )
    }

    private data class ReachabilityTarget(
        val host: String,
        val port: Int
    )

    private fun loadSavedControlServerEndpoint(): String {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTROL_SERVER_ENDPOINT, DEFAULT_CONTROL_SERVER_ENDPOINT)
            ?.trim()
            .orEmpty()

        return saved.ifBlank { DEFAULT_CONTROL_SERVER_ENDPOINT }
    }

    private fun saveControlServerEndpoint(endpoint: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CONTROL_SERVER_ENDPOINT, endpoint)
            .apply()
    }

    private fun loadSavedVideoStreamUrl(): String {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIDEO_STREAM_URL, DEFAULT_VIDEO_STREAM_URL)
            ?.trim()
            .orEmpty()

        val normalized = normalizeVideoStreamUrl(saved).ifBlank { DEFAULT_VIDEO_STREAM_URL }
        return if (normalized == "http://192.168.5.1:4022/udp/239.0.0.1:5004") {
            DEFAULT_VIDEO_STREAM_URL
        } else {
            normalized
        }
    }

    private fun saveVideoStreamUrl(url: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIDEO_STREAM_URL, url)
            .apply()
    }

    private fun normalizeControlServerEndpoint(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""

        return if (
            value.startsWith("ws://", ignoreCase = true) ||
            value.startsWith("wss://", ignoreCase = true)
        ) {
            value
        } else {
            value.removePrefix("http://")
                .removePrefix("https://")
                .trim()
        }
    }

    private fun normalizeVideoStreamUrl(raw: String): String {
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

    private fun buildTeacherControlWsUrl(): String {
        val savedValue = normalizeControlServerEndpoint(controlServerEndpoint)
        if (
            savedValue.startsWith("ws://", ignoreCase = true) ||
            savedValue.startsWith("wss://", ignoreCase = true)
        ) {
            return savedValue
        }

        val host = savedValue.substringBefore("/").trim()
        return "ws://$host:8071/vr-view-ws"
    }

    private fun parseReachabilityTarget(wsUrl: String): ReachabilityTarget? {
        return try {
            val uri = URI(wsUrl)
            val host = uri.host?.trim().orEmpty()
            if (host.isBlank()) return null

            val port = when {
                uri.port > 0 -> uri.port
                uri.scheme.equals("wss", ignoreCase = true) -> 443
                else -> 80
            }

            ReachabilityTarget(host, port)
        } catch (e: Exception) {
            Log.e(TAG, "Bad control WS URL: $wsUrl", e)
            null
        }
    }

    private fun startTeacherControlWithReachabilityCheck() {
        val wsUrl = buildTeacherControlWsUrl()
        val target = parseReachabilityTarget(wsUrl)

        if (target == null) {
            showControlServerIpDialog("Некорректный адрес сервера управления:\n$wsUrl")
            return
        }

        Thread {
            var socket: Socket? = null
            val ipToCheck = target.host
            val portToCheck = target.port
            try {
                Log.d(TAG, "Checking control server ${target.host}:${target.port} for $wsUrl")
                socket = Socket()
                socket.connect(InetSocketAddress(target.host, target.port), 2500)
                Log.d(TAG, "Control server reachable: ${target.host}:${target.port}")

                runOnUiThread {
                    connectTeacherControlClient()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Control server is not reachable: ${target.host}:${target.port}", e)

                runOnUiThread {
                    showControlServerIpDialog(
                        "Не удалось подключиться к компьютеру управления:\n$ipToCheck:$portToCheck"
                    )
                }
            } finally {
                try {
                    socket?.close()
                } catch (_: Throwable) {
                }
            }
        }.apply {
            name = "vr-control-server-check"
            start()
        }
    }

    private fun connectTeacherControlClient() {
        val wsUrl = buildTeacherControlWsUrl()

        Log.d(TAG, "Connecting TeacherControlClient to $wsUrl")

        teacherControlClient?.stop()
        teacherControlClient = TeacherControlClient(
            wsUrl = wsUrl,
            onLookAt = { yaw: Float, pitch: Float, radius: Float, duration: Int ->
                lookAtFromServer(
                    yaw = yaw,
                    pitch = pitch,
                    radius = radius,
                    duration = duration
                )
            },
            onVideoMode = { newInputLayout: InputLayout,
                            newInputMode: InputMode,
                            newOutputMode: OutputMode ->
                applyVideoModeFromServer(
                    newInputLayout = newInputLayout,
                    newInputMode = newInputMode,
                    newOutputMode = newOutputMode
                )
            },
            onTextMark = { mark: ServerTextMark ->
                showTextMarkInsideSphere(mark)
            },
            onTextMarkClear = {
                clearTextMarksInsideSphere()
            },
            onPlaybackCommand = { command ->
                handlePlaybackCommand(command)
            },
            onConnectionUnavailable = {
                showControlServerIpDialog(it)
            }
        )

        teacherControlClient?.start()
    }

    private fun showControlServerIpDialog(message: String) {
        if (isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed) return
        if (serverIpDialogShown) return

        serverIpDialogShown = true

        val wsEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "ws://192.168.1.104:8071/vr-view-ws"
            setSingleLine(true)
            setText(controlServerEndpoint)
            selectAll()
        }

        val streamEditText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = "http://192.168.10.1:7088/udp/239.0.0.1:5004"
            setSingleLine(true)
            setText(videoStreamUrl)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 8, padding, 0)
            addView(wsEditText)
            addView(streamEditText)
        }

        val dismissHandler = Handler(Looper.getMainLooper())
        var dialog: AlertDialog? = null
        val autoDismiss = Runnable {
            dialog?.dismiss()
        }

        dialog = AlertDialog.Builder(this)
            .setTitle("Адрес компьютера управления")
            .setMessage(
                "$message\n\n" +
                    "Верхнее поле: адрес WS-сервера управления.\n" +
                    "Нижнее поле: HLS master.m3u8 или старый HTTP-поток udpxy/msd_lite."
            )
            .setView(container)
            .setPositiveButton("Сохранить") { _, _ ->
                dismissHandler.removeCallbacks(autoDismiss)
                val newEndpoint = normalizeControlServerEndpoint(wsEditText.text?.toString().orEmpty())
                val newStreamUrl = normalizeVideoStreamUrl(streamEditText.text?.toString().orEmpty())
                serverIpDialogShown = false

                if (newEndpoint.isBlank()) {
                    showControlServerIpDialog("IP адрес пустой. Введите адрес компьютера управления.")
                    return@setPositiveButton
                }

                if (newStreamUrl.isBlank()) {
                    showControlServerIpDialog("HTTP адрес видеопотока пустой. Введите URL потока от udpxy или msd_lite.")
                    return@setPositiveButton
                }

                controlServerEndpoint = newEndpoint
                videoStreamUrl = newStreamUrl
                saveControlServerEndpoint(newEndpoint)
                saveVideoStreamUrl(newStreamUrl)
                videoTexturePlayer.setStreamSourceUrl(newStreamUrl)
                Log.d(TAG, "Saved control server endpoint: $controlServerEndpoint")
                Log.d(TAG, "Saved video stream URL: $videoStreamUrl")
                startTeacherControlWithReachabilityCheck()
            }
            .setNegativeButton("Отмена") { _, _ ->
                dismissHandler.removeCallbacks(autoDismiss)
                serverIpDialogShown = false
            }
            .setOnDismissListener {
                dismissHandler.removeCallbacks(autoDismiss)
                serverIpDialogShown = false
            }
            .show()

        dismissHandler.postDelayed(autoDismiss, CONTROL_SERVER_DIALOG_TIMEOUT_MS)
    }

    private fun applyStartDisplayModeDirect() {
        inputLayout = InputLayout.Mono
        inputMode = InputMode.Equirect360
        outputMode = OutputMode.CardboardStereo

        if (nativeApp != 0L) {
            NativeLibrary.nativeSetOptions(
                nativeApp,
                inputLayout.ordinal,
                inputMode.ordinal,
                outputMode.ordinal
            )

            Log.d(TAG, "FORCE START MODE DIRECT: $inputLayout $inputMode $outputMode")
        }
    }

    private fun applyStartDisplayModeOnGlThread() {
        inputLayout = InputLayout.Mono
        inputMode = InputMode.Equirect360
        outputMode = OutputMode.CardboardStereo

        if (!::glView.isInitialized) {
            Log.w(TAG, "applyStartDisplayModeOnGlThread ignored: glView not initialized")
            return
        }

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "FORCE START MODE ON GL: $inputLayout $inputMode $outputMode")
            }
        }
    }

    fun closePlayer(view: View) {
        finish()
    }

    fun showSettings(view: View) {
        val popup = PopupMenu(this, view)
        val inflater: MenuInflater = popup.menuInflater

        inflater.inflate(R.menu.settings_menu, popup.menu)
        MenuCompat.setGroupDividerEnabled(popup.menu, true)

        popup.menu.findItem(inputMode.menuItemId())?.isChecked = true
        popup.menu.findItem(inputLayout.menuItemId())?.isChecked = true
        popup.menu.findItem(outputMode.menuItemId())?.isChecked = true

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.switch_viewer -> {
                    NativeLibrary.nativeScanCardboardQr(nativeApp)
                    true
                }

                R.id.input_layout_mono -> {
                    setInputLayout(InputLayout.Mono, item)
                    true
                }

                R.id.input_layout_stereo_horiz -> {
                    setInputLayout(InputLayout.StereoHoriz, item)
                    true
                }

                R.id.input_layout_stereo_vert -> {
                    setInputLayout(InputLayout.StereoVert, item)
                    true
                }

                R.id.input_layout_anaglyph_red_cyan -> {
                    setInputLayout(InputLayout.AnaglyphRedCyan, item)
                    true
                }

                R.id.input_mode_plain_fov -> {
                    setInputMode(InputMode.PlainFov, item)
                    true
                }

                R.id.input_mode_equirect_180 -> {
                    setInputMode(InputMode.Equirect180, item)
                    true
                }

                R.id.input_mode_equirect_360 -> {
                    setInputMode(InputMode.Equirect360, item)
                    true
                }

                R.id.input_mode_panorama_180 -> {
                    setInputMode(InputMode.Panorama180, item)
                    true
                }

                R.id.input_mode_panorama_360 -> {
                    setInputMode(InputMode.Panorama360, item)
                    true
                }

                R.id.output_mode_mono_left_eye -> {
                    setOutputMode(OutputMode.MonoLeft, item)
                    true
                }

                R.id.output_mode_mono_right_eye -> {
                    setOutputMode(OutputMode.MonoRight, item)
                    true
                }

                R.id.output_mode_cardboard -> {
                    setOutputMode(OutputMode.CardboardStereo, item)
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    private fun handlePlaybackCommand(command: ServerPlaybackCommand) {
        Log.d(TAG, "handlePlaybackCommand: $command")

        when (command.type) {
            "load" -> {
                val url = command.url?.let(::normalizeVideoStreamUrl).orEmpty()
                if (url.isBlank()) {
                    Log.w(TAG, "Playback load ignored: empty URL")
                    return
                }

                videoStreamUrl = url
                saveVideoStreamUrl(url)

                videoTexturePlayer.loadFromServer(
                    url = url,
                    positionMs = command.positionMs ?: 0L,
                    autoPlay = command.autoPlay
                )

                Log.d(
                    TAG,
                    "Loaded adaptive lesson url=$url position=" +
                        (command.positionMs ?: 0L) +
                        " autoPlay=" + command.autoPlay
                )
            }

            "play" -> {
                videoTexturePlayer.playFromServer(command.positionMs)
            }

            "pause" -> {
                videoTexturePlayer.pauseFromServer(command.positionMs)
            }

            "seek" -> {
                command.positionMs?.let(videoTexturePlayer::seekTo)
            }

            "quality_limit" -> {
                videoTexturePlayer.setMaxVideoHeight(command.maxHeight ?: 0)
            }

            else -> {
                Log.w(TAG, "Unknown playback command: $command")
            }
        }
    }

    private fun radiusToFov(radius: Float): Float {
        val r = radius.coerceIn(0f, 300f)

        return if (r <= 150f) {
            val t = r / 150f
            120f + (90f - 120f) * t
        } else {
            val t = (r - 150f) / 150f
            90f + (35f - 90f) * t
        }
    }

    private fun lookAtFromServer(
        yaw: Float,
        pitch: Float,
        radius: Float,
        duration: Int
    ) {
        if (nativeApp == 0L) {
            Log.w(TAG, "lookAtFromServer ignored: nativeApp=0")
            return
        }

        if (!::glView.isInitialized) {
            Log.w(TAG, "lookAtFromServer ignored: glView not initialized")
            return
        }

        val fov = radiusToFov(radius)

        Log.d(
            TAG,
            "lookAtFromServer RECEIVED yaw=$yaw pitch=$pitch radius=$radius fov=$fov duration=$duration"
        )

        /*
         * Важно: Renderer живёт на GL-потоке GLSurfaceView.
         * Поэтому команду поворота отправляем через queueEvent.
         */
        glView.queueEvent {
            if (nativeApp != 0L) {
                Log.d(
                    TAG,
                    "lookAtFromServer SEND TO GL yaw=$yaw pitch=$pitch radius=$radius fov=$fov duration=$duration"
                )

                NativeLibrary.nativeLookAtPoint(
                    nativeApp,
                    yaw,
                    pitch,
                    fov,
                    duration
                )
            }
        }
    }

    private fun applyVideoModeFromServer(
        newInputLayout: InputLayout,
        newInputMode: InputMode,
        newOutputMode: OutputMode
    ) {
        if (nativeApp == 0L) {
            Log.w(TAG, "applyVideoModeFromServer ignored: nativeApp=0")
            return
        }

        if (!::glView.isInitialized) {
            Log.w(TAG, "applyVideoModeFromServer ignored: glView not initialized")
            return
        }

        /*
         * Важно для твоей задачи:
         * сервер может менять ТИП ВХОДНОГО ВИДЕО:
         *   Mono / StereoHoriz / StereoVert
         *   Equirect360 / Equirect180
         * но приложение на смартфоне должно оставаться в Cardboard-режиме.
         * Иначе после команды video_mode можно случайно уйти в MonoLeft/MonoRight.
         */
        val effectiveOutputMode = if (FORCE_CARDBOARD_OUTPUT) {
            OutputMode.CardboardStereo
        } else {
            newOutputMode
        }

        val changed =
            inputLayout != newInputLayout ||
                inputMode != newInputMode ||
                outputMode != effectiveOutputMode

        if (!changed) {
            Log.d(TAG, "applyVideoModeFromServer ignored: already $inputLayout $inputMode $outputMode")
            return
        }

        inputLayout = newInputLayout
        inputMode = newInputMode
        outputMode = effectiveOutputMode

        Log.d(
            TAG,
            "applyVideoModeFromServer RECEIVED: inputLayout=$inputLayout inputMode=$inputMode outputMode=$outputMode requestedOutput=$newOutputMode"
        )

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "applyVideoModeFromServer SEND TO GL: $inputLayout $inputMode $outputMode")
            }
        }
    }

    private fun setInputLayout(newLayout: InputLayout, menuItem: MenuItem) {
        if (newLayout == inputLayout) return
        inputLayout = newLayout
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true
        Log.d(TAG, "setInputLayout: $inputLayout")
    }

    private fun setInputMode(newMode: InputMode, menuItem: MenuItem) {
        if (newMode == inputMode) return
        inputMode = newMode
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true
        Log.d(TAG, "setInputMode: $inputMode")
    }

    private fun setOutputMode(newMode: OutputMode, menuItem: MenuItem) {
        if (newMode == outputMode) return
        outputMode = newMode
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true
        Log.d(TAG, "setOutputMode: $outputMode")
    }

    private fun setNativeOptionsOnGlThread() {
        if (nativeApp == 0L || !::glView.isInitialized) return

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "setNativeOptionsOnGlThread: $inputLayout $inputMode $outputMode")
            }
        }
    }


    /**
     * Показать текстовую метку не поверх Android-экрана, а внутри VR-сферы.
     *
     * Алгоритм:
     * 1. Kotlin рисует текст в Bitmap.
     * 2. Пиксели Bitmap передаются в C++ через JNI.
     * 3. Renderer.cpp создаёт OpenGL-текстуру и рисует её как прямоугольник
     *    внутри сферы в точке x/y/z или yaw/pitch.
     */
    private fun showTextMarkInsideSphere(mark: ServerTextMark) {
        if (nativeApp == 0L) {
            Log.w(TAG, "showTextMarkInsideSphere ignored: nativeApp=0")
            return
        }

        if (!::glView.isInitialized) {
            Log.w(TAG, "showTextMarkInsideSphere ignored: glView not initialized")
            return
        }

        val bitmapWidth = mark.width.coerceIn(160, 1024)
        val bitmapHeight = mark.height.coerceIn(80, 512)
        val bitmap = createTextMarkBitmap(
            text = mark.text,
            width = bitmapWidth,
            height = bitmapHeight
        )

        val pixels = IntArray(bitmapWidth * bitmapHeight)
        bitmap.getPixels(pixels, 0, bitmapWidth, 0, 0, bitmapWidth, bitmapHeight)
        bitmap.recycle()

        val x = mark.x ?: Float.NaN
        val y = mark.y ?: Float.NaN
        val z = mark.z ?: Float.NaN
        val yaw = mark.yaw ?: Float.NaN
        val pitch = mark.pitch ?: Float.NaN
        val duration = mark.durationMs.coerceAtLeast(500)

        Log.d(
            TAG,
            "showTextMarkInsideSphere id=${mark.id} text=${mark.text} x=$x y=$y z=$z yaw=$yaw pitch=$pitch duration=$duration"
        )

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeShowTextMark(
                    nativeApp,
                    mark.id,
                    pixels,
                    bitmapWidth,
                    bitmapHeight,
                    x,
                    y,
                    z,
                    yaw,
                    pitch,
                    bitmapWidth.toFloat(),
                    bitmapHeight.toFloat(),
                    duration
                )
            }
        }
    }

    /**
     * Скрыть все текстовые метки внутри сферы.
     */
    private fun clearTextMarksInsideSphere() {
        if (nativeApp == 0L || !::glView.isInitialized) return

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeClearTextMarks(nativeApp)
            }
        }
    }

    /**
     * Создать bitmap с чёрным полупрозрачным прямоугольником и белым текстом.
     * Этот bitmap потом станет OpenGL-текстурой внутри сферы.
     */
    private fun createTextMarkBitmap(text: String, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 0, 0, 0)
            style = Paint.Style.FILL
        }

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(230, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val rect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, 18f, 18f, backgroundPaint)
        canvas.drawRoundRect(rect.insetCopy(2f), 18f, 18f, borderPaint)

        val padding = 26
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = when {
                height <= 100 -> 18f
                height <= 160 -> 22f
                else -> 26f
            }
            textAlign = Paint.Align.LEFT
        }

        /*
         * Поддержка простых HTML-тегов от сервера:
         * <b>жирный</b>, <i>курсив</i>, <br> перенос строки.
         * Остальные неизвестные теги Android просто проигнорирует.
         */
        val spannedText: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(text)
        }

        val textWidth = width - padding * 2
        val staticLayout = StaticLayout.Builder
            .obtain(spannedText, 0, spannedText.length, textPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.0f)
            .setIncludePad(false)
            .build()

        val textTop = ((height - staticLayout.height) / 2).coerceAtLeast(padding)
        canvas.save()
        canvas.translate(padding.toFloat(), textTop.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }

    private fun RectF.insetCopy(value: Float): RectF {
        return RectF(left + value, top + value, right - value, bottom - value)
    }

    override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume()")

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnResume(nativeApp)
        }

        rotationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )

            Log.d(TAG, "Rotation sensor registered: ${sensor.name}")
        } ?: run {
            Log.e(TAG, "No rotation sensor found")
        }

        glView.onResume()
        videoTexturePlayer.onResume()

        // После возврата из паузы ещё раз закрепляем Cardboard-режим.
        // Это защищает от ситуации, когда GL renderer пересоздался после pause/resume.
        applyCurrentDisplayModeOnGlThread("onResume")
    }

    override fun onPause() {
        Log.d(TAG, "onPause()")

        sensorManager.unregisterListener(this)
        videoTexturePlayer.onPause()

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnPause(nativeApp)
        }

        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")

        teacherControlClient?.stop()
        teacherControlClient = null

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Throwable) {
        }

        try {
            videoTexturePlayer.onDestroy()
        } catch (e: Throwable) {
            Log.e(TAG, "videoTexturePlayer.onDestroy error", e)
        }

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnDestroy(nativeApp)
            nativeApp = 0L
        }

        releaseMulticastLock()
        super.onDestroy()
    }

    override fun onVideoSizeChanged(mp: MediaPlayer?, width: Int, height: Int) {
        Log.d(TAG, "onVideoSizeChanged width=$width height=$height")

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnVideoSizeChanged(nativeApp, width, height)
        }

        // Первый кадр VLC иногда приходит уже после пересоздания GL-состояния.
        // Поэтому на первом событии видео повторно отправляем текущий режим в native renderer.
        applyCurrentDisplayModeOnGlThread("onVideoSizeChanged")
    }

    private fun applyCurrentDisplayModeOnGlThread(reason: String) {
        if (!FORCE_CARDBOARD_OUTPUT) return
        if (nativeApp == 0L || !::glView.isInitialized) return

        outputMode = OutputMode.CardboardStereo

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    OutputMode.CardboardStereo.ordinal
                )

                Log.d(
                    TAG,
                    "FORCE CURRENT MODE CARDBOARD reason=$reason inputLayout=$inputLayout inputMode=$inputMode outputMode=${OutputMode.CardboardStereo}"
                )
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name}, accuracy=$accuracy")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (nativeApp == 0L) return

        if (
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedRotationMatrix
        )

        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        var yaw = orientationAngles[0]
        var pitch = orientationAngles[1]
        var roll = orientationAngles[2]

        yaw *= YAW_SENSITIVITY
        pitch *= PITCH_SENSITIVITY
        roll *= ROLL_SENSITIVITY

        pitch = pitch.coerceIn(-1.45f, 1.45f)

        /*
         * Для CardboardStereo Renderer.cpp использует CardboardHeadTracker.
         * Это останется fallback для MonoLeft/MonoRight.
         */
        NativeLibrary.nativeSetManualRotation(
            nativeApp,
            yaw,
            pitch,
            roll
        )
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifiManager.createMulticastLock("vr_multicast_lock").apply {
                setReferenceCounted(true)
                acquire()
            }

            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock error", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            Log.d(TAG, "MulticastLock released")
        } catch (e: Throwable) {
            Log.e(TAG, "releaseMulticastLock error", e)
        }

        multicastLock = null
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl10: GL10?, config: EGLConfig?) {
            Log.d(TAG, "Renderer.onSurfaceCreated")

            if (nativeApp != 0L) {
                NativeLibrary.nativeOnSurfaceCreated(nativeApp)
            }
        }

        override fun onSurfaceChanged(gl10: GL10?, width: Int, height: Int) {
            Log.d(TAG, "Renderer.onSurfaceChanged width=$width height=$height")

            if (nativeApp != 0L) {
                NativeLibrary.nativeSetScreenParams(nativeApp, width, height)
            }
        }

        override fun onDrawFrame(gl10: GL10?) {
            videoTexturePlayer.updateIfNeeded()

            if (nativeApp != 0L) {
                NativeLibrary.nativeDrawFrame(
                    nativeApp,
                    videoTexturePlayer.getVideoPosition()
                )
            }
        }
    }
}
