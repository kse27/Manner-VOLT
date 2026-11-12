package com.mannervolt.vehicle

/** 차량 상태 한 시점. 좌표는 기기 안에서만 쓰고 서버로 보내지 않는다 (FR-4.4, NFR-2). */
data class VehicleSnapshot(
    val socPercent: Double,
    val targetPercent: Int,
    val batteryCapacityKwh: Double,
    val plugged: Boolean,
    val charging: Boolean,
    val chargerPowerKw: Double,
    val outsideTempC: Double,
    val underground: Boolean,
    val lat: Double,
    val lon: Double,
    val gear: String = "P",
    /** 시뮬레이터 전용: 실제 충전 출력 ÷ 충전기 출력. 1보다 작으면 예상보다 느리게 충전된다 (FR-9 시연). */
    val powerFactor: Double = 1.0,
)

/** Pleos Vehicle SDK에서 읽은 값. null 은 미지원 또는 아직 수신 전. */
data class PleosReadings(
    val connected: Boolean? = null,
    val socPercent: Int? = null,
    val chargePortOpen: Boolean? = null,
    val chargeSwitchOn: Boolean? = null,
    val gear: String? = null,
    val lastError: String? = null,
)

/** 차량 데이터 접근 인터페이스 (NFR-5). Pleos 구현과 시뮬레이터 구현을 교체할 수 있다. */
interface VehicleDataSource {
    fun start()
    fun stop()
}
