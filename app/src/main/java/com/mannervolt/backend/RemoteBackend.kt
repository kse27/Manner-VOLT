package com.mannervolt.backend

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * AWS API Gateway 백엔드 클라이언트.
 * - HTTPS만 허용 (SECURITY-01), 기기 등록 후 받은 서명 토큰을 매 요청에 보낸다 (SECURITY-08).
 * - 익명 ID는 서버가 만든 무작위 UUID이며 VIN이나 번호판을 쓰지 않는다 (NFR-2).
 */
class RemoteBackend(context: Context, baseUrl: String) : MannerVoltBackend {
    private val base = baseUrl.trimEnd('/')
    private val prefs = context.applicationContext.getSharedPreferences("mannervolt_device", Context.MODE_PRIVATE)
    private val lock = Mutex()

    init { require(base.startsWith("https://")) { "API_BASE_URL must use https" } }

    /** 서버가 발급한 무작위 기기 ID (등록 전에는 빈 값). */
    override val deviceId: String get() = prefs.getString(KEY_ID, null) ?: ""

    private suspend fun token(): String = lock.withLock {
        val t = prefs.getString(KEY_TOKEN, null)
        val exp = prefs.getLong(KEY_EXP, 0)
        val now = System.currentTimeMillis()
        if (t != null && exp > now + REFRESH_BEFORE_MS) return t
        val reg = if (t != null && exp > now + 60_000) {
            call("POST", "/v1/devices/refresh", "{}", DeviceRegistration.serializer(), auth = t)
        } else {
            call("POST", "/v1/devices", "{}", DeviceRegistration.serializer(), auth = null)
        }
        prefs.edit().putString(KEY_ID, reg.deviceId).putString(KEY_TOKEN, reg.token).putLong(KEY_EXP, reg.expiresAt).apply()
        reg.token
    }

    private suspend fun <T> call(method: String, path: String, body: String?, ser: KSerializer<T>, auth: String? = ""): T =
        withContext(Dispatchers.IO) {
            val bearer = when { auth == null -> null; auth.isNotEmpty() -> auth; else -> token() }
            val conn = (URL(base + path).openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = 8_000; readTimeout = 8_000
                setRequestProperty("Accept", "application/json")
                bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    outputStream.use { it.write(body.toByteArray()) }
                }
            }
            try {
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED) prefs.edit().remove(KEY_TOKEN).apply()
                if (code !in 200..299) {
                    val msg = runCatching { conn.errorStream?.bufferedReader()?.readText() }.getOrNull()
                        ?.let { Regex("\"message\"\\s*:\\s*\"([^\"]{1,120})\"").find(it)?.groupValues?.get(1) }
                    throw BackendException(code, msg ?: "서버 요청이 실패했습니다.")
                }
                val text = conn.inputStream.bufferedReader().readText()
                Wire.json.decodeFromString(ser, text)
            } finally { conn.disconnect() }
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun <T> body(v: T, ser: KSerializer<T>) = Wire.json.encodeToString(ser, v)

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

    @kotlinx.serialization.Serializable
    data class Ack(val ok: Boolean = true)

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_TOKEN = "token"
        const val KEY_EXP = "token_exp"
        const val REFRESH_BEFORE_MS = 3L * 24 * 3600 * 1000
    }
}
