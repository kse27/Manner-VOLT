package com.mannervolt.backend

import com.mannervolt.domain.DepartureVerdict
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 앱과 백엔드가 주고받는 데이터. 모든 시각은 epoch 밀리초. */

@Serializable
enum class ChargerStatus { AVAILABLE, IN_USE, OUT_OF_SERVICE, UNKNOWN }

@Serializable
enum class DataSource { LIVE, CACHE, SAMPLE }

@Serializable
data class Slot(val row: Int, val col: Int)

@Serializable
data class ChargerView(
    /** 공공 API statId + "-" + chgerId (FR-7.2) */
    val chargerKey: String,
    val label: String,
    val typeLabel: String,
    val powerKw: Double,
    val status: ChargerStatus,
    val slot: Slot,
    val chargingStartedAt: Long? = null,
    val predictedCompletionAt: Long? = null,
    val plannedDepartureAt: Long? = null,
    /** false 이면 공공 API의 충전 시작 시각만 있고 출차 예정은 미공유 (FR-2.3) */
    val planShared: Boolean = false,
    val queueSize: Int = 0,
    val myQueuePosition: Int? = null,
    val isMine: Boolean = false,
    val departure: DepartureVerdict = DepartureVerdict.PARKED,
    val awaitingArrivalCheck: Boolean = false,
    /** 약속을 공유한 차량의 매너볼트. 차량 번호는 보여 주지 않는다 (FR-13.1). */
    val ownerScore: Int? = null,
    /** 약속이 없는 충전기의 완료 추정 (공공데이터 충전 시작 기준, FR-14) */
    val estimatedCompletionAt: Long? = null,
    /** 내 충전일 때 사용한 연장 횟수 (FR-12.3) */
    val extensionsUsed: Int = 0,
)

@Serializable
data class StationBoard(
    val stationId: String,
    val name: String,
    val underground: Boolean,
    val rows: Int,
    val cols: Int,
    val chargers: List<ChargerView>,
    val dataAsOf: Long,
    val source: DataSource,
)

@Serializable
data class ChargerBrief(
    val chargerKey: String,
    val typeLabel: String,
    val outputKw: Double,
    val status: ChargerStatus,
    val nowChargingStartedAt: Long? = null,
)

@Serializable
data class StationSummary(
    val statId: String,
    val name: String,
    val address: String,
    val operator: String,
    val useTime: String = "",
    val distanceLabel: String = "",
    val chargers: List<ChargerBrief>,
    /** 충전소 좌표 (공공 API lat/lng). 지도 표시에만 쓴다. */
    val lat: Double? = null,
    val lon: Double? = null,
)

/** 지도 기준점이 되는 단지 충전소 위치. */
@Serializable
data class StationPin(val statId: String, val name: String, val lat: Double, val lon: Double)

@Serializable
data class NearbyResult(
    val stations: List<StationSummary>,
    val dataAsOf: Long,
    val source: DataSource,
    val home: StationPin? = null,
)

/** USER: 운전자가 정한 변경, RECALC: 실제 충전 속도로 완료 시각이 바뀌어 맞춘 변경 (연장 횟수에 넣지 않음, FR-9.3) */
@Serializable
enum class ChangeReason { USER, RECALC }

@Serializable
data class SessionRequest(
    val chargerKey: String,
    val chargingStartedAt: Long,
    val predictedCompletionAt: Long,
    val plannedDepartureAt: Long,
    val reason: ChangeReason = ChangeReason.USER,
)

/** 충전기 자동 매칭 결과 (FR-8): 꽂은 시각 ± N분 안에 '충전 중'으로 바뀐, 약속 없는 충전기 */
@Serializable
data class MatchResult(val candidates: List<ChargerView>, val windowMinutes: Int)

@Serializable
data class QueueRequest(val etaAt: Long? = null)

@Serializable
data class DepartureReport(
    val chargerKey: String,
    val chargingEnded: Boolean,
    val unplugged: Boolean,
    val leftZone: Boolean,
    val observedAt: Long,
    /** 기어 D 또는 R (FR-12.1) */
    val gearMoving: Boolean = false,
)

@Serializable
data class DepartureResult(val verdict: DepartureVerdict, val scoreBefore: Int, val scoreAfter: Int)

@Serializable
enum class Observation { EMPTY, OCCUPIED }

@Serializable
data class ArrivalCheck(val chargerKey: String, val observed: Observation)

@Serializable
data class HistoryEntry(
    val chargerKey: String,
    val completionAt: Long,
    val promisedAt: Long,
    val actualAt: Long,
    val scoreBefore: Int,
    val scoreAfter: Int,
)

@Serializable
data class Profile(
    val deviceId: String,
    val score: Int,
    val history: List<HistoryEntry>,
)

@Serializable
enum class EventType {
    PRE_DEPARTURE, DEPARTURE_CHANGED, CHARGE_COMPLETE, CHARGER_AVAILABLE,
    GRACE_EXPIRED, SCORE_CHANGED, ARRIVAL_CONFIRMED, MISMATCH_RECORDED,
    QUEUE_CANCELLED,
}

@Serializable
data class AppEvent(
    val id: String,
    val at: Long,
    val type: EventType,
    val title: String,
    val body: String,
    val chargerKey: String? = null,
    val alternatives: List<String> = emptyList(),
)

@Serializable
data class DeviceRegistration(val deviceId: String, val token: String, val expiresAt: Long)

object Wire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
}

/** 백엔드 오류. 사용자에게 보여 줄 일반 메시지만 담는다 (SECURITY-09). */
class BackendException(val code: Int, message: String) : Exception(message)
