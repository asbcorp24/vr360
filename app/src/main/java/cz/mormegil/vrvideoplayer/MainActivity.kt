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
import android.util.Log
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cz.mormegil.vrvideoplayer.databinding.ActivityMainBinding
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity(),
    MediaPlayer.OnVideoSizeChangedListener,
    SensorEventListener {

    companion object {
        private const val TAG = "VRVideoPlayer"

        /*
         * Чувствительность гироскопа.
         * Если движение всё ещё медленное — увеличь до 5.0f или 6.0f.
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

    /*
     * Для твоего 360-видео:
     * InputLayout.Mono      — один 360 кадр.
     * InputMode.Equirect360 — сферическое 360-видео.
     * OutputMode.CardboardStereo — режим для двух глаз.
     */
    private var inputLayout: InputLayout = InputLayout.Mono
    private var inputMode: InputMode = InputMode.Equirect360
    private var outputMode: OutputMode = OutputMode.CardboardStereo

    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastTouchCoordinates = arrayOf(1.0f, 0.0f)

    /*
     * Резервный гироскоп через Android SensorManager.
     */
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

        acquireMulticastLock()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /*
         * Инициализация Android-датчика поворота.
         * Сначала пробуем GAME_ROTATION_VECTOR, потом обычный ROTATION_VECTOR.
         */
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        Log.d(TAG, "rotationSensor=${rotationSensor?.name}")

        glView = binding.surfaceView
        glView.setEGLContextClientVersion(2)

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

        glView.setOnClickListener {
            val x = lastTouchCoordinates[0]
            val halfWidth = glView.width.coerceAtLeast(1) * 0.5f
            val relX = (x - halfWidth) / halfWidth

            videoTexturePlayer.seek((10000 * relX).roundToInt())

            if (nativeApp != 0L) {
                NativeLibrary.nativeShowProgressBar(nativeApp)
            }
        }

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

        NativeLibrary.nativeSetOptions(
            nativeApp,
            inputLayout.ordinal,
            inputMode.ordinal,
            outputMode.ordinal
        )

        /*
         * Принудительно запускаем Cardboard/native часть.
         */
        NativeLibrary.nativeOnResume(nativeApp)

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

    private fun setInputLayout(newLayout: InputLayout, menuItem: MenuItem) {
        if (newLayout == inputLayout) return

        inputLayout = newLayout

        NativeLibrary.nativeSetOptions(
            nativeApp,
            inputLayout.ordinal,
            inputMode.ordinal,
            outputMode.ordinal
        )

        menuItem.isChecked = true
        Log.d(TAG, "setInputLayout: $inputLayout")
    }

    private fun setInputMode(newMode: InputMode, menuItem: MenuItem) {
        if (newMode == inputMode) return

        inputMode = newMode

        NativeLibrary.nativeSetOptions(
            nativeApp,
            inputLayout.ordinal,
            inputMode.ordinal,
            outputMode.ordinal
        )

        menuItem.isChecked = true
        Log.d(TAG, "setInputMode: $inputMode")
    }

    private fun setOutputMode(newMode: OutputMode, menuItem: MenuItem) {
        if (newMode == outputMode) return

        outputMode = newMode

        NativeLibrary.nativeSetOptions(
            nativeApp,
            inputLayout.ordinal,
            inputMode.ordinal,
            outputMode.ordinal
        )

        menuItem.isChecked = true
        Log.d(TAG, "setOutputMode: $outputMode")
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
        super.onDestroy()

        Log.d(TAG, "onDestroy()")

        sensorManager.unregisterListener(this)

        videoTexturePlayer.onDestroy()

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnDestroy(nativeApp)
            nativeApp = 0L
        }

        releaseMulticastLock()
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

        SensorManager.getRotationMatrixFromVector(
            rotationMatrix,
            event.values
        )

        /*
         * Оси для landscape.
         * Если влево/вправо будет работать неправильно — ниже дам альтернативу.
         */
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedRotationMatrix
        )

        SensorManager.getOrientation(
            remappedRotationMatrix,
            orientationAngles
        )

        /*
         * orientationAngles:
         * [0] = yaw   — влево/вправо
         * [1] = pitch — вверх/вниз
         * [2] = roll  — наклон головы
         */
        var yaw = orientationAngles[0]
        var pitch = orientationAngles[1]
        var roll = orientationAngles[2]

        /*
         * Усиление движения.
         */
        yaw *= YAW_SENSITIVITY
        pitch *= PITCH_SENSITIVITY
        roll *= ROLL_SENSITIVITY

        /*
         * Не даём камере перевернуться вверх ногами.
         */
        pitch = pitch.coerceIn(-1.45f, 1.45f)

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