package cz.mormegil.vrvideoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cz.mormegil.vrvideoplayer.databinding.ActivityMainBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : AppCompatActivity(),
    MediaPlayer.OnVideoSizeChangedListener,
    SensorEventListener {

    companion object {
        private const val TAG = "VRVideoPlayer"

        /*
         * Чувствительность Android SensorManager.
         * В CardboardStereo основной трекер головы находится в Renderer.cpp через CardboardHeadTracker.
         * Эти значения остаются только для fallback-режимов MonoLeft/MonoRight.
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
     * IP компьютера/сервера.
     * 127.0.0.1 на телефоне означает сам телефон, поэтому здесь нужен LAN IP сервера.
     */
    private val serverIp = "192.168.1.104"

    /* HTTP API сервера 360. */
    private val httpPort = 8070

    /* WebSocket управления VR: ws://host:8071/vr-view-ws */
    private val vrWsPort = 8071

    /*
     * Стартовый режим приложения.
     * По умолчанию всегда запускаем 360 mono в Cardboard.
     * Если сервер сообщает, что запущено 180_stereo, приложение переключится автоматически.
     */
    private var inputLayout: InputLayout = InputLayout.Mono
    private var inputMode: InputMode = InputMode.Equirect360
    private var outputMode: OutputMode = OutputMode.CardboardStereo

    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastTouchCoordinates = arrayOf(1.0f, 0.0f)

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private val rotationMatrix = FloatArray(9)
    private val remappedRotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val videoInfoHandler = Handler(Looper.getMainLooper())

    private val videoInfoPollRunnable = object : Runnable {
        override fun run() {
            requestCurrentVideoInfoFromServer()
            videoInfoHandler.postDelayed(this, 3000)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate()")

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        acquireMulticastLock()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        Log.d(TAG, "rotationSensor=${rotationSensor?.name}")

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

        /* Первый режим до первого кадра: всегда 360 Cardboard. */
        forceStart360CardboardDirect()

        glView = binding.surfaceView
        glView.setEGLContextClientVersion(3)

        val renderer = Renderer()
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        glView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchCoordinates[0] = event.x
                lastTouchCoordinates[1] = event.y
            }
            false
        }

        /*
         * На экране больше нет кнопки настроек.
         * Ручное переключение режимов удалено из UI.
         * Формат видео теперь приходит только с сервера.
         */

        NativeLibrary.nativeOnResume(nativeApp)

        /* Повторно фиксируем стартовый режим уже через GL-поток. */
        forceStart360CardboardOnGlThread()

        teacherControlClient = TeacherControlClient(
            wsUrl = "ws://$serverIp:$vrWsPort/vr-view-ws",
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
            }
        )

        teacherControlClient?.start()

        /*
         * Если Android включился уже после запуска видео на сервере,
         * WebSocket-событие могло быть пропущено. Поэтому дополнительно опрашиваем
         * /api/current-video-info и синхронизируем режим 360 / 180_stereo.
         */
        startCurrentVideoInfoPolling()

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
    }

    private fun forceStart360CardboardDirect() {
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

    private fun forceStart360CardboardOnGlThread() {
        inputLayout = InputLayout.Mono
        inputMode = InputMode.Equirect360
        outputMode = OutputMode.CardboardStereo

        if (!::glView.isInitialized) {
            Log.w(TAG, "forceStart360CardboardOnGlThread ignored: glView not initialized")
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

    private fun startCurrentVideoInfoPolling() {
        videoInfoHandler.removeCallbacks(videoInfoPollRunnable)
        requestCurrentVideoInfoFromServer()
        videoInfoHandler.postDelayed(videoInfoPollRunnable, 3000)
    }

    private fun stopCurrentVideoInfoPolling() {
        videoInfoHandler.removeCallbacks(videoInfoPollRunnable)
    }

    private fun requestCurrentVideoInfoFromServer() {
        Thread {
            try {
                val url = "http://$serverIp:$httpPort/api/current-video-info"

                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "current-video-info failed code=${response.code}")
                        return@use
                    }

                    val body = response.body?.string()
                    if (body.isNullOrBlank()) {
                        Log.w(TAG, "current-video-info empty body")
                        return@use
                    }

                    Log.d(TAG, "current-video-info: $body")
                    applyVideoInfoJsonFromServer(JSONObject(body))
                }
            } catch (e: Throwable) {
                Log.w(TAG, "requestCurrentVideoInfoFromServer error: ${e.message}")
            }
        }.apply {
            name = "vr-current-video-info"
            start()
        }
    }

    private fun applyVideoInfoJsonFromServer(json: JSONObject) {
        val projection = findStringInJson(
            json,
            listOf(
                "video_projection",
                "videoProjection",
                "projection",
                "videoMode",
                "inputMode",
                "format",
                "type"
            )
        )?.lowercase()?.trim().orEmpty()

        if (projection.isBlank()) {
            Log.d(TAG, "current-video-info has no projection field: $json")
            return
        }

        val layoutRaw = findStringInJson(
            json,
            listOf("layout", "inputLayout", "stereo_layout", "stereoLayout")
        )?.lowercase()?.trim().orEmpty()

        val newOutputMode = OutputMode.CardboardStereo

        val newInputMode: InputMode
        val newInputLayout: InputLayout

        when (projection) {
            "360", "360_mono", "equirect360", "equirect_360", "equirectangular360", "equirectangular_360" -> {
                newInputMode = InputMode.Equirect360
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.Mono)
            }

            "360_stereo", "360 stereo", "360-stereo" -> {
                newInputMode = InputMode.Equirect360
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.StereoHoriz)
            }

            "180_stereo", "180 stereo", "180-stereo" -> {
                newInputMode = InputMode.Equirect180
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.StereoHoriz)
            }

            "180", "180_mono", "equirect180", "equirect_180", "equirectangular180", "equirectangular_180" -> {
                newInputMode = InputMode.Equirect180
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.Mono)
            }

            "panorama180", "panorama_180" -> {
                newInputMode = InputMode.Panorama180
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.Mono)
            }

            "panorama360", "panorama_360" -> {
                newInputMode = InputMode.Panorama360
                newInputLayout = parseLayoutForProjection(layoutRaw, InputLayout.Mono)
            }

            "plain", "plain_fov", "flat", "2d" -> {
                newInputMode = InputMode.PlainFov
                newInputLayout = InputLayout.Mono
            }

            else -> {
                Log.w(TAG, "Unknown server video projection='$projection', keep current mode")
                return
            }
        }

        runOnUiThread {
            applyVideoModeFromServer(
                newInputLayout = newInputLayout,
                newInputMode = newInputMode,
                newOutputMode = newOutputMode
            )
        }
    }

    private fun parseLayoutForProjection(layout: String, defaultValue: InputLayout): InputLayout {
        return when (layout.lowercase()) {
            "mono", "360_mono", "180_mono" -> InputLayout.Mono
            "stereo", "stereo_horiz", "stereo_horizontal", "side_by_side", "side-by-side", "sbs" ->
                InputLayout.StereoHoriz
            "stereo_vert", "stereo_vertical", "top_bottom", "top-bottom", "tb", "over_under", "over-under", "ou" ->
                InputLayout.StereoVert
            "anaglyph", "anaglyph_red_cyan" -> InputLayout.AnaglyphRedCyan
            else -> defaultValue
        }
    }

    private fun findStringInJson(json: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            val value = json.opt(key)
            if (value is String && value.isNotBlank()) return value
        }

        val iterator = json.keys()
        while (iterator.hasNext()) {
            when (val value = json.opt(iterator.next())) {
                is JSONObject -> {
                    val found = findStringInJson(value, keys)
                    if (!found.isNullOrBlank()) return found
                }
                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item is JSONObject) {
                            val found = findStringInJson(item, keys)
                            if (!found.isNullOrBlank()) return found
                        }
                    }
                }
            }
        }

        return null
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

        val changed =
            inputLayout != newInputLayout ||
                inputMode != newInputMode ||
                outputMode != newOutputMode

        if (!changed) {
            Log.d(TAG, "applyVideoModeFromServer ignored: already $inputLayout $inputMode $outputMode")
            return
        }

        inputLayout = newInputLayout
        inputMode = newInputMode
        outputMode = newOutputMode

        Log.d(TAG, "applyVideoModeFromServer RECEIVED: $inputLayout $inputMode $outputMode")

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
        startCurrentVideoInfoPolling()
    }

    override fun onPause() {
        Log.d(TAG, "onPause()")

        stopCurrentVideoInfoPolling()
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

        stopCurrentVideoInfoPolling()

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
         * Этот вызов нужен как fallback для MonoLeft/MonoRight.
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
