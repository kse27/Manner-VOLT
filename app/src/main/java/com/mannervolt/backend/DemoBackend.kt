package com.mannervolt.backend

import android.content.Context
import android.content.Intent
import com.mannervolt.BuildConfig
import com.mannervolt.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 두 대 시연 설정 (영상 촬영용). 디버그 빌드에서만, Mac 로컬 시연 서버(에뮬레이터에서 10.0.2.2)에만 붙는다.
 * 실행: adb shell am start -n com.mannervolt/.MainActivity --es demoServer http://10.0.2.2:8787 --es demoRole A
 * 해제: --es demoServer off
 */
data class DemoConfig(val url: String, val role: Role) {
    val deviceId get() = if (role == Role.A) "demo-device-a" else "demo-device-b"

    companion object {
        @Volatile var current: DemoConfig? = null
            private set
        private val allowed = Regex("""^http://10\.0\.2\.2:\d{2,5}$""")

        fun load(context: Context, intent: Intent?) {
            val prefs = context.getSharedPreferences("mannervolt_demo", Context.MODE_PRIVATE)
            val url = intent?.getStringExtra("demoServer")
            val role = intent?.getStringExtra("demoRole")
            if (url == "off") prefs.edit().clear().apply()
            else if (url != null && role != null) prefs.edit().putString("url", url).putString("role", role).apply()
            val u = prefs.getString("url", null)
            val r = runCatching { Role.valueOf(prefs.getString("role", "") ?: "") }.getOrNull()
            current = if (BuildConfig.DEBUG && u != null && r != null && allowed.matches(u)) DemoConfig(u, r) else null
        }
    }
}

@Serializable
data class DemoCommand(val seq: Long, val action: String, val arg: JsonPrimitive? = null)

@Serializable
data class DemoSync(val now: Long, val speed: Double, val latest: Long = 0, val commands: List<DemoCommand> = emptyList())

/** 두 대 시연 서버 클라이언트. 서버는 AWS 백엔드와 같은 core.py 규칙을 쓴다. 로그인 대신 시연 기기 ID 헤더를 보낸다. */
class DemoBackend(private val config: DemoConfig) : MannerVoltBackend {
    private val base = config.url.trimEnd('/')
    override val deviceId: String get() = config.deviceId

    private suspend fun <T> call(method: String, path: String, body: String?, ser: KSerializer<T>): T = withContext(Dispatchers.IO) {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-Demo-Device", config.deviceId)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                val msg = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                    ?.let { Regex("\"message\"\\s*:\\s*\"([^\"]{1,120})\"").find(it)?.groupValues?.get(1) }
                throw BackendException(code, msg ?: "시연 서버 요청이 실패했습니다.")
            }
            Wire.json.decodeFromString(ser, conn.inputStream.bufferedReader().readText())
        } finally { conn.disconnect() }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun <T> body(v: T, ser: KSerializer<T>) = Wire.json.encodeToString(ser, v)

    @Serializable private data class Ack(val ok: Boolean = true)
    @Serializable private data class Physical(val chargerKey: String, val plugged: Boolean, val at: Long)

    /** 공유 시계와 이 차량에 온 시연 명령 */
    suspend fun sync(since: Long) = call("GET", "/demo/sync?since=$since", null, DemoSync.serializer())

    /** 공공데이터 층: 차가 실제로 꽂히거나 빠졌다 (공공 API 상태 변화와 같음). */
    suspend fun physical(chargerKey: String, plugged: Boolean, at: Long) {
        call("POST", "/demo/physical", body(Physical(chargerKey, plugged, at), Physical.serializer()), Ack.serializer())
    }

    override suspend fun board(stationId: String) = call("GET", "/v1/stations/${enc(stationId)}/board", null, StationBoard.serializer())
    override suspend fun nearby() = call("GET", "/v1/stations/nearby", null, NearbyResult.serializer())
    override suspend fun match(stationId: String, pluggedAt: Long) =
        call("GET", "/v1/stations/${enc(stationId)}/match?pluggedAt=$pluggedAt", null, MatchResult.serializer())
    override suspend fun upsertSession(req: SessionRequest) =
        call("PUT", "/v1/sessions", body(req, SessionRequest.serializer()), ChargerView.serializer())
    override suspend fun reportDeparture(report: DepartureReport) =
        call("POST", "/v1/sessions/departure", body(report, DepartureReport.serializer()), DepartureResult.serializer())
    override suspend fun joinQueue(chargerKey: String, etaAt: Long?) {
        call("POST", "/v1/chargers/${enc(chargerKey)}/queue", body(QueueRequest(etaAt), QueueRequest.serializer()), Ack.serializer())
    }
    override suspend fun leaveQueue(chargerKey: String) {
        call("DELETE", "/v1/chargers/${enc(chargerKey)}/queue", null, Ack.serializer())
    }
    override suspend fun arrivalCheck(check: ArrivalCheck) {
        call("POST", "/v1/chargers/${enc(check.chargerKey)}/arrival-check", body(check, ArrivalCheck.serializer()), Ack.serializer())
    }
    override suspend fun me() = call("GET", "/v1/me", null, Profile.serializer())
    override suspend fun events(since: Long) =
        call("GET", "/v1/events?since=${since.coerceAtLeast(0)}", null, ListSerializer(AppEvent.serializer()))
}
