package cz.mormegil.openxrquestplayer

import android.app.AlertDialog
import android.app.NativeActivity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaPlayer
import android.os.Build
import android.text.Html
import android.text.Layout
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.os.Bundle
import android.util.Log
import android.widget.EditText

class QuestPlayerActivity : NativeActivity(), MediaPlayer.OnVideoSizeChangedListener {
    companion object {
        private const val TAG = "QuestPlayerActivity"
        private const val PREFS_NAME = "vr360_settings"
        private const val KEY_CONTROL_SERVER_IP = "control_server_ip"
        private const val KEY_CONTROL_WS_URL = "control_ws_url"
        private const val KEY_STREAM_URL = "stream_url"
        private const val DEFAULT_CONTROL_SERVER_IP = "192.168.5.200"
        private const val DEFAULT_WS_PORT = 8071
        private const val DEFAULT_STREAM_URL = "http://192.168.5.1:4022/udp/239.0.0.1:5004"
    }

    private var teacherControlClient: TeacherControlClient? = null
    private var serverIp = DEFAULT_CONTROL_SERVER_IP
    private var vrWsPort = DEFAULT_WS_PORT
    private var savedWsUrlOverride: String? = null
    private var connectionDialogShown = false
    private var streamDialogShown = false
    private lateinit var videoTexturePlayer: VideoTexturePlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverIp = intent.getStringExtra("server_ip")
            ?: loadSavedControlServerIp()
        savedWsUrlOverride = loadSavedControlWsUrl()
        vrWsPort = intent.getIntExtra("vr_ws_port", DEFAULT_WS_PORT)
        val streamUrl = intent.getStringExtra("stream_url")
            ?: loadSavedStreamUrl()
        videoTexturePlayer = VideoTexturePlayer(
            context = applicationContext,
            videoSizeChangedListener = this,
            initialStreamUrl = streamUrl,
            onPlaybackFailure = { message ->
                runOnUiThread {
                    handleVideoPlaybackFailure(message)
                }
            }
        )
        QuestNativeBridge.nativeAttachVideoPlayer(videoTexturePlayer)
    }

    override fun onResume() {
        super.onResume()
        videoTexturePlayer.onResume()
        connectTeacherControlClient()
    }

    override fun onPause() {
        teacherControlClient?.stop()
        teacherControlClient = null
        videoTexturePlayer.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        videoTexturePlayer.onDestroy()
        super.onDestroy()
    }

    private fun loadSavedControlServerIp(): String {
        val saved = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTROL_SERVER_IP, DEFAULT_CONTROL_SERVER_IP)
            ?.trim()
            .orEmpty()

        return saved
            .removePrefix("ws://")
            .removePrefix("wss://")
            .substringBefore("/")
            .substringBefore(":")
            .ifBlank { DEFAULT_CONTROL_SERVER_IP }
    }

    private fun buildTeacherControlWsUrl(): String {
        val explicit = intent.getStringExtra("ws_url")?.trim().orEmpty()
        if (explicit.isNotBlank()) {
            return explicit
        }
        val savedOverride = savedWsUrlOverride?.trim().orEmpty()
        if (savedOverride.isNotBlank()) {
            return savedOverride
        }
        return "ws://$serverIp:$vrWsPort/vr-view-ws"
    }

    private fun connectTeacherControlClient() {
        val wsUrl = buildTeacherControlWsUrl()
        Log.d(TAG, "Connecting TeacherControlClient to $wsUrl")

        teacherControlClient?.stop()
        teacherControlClient = TeacherControlClient(
            wsUrl = wsUrl,
            onLookAt = { yaw, pitch, _, duration ->
                QuestNativeBridge.nativeSendLookAt(
                    yawDeg = yaw,
                    pitchDeg = pitch,
                    fovDeg = 90.0f,
                    durationMs = duration
                )
            },
            onVideoMode = { inputLayout, inputMode, outputMode ->
                QuestNativeBridge.nativeSetVideoMode(
                    inputLayout.ordinal + 1,
                    inputMode.ordinal + 1,
                    outputMode.ordinal + 1
                )
            },
            onTextMark = { mark ->
                showTextMarkInsideSphere(mark)
            },
            onTextMarkClear = {
                QuestNativeBridge.nativeClearTextMarks()
            },
            onConnectionFailure = { message ->
                handleTeacherControlConnectionFailure(message)
            }
        )
        teacherControlClient?.start()
    }

    override fun onVideoSizeChanged(mp: MediaPlayer?, width: Int, height: Int) {
        Log.d(TAG, "onVideoSizeChanged width=$width height=$height")
        QuestNativeBridge.nativeOnVideoSizeChanged(width, height)
    }

    private fun loadSavedControlWsUrl(): String? {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CONTROL_WS_URL, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun loadSavedStreamUrl(): String {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STREAM_URL, DEFAULT_STREAM_URL)
            ?.trim()
            ?.ifBlank { DEFAULT_STREAM_URL }
            ?: DEFAULT_STREAM_URL
    }

    private fun saveStreamUrl(streamUrl: String) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STREAM_URL, streamUrl.trim())
            .apply()
    }

    private fun saveTeacherControlEndpoint(rawValue: String) {
        val value = rawValue.trim()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()

        if (value.startsWith("ws://", ignoreCase = true) || value.startsWith("wss://", ignoreCase = true)) {
            savedWsUrlOverride = value
            serverIp = value
                .removePrefix("ws://")
                .removePrefix("wss://")
                .substringBefore("/")
                .substringBefore(":")
                .ifBlank { DEFAULT_CONTROL_SERVER_IP }
            editor.putString(KEY_CONTROL_WS_URL, value)
            editor.putString(KEY_CONTROL_SERVER_IP, serverIp)
        } else {
            savedWsUrlOverride = null
            serverIp = value
                .substringBefore("/")
                .substringBefore(":")
                .ifBlank { DEFAULT_CONTROL_SERVER_IP }
            editor.remove(KEY_CONTROL_WS_URL)
            editor.putString(KEY_CONTROL_SERVER_IP, serverIp)
        }

        editor.apply()
    }

    private fun handleTeacherControlConnectionFailure(message: String) {
        if (isFinishing || isDestroyed || connectionDialogShown) {
            return
        }

        connectionDialogShown = true
        teacherControlClient?.stop()
        teacherControlClient = null

        val currentValue = savedWsUrlOverride ?: serverIp
        val input = EditText(this).apply {
            setText(currentValue)
            setSelection(text.length)
            hint = "192.168.5.200 or ws://192.168.5.200:8071/vr-view-ws"
        }

        AlertDialog.Builder(this)
            .setTitle("WS address unavailable")
            .setMessage("Could not connect to teacher control:\n$message\n\nEnter IP or full WebSocket URL.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save and retry") { dialog, _ ->
                saveTeacherControlEndpoint(input.text.toString())
                connectionDialogShown = false
                connectTeacherControlClient()
                dialog.dismiss()
            }
            .setNegativeButton("Retry") { dialog, _ ->
                connectionDialogShown = false
                connectTeacherControlClient()
                dialog.dismiss()
            }
            .show()
    }

    private fun handleVideoPlaybackFailure(message: String) {
        if (isFinishing || isDestroyed || streamDialogShown) {
            return
        }

        streamDialogShown = true
        val input = EditText(this).apply {
            val currentValue = videoTexturePlayer.currentStreamUrl()
            setText(currentValue)
            setSelection(text.length)
            hint = "http://192.168.5.1:4022/udp/239.0.0.1:5004"
        }

        AlertDialog.Builder(this)
            .setTitle("Video stream unavailable")
            .setMessage("$message\n\nEnter a new stream URL.")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Save and retry") { dialog, _ ->
                val value = input.text.toString().trim().ifBlank { DEFAULT_STREAM_URL }
                saveStreamUrl(value)
                videoTexturePlayer.updateStreamUrl(value)
                streamDialogShown = false
                dialog.dismiss()
            }
            .setNegativeButton("Retry") { dialog, _ ->
                videoTexturePlayer.updateStreamUrl(videoTexturePlayer.currentStreamUrl())
                streamDialogShown = false
                dialog.dismiss()
            }
            .show()
    }

    private fun showTextMarkInsideSphere(mark: ServerTextMark) {
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

        QuestNativeBridge.nativeShowTextMark(
            id = mark.id,
            pixelsArgb = pixels,
            bitmapWidth = bitmapWidth,
            bitmapHeight = bitmapHeight,
            x = mark.x ?: Float.NaN,
            y = mark.y ?: Float.NaN,
            z = mark.z ?: Float.NaN,
            yawDeg = mark.yaw ?: Float.NaN,
            pitchDeg = mark.pitch ?: Float.NaN,
            markWidth = bitmapWidth.toFloat(),
            markHeight = bitmapHeight.toFloat(),
            durationMs = mark.durationMs.coerceAtLeast(500)
        )
    }

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
        canvas.drawRoundRect(RectF(2f, 2f, width.toFloat() - 2f, height.toFloat() - 2f), 18f, 18f, borderPaint)

        val padding = 22
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = when {
                height <= 100 -> 24f
                height <= 160 -> 30f
                else -> 36f
            }
            textAlign = Paint.Align.LEFT
        }

        val spannedText: Spanned = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(text)
        }

        val staticLayout = StaticLayout.Builder
            .obtain(spannedText, 0, spannedText.length, textPaint, width - padding * 2)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        val textTop = ((height - staticLayout.height) / 2).coerceAtLeast(padding)
        canvas.save()
        canvas.translate(padding.toFloat(), textTop.toFloat())
        staticLayout.draw(canvas)
        canvas.restore()

        return bitmap
    }
}
