package cz.mormegil.vrvideoplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

class TeacherControlClient(
    private val wsUrl: String,
    private val onLookAt: (yaw: Float, pitch: Float, radius: Float, duration: Int) -> Unit,
    private val onVideoMode: (
        inputLayout: InputLayout,
        inputMode: InputMode,
        outputMode: OutputMode
    ) -> Unit
) {
    private val tag = "TeacherControlClient"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        try {
            webSocket?.close(1000, "stop")
        } catch (_: Throwable) {
        }
        webSocket = null
    }

    private fun connect() {
        if (stopped) return

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Log.d(tag, "Connecting to $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket connected")

                /*
                 * Небольшой hello от Android-клиента.
                 * Если сервер его игнорирует — ничего страшного.
                 * Если сервер ведёт список клиентов — так проще увидеть подключение в логах.
                 */
                webSocket.send(
                    """
                    {"type":"android_hello","client":"vrvideoplayer","supports":["yaw_pitch","xyz","video_mode"]}
                    """.trimIndent()
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "WS raw message: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket failure: ${t.message}", t)
                reconnectLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code $reason")
                reconnectLater()
            }
        })
    }

    private fun reconnectLater() {
        if (stopped) return

        mainHandler.postDelayed({
            if (!stopped) connect()
        }, 2000)
    }

    private fun handleMessage(text: String) {
        try {
            Log.d(tag, "WS raw message handleMessage: $text")

            /*
             * Формат query:
             * /vr-view?yaw=90&pitch=0&radius=150&duration=1000
             * /vr-view?x=-150&y=0&z=0&radius=150&duration=1000
             * yaw=90&pitch=0
             * x=-150&y=0&z=0
             */
            if (looksLikeQuery(text)) {
                handleQueryStringMessage(text)
                return
            }

            val root = JSONObject(text)

            if (root.optString("type") == "hello") {
                Log.d(tag, "WS hello")
                return
            }

            /*
             * Сначала применяем режим видео.
             * return не делаем: в одном JSON могут прийти и режим, и yaw/pitch/x/y/z.
             */
            if (isVideoModeMessage(root)) {
                handleVideoModeMessage(root)
            }

            handleLookAtMessage(root)
        } catch (e: Exception) {
            Log.e(tag, "Bad WS message: $text", e)
        }
    }

    private fun looksLikeQuery(text: String): Boolean {
        if (text.contains("?")) return true
        if (text.contains("yaw=") && text.contains("pitch=")) return true
        if (text.contains("x=") && text.contains("y=") && text.contains("z=")) return true
        return false
    }

    private fun isVideoModeMessage(json: JSONObject): Boolean {
        if (json.optString("type") == "video_mode") return true
        if (json.has("videoMode")) return true
        if (json.has("video_projection")) return true
        if (json.has("videoProjection")) return true
        if (json.has("projection")) return true
        if (json.has("layout")) return true
        if (json.has("inputMode")) return true
        if (json.has("inputLayout")) return true
        if (json.has("outputMode")) return true
        if (json.has("output")) return true
        return false
    }

    private fun handleVideoModeMessage(root: JSONObject) {
        val videoMode = root.optString(
            "videoMode",
            root.optString(
                "video_projection",
                root.optString("videoProjection", "")
            )
        ).lowercase()

        val projection = root.optString(
            "projection",
            root.optString(
                "video_projection",
                root.optString("videoProjection", "")
            )
        ).lowercase()

        val inputModeRaw = root.optString("inputMode", "").lowercase()

        val layout = root.optString(
            "layout",
            root.optString("inputLayout", "")
        ).lowercase()

        val output = root.optString(
            "output",
            root.optString("outputMode", "")
        ).lowercase()

        val parsedInputMode = parseInputMode(videoMode, projection, inputModeRaw)
        val parsedInputLayout = parseInputLayout(videoMode, layout)
        val parsedOutputMode = parseOutputMode(output)

        Log.d(
            tag,
            "Parsed video mode inputLayout=$parsedInputLayout inputMode=$parsedInputMode outputMode=$parsedOutputMode"
        )

        mainHandler.post {
            onVideoMode(parsedInputLayout, parsedInputMode, parsedOutputMode)
        }
    }

    private fun parseInputMode(
        videoMode: String,
        projection: String,
        inputModeRaw: String
    ): InputMode {
        val value = when {
            inputModeRaw.isNotBlank() -> inputModeRaw
            projection.isNotBlank() -> projection
            else -> videoMode
        }.lowercase()

        return when (value) {
            "360", "360_mono", "360_stereo", "360-stereo", "360 stereo",
            "equirect360", "equirect_360", "equirectangular360", "equirectangular_360" ->
                InputMode.Equirect360

            "180", "180_mono", "180_stereo", "180-stereo", "180 stereo",
            "equirect180", "equirect_180", "equirectangular180", "equirectangular_180" ->
                InputMode.Equirect180

            "panorama180", "panorama_180" -> InputMode.Panorama180
            "panorama360", "panorama_360" -> InputMode.Panorama360
            "plain", "plain_fov", "flat", "2d" -> InputMode.PlainFov

            else -> {
                Log.w(tag, "Unknown inputMode/projection='$value', fallback Equirect360")
                InputMode.Equirect360
            }
        }
    }

    private fun parseInputLayout(videoMode: String, layout: String): InputLayout {
        val value = when {
            layout.isNotBlank() -> layout
            else -> videoMode
        }.lowercase()

        return when (value) {
            "mono", "360_mono", "180_mono" -> InputLayout.Mono

            "stereo", "stereo_horiz", "stereo_horizontal",
            "side_by_side", "side-by-side", "sbs",
            "360_stereo", "360-stereo", "360 stereo",
            "180_stereo", "180-stereo", "180 stereo" -> InputLayout.StereoHoriz

            "stereo_vert", "stereo_vertical",
            "top_bottom", "top-bottom", "tb",
            "over_under", "over-under", "ou" -> InputLayout.StereoVert

            "anaglyph", "anaglyph_red_cyan" -> InputLayout.AnaglyphRedCyan

            else -> {
                Log.w(tag, "Unknown layout='$value', fallback Mono")
                InputLayout.Mono
            }
        }
    }

    private fun parseOutputMode(output: String): OutputMode {
        return when (output.lowercase()) {
            "cardboard", "cardboard_stereo", "vr", "stereo" -> OutputMode.CardboardStereo
            "mono_left", "left", "mono" -> OutputMode.MonoLeft
            "mono_right", "right" -> OutputMode.MonoRight
            else -> OutputMode.CardboardStereo
        }
    }

    private fun handleLookAtMessage(root: JSONObject) {
        val json = findLookAtObject(root)

        if (json == null) {
            Log.d(tag, "WS message has no look_at yaw/pitch or x/y/z")
            return
        }

        val lookAt = parseLookAt(json)

        if (lookAt == null) {
            Log.d(tag, "WS look_at ignored: unsupported object=$json")
            return
        }

        Log.d(
            tag,
            "Parsed look_at yaw=${lookAt.yaw} pitch=${lookAt.pitch} radius=${lookAt.radius} duration=${lookAt.duration}"
        )

        mainHandler.post {
            onLookAt(lookAt.yaw, lookAt.pitch, lookAt.radius, lookAt.duration)
        }
    }

    private fun findLookAtObject(root: JSONObject): JSONObject? {
        if (hasLookAtFields(root)) return root

        val keys = listOf("data", "payload", "vrView", "view", "coords", "camera", "message")

        for (key in keys) {
            val obj = root.optJSONObject(key)
            if (hasLookAtFields(obj)) return obj
        }

        return null
    }

    private data class ParsedLookAt(
        val yaw: Float,
        val pitch: Float,
        val radius: Float,
        val duration: Int
    )

    private fun hasLookAtFields(json: JSONObject?): Boolean {
        if (json == null) return false
        if (json.has("yaw") && json.has("pitch")) return true
        if (json.has("x") && json.has("y") && json.has("z")) return true
        return false
    }

    private fun parseLookAt(json: JSONObject): ParsedLookAt? {
        val duration = json.optInt("duration", json.optInt("durationMs", 1000))

        if (json.has("yaw") && json.has("pitch")) {
            return ParsedLookAt(
                yaw = json.optDouble("yaw", 0.0).toFloat(),
                pitch = json.optDouble("pitch", 0.0).toFloat(),
                radius = json.optDouble("radius", 150.0).toFloat(),
                duration = duration
            )
        }

        if (json.has("x") && json.has("y") && json.has("z")) {
            val x = json.optDouble("x", 0.0)
            val y = json.optDouble("y", 0.0)
            val z = json.optDouble("z", 0.0)

            val vectorRadius = sqrt(x * x + y * y + z * z)
            val safeRadius = if (vectorRadius > 0.001) vectorRadius else 1.0

            /*
             * Обратное преобразование к твоему index.html:
             * x = -r * sin(yaw) * cos(pitch)
             * y = -r * sin(pitch)
             * z = -r * cos(yaw) * cos(pitch)
             */
            var yaw = Math.toDegrees(atan2(-x, -z)).toFloat()
            if (yaw < 0f) yaw += 360f

            val pitch = Math.toDegrees(
                asin((-y / safeRadius).coerceIn(-1.0, 1.0))
            ).toFloat()

            val radius = json.optDouble(
                "radius",
                if (vectorRadius > 0.001) vectorRadius else 150.0
            ).toFloat()

            return ParsedLookAt(
                yaw = yaw,
                pitch = pitch,
                radius = radius,
                duration = duration
            )
        }

        return null
    }

    private fun handleQueryStringMessage(text: String) {
        try {
            val clean = text.substringAfter("?")

            val params = clean.split("&")
                .mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = URLDecoder.decode(parts[0], "UTF-8")
                        val value = URLDecoder.decode(parts[1], "UTF-8")
                        key to value
                    } else {
                        null
                    }
                }
                .toMap()

            val json = JSONObject()

            params["yaw"]?.toDoubleOrNull()?.let { json.put("yaw", it) }
            params["pitch"]?.toDoubleOrNull()?.let { json.put("pitch", it) }
            params["x"]?.toDoubleOrNull()?.let { json.put("x", it) }
            params["y"]?.toDoubleOrNull()?.let { json.put("y", it) }
            params["z"]?.toDoubleOrNull()?.let { json.put("z", it) }
            params["radius"]?.toDoubleOrNull()?.let { json.put("radius", it) }
            params["duration"]?.toIntOrNull()?.let { json.put("duration", it) }
            params["durationMs"]?.toIntOrNull()?.let { json.put("duration", it) }

            val lookAt = parseLookAt(json)

            if (lookAt == null) {
                Log.d(tag, "Query message ignored: no yaw/pitch or x/y/z: $text")
                return
            }

            Log.d(
                tag,
                "Parsed query look_at yaw=${lookAt.yaw} pitch=${lookAt.pitch} radius=${lookAt.radius} duration=${lookAt.duration}"
            )

            mainHandler.post {
                onLookAt(lookAt.yaw, lookAt.pitch, lookAt.radius, lookAt.duration)
            }
        } catch (e: Exception) {
            Log.e(tag, "Bad query message: $text", e)
        }
    }
}
