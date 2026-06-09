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

/**
 * Текстовая метка, полученная от сервера по WebSocket.
 *
 * Серверный формат по документации:
 * {
 *   "type": "text-mark",
 *   "id": "mark1",
 *   "x": 0,
 *   "y": 0.2,
 *   "z": 1,
 *   "width": 320,
 *   "height": 120,
 *   "duration": 5000,
 *   "text": "Посмотрите сюда"
 * }
 */
data class ServerTextMark(
    val id: String,
    val text: String,
    val x: Float?,
    val y: Float?,
    val z: Float?,
    val yaw: Float?,
    val pitch: Float?,
    val width: Int,
    val height: Int,
    val durationMs: Int
)

class TeacherControlClient(
    private val wsUrl: String,
    private val onLookAt: (yaw: Float, pitch: Float, radius: Float, duration: Int) -> Unit,
    private val onVideoMode: (
        inputLayout: InputLayout,
        inputMode: InputMode,
        outputMode: OutputMode
    ) -> Unit,
    private val onTextMark: (mark: ServerTextMark) -> Unit,
    private val onTextMarkClear: () -> Unit
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

                webSocket.send(
                    """
                    {"type":"android_hello","client":"vrvideoplayer","supports":["yaw_pitch","xyz","video_mode","text-mark"]}
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

            if (looksLikeQuery(text)) {
                handleQueryStringMessage(text)
                return
            }

            val root = JSONObject(text)
            val type = root.optString("type", "").lowercase()

            if (type == "hello") {
                Log.d(tag, "WS hello")
                return
            }

            // ВАЖНО: text-mark содержит x/y/z, но это НЕ команда поворота камеры.
            // Поэтому обрабатываем его до handleLookAtMessage() и делаем return.
            if (type == "text-mark" || type == "overlay_message" || type == "message") {
                handleTextMarkMessage(root)
                return
            }

            if (type == "text-mark-clear" || type == "overlay_message_clear" || type == "message-clear") {
                Log.d(tag, "WS text mark clear")
                mainHandler.post { onTextMarkClear() }
                return
            }

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
        if (text.contains("message=") || text.contains("text=")) return true
        if (text.contains("yaw=") && text.contains("pitch=")) return true
        if (text.contains("x=") && text.contains("y=") && text.contains("z=")) return true
        return false
    }

    private fun isVideoModeMessage(json: JSONObject): Boolean {
        val type = json.optString("type", "").lowercase()
        if (type == "video_mode" || type == "video-projection") return true
        if (json.has("video_projection")) return true
        if (json.has("videoProjection")) return true
        if (json.has("videoMode")) return true
        if (json.has("projection")) return true
        if (json.has("layout")) return true
        if (json.has("inputMode")) return true
        if (json.has("inputLayout")) return true
        if (json.has("outputMode")) return true
        if (json.has("output")) return true
        return false
    }

    private fun handleVideoModeMessage(root: JSONObject) {
        val videoProjection = root.optString(
            "video_projection",
            root.optString("videoProjection", "")
        ).lowercase()

        val videoMode = root.optString("videoMode", videoProjection).lowercase()
        val projection = root.optString("projection", videoProjection).lowercase()
        val inputModeRaw = root.optString("inputMode", "").lowercase()

        val layout = root.optString(
            "layout",
            root.optString("inputLayout", videoProjection)
        ).lowercase()

        val output = root.optString(
            "output",
            root.optString("outputMode", "cardboard")
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

    private fun normalizeVideoValue(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace("-", "_")
            .replace(" ", "_")
    }

    private fun allVideoModeTokens(vararg values: String): String {
        return values
            .joinToString("_") { normalizeVideoValue(it) }
            .trim('_')
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
        }.let { normalizeVideoValue(it) }

        val all = allVideoModeTokens(videoMode, projection, inputModeRaw)

        return when {
            // 180 stereo / VR180 / equirectangular 180
            value in setOf(
                "180", "180_mono", "180_stereo", "stereo_180", "vr180",
                "equirect180", "equirect_180", "equirectangular180", "equirectangular_180",
                "equirect_180_stereo", "stereo_equirect_180"
            ) -> InputMode.Equirect180

            all.contains("180") || all.contains("vr180") -> InputMode.Equirect180

            value in setOf(
                "360", "360_mono", "360_stereo", "stereo_360", "vr360",
                "equirect360", "equirect_360", "equirectangular360", "equirectangular_360",
                "equirect_360_stereo", "stereo_equirect_360"
            ) -> InputMode.Equirect360

            all.contains("360") || all.contains("vr360") -> InputMode.Equirect360

            value in setOf("panorama180", "panorama_180") -> InputMode.Panorama180
            value in setOf("panorama360", "panorama_360") -> InputMode.Panorama360
            value in setOf("plain", "plain_fov", "flat", "2d") -> InputMode.PlainFov

            else -> {
                Log.w(tag, "Unknown inputMode/projection='$value' all='$all', fallback Equirect360")
                InputMode.Equirect360
            }
        }
    }

    private fun parseInputLayout(videoMode: String, layout: String): InputLayout {
        val value = when {
            layout.isNotBlank() -> layout
            else -> videoMode
        }.let { normalizeVideoValue(it) }

        val all = allVideoModeTokens(videoMode, layout)

        return when {
            value in setOf("anaglyph", "anaglyph_red_cyan") -> InputLayout.AnaglyphRedCyan

            /*
             * ВАЖНО ДЛЯ 360:
             * Обычный 360°-ролик должен отображаться как MONO + EQUIRECT_360 + CARDBOARD_STEREO.
             * Даже если сервер прислал строку вроде "360_stereo" без явного layout=sbs,
             * не режем картинку пополам. Иначе 360 начинает выглядеть неправильно.
             *
             * Стерео включаем только когда это явно SBS/TB или когда это VR180/180_stereo.
             */
            value in setOf("mono", "360", "360_mono", "360_stereo", "stereo_360", "vr360", "equirect_360") ->
                InputLayout.Mono

            // Vertical stereo / Top-Bottom / Over-Under
            value in setOf(
                "stereo_vert", "stereo_vertical", "vertical_stereo",
                "top_bottom", "topbottom", "tb",
                "over_under", "overunder", "ou",
                "180_tb", "180_top_bottom", "180_over_under",
                "360_tb", "360_top_bottom", "360_over_under"
            ) -> InputLayout.StereoVert

            all.contains("top_bottom") ||
                all.contains("over_under") ||
                all.endsWith("_tb") ||
                all.contains("_tb_") ||
                all.endsWith("_ou") ||
                all.contains("_ou_") -> InputLayout.StereoVert

            // Horizontal stereo / SBS. Для 180_stereo по умолчанию используем SBS.
            value in setOf(
                "stereo", "stereo_horiz", "stereo_horizontal", "horizontal_stereo",
                "side_by_side", "sidebyside", "side_by_side_full", "sbs", "lr", "left_right",
                "180_stereo", "stereo_180", "180_sbs", "sbs_180", "vr180"
            ) -> InputLayout.StereoHoriz

            all.contains("vr180") ||
                all.contains("180_stereo") ||
                all.contains("stereo_180") ||
                all.contains("side_by_side") ||
                all.contains("sidebyside") ||
                all.endsWith("_sbs") ||
                all.contains("_sbs_") -> InputLayout.StereoHoriz

            value in setOf("180_mono", "180") -> InputLayout.Mono

            else -> {
                Log.w(tag, "Unknown layout='$value' all='$all', fallback Mono")
                InputLayout.Mono
            }
        }
    }

    private fun parseOutputMode(output: String): OutputMode {
        return when (normalizeVideoValue(output)) {
            "cardboard", "cardboard_stereo", "vr", "stereo", "split", "split_screen" ->
                OutputMode.CardboardStereo

            "mono_left", "left", "mono" -> OutputMode.MonoLeft
            "mono_right", "right" -> OutputMode.MonoRight

            // Безопасный default: всегда Cardboard.
            else -> OutputMode.CardboardStereo
        }
    }

    private fun handleTextMarkMessage(root: JSONObject) {
        val rawText = root.optString(
            "text",
            root.optString("message", root.optString("html", root.optString("label", "")))
        )

        val text = cleanText(rawText)
        if (text.isBlank()) {
            Log.d(tag, "text-mark ignored: empty text")
            return
        }

        val mark = ServerTextMark(
            id = root.optString("id", "text_${System.currentTimeMillis()}"),
            text = text,
            x = root.optionalFloat("x"),
            y = root.optionalFloat("y"),
            z = root.optionalFloat("z"),
            yaw = root.optionalFloat("yaw"),
            pitch = root.optionalFloat("pitch"),
            width = root.optInt("width", 420).coerceIn(160, 1024),
            height = root.optInt("height", 140).coerceIn(80, 512),
            durationMs = root.optInt("durationMs", root.optInt("duration", 5000)).coerceAtLeast(500)
        )

        Log.d(tag, "Parsed text-mark: $mark")
        mainHandler.post { onTextMark(mark) }
    }

    private fun cleanText(value: String): String {
        return value
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .trim()
    }

    private fun JSONObject.optionalFloat(name: String): Float? {
        if (!has(name) || isNull(name)) return null
        return optDouble(name, Double.NaN).takeIf { !it.isNaN() }?.toFloat()
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
        if (json.optString("type", "").lowercase() == "text-mark") return false
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

            val textValue = params["text"] ?: params["message"]
            if (!textValue.isNullOrBlank()) {
                val mark = ServerTextMark(
                    id = params["id"] ?: "text_${System.currentTimeMillis()}",
                    text = cleanText(textValue),
                    x = params["x"]?.toFloatOrNull(),
                    y = params["y"]?.toFloatOrNull(),
                    z = params["z"]?.toFloatOrNull(),
                    yaw = params["yaw"]?.toFloatOrNull(),
                    pitch = params["pitch"]?.toFloatOrNull(),
                    width = params["width"]?.toIntOrNull() ?: 420,
                    height = params["height"]?.toIntOrNull() ?: 140,
                    durationMs = params["durationMs"]?.toIntOrNull()
                        ?: params["duration"]?.toIntOrNull()
                        ?: 5000
                )
                mainHandler.post { onTextMark(mark) }
                return
            }

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
